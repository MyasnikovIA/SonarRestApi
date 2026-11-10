package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.SessionParser;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GET /api/session  — прочитать текущий session.txt.
 * PUT /api/session  — перезаписать session.txt + перезагрузить сессию,
 *                     затем (по умолчанию) запустить async-пересбор снимка.
 *                     Отключается через ?build=false.
 *                     ?project=KEY задаёт проект для пересбора (иначе defaultProject).
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
        try { raw = state.readRawFile(); }
        catch (Exception e) {
            sendError(ex, 500, "Не удалось прочитать " + state.getSessionFile()
                    + ": " + e.getMessage());
            return;
        }

        JSONObject o = new JSONObject();
        o.put("path", state.getSessionFile().toString());
        o.put("exists", !raw.isEmpty());
        o.put("raw", raw);

        if (!raw.isEmpty()) {
            try {
                SessionParser.Session s = SessionParser.parse(raw);
                o.put("host", s.host);
                o.put("xsrf", s.xsrf == null ? "" : s.xsrf);
                o.put("hasCookie", s.cookie != null && !s.cookie.isEmpty());
            } catch (Exception e) {
                o.put("parseError", e.getMessage());
                o.put("host", "");
                o.put("xsrf", "");
                o.put("hasCookie", false);
            }
        } else {
            o.put("host", "");
            o.put("xsrf", "");
            o.put("hasCookie", false);
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
            sendError(ex, 400, "Пустое тело запроса. Передайте сырой текст session.txt");
            return;
        }

        try { state.update(raw); }
        catch (IllegalArgumentException | IllegalStateException e) {
            sendError(ex, 400, "Невалидный формат сессии: " + e.getMessage());
            return;
        } catch (Exception e) {
            sendError(ex, 500, "Не удалось записать " + state.getSessionFile()
                    + ": " + e.getMessage());
            return;
        }

        String targetProject = (project != null && !project.isEmpty())
                ? project
                : state.getDefaultProject();
        boolean buildStarted = false;
        if (build && targetProject != null && !targetProject.isEmpty()) {
            try { buildStarted = state.buildSnapshotAsync(targetProject); }
            catch (Exception e) {
                System.err.println("[SessionHandler] async build not started: " + e.getMessage());
            }
        }

        JSONObject o = new JSONObject();
        o.put("saved", true);
        o.put("path", state.getSessionFile().toString());
        o.put("host", state.getSession().host);
        o.put("xsrf", state.getSession().xsrf == null ? "" : state.getSession().xsrf);
        o.put("hasCookie", state.getSession().cookie != null
                && !state.getSession().cookie.isEmpty());
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