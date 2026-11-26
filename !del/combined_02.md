# Combined part 2 of 5

- files: 19
- bytes: 136031

## Measures.java

```
package ru.miacomsoft.sonar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Обёртка над ответом /api/measures/component.
 * Хранит ключевые метрики, которые нужны для верхней панели UI:
 *   - Quality Gate status
 *   - общее число issues (violations)
 *   - разбивка по видам (legacy: bugs/vulnerabilities/code_smells,
 *     MQR: reliability/security/maintainability issues)
 *   - accepted / high impact accepted
 *   - размер (lines, ncloc, ncloc по языкам)
 *   - покрытие, дубликаты
 *   - рейтинги A..E (1..5)
 */
public class Measures {

    public String projectKey;
    public String alertStatus;              // OK | ERROR | WARN
    public String qualityGateJson;          // сырой quality_gate_details

    // Legacy-разбивка
    public int violations;
    public int bugs;
    public int vulnerabilities;
    public int codeSmells;
    public int securityHotspots;

    // MQR-разбивка (SonarQube 10.6+)
    public int maintainabilityIssues;
    public int reliabilityIssues;
    public int securityIssues;

    // Accepted
    public int acceptedIssues;
    public int highImpactAcceptedIssues;

    // New code
    public int newViolations;
    public int newBugs;
    public int newVulnerabilities;
    public int newCodeSmells;
    public int newAcceptedIssues;

    // Размер
    public int lines;
    public int ncloc;
    public int linesToCover;

    // Покрытие / дубликаты
    public double coverage;
    public double duplicatedLinesDensity;

    // Рейтинги: 1=A..5=E, 0 = не оценено
    public double maintainabilityRating;
    public double reliabilityRating;
    public double securityRating;
    public double securityReviewRating;
    public double sqaleRating;

    // Распределение ncloc по языкам
    public Map<String, Integer> nclocByLanguage = new LinkedHashMap<>();

    // Полный сырой набор метрик (key -> значение строкой)
    public Map<String, String> raw = new LinkedHashMap<>();

    public static Measures from(JSONObject root, String projectKey) {
        Measures m = new Measures();
        m.projectKey = projectKey;
        if (root == null) return m;

        JSONObject component = root.optJSONObject("component");
        if (component == null) return m;

        JSONArray measures = component.optJSONArray("measures");
        if (measures == null) return m;

        for (int i = 0; i < measures.length(); i++) {
            JSONObject o = measures.getJSONObject(i);
            String key = o.optString("metric", "");
            if (key.isEmpty()) continue;

            String value = o.optString("value", "");
            // Периодные метрики (new_*) хранят значение внутри period
            JSONObject period = o.optJSONObject("period");
            if ((value == null || value.isEmpty()) && period != null) {
                value = period.optString("value", "");
            }
            m.raw.put(key, value);
        }

        m.alertStatus      = m.raw.getOrDefault("alert_status", "");
        m.qualityGateJson  = m.raw.getOrDefault("quality_gate_details", "");

        m.violations            = intOf(m.raw, "violations");
        m.bugs                  = intOf(m.raw, "bugs");
        m.vulnerabilities       = intOf(m.raw, "vulnerabilities");
        m.codeSmells            = intOf(m.raw, "code_smells");
        m.securityHotspots      = intOf(m.raw, "security_hotspots");

        m.maintainabilityIssues = intOf(m.raw, "software_quality_maintainability_issues");
        m.reliabilityIssues     = intOf(m.raw, "software_quality_reliability_issues");
        m.securityIssues        = intOf(m.raw, "software_quality_security_issues");

        m.acceptedIssues           = intOf(m.raw, "accepted_issues");
        m.highImpactAcceptedIssues = intOf(m.raw, "high_impact_accepted_issues");

        m.newViolations         = intOf(m.raw, "new_violations");
        m.newBugs               = intOf(m.raw, "new_bugs");
        m.newVulnerabilities    = intOf(m.raw, "new_vulnerabilities");
        m.newCodeSmells         = intOf(m.raw, "new_code_smells");
        m.newAcceptedIssues     = intOf(m.raw, "new_accepted_issues");

        m.lines                 = intOf(m.raw, "lines");
        m.ncloc                 = intOf(m.raw, "ncloc");
        m.linesToCover          = intOf(m.raw, "lines_to_cover");

        m.coverage              = doubleOf(m.raw, "coverage");
        m.duplicatedLinesDensity = doubleOf(m.raw, "duplicated_lines_density");

        m.maintainabilityRating = doubleOf(m.raw, "software_quality_maintainability_rating");
        m.reliabilityRating     = doubleOf(m.raw, "software_quality_reliability_rating");
        m.securityRating        = doubleOf(m.raw, "software_quality_security_rating");
        m.securityReviewRating  = doubleOf(m.raw, "security_review_rating");
        m.sqaleRating           = doubleOf(m.raw, "sqale_rating");

        String nclocLang = m.raw.getOrDefault("ncloc_language_distribution", "");
        if (!nclocLang.isEmpty()) {
            for (String part : nclocLang.split(";")) {
                int idx = part.indexOf('=');
                if (idx > 0) {
                    String lang = part.substring(0, idx).trim();
                    try {
                        m.nclocByLanguage.put(lang,
                                Integer.parseInt(part.substring(idx + 1).trim()));
                    } catch (NumberFormatException ignore) {}
                }
            }
        }

        return m;
    }

    private static int intOf(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isEmpty()) return 0;
        try { return (int) Double.parseDouble(v); } catch (Exception e) { return 0; }
    }

    private static double doubleOf(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isEmpty()) return 0.0;
        try { return Double.parseDouble(v); } catch (Exception e) { return 0.0; }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("project", projectKey);
        o.put("alertStatus", alertStatus != null ? alertStatus : "");

        o.put("violations", violations);
        o.put("bugs", bugs);
        o.put("vulnerabilities", vulnerabilities);
        o.put("codeSmells", codeSmells);
        o.put("securityHotspots", securityHotspots);

        o.put("maintainabilityIssues", maintainabilityIssues);
        o.put("reliabilityIssues", reliabilityIssues);
        o.put("securityIssues", securityIssues);

        o.put("acceptedIssues", acceptedIssues);
        o.put("highImpactAcceptedIssues", highImpactAcceptedIssues);

        o.put("newViolations", newViolations);
        o.put("newBugs", newBugs);
        o.put("newVulnerabilities", newVulnerabilities);
        o.put("newCodeSmells", newCodeSmells);
        o.put("newAcceptedIssues", newAcceptedIssues);

        o.put("lines", lines);
        o.put("ncloc", ncloc);
        o.put("linesToCover", linesToCover);

        o.put("coverage", coverage);
        o.put("duplicatedLinesDensity", duplicatedLinesDensity);

        o.put("maintainabilityRating", maintainabilityRating);
        o.put("reliabilityRating", reliabilityRating);
        o.put("securityRating", securityRating);
        o.put("securityReviewRating", securityReviewRating);
        o.put("sqaleRating", sqaleRating);

        o.put("qualityGate", qualityGateJson != null ? qualityGateJson : "");

        JSONObject langs = new JSONObject();
        for (Map.Entry<String, Integer> e : nclocByLanguage.entrySet()) {
            langs.put(e.getKey(), e.getValue());
        }
        o.put("nclocByLanguage", langs);

        JSONObject rawObj = new JSONObject();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            rawObj.put(e.getKey(), e.getValue());
        }
        o.put("raw", rawObj);

        return o;
    }
}
```

## SonarMeasuresApi.java

```
package ru.miacomsoft.sonar;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Тонкий клиент к /api/measures/component.
 * По умолчанию запрашивает полный набор метрик, который нужен UI
 * (эквивалент запроса, который делает сам SonarQube UI).
 */
public class SonarMeasuresApi {

    /** Полный набор метрик, который отдаёт официальный UI SonarQube. */
    public static final String DEFAULT_METRIC_KEYS =
            "alert_status,quality_gate_details,new_violations,accepted_issues," +
                    "new_accepted_issues,high_impact_accepted_issues," +
                    "software_quality_maintainability_issues," +
                    "software_quality_reliability_issues," +
                    "software_quality_security_issues," +
                    "bugs,new_bugs,reliability_rating," +
                    "software_quality_reliability_rating,new_reliability_rating," +
                    "new_software_quality_reliability_rating," +
                    "vulnerabilities,new_vulnerabilities,security_rating," +
                    "software_quality_security_rating,new_security_rating," +
                    "new_software_quality_security_rating," +
                    "security_hotspots,new_security_hotspots,security_hotspots_reviewed," +
                    "new_security_hotspots_reviewed,security_review_rating," +
                    "new_security_review_rating," +
                    "code_smells,new_code_smells,sqale_rating," +
                    "software_quality_maintainability_rating,new_maintainability_rating," +
                    "new_software_quality_maintainability_rating," +
                    "sqale_index,new_technical_debt,coverage,new_coverage," +
                    "lines_to_cover,new_lines_to_cover,tests," +
                    "duplicated_lines_density,new_duplicated_lines_density," +
                    "duplicated_blocks,ncloc,ncloc_language_distribution,projects," +
                    "lines,new_lines,violations";

    private final SonarClient client;
    private final String metricKeys;

    public SonarMeasuresApi(SonarClient client) {
        this(client, DEFAULT_METRIC_KEYS);
    }

    public SonarMeasuresApi(SonarClient client, String metricKeys) {
        this.client = client;
        this.metricKeys = (metricKeys == null || metricKeys.isEmpty())
                ? DEFAULT_METRIC_KEYS
                : metricKeys;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public Measures fetch(String projectKey) throws Exception {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey не задан");
        }

        // Принудительно обрезаем пробелы, если они затесались
        projectKey = projectKey.trim();

        String url = "/api/measures/component"
                + "?additionalFields=" + enc("period,metrics")
                + "&component=" + enc(projectKey)
                + "&metricKeys=" + enc(metricKeys);
        JSONObject root = new JSONObject(client.get(url));
        return Measures.from(root, projectKey);
    }
}
```

## jira/JiraFieldService.java

```
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

    // ===== Методы для списков (Проекты, Типы, Приоритеты, Безопасность) =====

    public List<AllowedValue> getProjects() {
        List<AllowedValue> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(jira.get("/rest/api/2/project"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new AllowedValue(o.optString("key"), o.optString("name") + " (" + o.optString("key") + ")"));
            }
        } catch (Exception e) {
            System.err.println("[JiraFieldService] Error loading projects: " + e.getMessage());
        }
        return out;
    }

    /**
     * ИСПРАВЛЕНО: Добавлен fallback для старых версий Jira (до 8.4),
     * где эндпоинт /createmeta/{projectKey}/issuetypes может отсутствовать.
     * Если новый эндпоинт возвращает пустоту, пробуем старый.
     */
    public List<AllowedValue> getIssueTypes(String projectKey) {
        List<AllowedValue> out = new ArrayList<>();
        // 1) Пытаемся использовать новый эндпоинт (Jira 8.4+)
        try {
            JSONObject root = new JSONObject(jira.get("/rest/api/2/issue/createmeta/" + enc(projectKey) + "/issuetypes"));
            JSONArray arr = root.optJSONArray("values");
            if (arr != null && arr.length() > 0) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.getJSONObject(i);
                    out.add(new AllowedValue(it.optString("id"), it.optString("name")));
                }
                return out;
            }
        } catch (Exception ignored) { }

        // 2) Fallback для старых версий Jira
        try {
            JSONObject root = new JSONObject(jira.get("/rest/api/2/issue/createmeta?projectKeys="
                    + enc(projectKey) + "&expand=projects.issuetypes"));
            JSONArray projects = root.optJSONArray("projects");
            if (projects != null && projects.length() > 0) {
                JSONArray types = projects.getJSONObject(0).optJSONArray("issuetypes");
                if (types != null) {
                    for (int i = 0; i < types.length(); i++) {
                        JSONObject it = types.getJSONObject(i);
                        out.add(new AllowedValue(it.optString("id"), it.optString("name")));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[JiraFieldService] Error loading issueTypes for " + projectKey + ": " + e.getMessage());
        }
        return out;
    }

    public List<AllowedValue> getAllPriorities() {
        List<AllowedValue> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(jira.get("/rest/api/2/priority"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                out.add(new AllowedValue(p.optString("id"), p.optString("name")));
            }
        } catch (Exception e) {
            System.err.println("[JiraFieldService] Error loading priorities: " + e.getMessage());
        }
        return out;
    }

    public List<AllowedValue> getSecurityLevels(String projectKey, String issueTypeId) {
        Map<String, FieldMeta> meta = getCreatemeta(projectKey, issueTypeId);
        if (meta.containsKey("security")) return meta.get("security").allowedValues();
        return List.of();
    }

    public String findIssueTypeId(String projectKey, String typeName) {
        if (typeName != null && typeName.matches("\\d+")) return typeName;
        try {
            JSONObject root = new JSONObject(jira.get("/rest/api/2/issue/createmeta/" + enc(projectKey) + "/issuetypes"));
            JSONArray arr = root.optJSONArray("values");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.getJSONObject(i);
                    if (it.optString("name").equalsIgnoreCase(typeName)) return it.optString("id");
                }
            }
        } catch (Exception ignored) { }
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
```

