package ru.dipol.exception;

/** Конфликт состояния: дубль, закрытый период, занятый ресурс — 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
