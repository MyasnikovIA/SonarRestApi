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
 * POST /api/mark
 *
 * Два режима:
 *   1) ?key=ISSUE_KEY&transition=...&comment=...&dryRun=true|false
 *      — пометить ОДИН issue. transition+comment идут в историю перехода
 *        (комментарий прикрепляется к переходу, а не отдельным add_comment).
 *
 *   2) ?sev=&qual=&transition=...&comment=...&dryRun=true|false
 *      — массовая пометка всех issues по фильтру (батчами по 500).
 *
 * Если transition = reopen, используется per-issue do_transition
 * (bulk_change с reopen работает не на всех версиях SonarQube).
 *
 * dryRun=true по умолчанию — ничего не меняется, только план.
 */
public class MarkHandler extends BaseHandler {

    public MarkHandler(ServerState state) {
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
        String transition = param(q, "transition", null);
        String comment = param(q, "comment", "");
        boolean dryRun = paramBool(q, "dryRun", true);

        if (transition == null || transition.isEmpty()) {
            sendError(ex, 400, "Параметр transition обязателен");
            return;
        }

        SonarBulkApi bulk = newBulk();

        if (key != null && !key.isEmpty()) {
            handleSingle(ex, project, key, transition, comment, dryRun, bulk);
        } else {
            handleBulk(ex, project, sev, qual, transition, comment, dryRun, bulk);
        }
    }

    // ---------- single ----------

    private void handleSingle(HttpExchange ex, String project, String key,
                              String transition, String comment, boolean dryRun,
                              SonarBulkApi bulk) throws Exception {
        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("key", key);
        o.put("transition", transition);
        o.put("comment", comment);
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

            if ("reopen".equalsIgnoreCase(transition)) {
                Set<String> failed = bulk.reopenIssues(List.of(stub));
                success = !failed.contains(key);
                if (!success) o.put("error", "reopen failed");
            } else {
                bulk.change(List.of(stub), transition, comment);
                success = true;
            }
        } catch (Exception e) {
            success = false;
            o.put("error", e.getMessage());
        }

        o.put("applied", true);
        o.put("success", success);
        sendJson(ex, 200, o);
    }

    // ---------- bulk ----------

    private void handleBulk(HttpExchange ex, String project, String sev, String qual,
                            String transition, String comment, boolean dryRun,
                            SonarBulkApi bulk) throws Exception {
        SonarIssuesApi api = newApi(project);
        List<Issue> issues = api.fetchAll(sev, qual);

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("transition", transition);
        o.put("comment", comment);
        o.put("issuesCount", issues.size());
        o.put("dryRun", dryRun);
        o.put("batches", bulk.batchCount(issues.size()));

        if (dryRun) {
            o.put("applied", false);
            sendJson(ex, 200, o);
            return;
        }

        if ("reopen".equalsIgnoreCase(transition)) {
            Set<String> failed = bulk.reopenIssues(issues);
            o.put("applied", true);
            o.put("succeeded", issues.size() - failed.size());
            o.put("failed", failed.size());
            o.put("failedKeys", new JSONArray(failed));
        } else {
            List<String> responses = bulk.change(issues, transition, comment);
            o.put("applied", true);
            o.put("responses", new JSONArray(responses));
        }
        sendJson(ex, 200, o);
    }
}