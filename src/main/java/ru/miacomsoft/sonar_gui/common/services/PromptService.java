package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;

/**
 * Обёртка над GET /api/prompt/build.
 *
 * Сервер сам подставляет ruleName, snippet, язык, теги в шаблон
 * prompts/<name>.txt. Мы получаем готовый текст промпта.
 */
public class PromptService {

    private final SonarApiClient api;

    public PromptService(SonarApiClient api) {
        this.api = api;
    }

    /** Один issue со статусом (status-comment / analyze-issue / auto-resolve). */
    public String buildForIssue(String projectKey, String promptName,
                                String issueKey, String status, String statusLabel)
            throws Exception {
        StringBuilder url = new StringBuilder("/api/prompt/build?");
        if (projectKey != null && !projectKey.isBlank()) {
            url.append("project=").append(SonarApiClient.enc(projectKey)).append('&');
        }
        url.append("name=").append(SonarApiClient.enc(promptName));
        url.append("&issueKey=").append(SonarApiClient.enc(issueKey));
        if (status != null && !status.isBlank()) {
            url.append("&status=").append(SonarApiClient.enc(status));
        }
        if (statusLabel != null && !statusLabel.isBlank()) {
            url.append("&statusLabel=").append(SonarApiClient.enc(statusLabel));
        }

        JSONObject root = api.getJson(url.toString());
        return root.optString("prompt", "");
    }

    /** Группа issue (bulk-status-comment) — keys через запятую. */
    public String buildForKeys(String projectKey, String promptName,
                               String keysCsv, String status, String statusLabel)
            throws Exception {
        StringBuilder url = new StringBuilder("/api/prompt/build?");
        if (projectKey != null && !projectKey.isBlank()) {
            url.append("project=").append(SonarApiClient.enc(projectKey)).append('&');
        }
        url.append("name=").append(SonarApiClient.enc(promptName));
        url.append("&keys=").append(SonarApiClient.enc(keysCsv));
        if (status != null && !status.isBlank()) {
            url.append("&status=").append(SonarApiClient.enc(status));
        }
        if (statusLabel != null && !statusLabel.isBlank()) {
            url.append("&statusLabel=").append(SonarApiClient.enc(statusLabel));
        }

        JSONObject root = api.getJson(url.toString());
        return root.optString("prompt", "");
    }
}