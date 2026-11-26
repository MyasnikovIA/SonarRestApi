package ru.miacomsoft.sonar_gui.backend.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Копия ru.miacomsoft.sonar.jira.JiraUserService. */
public class JiraUserService {

    private final JiraClient jira;

    public JiraUserService(JiraClient jira) { this.jira = jira; }

    public record JiraUser(String name, String key, String displayName,
                           String emailAddress, boolean active) {
        public static JiraUser from(JSONObject o) {
            return new JiraUser(o.optString("name", null), o.optString("key", null),
                    o.optString("displayName", null), o.optString("emailAddress", null),
                    o.optBoolean("active", true));
        }
    }

    public JiraUser getMyself() throws IOException, InterruptedException {
        return JiraUser.from(new JSONObject(jira.get("/rest/api/2/myself")));
    }

    public List<JiraUser> getAssignableUsers(String projectKey, String query, int maxResults)
            throws IOException, InterruptedException {
        JSONArray arr = new JSONArray(jira.get(
                "/rest/api/2/user/assignable/search?project=" + enc(projectKey)
                        + "&username=" + enc(query == null ? "" : query)
                        + "&maxResults=" + maxResults));
        return toList(arr);
    }

    public List<JiraUser> searchUsers(String query, int maxResults)
            throws IOException, InterruptedException {
        JSONArray arr = new JSONArray(jira.get(
                "/rest/api/2/user/search?username=" + enc(query)
                        + "&maxResults=" + maxResults));
        return toList(arr);
    }

    private static List<JiraUser> toList(JSONArray arr) {
        List<JiraUser> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) out.add(JiraUser.from(arr.getJSONObject(i)));
        return out;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}