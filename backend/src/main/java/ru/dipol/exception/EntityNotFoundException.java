package ru.dipol.exception;

/** Запрошенная сущность не найдена — 404. */
public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String message) {
        super(message);
    }

    public EntityNotFoundException(String entity, Object id) {
        super(entity + " не найден(а): id=" + id);
    }
}
