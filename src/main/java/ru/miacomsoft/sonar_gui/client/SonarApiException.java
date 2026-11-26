package ru.miacomsoft.sonar_gui.client;

/** Единый тип исключения для всех HTTP-ошибок клиента Sonar REST API. */
public class SonarApiException extends RuntimeException {
    public SonarApiException(String message) {
        super(message);
    }

    public SonarApiException(String message, Throwable cause) {
        super(message, cause);
    }
}