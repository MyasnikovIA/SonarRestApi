package ru.miacomsoft.sonar_gui.common.services;

import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.PromptBuilder;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;
import ru.miacomsoft.sonar_gui.common.GuiContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Строит промпты локально (PromptTemplate + PromptBuilder) — без сервера.
 */
public class PromptService {

    private final GuiContext ctx;

    public PromptService(GuiContext ctx) {
        this.ctx = ctx;
    }

    public String buildForIssue(String projectKey, String promptName,
                                String issueKey, String status, String statusLabel)
            throws Exception {
        if (!ctx.isSessionReady()) {
            System.err.println("[PromptService] Сессия не готова.");
            throw new IllegalStateException("Сессия не готова");
        }

        System.out.println("[PromptService] Генерация промпта '" + promptName + "' для issue: " + issueKey);
        try {
            SonarIssuesApi api = ctx.state().newApi(projectKey);
            Issue issue = api.fetchIssue(issueKey);
            if (issue == null) {
                System.err.println("[PromptService] Issue не найден: " + issueKey);
                throw new IllegalArgumentException("Issue не найден: " + issueKey);
            }

            String ruleName = "";
            try { ruleName = api.fetchRuleName(issue.rule); }
            catch (Exception e) {
                System.err.println("[PromptService] Ошибка получения имени правила: " + e.getMessage());
            }

            List<String> snippet;
            try { snippet = api.fetchSnippet(issueKey); }
            catch (Exception e) {
                System.err.println("[PromptService] Ошибка получения сниппета: " + e.getMessage());
                snippet = new ArrayList<>();
            }

            String fullSource = "";
            if (issue.component != null && !issue.component.isEmpty()) {
                try { fullSource = api.fetchFullSource(projectKey + ":" + issue.component); }
                catch (Exception e) {
                    System.err.println("[PromptService] Ошибка получения полного исходника: " + e.getMessage());
                }
            }

            List<String> availableTags = new ArrayList<>();
            try { availableTags.addAll(api.fetchTags(null)); }
            catch (Exception e) {
                System.err.println("[PromptService] Ошибка получения тегов: " + e.getMessage());
            }
            if (issue.tags != null) {
                for (String t : issue.tags) if (!availableTags.contains(t)) availableTags.add(t);
            }

            Map<String, String> params;
            switch (promptName) {
                case "status-comment" ->
                        params = PromptBuilder.buildStatusCommentParams(
                                issue, ruleName, snippet, status, statusLabel,
                                availableTags, fullSource);
                case "analyze-issue" ->
                        params = PromptBuilder.buildAnalyzeIssueParams(
                                issue, ruleName, snippet, availableTags, fullSource);
                case "auto-resolve" ->
                        params = PromptBuilder.buildAutoResolveParams(
                                issue, snippet, availableTags, fullSource);
                default ->
                        params = PromptBuilder.buildAnalyzeIssueParams(
                                issue, ruleName, snippet, availableTags, fullSource);
            }

            String prompt = ctx.template().render(promptName, params);
            if ((prompt == null || prompt.isBlank()) && "auto-resolve".equals(promptName)) {
                prompt = PromptBuilder.buildAutoResolvePrompt(issue, snippet, availableTags);
            }
            System.out.println("[PromptService] Промпт успешно сгенерирован (длина: " + (prompt == null ? 0 : prompt.length()) + " символов).");
            return prompt == null ? "" : prompt;
        } catch (Exception e) {
            System.err.println("[PromptService] Ошибка генерации промпта: " + e.getMessage());
            throw e;
        }
    }

    public String buildForKeys(String projectKey, String promptName,
                               String keysCsv, String status, String statusLabel)
            throws Exception {
        if (!ctx.isSessionReady()) {
            System.err.println("[PromptService] Сессия не готова.");
            throw new IllegalStateException("Сессия не готова");
        }

        System.out.println("[PromptService] Генерация группового промпта '" + promptName + "' для ключей: " + keysCsv);
        try {
            SonarIssuesApi api = ctx.state().newApi(projectKey);
            List<Issue> issues = new ArrayList<>();
            if (keysCsv != null && !keysCsv.isBlank()) {
                for (String k : keysCsv.split(",")) {
                    String key = k.trim();
                    if (key.isEmpty()) continue;
                    Issue it = api.fetchIssue(key);
                    if (it != null) issues.add(it);
                }
            }
            if (issues.isEmpty()) {
                System.err.println("[PromptService] Не найдено ни одного issue из переданных ключей.");
                throw new IllegalArgumentException("Не найдено ни одного issue");
            }

            List<String> availableTags = new ArrayList<>();
            try { availableTags.addAll(api.fetchTags(null)); }
            catch (Exception e) {
                System.err.println("[PromptService] Ошибка получения тегов: " + e.getMessage());
            }

            Map<String, String> params = PromptBuilder.buildBulkStatusCommentParams(
                    issues, status, statusLabel, availableTags);

            String prompt = ctx.template().render(promptName, params);
            System.out.println("[PromptService] Групповой промпт успешно сгенерирован (длина: " + (prompt == null ? 0 : prompt.length()) + " символов).");
            return prompt == null ? "" : prompt;
        } catch (Exception e) {
            System.err.println("[PromptService] Ошибка генерации группового промпта: " + e.getMessage());
            throw e;
        }
    }
}