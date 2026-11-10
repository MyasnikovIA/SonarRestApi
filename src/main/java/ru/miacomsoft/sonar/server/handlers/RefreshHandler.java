package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.util.Map;

/**
 * POST /api/refresh?project=KEY&wait=true|false
 * Полный пересбор снимка.
 *   wait=false (default) → 202 + запуск в фоне
 *   wait=true            → блокирующий вызов, 200 при успехе, 500 при ошибке
 * Если сборка уже идёт — возвращает текущее состояние без запуска новой.
 */
public class RefreshHandler extends BaseHandler {

    public RefreshHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        boolean wait = paramBool(q, "wait", false);

        if (!state.isSessionReady()) {
            throw new IllegalArgumentException(
                    "Сессия не загружена. Сначала PUT /api/session");
        }

        // Уже строится?
        if (state.isSnapshotBuilding()) {
            JSONObject o = new JSONObject();
            o.put("status", "building");
            o.put("message", "Пересбор уже идёт. Дождитесь завершения через GET /api/status");
            o.put("project", project);
            o.put("phase", state.getSnapshotPhase());
            o.put("progress", state.getSnapshotProgress());
            o.put("progressTotal", state.getSnapshotProgressTotal());
            sendJson(ex, 202, o);
            return;
        }

        if (wait) {
            try {
                Snapshot s = state.buildSnapshotSync(project);
                JSONObject o = new JSONObject();
                o.put("status", "ready");
                o.put("project", project);
                o.put("builtAt", s.builtAt.toString());
                o.put("issuesCount", s.issues.size());
                o.put("snippetsCount", s.snippets.size());
                sendJson(ex, 200, o);
            } catch (Exception e) {
                sendError(ex, 500, "Пересбор не удался: " + e.getMessage());
            }
        } else {
            boolean started = state.buildSnapshotAsync(project);
            JSONObject o = new JSONObject();
            o.put("status", started ? "building" : "already_building");
            o.put("project", project);
            o.put("message", "Следите за прогрессом через GET /api/status");
            sendJson(ex, 202, o);
        }
    }
}