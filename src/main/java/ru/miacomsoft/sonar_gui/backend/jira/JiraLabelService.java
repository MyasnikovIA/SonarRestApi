package ru.miacomsoft.sonar_gui.backend.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Копия ru.miacomsoft.sonar.jira.JiraLabelService. */
public class JiraLabelService {

    private final JiraClient jira;

    public JiraLabelService(JiraClient jira) { this.jira = jira; }

    public List<String> getSuggestions(String projectKey, String query) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject search = new JSONObject(jira.get(
                    "/rest/api/2/search?jql=project=" + enc(projectKey)
                            + " ORDER BY created DESC&maxResults=1&fields=id"));
            JSONArray issues = search.optJSONArray("issues");
            String issueId = issues != null && issues.length() > 0
                    ? issues.getJSONObject(0).optString("id") : "1";
            String url = "/rest/api/1.0/labels/" + issueId + "/suggest?query="
                    + enc(query == null ? "" : query);
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

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}