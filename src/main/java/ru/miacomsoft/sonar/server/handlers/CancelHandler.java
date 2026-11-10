package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/cancel
 *
 * Два режима:
 *   1) ?key=ISSUE_KEY&dryRun=true|false
 *      — снять пометку (reopen) с одного issue.
 *
 *   2) ?sev=&qual=&dryRun=true|false
 *      — снять пометки со всех issues по фильтру (reopen по одному).
 *
 * dryRun=true по умолчанию — только план, ничего не меняется.
 */
public class CancelHandler extends BaseHandler {

    public CancelHandler(ServerState state) {
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
        String sev = param(q, "sev", "MEDIUM");
        String qual = param(q, "qual", "MAINTAINABILITY");
        boolean dryRun = paramBool(q, "dryRun", true);

        SonarBulkApi bulk = newBulk();

        if (key != null && !key.isEmpty()) {
            handleSingle(ex, project, key, dryRun, bulk);
        } else {
            handleBulk(ex, project, sev, qual, dryRun, bulk);
        }
    }

    private void handleSingle(HttpExchange ex, String project, String key,
                              boolean dryRun, SonarBulkApi bulk) throws Exception {
        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("key", key);
        o.put("dryRun", dryRun);

        if (dryRun) {
            o.put("applied", false);
            sendJson(ex, 200, o);
            return;
        }

        boolean success;
        try {
            Issue stub = new Issue();
            stub.key = key;
            Set<String> failed = bulk.reopenIssues(List.of(stub));
            success = !failed.contains(key);
            if (!success) o.put("error", "reopen failed");
        } catch (Exception e) {
            success = false;
            o.put("error", e.getMessage());
        }

        o.put("applied", true);
        o.put("success", success);
        sendJson(ex, 200, o);
    }

    private void handleBulk(HttpExchange ex, String project, String sev, String qual,
                            boolean dryRun, SonarBulkApi bulk) throws Exception {
        SonarIssuesApi api = newApi(project);
        List<Issue> issues = api.fetchAll(sev, qual);

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("issuesCount", issues.size());
        o.put("dryRun", dryRun);

        if (dryRun) {
            o.put("applied", false);
            sendJson(ex, 200, o);
            return;
        }

        Set<String> failed = bulk.reopenIssues(issues);
        o.put("applied", true);
        o.put("succeeded", issues.size() - failed.size());
        o.put("failed", failed.size());
        o.put("failedKeys", new JSONArray(failed));
        sendJson(ex, 200, o);
    }
}