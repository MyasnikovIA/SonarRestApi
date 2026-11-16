package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JiraCreateParametersService {

    public record CreateParameters(
            String projectKey,
            String issueTypeId,
            String contextIssueKey,
            List<JiraFieldService.AllowedValue> priorities,
            List<JiraFieldService.AllowedValue> securityLevels,
            List<JiraFieldService.AllowedValue> versions,
            List<JiraFieldService.AllowedValue> fixVersions,
            List<JiraFieldService.AllowedValue> components,
            Map<String, List<JiraFieldService.AllowedValue>> nfeedOptions
    ) {}

    private final JiraClient jira;
    private final JiraFieldService fieldService;
    private final JiraNfeedService nfeedService;

    public JiraCreateParametersService(JiraClient jira,
                                       JiraFieldService fieldService,
                                       JiraNfeedService nfeedService) {
        this.jira = jira;
        this.fieldService = fieldService;
        this.nfeedService = nfeedService;
    }

    public CreateParameters load(String projectKey, String issueTypeId)
            throws IOException, InterruptedException {
        String sample = null;
        try {
            JSONObject root = new JSONObject(jira.get("/rest/api/2/search?jql="
                    + enc("project=" + projectKey + " AND issuetype=" + issueTypeId + " ORDER BY created DESC")
                    + "&fields=key&maxResults=1"));
            JSONArray issues = root.optJSONArray("issues");
            if (issues != null && !issues.isEmpty()) {
                sample = issues.getJSONObject(0).optString("key", null);
            }
        } catch (Exception ignored) { }
        return load(projectKey, issueTypeId, sample);
    }

    public CreateParameters load(String projectKey, String issueTypeId, String context)
            throws IOException, InterruptedException {
        Map<String, JiraFieldService.FieldMeta> meta = fieldService.getCreatemeta(projectKey, issueTypeId);
        List<JiraFieldService.AllowedValue> priorities =
                meta.containsKey("priority") ? meta.get("priority").allowedValues() : List.of();
        List<JiraFieldService.AllowedValue> securityLevels =
                meta.containsKey("security") ? meta.get("security").allowedValues() : List.of();
        List<JiraFieldService.AllowedValue> versions = loadVersions(projectKey);
        List<JiraFieldService.AllowedValue> components = loadComponents(projectKey);

        Map<String, List<JiraFieldService.AllowedValue>> nfeed = new LinkedHashMap<>();
        if (context != null) {
            for (JiraFieldService.FieldMeta fm : meta.values()) {
                if (fm.custom() != null && fm.custom().contains("nfeed")) {
                    nfeed.put(fm.id(), nfeedService.loadOptions(fm.id(), context));
                }
            }
        }
        return new CreateParameters(projectKey, issueTypeId, context,
                priorities, securityLevels, versions, versions, components, nfeed);
    }

    private List<JiraFieldService.AllowedValue> loadVersions(String projectKey) {
        try {
            JSONArray arr = new JSONArray(jira.get("/rest/api/2/project/" + enc(projectKey) + "/versions"));
            List<JiraFieldService.AllowedValue> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new JiraFieldService.AllowedValue(o.optString("id"), o.optString("name")));
            }
            return out;
        } catch (Exception e) { return List.of(); }
    }

    private List<JiraFieldService.AllowedValue> loadComponents(String projectKey) {
        try {
            JSONArray arr = new JSONArray(jira.get("/rest/api/2/project/" + enc(projectKey) + "/components"));
            List<JiraFieldService.AllowedValue> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new JiraFieldService.AllowedValue(o.optString("id"), o.optString("name")));
            }
            return out;
        } catch (Exception e) { return List.of(); }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}