## jira/JiraIssueDeleteService.java

```
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
```

## jira/JiraNfeedService.java

```
package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.jira.JiraClient;
import ru.miacomsoft.sonar.jira.JiraFieldService;

import java.util.ArrayList;
import java.util.List;

public class JiraNfeedService {

    private final JiraClient jira;

    public JiraNfeedService(JiraClient jira) { this.jira = jira; }

    public List<JiraFieldService.AllowedValue> loadOptions(String fieldId, String issueKeyOrId) {
        try {
            JSONObject body = new JSONObject()
                    .put("customFieldId", fieldId)
                    .put("userInput", "")
                    .put("view", "EDIT")
                    .put("formData", new JSONObject())
                    .put("startIndex", 0);
            if (issueKeyOrId != null) {
                body.put("fieldContext", new JSONObject().put("issueKeyOrId", issueKeyOrId));
            }
            String resp = jira.post("/rest/nfeed/3.0/nFeed/field/input/options", body.toString());
            if (resp == null || resp.isBlank()) return List.of();

            JSONArray arr;
            String t = resp.trim();
            if (t.startsWith("[")) arr = new JSONArray(t);
            else arr = new JSONObject(t).optJSONArray("options");
            if (arr == null) return List.of();

            List<JiraFieldService.AllowedValue> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", o.optString("value", null));
                String label = o.optString("value",
                        o.optString("label", o.optString("name", null)));
                out.add(new JiraFieldService.AllowedValue(id, label));
            }
            return out;
        } catch (Exception e) {
            System.out.println("  WARN: nfeed options " + fieldId + ": " + shortMsg(e));
            return List.of();
        }
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }
}
```

## jira/JiraTeamService.java

```
package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class JiraTeamService {

    private final JiraClient jira;

    public JiraTeamService(JiraClient jira) { this.jira = jira; }

    /**
     * Получает список всех доступных команд (Tempo Teams).
     * Использует эндпоинт picker/team, который возвращает объект с массивом "objects".
     */
    public List<JiraFieldService.AllowedValue> getTeams() {
        List<JiraFieldService.AllowedValue> out = new ArrayList<>();
        try {
            // Запрос к эндпоинту
            String resp = jira.get("/rest/tempo-teams/2/picker/team?expand=projects&query=");
            JSONObject root = new JSONObject(resp);

            // Ищем массив в ключе "objects" (как в вашем примере ответа)
            JSONArray arr = root.optJSONArray("objects");

            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.getJSONObject(i);
                    String id = t.optString("id");
                    String name = t.optString("name");
                    if (name != null && !name.isBlank()) {
                        out.add(new JiraFieldService.AllowedValue(id, name.trim()));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[JiraTeamService] Error loading teams: " + e.getMessage());
        }
        return out;
    }
}
```

## server/BootstrapFiles.java

```
package ru.miacomsoft.sonar.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * При старте проверяет наличие файлов, без которых сервер работает
 * в «пустом» режиме, и создаёт недостающие:
 *   - session.properties       (в корне проекта / cwd)
 *   - prompts/auto-resolve.txt
 *   - prompts/status-comment.txt
 *   - prompts/bulk-status-comment.txt
 *   - prompts/analyze-issue.txt
 *
 * Существующие файлы НИКОГДА не перезаписываются.
 */
public final class BootstrapFiles {

    private BootstrapFiles() {}

    public static void ensureAll(Path sessionFile, Path promptsDir) {
        ensureSession(sessionFile);
        ensurePrompts(promptsDir);
    }

    // ============================================================
    // session.properties
    // ============================================================

    public static void ensureSession(Path sessionFile) {
        if (sessionFile == null) return;
        try {
            if (Files.exists(sessionFile)) {
                System.out.println("[Bootstrap] session file found: "
                        + sessionFile.toAbsolutePath());
                return;
            }

            Path parent = sessionFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);

            Files.writeString(sessionFile,
                    Defaults.SESSION_PROPERTIES_TEMPLATE,
                    StandardCharsets.UTF_8);

            System.out.println();
            System.out.println("==========================================================");
            System.out.println("[Bootstrap] Создан файл конфигурации:");
            System.out.println("            " + sessionFile.toAbsolutePath());
            System.out.println();
            System.out.println("            Заполните поля sonar.url и sonar.token,");
            System.out.println("            затем перезапустите сервер или выполните:");
            System.out.println("              PUT /api/session   (тело — текст файла)");
            System.out.println();
            System.out.println("            Сейчас сервер стартует БЕЗ сессии —");
            System.out.println("            запросы к SonarQube вернут 503,");
            System.out.println("            пока конфиг не будет заполнен.");
            System.out.println("==========================================================");
            System.out.println();

        } catch (Exception e) {
            System.err.println("[Bootstrap] не удалось создать " + sessionFile
                    + ": " + e.getMessage());
        }
    }

    // ============================================================
    // prompts/
    // ============================================================

    public static void ensurePrompts(Path promptsDir) {
        if (promptsDir == null) return;
        try {
            Files.createDirectories(promptsDir);
        } catch (Exception e) {
            System.err.println("[Bootstrap] не удалось создать каталог " + promptsDir
                    + ": " + e.getMessage());
            return;
        }
        ensurePrompt(promptsDir, "auto-resolve",        Defaults.PROMPT_AUTO_RESOLVE);
        ensurePrompt(promptsDir, "status-comment",      Defaults.PROMPT_STATUS_COMMENT);
        ensurePrompt(promptsDir, "bulk-status-comment", Defaults.PROMPT_BULK_STATUS_COMMENT);
        ensurePrompt(promptsDir, "analyze-issue",       Defaults.PROMPT_ANALYZE_ISSUE);
    }

    private static void ensurePrompt(Path dir, String name, String content) {
        try {
            Path file = dir.resolve(name + ".txt");
            if (Files.exists(file)) return;
            Files.writeString(file, content, StandardCharsets.UTF_8);
            System.out.println("[Bootstrap] created " + file.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[Bootstrap] не удалось создать " + name + ".txt: "
                    + e.getMessage());
        }
    }

    public static Path resolvePromptsDir() {
        String sys = System.getProperty("sonar.prompts");
        if (sys != null && !sys.isBlank()) {
            Path p = Path.of(sys).toAbsolutePath().normalize();
            try { Files.createDirectories(p); } catch (Exception ignored) {}
            return p;
        }
        try {
            var cs = BootstrapFiles.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path loc = Path.of(cs.getLocation().toURI()).toAbsolutePath();
                Path dir = Files.isDirectory(loc) ? loc : loc.getParent();
                if (dir != null) {
                    Path beside = dir.resolve("prompts");
                    if (Files.isDirectory(beside)) return beside;
                    Path parent = dir.resolve("..").resolve("prompts").normalize();
                    if (Files.isDirectory(parent)) return parent;
                }
            }
        } catch (Exception ignored) {}

        Path cwd = Path.of("prompts").toAbsolutePath().normalize();
        try { Files.createDirectories(cwd); } catch (Exception ignored) {}
        return cwd;
    }
}
```

## server/ServerState.java

```
package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarClient;
import ru.miacomsoft.sonar.SonarIssuesApi;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class ServerState {

    public enum SnapshotStatus { EMPTY, BUILDING, READY, FAILED }

    private final Path sessionFile;
    private final String defaultProject;
    private final int batchSize;
    private final long delayMs;
    private final boolean warmupSnippets;
    private final int warmupSnippetLimit;

    private volatile SessionManager session;
    private volatile SonarClient    client;

    private volatile Snapshot snapshot;
    private volatile SnapshotStatus snapshotStatus = SnapshotStatus.EMPTY;
    private volatile String snapshotError;
    private volatile Instant snapshotStartedAt;
    private volatile String snapshotPhase;
    private volatile int snapshotProgress;
    private volatile int snapshotProgressTotal;

    private final Object buildLock = new Object();

    private final Map<String, String> ruleNameCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> snippetCache = new ConcurrentHashMap<>();

    public ServerState(Path sessionFile, String defaultProject, int batchSize, long delayMs,
                       boolean warmupSnippets, int warmupSnippetLimit) {
        this.sessionFile = sessionFile;
        this.defaultProject = defaultProject != null ? defaultProject : "";
        this.batchSize = batchSize;
        this.delayMs = delayMs;
        this.warmupSnippets = warmupSnippets;
        this.warmupSnippetLimit = warmupSnippetLimit;

        try {
            if (sessionFile != null && Files.exists(sessionFile)) {
                SessionManager sm = new SessionManager(sessionFile);
                this.session = sm;
                this.client = new SonarClient(sm);
                System.out.println("[ServerState] сессия загружена из " + sessionFile
                        + " (url=" + sm.getUrl()
                        + ", auth=" + sm.getAuthType()
                        + ", user=" + sm.getUser() + ")");
            } else {
                System.out.println("[ServerState] " + sessionFile
                        + " отсутствует — сервер стартует без сессии. "
                        + "Загрузите её через PUT /api/session");
            }
        } catch (Exception e) {
            System.err.println("[ServerState] не удалось загрузить " + sessionFile
                    + ": " + e.getMessage() + ". Сервер стартует без сессии.");
        }
    }

    public Path    getSessionFile()          { return sessionFile; }
    public String  getDefaultProject()       { return defaultProject; }
    public int     getBatchSize()            { return batchSize; }
    public long    getDelayMs()              { return delayMs; }
    public boolean isWarmupSnippets()        { return warmupSnippets; }
    public int     getWarmupSnippetLimit()   { return warmupSnippetLimit; }

    public boolean isSessionReady()          { return client != null; }
    public SessionManager getSession()       { return session; }

    public Snapshot getSnapshot()            { return snapshot; }
    public SnapshotStatus getSnapshotStatus(){ return snapshotStatus; }
    public String  getSnapshotError()        { return snapshotError; }
    public Instant getSnapshotStartedAt()    { return snapshotStartedAt; }
    public String  getSnapshotPhase()        { return snapshotPhase; }
    public int     getSnapshotProgress()     { return snapshotProgress; }
    public int     getSnapshotProgressTotal(){ return snapshotProgressTotal; }

    public boolean isSnapshotBuilding()      { return snapshotStatus == SnapshotStatus.BUILDING; }
    public boolean isSnapshotReady()         { return snapshot != null; }

    public SonarClient getClient() {
        SonarClient c = client;
        if (c == null) {
            throw new SessionNotReadyException(
                    "Сессия не инициализирована. Загрузите её через PUT /api/session "
                            + "(тело — текст session.properties).");
        }
        return c;
    }

    public SonarIssuesApi newApi(String projectKey) {
        return new SonarIssuesApi(getClient(), projectKey, ruleNameCache, snippetCache);
    }

    public SonarBulkApi newBulk() {
        return new SonarBulkApi(getClient(), batchSize, delayMs);
    }

    public synchronized String readRawFile() throws IOException {
        if (sessionFile == null || !Files.exists(sessionFile)) return "";
        return Files.readString(sessionFile, StandardCharsets.UTF_8);
    }

    public synchronized void update(String raw) throws IOException {
        SessionManager sm;
        try {
            sm = new SessionManager(raw);
        } catch (IllegalArgumentException e) {
            throw e;
        }

        Files.writeString(sessionFile, raw, StandardCharsets.UTF_8);
        this.session = sm;
        this.client  = new SonarClient(sm);
    }

    public synchronized void reload() throws IOException {
        if (sessionFile == null || !Files.exists(sessionFile)) {
            this.session = null;
            this.client = null;
            return;
        }
        SessionManager sm = new SessionManager(sessionFile);
        this.session = sm;
        this.client = new SonarClient(sm);
    }

    private void onProgress(String phase, int cur, int tot) {
        this.snapshotPhase = phase;
        this.snapshotProgress = cur;
        this.snapshotProgressTotal = tot;
    }

    public Snapshot buildSnapshotSync(String projectKey) throws Exception {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey не задан");
        }
        synchronized (buildLock) {
            snapshotStatus = SnapshotStatus.BUILDING;
            snapshotError = null;
            snapshotStartedAt = Instant.now();
            snapshotPhase = "starting";
            snapshotProgress = 0;
            snapshotProgressTotal = 0;

            snippetCache.clear();

            try {
                SonarIssuesApi api = newApi(projectKey);
                String host = session != null ? session.getHost() : "";
                Snapshot s = Snapshot.build(
                        api, projectKey, host,
                        warmupSnippets, warmupSnippetLimit,
                        this::onProgress);
                this.snapshot = s;
                snapshotStatus = SnapshotStatus.READY;
                snapshotPhase = "done";
                return s;
            } catch (Exception e) {
                snapshotError = e.getClass().getSimpleName() + ": " + e.getMessage();
                snapshotStatus = SnapshotStatus.FAILED;
                snapshotPhase = "failed";
                throw e;
            }
        }
    }

    public boolean buildSnapshotAsync(String projectKey) {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey не задан");
        }
        if (isSnapshotBuilding()) return false;
        Thread t = new Thread(() -> {
            try {
                buildSnapshotSync(projectKey);
            } catch (Exception e) {
                System.err.println("[Snapshot] async build failed: " + e.getMessage());
            }
        }, "snapshot-builder");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public RefreshResult refreshIssue(String projectKey, String issueKey) throws Exception {
        if (issueKey == null || issueKey.isEmpty()) {
            throw new IllegalArgumentException("issueKey не задан");
        }
        SonarIssuesApi api = newApi(projectKey);
        api.invalidateIssue(issueKey);

        Issue fresh = api.fetchIssue(issueKey);
        Snapshot current = snapshot;

        if (fresh == null) {
            if (current != null && current.projectKey.equals(projectKey)) {
                this.snapshot = current.withoutIssue(issueKey);
            }
            return RefreshResult.removed(issueKey, "not_found", null);
        }

        String st = fresh.status == null ? "" : fresh.status;
        // ИСПРАВЛЕНИЕ: Оставляем только OPEN и REOPENED, чтобы CONFIRMED и ACCEPTED тоже скрывались.
        boolean stillOpen = "OPEN".equals(st) || "REOPENED".equals(st);

        if (!stillOpen) {
            if (current != null && current.projectKey.equals(projectKey)) {
                this.snapshot = current.withoutIssue(issueKey);
            }
            return RefreshResult.removed(issueKey, "status_changed", fresh);
        }

        try { fresh.ruleName = api.fetchRuleName(fresh.rule); }
        catch (Exception ignored) { fresh.ruleName = ""; }
        List<String> snippet;
        try { snippet = api.fetchSnippet(issueKey); }
        catch (Exception e) { snippet = null; }

        if (current != null && current.projectKey.equals(projectKey)) {
            this.snapshot = current.withIssueAndSnippet(fresh, snippet);
        }
        return RefreshResult.updated(issueKey, fresh);
    }

    public static final class RefreshResult {
        public final String key;
        public final boolean removed;
        public final String reason;
        public final Issue issue;

        private RefreshResult(String key, boolean removed, String reason, Issue issue) {
            this.key = key;
            this.removed = removed;
            this.reason = reason;
            this.issue = issue;
        }

        static RefreshResult removed(String key, String reason, Issue lastKnown) {
            return new RefreshResult(key, true, reason, lastKnown);
        }

        static RefreshResult updated(String key, Issue issue) {
            return new RefreshResult(key, false, null, issue);
        }
    }
}
```

