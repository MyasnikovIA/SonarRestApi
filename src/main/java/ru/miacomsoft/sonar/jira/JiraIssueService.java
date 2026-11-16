package ru.miacomsoft.sonar.jira;

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

public class JiraIssueService {

    public record CreatedIssue(String id, String key, String self) {
        public String browseUrl(String baseUrl) { return baseUrl + "/browse/" + key; }
    }

    private final JiraClient jira;
    private final String baseUrl;
    private final JiraFieldService fieldService;
    private final JiraTempoTeamService tempoTeamService;
    private String cachedCurrentUserName;

    public JiraIssueService(JiraClient jira, String baseUrl,
                            JiraFieldService fieldService,
                            JiraTempoTeamService tempoTeamService) {
        this.jira = jira;
        this.baseUrl = baseUrl;
        this.fieldService = fieldService;
        this.tempoTeamService = tempoTeamService;
    }

    public CreatedIssue create(JSONObject fields) throws IOException, InterruptedException {
        JSONObject payload = new JSONObject().put("fields", fields);
        JSONObject o = new JSONObject(jira.post("/rest/api/2/issue", payload.toString()));
        return new CreatedIssue(o.getString("id"), o.getString("key"), o.optString("self"));
    }

    // ===== УНИВЕРСАЛЬНОЕ СОЗДАНИЕ С АВТО-ЗАПОЛНЕНИЕМ ОБЯЗАТЕЛЬНЫХ ПОЛЕЙ =====
    public CreatedIssue createIssueWithAutoFill(String projectKey, String issueTypeId,
                                                String summary, String description,
                                                String assignee,
                                                Map<String, Object> extraFields)
            throws IOException, InterruptedException {

        Map<String, JiraFieldService.FieldMeta> meta = fieldService.getCreatemeta(projectKey, issueTypeId);

        JSONObject fields = new JSONObject();
        fields.put("project", new JSONObject().put("key", projectKey));
        fields.put("issuetype", new JSONObject().put("id", issueTypeId));
        fields.put("summary", summary);
        if (description != null) fields.put("description", description);
        if (assignee != null && !assignee.isBlank()) fields.put("assignee", resolveAssignee(assignee));

        if (extraFields != null) {
            for (Map.Entry<String, Object> e : extraFields.entrySet()) {
                fields.put(e.getKey(), e.getValue());
            }
        }

        // Фильтруем поля, которых нет на экране создания
        if (meta.containsKey("summary")) filterByMeta(fields, meta);

        // Ищем незаполненные обязательные поля
        List<String> unresolved = new ArrayList<>();
        if (!meta.isEmpty()) {
            for (JiraFieldService.FieldMeta fm : meta.values()) {
                if (!fm.required() || fields.has(fm.id())) continue;
                Object v = autoValueFor(fm);
                if (v != null) {
                    fields.put(fm.id(), v);
                    System.out.println("  INFO: auto-filled required " + fm.id() + " (" + fm.name() + ") = " + v);
                } else {
                    unresolved.add(fm.id());
                }
            }
        }

        // Если не удалось авто-заполнить — берем значения из последней созданной задачи
        if (!unresolved.isEmpty()) {
            Map<String, Object> samples = fetchSampleRequiredValues(projectKey, issueTypeId, unresolved, meta);
            for (String fid : unresolved) {
                JiraFieldService.FieldMeta fm = meta.get(fid);
                if (samples.containsKey(fid)) {
                    fields.put(fid, samples.get(fid));
                    System.out.println("  INFO: sampled required " + fid + (fm == null ? "" : " (" + fm.name() + ")") + " = " + samples.get(fid));
                } else {
                    System.out.println("  WARN: cannot resolve required " + (fm == null ? fid : fm.toString()));
                }
            }
        }

        // Обработка Tempo Team (перебор форматов)
        String teamFieldId = null;
        Object teamValue = null;
        for (JiraFieldService.FieldMeta fm : meta.values()) {
            if ("team".equals(fm.type()) || fm.custom().contains("team.customfield")) {
                if (fields.has(fm.id())) {
                    teamFieldId = fm.id(); teamValue = fields.get(fm.id()); break;
                }
            }
        }

        if (teamFieldId != null) {
            boolean explicit = extraFields != null && extraFields.containsKey(teamFieldId);
            fields.remove(teamFieldId);
            return createWithTeamFallback(fields, teamFieldId, teamValue, explicit);
        }
        return createWithAutoRetry(fields);
    }

    // ==================== TEMPO TEAM FALLBACK ====================

