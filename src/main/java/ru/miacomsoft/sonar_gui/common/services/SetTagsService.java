package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;

/**
 * Обёртка над POST /api/set_tags.
 *
 * Сервер принимает массив ключей и строку тегов через запятую.
 * Пустая строка означает «снять все теги».
 */
public class SetTagsService {

    private final SonarApiClient api;

    public SetTagsService(SonarApiClient api) {
        this.api = api;
    }

    public JSONObject setTags(String projectKey, String keysCsv, String tagsCsv) throws Exception {
        StringBuilder url = new StringBuilder("/api/set_tags?");
        if (projectKey != null && !projectKey.isBlank()) {
            url.append("project=").append(SonarApiClient.enc(projectKey)).append('&');
        }
        url.append("keys=").append(SonarApiClient.enc(keysCsv));
        url.append("&issue=").append(SonarApiClient.enc(keysCsv));
        url.append("&tags=").append(SonarApiClient.enc(tagsCsv == null ? "" : tagsCsv));
        return api.postJson(url.toString());
    }

    public JSONObject setTags(String projectKey, String singleKey, String[] tags) throws Exception {
        String csv = (tags == null) ? "" : String.join(",", tags);
        return setTags(projectKey, singleKey, csv);
    }
}