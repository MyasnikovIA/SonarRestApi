package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.time.Instant;

public class HealthHandler extends BaseHandler {

    public HealthHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        JSONObject o = new JSONObject();
        o.put("status", "ok");
        o.put("sessionReady", state.isSessionReady());
        o.put("snapshotStatus", state.getSnapshotStatus().name());
        o.put("snapshotReady", state.isSnapshotReady());

        // Добавлены данные для прогресс-бара
        o.put("snapshotPhase", state.getSnapshotPhase() == null ? "" : state.getSnapshotPhase());
        o.put("snapshotProgress", state.getSnapshotProgress());
        o.put("snapshotProgressTotal", state.getSnapshotProgressTotal());

        if (state.getSnapshot() != null) {
            o.put("snapshotProject", state.getSnapshot().projectKey);
            o.put("snapshotIssuesCount", state.getSnapshot().issues.size());
            o.put("snapshotBuiltAt", state.getSnapshot().builtAt.toString());
        }
        o.put("defaultProject", state.getDefaultProject());
        o.put("timestamp", Instant.now().toString());
        sendJson(ex, 200, o);
    }
}