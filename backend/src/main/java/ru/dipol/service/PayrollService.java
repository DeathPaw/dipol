package ru.dipol.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.model.Employee;
import ru.dipol.model.Request;
import ru.dipol.model.RequestItem;
import ru.dipol.repository.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Расчёт ФОТ от принятого факта.
 *
 * <p>Главное правило модели (уточнение владельца процесса от 30.08.2026):
 * схема оплаты принадлежит связке «сотрудник + проект + период», а не проекту.
 * Поэтому одна и та же работа даёт сдельное начисление одному участнику и
 * ничего не даёт другому, у которого схема «за смену» — его начисление
 * приходит от закрытой смены/выезда (US-E5).
 *
 * <p>Каждое начисление хранит исходные данные, применённую версию условия
 * оплаты и текстовую расшифровку формулы: сумму должно быть можно
 * воспроизвести через год.
 */
@Service
public class PayrollService {

    private final PayrollRepository repo;
    private final RequestRepository requests;
    private final TripRepository trips;
    private final EmployeeRepository employees;
    private final ParameterRepository parameters;
    private final AuditService audit;

    public PayrollService(PayrollRepository repo, RequestRepository requests, TripRepository trips,
                          EmployeeRepository employees, ParameterRepository parameters,
                          AuditService audit) {
        this.repo = repo;
        this.requests = requests;
        this.trips = trips;
        this.employees = employees;
        this.parameters = parameters;
        this.audit = audit;
    }

    /** Результат расчёта по одному сотруднику — либо начисление, либо причина пропуска. */
    public record Accrual(Long entryId, Long employeeId, String employeeName, String scheme,
                          BigDecimal amount, String formula, String skipped) {}

