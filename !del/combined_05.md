# Combined part 5 of 5

- files: 20
- bytes: 136434

## Issue.java

```
package ru.miacomsoft.sonar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Issue {

    public static class Impact {
        public String softwareQuality;
        public String severity;

        public Impact() {}
        public Impact(String softwareQuality, String severity) {
            this.softwareQuality = softwareQuality;
            this.severity = severity;
        }
    }

    public static class TextRange {
        public int startLine;
        public int endLine;
        public int startOffset;
        public int endOffset;
    }

    public static class Comment {
        public String key;
        public String login;
        public String htmlText;
        public String markdown;
        public String createdAt;
    }

    public String key;
    public String rule;
    public String ruleName;
    public String severity;
    public String impactSeverity;
    public String component;
    public int line;
    public String message;
    public String status;
    public String resolution;
    public String issueStatus;
    public String effort;
    public String type;
    public String author;
    public String creationDate;
    public String updateDate;
    public int flowsCount;
    public List<String> tags = new ArrayList<>();
    public List<String> transitions = new ArrayList<>();
    public List<Impact> impacts = new ArrayList<>();
    public List<Comment> comments = new ArrayList<>();
    public TextRange textRange;

    // НОВОЕ ПОЛЕ: для хранения фрагмента кода при генерации отчетов
    public List<String> snippet = new ArrayList<>();

    public static Issue from(JSONObject o, String projectKey) {
        Issue i = new Issue();
        i.key = o.optString("key");
        i.rule = o.optString("rule");
        i.severity = o.optString("severity");
        i.component = o.optString("component");
        if (projectKey != null && i.component.startsWith(projectKey + ":")) {
            i.component = i.component.substring(projectKey.length() + 1);
        }
        i.line = o.optInt("line", -1);
        i.message = o.optString("message");
        i.status = o.optString("status");
        i.resolution = o.optString("resolution");
        i.issueStatus = o.optString("issueStatus");
        i.effort = o.optString("effort");
        i.type = o.optString("type");
        i.author = o.optString("author");
        i.creationDate = o.optString("creationDate");
        i.updateDate = o.optString("updateDate");

        JSONObject tr = o.optJSONObject("textRange");
        if (tr != null) {
            i.textRange = new TextRange();
            i.textRange.startLine = tr.optInt("startLine");
            i.textRange.endLine = tr.optInt("endLine");
            i.textRange.startOffset = tr.optInt("startOffset");
            i.textRange.endOffset = tr.optInt("endOffset");
        }

        JSONArray impacts = o.optJSONArray("impacts");
        if (impacts != null) {
            for (int k = 0; k < impacts.length(); k++) {
                JSONObject im = impacts.getJSONObject(k);
                i.impacts.add(new Impact(
                        im.optString("softwareQuality", ""),
                        im.optString("severity", "")
                ));
            }
        }
        if (!i.impacts.isEmpty()) {
            i.impactSeverity = i.impacts.get(0).severity;
        }

        JSONArray commentsArr = o.optJSONArray("comments");
        if (commentsArr != null) {
            for (int c = 0; c < commentsArr.length(); c++) {
                JSONObject co = commentsArr.getJSONObject(c);
                Comment cm = new Comment();
                cm.key = co.optString("key");
                cm.login = co.optString("login");
                cm.htmlText = co.optString("htmlText");
                cm.markdown = co.optString("markdown");
                cm.createdAt = co.optString("createdAt");
                i.comments.add(cm);
            }
        }

        JSONArray flows = o.optJSONArray("flows");
        if (flows != null) i.flowsCount = flows.length();

        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            for (int t = 0; t < tags.length(); t++) i.tags.add(tags.getString(t));
        }
        JSONArray trans = o.optJSONArray("transitions");
        if (trans != null) {
            for (int t = 0; t < trans.length(); t++) i.transitions.add(trans.getString(t));
        }
        return i;
    }

    public boolean matches(String sev, String qual) {
        boolean sevAll = sev == null || sev.isEmpty() || "ALL".equals(sev);
        boolean qualAll = qual == null || qual.isEmpty() || "ALL".equals(qual);
        if (sevAll && qualAll) return true;

        if (impacts.isEmpty()) {
            if (!sevAll && severity != null && !severity.isEmpty() && !severity.equals(sev)) {
                return false;
            }
            return true;
        }

        for (Impact im : impacts) {
            boolean sOk = sevAll || sev.equals(im.severity);
            boolean qOk = qualAll || qual.equals(im.softwareQuality);
            if (sOk && qOk) return true;
        }
        return false;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("key", key);
        o.put("rule", rule);
        o.put("ruleName", ruleName != null ? ruleName : "");
        o.put("severity", severity != null ? severity : "");
        o.put("impactSeverity", impactSeverity != null ? impactSeverity : "");
        o.put("component", component);
        o.put("line", line);
        o.put("message", message);
        o.put("status", status != null ? status : "");
        o.put("resolution", resolution != null ? resolution : "");
        o.put("issueStatus", issueStatus != null ? issueStatus : "");
        o.put("effort", effort != null ? effort : "");
        o.put("type", type != null ? type : "");
        o.put("author", author != null ? author : "");
        o.put("creationDate", creationDate != null ? creationDate : "");
        o.put("updateDate", updateDate != null ? updateDate : "");
        o.put("flowsCount", flowsCount);
        o.put("tags", new JSONArray(tags));
        o.put("transitions", new JSONArray(transitions));

        JSONArray commentsArr = new JSONArray();
        for (Comment cm : comments) {
            JSONObject co = new JSONObject();
            co.put("key", cm.key);
            co.put("login", cm.login);
            co.put("htmlText", cm.htmlText);
            co.put("markdown", cm.markdown);
            co.put("createdAt", cm.createdAt);
            commentsArr.put(co);
        }
        o.put("comments", commentsArr);

        JSONArray impactsArr = new JSONArray();
        for (Impact im : impacts) {
            JSONObject imo = new JSONObject();
            imo.put("softwareQuality", im.softwareQuality);
            imo.put("severity", im.severity);
            impactsArr.put(imo);
        }
        o.put("impacts", impactsArr);
        return o;
    }

    public String url(String host, String projectKey) {
        return url("https", host, projectKey);
    }

    public String url(String scheme, String host, String projectKey) {
        return scheme + "://" + host + "/project/issues?id=" + projectKey
                + "&issues=" + key + "&open=" + key;
    }

    @Override
    public String toString() {
        return String.format("%-40s %-12s %s", component + ":" + line, rule, message);
    }

    public static Issue fromHotspot(JSONObject o, String projectKey) {
        Issue i = new Issue();
        i.key = o.optString("key");
        i.rule = o.optString("ruleKey", "");
        i.severity = o.optString("vulnerabilityProbability", "");
        i.impactSeverity = i.severity;
        i.component = o.optString("component", "");
        if (projectKey != null && i.component.startsWith(projectKey + ":")) {
            i.component = i.component.substring(projectKey.length() + 1);
        }
        i.line = o.optInt("line", -1);
        i.message = o.optString("message", "");
        i.status = o.optString("status", "");
        i.resolution = o.optString("resolution", "");
        i.issueStatus = i.status;
        i.type = "SECURITY_HOTSPOT";
        i.author = o.optString("author", "");
        i.creationDate = o.optString("creationDate", "");
        i.updateDate = o.optString("updateDate", "");

        JSONObject tr = o.optJSONObject("textRange");
        if (tr != null) {
            i.textRange = new TextRange();
            i.textRange.startLine = tr.optInt("startLine");
            i.textRange.endLine = tr.optInt("endLine");
            i.textRange.startOffset = tr.optInt("startOffset");
            i.textRange.endOffset = tr.optInt("endOffset");
        }

        String prob = o.optString("vulnerabilityProbability", "");
        if (!prob.isEmpty()) {
            i.impacts.add(new Impact("SECURITY", prob));
        }
        return i;
    }
}
```

## Reporter.java

```
package ru.miacomsoft.sonar;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Reporter {

    public static void printFacet(String title, Map<String, Integer> data) {
        System.out.println("\n=== " + title + " ===");
        data.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("  %6d  %s%n", e.getValue(), e.getKey()));
    }

    public static void printGroups(String title, List<Issue> issues,
                                   Function<Issue, String> classifier, int examples) {
        Map<String, List<Issue>> groups = issues.stream()
                .collect(Collectors.groupingBy(classifier));

        System.out.println("\n=== " + title + " ===");
        groups.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .forEach(e -> {
                    System.out.printf("%n[%d] %s%n", e.getValue().size(), e.getKey());
                    e.getValue().stream().limit(examples).forEach(it ->
                            System.out.printf("    %-55s %s%n",
                                    it.component + ":" + it.line, shorten(it.message, 80)));
                });
    }

    public static void printMatrix(List<Issue> issues) {
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();
        Set<String> allRules = new TreeSet<>();

        for (Issue it : issues) {
            allRules.add(it.rule);
            matrix.computeIfAbsent(it.component, k -> new TreeMap<>())
                    .merge(it.rule, 1, Integer::sum);
        }

        System.out.println("\n=== Файл × правило ===");
        System.out.printf("%-35s", "file / rule");
        for (String r : allRules) System.out.printf("%10s", r.replace("php:", ""));
        System.out.println();

        for (Map.Entry<String, Map<String, Integer>> e : matrix.entrySet()) {
            System.out.printf("%-35s", shorten(e.getKey(), 34));
            for (String r : allRules) {
                Integer n = e.getValue().getOrDefault(r, 0);
                System.out.printf("%10s", n == 0 ? "" : n);
            }
            System.out.println();
        }
    }

    public static void printPerFile(Map<String, List<Issue>> byFile) {
        int totalIssues = byFile.values().stream().mapToInt(List::size).sum();
        System.out.printf("%n=== Замечания по файлам: %d файлов, %d замечаний ===%n",
                byFile.size(), totalIssues);

        byFile.forEach((file, issues) -> {
            System.out.printf("%n%s — %d:%n", file, issues.size());
            for (Issue it : issues) {
                String tags = it.tags.isEmpty() ? "" : " [" + String.join(",", it.tags) + "]";
                System.out.printf("  L%-6d %-12s %-8s %s%s%n",
                        it.line,
                        it.rule,
                        it.impactSeverity == null ? it.severity : it.impactSeverity,
                        shorten(it.message, 110),
                        tags);
            }
        });
    }

    public static void printDetailedByFileWithSnippets(Map<String, List<Issue>> byFile,
                                                       SonarIssuesApi api,
                                                       int maxSnippetsPerFile) throws Exception {
        int totalIssues = byFile.values().stream().mapToInt(List::size).sum();
        System.out.printf("%n=== ПОДРОБНЫЙ ОТЧЁТ ПО ФАЙЛАМ: %d файлов, %d замечаний ===%n",
                byFile.size(), totalIssues);

        for (Map.Entry<String, List<Issue>> entry : byFile.entrySet()) {
            String file = entry.getKey();
            List<Issue> issues = entry.getValue();

            System.out.printf("%n%n╔════════════════════════════════════════════════════════════════════════════════╗%n");
            System.out.printf("║ ФАЙЛ: %-70s ║%n", file);
            System.out.printf("║ Замечаний: %-66d ║%n", issues.size());
            System.out.printf("╚════════════════════════════════════════════════════════════════════════════════╝%n");

            int count = 0;
            for (Issue issue : issues) {
                if (count >= maxSnippetsPerFile) {
                    System.out.printf("%n  ... и ещё %d замечаний в этом файле ...%n",
                            issues.size() - maxSnippetsPerFile);
                    break;
                }

                String ruleName;
                try {
                    ruleName = api.fetchRuleName(issue.rule);
                } catch (Exception e) {
                    ruleName = "";
                }

                System.out.printf("%n  ┌─ Замечание #%d (строка %d) ──────────────────────────────────────────────%n",
                        count + 1, issue.line);
                System.out.printf("  │ %s%n", issue.message);
                if (!ruleName.isEmpty()) {
                    System.out.printf("  │ Правило: %s (%s)%n", ruleName, issue.rule);
                } else {
                    System.out.printf("  │ Правило: %s%n", issue.rule);
                }
                System.out.printf("  │ Серьёзность: %s%n",
                        issue.impactSeverity != null ? issue.impactSeverity : issue.severity);

                try {
                    List<String> snippet = api.fetchSnippet(issue.key);
                    if (!snippet.isEmpty()) {
                        System.out.println("  │ Код:");
                        for (String line : snippet) {
                            System.out.println("  │   " + line);
                        }
                    } else {
                        System.out.println("  │ (фрагмент кода недоступен)");
                    }
                } catch (Exception e) {
                    System.out.println("  │ (не удалось загрузить фрагмент кода: " + e.getMessage() + ")");
                }
                System.out.println("  └──────────────────────────────────────────────────────────────────────");

                count++;
            }
        }
        System.out.printf("%n%n=== КОНЕЦ ОТЧЁТА ===%n");
    }

    public static String perFileCsv(Map<String, List<Issue>> byFile) {
        StringBuilder sb = new StringBuilder(
                "file,line,rule,impactSeverity,type,message,tags,key\n");
        byFile.forEach((file, issues) -> {
            for (Issue it : issues) {
                sb.append(csv(file)).append(',')
                        .append(it.line).append(',')
                        .append(csv(it.rule)).append(',')
                        .append(csv(it.impactSeverity)).append(',')
                        .append(csv(it.type)).append(',')
                        .append(csv(it.message)).append(',')
                        .append(csv(String.join(";", it.tags))).append(',')
                        .append(csv(it.key)).append('\n');
            }
        });
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
```