    private CreatedIssue createWithTeamFallback(JSONObject baseFields, String teamFieldId,
                                                Object sample, boolean userExplicit)
            throws IOException, InterruptedException {
        String teamId = JiraTempoTeamService.extractTeamId(sample);
        String teamName = teamId != null ? tempoTeamService.teamName(teamId) : null;
        List<Object> candidates = tempoTeamService.buildCandidates(sample, teamId, teamName);

        System.out.println("  INFO: team " + teamFieldId + (userExplicit ? " (user)" : " (sample)")
                + " id=" + teamId + " name=" + teamName + " → " + candidates.size() + " formats");

        IllegalStateException last = null;
        int attempt = 0;
        for (Object c : candidates) {
            attempt++;
            JSONObject test = new JSONObject(baseFields.toString());
            test.put(teamFieldId, c);
            try {
                return createWithAutoRetry(test);
            } catch (IllegalStateException e) {
                if (!JiraTempoTeamService.isTeamError(e)) throw e;
                last = e;
            }
        }
        if (userExplicit) {
            throw new IllegalStateException("Команда '" + teamName + "' (id=" + teamId + ") отклонена " + attempt + " раз. Вероятно нет прав (не в /selectable). Последняя ошибка: " + (last == null ? "?" : last.getMessage()), last);
        }
        for (String myTeamId : tempoTeamService.findAccessibleTeamIds()) {
            if (myTeamId.equals(teamId)) continue;
            String myTeamName = tempoTeamService.teamName(myTeamId);
            for (Object c : tempoTeamService.buildCandidates(null, myTeamId, myTeamName)) {
                attempt++;
                JSONObject test = new JSONObject(baseFields.toString());
                test.put(teamFieldId, c);
                try { return createWithAutoRetry(test); }
                catch (IllegalStateException e) {
                    if (!JiraTempoTeamService.isTeamError(e)) throw e;
                    last = e;
                }
            }
        }
        throw new IllegalStateException("Team field " + teamFieldId + " неудачен (" + attempt + ")", last);
    }

    // ==================== SAMPLES + AUTOFILL ====================

    public Map<String, Object> fetchSampleRequiredValues(String projectKey, String issueTypeId,
                                                         List<String> fieldIds,
                                                         Map<String, JiraFieldService.FieldMeta> meta) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (fieldIds == null || fieldIds.isEmpty()) return result;
        try {
            String jql = "project=" + projectKey + " AND issuetype=" + issueTypeId + " ORDER BY created DESC";
            JSONObject root = new JSONObject(jira.get("/rest/api/2/search?jql=" + enc(jql)
                    + "&fields=" + enc(String.join(",", fieldIds)) + "&maxResults=1"));
            JSONArray issues = root.optJSONArray("issues");
            if (issues == null || issues.isEmpty()) return result;
            JSONObject fobj = issues.getJSONObject(0).getJSONObject("fields");
            for (String fid : fieldIds) {
                if (!fobj.has(fid) || fobj.isNull(fid)) continue;
                Object v = fobj.get(fid);
                JiraFieldService.FieldMeta fm = meta == null ? null : meta.get(fid);
                if (fm != null && ("team".equals(fm.type()) || fm.custom().contains("team.customfield"))) {
                    if (v instanceof JSONObject jo && jo.has("id"))
                        v = new JSONObject().put("id", jo.get("id"));
                }
                result.put(fid, v);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private Object autoValueFor(JiraFieldService.FieldMeta fm) {
        String type = fm.type() == null ? "" : fm.type();
        String custom = fm.custom() == null ? "" : fm.custom();

        if ("user".equals(type) || custom.contains("userpicker") || custom.contains(":user")) {
            try {
                if (cachedCurrentUserName == null) {
                    cachedCurrentUserName = new JSONObject(jira.get("/rest/api/2/myself")).optString("name", null);
                }
                return cachedCurrentUserName != null ? new JSONObject().put("name", cachedCurrentUserName) : null;
            } catch (Exception e) { return null; }
        }
        if ("securitylevel".equals(type)) {
            return fm.allowedValues().isEmpty() ? null : new JSONObject().put("id", fm.allowedValues().get(0).id());
        }
        if ("team".equals(type) || custom.contains("team.customfield")) return null;
        if (custom.contains("nfeed")) return null;
        if ("array".equals(type) && !fm.allowedValues().isEmpty()) {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("id", fm.allowedValues().get(0).id()));
            return arr;
        }
        if (("option".equals(type) || custom.contains(":select") || custom.contains(":radiobuttons"))
                && !fm.allowedValues().isEmpty()) {
            return new JSONObject().put("id", fm.allowedValues().get(0).id());
        }
        if ("string".equals(type)) return "auto: заполнено автоматически (REST API)";
        if ("number".equals(type)) return 1;
        if ("date".equals(type) || "datetime".equals(type)) return LocalDate.now().toString();
        return null;
    }

    private void filterByMeta(JSONObject fields, Map<String, JiraFieldService.FieldMeta> meta) {
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
                        System.out.printf("  WARN [%d]: removed '%s'%n", attempt, fid);
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
                || m.contains("не может быть установлено") || m.contains("не существует на экране")
                || m.contains("не находится на соответствующем экране");
    }

    private static JSONObject tryParseError(String m) {
        if (m == null) return null;
        int nl = m.indexOf('\n');
        if (nl < 0) return null;
        try { return new JSONObject(m.substring(nl + 1).trim()); } catch (Exception ignored) { return null; }
    }

    public JSONObject get(String issueIdOrKey) throws IOException, InterruptedException {
        return new JSONObject(jira.get("/rest/api/2/issue/" + enc(issueIdOrKey)));
    }

    public boolean exists(String issueIdOrKey) throws IOException, InterruptedException {
        try { get(issueIdOrKey); return true; }
        catch (IllegalStateException e) { return false; }
    }

    private JSONObject resolveAssignee(String idOrName) throws IOException, InterruptedException {
        String name = idOrName;
        if (idOrName.startsWith("JIRAUSER")) {
            try {
                String resolved = new JSONObject(jira.get("/rest/api/2/user?key=" + enc(idOrName))).optString("name", null);
                if (resolved != null && !resolved.isBlank()) name = resolved;
            } catch (Exception ignored) { }
        }
        return new JSONObject().put("name", name);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}