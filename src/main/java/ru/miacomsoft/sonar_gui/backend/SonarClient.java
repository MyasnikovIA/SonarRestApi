package ru.miacomsoft.sonar_gui.backend;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Прямой HTTP-клиент к SonarQube Web API.
 * Копия ru.miacomsoft.sonar.SonarClient без изменений.
 */
public class SonarClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    public static class SessionExpiredException extends RuntimeException {
        public SessionExpiredException(String message) { super(message); }
    }

    private final SessionManager session;
    private final HttpClient http;

    private final String authHeader;
    private final String cookieHeader;
    private final String xsrfToken;

    public SonarClient(SessionManager session) {
        this.session = session;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();

        String type = session.getAuthType() == null
                ? "bearer" : session.getAuthType().toLowerCase();

        if ("cookie".equals(type)) {
            this.authHeader   = null;
            this.cookieHeader = session.getCookie();
            this.xsrfToken    = extractXsrf(session.getCookie());
        } else if ("basic".equals(type)) {
            String raw = session.getUser() + ":" + session.getToken();
            this.authHeader   = "Basic " + Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            this.cookieHeader = null;
            this.xsrfToken    = null;
        } else {
            this.authHeader   = "Bearer " + session.getToken();
            this.cookieHeader = null;
            this.xsrfToken    = null;
        }
    }

    private static String extractXsrf(String cookie) {
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("XSRF-TOKEN=")) {
                return p.substring("XSRF-TOKEN=".length());
            }
        }
        return null;
    }

    public String get(String pathAndQuery) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(session.getUrl() + pathAndQuery))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "SonarGui/1.0");
        if (authHeader   != null) b.header("Authorization", authHeader);
        if (cookieHeader != null) b.header("Cookie", cookieHeader);
        if (xsrfToken    != null) b.header("X-XSRF-TOKEN", xsrfToken);

        HttpResponse<String> r = http.send(b.GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (r.statusCode() == 401) {
            throw new SessionExpiredException("GET " + pathAndQuery
                    + " -> HTTP 401 (неверный токен или истёк срок)");
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new RuntimeException("GET " + pathAndQuery
                    + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    public String postForm(String path, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(),
                                StandardCharsets.UTF_8));
            }
        }

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(session.getUrl() + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "SonarGui/1.0");
        if (authHeader   != null) b.header("Authorization", authHeader);
        if (cookieHeader != null) b.header("Cookie", cookieHeader);
        if (xsrfToken    != null) b.header("X-XSRF-TOKEN", xsrfToken);

        HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofString(
                body.toString(), StandardCharsets.UTF_8)).build();

        HttpResponse<String> r = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (r.statusCode() == 401) {
            throw new SessionExpiredException("POST " + path
                    + " -> HTTP 401 (неверный токен или истёк срок)");
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new RuntimeException("POST " + path
                    + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    public SessionManager getSession() { return session; }
    public String getUrl() { return session != null ? session.getUrl() : null; }
    public String getHost() { return session != null ? session.getHost() : ""; }
}