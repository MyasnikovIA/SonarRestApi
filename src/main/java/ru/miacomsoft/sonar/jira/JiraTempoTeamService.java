package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class JiraTempoTeamService {

    private final JiraClient jira;

    public JiraTempoTeamService(JiraClient jira) { this.jira = jira; }

    public Integer findIdByName(String name) {
        if (name == null) return null;
        String target = name.trim().toLowerCase();
        Integer sel = lookup("/rest/tempo-teams/2/team/selectable", target);
        if (sel != null) { System.out.println("  INFO: team '" + name + "' → id=" + sel + " (/selectable)"); return sel; }
        Integer all = lookup("/rest/tempo-teams/2/team", target);
        if (all != null) {
            System.out.println("  WARN: team '" + name + "' → id=" + all + " (/team — но НЕ в /selectable)");
            return all;
        }
        Integer member = lookup("/rest/tempo-teams/2/team/member", target);
        if (member != null) { System.out.println("  INFO: team '" + name + "' → id=" + member + " (/member)"); return member; }
        return null;
    }

    private Integer lookup(String ep, String target) {
        try {
            JSONArray arr = new JSONArray(jira.get(ep));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                if (t.optString("name", "").trim().equalsIgnoreCase(target)) {
                    try { return Integer.parseInt(t.optString("id", "")); } catch (NumberFormatException ignored) { }
                }
            }
        } catch (Exception ignored) { }
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
        for (String ep : new String[]{"/rest/tempo-teams/2/team/selectable",
                "/rest/tempo-teams/2/team/member"}) {
            try {
                JSONArray arr = new JSONArray(jira.get(ep));
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.getJSONObject(i).optString("id", null);
                    if (id != null && !out.contains(id)) out.add(id);
                }
                if (!out.isEmpty()) return out;
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Формат bare integer первым — рабочий для этого инстанса. */
    public List<Object> buildCandidates(Object sample, String teamId, String teamName) {
        List<Object> out = new ArrayList<>();
        int numericId = -1;
        if (teamId != null) try { numericId = Integer.parseInt(teamId); } catch (NumberFormatException ignored) { }
        if (numericId > 0) out.add(numericId);
        if (teamId != null) out.add(teamId);
        if (numericId > 0) out.add(new JSONObject().put("id", numericId));
        if (teamId != null) out.add(new JSONObject().put("id", teamId));
        if (numericId > 0) out.add(new JSONObject().put("teamId", numericId));
        if (teamId != null) out.add(new JSONObject().put("teamId", teamId));
        if (teamName != null) out.add(new JSONObject().put("name", teamName));
        if (teamName != null) out.add(new JSONObject().put("value", teamName));
        if (teamId != null) out.add(new JSONObject().put("value", teamId));
        if (teamName != null) out.add(new JSONObject().put("key", teamName));
        if (teamId != null) out.add(new JSONObject().put("key", teamId));
        if (teamId != null && teamName != null) {
            JSONObject c = new JSONObject().put("name", teamName).put("id", teamId);
            if (numericId > 0) c.put("id", numericId);
            out.add(c);
        }
        if (teamId != null) out.add(new JSONArray().put(teamId));
        if (numericId > 0) out.add(new JSONArray().put(numericId));
        if (teamId != null) out.add(new JSONArray().put(new JSONObject().put("id", teamId)));
        if (teamName != null) out.add(new JSONArray().put(new JSONObject().put("name", teamName)));
        if (numericId > 0) out.add(new JSONObject().put("id", numericId)
                .put("name", teamName == null ? "" : teamName));
        if (teamId != null) {
            Object full = getFullTeam(teamId);
            if (full != null) out.add(full);
        }
        if (teamName != null) out.add(teamName);
        if (sample != null) out.add(sample);
        return out;
    }

    public Object getFullTeam(String teamId) {
        try { return new JSONObject(jira.get("/rest/tempo-teams/2/team/" + teamId)); }
        catch (Exception e) { return null; }
    }

    public static String extractTeamId(Object sample) {
        if (sample instanceof JSONObject jo) {
            String id = jo.optString("id", null);
            return (id != null && !id.isBlank()) ? id : jo.optString("name", null);
        }
        if (sample instanceof JSONArray arr && !arr.isEmpty()) return extractTeamId(arr.get(0));
        if (sample instanceof String s) return s;
        if (sample instanceof Number n) return String.valueOf(n);
        return null;
    }

    public static boolean isTeamError(Exception e) {
        String m = e.getMessage();
        if (m == null) return false;
        String l = m.toLowerCase();
        return l.contains("could not find team") || l.contains("team with name")
                || l.contains("не удалось найти команду") || l.contains("команда с именем");
    }
}