## server/SessionNotReadyException.java

```
package ru.miacomsoft.sonar.server;

/** Бросается, когда эндпоинт требует Sonar-сессию, но она ещё не загружена. */
public class SessionNotReadyException extends RuntimeException {
    public SessionNotReadyException(String message) {
        super(message);
    }
}
```

## server/Snapshot.java

```
package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Неизменяемый снимок состояния проекта в Sonar.
 * Содержит полный список issues, привязанные ruleName и snippets,
 * а также готовые фильтры/facets, которые вычисляются на лету.
 *
 * Точечное обновление одного issue реализовано через copy-on-write:
 * withIssue / withoutIssue возвращают новый Snapshot.
 */
public class Snapshot {

    public interface ProgressCallback {
        void update(String phase, int current, int total);
        ProgressCallback NOOP = (phase, cur, tot) -> {};
    }

    public final Instant builtAt;
    public final String projectKey;
    public final String host;
    public final List<Issue> issues;
    public final Map<String, Issue> byKey;
    public final Map<String, List<String>> snippets;

    public Snapshot(Instant builtAt, String projectKey, String host,
                    List<Issue> issues, Map<String, List<String>> snippets) {
        this.builtAt = builtAt;
        this.projectKey = projectKey;
        this.host = host;
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));

        Map<String, Issue> m = new HashMap<>(Math.max(16, issues.size() * 2));
        for (Issue i : issues) m.put(i.key, i);
        this.byKey = Collections.unmodifiableMap(m);

        this.snippets = Collections.unmodifiableMap(new HashMap<>(snippets));
    }

    /**
     * Полный проход по проекту:
     *   1. fetchAll("ALL","ALL") — все CONFIRMED/OPEN issues
     *   2. fetchRuleName для каждого issue (кэш на уровне ServerState — быстро)
     *   3. fetchSnippet для первых N issues (если fetchSnippets == true)
     */
    public static Snapshot build(SonarIssuesApi api, String projectKey, String host,
                                 boolean fetchSnippets, int snippetLimit,
                                 ProgressCallback cb) throws Exception {
        if (cb == null) cb = ProgressCallback.NOOP;
        Instant start = Instant.now();

        cb.update("fetching_issues", 0, 0);
        List<Issue> issues = api.fetchAll("ALL", "ALL");
        int total = issues.size();
        cb.update("fetching_issues", total, total);
        System.out.printf("[Snapshot] fetched %d issues%n", total);

        int step = 25;
        for (int i = 0; i < total; i++) {
            try { issues.get(i).ruleName = api.fetchRuleName(issues.get(i).rule); }
            catch (Exception e) { issues.get(i).ruleName = ""; }
            if ((i + 1) % step == 0 || i + 1 == total) {
                cb.update("resolving_rules", i + 1, total);
            }
        }
        System.out.printf("[Snapshot] rule names resolved (%d issues)%n", total);

        Map<String, List<String>> snippets = new HashMap<>();
        if (fetchSnippets) {
            int limit = snippetLimit > 0 ? Math.min(snippetLimit, total) : total;
            int sstep = 10;
            for (int i = 0; i < limit; i++) {
                Issue it = issues.get(i);
                try { snippets.put(it.key, api.fetchSnippet(it.key)); }
                catch (Exception e) { snippets.put(it.key, List.of()); }
                if ((i + 1) % sstep == 0 || i + 1 == limit) {
                    cb.update("fetching_snippets", i + 1, limit);
                }
            }
            System.out.printf("[Snapshot] snippets fetched (%d / %d)%n", snippets.size(), limit);
        } else {
            cb.update("fetching_snippets", 0, 0);
        }

        cb.update("done", 0, 0);
        long sec = Duration.between(start, Instant.now()).getSeconds();
        System.out.printf("[Snapshot] build complete in %ds%n", sec);

        return new Snapshot(Instant.now(), projectKey, host, issues, snippets);
    }

    // ================= фильтры =================

    public List<Issue> filter(String sev, String qual) {
        if ("ALL".equals(sev) && "ALL".equals(qual)) {
            // ИСПРАВЛЕНИЕ: Возвращаем копию, чтобы вызывающий код мог безопасно модифицировать список
            return new ArrayList<>(issues);
        }
        List<Issue> out = new ArrayList<>();
        for (Issue i : issues) {
            if (i.matches(sev, qual)) out.add(i);
        }
        return out;
    }

    public Map<String, List<Issue>> filterByFile(String sev, String qual) {
        Map<String, List<Issue>> byFile = new TreeMap<>();
        for (Issue i : filter(sev, qual)) {
            byFile.computeIfAbsent(i.component, k -> new ArrayList<>()).add(i);
        }
        for (List<Issue> list : byFile.values()) {
            list.sort(Comparator.comparingInt(i -> i.line));
        }
        return byFile;
    }

    public Map<String, Integer> facetRules(String sev, String qual) {
        Map<String, Integer> m = new TreeMap<>();
        for (Issue i : filter(sev, qual)) m.merge(i.rule, 1, Integer::sum);
        return m;
    }

    public Map<String, Integer> facetFiles(String sev, String qual) {
        Map<String, Integer> m = new TreeMap<>();
        for (Issue i : filter(sev, qual)) m.merge(i.component, 1, Integer::sum);
        return m;
    }

    public Map<String, Integer> facetSeverities(String sev, String qual) {
        Map<String, Integer> m = new TreeMap<>();
        for (Issue i : filter(sev, qual)) {
            String s = (i.impactSeverity != null && !i.impactSeverity.isEmpty())
                    ? i.impactSeverity
                    : (i.severity != null ? i.severity : "UNKNOWN");
            m.merge(s, 1, Integer::sum);
        }
        return m;
    }

    public List<String> snippet(String issueKey) {
        return snippets.getOrDefault(issueKey, List.of());
    }

    // ================= copy-on-write =================

    public Snapshot withIssue(Issue updated) {
        return withIssueAndSnippet(updated, null);
    }

    public Snapshot withIssueAndSnippet(Issue updated, List<String> newSnippet) {
        List<Issue> newIssues = new ArrayList<>(issues.size());
        boolean replaced = false;
        for (Issue i : issues) {
            if (i.key.equals(updated.key)) {
                newIssues.add(updated);
                replaced = true;
            } else {
                newIssues.add(i);
            }
        }
        if (!replaced) newIssues.add(updated);

        Map<String, List<String>> newSnippets = new HashMap<>(snippets);
        if (newSnippet != null && !newSnippet.isEmpty()) {
            newSnippets.put(updated.key, newSnippet);
        }
        return new Snapshot(builtAt, projectKey, host, newIssues, newSnippets);
    }

    public Snapshot withoutIssue(String issueKey) {
        List<Issue> newIssues = new ArrayList<>(issues.size());
        for (Issue i : issues) {
            if (!i.key.equals(issueKey)) newIssues.add(i);
        }
        Map<String, List<String>> newSnippets = new HashMap<>(snippets);
        newSnippets.remove(issueKey);
        return new Snapshot(builtAt, projectKey, host, newIssues, newSnippets);
    }
}
```

## server/TeamMapping.java

