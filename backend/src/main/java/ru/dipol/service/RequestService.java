package ru.dipol.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.exception.ConflictException;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.model.*;
import ru.dipol.repository.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Жизненный цикл заявки.
 *
 * <p>Статусная схема проверяется по конфигурации проекта, а не по константе в
 * коде: разные направления могут сужать переходы. Обход правила разрешён
 * отдельным правом и всегда попадает в журнал (US-B5).
 */
@Service
public class RequestService {

    private final RequestRepository repo;
    private final ProjectRepository projects;
    private final ProjectConfigRepository configs;
    private final BuildingRepository buildings;
    private final BuildingService buildingService;
    private final PermissionService permissions;
    private final AuditService audit;

    public RequestService(RequestRepository repo, ProjectRepository projects,
                          ProjectConfigRepository configs, BuildingRepository buildings,
                          BuildingService buildingService, PermissionService permissions,
                          AuditService audit) {
        this.repo = repo;
        this.projects = projects;
        this.configs = configs;
        this.buildings = buildings;
        this.buildingService = buildingService;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional
    public Request create(Request draft) {
        Project project = projects.findById(draft.projectId())
                .orElseThrow(() -> new EntityNotFoundException("Проект", draft.projectId()));
        if ("CLOSED".equals(project.status())) {
            throw new BusinessRuleException(
                    "Проект «" + project.name() + "» закрыт — новые заявки в него не создаются");
        }
        Building building = buildings.findById(draft.buildingId())
                .orElseThrow(() -> new EntityNotFoundException("Дом", draft.buildingId()));

        // Внешняя заявка не должна задваиваться при повторном импорте (US-B1).
        if (draft.externalNumber() != null && !draft.externalNumber().isBlank()) {
            var existing = repo.findByExternal(draft.externalSystem(), draft.externalNumber());
            if (existing.isPresent()) {
                throw new ConflictException("Заявка с внешним номером " + draft.externalNumber()
                        + " уже есть: " + existing.get().number());
            }
        }

        LocalDateTime received = draft.receivedAt() != null ? draft.receivedAt() : LocalDateTime.now();
        int year = received.getYear();
        int seq = repo.nextSeq(project.id(), year);
        String number = project.code() + "_" + year + "_" + seq;

        Integer apartmentNo = parseApartment(draft.apartment());
        var guess = apartmentNo != null
                ? buildingService.entranceFor(building.id(), apartmentNo)
                : new BuildingService.EntranceGuess(null, "UNKNOWN", false);
        Integer entrance = draft.entrance() != null ? draft.entrance() : guess.entrance();

        Request toInsert = withDefaults(draft, number, seq, received, building,
                entrance, guess.entrance(), draft.entrance() != null);
        Request created = repo.insert(toInsert);

        repo.initChecklist(created.id(), project.configId());
        repo.addStatusChange(created.id(), null, created.status(), "Создание заявки", false, CurrentUser.name());
        repo.addEvent(created.id(), "CREATED", "Заявка создана", null, null, CurrentUser.name());
        audit.created("REQUEST", created.id());
        return created;
    }

    @Transactional
    public Request update(Long id, Request draft) {
        Request before = get(id);
        assertEditable(before);
        Request updated = repo.update(id, draft);
        audit.diff("REQUEST", id,
                Map.of("apartment", before.apartment(), "description", before.description(),
                       "planned_date", before.plannedDate(), "urgency", before.urgency(),
                       "financial_class", before.financialClass(),
                       "responsible", before.responsibleEmployeeId()),
                Map.of("apartment", updated.apartment(), "description", updated.description(),
                       "planned_date", updated.plannedDate(), "urgency", updated.urgency(),
                       "financial_class", updated.financialClass(),
                       "responsible", updated.responsibleEmployeeId()));
        return updated;
    }

    /**
     * Смена статуса. Обход недопустимого перехода возможен только с правом
     * REQUEST_STATUS_OVERRIDE и помечается в истории как override.
     */
    @Transactional
    public Request changeStatus(Long id, String toStatus, String reason, boolean override) {
        Request r = get(id);
        if (r.status().equals(toStatus)) return r;

        boolean allowed = configs.transitionAllowed(r.configId(), r.status(), toStatus);
        if (!allowed) {
            if (!override) {
                throw new BusinessRuleException("Переход «" + r.statusName() + "» → «" + toStatus
                        + "» не разрешён конфигурацией проекта");
            }
            permissions.require("REQUEST_STATUS_OVERRIDE",
                    "Обход статусной схемы доступен только с правом «Обход правил перехода статуса»");
            if (reason == null || reason.isBlank()) {
                throw new BusinessRuleException("Обход правила перехода требует причины");
            }
        }
        if ("CANCELLED".equals(toStatus) && (reason == null || reason.isBlank())) {
            throw new BusinessRuleException("Отмена заявки требует указания причины");
        }

        repo.setStatus(id, toStatus);
        if ("CANCELLED".equals(toStatus)) repo.setCancelReason(id, reason);
        // «Выполнена» фиксирует момент физического завершения работ — это не то
        // же самое, что приёмка заказчиком и не то же, что акт.
        if ("DONE".equals(toStatus) && r.doneAt() == null) repo.setDoneAt(id, LocalDateTime.now());

        repo.addStatusChange(id, r.status(), toStatus, reason, !allowed, CurrentUser.name());
        repo.addEvent(id, "STATUS", "Статус: " + r.status() + " → " + toStatus, null, null, CurrentUser.name());
        audit.status("REQUEST", id, r.status(), toStatus, reason);
        return repo.findById(id).orElseThrow();
    }

    /** Ожидание — событие с причиной, а не отдельный статус заявки (US-B5, US-D2). */
    @Transactional
    public void registerWait(Long id, String reasonCode, String comment) {
        get(id);
        repo.addEvent(id, "WAIT", comment, reasonCode, null, CurrentUser.name());
        audit.action("REQUEST", id, "WAIT", reasonCode + (comment != null ? ": " + comment : ""));
    }

    @Transactional
    public void addComment(Long id, String text) {
        get(id);
        repo.addEvent(id, "COMMENT", text, null, null, CurrentUser.name());
    }

    /**
     * Операционное закрытие по чек-листу. Пропуск обязательного пункта возможен
     * только с явной причиной — она останется в истории (US-K5).
     */
    @Transactional
    public Map<String, Object> close(Long id) {
        Request r = get(id);
        List<String> pending = repo.pendingChecklist(id);
        if (!pending.isEmpty()) {
            return Map.of("status", "BLOCKED", "pending", pending);
        }
        repo.setClosed(id, true);
        repo.addEvent(id, "COMMENT", "Заявка закрыта операционно", null, null, CurrentUser.name());
        audit.action("REQUEST", id, "CLOSE", null);
        return Map.of("status", "CLOSED", "request", repo.findById(id).orElseThrow());
    }

    @Transactional
    public void setChecklistItem(Long id, String itemCode, boolean done, String skippedReason) {
        get(id);
        repo.setChecklistItem(id, itemCode, done, skippedReason, CurrentUser.name());
        if (skippedReason != null && !skippedReason.isBlank()) {
            audit.action("REQUEST", id, "CHECKLIST_SKIP", itemCode + ": " + skippedReason);
        }
    }

    /**
     * Повторная или гарантийная заявка — всегда НОВАЯ запись со ссылкой на
     * исходную. Закрытый факт не переписывается (US-B7).
     */
    @Transactional
    public Request createLinked(Long sourceId, String linkType, String reason, String description) {
        Request source = get(sourceId);
        Request draft = new Request(null, null, null, null, null, source.sourceCode(),
                source.clientId(), null, source.projectId(), null, null, null,
                source.buildingId(), null, null, source.zheuId(), null,
                source.apartment(), source.entrance(), false, null, null, source.placeNote(),
                null, LocalDateTime.now(), null, null, null,
                source.urgency(), "WARRANTY".equals(linkType),
                sourceId, linkType, reason,
                "CREATED", null, source.financialClass(), false, false, null, null,
                source.priority(), null,
                description != null ? description : source.description(), null, null,
                source.responsibleEmployeeId(), null, source.contactId(),
                source.contactAdhocName(), source.contactAdhocPhone(),
                null, null, null, CurrentUser.name(), null);
        Request created = create(draft);
        repo.addEvent(sourceId, "COMMENT",
                "Создана связанная заявка " + created.number() + " (" + linkType + ")",
                null, null, CurrentUser.name());
        return created;
    }

    /** Ручное исправление подъезда: исходное значение и причина сохраняются (US-F2). */
    @Transactional
    public Request fixEntrance(Long id, Integer entrance, String reason, boolean makePermanent) {
        Request r = get(id);
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Исправление подъезда требует причины");
        }
        Request updated = repo.update(id, new Request(null, r.number(), r.seqInProject(),
                r.externalNumber(), r.externalSystem(), r.sourceCode(), r.clientId(), null,
                r.projectId(), null, null, null, r.buildingId(), null, null, r.zheuId(), null,
                r.apartment(), entrance, true, r.entranceAuto() != null ? r.entranceAuto() : r.entrance(),
                reason, r.placeNote(), r.receivedAtCustomer(), r.receivedAt(), r.plannedDate(),
                r.doneAt(), r.customerAcceptedAt(), r.urgency(), r.isWarranty(), r.parentRequestId(),
                r.linkType(), r.linkReason(), r.status(), null, r.financialClass(), r.customerClosed(),
                r.isClosed(), r.closedAt(), r.cancelReason(), r.priority(), r.priorityReason(),
                r.description(), r.actWording(), r.internalComment(), r.responsibleEmployeeId(), null,
                r.contactId(), r.contactAdhocName(), r.contactAdhocPhone(), r.reportLink(),
                r.photoLink(), r.reportText(), r.createdBy(), r.createdAt()));
        audit.changed("REQUEST", id, "entrance", r.entrance(), entrance);
        repo.addEvent(id, "COMMENT", "Подъезд исправлен на " + entrance + ": " + reason,
                null, null, CurrentUser.name());

        if (makePermanent) {
            // Исправление становится правилом дома — следующая заявка по этой
            // квартире получит верный подъезд автоматически.
            Integer apt = parseApartment(r.apartment());
            if (apt != null) {
                buildings.saveEntrance(r.buildingId(),
                        new BuildingEntrance(null, r.buildingId(), entrance, apt, apt, null,
                                "Из заявки " + r.number() + ": " + reason));
            }
        }
        return updated;
    }

