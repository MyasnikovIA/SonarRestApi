package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.SessionManager;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * GET /api/session  — прочитать session.properties.
 * PUT /api/session  — перезаписать session.properties + перезагрузить,
 *                     затем (по умолчанию) запустить async-пересбор снимка.
 *
 * Тело PUT — полный текст session.properties.
 */
public class SessionHandler extends BaseHandler {

    public SessionHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) readSession(ex);
        else writeSession(ex);
    }

    private void readSession(HttpExchange ex) throws Exception {
        String raw;
        try {
            raw = state.readRawFile();
        } catch (Exception e) {
            sendError(ex, 500, "Не удалось прочитать " + state.getSessionFile()
                    + ": " + e.getMessage());
            return;
        }

        JSONObject o = new JSONObject();
        o.put("path", state.getSessionFile() == null
                ? "" : state.getSessionFile().toString());
        o.put("exists", !raw.isEmpty());

        if (!raw.isEmpty()) {
            try {
                Properties p = new Properties();
                try (StringReader sr = new StringReader(raw)) { p.load(sr); }

                o.put("url",       p.getProperty("sonar.url", ""));
                o.put("user",      p.getProperty("sonar.user", ""));
                o.put("authType",  p.getProperty("sonar.auth", "bearer"));
                o.put("hasToken",  !p.getProperty("sonar.token", "").isBlank());
                o.put("hasCookie", !p.getProperty("sonar.cookie", "").isBlank());

                // raw отдаём, но с замаскированными секретами
                String redacted = raw
                        .replaceAll("(?m)^(\\s*sonar\\.token\\s*=).*$", "$1***REDACTED***")
                        .replaceAll("(?m)^(\\s*sonar\\.cookie\\s*=).*$", "$1***REDACTED***");
                o.put("raw", redacted);
            } catch (Exception e) {
                o.put("parseError", e.getMessage());
            }
        } else {
            o.put("raw", "");
        }
        sendJson(ex, 200, o);
    }

    private void writeSession(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);
        boolean build = paramBool(q, "build", true);
        String project = param(q, "project", null);

        byte[] bodyBytes = ex.getRequestBody().readAllBytes();
        String raw = new String(bodyBytes, StandardCharsets.UTF_8);
        if (raw == null || raw.isBlank()) {
            sendError(ex, 400, "Пустое тело запроса. Передайте текст session.properties");
            return;
        }

        try {
            state.update(raw);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendError(ex, 400, "Невалидный session.properties: " + e.getMessage());
            return;
        } catch (Exception e) {
            sendError(ex, 500, "Не удалось записать " + state.getSessionFile()
                    + ": " + e.getMessage());
            return;
        }

        String targetProject = (project != null && !project.isEmpty())
                ? project : state.getDefaultProject();
        boolean buildStarted = false;
        if (build && targetProject != null && !targetProject.isEmpty()) {
            try { buildStarted = state.buildSnapshotAsync(targetProject); }
            catch (Exception e) {
                System.err.println("[SessionHandler] async build not started: " + e.getMessage());
            }
        }

        SessionManager sm = state.getSession();
        JSONObject o = new JSONObject();
        o.put("saved", true);
        o.put("path", state.getSessionFile().toString());
        if (sm != null) {
            o.put("url",      sm.getUrl());
            o.put("user",     sm.getUser());
            o.put("authType", sm.getAuthType());
            o.put("hasToken", sm.hasToken());
            o.put("hasCookie", sm.hasCookie());
        }
        o.put("buildStarted", buildStarted);
        if (buildStarted) {
            o.put("buildProject", targetProject);
            o.put("note", "Пересбор снимка идёт в фоне, прогресс: GET /api/status");
        } else if (build) {
            o.put("note", "build не запущен: project не задан (укажите ?project= или --project=)");
        }
        sendJson(ex, 200, o);
    }
}