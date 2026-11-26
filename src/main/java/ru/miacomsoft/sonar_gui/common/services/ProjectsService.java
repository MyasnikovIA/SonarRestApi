package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Обёртка над GET /api/projects.
 * Возвращает список доступных проектов SonarQube.
 */
public class ProjectsService {

    /** Простой DTO для выпадающего списка. */
    public record ProjectItem(String key, String name) {
        @Override public String toString() { return key; }
    }

    private final SonarApiClient api;

    public ProjectsService(SonarApiClient api) {
        this.api = api;
    }

    public List<ProjectItem> fetchProjects() throws Exception {
        JSONObject root = api.getJson("/api/projects");
        JSONArray arr = root.optJSONArray("projects");
        List<ProjectItem> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new ProjectItem(
                        o.optString("key", ""),
                        o.optString("name", "")));
            }
        }
        return out;
    }
}