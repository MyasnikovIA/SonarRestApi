package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;
import ru.miacomsoft.sonar_gui.common.GuiContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Данные issue + ruleName + snippet — напрямую через SonarIssuesApi.
 */
public class SnippetService {

    public record SnippetResult(JSONObject issue, String ruleName, List<String> snippet) {}

    private final GuiContext ctx;

    public SnippetService(GuiContext ctx) {
        this.ctx = ctx;
    }

    public SnippetResult fetchSnippet(String projectKey, String issueKey) throws Exception {
        if (!ctx.isSessionReady()) throw new IllegalStateException("Сессия SonarQube не готова");

        SonarIssuesApi api = ctx.state().newApi(projectKey);
        Issue issue = api.fetchIssue(issueKey);
        if (issue == null) {
            return new SnippetResult(new JSONObject(), "", List.of());
        }

        String ruleName = "";
        try { ruleName = api.fetchRuleName(issue.rule); }
        catch (Exception ignored) {}

        List<String> snippet;
        try { snippet = api.fetchSnippet(issueKey); }
        catch (Exception e) { snippet = new ArrayList<>(); }

        return new SnippetResult(issue.toJson(), ruleName, snippet);
    }
}