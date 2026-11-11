package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.PromptTemplate;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/prompt/build?name=<promptName>&project=KEY
 *     [&issueKey=KEY | &keys=A,B,C | &sev=ALL&qual=ALL]
 *     [&status=accept&statusLabel=...]
 *
 * Читает <promptsDir>/<name>.txt, подставляет [TAG]-значения и возвращает
 * готовый текст. Параметры сервер сам добирает из SonarQube.
 *
 * Если промпт не найден — 404 с перечнем доступных имён промптов
 * (чтобы было видно, что именно видит сервер).
 */
public class BuildPromptHandler extends BaseHandler {

    private final PromptTemplate template;

    public BuildPromptHandler(ServerState state, PromptTemplate template) {
        super(state);
        this.template = template;
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);

        String name = param(q, "name", null);
        if (name == null || name.isEmpty()) {
            sendError(ex, 400,
                    "Параметр name обязателен (имя файла промпта без .txt)");
            return;
        }
        if (!template.exists(name)) {
            List<String> available = template.listAvailable();
            String availStr = available.isEmpty() ? "<пусто>" : String.join(", ", available);
            String absPath;
            try {
                absPath = template.getPromptsDir().toAbsolutePath().normalize().toString();
            } catch (Exception e) {
                absPath = String.valueOf(template.getPromptsDir());
            }
            System.err.println("[BuildPromptHandler] NOT FOUND: name=" + name
                    + " dir=" + absPath + " available=" + availStr);
            sendError(ex, 404,
                    "Промпт не найден: " + name
                            + ". Каталог: " + absPath
                            + ". Доступные промпты: " + availStr
                            + ". Создайте <dir>/" + name + ".txt или проверьте -Dsonar.prompts.");
            return;
        }

        String project = resolveProject(ex);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("PROJECT", project);

        String status      = param(q, "status", "");
        String statusLabel = param(q, "statusLabel", "");
        if ((statusLabel == null || statusLabel.isEmpty())
                && status != null && !status.isEmpty()) {
            statusLabel = PromptBuilder.defaultStatusLabel(status);
        }
        params.put("STATUS", status);
        params.put("STATUS_LABEL", statusLabel);

        List<String> availableTags = new ArrayList<>();
        try {
            List<String> raw = newApi(project).fetchTags(null);
            if (raw != null) availableTags.addAll(raw);
        } catch (Exception ignored) {
            // не критично
        }

        String issueKey = param(q, "issueKey", null);
        String keysCsv  = param(q, "keys",     null);
        String sev      = param(q, "sev",  "ALL");
        String qual     = param(q, "qual", "ALL");

        if (issueKey != null && !issueKey.isEmpty()) {
            buildSingle(project, issueKey, params, availableTags);
        } else if (keysCsv != null && !keysCsv.isEmpty()) {
            buildByKeys(project, keysCsv, params, availableTags);
        } else {
            buildByFilter(project, sev, qual, params, availableTags);
        }

        String prompt = template.render(name, params);

        JSONObject o = new JSONObject();
        o.put("promptName", name);
        o.put("promptLength", prompt.length());
        o.put("prompt", prompt);
        o.put("tagsAvailable", new JSONArray(availableTags));

        JSONObject paramsJson = new JSONObject();
        for (Map.Entry<String, String> e : params.entrySet()) {
            paramsJson.put(e.getKey(), e.getValue());
        }
        o.put("params", paramsJson);