    /** Приоритет меняется только с обязательным комментарием (US-C5). */
    @Transactional
    public void setPriority(Long id, int priority, String reason) {
        Request r = get(id);
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Изменение приоритета требует основания");
        }
        repo.update(id, copyWithPriority(r, priority, reason));
        audit.changed("REQUEST", id, "priority", r.priority(), priority);
        repo.addEvent(id, "COMMENT", "Приоритет: " + priority + " — " + reason, null, null, CurrentUser.name());
    }

    public Request get(Long id) {
        return repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Заявка", id));
    }

    /**
     * Информационное предупреждение о превышении лимита локремонта.
     * Работу не блокирует — только подсвечивает (US-E2).
     */
    public List<String> warnings(Long id, BigDecimal limitM2) {
        Request r = get(id);
        List<String> warnings = new java.util.ArrayList<>();
        if (limitM2 != null) {
            BigDecimal areaTotal = repo.items(id).stream()
                    .filter(i -> "M2".equals(i.unitCode()))
                    .map(i -> firstNonNull(i.acceptedQty(), i.factQty(), i.declaredQty(), i.plannedQty()))
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (areaTotal.compareTo(limitM2) > 0) {
                warnings.add("Объём " + areaTotal.stripTrailingZeros().toPlainString()
                        + " м² превышает ориентировочный лимит " + limitM2.stripTrailingZeros().toPlainString() + " м²");
            }
        }
        if (r.isWarranty() && r.parentRequestId() == null) {
            warnings.add("Гарантийная заявка без ссылки на исходную");
        }
        return warnings;
    }

    private void assertEditable(Request r) {
        if ("ACT_SIGNED".equals(r.status())) {
            throw new BusinessRuleException(
                    "Заявка с подписанным актом не редактируется: нужна корректировка с правом и журналом");
        }
    }

    private static Integer parseApartment(String apartment) {
        if (apartment == null) return null;
        String digits = apartment.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal v : values) if (v != null) return v;
        return null;
    }

    private Request withDefaults(Request d, String number, int seq, LocalDateTime received,
                                 Building building, Integer entrance, Integer entranceAuto,
                                 boolean entranceManual) {
        return new Request(null, number, seq, d.externalNumber(), d.externalSystem(), d.sourceCode(),
                d.clientId(), null, d.projectId(), null, null, null,
                building.id(), null, null,
                d.zheuId() != null ? d.zheuId() : building.zheuId(), null,
                d.apartment(), entrance, entranceManual, entranceAuto, d.entranceFixReason(), d.placeNote(),
                d.receivedAtCustomer(), received, d.plannedDate(), d.doneAt(), d.customerAcceptedAt(),
                d.urgency() != null ? d.urgency() : "PLANNED", d.isWarranty(),
                d.parentRequestId(), d.linkType(), d.linkReason(),
                d.status() != null ? d.status() : "CREATED", null, d.financialClass(),
                d.customerClosed(), false, null, null,
                d.priority(), d.priorityReason(),
                d.description(), d.actWording(), d.internalComment(),
                d.responsibleEmployeeId(), null, d.contactId(), d.contactAdhocName(), d.contactAdhocPhone(),
                d.reportLink(), d.photoLink(), d.reportText(),
                CurrentUser.name(), null);
    }

    private Request copyWithPriority(Request r, int priority, String reason) {
        return new Request(null, r.number(), r.seqInProject(), r.externalNumber(), r.externalSystem(),
                r.sourceCode(), r.clientId(), null, r.projectId(), null, null, null, r.buildingId(),
                null, null, r.zheuId(), null, r.apartment(), r.entrance(), r.entranceIsManual(),
                r.entranceAuto(), r.entranceFixReason(), r.placeNote(), r.receivedAtCustomer(),
                r.receivedAt(), r.plannedDate(), r.doneAt(), r.customerAcceptedAt(), r.urgency(),
                r.isWarranty(), r.parentRequestId(), r.linkType(), r.linkReason(), r.status(), null,
                r.financialClass(), r.customerClosed(), r.isClosed(), r.closedAt(), r.cancelReason(),
                priority, reason, r.description(), r.actWording(), r.internalComment(),
                r.responsibleEmployeeId(), null, r.contactId(), r.contactAdhocName(),
                r.contactAdhocPhone(), r.reportLink(), r.photoLink(), r.reportText(),
                r.createdBy(), r.createdAt());
    }
}
