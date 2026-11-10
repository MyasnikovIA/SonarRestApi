package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.util.Map;

/**
 * POST /api/refresh/issue?project=KEY&key=ISSUE_KEY
 * Синхронный точечный refresh одного issue:
 *   - обновляет issue в снимке (если он всё ещё OPEN/CONFIRMED);
 *   - удаляет его из снимка (если статус изменился на RESOLVED/CLOSED/...);
 *   - удаляет (если в Sonar уже нет).
 * При отсутствии снимка — просто возвращает свежие данные из Sonar без мутации.
 */
public class RefreshIssueHandler extends BaseHandler {

    public RefreshIssueHandler(ServerState state) {
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
        String key = param(q, "key", null);
        if (key == null || key.isEmpty()) key = param(q, "issueKey", null);
        if (key == null || key.isEmpty()) {
            sendError(ex, 400, "Параметр key (или issueKey) обязателен");
            return;
        }

        ServerState.RefreshResult r = state.refreshIssue(project, key);

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("key", key);
        o.put("removed", r.removed);
        if (r.reason != null) o.put("reason", r.reason);
        if (r.issue != null) o.put("issue", r.issue.toJson());

        // snapshotFor(...) унаследован из BaseHandler (protected) — используем напрямую.
        Snapshot snapshot = snapshotFor(project);
        o.put("snapshotUpdated", snapshot != null);
        if (snapshot != null) {
            o.put("snapshotIssuesCount", snapshot.issues.size());
        }

        sendJson(ex, 200, o);
    }
}