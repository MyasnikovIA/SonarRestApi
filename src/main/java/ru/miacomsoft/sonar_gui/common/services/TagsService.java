package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Обёртка над GET /api/tags.
 *
 * Как и в web/tabs/perfile/tags.js, принудительно добавляет
 * приоритетные теги (require-fix / not-require-fix), даже если
 * SonarQube ещё их не знает — тег создастся при первом set_tags.
 */
public class TagsService {

    public static final List<String> PRIORITY_TAGS =
            List.of("CWE", "not-require-fix", "require-fix");

    private final SonarApiClient api;

    public TagsService(SonarApiClient api) {
        this.api = api;
    }

    /**
     * @param projectKey проект для контекста запроса (может быть null/пусто)
     */
    public List<String> fetchTags(String projectKey) throws Exception {
        StringBuilder url = new StringBuilder("/api/tags");
        if (projectKey != null && !projectKey.isBlank()) {
            url.append("?project=").append(SonarApiClient.enc(projectKey));
        }
        JSONObject root = api.getJson(url.toString());
        JSONArray arr = root.optJSONArray("tags");
        Set<String> tags = new LinkedHashSet<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String t = arr.optString(i, "");
                if (!t.isBlank()) tags.add(t);
            }
        }
        // Приоритетные — всегда в списке.
        for (String p : PRIORITY_TAGS) tags.add(p);
        return new ArrayList<>(tags);
    }
}