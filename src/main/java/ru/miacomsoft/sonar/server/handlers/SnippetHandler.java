package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.util.List;
import java.util.Map;

public class SnippetHandler extends BaseHandler {

    public SnippetHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String issueKey = param(q, "issueKey", null);
        if (issueKey == null || issueKey.isEmpty()) {
            sendError(ex, 400, "Параметр issueKey обязателен");
            return;
        }

        Snapshot snap = snapshotFor(project);
        Issue issue = snap != null ? snap.byKey.get(issueKey) : null;
        if (issue == null) {
            SonarIssuesApi api = newApi(project);
            issue = api.fetchIssue(issueKey);
            if (issue == null) {
                sendError(ex, 404, "Issue не найден: " + issueKey);
                return;
            }
            try { issue.ruleName = api.fetchRuleName(issue.rule); }
            catch (Exception ignored) {}
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("issue", issue.toJson());
        try {
            if (issue.ruleName != null && !issue.ruleName.isEmpty()) {
                o.put("ruleName", issue.ruleName);
            } else {
                o.put("ruleName", newApi(project).fetchRuleName(issue.rule));
            }
        } catch (Exception e) { o.put("ruleName", ""); }

        try {
            List<String> snippet = fetchSnippetCached(project, issueKey);
            o.put("snippet", new JSONArray(snippet));
        } catch (Exception e) {
            o.put("snippet", new JSONArray());
            o.put("snippetError", e.getMessage());
        }

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}