## SonarBulkApi.java

```
package ru.miacomsoft.sonar;

import java.util.*;

public class SonarBulkApi {

    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final long DEFAULT_DELAY_MS = 500;
    public static final long REOPEN_DELAY_MS = 120;

    private final SonarClient client;
    private final int batchSize;
    private final long delayMs;

    /** Если true — не печатать "Батч N/M ... OK" в stdout (для auto-режима). */
    private boolean silent = false;

    public SonarBulkApi(SonarClient client) {
        this(client, DEFAULT_BATCH_SIZE, DEFAULT_DELAY_MS);
    }

    public SonarBulkApi(SonarClient client, int batchSize, long delayMs) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize > 0");
        if (delayMs < 0) throw new IllegalArgumentException("delayMs >= 0");
        this.client = client;
        this.batchSize = batchSize;
        this.delayMs = delayMs;
    }

    public boolean isSilent()          { return silent; }
    public void    setSilent(boolean s){ this.silent = s; }

    public int batchCount(int totalIssues) {
        return (totalIssues + batchSize - 1) / batchSize;
    }

    public Map<String, Object> dryRun(List<Issue> issues, String transition, String comment) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("issues", issues.size());
        r.put("transition", transition);
        r.put("comment", comment);
        r.put("batchSize", batchSize);
        r.put("batches", batchCount(issues.size()));
        return r;
    }

    public List<String> change(List<Issue> issues, String transition, String comment) throws Exception {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < issues.size(); i += batchSize) {
            List<Issue> batch = issues.subList(i, Math.min(i + batchSize, issues.size()));

            StringBuilder keys = new StringBuilder();
            for (Issue it : batch) {
                if (keys.length() > 0) keys.append(',');
                keys.append(it.key);
            }

            Map<String, String> form = new LinkedHashMap<>();
            form.put("issues", keys.toString());
            form.put("do_transition", transition);
            if (comment != null && !comment.isEmpty()) {
                form.put("comment", comment);
            }

            String resp = client.postForm("/api/issues/bulk_change", form);
            results.add(resp);

            if (!silent) {
                // Выводим список ключей текущего батча
                StringBuilder keysList = new StringBuilder();
                for (int j = 0; j < batch.size(); j++) {
                    if (j > 0) keysList.append(", ");
                    keysList.append(batch.get(j).key);
                }
                System.out.printf("Батч %d/%d (%d): OK [%s]%n",
                        (i / batchSize + 1), batchCount(issues.size()), batch.size(), keysList.toString());
            }

            if (i + batchSize < issues.size() && delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        return results;
    }

    public void doTransition(String issueKey, String transition) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("issue", issueKey);
        form.put("transition", transition);
        client.postForm("/api/issues/do_transition", form);
    }

    public void addComment(String issueKey, String text) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("issue", issueKey);
        form.put("text", text);
        client.postForm("/api/issues/add_comment", form);
    }

    public void setTags(List<Issue> issues, String tagsCsv) throws Exception {
        if (issues == null || issues.isEmpty()) return;

        for (Issue it : issues) {
            if (it == null || it.key == null || it.key.isEmpty()) continue;

            Map<String, String> form = new LinkedHashMap<>();
            form.put("issue", it.key);
            form.put("tags", tagsCsv == null ? "" : tagsCsv);
            client.postForm("/api/issues/set_tags", form);

            if (issues.size() > 1) {
                try { Thread.sleep(50); } catch (InterruptedException ignore) {}
            }
        }
    }

    public Set<String> reopenIssues(List<Issue> issues) throws Exception {
        Set<String> failed = new HashSet<>();
        int total = issues.size();
        for (int i = 0; i < total; i++) {
            Issue it = issues.get(i);
            try {
                doTransition(it.key, "reopen");
                if (!silent) {
                    System.out.printf("reopen [%d/%d] %s: OK%n", i + 1, total, it.key);
                }
            } catch (Exception e) {
                if (!silent) {
                    System.out.printf("reopen [%d/%d] %s: FAIL %s%n",
                            i + 1, total, it.key, e.getMessage());
                }
                failed.add(it.key);
            }
            if (i < total - 1) Thread.sleep(REOPEN_DELAY_MS);
        }
        return failed;
    }
}
```

## jira/CreateOptionsVisualizer.java

```
package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.jira.JiraCreateParametersService;
import ru.miacomsoft.sonar.jira.JiraFieldService;
import ru.miacomsoft.sonar.jira.JiraTempoTeamService;

import java.util.List;
import java.util.Map;

public final class CreateOptionsVisualizer {

    private CreateOptionsVisualizer() {}

    public static void printCompact(JiraCreateParametersService.CreateParameters p) {
        header(p);
        line("Приоритеты",          p.priorities().size());
        line("Уровни безопасности", p.securityLevels().size());
        line("Затронуты версии",    p.versions().size());
        line("Исправить в версиях", p.fixVersions().size());
        line("Компоненты",          p.components().size());
        for (Map.Entry<String, List<JiraFieldService.AllowedValue>> e : p.nfeedOptions().entrySet())
            line("nFeed " + e.getKey(), e.getValue().size());
    }

    public static void printField(JiraCreateParametersService.CreateParameters p, String key) {
        List<JiraFieldService.AllowedValue> list = pick(p, key);
        if (list == null) { System.out.println("  " + key + ": not in CreateParameters"); return; }
        System.out.println("  ── " + key + " (" + list.size() + ") ──");
        for (JiraFieldService.AllowedValue av : list)
            System.out.printf("     %-8s = %s%n", av.id(), av.value() == null ? "<no label>" : av.value());
    }

    public static void printLabelSuggestions(JiraLabelService labels, String issueIdOrKey) {
        System.out.println("-- labels для " + issueIdOrKey + " --");
        try {
            JSONArray arr = labels.loadSuggestions(issueIdOrKey, "");
            System.out.println("  найдено: " + arr.length());
            for (int i = 0; i < arr.length(); i++)
                System.out.printf("  %-3d %s%n", i + 1, arr.getJSONObject(i).optString("label"));
        } catch (Exception e) { System.out.println("  " + e.getMessage()); }
    }

    public static void printTempoTeams(JiraTempoTeamService tempo) {
        System.out.println("-- Tempo Teams, доступные вам (/selectable) --");
        try {
            for (String id : tempo.findAccessibleTeamIds()) {
                JSONObject t = tempo.getTeam(Integer.parseInt(id));
                System.out.printf("  %-6s = %s%n", id, t == null ? "?" : t.optString("name"));
            }
        } catch (Exception e) { System.out.println("  " + e.getMessage()); }
    }

    private static List<JiraFieldService.AllowedValue> pick(
            JiraCreateParametersService.CreateParameters p, String k) {
        return switch (k) {
            case "priority"    -> p.priorities();
            case "security"    -> p.securityLevels();
            case "versions"    -> p.versions();
            case "fixVersions" -> p.fixVersions();
            case "components"  -> p.components();
            default            -> p.nfeedOptions().get(k);
        };
    }

    private static void header(JiraCreateParametersService.CreateParameters p) {
        System.out.println("╔═ CreateParameters: " + p.projectKey() + " / " + p.issueTypeId()
                + (p.contextIssueKey() == null ? "" : " (ctx " + p.contextIssueKey() + ")"));
    }

    private static void line(String label, int n) {
        System.out.printf("  %-32s %d%n", label, n);
    }
}
```

## jira/JiraClient.java

```
package ru.miacomsoft.sonar.jira;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

public class JiraClient {

    private final String base;
    private final String cookie;
    private final HttpClient http;

    public JiraClient(String baseUrl, String cookieHeader) {
        this.base = baseUrl;
        this.cookie = cookieHeader;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String get(String path) throws IOException, InterruptedException {
        return send(baseBuilder(path).GET().build());
    }

    public String post(String path, String jsonBody) throws IOException, InterruptedException {
        return send(baseBuilder(path)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Atlassian-Token", "no-check")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build());
    }

    public String delete(String path) throws IOException, InterruptedException {
        return send(baseBuilder(path)
                .header("X-Atlassian-Token", "no-check")
                .DELETE()
                .build());
    }

    public String uploadAttachment(String issueKey, Path file)
            throws IOException, InterruptedException {
        String boundary = "----JiraBoundary" + UUID.randomUUID();
        String fileName = file.getFileName().toString();
        byte[] fileBytes = Files.readAllBytes(file);

        byte[] prefix = ("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        byte[] body = new byte[prefix.length + fileBytes.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(fileBytes, 0, body, prefix.length, fileBytes.length);
        System.arraycopy(suffix, 0, body, prefix.length + fileBytes.length, suffix.length);

        return send(baseBuilder("/rest/api/2/issue/" + URLEncoder.encode(issueKey, StandardCharsets.UTF_8)
                + "/attachments")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Atlassian-Token", "no-check")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build());
    }

    private HttpRequest.Builder baseBuilder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Cookie", cookie)
                .header("Accept", "application/json")
                .header("Origin", base)
                .header("Referer", base + "/secure/Dashboard.jspa")
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
    }

    private String send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> resp =
                http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int sc = resp.statusCode();
        if (sc >= 200 && sc < 300) return resp.body();
        throw new IllegalStateException("HTTP " + sc + " for " + req.uri() + "\n" + resp.body());
    }
}
```

