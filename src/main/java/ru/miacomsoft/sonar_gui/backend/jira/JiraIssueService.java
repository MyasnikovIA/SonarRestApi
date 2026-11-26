package ru.miacomsoft.sonar_gui.backend.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Копия ru.miacomsoft.sonar.jira.JiraIssueService (базовая часть). */
public class JiraIssueService {

    public record CreatedIssue(String id, String key, String self) {
        public String browseUrl(String baseUrl) { return baseUrl + "/browse/" + key; }
    }

    private final JiraClient jira;
    private final JiraFieldService fieldService;

    public JiraIssueService(JiraClient jira, String baseUrl,
                            JiraFieldService fieldService,
                            JiraTempoTeamService tempoTeamService) {
        this.jira = jira;
        this.fieldService = fieldService;
    }

    public CreatedIssue create(JSONObject fields) throws IOException, InterruptedException {
        JSONObject payload = new JSONObject().put("fields", fields);
        JSONObject o = new JSONObject(jira.post("/rest/api/2/issue", payload.toString()));
        return new CreatedIssue(o.getString("id"), o.getString("key"),
                o.optString("self"));
    }

    public CreatedIssue createIssueWithAutoFill(String projectKey, String issueTypeId,
                                                String summary, String description,
                                                String assignee,
                                                Map<String, Object> extraFields)
            throws IOException, InterruptedException {
        Map<String, JiraFieldService.FieldMeta> meta =
                fieldService.getCreatemeta(projectKey, issueTypeId);

        JSONObject fields = new JSONObject();
        fields.put("project", new JSONObject().put("key", projectKey));
        fields.put("issuetype", new JSONObject().put("id", issueTypeId));
        fields.put("summary", summary);
        if (description != null) fields.put("description", description);
        if (assignee != null && !assignee.isBlank())
            fields.put("assignee", new JSONObject().put("name", assignee));

        if (extraFields != null) {
            for (Map.Entry<String, Object> e : extraFields.entrySet()) {
                fields.put(e.getKey(), e.getValue());
            }
        }

        if (meta.containsKey("summary")) filterByMeta(fields, meta);

        List<String> unresolved = new ArrayList<>();
        for (JiraFieldService.FieldMeta fm : meta.values()) {
            if (!fm.required() || fields.has(fm.id())) continue;
            Object v = autoValueFor(fm);
            if (v != null) fields.put(fm.id(), v);
            else unresolved.add(fm.id());
        }
        return createWithAutoRetry(fields);
    }

    private Object autoValueFor(JiraFieldService.FieldMeta fm) {
        String type = fm.type() == null ? "" : fm.type();
        String custom = fm.custom() == null ? "" : fm.custom();

        if ("user".equals(type) || custom.contains("userpicker") || custom.contains(":user")) {
            try {
                String name = new JSONObject(jira.get("/rest/api/2/myself"))
                        .optString("name", null);
                return name != null ? new JSONObject().put("name", name) : null;
            } catch (Exception e) { return null; }
        }
        if ("securitylevel".equals(type)) {
            return fm.allowedValues().isEmpty() ? null
                    : new JSONObject().put("id", fm.allowedValues().get(0).id());
        }
        if ("array".equals(type) && !fm.allowedValues().isEmpty()) {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("id", fm.allowedValues().get(0).id()));
            return arr;
        }
        if (("option".equals(type) || custom.contains(":select")
                || custom.contains(":radiobuttons"))
                && !fm.allowedValues().isEmpty()) {
            return new JSONObject().put("id", fm.allowedValues().get(0).id());
        }
        if ("string".equals(type)) return "auto: заполнено автоматически (REST API)";
        if ("number".equals(type)) return 1;
        if ("date".equals(type) || "datetime".equals(type))
            return LocalDate.now().toString();
        return null;
    }

    private void filterByMeta(JSONObject fields,
                              Map<String, JiraFieldService.FieldMeta> meta) {
        List<String> rm = new ArrayList<>();
        for (String k : fields.keySet()) {
            if (k.equals("project") || k.equals("issuetype") || k.equals("summary")) continue;
            if (!meta.containsKey(k)) rm.add(k);
        }
        for (String k : rm) fields.remove(k);
    }

    private CreatedIssue createWithAutoRetry(JSONObject originalFields)
            throws IOException, InterruptedException {
        JSONObject fields = new JSONObject(originalFields.toString());
        for (int attempt = 1; attempt <= 10; attempt++) {
            try { return create(fields); }
            catch (IllegalStateException e) {
                JSONObject err = tryParseError(e.getMessage());
                if (err == null) throw e;
                JSONObject errors = err.optJSONObject("errors");
                if (errors == null || errors.isEmpty()) throw e;
                boolean any = false;
                for (String fid : errors.keySet()) {
                    String txt = errors.optString(fid, "");
                    if (isNotOnScreenError(txt) && fields.has(fid)) {
                        fields.remove(fid);
                        any = true;
                    }
                }
                if (!any) throw e;
            }
        }
        throw new IllegalStateException("Auto-retry exhausted");
    }

    private static boolean isNotOnScreenError(String msg) {
        if (msg == null || msg.isEmpty()) return false;
        String m = msg.toLowerCase();
        return m.contains("cannot be set") || m.contains("not on the appropriate screen")
                || m.contains("не может быть установлено") || m.contains("не существует на экране");
    }

    private static JSONObject tryParseError(String m) {
        if (m == null) return null;
        int nl = m.indexOf('\n');
        if (nl < 0) return null;
        try { return new JSONObject(m.substring(nl + 1).trim()); }
        catch (Exception ignored) { return null; }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}