```
package ru.miacomsoft.sonar.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Парсит team_mapping.csv нового формата:
 *
 *   Колонка «Форма»              — путь к форме (например /Forms/ARMMainDoc/main_doc.frm)
 *   Колонка «Переопределяется»   — пути-алиасы (UserFormsXXX/...) того же файла
 *   Колонка «Компонент»          — подсистема / модуль (например "Статистика стационара")
 *   Колонка «Владелец тМИС»      — команда-владелец (например "HM3")
 *
 * Для каждого файла хранится запись (team, component) — оба поля опциональны.
 * Поиск выполняется по нормализованному пути (без префикса проекта и без ведущего слэша).
 *
 * Совместимость: findTeam(String) возвращает только команду (для legacy-кода).
 * Новый метод findInfo(String) возвращает полную запись FileInfo.
 */
public class TeamMapping {

    public static final Path FILE_PATH = Path.of("team_mapping.csv").toAbsolutePath().normalize();

    // ---------- Запись о файле ----------
    public static final class FileInfo {
        public final String team;       // "" если не задана
        public final String component;  // "" если не задан

        public FileInfo(String team, String component) {
            this.team      = (team      == null ? "" : team.trim());
            this.component = (component == null ? "" : component.trim());
        }

        public boolean isEmpty()      { return team.isEmpty() && component.isEmpty(); }
        public boolean hasTeam()      { return !team.isEmpty(); }
        public boolean hasComponent() { return !component.isEmpty(); }

        @Override
        public String toString() {
            return "FileInfo{team='" + team + "', component='" + component + "'}";
        }
    }

    private final Map<String, FileInfo> pathToInfo = new HashMap<>();

    // ---- Статистика (для UI) ----
    private int totalRows           = 0;
    private int rowsWithTeam        = 0;
    private int rowsWithComponent   = 0;
    private int rowsWithBoth        = 0;

    public static TeamMapping load() {
        TeamMapping m = new TeamMapping();
        m.loadInternal();
        return m;
    }

    public static boolean exists() {
        return Files.isRegularFile(FILE_PATH);
    }

    // ============================================================
    // Загрузка
    // ============================================================

    private void loadInternal() {
        if (!Files.isRegularFile(FILE_PATH)) {
            System.out.println("[TeamMapping] " + FILE_PATH + " не найден — команды/компоненты не подставляются");
            return;
        }
        try {
            String text = Files.readString(FILE_PATH, StandardCharsets.UTF_8);
            List<List<String>> rows = parseCsv(text);
            if (rows.isEmpty()) return;

            List<String> header = rows.get(0);
            // Убираем BOM из первого заголовка
            if (!header.isEmpty()) {
                header.set(0, header.get(0).replace("\uFEFF", "").trim());
            }

            int colForm       = findColumnExact(header, "Форма");
            int colComponent  = findColumnExact(header, "Компонент");
            int colTeam       = findColumnExact(header, "Владелец тМИС");
            int colOverridden = findColumnExact(header, "Переопределяется формами");

            if (colForm < 0) {
                System.err.println("[TeamMapping] В " + FILE_PATH
                        + " не найдена колонка 'Форма'. Формат не распознан.");
                return;
            }
            if (colTeam < 0 && colComponent < 0) {
                System.err.println("[TeamMapping] В " + FILE_PATH
                        + " не найдены ни 'Владелец тМИС', ни 'Компонент'. "
                        + "Загружаем только пути без привязки.");
                return;
            }

            System.out.println("[TeamMapping] Колонки: "
                    + "Форма=" + colForm
                    + ", Компонент=" + colComponent
                    + ", Владелец тМИС=" + colTeam
                    + (colOverridden >= 0 ? ", Переопределяется=" + colOverridden : ""));

            // Проход 1: канонические пути (колонка «Форма»)
            for (int r = 1; r < rows.size(); r++) {
                processRow(rows.get(r), colForm, colComponent, colTeam, false);
            }

            // Проход 2: алиасы из «Переопределяется формами»
            // (только если путь ещё не был зарегистрирован напрямую)
            if (colOverridden >= 0) {
                for (int r = 1; r < rows.size(); r++) {
                    processRow(rows.get(r), colOverridden, colComponent, colTeam, true);
                }
            }

            System.out.println("[TeamMapping] Загружено " + pathToInfo.size()
                    + " путей (строк: " + totalRows
                    + ", с командой: " + rowsWithTeam
                    + ", с компонентом: " + rowsWithComponent
                    + ", с обоими: " + rowsWithBoth + ")");

        } catch (Exception e) {
            System.err.println("[TeamMapping] Ошибка чтения " + FILE_PATH + ": " + e.getMessage());
        }
    }

    private void processRow(List<String> cells, int colPath,
                            int colComponent, int colTeam, boolean asAlias) {
        if (cells.size() <= colPath) return;

        String raw = cells.get(colPath);
        if (raw == null || raw.isBlank()) return;

        String team      = (colTeam      >= 0 && cells.size() > colTeam)      ? cells.get(colTeam).trim()      : "";
        String component = (colComponent >= 0 && cells.size() > colComponent) ? cells.get(colComponent).trim() : "";

        // Строка без team и без component — нечего сохранять
        if (team.isEmpty() && component.isEmpty()) return;

        if (!asAlias) {
            totalRows++;
            if (!team.isEmpty())      rowsWithTeam++;
            if (!component.isEmpty()) rowsWithComponent++;
            if (!team.isEmpty() && !component.isEmpty()) rowsWithBoth++;
        }

        FileInfo info = new FileInfo(team, component);

        // В колонке «Переопределяется» может быть несколько путей через \n
        for (String part : raw.split("\\r?\\n")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // Защита от мусорной кавычки в конце (встречается в реальных CSV)
            while (p.endsWith("\"")) p = p.substring(0, p.length() - 1).trim();
            if (p.isEmpty()) continue;

            String key = normPath(p);
            if (asAlias) {
                // Не перетираем канонические записи из первого прохода
                pathToInfo.putIfAbsent(key, info);
            } else {
                pathToInfo.put(key, info);
            }
        }
    }

    // ============================================================
    // Поиск
    // ============================================================

    /** Возвращает команду-владельца (или "") — legacy API. */
    public String findTeam(String component) {
        FileInfo info = findInfo(component);
        return info == null ? "" : info.team;
    }

    /** Возвращает полную запись (team + component) или null. */
    public FileInfo findInfo(String component) {
        if (component == null || component.isEmpty()) return null;

        // отрезаем префикс проекта SonarQube ("MYPROJ:/path/to/file.frm" → "/path/to/file.frm")
        String path = component;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);

        String normalized = normPath(path);

        FileInfo info = pathToInfo.get(normalized);
        if (info != null) return info;

        // попробовать без ведущего слэша
        String noSlash = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        info = pathToInfo.get(noSlash);
        if (info != null) return info;

        // попробовать со слэшем
        if (!normalized.startsWith("/")) {
            info = pathToInfo.get("/" + normalized);
        }
        return info;
    }

    // ---- Статистика для UI ----
    public int getTotalRows()         { return totalRows; }
    public int getRowsWithTeam()      { return rowsWithTeam; }
    public int getRowsWithComponent() { return rowsWithComponent; }
    public int getRowsWithBoth()      { return rowsWithBoth; }
    public int getPathCount()         { return pathToInfo.size(); }

    // ============================================================
    // Нормализация и утилиты
    // ============================================================

    private static String normPath(String s) {
        String n = s.replace('\\', '/').trim();
        // Очистка от возможных кавычек (CSV иногда бывает битым)
        while (n.startsWith("\"")) n = n.substring(1);
        while (n.endsWith("\""))   n = n.substring(0, n.length() - 1);
        n = n.toLowerCase(Locale.ROOT);
        if (!n.startsWith("/")) n = "/" + n;
        // убираем двойные слэши
        n = n.replaceAll("/{2,}", "/");
        return n;
    }

    private static String normalizeHeader(String s) {
        if (s == null) return "";
        return s.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
    }

    private static int findColumnExact(List<String> header, String name) {
        String target = normalizeHeader(name);
        for (int i = 0; i < header.size(); i++) {
            if (normalizeHeader(header.get(i)).equals(target)) return i;
        }
        return -1;
    }

    // ============================================================
    // CSV-парсер с поддержкой многострочных значений в кавычках
    // ============================================================

    /**
     * Устойчивый парсер:
     *   - значения в двойных кавычках могут содержать запятые и \n;
     *   - "\\"" внутри кавычек → литеральная кавычка;
     *   - одиночная кавычка ВНУТРИ незакавыченного значения трактуется
     *     как литерал (защита от битых CSV вроде "form.frm"").
     */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;

        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        int i = 0, len = text.length();

        while (i < len) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cur.append(c);
                i++;
                continue;
            }

            // не в кавычках
            if (c == '"' && cur.length() == 0) {
                inQuotes = true;
                i++;
                continue;
            }
            if (c == ',') {
                row.add(cur.toString());
                cur.setLength(0);
                i++;
                continue;
            }
            if (c == '\n') {
                row.add(cur.toString());
                cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    result.add(row);
                }
                row = new ArrayList<>();
                i++;
                continue;
            }
            if (c == '\r') {
                i++;
                continue;
            }
            cur.append(c);
            i++;
        }

        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            result.add(row);
        }
        return result;
    }
}
```

## server/console/SessionCommands.java

```
package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.SessionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Команда {@code session *}: работа с session.properties
 * (show / set / reload / save).
 */
public class SessionCommands {

    private final ConsoleContext ctx;

    public SessionCommands(ConsoleContext ctx) {
        this.ctx = ctx;
    }

    public void handle(String[] a) throws Exception {
        if (a.length == 0) { sessionHelp(); return; }
        ServerState state = ctx.state();
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "show" -> {
                String raw = state.readRawFile();
                System.out.println("path    = " + state.getSessionFile());
                System.out.println("exists  = " + !raw.isEmpty());
                if (!raw.isEmpty()) {
                    System.out.println("--- raw ---");
                    System.out.println(raw);
                    System.out.println("-----------");
                }
                SessionManager sm = state.getSession();
                if (sm != null) {
                    System.out.println("url     = " + sm.getUrl());
                    System.out.println("user    = " + sm.getUser());
                    System.out.println("auth    = " + sm.getAuthType());
                    System.out.println("hasToken= " + sm.hasToken());
                }
            }
            case "set" -> {
                if (a.length < 2) { System.out.println("usage: session set <path>"); return; }
                String raw = Files.readString(Path.of(a[1]), StandardCharsets.UTF_8);
                state.update(raw);
                System.out.println("[console] session updated from " + a[1]);
                if (state.getSession() != null) {
                    System.out.println("          url  = " + state.getSession().getUrl());
                    System.out.println("          auth = " + state.getSession().getAuthType());
                }
                if (ctx.requireProject()) {
                    state.buildSnapshotAsync(ctx.getProject());
                    System.out.println("[console] snapshot rebuild started in background");
                }
            }
            case "reload" -> {
                state.reload();
                System.out.println("[console] reloaded from " + state.getSessionFile());
                if (state.getSession() != null) {
                    System.out.println("          url  = " + state.getSession().getUrl());
                    System.out.println("          auth = " + state.getSession().getAuthType());
                }
            }
            case "save" -> {
                if (a.length < 2) { System.out.println("usage: session save <path>"); return; }
                String raw = state.readRawFile();
                Files.writeString(Path.of(a[1]), raw, StandardCharsets.UTF_8);
                System.out.println("[console] saved to " + a[1]);
            }
            default -> sessionHelp();
        }
    }

    private void sessionHelp() {
        System.out.println("session sub-commands:");
        System.out.println("  session show              — прочитать session.properties");
        System.out.println("  session set <path>        — заменить session.properties + reload + auto build");
        System.out.println("  session reload            — перечитать session.properties с диска");
        System.out.println("  session save <path>       — сохранить текущий текст в файл");
    }
}
```

