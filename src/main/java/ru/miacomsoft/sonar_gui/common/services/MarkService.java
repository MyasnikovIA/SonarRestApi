package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarBulkApi;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;
import ru.miacomsoft.sonar_gui.common.GuiContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Пометки issue (accept / confirm / reopen …) напрямую через SonarBulkApi.
 *
 * ВАЖНО: SonarBulkApi.change() работает батчами, но для одного issue
 * тоже сгодится — передаём список из одного Issue.
 */
public class MarkService {

    private final GuiContext ctx;

    public MarkService(GuiContext ctx) {
        this.ctx = ctx;
    }

    public JSONObject markOne(String projectKey, String issueKey,
                              String transition, String comment) throws Exception {
        if (!ctx.isSessionReady()) throw new IllegalStateException("Сессия не готова");

        SonarBulkApi bulk = ctx.state().newBulk();
        bulk.setSilent(true);

        Issue stub = new Issue();
        stub.key = issueKey;

        if ("reopen".equalsIgnoreCase(transition)) {
            bulk.reopenIssues(List.of(stub));
        } else {
            bulk.change(List.of(stub), transition, comment);
        }
        JSONObject o = new JSONObject();
        o.put("success", true);
        o.put("applied", true);
        return o;
    }

    public JSONObject markBulk(String projectKey, String severity, String quality,
                               String transition, String comment) throws Exception {
        if (!ctx.isSessionReady()) throw new IllegalStateException("Сессия не готова");

        SonarIssuesApi api = ctx.state().newApi(projectKey);
        SonarBulkApi bulk = ctx.state().newBulk();
        bulk.setSilent(true);

        List<Issue> issues = api.fetchOpen(severity, quality);
        int ok = 0;
        int fail = 0;

        if ("reopen".equalsIgnoreCase(transition)) {
            java.util.Set<String> failed = bulk.reopenIssues(issues);
            ok = issues.size() - failed.size();
            fail = failed.size();
        } else {
            List<Issue> batch = new ArrayList<>(issues);
            List<String> responses = bulk.change(batch, transition, comment);
            ok = issues.size();
        }
        JSONObject o = new JSONObject();
        o.put("success", true);
        o.put("applied", true);
        o.put("succeeded", ok);
        o.put("failed", fail);
        return o;
    }
}