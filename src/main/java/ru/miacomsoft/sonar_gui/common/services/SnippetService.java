package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Обёртка над GET /api/snippet.
 *
 * Возвращает данные issue, её ruleName и фрагмент кода
 * в виде списка строк (с маркерами §HL_START§/§HL_END§).
 */
public class SnippetService {

    public record SnippetResult(JSONObject issue, String ruleName, List<String> snippet) {}

    private final SonarApiClient api;

    public SnippetService(SonarApiClient api) {
        this.api = api;
    }

    public SnippetResult fetchSnippet(String projectKey, String issueKey) throws Exception {
        StringBuilder url = new StringBuilder("/api/snippet?");
        if (projectKey != null && !projectKey.isBlank()) {
            url.append("project=").append(SonarApiClient.enc(projectKey)).append('&');
        }
        url.append("issueKey=").append(SonarApiClient.enc(issueKey));

        JSONObject root = api.getJson(url.toString());
        JSONObject issue = root.optJSONObject("issue");
        String ruleName = root.optString("ruleName", "");

        JSONArray arr = root.optJSONArray("snippet");
        List<String> snippet = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                snippet.add(arr.optString(i, ""));
            }
        }
        return new SnippetResult(issue, ruleName, snippet);
    }
}