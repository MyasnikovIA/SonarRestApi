package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class JiraTeamService {

    private final JiraClient jira;

    public JiraTeamService(JiraClient jira) { this.jira = jira; }

    /**
     * Получает список всех доступных команд (Tempo Teams).
     * Использует эндпоинт picker/team, который возвращает объект с массивом "objects".
     */
    public List<JiraFieldService.AllowedValue> getTeams() {
        List<JiraFieldService.AllowedValue> out = new ArrayList<>();
        try {
            // Запрос к эндпоинту
            String resp = jira.get("/rest/tempo-teams/2/picker/team?expand=projects&query=");
            JSONObject root = new JSONObject(resp);

            // Ищем массив в ключе "objects" (как в вашем примере ответа)
            JSONArray arr = root.optJSONArray("objects");

            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.getJSONObject(i);
                    String id = t.optString("id");
                    String name = t.optString("name");
                    if (name != null && !name.isBlank()) {
                        out.add(new JiraFieldService.AllowedValue(id, name.trim()));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[JiraTeamService] Error loading teams: " + e.getMessage());
        }
        return out;
    }
}