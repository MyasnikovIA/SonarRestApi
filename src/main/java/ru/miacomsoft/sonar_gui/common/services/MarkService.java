package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;

/**
 * Обёртка над POST /api/mark — один issue или массовая пометка.
 * И соответствует REST-контракту сервера (dryRun по умолчанию true).
 */
public class MarkService {

    private final SonarApiClient api;

    public MarkService(SonarApiClient api) {
        this.api = api;
    }

    /** Один issue. dryRun = false — реально применяет переход. */
    public JSONObject markOne(String projectKey, String issueKey,
                              String transition, String comment) throws Exception {
        StringBuilder url = new StringBuilder("/api/mark?");
        if (projectKey != null && !projectKey.isBlank()) {
            url.append("project=").append(SonarApiClient.enc(projectKey)).append('&');
        }
        url.append("key=").append(SonarApiClient.enc(issueKey));
        url.append("&transition=").append(SonarApiClient.enc(transition));
        if (comment != null && !comment.isEmpty()) {
            url.append("&comment=").append(SonarApiClient.enc(comment));
        }
        url.append("&dryRun=false");
        return api.postJson(url.toString());
    }

    /** Массовая пометка по фильтру. */
    public JSONObject markBulk(String projectKey, String severity, String quality,
                               String transition, String comment) throws Exception {
        StringBuilder url = new StringBuilder("/api/mark?");
        if (projectKey != null && !projectKey.isBlank()) {
            url.append("project=").append(SonarApiClient.enc(projectKey)).append('&');
        }
        url.append("sev=").append(SonarApiClient.enc(severity));
        url.append("&qual=").append(SonarApiClient.enc(quality));
        url.append("&transition=").append(SonarApiClient.enc(transition));
        if (comment != null && !comment.isEmpty()) {
            url.append("&comment=").append(SonarApiClient.enc(comment));
        }
        url.append("&dryRun=false");
        return api.postJson(url.toString());
    }
}