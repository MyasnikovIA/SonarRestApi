package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.jira.JiraCreateParametersService;
import ru.miacomsoft.sonar.jira.JiraFieldService;
import ru.miacomsoft.sonar.jira.JiraTempoTeamService;

import java.util.List;
import java.util.Map;

public final class CreateOptionsVisualizer {

    private CreateOptionsVisualizer() {}

    public static void printCompact(JiraCreateParametersService.CreateParameters p) {
        header(p);
        line("Приоритеты",          p.priorities().size());
        line("Уровни безопасности", p.securityLevels().size());
        line("Затронуты версии",    p.versions().size());
        line("Исправить в версиях", p.fixVersions().size());
        line("Компоненты",          p.components().size());
        for (Map.Entry<String, List<JiraFieldService.AllowedValue>> e : p.nfeedOptions().entrySet())
            line("nFeed " + e.getKey(), e.getValue().size());
    }

    public static void printField(JiraCreateParametersService.CreateParameters p, String key) {
        List<JiraFieldService.AllowedValue> list = pick(p, key);
        if (list == null) { System.out.println("  " + key + ": not in CreateParameters"); return; }
        System.out.println("  ── " + key + " (" + list.size() + ") ──");
        for (JiraFieldService.AllowedValue av : list)
            System.out.printf("     %-8s = %s%n", av.id(), av.value() == null ? "<no label>" : av.value());
    }

    public static void printLabelSuggestions(JiraLabelService labels, String issueIdOrKey) {
        System.out.println("-- labels для " + issueIdOrKey + " --");
        try {
            JSONArray arr = labels.loadSuggestions(issueIdOrKey, "");
            System.out.println("  найдено: " + arr.length());
            for (int i = 0; i < arr.length(); i++)
                System.out.printf("  %-3d %s%n", i + 1, arr.getJSONObject(i).optString("label"));
        } catch (Exception e) { System.out.println("  " + e.getMessage()); }
    }

    public static void printTempoTeams(JiraTempoTeamService tempo) {
        System.out.println("-- Tempo Teams, доступные вам (/selectable) --");
        try {
            for (String id : tempo.findAccessibleTeamIds()) {
                JSONObject t = tempo.getTeam(Integer.parseInt(id));
                System.out.printf("  %-6s = %s%n", id, t == null ? "?" : t.optString("name"));
            }
        } catch (Exception e) { System.out.println("  " + e.getMessage()); }
    }

    private static List<JiraFieldService.AllowedValue> pick(
            JiraCreateParametersService.CreateParameters p, String k) {
        return switch (k) {
            case "priority"    -> p.priorities();
            case "security"    -> p.securityLevels();
            case "versions"    -> p.versions();
            case "fixVersions" -> p.fixVersions();
            case "components"  -> p.components();
            default            -> p.nfeedOptions().get(k);
        };
    }

    private static void header(JiraCreateParametersService.CreateParameters p) {
        System.out.println("╔═ CreateParameters: " + p.projectKey() + " / " + p.issueTypeId()
                + (p.contextIssueKey() == null ? "" : " (ctx " + p.contextIssueKey() + ")"));
    }

    private static void line(String label, int n) {
        System.out.printf("  %-32s %d%n", label, n);
    }
}