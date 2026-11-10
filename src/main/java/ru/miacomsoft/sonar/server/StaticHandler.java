package ru.miacomsoft.sonar.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Раздаёт статику из каталога WWW.
 *  - "/"           -> index.html
 *  - любой файл    -> отдаётся как есть, MIME по расширению
 *  - /api/*        -> 404 JSON (чтобы статика не «съела» несуществующие API-пути)
 *  - защита от path traversal: результат normalize() обязан начинаться с root
 */
public class StaticHandler implements HttpHandler {

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html",  "text/html; charset=utf-8"),
            Map.entry("css",   "text/css; charset=utf-8"),
            Map.entry("js",    "application/javascript; charset=utf-8"),
            Map.entry("mjs",   "application/javascript; charset=utf-8"),
            Map.entry("json",  "application/json; charset=utf-8"),
            Map.entry("svg",   "image/svg+xml"),
            Map.entry("png",   "image/png"),
            Map.entry("jpg",   "image/jpeg"),
            Map.entry("jpeg",  "image/jpeg"),
            Map.entry("gif",   "image/gif"),
            Map.entry("ico",   "image/x-icon"),
            Map.entry("woff",  "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf",   "font/ttf"),
            Map.entry("map",   "application/json")
    );

    private final Path root;

    public StaticHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }

            String path = ex.getRequestURI().getPath();
            if (path == null || path.isEmpty()) path = "/";

            // API-пути, которые не поймали реальные хендлеры, не отдаём как статику
            if (path.startsWith("/api/")) {
                sendJson(ex, 404, "{\"error\":\"Not Found\",\"status\":404}");
                return;
            }

            if (path.equals("/") || path.equals("/index.html")) {
                Path idx = root.resolve("index.html");
                if (Files.isRegularFile(idx)) { sendFile(ex, idx); return; }
                sendText(ex, 404, "index.html not found in " + root);
                return;
            }

            // Срезаем ведущий '/', резолвим, нормализуем — и проверяем, что не убежали из root
            Path target = root.resolve(path.substring(1)).normalize();
            if (!target.startsWith(root)) {
                sendText(ex, 403, "Forbidden");
                return;
            }
            if (!Files.isRegularFile(target)) {
                sendText(ex, 404, "Not Found: " + path);
                return;
            }
            sendFile(ex, target);
        } finally {
            ex.close();
        }
    }

    private void sendFile(HttpExchange ex, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        int i = name.lastIndexOf('.');
        String ext = i >= 0 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
        String mime = MIME.getOrDefault(ext, "application/octet-stream");

        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", body);
    }

    private static void sendText(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", body);
    }

    private static void send(HttpExchange ex, int status, String mime, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}