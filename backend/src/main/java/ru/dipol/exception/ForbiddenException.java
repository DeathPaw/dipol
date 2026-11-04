package ru.dipol.exception;

/** Нет права на операцию — 403. Проверяется по permissions, а не только по роли. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
