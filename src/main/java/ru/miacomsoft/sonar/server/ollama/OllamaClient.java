package ru.miacomsoft.sonar.server.ollama;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.OllamaConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Тонкий синхронный клиент к Ollama API.
 * Использует /api/generate (stream=false).
 */
public class OllamaClient {

    private final OllamaConfig config;
    private final HttpClient http;

    public OllamaClient(OllamaConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Отправляет prompt и возвращает полный ответ модели. */
    public String chat(String prompt) throws Exception {
        if (!config.isConfigured()) {
            throw new IllegalStateException("Ollama не настроена (url/model). Используйте: ollama set url/model ...");
        }

        JSONObject body = new JSONObject();
        body.put("model",  config.getModel());
        body.put("prompt", prompt);
        body.put("stream", false);

        JSONObject options = new JSONObject();
        options.put("temperature", config.getTemperature());
        body.put("options", options);

        String endpoint = joinUrl(config.getUrl(), "/api/generate");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept",       "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Ollama HTTP " + resp.statusCode()
                    + ": " + truncate(resp.body(), 500));
        }

        JSONObject json = new JSONObject(resp.body());
        if (json.has("error")) {
            throw new RuntimeException("Ollama error: " + json.getString("error"));
        }
        return json.optString("response", "");
    }

    /** Проверка соединения: GET /api/tags (список моделей). */
    public void testConnection() throws Exception {
        String endpoint = joinUrl(config.getUrl(), "/api/tags");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
        JSONObject json = new JSONObject(resp.body());
        JSONArray models = json.optJSONArray("models");
        if (models != null) {
            System.out.println("Доступные модели:");
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.getJSONObject(i);
                System.out.println("  - " + m.optString("name", "?"));
            }
        }
    }

    private static String joinUrl(String base, String path) {
        if (base == null) base = "";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + path;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}