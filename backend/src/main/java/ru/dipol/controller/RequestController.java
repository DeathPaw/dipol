package ru.dipol.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.dto.PageResponse;
import ru.dipol.model.*;
import ru.dipol.repository.ParameterRepository;
import ru.dipol.repository.RequestRepository;
import ru.dipol.service.AuditService;
import ru.dipol.service.RequestService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final RequestRepository repo;
    private final RequestService service;
    private final ParameterRepository parameters;
    private final AuditService audit;

    public RequestController(RequestRepository repo, RequestService service,
                             ParameterRepository parameters, AuditService audit) {
        this.repo = repo;
        this.service = service;
        this.parameters = parameters;
        this.audit = audit;
    }

    public record CreateRequestBody(
            @NotNull Long clientId,
            @NotNull Long projectId,
            @NotNull Long buildingId,
            Long zheuId,
            String externalNumber,
            String externalSystem,
            String sourceCode,
            String apartment,
            Integer entrance,
            String placeNote,
            LocalDateTime receivedAtCustomer,
            LocalDateTime receivedAt,
            LocalDate plannedDate,
            String urgency,
            Boolean isWarranty,
            Long parentRequestId,
            String linkType,
            String financialClass,
            String description,
            String actWording,
            String internalComment,
            Long responsibleEmployeeId,
            Long contactId,
            String contactAdhocName,
            String contactAdhocPhone
    ) {
        Request toModel() {
            return new Request(null, null, null, externalNumber, externalSystem, sourceCode,
                    clientId, null, projectId, null, null, null, buildingId, null, null, zheuId, null,
                    apartment, entrance, false, null, null, placeNote,
                    receivedAtCustomer, receivedAt, plannedDate, null, null,
                    urgency, Boolean.TRUE.equals(isWarranty), parentRequestId, linkType, null,
                    "CREATED", null, financialClass, false, false, null, null,
                    0, null, description, actWording, internalComment,
                    responsibleEmployeeId, null, contactId, contactAdhocName, contactAdhocPhone,
                    null, null, null, null, null);
        }
    }

    public record UpdateRequestBody(
            Long clientId, Long projectId, Long buildingId, Long zheuId,
            String externalNumber, String externalSystem, String sourceCode,
            String apartment, Integer entrance, String placeNote,
            LocalDateTime receivedAtCustomer, LocalDate plannedDate, LocalDateTime doneAt,
            LocalDate customerAcceptedAt, String urgency, Boolean isWarranty,
            String financialClass, Boolean customerClosed,
            String description, String actWording, String internalComment,
            Long responsibleEmployeeId, Long contactId,
            String contactAdhocName, String contactAdhocPhone,
            String reportLink, String photoLink, String reportText
    ) {
        Request toModel(Request current) {
            return new Request(null, current.number(), current.seqInProject(),
                    externalNumber, externalSystem, sourceCode,
                    clientId != null ? clientId : current.clientId(), null,
                    projectId != null ? projectId : current.projectId(), null, null, null,
                    buildingId != null ? buildingId : current.buildingId(), null, null, zheuId, null,
                    apartment, entrance, current.entranceIsManual(), current.entranceAuto(),
                    current.entranceFixReason(), placeNote,
                    receivedAtCustomer, current.receivedAt(), plannedDate, doneAt, customerAcceptedAt,
                    urgency != null ? urgency : current.urgency(),
                    isWarranty != null ? isWarranty : current.isWarranty(),
                    current.parentRequestId(), current.linkType(), current.linkReason(),
                    current.status(), null, financialClass,
                    customerClosed != null ? customerClosed : current.customerClosed(),
                    current.isClosed(), current.closedAt(), current.cancelReason(),
                    current.priority(), current.priorityReason(),
                    description, actWording, internalComment,
                    responsibleEmployeeId, null, contactId, contactAdhocName, contactAdhocPhone,
                    reportLink, photoLink, reportText, current.createdBy(), current.createdAt());
        }
    }

    @GetMapping
    public PageResponse<Request> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<Long> projectIds,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) List<String> financialClasses,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long zheuId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) Long responsibleId,
            @RequestParam(required = false) String urgency,
            @RequestParam(required = false) Boolean isWarranty,
            @RequestParam(required = false) Boolean customerClosed,
            @RequestParam(required = false) Boolean isClosed,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String dateField,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) Boolean unplanned,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var filter = new RequestRepository.Filter(q, projectIds, statuses, financialClasses,
                buildingId, zheuId, clientId, responsibleId, urgency, isWarranty, customerClosed,
                isClosed, dateFrom, dateTo, dateField, overdue, unplanned);
        return PageResponse.of(repo.search(filter, size, page * size), repo.count(filter), page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Request r = service.get(id);
        BigDecimal limit = parameters.currentNumber("AB_LIMIT_M2");
        return Map.ofEntries(
                Map.entry("request", r),
                Map.entry("items", repo.items(id)),
                Map.entry("work_objects", repo.workObjects(id)),
                Map.entry("events", repo.events(id)),
                Map.entry("status_history", repo.statusHistory(id)),
                Map.entry("checklist", repo.checklist(id)),
                Map.entry("linked", repo.linkedRequests(id)),
                Map.entry("trips", repo.trips(id)),
                Map.entry("warnings", service.warnings(id, limit))
        );
    }

    @PostMapping
    public Request create(@Valid @RequestBody CreateRequestBody body) {
        return service.create(body.toModel());
    }

    @PutMapping("/{id}")
    public Request update(@PathVariable Long id, @RequestBody UpdateRequestBody body) {
        Request current = service.get(id);
        return service.update(id, body.toModel(current));
    }

    @PostMapping("/{id}/status")
    public Request changeStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return service.changeStatus(id, String.valueOf(body.get("status")),
                (String) body.get("reason"), Boolean.TRUE.equals(body.get("override")));
    }

    @PostMapping("/{id}/wait")
    public List<RequestEvent> registerWait(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.registerWait(id, body.get("reason_code"), body.get("comment"));
        return repo.events(id);
    }

    @PostMapping("/{id}/comments")
    public List<RequestEvent> addComment(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.addComment(id, body.get("text"));
        return repo.events(id);
    }

    @PostMapping("/{id}/close")
    public Map<String, Object> close(@PathVariable Long id) {
        return service.close(id);
    }

    @PutMapping("/{id}/checklist/{itemCode}")
    public List<ChecklistItem> setChecklistItem(@PathVariable Long id, @PathVariable String itemCode,
                                                @RequestBody Map<String, Object> body) {
        service.setChecklistItem(id, itemCode, Boolean.TRUE.equals(body.get("is_done")),
                (String) body.get("skipped_reason"));
        return repo.checklist(id);
    }

    /** Повторная/гарантийная заявка как новая запись со ссылкой на исходную. */
    @PostMapping("/{id}/linked")
    public Request createLinked(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return service.createLinked(id, body.getOrDefault("link_type", "REPEAT"),
                body.get("reason"), body.get("description"));
    }

    @PostMapping("/{id}/entrance")
    public Request fixEntrance(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer entrance = body.get("entrance") != null
                ? Integer.valueOf(String.valueOf(body.get("entrance"))) : null;
        return service.fixEntrance(id, entrance, (String) body.get("reason"),
                Boolean.TRUE.equals(body.get("make_permanent")));
    }

    @PostMapping("/{id}/priority")
    public Request setPriority(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        service.setPriority(id, Integer.parseInt(String.valueOf(body.get("priority"))),
                (String) body.get("reason"));
        return service.get(id);
    }

    // ---- позиции ------------------------------------------------------------

    public record ItemBody(
            Long workObjectId, @NotNull Long skuId, String unitCode,
            BigDecimal plannedQty, BigDecimal declaredQty, BigDecimal factQty,
            BigDecimal remeasuredQty, BigDecimal acceptedQty, BigDecimal manHours,
            BigDecimal referenceQty, String referenceUnitCode,
            String repairType, String status, String note, Integer sortOrder,
            List<Long> assigneeIds
    ) {
        RequestItem toModel(Long requestId) {
            return new RequestItem(null, requestId, workObjectId, null, skuId, null, null,
                    unitCode, plannedQty, declaredQty, factQty, remeasuredQty, acceptedQty,
                    manHours, referenceQty, referenceUnitCode, repairType,
                    status != null ? status : "PLANNED", null, null, null, note,
                    sortOrder != null ? sortOrder : 100, assigneeIds);
        }
    }

    @GetMapping("/{id}/items")
    public List<RequestItem> items(@PathVariable Long id) {
        return repo.items(id);
    }

    @PostMapping("/{id}/items")
    public List<RequestItem> addItem(@PathVariable Long id, @Valid @RequestBody ItemBody body) {
        service.get(id);
        RequestItem created = repo.insertItem(body.toModel(id));
        audit.action("REQUEST", id, "ITEM_ADD", "работа id=" + body.skuId());
        repo.addEvent(id, "QTY", "Добавлена позиция: " + created.skuName(), null, null, CurrentUser.name());
        return repo.items(id);
    }

    @PutMapping("/items/{itemId}")
    public List<RequestItem> updateItem(@PathVariable Long itemId, @Valid @RequestBody ItemBody body) {
        RequestItem before = repo.item(itemId)
                .orElseThrow(() -> new ru.dipol.exception.EntityNotFoundException("Позиция", itemId));
        RequestItem after = repo.updateItem(itemId, body.toModel(before.requestId()));
        audit.changed("REQUEST_ITEM", itemId, "fact_qty", before.factQty(), after.factQty());
        audit.changed("REQUEST_ITEM", itemId, "accepted_qty", before.acceptedQty(), after.acceptedQty());
        audit.changed("REQUEST_ITEM", itemId, "man_hours", before.manHours(), after.manHours());
        return repo.items(before.requestId());
    }

    @DeleteMapping("/items/{itemId}")
    public List<RequestItem> deleteItem(@PathVariable Long itemId) {
        RequestItem item = repo.item(itemId)
                .orElseThrow(() -> new ru.dipol.exception.EntityNotFoundException("Позиция", itemId));
        repo.deleteItem(itemId);
        audit.action("REQUEST", item.requestId(), "ITEM_DELETE", item.skuName());
        return repo.items(item.requestId());
    }

    // ---- объекты работ ------------------------------------------------------

    @PostMapping("/{id}/work-objects")
    public List<Map<String, Object>> addWorkObject(@PathVariable Long id,
                                                   @RequestBody Map<String, String> body) {
        repo.addWorkObject(id, body.get("object_type"), body.get("name"), body.get("note"));
        return repo.workObjects(id);
    }
}
