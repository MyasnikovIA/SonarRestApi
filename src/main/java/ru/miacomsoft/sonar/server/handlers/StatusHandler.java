package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

/** GET /api/status — состояние снимка, сессии, прогресс сборки. */
public class StatusHandler extends BaseHandler {

    public StatusHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        JSONObject root = new JSONObject();

        JSONObject session = new JSONObject();
        session.put("ready", state.isSessionReady());
        session.put("path", state.getSessionFile().toString());
        if (state.isSessionReady()) {
            session.put("host", state.getSession().host);
            session.put("hasCookie", state.getSession().cookie != null
                    && !state.getSession().cookie.isEmpty());
            session.put("hasXsrf", state.getSession().xsrf != null
                    && !state.getSession().xsrf.isEmpty());
        }
        root.put("session", session);

        JSONObject snap = new JSONObject();
        snap.put("status", state.getSnapshotStatus().name());
        snap.put("phase", state.getSnapshotPhase() == null ? "" : state.getSnapshotPhase());
        snap.put("progress", state.getSnapshotProgress());
        snap.put("progressTotal", state.getSnapshotProgressTotal());
        if (state.getSnapshotStartedAt() != null) {
            snap.put("startedAt", state.getSnapshotStartedAt().toString());
        }
        if (state.getSnapshotError() != null) {
            snap.put("error", state.getSnapshotError());
        }
        Snapshot s = state.getSnapshot();
        if (s != null) {
            snap.put("projectKey", s.projectKey);
            snap.put("builtAt", s.builtAt.toString());
            snap.put("issuesCount", s.issues.size());
            snap.put("snippetsCount", s.snippets.size());
            snap.put("host", s.host);
        }
        root.put("snapshot", snap);

        root.put("defaultProject", state.getDefaultProject());
        root.put("warmupSnippets", state.isWarmupSnippets());
        root.put("warmupSnippetLimit", state.getWarmupSnippetLimit());

        sendJson(ex, 200, root);
    }
}