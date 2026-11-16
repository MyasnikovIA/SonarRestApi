package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class JiraEpicService {

    private final JiraClient jira;

    public JiraEpicService(JiraClient jira) { this.jira = jira; }

    public List<JiraFieldService.AllowedValue> getEpics(String projectKey) {
        List<JiraFieldService.AllowedValue> out = new ArrayList<>();
        try {
            String url = "/rest/greenhopper/1.0/epics?searchQuery=&projectKey=" + enc(projectKey) + "&maxResults=100&hideDone=true";
            JSONObject root = new JSONObject(jira.get(url));
            JSONArray lists = root.optJSONArray("epicLists");
            if (lists != null) {
                for (int i = 0; i < lists.length(); i++) {
                    JSONObject list = lists.getJSONObject(i);
                    JSONArray epics = list.optJSONArray("epicNames");
                    if (epics != null) {
                        for (int j = 0; j < epics.length(); j++) {
                            JSONObject ep = epics.getJSONObject(j);
                            String key = ep.optString("key");
                            String name = ep.optString("name");
                            out.add(new JiraFieldService.AllowedValue(key, key + ": " + name));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[JiraEpicService] Error: " + e.getMessage());
        }
        return out;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}