## server/handlers/ExportDownloadHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ExportDownloadHandler extends BaseHandler {

    public ExportDownloadHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);
        String filePathStr = param(q, "file", null);
        String downloadName = param(q, "name", null);

        if (filePathStr == null || filePathStr.isEmpty()) {
            sendError(ex, 400, "Параметр file обязателен");
            return;
        }

        Path file = Path.of(filePathStr);
        if (!Files.exists(file)) {
            sendError(ex, 404, "Файл не найден (возможно, уже удален)");
            return;
        }

        byte[] bytes = Files.readAllBytes(file);

        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        String mime = "application/octet-stream";
        if ("txt".equals(ext))  mime = "text/plain";
        if ("md".equals(ext))   mime = "text/markdown";
        if ("html".equals(ext)) mime = "text/html";
        if ("zip".equals(ext))  mime = "application/zip";

        // Итоговое имя для Content-Disposition:
        //  - если клиент передал ?name= — используем его (санитайзим);
        //  - иначе — имя temp-файла.
        String useName = (downloadName != null && !downloadName.isBlank())
                ? sanitizeDownloadName(downloadName)
                : fileName;

        // RFC 5987 для не-ASCII (и заодно обычный filename= для совместимости)
        String asciiFallback = toAsciiFallback(useName);
        String encodedName = URLEncoder.encode(useName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        String contentDisposition = "attachment; filename=\"" + asciiFallback + "\""
                + "; filename*=UTF-8''" + encodedName;

        ex.getResponseHeaders().set("Content-Type", mime + "; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", contentDisposition);
        ex.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }

        try {
            file.toFile().deleteOnExit();
            System.out.println("[ExportDownload] Отдан файл: " + file.toAbsolutePath()
                    + " как \"" + useName + "\" (" + bytes.length + " байт)");
        } catch (Exception e) {
            System.err.println("[ExportDownload] Не удалось запланировать удаление: " + e.getMessage());
        }
    }

    /** Убирает из имени всё, что может сломать заголовок или пробить path traversal. */
    private static String sanitizeDownloadName(String raw) {
        if (raw == null) return "download";
        String s = raw
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\x00-\\x1F]", "_")
                .trim();
        // защита от пустого и от «.»
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) return "download";
        // не даём разрастись
        if (s.length() > 200) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 ? s.substring(dot) : "";
            s = s.substring(0, Math.min(200 - ext.length(), s.length())) + ext;
        }
        return s;
    }

    /** ASCII-версия имени для filename= (RFC 6266 fallback). */
    private static String toAsciiFallback(String s) {
        if (s == null) return "download";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F && c != '"' && c != '\\') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "download" : sb.toString();
    }
}
```

## server/handlers/ExportStreamHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.TeamMapping;
import ru.miacomsoft.sonar.server.exporters.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class ExportStreamHandler extends BaseHandler {

    private static final int SONAR_PAGE_LIMIT = 10000;

    /** Логический размер «пакета» для отображения в UI (SonarQube режет ps до 500). */
    private static final int BATCH_SIZE = 3000;

    public ExportStreamHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String format = param(q, "format", "html");
        String sev = param(q, "sev", "ALL");
        String qual = param(q, "qual", "ALL");
        String rule = param(q, "rule", null);
        boolean hideProcessed = paramBool(q, "hideProcessed", false);

        String tagsParam = param(q, "tags", "");
        String excludedTagsParam = param(q, "excludedTags", "");
        String selectedRulesParam = param(q, "selectedRules", "");
        String filterText = param(q, "filterText", "");
        String statusFilter = param(q, "statusFilter", "ALL");
        int expectedTotal = paramInt(q, "expectedTotal", -1);

        List<String> keys = null;
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            byte[] body = ex.getRequestBody().readAllBytes();
            if (body.length > 0) {
                try {
                    JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    if (req.has("keys")) {
                        JSONArray arr = req.getJSONArray("keys");
                        keys = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) keys.add(arr.getString(i));
                    }
                } catch (Exception e) {
                    System.err.println("[ExportStream] Ошибка парсинга POST тела: " + e.getMessage());
                }
            }
        }

        long exportStartMs = System.currentTimeMillis();

        System.out.println("[ExportStream] ▶ СТАРТ экспорта: project=" + project
                + ", format=" + format + ", sev=" + sev + ", qual=" + qual
                + ", rule=" + (rule == null ? "нет" : rule)
                + ", hideProcessed=" + hideProcessed
                + ", tags=" + tagsParam
                + ", excludedTags=" + excludedTagsParam
                + ", selectedRules=" + selectedRulesParam
                + ", filterText=" + filterText
                + ", statusFilter=" + statusFilter
                + ", expectedTotal=" + expectedTotal
                + ", keys=" + (keys == null ? "нет (запрос по фильтрам)" : keys.size() + " (отфильтровано клиентом)"));

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        try {
            if (keys != null && !keys.isEmpty()) {
                System.out.println("[ExportStream] Режим: обработка " + keys.size() + " переданных ключей (без доп. фильтрации)");
                runExportByKeys(os, project, format, keys, sev, qual);
            } else {
                System.out.println("[ExportStream] Режим: серверная выборка по фильтрам (без ключей). "
                        + "Пакеты по " + BATCH_SIZE + " записей (SonarQube режет ps до 500).");
                runExport(os, project, format, sev, qual, rule, hideProcessed,
                        tagsParam, excludedTagsParam, selectedRulesParam, filterText, statusFilter, expectedTotal);
            }
        } catch (Exception e) {
            System.err.println("[ExportStream] ОШИБКА: " + e.getMessage());
            e.printStackTrace();
            try { sendEvent(os, "error", new JSONObject().put("message", e.getMessage())); }
            catch (Exception ignored) {}
        } finally {
            long totalMs = System.currentTimeMillis() - exportStartMs;
            System.out.println("[ExportStream] ✅ ЭКСПОРТ ЗАВЕРШЁН за " + formatDuration(totalMs)
                    + " (" + totalMs + " ms), формат=" + format + ", project=" + project);
            try { os.close(); } catch (IOException ignored) {}
        }
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + " мс";
        long sec = ms / 1000;
        if (sec < 60) return String.format("%.1f с", ms / 1000.0);
        return String.format("%d мин %d с", sec / 60, sec % 60);
    }

    private static String buildDownloadName(String project, String format, long nowMs) {
        String safeProject = (project == null ? "project" : project)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeProject.isEmpty()) safeProject = "project";

        String ext = "html";
        if ("jira".equals(format)) ext = "txt";
        else if ("md".equals(format)) ext = "md";
        else if ("txt".equals(format)) ext = "txt";
        else if ("confluence".equals(format)) ext = "html";
        else if ("html".equals(format)) ext = "html";
        else if ("html-files".equals(format) || "html-files-teams".equals(format) ||
                "llm-md-zip".equals(format) || "team-module-zip".equals(format)) ext = "zip";

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date(nowMs));
        return safeProject + "_" + stamp + "." + ext;
    }

    private void sendEvent(OutputStream os, String event, JSONObject data) {
        try {
            String msg = "event: " + event + "\ndata: " + data.toString() + "\n\n";
            byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
            synchronized (os) {
                os.write(bytes);
                os.flush();
            }
            if (!"complete".equals(event) && !"progress".equals(event)) {
                System.out.println("[ExportStream] Событие: " + event + " -> " + data.toString());
            }
        } catch (Exception e) {
            String m = e.getMessage();
            if (m != null && m.contains("разорвала")) {
                // Клиент отключился
            } else {
                System.err.println("[ExportStream] Ошибка отправки события: " + m);
            }
        }
    }

    /**
     * Отправка прогресса в браузер.
     */
    private void sendProgress(OutputStream os, long startMs, String stage,
                              int current, int total, String message) {
        long elapsedMs = System.currentTimeMillis() - startMs;
        int safeTotal = Math.max(0, total);
        int safeCurrent = current;
        if (safeTotal > 0 && safeCurrent > safeTotal) safeCurrent = safeTotal;

        int batchCurrent = safeCurrent > 0
                ? (safeCurrent + BATCH_SIZE - 1) / BATCH_SIZE : 0;
        int batchTotal = safeTotal > 0
                ? (safeTotal + BATCH_SIZE - 1) / BATCH_SIZE : 0;

        long etaMs = -1;
        if (safeCurrent > 0 && safeTotal > 0) {
            if (safeCurrent >= safeTotal) {
                etaMs = 0;
            } else {
                etaMs = (long) (elapsedMs * ((double) safeTotal / safeCurrent - 1));
            }
        }

        String finalMessage = message == null ? "" : message;
        if (safeTotal > BATCH_SIZE && batchTotal > 0 && batchCurrent > 0) {
            finalMessage += String.format(" · пакет %d/%d", batchCurrent, batchTotal);
        }

        try {
            sendEvent(os, "progress", new JSONObject()
                    .put("stage", stage)
                    .put("total", safeTotal)
                    .put("current", safeCurrent)
                    .put("batchSize", BATCH_SIZE)
                    .put("batchCurrent", batchCurrent)
                    .put("batchTotal", batchTotal)
                    .put("etaMs", etaMs)
                    .put("elapsedMs", elapsedMs)
                    .put("elapsedText", formatDuration(elapsedMs))
                    .put("message", finalMessage));
        } catch (Exception ignored) {}
    }

    private static boolean is10000LimitError(Throwable e) {
        return e != null && e.getMessage() != null && e.getMessage().contains("10000");
    }

    private static boolean isZipFormat(String format) {
        return "html-files".equals(format) || "html-files-teams".equals(format)
                || "llm-md-zip".equals(format) || "team-module-zip".equals(format);
    }

    private static boolean needsSnippets(String format) {
        return isZipFormat(format) && !"llm-md-zip".equals(format) && !"team-module-zip".equals(format)
                || "confluence".equals(format);
    }

    private static boolean hasPostFilters(Set<String> selectedTags,
                                          Set<String> excludedTags,
                                          Set<String> selectedRules,
                                          String qText,
                                          String statusFilter) {
        return (selectedTags != null && !selectedTags.isEmpty())
                || (excludedTags != null && !excludedTags.isEmpty())
                || (selectedRules != null && !selectedRules.isEmpty())
                || (qText != null && !qText.isEmpty())
                || (statusFilter != null && !statusFilter.isEmpty() && !"ALL".equals(statusFilter));
    }

    private void fetchSnippetsParallel(OutputStream os, long startMs, String project,
                                       Map<String, List<Issue>> source,
                                       Map<String, List<String>> issueSnippets,
                                       int snippetCurrent, int snippetTotal) {
        if (source == null || source.isEmpty()) return;
        int totalForSnippets = 0;
        for (List<Issue> lst : source.values()) totalForSnippets += lst.size();
        if (totalForSnippets == 0) return;

        final int totalSnip = totalForSnippets;
        final int curSnip = snippetCurrent;
        final int totSnip = snippetTotal;

        System.out.println("[ExportStream] ▶ Загрузка фрагментов кода: " + totalSnip + " issues…");
        long phaseStart = System.currentTimeMillis();

        int poolSize = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        AtomicInteger doneCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (List<Issue> lst : source.values()) {
            for (Issue it : lst) {
                final Issue issue = it;
                futures.add(pool.submit(() -> {
                    try {
                        List<String> snip = fetchSnippetCached(project, issue.key);
                        if (snip != null && !snip.isEmpty()) {
                            issueSnippets.put(issue.key, snip);
                        }
                    } catch (Exception ignored) {}
                    int n = doneCount.incrementAndGet();
                    if (n % 25 == 0 || n == totalSnip) {
                        sendProgress(os, startMs, "snippets", curSnip, totSnip,
                                "Фрагменты кода: " + n + "/" + totalSnip);
                    }
                }));
            }
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        pool.shutdown();

        long phaseMs = System.currentTimeMillis() - phaseStart;
        System.out.println("[ExportStream] ✔ Сниппеты: " + issueSnippets.size()
                + "/" + totalSnip + " за " + formatDuration(phaseMs));
    }

    private static void appendIssueInline(StringBuilder reportContent, String format,
                                          Issue it, String project, String host) {
        if ("jira".equals(format)) reportContent.append(JiraExporter.exportSingle(it, project, host));
        else if ("md".equals(format)) reportContent.append(MdExporter.exportSingle(it, project, host));
        else if ("txt".equals(format)) reportContent.append(TxtExporter.exportSingle(it, project, host));
        else if ("html".equals(format)) reportContent.append(HtmlExporter.exportSingle(it, project, host));
    }

    private static boolean shouldSkip(Issue it,
                                      Set<String> selectedTags,
                                      Set<String> excludedTags,
                                      Set<String> selectedRules,
                                      String qText,
                                      String statusFilter) {
        if (statusFilter != null && !statusFilter.isEmpty() && !"ALL".equals(statusFilter)) {
            boolean isProcessed = !"OPEN".equals(it.status) && !"REOPENED".equals(it.status);
            if ("PROCESSED".equals(statusFilter) && !isProcessed) return true;
            if ("UNPROCESSED".equals(statusFilter) && isProcessed) return true;
        }
        if (!selectedTags.isEmpty()) {
            List<String> itTags = it.tags != null ? it.tags : Collections.emptyList();
            boolean hasMatch = false;
            for (String t : selectedTags) {
                if (t.equals("__NO_TAGS__")) { if (itTags.isEmpty()) hasMatch = true; }
                else if (itTags.contains(t)) hasMatch = true;
                if (hasMatch) break;
            }
            if (!hasMatch) return true;
        }
        if (!selectedRules.isEmpty() && !selectedRules.contains(it.rule)) return true;
        if (!qText.isEmpty()) {
            boolean m =
                    (it.key != null && it.key.toLowerCase().contains(qText)) ||
                            (it.message != null && it.message.toLowerCase().contains(qText)) ||
                            (it.component != null && it.component.toLowerCase().contains(qText));
            if (!m) return true;
        }
        if (!excludedTags.isEmpty()) {
            List<String> itTags = it.tags != null ? it.tags : Collections.emptyList();
            boolean hasExcluded = false;
            for (String t : excludedTags) {
                if (t.equals("__NO_TAGS__")) { if (itTags.isEmpty()) hasExcluded = true; }
                else if (itTags.contains(t)) hasExcluded = true;
                if (hasExcluded) break;
            }
            if (hasExcluded) return true;
        }
        return false;
    }

    private static void appendConfluenceHeader(StringBuilder reportContent, String project) {
        reportContent.append("<style>")
                .append("mark.sonar-issue-mark{background-color:rgba(248,81,73,0.25);")
                .append("border-bottom:2px solid #f85149;padding:0 2px;border-radius:2px;}")
                .append("details{margin-top:12px;border:1px solid #dfe1e6;border-radius:4px;background:#f4f5f7;}")
                .append("summary{cursor:pointer;padding:8px 12px;font-weight:600;color:#42526e;user-select:none;}")
                .append("summary:hover{background:#ebecf0;}")
                .append("pre{margin:0;padding:12px;background:#fff;overflow-x:auto;")
                .append("border-top:1px solid #dfe1e6;font-family:monospace;font-size:13px;white-space:pre-wrap;}")
                .append("</style>\n");
        reportContent.append("<h2>Проект: ").append(project).append("</h2>\n");
    }

    private void runExportByKeys(OutputStream os, String project, String format,
                                 List<String> keys, String sev, String qual) throws Exception {
        long startMs = System.currentTimeMillis();
        SonarIssuesApi api = state.newApi(project);
        String host = state.getSession() != null ? state.getSession().getHost() : "sonar.bars.group";

        List<String> uniqueKeys = new ArrayList<>(new LinkedHashSet<>(keys));
        if (uniqueKeys.size() != keys.size()) {
            System.out.println("[ExportStream] Дедуп ключей: " + keys.size() + " → " + uniqueKeys.size());
        }

        int total = uniqueKeys.size();
        boolean zipMode = isZipFormat(format);
        boolean isConfluence = "confluence".equals(format);
        boolean snippetsNeeded = needsSnippets(format);

        sendProgress(os, startMs, "start", 0, total, "Начинаем сбор по " + total + " ключам…");

        StringBuilder reportContent = new StringBuilder();
        Map<String, List<Issue>> issuesByFile = zipMode ? new TreeMap<>() : null;
        List<Issue> bufferedIssues = isConfluence ? new ArrayList<>() : null;
        Map<String, List<String>> issueSnippets = snippetsNeeded ? new HashMap<>() : null;
        int loadedCount = 0;
        Set<String> seenKeys = new HashSet<>();

        if (!zipMode) {
            if ("jira".equals(format)) reportContent.append("h3. Проект: ").append(project).append("\n\n");
            else if ("md".equals(format)) reportContent.append("# Отчет по проекту: ").append(project).append("\n\n");
            else if ("txt".equals(format)) reportContent.append("=== Отчет по проекту: ").append(project).append(" ===\n\n");
            else if ("html".equals(format)) {
                reportContent.append("<!DOCTYPE html><html><head><meta charset='utf-8'></head><body>\n");
                reportContent.append("<h1>Проект: ").append(project).append("</h1>\n");
            } else if (isConfluence) {
                appendConfluenceHeader(reportContent, project);
            }
        }

        int batchSize = 100;
        int totalKeyBatches = (uniqueKeys.size() + batchSize - 1) / batchSize;
        for (int i = 0; i < uniqueKeys.size(); i += batchSize) {
            int keyBatchNum = i / batchSize + 1;
            List<String> batch = uniqueKeys.subList(i, Math.min(i + batchSize, uniqueKeys.size()));
            String keysCsv = String.join(",", batch);
            try {
                JSONObject raw = api.fetchIssuesByKeys(keysCsv);
                JSONArray arr = raw.optJSONArray("issues");
                if (arr != null) {
                    for (int j = 0; j < arr.length(); j++) {
                        Issue it = Issue.from(arr.getJSONObject(j), project);
                        if (!seenKeys.add(it.key)) continue;

                        if (zipMode) {
                            String comp = it.component != null ? it.component : "(unknown)";
                            issuesByFile.computeIfAbsent(comp, k -> new ArrayList<>()).add(it);
                        } else if (isConfluence) {
                            bufferedIssues.add(it);
                        } else {
                            appendIssueInline(reportContent, format, it, project, host);
                        }
                        loadedCount++;
                    }
                }
            } catch (Exception e) {
                System.err.println("[ExportStream] Ошибка при запросе пачки ключей: " + e.getMessage());
            }
            sendProgress(os, startMs, "fetching", loadedCount, total,
                    String.format("Ключи %d/%d: загружено %d/%d",
                            keyBatchNum, totalKeyBatches, loadedCount, total));
        }

        if (snippetsNeeded) {
            Map<String, List<Issue>> source = zipMode
                    ? issuesByFile
                    : Collections.singletonMap("all", bufferedIssues);
            fetchSnippetsParallel(os, startMs, project, source, issueSnippets, loadedCount, total);
        }

        if (isConfluence) {
            for (Issue it : bufferedIssues) {
                reportContent.append(ConfluenceExporter.exportSingle(
                        it, project, host, issueSnippets.get(it.key)));
            }
        }

        long totalMs = System.currentTimeMillis() - startMs;
        Path tempFile;

        if (zipMode) {
            tempFile = Files.createTempFile("sonar_report_", ".zip");
            int entries = 0;

            if ("llm-md-zip".equals(format)) {
                entries = LlmMdExporter.writeZip(tempFile, issuesByFile, api, project, host, os, startMs);
            } else if ("team-module-zip".equals(format)) {
                TeamMapping mapping = TeamMapping.load();
                entries = TeamModuleMdExporter.writeZip(tempFile, issuesByFile, api, project, host, mapping, os, startMs);
            } else {
                boolean useTeams = "html-files-teams".equals(format);
                TeamMapping mapping = useTeams ? TeamMapping.load() : null;
                entries = HtmlPerFileExporter.writeZip(tempFile, issuesByFile, issueSnippets,
                        project, host, useTeams, mapping);
            }
            System.out.println("[ExportStream] runExportByKeys ZIP: " + entries + " файлов, "
                    + loadedCount + " issues, " + Files.size(tempFile) + " байт, за " + formatDuration(totalMs));
        } else {
            if ("html".equals(format)) reportContent.append("</body></html>");
            String ext = "html";
            if ("jira".equals(format)) ext = "txt";
            if ("md".equals(format)) ext = "md";
            if ("txt".equals(format)) ext = "txt";
            tempFile = Files.createTempFile("sonar_report_", "." + ext);
            Files.writeString(tempFile, reportContent.toString(), StandardCharsets.UTF_8);
            System.out.println("[ExportStream] runExportByKeys: " + loadedCount + "/" + total
                    + " issues, файл " + tempFile.toAbsolutePath() + ", за " + formatDuration(totalMs));
        }

        sendProgress(os, startMs, "done", loadedCount, loadedCount,
                "Готово: " + loadedCount + " issues");

        String downloadName = buildDownloadName(project, format, System.currentTimeMillis());
        System.out.println("[ExportStream] Имя скачиваемого файла: " + downloadName);

        sendEvent(os, "complete", new JSONObject()
                .put("file", tempFile.toAbsolutePath().toString())
                .put("downloadName", downloadName)
                .put("total", loadedCount)
                .put("expectedTotal", total)
                .put("elapsedMs", totalMs)
                .put("elapsedText", formatDuration(totalMs))
                .put("message", "Файл сформирован за " + formatDuration(totalMs)));
    }

    private void runExport(OutputStream os, String project, String format,
                           String sev, String qual, String rule, boolean hideProcessed,
                           String tagsParam, String excludedTagsParam,
                           String selectedRulesParam, String filterText,
                           String statusFilter, int expectedTotal) throws Exception {
        long startMs = System.currentTimeMillis();
        SonarIssuesApi api = state.newApi(project);
        String host = state.getSession() != null ? state.getSession().getHost() : "sonar.bars.group";

        // ИЗМЕНЕНО: раньше при hideProcessed=false передавалось statuses=null,
        // и SonarQube отбрасывал ACCEPTED/FALSE_POSITIVE/FIXED. Теперь для
        // полного отчёта передаём явный ALL_STATUSES (включает ACCEPTED).
        // sanitizeStatuses() внутри SonarIssuesApi сам смапит legacy → MQR
        // и уберёт то, что сервер не поддерживает.
        String statuses = hideProcessed
                ? SonarIssuesApi.ACTIVE_STATUSES
                : SonarIssuesApi.ALL_STATUSES;

        boolean zipMode = isZipFormat(format);
        boolean isConfluence = "confluence".equals(format);
        boolean snippetsNeeded = needsSnippets(format);

        System.out.println("[ExportStream] Фильтры: sev=" + sev + ", qual=" + qual
                + ", rule=" + (rule == null ? "нет" : rule)
                + ", hideProcessed=" + hideProcessed
                + ", statuses=" + (statuses == null ? "<все>" : statuses)
                + ", tags=" + tagsParam + ", excludedTags=" + excludedTagsParam
                + ", selectedRules=" + selectedRulesParam
                + ", filterText=" + filterText + ", statusFilter=" + statusFilter
                + (zipFormatDebug(zipMode))
                + (isConfluence ? " [CONFLUENCE MODE]" : ""));

        // Очищаем теги от __NO_TAGS__ для передачи в SonarQube API
        String sqTags = tagsParam;
        String sqNotTags = excludedTagsParam;
        if (sqTags != null && sqTags.contains("__NO_TAGS__")) sqTags = null;
        if (sqNotTags != null && sqNotTags.contains("__NO_TAGS__")) sqNotTags = null;

        sendProgress(os, startMs, "count", 0, 0, "Подсчет общего количества замечаний…");

        JSONObject firstRaw = api.fetchPageRaw(statuses, sev, qual, rule, sqTags, sqNotTags, 1, 1);
        int total = firstRaw.optInt("total", 0);
        if (expectedTotal > 0) {
            total = expectedTotal;
            System.out.println("[ExportStream] Используем expectedTotal от клиента: " + total);
        } else {
            System.out.println("[ExportStream] Всего замечаний от SonarQube: " + total);
        }

        if (total == 0) {
            sendEvent(os, "error", new JSONObject().put("message", "Нет замечаний для выгрузки"));
            return;
        }

        int plannedBatches = (total + BATCH_SIZE - 1) / BATCH_SIZE;
        sendProgress(os, startMs, "start", 0, total,
                "Начинаем сбор данных: " + total + " замечаний"
                        + (plannedBatches > 1
                        ? String.format(" (%d пакетов по %d)", plannedBatches, BATCH_SIZE)
                        : ""));

        StringBuilder reportContent = new StringBuilder();
        Map<String, List<Issue>> issuesByFile = zipMode ? new TreeMap<>() : null;
        List<Issue> bufferedIssues = isConfluence ? new ArrayList<>() : null;
        Map<String, List<String>> issueSnippets = snippetsNeeded ? new HashMap<>() : null;
        int loadedCount = 0;
        Set<String> seenKeys = new HashSet<>();

        if (!zipMode) {
            if ("jira".equals(format)) reportContent.append("h3. Проект: ").append(project).append("\n\n");
            else if ("md".equals(format)) reportContent.append("# Отчет по проекту: ").append(project).append("\n\n");
            else if ("txt".equals(format)) reportContent.append("=== Отчет по проекту: ").append(project).append(" ===\n\n");
            else if ("html".equals(format)) {
                reportContent.append("<!DOCTYPE html><html><head><meta charset='utf-8'></head><body>\n");
                reportContent.append("<h1>Проект: ").append(project).append("</h1>\n");
            } else if (isConfluence) {
                appendConfluenceHeader(reportContent, project);
            }
        }

        Set<String> selectedTags = new HashSet<>();
        if (!tagsParam.isEmpty()) for (String t : tagsParam.split(",")) if (!t.isEmpty()) selectedTags.add(t.trim());

        Set<String> excludedTags = new HashSet<>();
        if (!excludedTagsParam.isEmpty()) for (String t : excludedTagsParam.split(",")) if (!t.isEmpty()) excludedTags.add(t.trim());

        Set<String> selectedRules = new HashSet<>();
        if (!selectedRulesParam.isEmpty()) for (String r : selectedRulesParam.split(",")) if (!r.isEmpty()) selectedRules.add(r.trim());

        String qText = filterText == null ? "" : filterText.toLowerCase();

        Map<String, Integer> ruleCounts = new HashMap<>();
        Set<String> rulesToProcess = new HashSet<>();

        if (rule != null && !rule.isEmpty()) {
            rulesToProcess.add(rule);
            ruleCounts.put(rule, total);
        } else {
            sendProgress(os, startMs, "count", 0, total, "Считаем правила…");
            try {
                Map<String, Integer> facetRules = api.facetWithStatuses(statuses, sev, qual, null, "rules");
                for (Map.Entry<String, Integer> e : facetRules.entrySet()) {
                    String r = e.getKey();
                    if (selectedRules.isEmpty() || selectedRules.contains(r)) {
                        rulesToProcess.add(r);
                        ruleCounts.put(r, e.getValue());
                    }
                }
                System.out.println("[ExportStream] Найдено правил: " + rulesToProcess.size()
                        + " (фасеты: " + formatDuration(System.currentTimeMillis() - startMs) + ")");
                sendProgress(os, startMs, "count", 0, total,
                        "Найдено правил: " + rulesToProcess.size());
            } catch (Exception e) {
                System.err.println("[ExportStream] Не удалось получить фасеты правил: " + e.getMessage());
                rulesToProcess.add(null);
                ruleCounts.put(null, total);
            }
        }

        List<String> projectFiles = null;

        // ───────── цикл по правилам с индексацией ruleIdx/ruleTotal ─────────
        int ruleIdx = 0;
        int ruleTotal = rulesToProcess.size();

        for (String currentRule : rulesToProcess) {
            ruleIdx++;
            long ruleStartMs = System.currentTimeMillis();
            int expectedCount = ruleCounts.getOrDefault(currentRule, -1);

            System.out.println("[ExportStream] ▶ Обработка правила " + ruleIdx + "/" + ruleTotal
                    + ": " + (currentRule == null ? "все" : currentRule)
                    + (expectedCount >= 0 ? " (ожидается " + expectedCount + ")" : ""));

            sendProgress(os, startMs, "fetching", loadedCount, total,
                    String.format("▶ Правило %d/%d: %s (ожидается %d)",
                            ruleIdx, ruleTotal,
                            currentRule == null ? "все" : currentRule,
                            Math.max(0, expectedCount)));

            boolean needFileSplit = expectedCount > SONAR_PAGE_LIMIT;
            int ruleTotalIssues = expectedCount > 0 ? expectedCount : -1;
            int ruleLoaded = 0;
            int pageSize = SonarIssuesApi.FETCH_PAGE_SIZE;

            if (needFileSplit) {
                System.out.println("[ExportStream] Правило " + currentRule + " имеет " + expectedCount
                        + " issues > " + SONAR_PAGE_LIMIT + " → сразу file-split.");
            } else {
                int page = 1;
                while (true) {
                    try {
                        JSONObject raw = api.fetchPageRaw(statuses, sev, qual, currentRule, sqTags, sqNotTags, page, pageSize);
                        if (ruleTotalIssues < 0) {
                            ruleTotalIssues = raw.optInt("total", 0);
                            System.out.println("[ExportStream] Правило " + currentRule
                                    + ": всего " + ruleTotalIssues + " замечаний");
                        }
                        JSONArray arr = raw.optJSONArray("issues");
                        if (arr == null || arr.length() == 0) break;

                        for (int i = 0; i < arr.length(); i++) {
                            Issue it = Issue.from(arr.getJSONObject(i), project);
                            if (shouldSkip(it, selectedTags, excludedTags, selectedRules, qText, statusFilter)) continue;
                            if (!seenKeys.add(it.key)) continue;

                            if (zipMode) {
                                String comp = it.component != null ? it.component : "(unknown)";
                                issuesByFile.computeIfAbsent(comp, k -> new ArrayList<>()).add(it);
                            } else if (isConfluence) {
                                bufferedIssues.add(it);
                            } else {
                                appendIssueInline(reportContent, format, it, project, host);
                            }
                            loadedCount++;
                            ruleLoaded++;
                        }

                        sendProgress(os, startMs, "fetching", loadedCount, total,
                                String.format("Правило %d/%d: %s — собрано %d%s",
                                        ruleIdx, ruleTotal,
                                        currentRule == null ? "все" : currentRule,
                                        ruleLoaded,
                                        ruleTotalIssues > 0 ? "/" + ruleTotalIssues : ""));
                        page++;

                        if (ruleTotalIssues > 0 && ruleLoaded >= ruleTotalIssues) break;

                    } catch (Exception e) {
                        if (is10000LimitError(e)) {
                            System.out.println("[ExportStream] Правило " + currentRule
                                    + " упёрлось в лимит 10000 на странице " + page
                                    + " (собрано " + ruleLoaded + "). Переключаемся на file-split.");
                            needFileSplit = true;
                            break;
                        } else {
                            System.err.println("[ExportStream] Ошибка на странице " + page + ": " + e.getMessage());
                            sendEvent(os, "error", new JSONObject().put("message", e.getMessage()));
                            return;
                        }
                    }
                }
            }

            if (needFileSplit) {
                sendProgress(os, startMs, "fetching", loadedCount, total,
                        String.format("Правило %d/%d: %s — file-split (запрашиваем список файлов…)",
                                ruleIdx, ruleTotal,
                                currentRule == null ? "все" : currentRule));

                Map<String, Integer> ruleFiles = new TreeMap<>();
                int facetSum = 0;
                try {
                    ruleFiles = api.facetWithStatuses(statuses, sev, qual, currentRule, "files");
                    facetSum = ruleFiles.values().stream().mapToInt(Integer::intValue).sum();
                    System.out.println("[ExportStream] Facet files для " + currentRule + ": "
                            + ruleFiles.size() + " файлов, " + facetSum + " issues"
                            + (expectedCount > 0 ? " (ожидается " + expectedCount + ")" : ""));
                } catch (Exception e) {
                    System.err.println("[ExportStream] Facet files недоступен: " + e.getMessage());
                    ruleFiles = null;
                }

                List<String> filesToProcess;
                boolean facetComplete = ruleFiles != null && !ruleFiles.isEmpty()
                        && (expectedCount <= 0 || facetSum >= expectedCount);

                if (facetComplete) {
                    filesToProcess = new ArrayList<>(ruleFiles.keySet());
                    System.out.println("[ExportStream] Итерируем " + filesToProcess.size()
                            + " файлов из фасета (быстрый путь).");
                } else {
                    if (projectFiles == null) {
                        sendProgress(os, startMs, "fetching", loadedCount, total,
                                "Запрашиваем полный список файлов проекта…");
                        try {
                            long filesStartMs = System.currentTimeMillis();
                            projectFiles = api.fetchProjectFilesList();
                            System.out.println("[ExportStream] Получен полный список файлов: "
                                    + projectFiles.size()
                                    + " (за " + formatDuration(System.currentTimeMillis() - filesStartMs) + ")");
                        } catch (Exception e) {
                            System.err.println("[ExportStream] Не удалось получить список файлов: " + e.getMessage());
                            sendEvent(os, "error", new JSONObject()
                                    .put("message", "Не удалось получить список файлов для разбивки"));
                            return;
                        }
                    }
                    filesToProcess = projectFiles;
                    System.out.println("[ExportStream] Facet неполный (" + facetSum
                            + "), fallback на полный список: " + filesToProcess.size() + " файлов.");
                }

                int fileIdx = 0;
                int fileTotal = filesToProcess.size();
                for (String file : filesToProcess) {
                    fileIdx++;
                    int filePage = 1;
                    int fileTotalIssues = -1;
                    while (true) {
                        try {
                            JSONObject raw = api.fetchPageRawForComponent(statuses, sev, qual,
                                    currentRule, file, sqTags, sqNotTags, filePage, pageSize);
                            if (fileTotalIssues < 0) fileTotalIssues = raw.optInt("total", 0);

                            JSONArray arr = raw.optJSONArray("issues");
                            if (arr == null || arr.length() == 0) break;

                            for (int i = 0; i < arr.length(); i++) {
                                Issue it = Issue.from(arr.getJSONObject(i), project);
                                if (shouldSkip(it, selectedTags, excludedTags, selectedRules, qText, statusFilter)) continue;
                                if (!seenKeys.add(it.key)) continue;

                                if (zipMode) {
                                    String comp = it.component != null ? it.component : "(unknown)";
                                    issuesByFile.computeIfAbsent(comp, k -> new ArrayList<>()).add(it);
                                } else if (isConfluence) {
                                    bufferedIssues.add(it);
                                } else {
                                    appendIssueInline(reportContent, format, it, project, host);
                                }
                                loadedCount++;
                                ruleLoaded++;
                            }
                            filePage++;

                            sendProgress(os, startMs, "fetching", loadedCount, total,
                                    String.format("Правило %d/%d: %s — файл %d/%d (%d%s)",
                                            ruleIdx, ruleTotal,
                                            currentRule == null ? "все" : currentRule,
                                            fileIdx, fileTotal,
                                            ruleLoaded,
                                            ruleTotalIssues > 0 ? "/" + ruleTotalIssues : ""));

                            if (fileTotalIssues > 0 && filePage * pageSize > fileTotalIssues) break;

                        } catch (Exception e) {
                            if (is10000LimitError(e)) {
                                System.err.println("[ExportStream] Файл " + file
                                        + " содержит >10000 issues — невозможно разбить дальше, пропущен.");
                            } else {
                                System.err.println("[ExportStream] Ошибка при запросе файла " + file
                                        + ": " + e.getMessage());
                            }
                            break;
                        }
                    }
                }
            }

            long ruleMs = System.currentTimeMillis() - ruleStartMs;
            System.out.println("[ExportStream] ✔ Правило " + ruleIdx + "/" + ruleTotal
                    + " " + (currentRule == null ? "все" : currentRule)
                    + ": собрано " + ruleLoaded + (ruleTotalIssues > 0 ? "/" + ruleTotalIssues : "")
                    + " issues за " + formatDuration(ruleMs));

            sendProgress(os, startMs, "fetching", loadedCount, total,
                    String.format("✔ Правило %d/%d завершено: +%d (накоплено %d/%d)",
                            ruleIdx, ruleTotal, ruleLoaded, loadedCount, total));
        }

        if (snippetsNeeded) {
            Map<String, List<Issue>> source = zipMode
                    ? issuesByFile
                    : Collections.singletonMap("all", bufferedIssues);
            fetchSnippetsParallel(os, startMs, project, source, issueSnippets, loadedCount, loadedCount);
        }

        if (isConfluence) {
            for (Issue it : bufferedIssues) {
                reportContent.append(ConfluenceExporter.exportSingle(
                        it, project, host, issueSnippets.get(it.key)));
            }
        }

        long totalMs = System.currentTimeMillis() - startMs;
        Path tempFile;

        if (zipMode) {
            tempFile = Files.createTempFile("sonar_report_", ".zip");
            int entries = 0;

            if ("llm-md-zip".equals(format)) {
                entries = LlmMdExporter.writeZip(tempFile, issuesByFile, api, project, host, os, startMs);
            } else if ("team-module-zip".equals(format)) {
                TeamMapping mapping = TeamMapping.load();
                entries = TeamModuleMdExporter.writeZip(tempFile, issuesByFile, api, project, host, mapping, os, startMs);
            } else {
                boolean useTeams = "html-files-teams".equals(format);
                TeamMapping mapping = useTeams ? TeamMapping.load() : null;
                entries = HtmlPerFileExporter.writeZip(tempFile, issuesByFile, issueSnippets,
                        project, host, useTeams, mapping);
            }

            System.out.println("[ExportStream] runExport ZIP: " + entries + " файлов, "
                    + loadedCount + " issues, " + Files.size(tempFile) + " байт, за " + formatDuration(totalMs));
        } else {
            if ("html".equals(format)) reportContent.append("</body></html>");
            String ext = "html";
            if ("jira".equals(format)) ext = "txt";
            if ("md".equals(format)) ext = "md";
            if ("txt".equals(format)) ext = "txt";
            tempFile = Files.createTempFile("sonar_report_", "." + ext);
            Files.writeString(tempFile, reportContent.toString(), StandardCharsets.UTF_8);
            System.out.println("[ExportStream] runExport: " + loadedCount + "/" + total
                    + " issues, файл " + tempFile.toAbsolutePath() + ", за " + formatDuration(totalMs));
        }

        boolean postFiltersActive = hasPostFilters(selectedTags, excludedTags, selectedRules, qText, statusFilter);
        if (loadedCount != total) {
            if (postFiltersActive) {
                System.out.println("[ExportStream] ℹ Экспортировано " + loadedCount
                        + " из " + total + " (отфильтровано " + (total - loadedCount)
                        + " post-фильтрами: tags/excludedTags/text/statusFilter)");
            } else {
                System.out.println("[ExportStream] ⚠ РАСХОЖДЕНИЕ: собрано=" + loadedCount
                        + ", total=" + total + ", разница=" + (loadedCount - total));
            }
        } else {
            System.out.println("[ExportStream] ✅ Сверка OK: собрано = total = " + loadedCount);
        }

        sendProgress(os, startMs, "done", loadedCount, loadedCount,
                "Готово: " + loadedCount + " issues"
                        + (postFiltersActive && total > loadedCount
                        ? " (отфильтровано " + (total - loadedCount) + " post-фильтрами)"
                        : ""));

        String downloadName = buildDownloadName(project, format, System.currentTimeMillis());
        System.out.println("[ExportStream] Имя скачиваемого файла: " + downloadName);

        sendEvent(os, "complete", new JSONObject()
                .put("file", tempFile.toAbsolutePath().toString())
                .put("downloadName", downloadName)
                .put("total", loadedCount)
                .put("expectedTotal", total)
                .put("elapsedMs", totalMs)
                .put("elapsedText", formatDuration(totalMs))
                .put("message", "Файл сформирован за " + formatDuration(totalMs)));
    }

    private String zipFormatDebug(boolean zipMode) {
        return zipMode ? " [ZIP MODE]" : "";
    }
}
```

