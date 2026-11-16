package ru.miacomsoft.sonar.jira;

import org.json.JSONObject;
import ru.miacomsoft.sonar.jira.JiraClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class JiraIssueCommentService {

    private final JiraClient jira;

    public JiraIssueCommentService(JiraClient jira) { this.jira = jira; }

    public void add(String issueIdOrKey, String body) throws IOException, InterruptedException {
        jira.post("/rest/api/2/issue/" + enc(issueIdOrKey) + "/comment",
                new JSONObject().put("body", body).toString());
    }

    public int count(String issueIdOrKey) throws IOException, InterruptedException {
        return new JSONObject(jira.get("/rest/api/2/issue/" + enc(issueIdOrKey) + "/comment"))
                .optInt("total", 0);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}