package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.jira.JiraClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    public JiraUser getUser(String username) throws IOException, InterruptedException {
        return JiraUser.from(new JSONObject(jira.get("/rest/api/2/user?username=" + enc(username))));
    }

    public List<JiraUser> searchUsers(String query, int maxResults)
            throws IOException, InterruptedException {
        JSONArray arr = new JSONArray(jira.get(
                "/rest/api/2/user/search?username=" + enc(query) + "&maxResults=" + maxResults));
        return toList(arr);
    }

    public List<JiraUser> getAssignableUsers(String projectKey, String query, int maxResults)
            throws IOException, InterruptedException {
        JSONArray arr = new JSONArray(jira.get(
                "/rest/api/2/user/assignable/search?project=" + enc(projectKey)
                        + "&username=" + enc(query == null ? "" : query)
                        + "&maxResults=" + maxResults));
        return toList(arr);
    }

    public List<JiraUser> getGroupMembers(String groupName, boolean includeInactive)
            throws IOException, InterruptedException {
        List<JiraUser> out = new ArrayList<>();
        int startAt = 0;
        while (true) {
            JSONObject page = new JSONObject(jira.get(
                    "/rest/api/2/group/member?groupname=" + enc(groupName)
                            + "&includeInactiveUsers=" + includeInactive
                            + "&startAt=" + startAt + "&maxResults=50"));
            JSONArray values = page.optJSONArray("values");
            if (values == null || values.isEmpty()) break;
            for (int i = 0; i < values.length(); i++) out.add(JiraUser.from(values.getJSONObject(i)));
            int total = page.optInt("total", out.size());
            startAt += values.length();
            if (startAt >= total || values.length() < 50) break;
        }
        return out;
    }

    private static List<JiraUser> toList(JSONArray arr) {
        List<JiraUser> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) out.add(JiraUser.from(arr.getJSONObject(i)));
        return out;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}