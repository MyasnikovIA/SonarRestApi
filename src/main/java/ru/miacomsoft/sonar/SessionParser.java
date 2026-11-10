package ru.miacomsoft.sonar;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Парсит сырой дамп запроса (Request URL / заголовки) в объект Session.
 * Поддерживает два формата:
 *   1) Построчно "Key: Value" (стандартный "Copy request headers")
 *   2) Пары строк "Key\nValue" ("Copy as cURL" построчно)
 * Из дампа достаёт host, scheme, cookie и XSRF-токен.
 */
public class SessionParser {

    public static class Session {
        public String scheme = "https";
        public String host;
        public String cookie;
        public String xsrf;

        public String baseUrl() {
            return scheme + "://" + host;
        }
    }

    public static Session parseFile(String file) throws Exception {
        String raw = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        return parse(raw);
    }

    public static Session parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("Пустой дамп сессии");
        raw = raw.replace("\uFEFF", "");
        String[] lines = raw.split("\\r?\\n");

        Map<String, String> h = new LinkedHashMap<>();

        // Формат 1: "Key: Value" построчно
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int idx = t.indexOf(':');
            if (idx > 0) {
                String k = t.substring(0, idx).trim().toLowerCase();
                String v = t.substring(idx + 1).trim();
                if (!k.isEmpty()) h.put(k, v);
            }
        }

        // Если Host не нашли — пробуем формат 2 (пары строк)
        if (!h.containsKey("host")) {
            Map<String, String> pairs = new LinkedHashMap<>();
            for (int i = 0; i + 1 < lines.length; i += 2) {
                String k = lines[i].trim();
                String v = lines[i + 1].trim();
                if (!k.isEmpty()) pairs.put(k.toLowerCase(), v);
            }
            if (pairs.containsKey("host")) h = pairs;
        }

        if (!h.containsKey("host") || h.get("host").isEmpty()) {
            throw new IllegalStateException(
                    "В дампе нет заголовка Host. Формат: либо 'Key: Value', " +
                            "либо пары строк 'Key' / 'Value'.");
        }

        Session s = new Session();
        s.host = h.get("host");
        s.scheme = h.getOrDefault("x-forwarded-proto",
                h.getOrDefault("scheme", "https"));
        s.cookie = h.getOrDefault("cookie", "");

        // XSRF: из заголовка, иначе из cookie
        s.xsrf = h.getOrDefault("x-xsrf-token", "");
        if (s.xsrf.isEmpty() && !s.cookie.isEmpty()) {
            for (String part : s.cookie.split(";")) {
                String p = part.trim();
                if (p.startsWith("XSRF-TOKEN=")) {
                    s.xsrf = p.substring("XSRF-TOKEN=".length());
                    break;
                }
            }
        }
        if (s.cookie.isEmpty()) {
            throw new IllegalStateException("В дампе нет заголовка Cookie");
        }
        return s;
    }
}