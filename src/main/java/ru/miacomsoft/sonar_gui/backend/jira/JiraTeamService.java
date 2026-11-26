package ru.miacomsoft.sonar_gui.backend.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Копия ru.miacomsoft.sonar.jira.JiraTeamService. */
public class JiraTeamService {

    private final JiraClient jira;

    public JiraTeamService(JiraClient jira) { this.jira = jira; }

    public List<JiraFieldService.AllowedValue> getTeams() {
        List<JiraFieldService.AllowedValue> out = new ArrayList<>();
        try {
            String resp = jira.get("/rest/tempo-teams/2/picker/team?expand=projects&query=");
            JSONObject root = new JSONObject(resp);
            JSONArray arr = root.optJSONArray("objects");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.getJSONObject(i);
                    String id = t.optString("id");
                    String name = t.optString("name");
                    if (name != null && !name.isBlank())
                        out.add(new JiraFieldService.AllowedValue(id, name.trim()));
                }
            }
        } catch (Exception e) {
            System.err.println("[JiraTeamService] " + e.getMessage());
        }
        return out;
    }
}