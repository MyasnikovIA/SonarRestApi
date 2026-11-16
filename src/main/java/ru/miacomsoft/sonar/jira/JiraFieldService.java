package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JiraFieldService {

    public record AllowedValue(String id, String value) {
        @Override public String toString() { return id + " = " + value; }
    }

    public record FieldMeta(String id, String name, boolean required,
                            String type, String custom, List<AllowedValue> allowedValues) {}

    private final JiraClient jira;

    public JiraFieldService(JiraClient jira) { this.jira = jira; }

    public Map<String, FieldMeta> getCreatemeta(String projectKey, String issueTypeId) {
        try {
            Map<String, FieldMeta> m = fetchNew(projectKey, issueTypeId);
            if (m.containsKey("summary")) return m;
        } catch (Exception ignored) { }
        try {
            Map<String, FieldMeta> m = fetchOld(projectKey, issueTypeId);
            if (m.containsKey("summary")) return m;
        } catch (Exception ignored) { }
        return Map.of();
    }

    private Map<String, FieldMeta> fetchNew(String projectKey, String issueTypeId)
            throws IOException, InterruptedException {
        JSONObject root = new JSONObject(jira.get(
                "/rest/api/2/issue/createmeta/" + enc(projectKey) + "/issuetypes/" + enc(issueTypeId)));
        Map<String, FieldMeta> r = new LinkedHashMap<>();
        JSONArray values = root.optJSONArray("values");
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                JSONObject f = values.getJSONObject(i);
                String id = f.optString("fieldId", f.optString("key", "?"));
                JSONObject sc = f.optJSONObject("schema");
                r.put(id, new FieldMeta(id, f.optString("name", id),
                        f.optBoolean("required", false),
                        sc == null ? "" : sc.optString("type", ""),
                        sc == null ? "" : sc.optString("custom", ""),
                        parseAV(f)));
            }
        }
        return r;
    }

    private Map<String, FieldMeta> fetchOld(String projectKey, String issueTypeId)
            throws IOException, InterruptedException {
        JSONObject root = new JSONObject(jira.get("/rest/api/2/issue/createmeta?projectKeys="
                + enc(projectKey) + "&issuetypeIds=" + enc(issueTypeId)));
        Map<String, FieldMeta> r = new LinkedHashMap<>();
        JSONArray projects = root.optJSONArray("projects");
        if (projects == null) return r;
        for (int i = 0; i < projects.length(); i++) {
            JSONArray types = projects.getJSONObject(i).optJSONArray("issuetypes");
            if (types == null) continue;
            for (int j = 0; j < types.length(); j++) {
                JSONObject fields = types.getJSONObject(j).optJSONObject("fields");
                if (fields == null) continue;
                for (String id : fields.keySet()) {
                    JSONObject f = fields.getJSONObject(id);
                    JSONObject sc = f.optJSONObject("schema");
                    r.put(id, new FieldMeta(id, f.optString("name", id),
                            f.optBoolean("required", false),
                            sc == null ? "" : sc.optString("type", ""),
                            sc == null ? "" : sc.optString("custom", ""),
                            parseAV(f)));
                }
            }
        }
        return r;
    }

    private static List<AllowedValue> parseAV(JSONObject f) {
        JSONArray av = f.optJSONArray("allowedValues");
        if (av == null) return List.of();
        List<AllowedValue> out = new ArrayList<>(av.length());
        for (int k = 0; k < av.length(); k++) {
            JSONObject o = av.getJSONObject(k);
            out.add(new AllowedValue(o.optString("id", null),
                    o.optString("value", o.optString("name", null))));
        }
        return out;
    }

    // ===== ИСПРАВЛЕНО: Умный поиск ID типа задачи с фоллбэком =====
    public String findIssueTypeId(String projectKey, String typeName) {
        if (typeName != null && typeName.matches("\\d+")) return typeName; // Уже ID

        List<JSONObject> availableTypes = new ArrayList<>();

        try {
            JSONObject root = new JSONObject(jira.get("/rest/api/2/issue/createmeta/" + enc(projectKey) + "/issuetypes"));
            JSONArray arr = root.optJSONArray("values");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.getJSONObject(i);
                    availableTypes.add(it);
                    if (typeName != null && it.optString("name").equalsIgnoreCase(typeName)) {
                        return it.optString("id");
                    }
                }
            }
        } catch (Exception ignored) { }

        try {
            JSONObject root = new JSONObject(jira.get("/rest/api/2/project/" + enc(projectKey)));
            JSONArray types = root.optJSONArray("issueTypes");
            if (types != null) {
                for (int i = 0; i < types.length(); i++) {
                    JSONObject it = types.getJSONObject(i);
                    if (typeName != null && it.optString("name").equalsIgnoreCase(typeName)) {
                        return it.optString("id");
                    }
                }
            }
        } catch (Exception ignored) { }

        System.out.println("  WARN: тип задачи '" + typeName + "' не найден в проекте " + projectKey);
        if (!availableTypes.isEmpty()) {
            System.out.println("  Доступные типы задач в этом проекте:");
            for (JSONObject it : availableTypes) {
                System.out.println("    " + it.optString("id") + " = " + it.optString("name"));
            }
            String fallbackId = availableTypes.get(0).optString("id");
            System.out.println("  ВНИМАНИЕ: используем первый доступный тип: " + fallbackId);
            return fallbackId;
        }
        return typeName;
    }

    public String findPriorityIdByName(String p, String it, String name) {
        return findByName(getCreatemeta(p, it).get("priority"), name);
    }

    public String findSecurityLevelIdByName(String p, String it, String name) {
        return findByName(getCreatemeta(p, it).get("security"), name);
    }

    private static String findByName(FieldMeta fm, String name) {
        if (fm == null || name == null) return null;
        for (AllowedValue av : fm.allowedValues()) {
            if (av.value() != null && av.value().trim().equalsIgnoreCase(name.trim())) return av.id();
        }
        return null;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}