package ru.miacomsoft.sonar_gui.client;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Тонкий HTTP-клиент к локальному Sonar REST API-серверу
 * (пакет ru.miacomsoft.sonar.server). Никакой бизнес-логики —
 * только транспорт.
 */
public class SonarApiClient {

    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration LONG_TIMEOUT  = Duration.ofMinutes(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final HttpClient http;

    public SonarApiClient(String baseUrl) {
        this.baseUrl = normalizeBase(baseUrl);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ============ GET ============

    public JSONObject getJson(String pathAndQuery) throws Exception {
        String body = getString(pathAndQuery);
        return parseJsonOrThrow(body, pathAndQuery);
    }

    public String getString(String pathAndQuery) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(SHORT_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "SonarGui/1.0")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new SonarApiException(
                    "GET " + pathAndQuery + " -> HTTP " + resp.statusCode()
                            + ": " + truncate(resp.body(), 500));
        }
        return resp.body();
    }

    // ============ POST (без тела) ============

    public JSONObject postJson(String pathAndQuery) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(SHORT_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "SonarGui/1.0")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new SonarApiException(
                    "POST " + pathAndQuery + " -> HTTP " + resp.statusCode()
                            + ": " + truncate(resp.body(), 500));
        }
        String body = resp.body();
        return (body == null || body.isBlank())
                ? new JSONObject()
                : parseJsonOrThrow(body, pathAndQuery);
    }

    // ============ POST (JSON body) ============

    public JSONObject postJson(String pathAndQuery, JSONObject jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(SHORT_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", "SonarGui/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(
                        jsonBody == null ? "{}" : jsonBody.toString(),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new SonarApiException(
                    "POST " + pathAndQuery + " -> HTTP " + resp.statusCode()
                            + ": " + truncate(resp.body(), 500));
        }
        String body = resp.body();
        return (body == null || body.isBlank())
                ? new JSONObject()
                : parseJsonOrThrow(body, pathAndQuery);
    }

    // ============ SSE STREAM ============

    /**
     * Открывает SSE-поток. Блокирующий вызов — держит текущий поток
     * до завершения стрима. Для вызова из фонового потока используйте
     * SwingWorker.
     */
    public void streamSse(String pathAndQuery, SseListener listener) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(LONG_TIMEOUT)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "SonarGui/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req,
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            try (InputStream es = resp.body()) {
                String err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                throw new SonarApiException(
                        "SSE " + pathAndQuery + " -> HTTP " + resp.statusCode()
                                + ": " + truncate(err, 500));
            }
        }
        readSseStream(resp.body(), listener);
    }

    // ============ UTILS ============

    public static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String normalizeBase(String base) {
        if (base == null || base.isBlank()) return "http://localhost:6060";
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static JSONObject parseJsonOrThrow(String body, String ctx) {
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new SonarApiException(
                    "Ответ не JSON для " + ctx + ": " + truncate(body, 200), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
    // Добавить в SonarApiClient.java

    public void streamSsePost(String pathAndQuery, String jsonBody, SseListener listener) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(LONG_TIMEOUT)
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "SonarGui/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(
                        jsonBody == null ? "{}" : jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> resp = http.send(req,
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            try (InputStream es = resp.body()) {
                String err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                throw new SonarApiException(
                        "SSE POST " + pathAndQuery + " -> HTTP " + resp.statusCode()
                                + ": " + truncate(err, 500));
            }
        }
        readSseStream(resp.body(), listener);
    }

    private void readSseStream(InputStream in, SseListener listener) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        try {
                            listener.onEvent(event, new JSONObject(data.toString()));
                        } catch (Exception parseEx) {
                            listener.onError(parseEx);
                            throw parseEx;
                        }
                    }
                    event = "message";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
            if (data.length() > 0) {
                try {
                    listener.onEvent(event, new JSONObject(data.toString()));
                } catch (Exception parseEx) {
                    listener.onError(parseEx);
                    throw parseEx;
                }
            }
            listener.onComplete();
        } catch (Exception e) {
            listener.onError(e);
            throw e;
        }
    }
}