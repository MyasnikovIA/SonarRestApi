package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class JiraLabelService {

    private final JiraClient jira;

    public JiraLabelService(JiraClient jira) { this.jira = jira; }

    /**
     * Новый метод: получение подсказок меток по проекту.
     * Используется в CreateJiraIssueHandler для выпадающего списка меток.
     */
    public List<String> getSuggestions(String projectKey, String query) {
        List<String> out = new ArrayList<>();
        try {
            // Ищем любой issue в проекте для контекста меток
            JSONObject search = new JSONObject(jira.get("/rest/api/2/search?jql=project=" + enc(projectKey) + " ORDER BY created DESC&maxResults=1&fields=id"));
            JSONArray issues = search.optJSONArray("issues");
            String issueId = issues != null && issues.length() > 0 ? issues.getJSONObject(0).optString("id") : "1";

            String url = "/rest/api/1.0/labels/" + issueId + "/suggest?query=" + enc(query == null ? "" : query);
            JSONObject root = new JSONObject(jira.get(url));
            JSONArray suggestions = root.optJSONArray("suggestions");
            if (suggestions != null) {
                for (int i = 0; i < suggestions.length(); i++) {
                    out.add(suggestions.getJSONObject(i).optString("label"));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Старый метод: получение подсказок меток по конкретному issue.
     * Оставлен для совместимости с тестовым классом CreateOptionsVisualizer.
     */
    public JSONArray loadSuggestions(String issueIdOrKey, String query) throws Exception {
        String q = query == null ? "" : query;
        try {
            return new JSONArray(jira.get("/rest/api/1.0/labels/" + enc(issueIdOrKey)
                    + "/suggest?query=" + enc(q)));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                String numericId = null;
                try {
                    numericId = new JSONObject(
                            jira.get("/rest/api/2/issue/" + enc(issueIdOrKey))).optString("id");
                } catch (Exception ignored) { }
                if (numericId != null) {
                    return new JSONArray(jira.get("/rest/api/1.0/labels/" + enc(numericId)
                            + "/suggest?query=" + enc(q)));
                }
            }
            throw e;
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}