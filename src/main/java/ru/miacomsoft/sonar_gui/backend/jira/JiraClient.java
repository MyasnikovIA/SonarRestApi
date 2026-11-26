package ru.miacomsoft.sonar_gui.backend.jira;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Тонкий HTTP-клиент к Jira (Cookie-auth).
 * Копия ru.miacomsoft.sonar.jira.JiraClient.
 */
public class JiraClient {

    private final String base;
    private final String cookie;
    private final HttpClient http;

    public JiraClient(String baseUrl, String cookieHeader) {
        this.base = baseUrl;
        this.cookie = cookieHeader;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String get(String path) throws IOException, InterruptedException {
        return send(baseBuilder(path).GET().build());
    }

    public String post(String path, String jsonBody)
            throws IOException, InterruptedException {
        return send(baseBuilder(path)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Atlassian-Token", "no-check")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody,
                        StandardCharsets.UTF_8))
                .build());
    }

    public String delete(String path) throws IOException, InterruptedException {
        return send(baseBuilder(path)
                .header("X-Atlassian-Token", "no-check")
                .DELETE()
                .build());
    }

    public String uploadAttachment(String issueKey, Path file)
            throws IOException, InterruptedException {
        String boundary = "----JiraBoundary" + UUID.randomUUID();
        String fileName = file.getFileName().toString();
        byte[] fileBytes = Files.readAllBytes(file);

        byte[] prefix = ("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        byte[] body = new byte[prefix.length + fileBytes.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(fileBytes, 0, body, prefix.length, fileBytes.length);
        System.arraycopy(suffix, 0, body, prefix.length + fileBytes.length, suffix.length);

        return send(baseBuilder("/rest/api/2/issue/"
                + URLEncoder.encode(issueKey, StandardCharsets.UTF_8)
                + "/attachments")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Atlassian-Token", "no-check")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build());
    }

    private HttpRequest.Builder baseBuilder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Cookie", cookie)
                .header("Accept", "application/json")
                .header("Origin", base)
                .header("Referer", base + "/secure/Dashboard.jspa")
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
    }

    private String send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> resp =
                http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int sc = resp.statusCode();
        if (sc >= 200 && sc < 300) return resp.body();
        throw new IllegalStateException("HTTP " + sc + " for " + req.uri()
                + "\n" + resp.body());
    }
}