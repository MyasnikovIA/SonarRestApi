package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import ru.miacomsoft.sonar.jira.JiraClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class JiraLabelService {

    private final JiraClient jira;

    public JiraLabelService(JiraClient jira) { this.jira = jira; }

    public JSONArray loadSuggestions(String issueIdOrKey, String query)
            throws IOException, InterruptedException {
        String q = query == null ? "" : query;
        try {
            return new JSONArray(jira.get("/rest/api/1.0/labels/" + enc(issueIdOrKey)
                    + "/suggest?query=" + enc(q)));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                String numericId = null;
                try {
                    numericId = new org.json.JSONObject(
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