package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class JiraEpicService {

    private final JiraClient jira;

    public JiraEpicService(JiraClient jira) {
        this.jira = jira;
    }

    public List<JiraFieldService.AllowedValue> getEpics(String projectKey) {
        List<JiraFieldService.AllowedValue> out = new ArrayList<>();
        try {
            // Новый запрос через Greenhopper API
            String url = "/rest/greenhopper/1.0/epics?searchQuery=&projectKey="
                    + URLEncoder.encode(projectKey, StandardCharsets.UTF_8)
                    + "&maxResults=10000&hideDone=true";

            String resp = jira.get(url);
            JSONObject root = new JSONObject(resp);
            JSONArray epicLists = root.optJSONArray("epicLists");

            if (epicLists != null) {
                for (int i = 0; i < epicLists.length(); i++) {
                    JSONObject listObj = epicLists.getJSONObject(i);
                    JSONArray epicNames = listObj.optJSONArray("epicNames");
                    if (epicNames != null) {
                        for (int j = 0; j < epicNames.length(); j++) {
                            JSONObject epic = epicNames.getJSONObject(j);
                            String key = epic.optString("key");
                            String name = epic.optString("name");

                            // Адаптируем под формат AllowedValue: id = key, value = name
                            if (key != null && !key.isEmpty()) {
                                out.add(new JiraFieldService.AllowedValue(key, name));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[JiraEpicService] Ошибка загрузки эпиков: " + e.getMessage());
        }
        return out;
    }
}