        putSource(o, project);
        sendJson(ex, 200, o);
    }

    private void buildSingle(String project, String issueKey,
                             Map<String, String> params,
                             List<String> availableTags) throws Exception {
        SonarIssuesApi api = newApi(project);
        Issue issue = api.fetchIssue(issueKey);
        if (issue == null) {
            throw new IllegalArgumentException("Issue не найден: " + issueKey);
        }

        String ruleName;
        try {
            ruleName = api.fetchRuleName(issue.rule);
        } catch (Exception ignored) {
            ruleName = "";
        }

        List<String> snippet;
        try {
            snippet = fetchSnippetCached(project, issueKey);
        } catch (Exception e) {
            snippet = new ArrayList<>();
        }

        if (issue.tags != null) {
            for (String t : issue.tags) {
                if (!availableTags.contains(t)) availableTags.add(t);
            }
        }

        fillIssueParams(issue, ruleName, snippet, availableTags, params);
    }

    private void buildByKeys(String project, String keysCsv,
                             Map<String, String> params,
                             List<String> availableTags) throws Exception {
        SonarIssuesApi api = newApi(project);
        List<Issue> issues = new ArrayList<>();
        for (String raw : keysCsv.split(",")) {
            String key = raw.trim();
            if (key.isEmpty()) continue;
            Issue it = api.fetchIssue(key);
            if (it != null) issues.add(it);
        }
        fillBulkParams(issues, availableTags, params);
    }

    private void buildByFilter(String project, String sev, String qual,
                               Map<String, String> params,
                               List<String> availableTags) throws Exception {
        List<Issue> issues;
        try {
            issues = fetchIssues(project, sev, qual);
        } catch (Exception e) {
            issues = newApi(project).fetchAll(sev, qual);
        }
        fillBulkParams(issues, availableTags, params);
    }

    private void fillIssueParams(Issue issue, String ruleName, List<String> snippet,
                                 List<String> availableTags,
                                 Map<String, String> params) {

        PromptBuilder.Lang lang = PromptBuilder.detectLanguage(issue.component);

        String codeHighlighted = PromptBuilder.highlightForPrompt(snippet, issue.line).trim();
        String codeRaw = (snippet == null) ? "" : String.join("\n", snippet)
                .replace("§HL_START§", "")
                .replace("§HL_END§", "");

        String severity = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");

        String ruleCtx = (ruleName != null && !ruleName.isEmpty())
                ? ruleName
                : (issue.rule != null ? issue.rule : "");

        String component = issue.component != null ? issue.component : "";
        String location = component + (issue.line > 0 ? ":" + issue.line : "");

        params.put("RULE_KEY",         issue.rule != null ? issue.rule : "");
        params.put("RULE_NAME",        ruleName != null ? ruleName : "");
        params.put("RULE_NAME_OR_KEY", ruleCtx);
        params.put("MESSAGE",          issue.message != null ? issue.message : "");
        params.put("COMPONENT",        component);
        params.put("FILE",             component);
        params.put("LINE",             issue.line > 0 ? String.valueOf(issue.line) : "");
        params.put("LOCATION",         location);
        params.put("SEVERITY",         severity);
        params.put("LANGUAGE",         lang.name);
        params.put("LANGUAGE_FENCE",   lang.codeFence);
        params.put("CODE",             codeHighlighted);
        params.put("CODE_RAW",         codeRaw);
        params.put("TAGS_LIST",        tagsList(availableTags));
        params.put("ISSUES_COUNT",     "1");
        params.put("ISSUES_LIST",      "1. " + issue.key + " | " + issue.rule
                + " | " + location + " | " + (issue.message != null ? issue.message : ""));
    }

    private void fillBulkParams(List<Issue> issues, List<String> availableTags,
                                Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < issues.size(); i++) {
            Issue it = issues.get(i);
            String location = (it.component != null ? it.component : "")
                    + (it.line > 0 ? ":" + it.line : "");
            if (i > 0) sb.append('\n');
            sb.append(i + 1).append(". ")
                    .append(it.key).append(" | ")
                    .append(it.rule).append(" | ")
                    .append(location).append(" | ")
                    .append(it.message != null ? it.message : "");
        }
        params.put("ISSUES_LIST",  sb.toString());
        params.put("ISSUES_COUNT", String.valueOf(issues.size()));
        params.put("TAGS_LIST",    tagsList(availableTags));

        putEmptyIfAbsent(params, "RULE_KEY");
        putEmptyIfAbsent(params, "RULE_NAME");
        putEmptyIfAbsent(params, "RULE_NAME_OR_KEY");
        putEmptyIfAbsent(params, "MESSAGE");
        putEmptyIfAbsent(params, "COMPONENT");
        putEmptyIfAbsent(params, "FILE");
        putEmptyIfAbsent(params, "LINE");
        putEmptyIfAbsent(params, "LOCATION");
        putEmptyIfAbsent(params, "SEVERITY");
        putEmptyIfAbsent(params, "LANGUAGE");
        putEmptyIfAbsent(params, "LANGUAGE_FENCE");
        putEmptyIfAbsent(params, "CODE");
        putEmptyIfAbsent(params, "CODE_RAW");
    }

    private static void putEmptyIfAbsent(Map<String, String> m, String k) {
        if (!m.containsKey(k)) m.put(k, "");
    }

    private static String tagsList(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "любые релевантные";
        return String.join(", ", tags);
    }
}