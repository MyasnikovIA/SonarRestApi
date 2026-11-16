package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class JiraIssueTransitionService {

    private final JiraClient jira;

    public JiraIssueTransitionService(JiraClient jira) { this.jira = jira; }

    public JSONArray getAvailableTransitions(String issueIdOrKey)
            throws IOException, InterruptedException {
        return parseArrayOrWrapper(jira.get("/rest/api/2/issue/" + enc(issueIdOrKey)
                + "/transitions?expand=transitions.fields"), "transitions");
    }

    public JSONArray getResolutions() throws IOException, InterruptedException {
        return parseArrayOrWrapper(jira.get("/rest/api/2/resolution"), "resolutions");
    }

    public void transitionTo(String issueIdOrKey, String targetNameOrId, String resolutionId)
            throws IOException, InterruptedException {
        JSONArray transitions = getAvailableTransitions(issueIdOrKey);
        String chosen = null;
        for (int i = 0; i < transitions.length(); i++) {
            JSONObject t = transitions.getJSONObject(i);
            String id = t.optString("id");
            String name = t.optString("name", "");
            JSONObject to = t.optJSONObject("to");
            String toName = to != null ? to.optString("name", "") : "";
            if (id.equals(targetNameOrId) || name.equalsIgnoreCase(targetNameOrId)
                    || toName.equalsIgnoreCase(targetNameOrId)) {
                chosen = id; break;
            }
        }
        if (chosen == null) {
            throw new IllegalStateException("Transition '" + targetNameOrId + "' not found. Available: "
                    + describe(transitions));
        }
        JSONObject payload = new JSONObject().put("transition", new JSONObject().put("id", chosen));
        if (resolutionId != null) {
            payload.put("fields", new JSONObject().put("resolution",
                    new JSONObject().put("id", resolutionId)));
        }
        jira.post("/rest/api/2/issue/" + enc(issueIdOrKey) + "/transitions", payload.toString());
    }

    public boolean tryTransition(String issueIdOrKey, String target, String resolutionId) {
        try { transitionTo(issueIdOrKey, target, resolutionId); return true; }
        catch (Exception e) { return false; }
    }

    public void listAvailableTransitions(String issueIdOrKey) {
        System.out.println("-- transitions " + issueIdOrKey + " --");
        try {
            JSONArray arr = getAvailableTransitions(issueIdOrKey);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                JSONObject to = t.optJSONObject("to");
                System.out.printf("  [%s] %-30s → %s%n",
                        t.optString("id"), t.optString("name"),
                        to == null ? "?" : to.optString("name"));
            }
        } catch (Exception e) { System.out.println("  " + e.getMessage()); }
    }

    public String findResolutionId(String... candidates) {
        try {
            JSONArray rs = getResolutions();
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.getJSONObject(i);
                String name = r.optString("name", "");
                for (String c : candidates) if (name.equalsIgnoreCase(c)) return r.optString("id");
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONArray parseArrayOrWrapper(String body, String key) {
        if (body == null) return new JSONArray();
        String t = body.trim();
        if (t.isEmpty()) return new JSONArray();
        if (t.startsWith("[")) return new JSONArray(t);
        JSONArray arr = new JSONObject(t).optJSONArray(key);
        return arr == null ? new JSONArray() : arr;
    }

    private static String describe(JSONArray arr) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.getJSONObject(i);
            JSONObject to = t.optJSONObject("to");
            out.add(t.optString("name") + " → " + (to == null ? "?" : to.optString("name")));
        }
        return out.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}