## server/handlers/HealthHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.time.Instant;

public class HealthHandler extends BaseHandler {

    public HealthHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        JSONObject o = new JSONObject();
        o.put("status", "ok");
        o.put("sessionReady", state.isSessionReady());
        o.put("snapshotStatus", state.getSnapshotStatus().name());
        o.put("snapshotReady", state.isSnapshotReady());

        // Добавлены данные для прогресс-бара
        o.put("snapshotPhase", state.getSnapshotPhase() == null ? "" : state.getSnapshotPhase());
        o.put("snapshotProgress", state.getSnapshotProgress());
        o.put("snapshotProgressTotal", state.getSnapshotProgressTotal());

        if (state.getSnapshot() != null) {
            o.put("snapshotProject", state.getSnapshot().projectKey);
            o.put("snapshotIssuesCount", state.getSnapshot().issues.size());
            o.put("snapshotBuiltAt", state.getSnapshot().builtAt.toString());
        }
        o.put("defaultProject", state.getDefaultProject());
        o.put("timestamp", Instant.now().toString());
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/MarkHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/mark
 *
 * Два режима:
 *   1) ?key=ISSUE_KEY&transition=...&comment=...&dryRun=true|false
 *      — пометить ОДИН issue. transition+comment идут в историю перехода
 *        (комментарий прикрепляется к переходу, а не отдельным add_comment).
 *
 *   2) ?sev=&qual=&transition=...&comment=...&dryRun=true|false
 *      — массовая пометка всех issues по фильтру (батчами по 500).
 *
 * Если transition = reopen, используется per-issue do_transition
 * (bulk_change с reopen работает не на всех версиях SonarQube).
 *
 * dryRun=true по умолчанию — ничего не меняется, только план.
 */
