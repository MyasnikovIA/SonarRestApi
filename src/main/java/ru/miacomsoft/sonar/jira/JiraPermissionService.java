package ru.miacomsoft.sonar.jira;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class JiraPermissionService {

    private final JiraClient jira;

    public JiraPermissionService(JiraClient jira) { this.jira = jira; }

    public JSONObject myPermissions(String projectKey) throws IOException, InterruptedException {
        String path = "/rest/api/2/mypermissions";
        if (projectKey != null) path += "?projectKey=" + enc(projectKey);
        return new JSONObject(jira.get(path));
    }

    public boolean havePermission(String projectKey, String permission) {
        try {
            JSONObject p = myPermissions(projectKey).getJSONObject("permissions");
            return p.has(permission) && p.getJSONObject(permission).optBoolean("havePermission", false);
        } catch (Exception e) { return false; }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}