## jira/JiraIssueService.java

```
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
```

## jira/JiraLabelService.java

```
package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class JiraLabelService {

    private final JiraClient jira;

    public JiraLabelService(JiraClient jira) { this.jira = jira; }

    /**
     * Новый метод: получение подсказок меток по проекту.
     * Используется в CreateJiraIssueHandler для выпадающего списка меток.
     */
    public List<String> getSuggestions(String projectKey, String query) {
        List<String> out = new ArrayList<>();
        try {
            // Ищем любой issue в проекте для контекста меток
            JSONObject search = new JSONObject(jira.get("/rest/api/2/search?jql=project=" + enc(projectKey) + " ORDER BY created DESC&maxResults=1&fields=id"));
            JSONArray issues = search.optJSONArray("issues");
            String issueId = issues != null && issues.length() > 0 ? issues.getJSONObject(0).optString("id") : "1";

            String url = "/rest/api/1.0/labels/" + issueId + "/suggest?query=" + enc(query == null ? "" : query);
            JSONObject root = new JSONObject(jira.get(url));
            JSONArray suggestions = root.optJSONArray("suggestions");
            if (suggestions != null) {
                for (int i = 0; i < suggestions.length(); i++) {
                    out.add(suggestions.getJSONObject(i).optString("label"));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Старый метод: получение подсказок меток по конкретному issue.
     * Оставлен для совместимости с тестовым классом CreateOptionsVisualizer.
     */
    public JSONArray loadSuggestions(String issueIdOrKey, String query) throws Exception {
        String q = query == null ? "" : query;
        try {
            return new JSONArray(jira.get("/rest/api/1.0/labels/" + enc(issueIdOrKey)
                    + "/suggest?query=" + enc(q)));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                String numericId = null;
                try {
                    numericId = new JSONObject(
                            jira.get("/rest/api/2/issue/" + enc(issueIdOrKey))).optString("id");
                } catch (Exception ignored) { }
                if (numericId != null) {
                    return new JSONArray(jira.get("/rest/api/1.0/labels/" + enc(numericId)
                            + "/suggest?query=" + enc(q)));
                }
            }
            throw e;
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
```

## jira/JiraPermissionService.java

```
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
```

## jira/JiraTempoTeamService.java

```
package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class JiraTempoTeamService {

    private final JiraClient jira;

    public JiraTempoTeamService(JiraClient jira) { this.jira = jira; }

    public List<JiraFieldService.AllowedValue> getPickerTeams() {
        List<JiraFieldService.AllowedValue> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(jira.get("/rest/tempo-teams/2/picker/team?expand=projects&query="));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                out.add(new JiraFieldService.AllowedValue(t.optString("id"), t.optString("name")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public Integer findIdByName(String name) {
        if (name == null) return null;
        String target = name.trim().toLowerCase();
        Integer sel = lookup("/rest/tempo-teams/2/team/selectable", target);
        if (sel != null) return sel;
        Integer all = lookup("/rest/tempo-teams/2/team", target);
        if (all != null) return all;
        Integer member = lookup("/rest/tempo-teams/2/team/member", target);
        if (member != null) return member;
        return null;
    }

    private Integer lookup(String ep, String target) {
        try {
            JSONArray arr = new JSONArray(jira.get(ep));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                if (t.optString("name", "").trim().equalsIgnoreCase(target)) {
                    try { return Integer.parseInt(t.optString("id", "")); } catch (NumberFormatException ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    public JSONObject getTeam(int id) {
        try { return new JSONObject(jira.get("/rest/tempo-teams/2/team/" + id)); }
        catch (Exception e) { return null; }
    }

    public String teamName(String teamId) {
        try {
            JSONObject t = getTeam(Integer.parseInt(teamId));
            return t == null ? null : t.optString("name", null);
        } catch (Exception e) { return null; }
    }

    public List<String> findAccessibleTeamIds() {
        List<String> out = new ArrayList<>();
        for (String ep : new String[]{"/rest/tempo-teams/2/team/selectable", "/rest/tempo-teams/2/team/member"}) {
            try {
                JSONArray arr = new JSONArray(jira.get(ep));
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.getJSONObject(i).optString("id", null);
                    if (id != null && !out.contains(id)) out.add(id);
                }
                if (!out.isEmpty()) return out;
            } catch (Exception ignored) { }
        }
        return out;
    }

    public List<Object> buildCandidates(Object sample, String teamId, String teamName) {
        List<Object> out = new ArrayList<>();
        int numericId = -1;
        if (teamId != null) try { numericId = Integer.parseInt(teamId); } catch (NumberFormatException ignored) { }
        if (numericId > 0) out.add(numericId);
        if (teamId != null) out.add(teamId);
        if (numericId > 0) out.add(new JSONObject().put("id", numericId));
        if (teamId != null) out.add(new JSONObject().put("id", teamId));
        if (numericId > 0) out.add(new JSONObject().put("teamId", numericId));
        if (teamId != null) out.add(new JSONObject().put("teamId", teamId));
        if (teamName != null) out.add(new JSONObject().put("name", teamName));
        if (teamName != null) out.add(new JSONObject().put("value", teamName));
        if (teamId != null) out.add(new JSONObject().put("value", teamId));
        if (teamName != null) out.add(new JSONObject().put("key", teamName));
        if (teamId != null) out.add(new JSONObject().put("key", teamId));
        if (teamId != null && teamName != null) {
            JSONObject c = new JSONObject().put("name", teamName).put("id", teamId);
            if (numericId > 0) c.put("id", numericId);
            out.add(c);
        }
        if (teamId != null) out.add(new JSONArray().put(teamId));
        if (numericId > 0) out.add(new JSONArray().put(numericId));
        if (teamId != null) out.add(new JSONArray().put(new JSONObject().put("id", teamId)));
        if (teamName != null) out.add(new JSONArray().put(new JSONObject().put("name", teamName)));
        if (numericId > 0) out.add(new JSONObject().put("id", numericId).put("name", teamName == null ? "" : teamName));
        if (teamId != null) {
            Object full = getFullTeam(teamId);
            if (full != null) out.add(full);
        }
        if (teamName != null) out.add(teamName);
        if (sample != null) out.add(sample);
        return out;
    }

    public Object getFullTeam(String teamId) {
        try { return new JSONObject(jira.get("/rest/tempo-teams/2/team/" + teamId)); }
        catch (Exception e) { return null; }
    }

    public static String extractTeamId(Object sample) {
        if (sample instanceof JSONObject jo) {
            String id = jo.optString("id", null);
            return (id != null && !id.isBlank()) ? id : jo.optString("name", null);
        }
        if (sample instanceof JSONArray arr && !arr.isEmpty()) return extractTeamId(arr.get(0));
        if (sample instanceof String s) return s;
        if (sample instanceof Number n) return String.valueOf(n);
        return null;
    }

    public static boolean isTeamError(Exception e) {
        String m = e.getMessage();
        if (m == null) return false;
        String l = m.toLowerCase();
        return l.contains("could not find team") || l.contains("team with name")
                || l.contains("не удалось найти команду") || l.contains("команда с именем");
    }
}
```

## server/console/OllamaCommands.java

```
package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.server.OllamaConfig;
import ru.miacomsoft.sonar.server.ollama.OllamaClient;

import java.util.Locale;

/**
 * Команда {@code ollama *}: show / set / test / reload.
 */
public class OllamaCommands {

    private final ConsoleContext ctx;

    public OllamaCommands(ConsoleContext ctx) {
        this.ctx = ctx;
    }

    public void handle(String[] a) throws Exception {
        OllamaConfig cfg = ctx.ollamaConfig();

        if (a.length == 0 || "show".equalsIgnoreCase(a[0])) {
            System.out.println("Ollama settings  (" + cfg.getConfigFile() + ")");
            System.out.println("  url         = " + cfg.getUrl());
            System.out.println("  model       = " + cfg.getModel());
            System.out.println("  timeout     = " + cfg.getTimeoutSeconds() + "s");
            System.out.println("  temperature = " + cfg.getTemperature());
            System.out.println();
            System.out.println("Usage:");
            System.out.println("  ollama show");
            System.out.println("  ollama set url <URL>");
            System.out.println("  ollama set model <MODEL>");
            System.out.println("  ollama set timeout <SECONDS>");
            System.out.println("  ollama set temperature <FLOAT>");
            System.out.println("  ollama test");
            System.out.println("  ollama reload");
            return;
        }

        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (a.length < 3) {
                    System.out.println("usage: ollama set <url|model|timeout|temperature> <value>");
                    return;
                }
                switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "url"         -> cfg.setUrl(a[2]);
                    case "model"       -> cfg.setModel(a[2]);
                    case "timeout"     -> cfg.setTimeoutSeconds(Integer.parseInt(a[2]));
                    case "temperature" -> cfg.setTemperature(Double.parseDouble(a[2]));
                    default -> { System.out.println("unknown key: " + a[1]); return; }
                }
                cfg.save();
                System.out.println("[ollama] saved");
            }
            case "test" -> {
                try {
                    new OllamaClient(cfg).testConnection();
                    System.out.println("[ollama] OK: " + cfg.getUrl());
                } catch (Exception e) {
                    System.err.println("[ollama] FAIL: " + e.getMessage());
                }
            }
            case "reload" -> {
                OllamaConfig fresh = new OllamaConfig(cfg.getConfigFile());
                cfg.setUrl(fresh.getUrl());
                cfg.setModel(fresh.getModel());
                cfg.setTimeoutSeconds(fresh.getTimeoutSeconds());
                cfg.setTemperature(fresh.getTemperature());
                System.out.println("[ollama] reloaded from " + cfg.getConfigFile());
            }
            default -> System.out.println("unknown sub-command: " + sub);
        }
    }
}
```

## server/exporters/ConfluenceExporter.java

