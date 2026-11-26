package ru.miacomsoft.sonar_gui.backend.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Копия ru.miacomsoft.sonar.jira.JiraTempoTeamService. */
public class JiraTempoTeamService {

    private final JiraClient jira;

    public JiraTempoTeamService(JiraClient jira) { this.jira = jira; }

    public Integer findIdByName(String name) {
        if (name == null) return null;
        String target = name.trim().toLowerCase();
        Integer sel = lookup("/rest/tempo-teams/2/team/selectable", target);
        if (sel != null) return sel;
        Integer all = lookup("/rest/tempo-teams/2/team", target);
        if (all != null) return all;
        return lookup("/rest/tempo-teams/2/team/member", target);
    }

    private Integer lookup(String ep, String target) {
        try {
            JSONArray arr = new JSONArray(jira.get(ep));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                if (t.optString("name", "").trim().equalsIgnoreCase(target)) {
                    try { return Integer.parseInt(t.optString("id", "")); }
                    catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public JSONObject getTeam(int id) {
        try { return new JSONObject(jira.get("/rest/tempo-teams/2/team/" + id)); }
        catch (Exception e) { return null; }
    }

    public String teamName(String teamId) {
        try {
            JSONObject t = getTeam(Integer.parseInt(teamId));
            return t == null ? null : t.optString("name", null);
        } catch (Exception e) { return null; }
    }

    public List<String> findAccessibleTeamIds() {
        List<String> out = new ArrayList<>();
        for (String ep : new String[]{
                "/rest/tempo-teams/2/team/selectable",
                "/rest/tempo-teams/2/team/member"}) {
            try {
                JSONArray arr = new JSONArray(jira.get(ep));
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.getJSONObject(i).optString("id", null);
                    if (id != null && !out.contains(id)) out.add(id);
                }
                if (!out.isEmpty()) return out;
            } catch (Exception ignored) {}
        }
        return out;
    }
}