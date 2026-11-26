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
        if (!ctx.isSessionReady()) {
            System.err.println("[SnippetService] Сессия не готова.");
            throw new IllegalStateException("Сессия SonarQube не готова");
        }

        System.out.println("[SnippetService] Загрузка фрагмента для issue: " + issueKey);
        try {
            SonarIssuesApi api = ctx.state().newApi(projectKey);
            Issue issue = api.fetchIssue(issueKey);
            if (issue == null) {
                System.err.println("[SnippetService] Issue не найден: " + issueKey);
                return new SnippetResult(new JSONObject(), "", List.of());
            }

            String ruleName = "";
            try { ruleName = api.fetchRuleName(issue.rule); }
            catch (Exception e) {
                System.err.println("[SnippetService] Не удалось загрузить имя правила для " + issue.rule + ": " + e.getMessage());
            }

            List<String> snippet;
            try { snippet = api.fetchSnippet(issueKey); }
            catch (Exception e) {
                System.err.println("[SnippetService] Не удалось загрузить сниппет для " + issueKey + ": " + e.getMessage());
                snippet = new ArrayList<>();
            }

            System.out.println("[SnippetService] Сниппет для " + issueKey + " успешно загружен (строк: " + snippet.size() + ")");
            return new SnippetResult(issue.toJson(), ruleName, snippet);
        } catch (Exception e) {
            System.err.println("[SnippetService] Критическая ошибка: " + e.getMessage());
            throw e;
        }
    }
}