```
package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ConfluenceExporter {

    public static String exportSingle(Issue issue, String projectName, String host) {
        return exportSingle(issue, projectName, host, null);
    }

    /**
     * @param snippet — фрагмент кода в формате "%4d | code",
     *                  может содержать §HL_START§ / §HL_END§.
     *                  null / пусто → блок кода не выводится.
     */
    public static String exportSingle(Issue issue, String projectName, String host,
                                      List<String> snippet) {
        StringBuilder sb = new StringBuilder(1024);

        String link = "https://" + host + "/project/issues?id=" + urlEnc(projectName)
                + "&open=" + urlEnc(issue.key);
        String ruleName = (issue.ruleName != null && !issue.ruleName.isEmpty())
                ? " — " + issue.ruleName : "";
        String tagsStr = (issue.tags == null || issue.tags.isEmpty())
                ? "нет" : String.join(", ", issue.tags);
        String lastComment = getLastComment(issue);

        sb.append("<h3>").append(esc(issue.key)).append("</h3>\n");

        if (issue.line > 0) {
            sb.append("<p><b>Строка:</b> ").append(issue.line).append("</p>\n");
        } else {
            sb.append("<p><b>Строка:</b> <em>(issue на весь файл)</em></p>\n");
        }

        sb.append("<p><b>Ссылка:</b> <a href=\"").append(esc(link))
                .append("\" target=\"_blank\">").append(esc(link)).append("</a></p>\n");
        sb.append("<p><b>Правило:</b> ")
                .append(esc(issue.rule)).append(esc(ruleName)).append("</p>\n");
        sb.append("<p><b>Файл:</b> ").append(esc(issue.component)).append("</p>\n");
        sb.append("<p><b>Теги:</b> ").append(esc(tagsStr)).append("</p>\n");
        sb.append("<p><b>Сообщение:</b> ").append(esc(issue.message)).append("</p>\n");
        sb.append("<p><b>Пользовательский комментарий:</b> ")
                .append(esc(lastComment)).append("</p>\n");

        if (snippet != null && !snippet.isEmpty()) {
            sb.append("<details>\n<summary>Показать фрагмент кода");
            if (issue.line <= 0) {
                sb.append(" (первые ").append(snippet.size()).append(" строк файла)");
            }
            sb.append("</summary>\n<pre><code>");
            for (int i = 0; i < snippet.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(renderSnippetLine(snippet.get(i)));
            }
            sb.append("</code></pre>\n</details>\n");
        }

        sb.append("<br/><hr/><br/>\n");
        return sb.toString();
    }

    /**
     * Экранирует HTML и превращает §HL_START§/§HL_END§ в &lt;mark&gt;.
     * Пустые &lt;mark&gt;&lt;/mark&gt; вычищаются.
     */
    private static String renderSnippetLine(String line) {
        if (line == null) return "";
        String result = esc(line)
                .replace("§HL_START§", "<mark class=\"sonar-issue-mark\">")
                .replace("§HL_END§", "</mark>");
        return result.replace("<mark class=\"sonar-issue-mark\"></mark>", "");
    }

    private static String getLastComment(Issue issue) {
        if (issue.comments == null || issue.comments.isEmpty()) return "нет";
        Issue.Comment latest = null;
        for (Issue.Comment c : issue.comments) {
            if (latest == null
                    || String.valueOf(c.createdAt).compareTo(String.valueOf(latest.createdAt)) > 0) {
                latest = c;
            }
        }
        if (latest == null) return "нет";
        if (latest.markdown != null && !latest.markdown.isEmpty()) return latest.markdown;
        return latest.htmlText != null ? latest.htmlText : "нет";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}
```

## server/exporters/HtmlPerFileExporter.java

```
package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.server.TeamMapping;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class HtmlPerFileExporter {

    public static final String MANIFEST_NAME = "!files.csv";

    private HtmlPerFileExporter() {}

    public static int writeZip(Path target,
                               Map<String, List<Issue>> issuesByFile,
                               Map<String, List<String>> issueSnippets,
                               String projectName, String host,
                               boolean useTeams, TeamMapping mapping) throws IOException {
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(target));
             ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
            zos.setLevel(6);
            int written = 0;

            StringBuilder manifest = new StringBuilder(1024);
            manifest.append(projectName != null ? projectName : "").append('\n');

            for (Map.Entry<String, List<Issue>> e : issuesByFile.entrySet()) {
                String fileName = e.getKey();
                List<Issue> issues = e.getValue();

                String html = formatHtmlFile(issues, issueSnippets, projectName, host, fileName);
                String entryName = makeEntryName(fileName, useTeams, mapping);

                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(html.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                written++;

                String sonarLink = buildSonarLink(projectName, host, fileName, issues);
                manifest.append(fileName).append(';')
                        .append(entryName).append(';')
                        .append(sonarLink).append('\n');
            }

            zos.putNextEntry(new ZipEntry(MANIFEST_NAME));
            zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            return written;
        }
    }

    private static String buildSonarLink(String projectName, String host,
                                         String fileName, List<Issue> issues) {
        String base = "https://" + host + "/project/issues?id=" + urlEnc(projectName);
        if (issues != null && !issues.isEmpty()) {
            Issue first = issues.get(0);
            if (first != null && first.key != null && !first.key.isEmpty()) {
                return base + "&open=" + urlEnc(first.key);
            }
        }
        return base + "&files=" + urlEnc(fileName);
    }

    private static String makeEntryName(String component, boolean useTeams, TeamMapping mapping) {
        String cleanPath = component;
        if (cleanPath.contains(":")) cleanPath = cleanPath.substring(cleanPath.indexOf(':') + 1);

        String safePath = cleanPath.replaceAll("[\\\\/]", "_")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (safePath.isEmpty()) safePath = "unknown";

        if (useTeams && mapping != null) {
            TeamMapping.FileInfo info = mapping.findInfo(component);
            if (info != null && !info.isEmpty()) {
                StringBuilder prefix = new StringBuilder();
                if (info.hasTeam()) {
                    prefix.append("(")
                            .append(sanitizeForFileName(info.team))
                            .append(")");
                }
                if (info.hasComponent()) {
                    prefix.append("[")
                            .append(sanitizeForFileName(info.component))
                            .append("]");
                }
                prefix.append("_");
                return prefix + safePath + ".html";
            }
        }
        return safePath + ".html";
    }

    private static String sanitizeForFileName(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return out.isEmpty() ? "unknown" : out;
    }


    public static String formatHtmlFile(List<Issue> issues,
                                        Map<String, List<String>> issueSnippets,
                                        String projectName, String host, String fileName) {
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());
        StringBuilder html = new StringBuilder(4096);

        html.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Отчет: ").append(escapeHtml(projectName))
                .append(" - ").append(escapeHtml(fileName)).append("</title>\n");
        html.append("<style>\n");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;line-height:1.5;color:#172b4d;max-width:1000px;margin:0 auto;padding:20px;background:#fafbfc;}\n");
        html.append("h1,h2{color:#0052cc;margin-bottom:8px;}\n");
        html.append("a{color:#0052cc;text-decoration:none;}a:hover{text-decoration:underline;}\n");
        html.append(".issue{border:1px solid #dfe1e6;border-radius:4px;background:#fff;padding:16px;margin-bottom:16px;}\n");
        html.append("details{margin-top:12px;border:1px solid #dfe1e6;border-radius:4px;background:#f4f5f7;}\n");
        html.append("summary{cursor:pointer;padding:8px 12px;font-weight:600;color:#42526e;user-select:none;}\n");
        html.append("summary:hover{background:#ebecf0;}\n");
        html.append("pre{margin:0;padding:12px;background:#fff;overflow-x:auto;border-top:1px solid #dfe1e6;border-radius:0 0 4px 4px;font-family:monospace;font-size:13px;white-space:pre-wrap;}\n");
        html.append("mark.sonar-issue-mark{background-color:rgba(248,81,73,0.25);border-bottom:2px solid #f85149;color:inherit;padding:0 2px;border-radius:2px;}\n");
        html.append(".line-meta{color:#8b949e;font-size:11px;margin-left:6px;}\n");
        html.append("</style>\n</head>\n<body>\n");
        html.append("<h1>Проект: ").append(escapeHtml(projectName)).append("</h1>\n");
        html.append("<h2>Файл: ").append(escapeHtml(fileName)).append("</h2>\n");
        html.append("<p><em>Сгенерировано: ").append(now)
                .append(" &middot; Всего issues: ").append(issues.size()).append("</em></p>\n<hr/>\n");

        for (Issue it : issues) {
            String link = "https://" + host + "/project/issues?id=" + urlEnc(projectName)
                    + "&open=" + urlEnc(it.key);

            // Формируем ссылку на исходник файла в SonarQube
            String fullComponentKey = projectName + ":" + it.component;
            String sourceLink = "https://" + host + "/code?id=" + urlEnc(projectName)
                    + "&selected=" + urlEnc(fullComponentKey);

            String ruleName = (it.ruleName != null && !it.ruleName.isEmpty())
                    ? " — " + it.ruleName : "";
            String tagsStr = (it.tags == null || it.tags.isEmpty())
                    ? "нет" : String.join(", ", it.tags);
            String lastComment = getLastComment(it);

            html.append("<div class=\"issue\">\n");

            html.append("<p><strong>Идентификатор ошибки:</strong> ").append(escapeHtml(it.key));
            if (it.line > 0) {
                html.append(" (Строка: ").append(it.line).append(")");
            } else {
                html.append(" <span class=\"line-meta\">(issue на весь файл)</span>");
            }
            html.append("</p>\n");

            html.append("<p><strong>Ссылка:</strong> <a href=\"").append(escapeHtml(link))
                    .append("\" target=\"_blank\">").append(escapeHtml(link)).append("</a></p>\n");

            // ДОБАВЛЕНО: ссылка на исходник (строго перед "Правило:")
            html.append("<p><strong>Ссылка на исходник:</strong> <a href=\"")
                    .append(escapeHtml(sourceLink))
                    .append("\" target=\"_blank\">")
                    .append(escapeHtml(fullComponentKey))
                    .append("</a></p>\n");

            html.append("<p><strong>Правило:</strong> ")
                    .append(escapeHtml(it.rule)).append(escapeHtml(ruleName)).append("</p>\n");
            html.append("<p><strong>Теги:</strong> ").append(escapeHtml(tagsStr)).append("</p>\n");
            html.append("<p><strong>Сообщение:</strong> ").append(escapeHtml(it.message)).append("</p>\n");
            html.append("<p><strong>Пользовательский комментарий:</strong> ")
                    .append(escapeHtml(lastComment)).append("</p>\n");

            List<String> snippet = (issueSnippets != null) ? issueSnippets.get(it.key) : null;
            if (snippet != null && !snippet.isEmpty()) {
                boolean snippetIsFileHead =
                        it.line <= 0 && (it.textRange == null || it.textRange.startLine <= 0);

                html.append("<details>\n<summary>Показать фрагмент кода");
                if (snippetIsFileHead) {
                    html.append(" (первые ").append(snippet.size()).append(" строк файла)");
                }
                html.append("</summary>\n<pre><code>");
                for (int i = 0; i < snippet.size(); i++) {
                    if (i > 0) html.append('\n');
                    html.append(renderSnippetLine(snippet.get(i)));
                }
                html.append("</code></pre>\n</details>\n");
            }

            html.append("</div>\n");
        }

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static String renderSnippetLine(String line) {
        if (line == null) return "";
        String result = escapeHtml(line)
                .replace("§HL_START§", "<mark class=\"sonar-issue-mark\">")
                .replace("§HL_END§", "</mark>");
        result = result.replace("<mark class=\"sonar-issue-mark\"></mark>", "");
        return result;
    }

    private static String getLastComment(Issue issue) {
        if (issue.comments == null || issue.comments.isEmpty()) return "нет";
        Issue.Comment latest = null;
        for (Issue.Comment c : issue.comments) {
            if (latest == null || String.valueOf(c.createdAt).compareTo(String.valueOf(latest.createdAt)) > 0) {
                latest = c;
            }
        }
        if (latest == null) return "нет";
        if (latest.markdown != null && !latest.markdown.isEmpty()) return latest.markdown;
        return latest.htmlText != null ? latest.htmlText : "нет";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}
```