    /**
     * Пересчёт начислений по принятой позиции. Черновые начисления по этой же
     * позиции удаляются, утверждённые — нет: их правят только сторно.
     */
    @Transactional
    public List<Accrual> accrueForItem(Long itemId) {
        RequestItem item = requests.item(itemId)
                .orElseThrow(() -> new ru.dipol.exception.EntityNotFoundException("Позиция", itemId));
        Request request = requests.findById(item.requestId()).orElseThrow();
        BigDecimal qty = item.acceptedQty() != null ? item.acceptedQty() : item.factQty();
        if (qty == null) {
            return List.of(new Accrual(null, null, null, null, null, null,
                    "Нет принятого объёма — начисление не рассчитывается"));
        }

        LocalDate date = request.doneAt() != null
                ? request.doneAt().toLocalDate()
                : request.receivedAt().toLocalDate();

        repo.removeDraftEntriesForItem(itemId);

        List<Accrual> result = new ArrayList<>();
        List<Long> assignees = item.assigneeIds();
        if (assignees.isEmpty()) {
            return List.of(new Accrual(null, null, null, null, null, null,
                    "На позицию не назначен ни один исполнитель"));
        }

        for (Long employeeId : assignees) {
            Employee emp = employees.findById(employeeId).orElse(null);
            if (emp == null) continue;

            Optional<Map<String, Object>> termsOpt = repo.payTerms(employeeId, request.projectId(), date);
            if (termsOpt.isEmpty()) {
                result.add(new Accrual(null, employeeId, emp.fullName(), null, null, null,
                        "Не задано условие оплаты для этого проекта и периода"));
                continue;
            }
            Map<String, Object> terms = termsOpt.get();
            String scheme = String.valueOf(terms.get("scheme_code"));

            // «За смену» не считается от объёма работ: иначе один и тот же день
            // оплатился бы столько раз, сколько позиций закрыл сотрудник.
            if ("SHIFT".equals(scheme) || "FIX".equals(scheme)) {
                result.add(new Accrual(null, employeeId, emp.fullName(), scheme, null, null,
                        "Схема «" + scheme + "»: начисление приходит от закрытой смены/выезда, не от позиции"));
                continue;
            }

            BigDecimal base;
            String unit;
            if ("HOUR".equals(scheme)) {
                base = item.manHours();
                unit = "MH";
                if (base == null) {
                    result.add(new Accrual(null, employeeId, emp.fullName(), scheme, null, null,
                            "Не внесены человеко-часы"));
                    continue;
                }
                // Часы позиции общие на всех исполнителей — делим поровну, иначе
                // каждый получит оплату за работу всей бригады.
                base = base.divide(BigDecimal.valueOf(assignees.size()), 2, RoundingMode.HALF_UP);
            } else {
                base = qty.divide(BigDecimal.valueOf(assignees.size()), 3, RoundingMode.HALF_UP);
                unit = item.unitCode();
            }

            BigDecimal rate = null;
            Long rateId = null;
            var rateRow = repo.findRate(item.skuId(), request.projectId(), employeeId, emp.roleCode(), date);
            if (rateRow.isPresent()) {
                rate = new BigDecimal(String.valueOf(rateRow.get().get("value")));
                rateId = ((Number) rateRow.get().get("id")).longValue();
            } else if (terms.get("rate") != null) {
                rate = new BigDecimal(String.valueOf(terms.get("rate")));
            }
            if (rate == null) {
                result.add(new Accrual(null, employeeId, emp.fullName(), scheme, null, null,
                        "Не найден тариф для работы «" + item.skuName() + "» на " + date));
                continue;
            }

            BigDecimal amount = base.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            String formula = base.stripTrailingZeros().toPlainString() + " " + unit
                    + " × " + rate.stripTrailingZeros().toPlainString() + " ₽"
                    + (assignees.size() > 1 ? " (объём поделён на " + assignees.size() + " исполнителей)" : "");

            Map<String, Object> entry = new HashMap<>();
            entry.put("employee_id", employeeId);
            entry.put("project_id", request.projectId());
            entry.put("request_id", request.id());
            entry.put("request_item_id", itemId);
            entry.put("basis_type", "FACT");
            entry.put("period_month", date.withDayOfMonth(1));
            entry.put("entry_date", date);
            entry.put("sku_id", item.skuId());
            entry.put("qty", base);
            entry.put("unit_code", unit);
            entry.put("rate", rate);
            entry.put("coefficient", BigDecimal.ONE);
            entry.put("formula", formula);
            entry.put("amount", amount);
            entry.put("pay_terms_id", ((Number) terms.get("id")).longValue());
            entry.put("pay_terms_version", terms.get("version"));
            entry.put("rate_id", rateId);
            entry.put("created_by", CurrentUser.name());

            Long entryId = repo.insertEntry(entry);
            audit.action("PAYROLL_ENTRY", entryId, "CREATE",
                    emp.fullName() + ": " + amount + " ₽ (" + formula + ")");
            result.add(new Accrual(entryId, employeeId, emp.fullName(), scheme, amount, formula, null));
        }
        return result;
    }