public class MarkHandler extends BaseHandler {

    public MarkHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String key = param(q, "key", null);
        String sev = param(q, "sev", "MEDIUM");
        String qual = param(q, "qual", "MAINTAINABILITY");
        String transition = param(q, "transition", null);
        String comment = param(q, "comment", "");
        boolean dryRun = paramBool(q, "dryRun", true);

        if (transition == null || transition.isEmpty()) {
            sendError(ex, 400, "Параметр transition обязателен");
            return;
        }

        SonarBulkApi bulk = newBulk();

        if (key != null && !key.isEmpty()) {
            handleSingle(ex, project, key, transition, comment, dryRun, bulk);
        } else {
            handleBulk(ex, project, sev, qual, transition, comment, dryRun, bulk);
        }
    }

    // ---------- single ----------

    private void handleSingle(HttpExchange ex, String project, String key,
                              String transition, String comment, boolean dryRun,
                              SonarBulkApi bulk) throws Exception {
        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("key", key);
        o.put("transition", transition);
        o.put("comment", comment);
        o.put("dryRun", dryRun);

        if (dryRun) {
            o.put("applied", false);
            sendJson(ex, 200, o);
            return;
        }

        boolean success;
        try {
            Issue stub = new Issue();
            stub.key = key;

            if ("reopen".equalsIgnoreCase(transition)) {
                Set<String> failed = bulk.reopenIssues(List.of(stub));
                success = !failed.contains(key);
                if (!success) o.put("error", "reopen failed");
            } else {
                bulk.change(List.of(stub), transition, comment);
                success = true;
            }
        } catch (Exception e) {
            success = false;
            o.put("error", e.getMessage());
        }

        o.put("applied", true);
        o.put("success", success);
        sendJson(ex, 200, o);
    }

    // ---------- bulk ----------

    private void handleBulk(HttpExchange ex, String project, String sev, String qual,
                            String transition, String comment, boolean dryRun,
                            SonarBulkApi bulk) throws Exception {
        SonarIssuesApi api = newApi(project);
        List<Issue> issues = api.fetchAll(sev, qual);

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("transition", transition);
        o.put("comment", comment);
        o.put("issuesCount", issues.size());
        o.put("dryRun", dryRun);
        o.put("batches", bulk.batchCount(issues.size()));

        if (dryRun) {
            o.put("applied", false);
            sendJson(ex, 200, o);
            return;
        }

        if ("reopen".equalsIgnoreCase(transition)) {
            Set<String> failed = bulk.reopenIssues(issues);
            o.put("applied", true);
            o.put("succeeded", issues.size() - failed.size());
            o.put("failed", failed.size());
            o.put("failedKeys", new JSONArray(failed));
        } else {
            List<String> responses = bulk.change(issues, transition, comment);
            o.put("applied", true);
            o.put("responses", new JSONArray(responses));
        }
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/MetadataHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GET /api/metadata?project=KEY
 *
 * Сводная справочная информация о проекте:
 *   - languages     : /api/languages/list
 *   - branches      : /api/project_branches/list?project=KEY
 *   - pullRequests  : /api/project_pull_requests/list?project=KEY
 *   - settings      : /api/settings/values?keys=sonar.announcement.*
 *
 * Каждый источник обёрнут в try/catch: если один недоступен — остальные возвращаются.
 */
public class MetadataHandler extends BaseHandler {

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public MetadataHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        JSONObject o = new JSONObject();
        o.put("project", project);

        if (paramBool(q, "languages", true))    o.put("languages",    safeArray(this::fetchLanguages));
        if (paramBool(q, "branches", true))     o.put("branches",     safeArray(() -> fetchBranches(project)));
        if (paramBool(q, "pullRequests", true)) o.put("pullRequests", safeArray(() -> fetchPullRequests(project)));
        if (paramBool(q, "settings", true))     o.put("settings",     safeObject(this::fetchSettings));

        putSource(o, project);
        sendJson(ex, 200, o);
    }

    private JSONArray fetchLanguages() throws Exception {
        JSONObject r = new JSONObject(state.getClient().get("/api/languages/list"));
        JSONArray a = r.optJSONArray("languages");
        return a != null ? a : new JSONArray();
    }

    private JSONArray fetchBranches(String project) throws Exception {
        String p = URLEncoder.encode(project, StandardCharsets.UTF_8);
        JSONObject r = new JSONObject(state.getClient()
                .get("/api/project_branches/list?project=" + p));
        JSONArray a = r.optJSONArray("branches");
        return a != null ? a : new JSONArray();
    }

    private JSONArray fetchPullRequests(String project) throws Exception {
        String p = URLEncoder.encode(project, StandardCharsets.UTF_8);
        JSONObject r = new JSONObject(state.getClient()
                .get("/api/project_pull_requests/list?project=" + p));
        JSONArray a = r.optJSONArray("pullRequests");
        return a != null ? a : new JSONArray();
    }

    private JSONObject fetchSettings() throws Exception {
        String keys = URLEncoder.encode(
                "sonar.announcement.displayMessage,sonar.announcement.message",
                StandardCharsets.UTF_8);
        return new JSONObject(state.getClient()
                .get("/api/settings/values?keys=" + keys));
    }

    private JSONArray safeArray(ThrowingSupplier<JSONArray> s) {
        try { return s.get(); } catch (Exception e) { return new JSONArray(); }
    }

    private JSONObject safeObject(ThrowingSupplier<JSONObject> s) {
        try { return s.get(); } catch (Exception e) { return new JSONObject(); }
    }
}
```

## server/handlers/PerFileHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class PerFileHandler extends BaseHandler {

    public PerFileHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String sev = param(q, "sev", "MEDIUM");
        String qual = param(q, "qual", "MAINTAINABILITY");
        String rule = param(q, "rule", null);
        boolean hideProcessed = paramBool(q, "hideProcessed", false);
        int page = paramInt(q, "p", 1);
        int ps = paramInt(q, "ps", 50);

        // ИСПРАВЛЕНИЕ: Если hideProcessed = false, то statuses = null (все статусы).
        // Если true, то только активные.
        String statuses = hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : null;

        SonarIssuesApi api = newApi(project);
        JSONObject rawResponse = api.fetchPageRaw(statuses, sev, qual, rule, page, ps);
        JSONArray rawIssues = rawResponse.optJSONArray("issues");

        Map<String, List<Issue>> byFile = new TreeMap<>();
        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                Issue it = Issue.from(rawIssues.getJSONObject(i), project);
                byFile.computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
            }
        }

        JSONArray files = new JSONArray();
        int totalIssuesOnPage = 0;
        for (Map.Entry<String, List<Issue>> e : byFile.entrySet()) {
            List<Issue> issues = e.getValue();
            totalIssuesOnPage += issues.size();

            JSONArray arr = new JSONArray();
            for (Issue it : issues) arr.put(it.toJson());

            JSONObject f = new JSONObject();
            f.put("file", e.getKey());
            f.put("count", issues.size());
            f.put("issues", arr);
            files.put(f);
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("rule", rule != null ? rule : "");
        o.put("total", rawResponse.optInt("total", 0));
        o.put("p", page);
        o.put("ps", ps);
        o.put("filesCount", byFile.size());
        o.put("issuesCount", totalIssuesOnPage);
        o.put("files", files);

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/SessionReloadHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.SessionManager;

import java.util.Map;

/**
 * POST /api/session/reload?build=true&project=KEY
 * Перечитывает session.properties с диска и (по умолчанию) запускает async-пересбор.
 */
public class SessionReloadHandler extends BaseHandler {

    public SessionReloadHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);
        boolean build = paramBool(q, "build", true);
        String project = param(q, "project", null);

        try {
            state.reload();
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
            return;
        } catch (Exception e) {
            sendError(ex, 500, "Не удалось перезагрузить сессию: " + e.getMessage());
            return;
        }

        String targetProject = (project != null && !project.isEmpty())
                ? project : state.getDefaultProject();
        boolean buildStarted = false;
        if (build && targetProject != null && !targetProject.isEmpty()
                && state.isSessionReady()) {
            try { buildStarted = state.buildSnapshotAsync(targetProject); }
            catch (Exception e) {
                System.err.println("[SessionReloadHandler] async build not started: " + e.getMessage());
            }
        }

        JSONObject o = new JSONObject();
        o.put("reloaded", true);
        o.put("path", state.getSessionFile() == null
                ? "" : state.getSessionFile().toString());
        o.put("ready", state.isSessionReady());

        SessionManager sm = state.getSession();
        if (sm != null) {
            o.put("url",      sm.getUrl());
            o.put("user",     sm.getUser());
            o.put("authType", sm.getAuthType());
            o.put("hasToken", sm.hasToken());
            o.put("hasCookie", sm.hasCookie());
        }
        o.put("buildStarted", buildStarted);
        if (buildStarted) {
            o.put("buildProject", targetProject);
            o.put("note", "Пересбор снимка идёт в фоне, прогресс: GET /api/status");
        }
        sendJson(ex, 200, o);
    }
}
```