## server/exporters/MdExporter.java

````
package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

public class MdExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(issue.key).append("\n");
        sb.append("- **Ссылка:** https://").append(host).append("/project/issues?id=").append(projectName).append("&open=").append(issue.key).append("\n");
        sb.append("- **Правило:** ").append(issue.rule).append("\n");
        sb.append("- **Файл:** `").append(issue.component).append(":").append(issue.line).append("`\n");
        sb.append("- **Сообщение:** ").append(issue.message).append("\n\n");
        sb.append("```\n// Код\n```\n\n---\n");
        return sb.toString();
    }
}
````

## server/handlers/AcceptedHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;

public class AcceptedHandler extends BaseHandler {

    public AcceptedHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        String sev            = param(q, "sev",  "ALL");
        String qual           = param(q, "qual", "ALL");
        String rule           = param(q, "rule", null);
        boolean includeFacets = paramBool(q, "facets", true);
        int page = paramInt(q, "p", 1);
        int ps = paramInt(q, "ps", 50);

        SonarIssuesApi api = newApi(project);
        // 6 аргументов
        JSONObject rawResponse = api.fetchPageRaw("ACCEPTED", sev, qual, rule, page, ps);
        JSONArray rawIssues = rawResponse.optJSONArray("issues");

        JSONArray arr = new JSONArray();
        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                Issue it = Issue.from(rawIssues.getJSONObject(i), project);
                JSONObject io = it.toJson();
                io.put("ruleName", api.fetchRuleName(it.rule));
                arr.put(io);
            }
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("rule", rule != null ? rule : "");
        o.put("total", rawResponse.optInt("total", 0));
        o.put("p", page);
        o.put("ps", ps);
        o.put("issues", arr);

        if (includeFacets) {
            // Фасеты можно добавить позже, если потребуется
        }

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/CreateJiraIssueHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.jira.*;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.exporters.HtmlPerFileExporter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CreateJiraIssueHandler extends BaseHandler {

    public CreateJiraIssueHandler(ServerState state) { super(state); }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/config")) handleGetConfig(ex);
        else if (path.endsWith("/epics")) handleGetEpics(ex);
        else if (path.endsWith("/teams")) handleGetTeams(ex);
        else {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) sendError(ex, 405, "Use POST");
            else handlePost(ex);
        }
    }

    private void handleGetConfig(HttpExchange ex) throws Exception {
        JiraConfig cfg = JiraConfig.getInstance();
        SessionLoader.SessionInfo session = null;
        String sessionError = null;
        try {
            session = SessionLoader.load(Path.of(cfg.sessionFile()));
        } catch (Exception e) {
            sessionError = e.getMessage();
        }

        String defaultAssignee = session != null && session.username() != null ? session.username() : cfg.assignee();
        String userDisplayName = defaultAssignee;

        JSONObject resp = new JSONObject()
                .put("url", cfg.baseUrl())
                .put("sessionReady", session != null)
                .put("username", session != null ? session.username() : null)
                .put("userDisplayName", userDisplayName)
                .put("configProject", cfg.defaultProject())
                .put("configIssueType", cfg.issueType())
                .put("configEpic", cfg.epic())
                .put("configPriority", cfg.priority())
                .put("configSecurity", cfg.security())
                .put("configTeam", cfg.team())
                .put("configLabels", cfg.labels())
                .put("configComponents", cfg.components())
                .put("configAssignee", defaultAssignee);

        if (sessionError != null) resp.put("sessionError", sessionError);

        if (session == null) {
            sendJson(ex, 200, resp);
            return;
        }

        JiraClient jira = new JiraClient(cfg.baseUrl(), session.cookieHeader());
        JiraFieldService fieldService = new JiraFieldService(jira);
        JiraUserService userService = new JiraUserService(jira);
        JiraLabelService labelService = new JiraLabelService(jira);

        try {
            JiraUserService.JiraUser me = userService.getMyself();
            if (me != null && me.displayName() != null && !me.displayName().isBlank()) {
                resp.put("userDisplayName", me.displayName());
            }
        } catch (Exception ignored) {}

        List<String> errors = new ArrayList<>();

        try {
            JSONArray projectsArr = new JSONArray();
            for (JiraFieldService.AllowedValue av : fieldService.getProjects()) {
                projectsArr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            resp.put("projects", projectsArr);
            if (projectsArr.isEmpty()) errors.add("projects пуст");
        } catch (Exception e) {
            errors.add("projects: " + e.getMessage());
            resp.put("projects", new JSONArray());
        }

        try {
            JSONArray typesArr = new JSONArray();
            for (JiraFieldService.AllowedValue av : fieldService.getIssueTypes(cfg.defaultProject())) {
                typesArr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            resp.put("issueTypes", typesArr);
            if (typesArr.isEmpty()) errors.add("issueTypes пуст");
        } catch (Exception e) {
            errors.add("issueTypes: " + e.getMessage());
            resp.put("issueTypes", new JSONArray());
        }

        try {
            JSONArray priorsArr = new JSONArray();
            for (JiraFieldService.AllowedValue av : fieldService.getAllPriorities()) {
                priorsArr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            resp.put("priorities", priorsArr);
            if (priorsArr.isEmpty()) errors.add("priorities пуст");
        } catch (Exception e) {
            errors.add("priorities: " + e.getMessage());
            resp.put("priorities", new JSONArray());
        }

        try {
            JSONArray secArr = new JSONArray();
            String defaultSecurityId = "";
            for (JiraFieldService.AllowedValue av : fieldService.getSecurityLevels(cfg.defaultProject(), cfg.issueType())) {
                secArr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
                if (av.value().equalsIgnoreCase(cfg.security()) || av.value().equalsIgnoreCase("Основной")) {
                    defaultSecurityId = av.id();
                }
            }
            resp.put("securityLevels", secArr);
            resp.put("configSecurity", defaultSecurityId);
        } catch (Exception e) {
            errors.add("securityLevels: " + e.getMessage());
            resp.put("securityLevels", new JSONArray());
        }

        try {
            JSONArray labelsArr = new JSONArray();
            for (String label : labelService.getSuggestions(cfg.defaultProject(), "")) {
                labelsArr.put(label);
            }
            resp.put("availableLabels", labelsArr);
        } catch (Exception e) {
            errors.add("availableLabels: " + e.getMessage());
            resp.put("availableLabels", new JSONArray());
        }

        try {
            JSONArray usersArr = new JSONArray();
            List<JiraUserService.JiraUser> users = userService.getAssignableUsers(cfg.defaultProject(), "", 100);
            for (JiraUserService.JiraUser u : users) {
                usersArr.put(new JSONObject().put("name", u.name()).put("displayName", u.displayName()));
            }
            resp.put("assignees", usersArr);
            if (usersArr.isEmpty()) errors.add("assignees пуст");
        } catch (Exception e) {
            errors.add("assignees: " + e.getMessage());
            resp.put("assignees", new JSONArray());
        }

        if (!errors.isEmpty()) {
            resp.put("listError", "Ошибки загрузки: " + String.join("; ", errors)
                    + ". Проверьте cookie в session.txt.");
        }

        sendJson(ex, 200, resp);
    }

    private void handleGetEpics(HttpExchange ex) throws Exception {
        JiraConfig cfg = JiraConfig.getInstance();
        Map<String, String> q = parseQuery(ex);
        String project = param(q, "project", cfg.defaultProject());

        try {
            SessionLoader.SessionInfo session = SessionLoader.load(Path.of(cfg.sessionFile()));
            JiraClient jira = new JiraClient(cfg.baseUrl(), session.cookieHeader());
            JiraEpicService epicService = new JiraEpicService(jira);

            JSONArray arr = new JSONArray();
            for (JiraFieldService.AllowedValue av : epicService.getEpics(project)) {
                arr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            JSONObject resp = new JSONObject().put("epics", arr);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    private void handleGetTeams(HttpExchange ex) throws Exception {
        JiraConfig cfg = JiraConfig.getInstance();
        try {
            SessionLoader.SessionInfo session = SessionLoader.load(Path.of(cfg.sessionFile()));
            JiraClient jira = new JiraClient(cfg.baseUrl(), session.cookieHeader());
            JiraTeamService teamService = new JiraTeamService(jira);

            JSONArray arr = new JSONArray();
            for (JiraFieldService.AllowedValue av : teamService.getTeams()) {
                arr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            JSONObject resp = new JSONObject().put("teams", arr);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    private void handlePost(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        JiraConfig cfg = JiraConfig.getInstance();
        String sev = param(q, "sev", "ALL");
        String qual = param(q, "qual", "ALL");
        String customSum = param(q, "summary", null);
        String customDesc = param(q, "description", null);
        String statusFilter = param(q, "statusFilter", "ALL");

        String tagsParam = param(q, "tags", "");
        String excludedTagsParam = param(q, "excludedTags", "");
        String selectedRulesParam = param(q, "selectedRules", "");
        String filterText = param(q, "filterText", "");

        String overrideProject = param(q, "overrideProject", cfg.defaultProject());
        String overrideIssueType = param(q, "overrideIssueType", cfg.issueType());
        String overrideEpic = param(q, "overrideEpic", cfg.epic());
        String overridePriority = param(q, "overridePriority", cfg.priority());
        String overrideSecurity = param(q, "overrideSecurity", cfg.security());
        String overrideTeam = param(q, "overrideTeam", cfg.team());
        String overrideLabels = param(q, "overrideLabels", cfg.labels());
        String overrideComponents = param(q, "overrideComponents", cfg.components());
        String overrideAssignee = param(q, "overrideAssignee", cfg.assignee());

        System.out.println("[CreateJiraIssue] POST: project=" + project
                + " sev=" + sev + " qual=" + qual
                + " statusFilter=" + statusFilter
                + " tags=[" + tagsParam + "]"
                + " excludedTags=[" + excludedTagsParam + "]"
                + " selectedRules=[" + selectedRulesParam + "]"
                + " filterText=[" + filterText + "]");

        if (overrideProject == null || overrideProject.isBlank()) {
            sendError(ex, 400, "Проект не выбран"); return;
        }

        SessionLoader.SessionInfo session;
        try {
            session = SessionLoader.load(Path.of(cfg.sessionFile()));
        } catch (Exception e) {
            sendError(ex, 500, cfg.sessionFile() + ": " + e.getMessage()); return;
        }


        JiraClient jira = new JiraClient(cfg.baseUrl(), session.cookieHeader());
        JiraFieldService fieldService = new JiraFieldService(jira);
        JiraTempoTeamService tempo = new JiraTempoTeamService(jira);
        JiraIssueService issueService = new JiraIssueService(jira, cfg.baseUrl(), fieldService, tempo);
        JiraUserService userService = new JiraUserService(jira);
        JiraIssueCommentService comment = new JiraIssueCommentService(jira);
        JiraIssueAttachmentService attach = new JiraIssueAttachmentService(jira, cfg.baseUrl(), comment);

        String author = "me".equalsIgnoreCase(overrideAssignee) ? session.username() : overrideAssignee;
        if (author == null || author.isBlank()) {
            try { author = userService.getMyself().name(); } catch (Exception ignored) {}
        }

        SonarIssuesApi api = newApi(project);
        List<Issue> issues = new ArrayList<>();
        String keysCsv = param(q, "keys", null);
        byte[] bodyBytes = ex.getRequestBody().readAllBytes();

        boolean hasExplicitKeys = false;

        if (bodyBytes.length > 0 && (keysCsv == null || keysCsv.isEmpty())) {
            System.out.println("[CreateJiraIssue] Body bytes: " + bodyBytes.length);
            try {
                JSONObject req = new JSONObject(new String(bodyBytes, StandardCharsets.UTF_8));
                if (req.has("keys")) {
                    hasExplicitKeys = true;
                    JSONArray arr = req.getJSONArray("keys");
                    List<String> keysList = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String k = arr.optString(i, "");
                        if (k != null && !k.isEmpty()) keysList.add(k);
                    }

                    final int BATCH = 100;
                    for (int i = 0; i < keysList.size(); i += BATCH) {
                        int end = Math.min(i + BATCH, keysList.size());
                        List<String> batch = keysList.subList(i, end);
                        try {
                            JSONObject raw = api.fetchIssuesByKeys(String.join(",", batch));
                            JSONArray issuesArr = raw.optJSONArray("issues");
                            if (issuesArr != null) {
                                for (int j = 0; j < issuesArr.length(); j++) {
                                    issues.add(Issue.from(issuesArr.getJSONObject(j), project));
                                }
                            }
                        } catch (Exception e) {
                            for (String k : batch) {
                                try {
                                    Issue it = api.fetchIssue(k);
                                    if (it != null) issues.add(it);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    System.out.println("[CreateJiraIssue] Explicit keys loaded: "
                            + issues.size() + " из " + keysList.size() + " запрошенных");
                }
            } catch (Exception ignored) {}
        }
        if (issues.isEmpty() && keysCsv != null && !keysCsv.isEmpty()) {
            hasExplicitKeys = true;
            for (String raw : keysCsv.split(",")) {
                Issue it = api.fetchIssue(raw.trim());
                if (it != null) issues.add(it);
            }
        }

        if (!hasExplicitKeys && issues.isEmpty()) {
            String sqTags = tagsParam;
            String sqNotTags = excludedTagsParam;
            if (sqTags != null && sqTags.contains("__NO_TAGS__")) sqTags = null;
            if (sqNotTags != null && sqNotTags.contains("__NO_TAGS__")) sqNotTags = null;

            Set<String> selectedTags = new HashSet<>();
            if (!tagsParam.isEmpty()) for (String t : tagsParam.split(",")) if (!t.isEmpty()) selectedTags.add(t.trim());

            Set<String> excludedTags = new HashSet<>();
            if (!excludedTagsParam.isEmpty()) for (String t : excludedTagsParam.split(",")) if (!t.isEmpty()) excludedTags.add(t.trim());

            Set<String> selectedRules = new HashSet<>();
            if (!selectedRulesParam.isEmpty()) for (String r : selectedRulesParam.split(",")) if (!r.isEmpty()) selectedRules.add(r.trim());

            String qText = filterText == null ? "" : filterText.toLowerCase();
            String fetchStatuses = "UNPROCESSED".equals(statusFilter) ? SonarIssuesApi.ACTIVE_STATUSES : null;

            int page = 1;
            int pageSize = SonarIssuesApi.FETCH_PAGE_SIZE; // 3000
            int total = -1;

            try {
                while (total < 0 || issues.size() < total) {
                    JSONObject raw = api.fetchPageRaw(fetchStatuses, sev, qual, null, sqTags, sqNotTags, page, pageSize);
                    total = raw.optInt("total", 0);
                    JSONArray arr = raw.optJSONArray("issues");
                    if (arr == null || arr.length() == 0) break;

                    for (int i = 0; i < arr.length(); i++) {
                        Issue it = Issue.from(arr.getJSONObject(i), project);
                        if (shouldSkip(it, selectedTags, excludedTags, selectedRules, qText, statusFilter)) continue;
                        issues.add(it);
                    }
                    page++;
                    if (page > 100) break;
                }
            } catch (Exception e) {
                System.out.println("[CreateJiraIssue] Limit hit or error: " + e.getMessage() + ". Falling back to fetchAllByStatuses");
                List<Issue> allIssues;
                if ("UNPROCESSED".equals(statusFilter)) allIssues = api.fetchAllByStatuses(SonarIssuesApi.ACTIVE_STATUSES, sev, qual);
                else if ("PROCESSED".equals(statusFilter)) {
                    allIssues = new ArrayList<>();
                    for (Issue it : api.fetchAllByStatuses(null, sev, qual)) {
                        if (!"OPEN".equals(it.status) && !"REOPENED".equals(it.status)) allIssues.add(it);
                    }
                } else allIssues = api.fetchAllByStatuses(null, sev, qual);

                for (Issue it : allIssues) {
                    if (shouldSkip(it, selectedTags, excludedTags, selectedRules, qText, statusFilter)) continue;
                    issues.add(it);
                }
            }
            System.out.println("[CreateJiraIssue] After filter: " + issues.size() + " issues");
        }

        if (issues.isEmpty()) { sendError(ex, 400, "Нет issues для отчета (проверьте фильтры)"); return; }

        Map<String, List<Issue>> issuesByFile = new TreeMap<>();
        for (Issue it : issues) issuesByFile.computeIfAbsent(it.component != null ? it.component : "(unknown)", k -> new ArrayList<>()).add(it);

        Map<String, List<String>> snippets = Collections.synchronizedMap(new HashMap<>());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, Runtime.getRuntime().availableProcessors()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (List<Issue> lst : issuesByFile.values())
                for (Issue it : lst)
                    futures.add(pool.submit(() -> {
                        try {
                            List<String> snip = fetchSnippetCached(project, it.key);
                            if (snip != null && !snip.isEmpty()) snippets.put(it.key, snip);
                        } catch (Exception ignored) {}
                    }));
            for (Future<?> f : futures) try { f.get(); } catch (Exception ignored) {}
        } finally { pool.shutdown(); }

        Path zip = Files.createTempFile("sonar_jira_", ".zip");
        try {
            HtmlPerFileExporter.writeZip(zip, issuesByFile, snippets, project, (state.getSession() != null ? state.getSession().getHost() : "sonar.bars.group"), false, null);
        } catch (Exception e) {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
            sendError(ex, 500, "Ошибка сборки ZIP: " + e.getMessage()); return;
        }

        String summary = (customSum != null && !customSum.isBlank()) ? customSum : project + ": Необходимо исправить ошибки, найденные SonarQube";
        String description = (customDesc != null && !customDesc.isBlank()) ? customDesc : "Необходимо проанализировать ошибки...\n\n*Всего issues:* " + issues.size();

        String issueTypeId = fieldService.findIssueTypeId(overrideProject, overrideIssueType);
        Map<String, Object> extras = buildExtraFields(fieldService, tempo, overrideProject, issueTypeId,
                overrideEpic, overridePriority, overrideSecurity, overrideTeam, overrideLabels, overrideComponents);

        JiraIssueService.CreatedIssue created;
        try {
            created = issueService.createIssueWithAutoFill(overrideProject, issueTypeId, summary, description, author, extras);
        } catch (Exception e) {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
            sendError(ex, 500, "Не удалось создать задачу: " + e.getMessage()); return;
        }

        String browseUrl = cfg.baseUrl() + "/browse/" + created.key();

        List<JiraIssueAttachmentService.AttachmentInfo> attachments = Collections.emptyList();
        try {
            attachments = attach.uploadFile(created.key(), zip);
        } catch (Exception e) {
            System.err.println("Ошибка загрузки вложения: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
        }

        if (!attachments.isEmpty()) {
            String text = "Отчёт SonarQube сформирован.\nФайлов в отчёте: " + issuesByFile.size() + "\nIssues: " + issues.size();
            try { attach.addCommentWithLinks(created.key(), text, attachments); } catch (Exception ignored) {}
        }

        if (issues.size() >= 10000) {
            System.out.println("[CreateJiraIssue] WARN: Large batch (" + issues.size()
                    + ") — near SonarQube 10000-result limit");
        }
        JSONObject resp = new JSONObject()
                .put("success", true)
                .put("issueKey", created.key())
                .put("url", browseUrl)
                .put("issuesCount", issues.size())
                .put("attachmentsCount", attachments.size());

        sendJson(ex, 200, resp);
    }

    private boolean shouldSkip(Issue it, Set<String> selectedTags, Set<String> excludedTags,
                               Set<String> selectedRules, String qText, String statusFilter) {
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
            boolean m = (it.key != null && it.key.toLowerCase().contains(qText)) ||
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

    private Map<String, Object> buildExtraFields(JiraFieldService fields, JiraTempoTeamService tempo,
                                                 String jiraProject, String issueTypeId,
                                                 String epic, String priority, String security,
                                                 String team, String labels, String components) {
        Map<String, Object> extras = new LinkedHashMap<>();

        if (epic != null && !epic.isBlank()) extras.put("customfield_10101", epic);

        if (priority != null && !priority.isBlank()) {
            String id = priority.matches("\\d+") ? priority : fields.findPriorityIdByName(jiraProject, issueTypeId, priority);
            if (id != null) extras.put("priority", new JSONObject().put("id", id));
        }

        if (security != null && !security.isBlank()) {
            String id = security.matches("\\d+") ? security : fields.findSecurityLevelIdByName(jiraProject, issueTypeId, security);
            if (id != null) extras.put("security", new JSONObject().put("id", id));
        }

        if (team != null && !team.isBlank()) {
            Integer teamId = tempo.findIdByName(team);
            if (teamId != null) extras.put("customfield_10202", teamId);
        }

        if (labels != null && !labels.isBlank()) {
            JSONArray arr = new JSONArray();
            for (String l : labels.split(",")) if (!l.trim().isEmpty()) arr.put(l.trim());
            if (arr.length() > 0) extras.put("labels", arr);
        }

        if (components != null && !components.isBlank()) {
            JSONArray arr = new JSONArray();
            for (String c : components.split(",")) {
                if (c.trim().isEmpty()) continue;
                JSONObject o = new JSONObject();
                if (c.trim().matches("\\d+")) o.put("id", c.trim());
                else o.put("name", c.trim());
                arr.put(o);
            }
            if (arr.length() > 0) extras.put("components", arr);
        }

        return extras;
    }
}
```

## server/handlers/FacetsHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;
import java.util.TreeMap;

public class FacetsHandler extends BaseHandler {

    public FacetsHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        String sev = param(q, "sev", "ALL");
        String qual = param(q, "qual", "ALL");
        String rule = param(q, "rule", null);
        String facet = param(q, "facet", "rules");
        boolean hideProcessed = paramBool(q, "hideProcessed", false);

        String statuses = hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : null;

        SonarIssuesApi api = newApi(project);
        Map<String, Integer> data = api.facetWithStatuses(statuses, sev, qual, rule, facet);

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("facet", facet);
        o.put("values", new JSONObject(data));

        sendJson(ex, 200, o);
    }
}
```

## server/handlers/MatrixHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class MatrixHandler extends BaseHandler {

    public MatrixHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String sev = param(q, "sev", "MEDIUM");
        String qual = param(q, "qual", "MAINTAINABILITY");
        String rule = param(q, "rule", null);
        int page = paramInt(q, "p", 1);
        int ps = paramInt(q, "ps", 50);

        SonarIssuesApi api = newApi(project);
        // 6 аргументов
        JSONObject rawResponse = api.fetchPageRaw(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, rule, page, ps);
        JSONArray rawIssues = rawResponse.optJSONArray("issues");

        Set<String> rules = new TreeSet<>();
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();

        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                Issue it = Issue.from(rawIssues.getJSONObject(i), project);
                rules.add(it.rule);
                matrix.computeIfAbsent(it.component, k -> new TreeMap<>())
                        .merge(it.rule, 1, Integer::sum);
            }
        }

        JSONArray rows = new JSONArray();
        for (Map.Entry<String, Map<String, Integer>> e : matrix.entrySet()) {
            JSONObject row = new JSONObject();
            row.put("file", e.getKey());
            JSONObject counts = new JSONObject();
            int total = 0;
            for (String r : rules) {
                int n = e.getValue().getOrDefault(r, 0);
                counts.put(r, n);
                total += n;
            }
            row.put("counts", counts);
            row.put("total", total);
            rows.put(row);
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("rule", rule != null ? rule : "");
        o.put("total", rawResponse.optInt("total", 0));
        o.put("p", page);
        o.put("ps", ps);
        o.put("rules", new JSONArray(rules));
        o.put("rows", rows);
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/MeasuresHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Measures;
import ru.miacomsoft.sonar.SonarMeasuresApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;

/**
 * GET /api/measures?project=KEY[&metricKeys=a,b,c]
 *
 * Возвращает агрегированные метрики проекта:
 *   - Quality Gate
 *   - общее число issues (violations) и разбивку по видам
 *   - accepted / high impact accepted
 *   - новые issues
 *   - размер (lines, ncloc, ncloc по языкам)
 *   - покрытие, дубликаты
 *   - рейтинги A..E
 *
 * metricKeys можно переопределить вручную (по умолчанию — полный набор).
 */
public class MeasuresHandler extends BaseHandler {

    public MeasuresHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String metricKeys = param(q, "metricKeys", null);

        // Убеждаемся, что project - это одна строка (без лишних пробелов)
        project = project.trim();

        SonarMeasuresApi api = (metricKeys == null)
                ? new SonarMeasuresApi(state.getClient())
                : new SonarMeasuresApi(state.getClient(), metricKeys);

        Measures m = api.fetch(project);
        JSONObject o = m.toJson();
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/PromptHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.BootstrapFiles;
import ru.miacomsoft.sonar.server.PromptTemplate;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GET /api/prompt?project=KEY&issueKey=KEY[&status=accept][&statusLabel=...]
 *
 * Legacy-эндпоинт. Возвращает готовый текст промпта для передачи в LLM.
 * Рендерит из prompts/status-comment.txt через PromptTemplate — тот же
 * шаблон, что использует /api/prompt/build?name=status-comment.
 *
 * Внутри делает все необходимые подзапросы к SonarQube:
 *   - /api/issues/search?issues=KEY          (данные issue)
 *   - /api/rules/show?key=RULE               (имя правила)
 *   - /api/sources/issue_snippets?issueKey=  (фрагмент кода)
 *   - /api/issues/tags?ps=100                (список доступных тегов)
 *
 * Параметры:
 *   - project     : ключ проекта (обязателен)
 *   - issueKey    : ключ issue (обязателен; также поддерживается alias key)
 *   - status      : accept|confirm|falsepositive|resolve|wontfix
 *                   (опционально, по умолчанию accept)
 *   - statusLabel : человекочитаемое описание статуса
 *                   (опционально, по умолчанию берётся из маппинга)
 *
 * Ответ:
 * {
 *   "project": "...", "issueKey": "...", "rule": "...", "ruleName": "...",
 *   "status": "accept", "statusLabel": "...", "severity": "...",
 *   "prompt": "Ты — опытный разработчик ...",
 *   "promptLength": 5028,
 *   "tagsAvailable": [ "cwe", "phpunit", ... ]
 * }
 */
public class PromptHandler extends BaseHandler {

    private final PromptTemplate template;

    /**
     * Конструктор для обратной совместимости: сам находит каталог промптов
     * через BootstrapFiles.resolvePromptsDir().
     */
    public PromptHandler(ServerState state) {
        super(state);
        this.template = new PromptTemplate(BootstrapFiles.resolvePromptsDir());
    }

    /**
     * Основной конструктор: принимает общий PromptTemplate от ApiServer,
     * чтобы не создавать лишний экземпляр.
     */
    public PromptHandler(ServerState state, PromptTemplate template) {
        super(state);
        this.template = template;
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        String issueKey = param(q, "issueKey", null);
        if (issueKey == null || issueKey.isEmpty()) issueKey = param(q, "key", null);
        if (issueKey == null || issueKey.isEmpty()) {
            sendError(ex, 400, "Параметр issueKey (или key) обязателен");
            return;
        }

        String status      = param(q, "status", "accept");
        String statusLabel = param(q, "statusLabel", "");
        if (statusLabel == null || statusLabel.isEmpty()) {
            statusLabel = PromptBuilder.defaultStatusLabel(status);
        }

        SonarIssuesApi api = newApi(project);

        // 1) Данные issue
        Issue issue = api.fetchIssue(issueKey);
        if (issue == null) {
            sendError(ex, 404, "Issue не найден: " + issueKey);
            return;
        }

        // 2) Имя правила
        String ruleName = "";
        try { ruleName = api.fetchRuleName(issue.rule); }
        catch (Exception ignored) { ruleName = ""; }

        // 3) Фрагмент кода (из snapshot-кэша, иначе live)
        List<String> snippet;
        try { snippet = fetchSnippetCached(project, issueKey); }
        catch (Exception e) { snippet = new ArrayList<>(); }

        // 4) Доступные теги + текущие теги issue
        List<String> availableTags;
        try { availableTags = new ArrayList<>(api.fetchTags(null)); }
        catch (Exception e) { availableTags = new ArrayList<>(); }

        if (issue.tags != null) {
            for (String t : issue.tags) {
                if (!availableTags.contains(t)) availableTags.add(t);
            }
        }

        // 5) Сборка промпта: params → PromptTemplate.render("status-comment", params)
        String prompt;
        try {
            Map<String, String> params = PromptBuilder.buildStatusCommentParams(
                    issue, ruleName, snippet, status, statusLabel, availableTags);
            prompt = template.render("status-comment", params);
        } catch (Exception e) {
            sendError(ex, 500,
                    "Не удалось построить промпт из " + template.getPromptsDir()
                            + "/status-comment.txt: " + e.getMessage());
            return;
        }

        if (prompt == null || prompt.isBlank()) {
            sendError(ex, 500,
                    "Файл prompts/status-comment.txt пуст или не содержит [TAG]-полей: "
                            + template.getPromptsDir());
            return;
        }

        // 6) Ответ
        String severity = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("issueKey", issueKey);
        o.put("rule", issue.rule != null ? issue.rule : "");
        o.put("ruleName", ruleName);
        o.put("status", status);
        o.put("statusLabel", statusLabel);
        o.put("severity", severity);
        o.put("prompt", prompt);
        o.put("promptLength", prompt.length());
        o.put("tagsAvailable", new JSONArray(availableTags));

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/StatsProjectsHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.SessionManager;
import ru.miacomsoft.sonar.server.exporters.StatsHtmlExporter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * POST /api/stats/projects-stream
 *
 * Body (application/json):
 * {
 *   "projects":     ["PROJ1", "PROJ2"],
 *   "includedTags": ["tagA"],            // опционально
 *   "excludedTags": ["tagB"],            // опционально
 *   "rules":        ["php:S116", ...],   // опционально (новая поддержка)
 *   "statuses":     "OPEN,CONFIRMED",    // опционально; по умолчанию ALL_STATUSES
 *   "branch":       "master"             // опционально (для ссылок на SonarQube)
 * }
 *
 * Особый маркер "__NO_TAGS__" в includedTags/excludedTags означает
 * «issues без тегов».
 */
public class StatsProjectsHandler extends BaseHandler {

    /** Маркер «Без тегов» (синхронизирован с фронтом: NO_TAGS_KEY). */
    private static final String NO_TAGS_MARKER = "__NO_TAGS__";

    public StatsProjectsHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        state.getClient();

        // ---------- Парсинг тела ----------
        byte[] bodyBytes = ex.getRequestBody().readAllBytes();
        if (bodyBytes.length == 0) {
            sendError(ex, 400, "Пустое тело запроса. Ожидается JSON вида " +
                    "{\"projects\":[\"KEY1\"]}");
            return;
        }

        JSONObject req;
        try {
            req = new JSONObject(new String(bodyBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            sendError(ex, 400, "Невалидный JSON: " + e.getMessage());
            return;
        }

        JSONArray projectsArr = req.optJSONArray("projects");
        if (projectsArr == null || projectsArr.isEmpty()) {
            sendError(ex, 400, "Поле 'projects' обязательно и не должно быть пустым");
            return;
        }

        List<String> projects = new ArrayList<>();
        for (int i = 0; i < projectsArr.length(); i++) {
            String p = projectsArr.optString(i, "").trim();
            if (!p.isEmpty() && !projects.contains(p)) projects.add(p);
        }
        if (projects.isEmpty()) {
            sendError(ex, 400, "Список проектов пуст после трима");
            return;
        }

        Set<String> includedTags  = parseSet(req.optJSONArray("includedTags"));
        Set<String> excludedTags  = parseSet(req.optJSONArray("excludedTags"));
        Set<String> selectedRules = parseSet(req.optJSONArray("rules"));  // NEW

        // ИЗМЕНЕНО: было optString("statuses", SonarIssuesApi.ACTIVE_STATUSES)
        // — сводная статистика по проектам по умолчанию не включала ACCEPTED.
        // Теперь по умолчанию — полный набор (OPEN,CONFIRMED,REOPENED,ACCEPTED,
        // FALSE_POSITIVE,RESOLVED,CLOSED,WONTFIX). sanitizeStatuses() внутри
        // SonarIssuesApi сам смапит legacy → MQR и отбросит неподдерживаемые.
        String statusesParam = req.optString("statuses", SonarIssuesApi.ALL_STATUSES);
        final String statuses = (statusesParam == null || statusesParam.isBlank())
                ? SonarIssuesApi.ALL_STATUSES
                : statusesParam.trim();

        final String branch = req.optString("branch", "");

        // ---------- SSE заголовки ----------
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        OutputStream out = ex.getResponseBody();
        long startMs = System.currentTimeMillis();

        try {
            // ---------- Инициализация ----------
            emitProgress(out, "init", "Инициализация...",
                    0, 0, 0, projects.size(), "", startMs);

            // ---------- Проход по проектам ----------
            Map<String, JSONObject> perProject = new ConcurrentHashMap<>();
            Map<String, String>     errors     = new ConcurrentHashMap<>();

            for (int i = 0; i < projects.size(); i++) {
                String projectKey = projects.get(i);
                int projIdx = i + 1;
                int projTotal = projects.size();

                try {
                    JSONObject stats = computeForProject(
                            projectKey, statuses,
                            includedTags, excludedTags, selectedRules,
                            out, projIdx, projTotal, startMs
                    );
                    perProject.put(projectKey, stats);
                } catch (Exception e) {
                    errors.put(projectKey, e.getClass().getSimpleName()
                            + ": " + (e.getMessage() == null ? "unknown" : e.getMessage()));

                    JSONObject p = new JSONObject();
                    p.put("stage", "error");
                    p.put("message", "Проект " + projectKey + ": " + e.getMessage());
                    p.put("projectKey", projectKey);
                    p.put("projectIndex", projIdx);
                    p.put("projectTotal", projTotal);
                    p.put("elapsedMs", System.currentTimeMillis() - startMs);
                    emitEvent(out, "progress", p);
                }
            }

            // ---------- Агрегация ----------
            emitProgress(out, "aggregating", "Агрегация результатов...",
                    0, 0, projects.size(), projects.size(), "", startMs);

            JSONObject byProject = new JSONObject();
            Stats aggregated = new Stats();
            int okProjects = 0;

            for (String p : projects) {
                JSONObject o = perProject.get(p);
                if (o == null) {
                    JSONObject err = new JSONObject();
                    err.put("error", errors.getOrDefault(p, "unknown error"));
                    byProject.put(p, err);
                } else {
                    byProject.put(p, o);
                    aggregated.merge(o);
                    okProjects++;
                }
            }

            JSONObject response = new JSONObject();
            response.put("includedTags", new JSONArray(includedTags));
            response.put("excludedTags", new JSONArray(excludedTags));
            response.put("rules",        new JSONArray(selectedRules));   // NEW
            response.put("statuses", statuses);
            response.put("projectsRequested", projects.size());
            response.put("projectsOk", okProjects);
            response.put("projectsFailed", projects.size() - okProjects);
            response.put("projects", byProject);
            response.put("totals", aggregated.toJson());
            response.put("sonarBaseUrl", resolveSonarBaseUrl());
            response.put("branch", branch);

            // ---------- Генерация HTML ----------
            emitProgress(out, "rendering", "Формирование HTML-отчёта...",
                    0, 0, projects.size(), projects.size(), "", startMs);

            String html = StatsHtmlExporter.export(response);

            // ---------- Сохранение файла ----------
            emitProgress(out, "saving", "Сохранение файла на сервере...",
                    0, 0, projects.size(), projects.size(), "", startMs);

            Path file = createExportFile();
            Files.writeString(file, html, StandardCharsets.UTF_8);

            // ---------- complete ----------
            JSONObject complete = new JSONObject();
            complete.put("file", file.toAbsolutePath().toString());
            complete.put("downloadName", file.getFileName().toString());
            complete.put("total", aggregated.totalIssues);
            complete.put("projectsOk", okProjects);
            complete.put("projectsRequested", projects.size());
            complete.put("elapsedMs", System.currentTimeMillis() - startMs);
            emitEvent(out, "complete", complete);

        } catch (Exception e) {
            JSONObject err = new JSONObject();
            err.put("message", e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "unknown" : e.getMessage()));
            try { emitEvent(out, "error", err); } catch (IOException ignored) {}
        } finally {
            try { out.flush(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    // ============================================================
    // Внутренняя логика
    // ============================================================

    private Set<String> parseSet(JSONArray arr) {
        Set<String> set = new LinkedHashSet<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String t = arr.optString(i, "").trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        return set;
    }

    private JSONObject computeForProject(
            String projectKey, String statuses,
            Set<String> includedTags, Set<String> excludedTags, Set<String> selectedRules,
            OutputStream out, int projIdx, int projTotal, long startMs
    ) throws Exception {

        SonarIssuesApi api = state.newApi(projectKey);

        SonarIssuesApi.FetchProgressListener listener = (fetched, total, message) -> {
            JSONObject p = new JSONObject();
            p.put("stage", "fetch");
            p.put("message", message != null ? message : "");
            p.put("current", fetched);
            p.put("total", total);
            p.put("projectKey", projectKey);
            p.put("projectIndex", projIdx);
            p.put("projectTotal", projTotal);
            p.put("elapsedMs", System.currentTimeMillis() - startMs);
            try {
                emitEvent(out, "progress", p);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        List<Issue> all = api.fetchAllByStatuses(statuses, "ALL", "ALL", listener);

        // ---------- Подсчёт ----------
        Stats stats = new Stats();
        Set<String> files = new HashSet<>();

        for (Issue it : all) {
            if (!matchesTagFilter(it, includedTags, excludedTags)) continue;

            // NEW: фильтр по правилам (если список пуст — не фильтруем)
            if (!selectedRules.isEmpty() && !selectedRules.contains(it.rule)) continue;

            stats.totalIssues++;

            String sev = (it.impactSeverity != null && !it.impactSeverity.isEmpty())
                    ? it.impactSeverity
                    : (it.severity != null ? it.severity : "");
            if (sev.isEmpty()) sev = "UNKNOWN";
            stats.bySeverity.merge(sev, 1, Integer::sum);

            boolean hasQuality = false;
            if (it.impacts != null && !it.impacts.isEmpty()) {
                Set<String> seen = new HashSet<>();
                for (Issue.Impact im : it.impacts) {
                    if (im == null) continue;
                    String q = im.softwareQuality;
                    if (q == null || q.isEmpty()) continue;
                    if (seen.add(q)) {
                        stats.byQuality.merge(q, 1, Integer::sum);
                        hasQuality = true;
                    }
                }
            }
            if (!hasQuality) {
                stats.byQuality.merge("UNKNOWN", 1, Integer::sum);
            }

            if (it.tags != null) {
                for (String t : it.tags) {
                    if (t == null || t.isEmpty()) continue;
                    stats.byTag.merge(t, 1, Integer::sum);
                }
            }

            // NEW: подсчёт по правилам
            if (it.rule != null && !it.rule.isEmpty()) {
                stats.byRule.merge(it.rule, 1, Integer::sum);
            }

            if (it.component != null && !it.component.isEmpty()) {
                files.add(it.component);
            }
        }

        stats.totalFiles = files.size();

        JSONObject done = new JSONObject();
        done.put("stage", "project-done");
        done.put("message", "Проект " + projectKey + " обработан (" + stats.totalIssues + " issues)");
        done.put("projectKey", projectKey);
        done.put("projectIndex", projIdx);
        done.put("projectTotal", projTotal);
        done.put("elapsedMs", System.currentTimeMillis() - startMs);
        emitEvent(out, "progress", done);

        return stats.toJson();
    }

    /**
     * Фильтр по тегам. Поддерживает маркер {@code __NO_TAGS__}:
     * если он есть в included — включаются issues без тегов;
     * если он есть в excluded — исключаются issues без тегов.
     */
    private boolean matchesTagFilter(Issue it, Set<String> included, Set<String> excluded) {
        List<String> itTags = it.tags != null ? it.tags : Collections.emptyList();

        if (!included.isEmpty()) {
            boolean has = false;
            for (String t : included) {
                if (NO_TAGS_MARKER.equals(t)) {
                    if (itTags.isEmpty()) { has = true; break; }
                } else if (itTags.contains(t)) {
                    has = true;
                    break;
                }
            }
            if (!has) return false;
        }

        if (!excluded.isEmpty()) {
            for (String t : excluded) {
                if (NO_TAGS_MARKER.equals(t)) {
                    if (itTags.isEmpty()) return false;
                } else if (itTags.contains(t)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Path createExportFile() throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sonar-stats-export");
        Files.createDirectories(dir);
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return dir.resolve("stats_projects_" + ts + "_" + System.nanoTime() + ".html");
    }

    // ============================================================
    // SSE emission
    // ============================================================

    private static void emitProgress(
            OutputStream out, String stage, String message,
            int current, int total,
            int projectIndex, int projectTotal,
            String projectKey, long startMs
    ) throws IOException {
        JSONObject p = new JSONObject();
        p.put("stage", stage);
        p.put("message", message != null ? message : "");
        p.put("current", current);
        p.put("total", total);
        p.put("projectIndex", projectIndex);
        p.put("projectTotal", projectTotal);
        p.put("projectKey", projectKey != null ? projectKey : "");
        p.put("elapsedMs", System.currentTimeMillis() - startMs);
        emitEvent(out, "progress", p);
    }

    private static void emitEvent(OutputStream out, String eventName, JSONObject data)
            throws IOException {
        String payload = "event: " + eventName + "\n"
                + "data: " + data.toString() + "\n\n";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ============================================================
    // Аккумулятор
    // ============================================================

    private static class Stats {
        int totalIssues;
        int totalFiles;
        final Map<String, Integer> bySeverity = new TreeMap<>();
        final Map<String, Integer> byQuality  = new TreeMap<>();
        final Map<String, Integer> byTag      = new TreeMap<>();
        final Map<String, Integer> byRule     = new TreeMap<>(); // NEW

        void merge(JSONObject other) {
            if (other == null) return;
            totalIssues += other.optInt("totalIssues", 0);
            totalFiles  += other.optInt("totalFiles", 0);
            mergeMap(other.optJSONObject("bySeverity"), bySeverity);
            mergeMap(other.optJSONObject("byQuality"),  byQuality);
            mergeMap(other.optJSONObject("byTag"),      byTag);
            mergeMap(other.optJSONObject("byRule"),     byRule); // NEW
        }

        private static void mergeMap(JSONObject src, Map<String, Integer> dst) {
            if (src == null) return;
            for (String k : src.keySet()) dst.merge(k, src.optInt(k, 0), Integer::sum);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("totalIssues", totalIssues);
            o.put("totalFiles",  totalFiles);
            o.put("bySeverity",  new JSONObject(bySeverity));
            o.put("byQuality",   new JSONObject(byQuality));
            o.put("byTag",       new JSONObject(byTag));
            o.put("byRule",      new JSONObject(byRule)); // NEW
            return o;
        }
    }

    private String resolveSonarBaseUrl() {
        String prop = System.getProperty("sonar.url");
        if (prop != null && !prop.isBlank()) return prop.replaceAll("/+$", "");

        String env = System.getenv("SONAR_URL");
        if (env != null && !env.isBlank()) return env.replaceAll("/+$", "");

        try {
            SessionManager sm = state.getSession();
            if (sm != null) {
                String u = sm.getUrl();
                if (u != null && !u.isBlank()) {
                    return u.replaceAll("/+$", "");
                }
            }
        } catch (Exception ignored) {}

        try {
            Object client = state.getClient();
            if (client != null) {
                for (String m : new String[]{"getBaseUrl", "getUrl", "getServerUrl", "getHost"}) {
                    try {
                        java.lang.reflect.Method method = client.getClass().getMethod(m);
                        Object result = method.invoke(client);
                        if (result != null) {
                            String s = result.toString();
                            if (!s.isBlank()) return s.replaceAll("/+$", "");
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return "";
    }
}
```

