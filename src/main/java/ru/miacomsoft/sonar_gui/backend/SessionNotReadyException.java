package ru.miacomsoft.sonar_gui.backend;

/**
 * Бросается, когда GUI-операция требует загруженной сессии SonarQube,
 * но session.properties ещё не заполнен.
 */
public class SessionNotReadyException extends RuntimeException {
    public SessionNotReadyException(String message) {
        super(message);
    }
}