    /**
     * Начисления «за смену» по закрытому выезду: по одному на участника со
     * схемой SHIFT. Участники со сдельной схемой пропускаются — их начисления
     * идут от принятого объёма работ.
     */
    @Transactional
    public List<Accrual> accrueForTrip(Long tripId, Long projectId, LocalDate date) {
        List<Map<String, Object>> members = trips.members(tripId);
        List<Accrual> result = new ArrayList<>();
        for (var m : members) {
            Long employeeId = ((Number) m.get("employee_id")).longValue();
            String name = String.valueOf(m.get("full_name"));
            var termsOpt = repo.payTerms(employeeId, projectId, date);
            if (termsOpt.isEmpty()) {
                result.add(new Accrual(null, employeeId, name, null, null, null,
                        "Не задано условие оплаты для проекта и периода"));
                continue;
            }
            var terms = termsOpt.get();
            String scheme = String.valueOf(terms.get("scheme_code"));
            if (!"SHIFT".equals(scheme) && !"FIX".equals(scheme)) {
                result.add(new Accrual(null, employeeId, name, scheme, null, null,
                        "Схема «" + scheme + "»: начисление считается от принятого объёма"));
                continue;
            }
            if (terms.get("rate") == null) {
                result.add(new Accrual(null, employeeId, name, scheme, null, null,
                        "В условии оплаты не задана ставка за смену"));
                continue;
            }
            BigDecimal rate = new BigDecimal(String.valueOf(terms.get("rate")));

            Map<String, Object> entry = new HashMap<>();
            entry.put("employee_id", employeeId);
            entry.put("project_id", projectId);
            entry.put("trip_id", tripId);
            entry.put("basis_type", "SHIFT");
            entry.put("period_month", date.withDayOfMonth(1));
            entry.put("entry_date", date);
            entry.put("qty", BigDecimal.ONE);
            entry.put("unit_code", "SHIFT");
            entry.put("rate", rate);
            entry.put("coefficient", BigDecimal.ONE);
            entry.put("formula", "1 смена × " + rate.stripTrailingZeros().toPlainString() + " ₽");
            entry.put("amount", rate);
            entry.put("pay_terms_id", ((Number) terms.get("id")).longValue());
            entry.put("pay_terms_version", terms.get("version"));
            entry.put("created_by", CurrentUser.name());

            Long id = repo.insertEntry(entry);
            result.add(new Accrual(id, employeeId, name, scheme, rate,
                    String.valueOf(entry.get("formula")), null));
        }
        return result;
    }

    /**
     * Расчёт вырубки РК-3.
     *
     * <p>p = принятые м² / фактические чел·ч, k берётся из версионируемой
     * таблицы диапазонов, выплата = часы × базовая ставка × k. Использовать p
     * вместо k запрещено — это найденная в legacy-таблице ошибка (US-F6, P0).
     * Пока базовая ставка не утверждена (конфликт 595 / 610, вопрос Q-03),
     * расчёт не выполняется, а возвращает понятный отказ.
     */
    public record DemolitionCalc(BigDecimal productivity, BigDecimal coefficient, BigDecimal baseRate,
                                 BigDecimal amount, String formula, String error) {}

    public DemolitionCalc calcDemolition(BigDecimal acceptedM2, BigDecimal manHours, LocalDate date) {
        if (manHours == null || manHours.compareTo(BigDecimal.ZERO) <= 0) {
            return new DemolitionCalc(null, null, null, null, null,
                    "Не заданы фактические чел·ч — производительность не считается");
        }
        if (acceptedM2 == null || acceptedM2.compareTo(BigDecimal.ZERO) <= 0) {
            return new DemolitionCalc(null, null, null, null, null,
                    "Не задан принятый объём вырубки");
        }
        BigDecimal p = acceptedM2.divide(manHours, 4, RoundingMode.HALF_UP);
        BigDecimal k = parameters.coefficient("RK3_DEMOLITION_K", p, date);
        if (k == null) {
            return new DemolitionCalc(p, null, null, null, null,
                    "Для производительности " + p + " не задан диапазон коэффициента");
        }
        BigDecimal baseRate = parameters.numberOn("RK3_BASE_RATE", date);
        if (baseRate == null) {
            return new DemolitionCalc(p, k, null, null, null,
                    "Базовая ставка вырубки не утверждена (вопрос Q-03: 595 или 610) — "
                    + "расчёт выплаты не выполняется");
        }
        BigDecimal amount = manHours.multiply(baseRate).multiply(k).setScale(2, RoundingMode.HALF_UP);
        String formula = manHours.stripTrailingZeros().toPlainString() + " чел·ч × "
                + baseRate.stripTrailingZeros().toPlainString() + " ₽ × k="
                + k.stripTrailingZeros().toPlainString()
                + " (p=" + p.stripTrailingZeros().toPlainString() + " м²/чел·ч)";
        return new DemolitionCalc(p, k, baseRate, amount, formula, null);
    }

    @Transactional
    public void reverse(Long entryId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Сторно начисления требует основания");
        }
        Long newId = repo.reverseEntry(entryId, reason, CurrentUser.name());
        audit.action("PAYROLL_ENTRY", entryId, "REVERSE", reason + " → сторно #" + newId);
    }

}
