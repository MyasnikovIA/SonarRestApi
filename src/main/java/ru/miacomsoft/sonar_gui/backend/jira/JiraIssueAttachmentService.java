package ru.miacomsoft.sonar_gui.backend.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Копия ru.miacomsoft.sonar.jira.JiraIssueAttachmentService. */
public class JiraIssueAttachmentService {

    public record AttachmentInfo(String id, String filename, String url) {}

    private final JiraClient jira;
    private final String baseUrl;
    private final JiraIssueCommentService commentService;

    public JiraIssueAttachmentService(JiraClient jira, String baseUrl,
                                      JiraIssueCommentService commentService) {
        this.jira = jira;
        this.baseUrl = baseUrl;
        this.commentService = commentService;
    }

    public List<AttachmentInfo> uploadTextAttachments(String issueKey,
                                                      Map<String, String> nameToContent)
            throws IOException, InterruptedException {
        Path dir = Path.of("attachments");
        Files.createDirectories(dir);
        List<AttachmentInfo> out = new ArrayList<>();
        for (Map.Entry<String, String> e : nameToContent.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            out.addAll(uploadFile(issueKey, p));
        }
        return out;
    }

    public List<AttachmentInfo> uploadFile(String issueKey, Path file)
            throws IOException, InterruptedException {
        String resp = jira.uploadAttachment(issueKey, file);
        JSONArray arr = new JSONArray(resp);
        List<AttachmentInfo> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String id   = o.optString("id");
            String name = o.optString("filename");
            String url  = baseUrl + "/secure/attachment/" + id + "/"
                    + URLEncoder.encode(name, StandardCharsets.UTF_8);
            out.add(new AttachmentInfo(id, name, url));
        }
        return out;
    }

    public void addCommentWithLinks(String issueKey, String text,
                                    List<AttachmentInfo> attachments)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder(text == null ? "" : text);
        for (AttachmentInfo a : attachments) {
            body.append("\n* [").append(a.filename()).append("|")
                    .append(a.url()).append("]");
        }
        commentService.add(issueKey, body.toString());
    }
}