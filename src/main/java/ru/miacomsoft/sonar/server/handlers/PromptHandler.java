package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GET /api/prompt?project=KEY&issueKey=KEY[&status=accept][&statusLabel=...]
 *
 * Возвращает готовый текст промпта для передачи в LLM.
 *
 * Внутри делает все необходимые подзапросы к SonarQube:
 *   - /api/issues/search?issues=KEY          (данные issue)
 *   - /api/rules/show?key=RULE               (имя правила)
 *   - /api/sources/issue_snippets?issueKey=  (фрагмент кода)
 *   - /api/issues/tags?ps=100                (список доступных тегов)
 *
 * Параметры:
 *   - project     : ключ проекта (обязателен)
 *   - issueKey    : ключ issue (обязателен; также поддерживается alias key)
 *   - status      : accept|confirm|falsepositive|resolve|wontfix
 *                   (опционально, по умолчанию accept)
 *   - statusLabel : человекочитаемое описание статуса
 *                   (опционально, по умолчанию берётся из маппинга)
 *
 * Ответ:
 * {
 *   "project": "...", "issueKey": "...", "rule": "...", "ruleName": "...",
 *   "status": "accept", "statusLabel": "...", "severity": "...",
 *   "prompt": "Ты — опытный разработчик ...",
 *   "promptLength": 5028,
 *   "tagsAvailable": [ "cwe", "phpunit", ... ]
 * }
 */
public class PromptHandler extends BaseHandler {

    public PromptHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        String issueKey = param(q, "issueKey", null);
        if (issueKey == null || issueKey.isEmpty()) issueKey = param(q, "key", null);
        if (issueKey == null || issueKey.isEmpty()) {
            sendError(ex, 400, "Параметр issueKey (или key) обязателен");
            return;
        }

        String status      = param(q, "status", "accept");
        String statusLabel = param(q, "statusLabel", "");
        if (statusLabel == null || statusLabel.isEmpty()) {
            statusLabel = PromptBuilder.defaultStatusLabel(status);
        }

        SonarIssuesApi api = newApi(project);

        // 1) Данные issue
        Issue issue = api.fetchIssue(issueKey);
        if (issue == null) {
            sendError(ex, 404, "Issue не найден: " + issueKey);
            return;
        }

        // 2) Имя правила
        String ruleName = "";
        try { ruleName = api.fetchRuleName(issue.rule); }
        catch (Exception ignored) { ruleName = ""; }

        // 3) Фрагмент кода (из snapshot-кэша, иначе live)
        List<String> snippet;
        try { snippet = fetchSnippetCached(project, issueKey); }
        catch (Exception e) { snippet = new ArrayList<>(); }

        // 4) Доступные теги + текущие теги issue
        List<String> availableTags;
        try { availableTags = new ArrayList<>(api.fetchTags(null)); }
        catch (Exception e) { availableTags = new ArrayList<>(); }

        if (issue.tags != null) {
            for (String t : issue.tags) {
                if (!availableTags.contains(t)) availableTags.add(t);
            }
        }

        // 5) Сборка промпта
        String prompt = PromptBuilder.buildStatusCommentPrompt(
                issue, ruleName, snippet, status, statusLabel, availableTags);

        // 6) Ответ
        String severity = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("issueKey", issueKey);
        o.put("rule", issue.rule != null ? issue.rule : "");
        o.put("ruleName", ruleName);
        o.put("status", status);
        o.put("statusLabel", statusLabel);
        o.put("severity", severity);
        o.put("prompt", prompt);
        o.put("promptLength", prompt.length());
        o.put("tagsAvailable", new JSONArray(availableTags));

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}