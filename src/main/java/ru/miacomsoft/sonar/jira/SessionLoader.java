package ru.miacomsoft.sonar.jira;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SessionLoader {

    // ===== ДОБАВЛЕНО: baseUrl (из строки "Request URL" в session.txt) =====
    public record SessionInfo(String cookieHeader, String username, String baseUrl) {}

    public static SessionInfo loadDefault() throws IOException {
        String prop = System.getProperty("jira.session");
        Path p = (prop != null && !prop.isBlank()) ? Path.of(prop) : Path.of("session.txt");
        return load(p);
    }

    public static SessionInfo load(Path sessionFile) throws IOException {
        if (!Files.exists(sessionFile)) {
            throw new IOException("session.txt not found: " + sessionFile.toAbsolutePath());
        }
        List<String> lines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
        String cookie = null, username = null, baseUrl = null;

        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty()) continue;

            if (t.regionMatches(true, 0, "cookie:", 0, 7)) {
                cookie = t.substring(7).trim(); continue;
            }
            if (t.regionMatches(true, 0, "x-ausername:", 0, 12)) {
                username = t.substring(12).trim(); continue;
            }

            // ===== ДОБАВЛЕНО: разбор "Request URL" → baseUrl =====
            if (t.equalsIgnoreCase("Request URL") && i + 1 < lines.size()) {
                String urlLine = lines.get(i + 1).trim();
                try {
                    URI u = URI.create(urlLine);
                    String scheme = u.getScheme() == null ? "https" : u.getScheme();
                    int port = u.getPort();
                    baseUrl = scheme + "://" + u.getHost()
                            + (port > 0 && port != 80 && port != 443 ? ":" + port : "");
                } catch (Exception ignored) {}
                i++;
                continue;
            }

            if (t.equalsIgnoreCase("cookie") && i + 1 < lines.size()) {
                StringBuilder sb = new StringBuilder(lines.get(i + 1).trim());
                int j = i + 2;
                while (j < lines.size() && !lines.get(j).isEmpty()
                        && Character.isWhitespace(lines.get(j).charAt(0))) {
                    sb.append(' ').append(lines.get(j).trim());
                    j++;
                }
                cookie = sb.toString(); i = j - 1;
            } else if (t.equalsIgnoreCase("x-ausername") && i + 1 < lines.size()) {
                username = lines.get(i + 1).trim(); i++;
            }
        }
        if (cookie == null || cookie.isEmpty()) {
            throw new IllegalArgumentException("Cookie header not found in " + sessionFile);
        }
        return new SessionInfo(cookie, username, baseUrl);
    }
}