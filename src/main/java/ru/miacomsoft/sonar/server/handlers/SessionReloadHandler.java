package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.SessionManager;

import java.util.Map;

/**
 * POST /api/session/reload?build=true&project=KEY
 * Перечитывает session.properties с диска и (по умолчанию) запускает async-пересбор.
 */
public class SessionReloadHandler extends BaseHandler {

    public SessionReloadHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);
        boolean build = paramBool(q, "build", true);
        String project = param(q, "project", null);

        try {
            state.reload();
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
            return;
        } catch (Exception e) {
            sendError(ex, 500, "Не удалось перезагрузить сессию: " + e.getMessage());
            return;
        }

        String targetProject = (project != null && !project.isEmpty())
                ? project : state.getDefaultProject();
        boolean buildStarted = false;
        if (build && targetProject != null && !targetProject.isEmpty()
                && state.isSessionReady()) {
            try { buildStarted = state.buildSnapshotAsync(targetProject); }
            catch (Exception e) {
                System.err.println("[SessionReloadHandler] async build not started: " + e.getMessage());
            }
        }

        JSONObject o = new JSONObject();
        o.put("reloaded", true);
        o.put("path", state.getSessionFile() == null
                ? "" : state.getSessionFile().toString());
        o.put("ready", state.isSessionReady());

        SessionManager sm = state.getSession();
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
        }
        sendJson(ex, 200, o);
    }
}