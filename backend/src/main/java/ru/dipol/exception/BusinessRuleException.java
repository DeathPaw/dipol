package ru.dipol.exception;

/** Нарушено бизнес-правило (недопустимый переход статуса, отрицательный остаток) — 422. */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
