package ru.miacomsoft.sonar.jira;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class JiraIssueDeleteService {

    private final JiraClient jira;

    public JiraIssueDeleteService(JiraClient jira) { this.jira = jira; }

    public void delete(String issueIdOrKey) throws IOException, InterruptedException {
        delete(issueIdOrKey, false);
    }

    public void delete(String issueIdOrKey, boolean deleteSubtasks)
            throws IOException, InterruptedException {
        jira.delete("/rest/api/2/issue/" + enc(issueIdOrKey)
                + "?deleteSubtasks=" + deleteSubtasks);
    }

    public boolean canDelete(String issueIdOrKey) throws IOException, InterruptedException {
        try { delete(issueIdOrKey); return true; }
        catch (IllegalStateException e) { return false; }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}