package ru.miacomsoft.sonar.server;

/** Бросается, когда эндпоинт требует Sonar-сессию, но она ещё не загружена. */
public class SessionNotReadyException extends RuntimeException {
    public SessionNotReadyException(String message) {
        super(message);
    }
}