package ru.miacomsoft.sonar;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Тонкий HTTP-клиент над SonarQube.
 * Работает по сессионной cookie + X-XSRF-TOKEN (как браузер).
 *
 * На HTTP 401 выбрасывает SessionExpiredException — чтобы вызывающий
 * код мог отличить «сессия протухла» от обычной 4xx/5xx и остановиться.
 */
public class SonarClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** Сессия SonarQube истекла или невалидна (HTTP 401). */
    public static class SessionExpiredException extends RuntimeException {
        public SessionExpiredException(String message) { super(message); }
    }

    private final SessionParser.Session session;
    private final HttpClient http;

    public SonarClient(SessionParser.Session session) {
        this.session = session;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
    }

    public String get(String pathAndQuery) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(session.baseUrl() + pathAndQuery))
                .timeout(TIMEOUT)
                .header("Cookie", session.cookie)
                .header("Accept", "application/json")
                .header("User-Agent", "Java-Sonar-Client");
        if (session.xsrf != null && !session.xsrf.isEmpty()) {
            b.header("X-XSRF-TOKEN", session.xsrf);
        }

        HttpResponse<String> r = http.send(b.GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (r.statusCode() == 401) {
            throw new SessionExpiredException("GET " + pathAndQuery
                    + " -> HTTP 401 (сессия истекла или невалидна)");
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new RuntimeException("GET " + pathAndQuery
                    + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    public String postForm(String path, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(session.baseUrl() + path))
                .timeout(TIMEOUT)
                .header("Cookie", session.cookie)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Java-Sonar-Client");
        if (session.xsrf != null && !session.xsrf.isEmpty()) {
            b.header("X-XSRF-TOKEN", session.xsrf);
        }

        HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> r = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (r.statusCode() == 401) {
            throw new SessionExpiredException("POST " + path
                    + " -> HTTP 401 (сессия истекла или невалидна)");
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new RuntimeException("POST " + path
                    + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }
}