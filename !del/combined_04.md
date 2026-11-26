# Combined part 4 of 5

- files: 20
- bytes: 136380

## Main.java

```
package ru.miacomsoft.sonar;

import ru.miacomsoft.sonar.server.ApiServer;
import ru.miacomsoft.sonar.server.BootstrapFiles;
import ru.miacomsoft.sonar.server.Console;
import ru.miacomsoft.sonar.server.OllamaConfig;
import ru.miacomsoft.sonar.server.ServerState;

import java.nio.file.Path;

public class Main {

    private static final int DEFAULT_PORT = 6060;

    public static void main(String[] args) throws Exception {
        String host = "0.0.0.0";
        int port = DEFAULT_PORT;
        int threads = 8;
        int batchSize = SonarBulkApi.DEFAULT_BATCH_SIZE;
        long delayMs = SonarBulkApi.DEFAULT_DELAY_MS;
        String sessionFile = "session.properties";
        String project = System.getenv().getOrDefault("SONAR_PROJECT", "");

        // ИЗМЕНЕНИЕ: Отключаем сборку снимка при старте, так как перешли на прямые запросы к SonarQube
        boolean warmup = false;

        boolean asyncWarmup = false;
        boolean warmupSnippets = false; // Сниппеты тоже не кэшируем глобально
        int snippetLimit = 0;
        boolean console = true;

        String ollamaConfigFile  = "ollama.properties";
        String consoleConfigFile = "console.properties";

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--host="))                host = a.substring("--host=".length());
            else if (a.startsWith("--port="))           port = Integer.parseInt(a.substring("--port=".length()));
            else if (a.startsWith("--threads="))        threads = Integer.parseInt(a.substring("--threads=".length()));
            else if (a.startsWith("--batch="))          batchSize = Integer.parseInt(a.substring("--batch=".length()));
            else if (a.startsWith("--delay="))          delayMs = Long.parseLong(a.substring("--delay=".length()));
            else if (a.startsWith("--session="))        sessionFile = a.substring("--session=".length());
            else if (a.startsWith("--session-properties=")) sessionFile = a.substring("--session-properties=".length());
            else if (a.startsWith("--project="))        project = a.substring("--project=".length());
            else if (a.startsWith("--snippet-limit="))  snippetLimit = Integer.parseInt(a.substring("--snippet-limit=".length()));
            else if (a.startsWith("--ollama-config="))  ollamaConfigFile = a.substring("--ollama-config=".length());
            else if (a.startsWith("--console-config=")) consoleConfigFile = a.substring("--console-config=".length());

                // Если пользователь явно захочет собрать снимок, он может передать --warmup
            else if ("--warmup".equals(a))              warmup = true;
            else if ("--no-warmup".equals(a))           warmup = false;
            else if ("--async-warmup".equals(a))        asyncWarmup = true;
            else if ("--snippets".equals(a))            warmupSnippets = true;
            else if ("--no-snippets".equals(a))         warmupSnippets = false;
            else if ("--no-console".equals(a))          console = false;
            else if (i == 0)                            sessionFile = a;
            else if (i == 1)                            project = a;
        }

        // ---------- BOOTSTRAP ----------
        Path sessionPath = Path.of(sessionFile);
        Path promptsDir  = BootstrapFiles.resolvePromptsDir();
        BootstrapFiles.ensureAll(sessionPath, promptsDir);

        ServerState state = new ServerState(
                sessionPath, project, batchSize, delayMs,
                warmupSnippets, snippetLimit);

        OllamaConfig ollamaConfig = new OllamaConfig(Path.of(ollamaConfigFile));
        System.out.println("[main] Ollama config: " + ollamaConfig.getConfigFile()
                + " (url=" + ollamaConfig.getUrl()
                + ", model=" + ollamaConfig.getModel() + ")");

        if (console) {
            new Console(state, ollamaConfig, Path.of(consoleConfigFile)).start();
            System.out.println("[main] Console state: " + consoleConfigFile);
        } else {
            System.out.println("[main] Console disabled (--no-console)");
        }

        if (warmup) {
            if (!state.isSessionReady()) {
                System.out.println("[Warmup] SKIP: сессия не загружена");
            } else if (project == null || project.isEmpty()) {
                System.out.println("[Warmup] SKIP: project не задан.");
            } else if (asyncWarmup) {
                System.out.println("[Warmup] async: запускаю пересбор снимка в фоне");
                state.buildSnapshotAsync(project);
            } else {
                System.out.println("[Warmup] sync: собираю снимок для " + project + " ...");
                try {
                    state.buildSnapshotSync(project);
                } catch (Exception e) {
                    System.err.println("[Warmup] FAILED: " + e.getMessage());
                }
            }
        } else {
            System.out.println("[Warmup] отключён (--no-warmup). Работаем напрямую через SonarQube API.");
        }

        ApiServer.start(host, port, threads, state);
        Thread.currentThread().join();
    }
}
```

## SonarClient.java

```
package ru.miacomsoft.sonar;

import ru.miacomsoft.sonar.server.SessionManager;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Тонкий HTTP-клиент к стандартному SonarQube Web API.
 *
 * Аутентификация по session.properties:
 *   - sonar.auth=bearer  → Authorization: Bearer <token>
 *   - sonar.auth=basic   → Authorization: Basic base64(user:token)
 *   - sonar.auth=cookie  → Cookie: <cookie> + X-XSRF-TOKEN (из cookie)
 *
 * HTTP 401 → SessionExpiredException (чтобы AutoProcessor мог остановиться).
 */
public class SonarClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** Сессия/токен недействителен (HTTP 401). */
    public static class SessionExpiredException extends RuntimeException {
        public SessionExpiredException(String message) { super(message); }
    }

    private final SessionManager session;
    private final HttpClient http;

    private final String authHeader;
    private final String cookieHeader;
    private final String xsrfToken;

    public SonarClient(SessionManager session) {
        this.session = session;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();

        String type = session.getAuthType() == null
                ? "bearer" : session.getAuthType().toLowerCase();

        if ("cookie".equals(type)) {
            this.authHeader   = null;
            this.cookieHeader = session.getCookie();
            this.xsrfToken    = extractXsrf(session.getCookie());
        } else if ("basic".equals(type)) {
            String raw = session.getUser() + ":" + session.getToken();
            this.authHeader   = "Basic " + Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            this.cookieHeader = null;
            this.xsrfToken    = null;
        } else { // bearer
            this.authHeader   = "Bearer " + session.getToken();
            this.cookieHeader = null;
            this.xsrfToken    = null;
        }
    }

    private static String extractXsrf(String cookie) {
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("XSRF-TOKEN=")) {
                return p.substring("XSRF-TOKEN=".length());
            }
        }
        return null;
    }

    // ============ GET ============

    public String get(String pathAndQuery) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(session.getUrl() + pathAndQuery))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "SonarRestApi/2.0");
        if (authHeader   != null) b.header("Authorization", authHeader);
        if (cookieHeader != null) b.header("Cookie", cookieHeader);
        if (xsrfToken    != null) b.header("X-XSRF-TOKEN", xsrfToken);

        HttpResponse<String> r = http.send(b.GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (r.statusCode() == 401) {
            throw new SessionExpiredException("GET " + pathAndQuery
                    + " -> HTTP 401 (неверный токен или истёк срок)");
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new RuntimeException("GET " + pathAndQuery
                    + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    // ============ POST (form-urlencoded) ============

    public String postForm(String path, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(
                                e.getValue() == null ? "" : e.getValue(),
                                StandardCharsets.UTF_8));
            }
        }

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(session.getUrl() + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "SonarRestApi/2.0");
        if (authHeader   != null) b.header("Authorization", authHeader);
        if (cookieHeader != null) b.header("Cookie", cookieHeader);
        if (xsrfToken    != null) b.header("X-XSRF-TOKEN", xsrfToken);

        HttpRequest req = b.POST(HttpRequest
                        .BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> r = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (r.statusCode() == 401) {
            throw new SessionExpiredException("POST " + path
                    + " -> HTTP 401 (неверный токен или истёк срок)");
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new RuntimeException("POST " + path
                    + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    public SessionManager getSession() { return session; }

    /**
     * Базовый URL SonarQube (без завершающего слэша).
     * Нужен, в частности, StatsProjectsHandler для построения кликабельных
     * ссылок в HTML-отчёте. Рефлексия в resolveSonarBaseUrl() ищет именно
     * этот метод (или getBaseUrl / getServerUrl / getHost).
     */
    public String getUrl() {
        return session != null ? session.getUrl() : null;
    }
}
```

## jira/JiraCancelService.java

```
package ru.miacomsoft.sonar.jira;

import ru.miacomsoft.sonar.jira.JiraIssueCommentService;
import ru.miacomsoft.sonar.jira.JiraIssueDeleteService;
import ru.miacomsoft.sonar.jira.JiraIssueTransitionService;

import java.io.IOException;

public class JiraCancelService {

    private final JiraIssueDeleteService deleteService;
    private final JiraIssueTransitionService transitionService;
    private final JiraIssueCommentService commentService;

    public JiraCancelService(JiraIssueDeleteService deleteService,
                             JiraIssueTransitionService transitionService,
                             JiraIssueCommentService commentService) {
        this.deleteService = deleteService;
        this.transitionService = transitionService;
        this.commentService = commentService;
    }

    public String deleteOrClose(String issueIdOrKey) throws IOException, InterruptedException {
        // 1) DELETE
        try {
            deleteService.delete(issueIdOrKey);
            return "deleted";
        } catch (IllegalStateException e) {
            String m = e.getMessage();
            if (m == null || !m.contains("HTTP 403")) throw e;
            System.out.println("  INFO: нет прав на удаление " + issueIdOrKey + " — закрываю/отменяю");
        }

        String wonDo = transitionService.findResolutionId(
                "Won't Do", "Won't Fix", "Не будет сделано", "Отклонено", "Не будет исправлено");

        // 2) Прямое закрытие
        for (String t : new String[]{"Closed","Закрыто","Done","Готово","Resolved","Решено"}) {
            if (transitionService.tryTransition(issueIdOrKey, t, wonDo)) {
                System.out.println("  INFO: закрыто через '" + t + "'"); return "closed";
            }
            if (transitionService.tryTransition(issueIdOrKey, t, null)) {
                System.out.println("  INFO: закрыто через '" + t + "'"); return "closed";
            }
        }

        // 3) Комментарий
        try {
            commentService.add(issueIdOrKey, "Задача на удаление");
            System.out.println("  INFO: комментарий добавлен");
        } catch (Exception e) { System.out.println("  WARN: комментарий: " + e.getMessage()); }

        // 3a) Новая → К разработке
        for (String t : new String[]{"К разработке","To Development","In Development"}) {
            if (transitionService.tryTransition(issueIdOrKey, t, null)) {
                System.out.println("  INFO: шаг 1 — '" + t + "'"); break;
            }
        }
        // 3b) К разработке → Анализ
        for (String t : new String[]{"Вернуть на анализ","Анализ","Analysis"}) {
            if (transitionService.tryTransition(issueIdOrKey, t, null)) {
                System.out.println("  INFO: шаг 2 — '" + t + "'"); break;
            }
        }
        // 3c) Анализ → Отменена
        for (String t : new String[]{"Отклонить задачу","Отменена","Отменено",
                "Cancelled","Cancel","Отклонена","Отклонено"}) {
            if (transitionService.tryTransition(issueIdOrKey, t, wonDo)) {
                System.out.println("  INFO: шаг 3 — '" + t + "'"); return "cancelled";
            }
            if (transitionService.tryTransition(issueIdOrKey, t, null)) {
                System.out.println("  INFO: шаг 3 — '" + t + "' (без resolution)"); return "cancelled";
            }
        }
        System.out.println("  WARN: не удалось ни удалить, ни отменить " + issueIdOrKey);
        return "not-removed";
    }
}
```

## jira/JiraConfig.java

```
package ru.miacomsoft.sonar.jira;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class JiraConfig {

    public static final String DEFAULT_URL         = "https://jira-medmis.bars.group";
    public static final String DEFAULT_ISSUE_TYPE  = "10206";
    public static final String DEFAULT_PROJECT     = "MEDDEV";
    public static final String DEFAULT_SESSION     = "session.txt";
    public static final String CONFIG_FILE_NAME    = "jira.properties";

    private static volatile JiraConfig INSTANCE;
    private final Path configFile;

    private String baseUrl;
    private String sessionFile;
    private String issueType;
    private String defaultProject;
    private String epic;
    private String priority;
    private String security;
    private String team;
    private String labels;
    private String components;
    private String assignee;

    private JiraConfig(Path configFile) {
        this.configFile = configFile;
        load();
    }

    public static JiraConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (JiraConfig.class) {
                if (INSTANCE == null) {
                    String cfgPath = System.getProperty("jira.config", CONFIG_FILE_NAME);
                    INSTANCE = new JiraConfig(Path.of(cfgPath));
                }
            }
        }
        return INSTANCE;
    }

    public void reload() { load(); }

    private void load() {
        this.baseUrl        = DEFAULT_URL;
        this.sessionFile    = DEFAULT_SESSION;
        this.issueType      = DEFAULT_ISSUE_TYPE;
        this.defaultProject = DEFAULT_PROJECT;
        this.epic           = "";
        this.priority       = "";
        this.security       = "";
        this.team           = "HM12"; // По умолчанию HM12
        this.labels         = "";
        this.components     = "";
        this.assignee       = "me";

        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                Properties p = new Properties();
                p.load(reader);
                baseUrl        = safe(p.getProperty("jira.url"),            baseUrl);
                sessionFile    = safe(p.getProperty("jira.session"),        sessionFile);
                issueType      = safe(p.getProperty("jira.issueType"),      issueType);
                defaultProject = safe(p.getProperty("jira.defaultProject"), defaultProject);
                epic           = safe(p.getProperty("jira.epic"),           epic);
                priority       = safe(p.getProperty("jira.priority"),       priority);
                security       = safe(p.getProperty("jira.security"),       security);
                team           = safe(p.getProperty("jira.team"),           team);
                labels         = safe(p.getProperty("jira.labels"),         labels);
                components     = safe(p.getProperty("jira.components"),     components);
                assignee       = safe(p.getProperty("jira.assignee"),       assignee);
            } catch (Exception e) {
                System.err.println("[JiraConfig] " + configFile + ": " + e.getMessage());
            }
        }

        String override = System.getProperty("jira.url");
        if (override == null || override.isBlank()) override = System.getenv("JIRA_URL");
        if (override != null && !override.isBlank()) baseUrl = override.trim();
        while (baseUrl != null && baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    private static String safe(String v, String def) {
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    public String baseUrl()        { return baseUrl; }
    public String sessionFile()    { return sessionFile; }
    public String issueType()      { return issueType; }
    public String defaultProject() { return defaultProject; }
    public String epic()           { return epic; }
    public String priority()       { return priority; }
    public String security()       { return security; }
    public String team()           { return team; }
    public String labels()         { return labels; }
    public String components()     { return components; }
    public String assignee()       { return assignee; }
    public Path   configFile()     { return configFile; }
}
```

## jira/JiraCreateParametersService.java

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

public class JiraCreateParametersService {

    public record CreateParameters(
            String projectKey,
            String issueTypeId,
            String contextIssueKey,
            List<JiraFieldService.AllowedValue> priorities,
            List<JiraFieldService.AllowedValue> securityLevels,
            List<JiraFieldService.AllowedValue> versions,
            List<JiraFieldService.AllowedValue> fixVersions,
            List<JiraFieldService.AllowedValue> components,
            Map<String, List<JiraFieldService.AllowedValue>> nfeedOptions
    ) {}

    private final JiraClient jira;
    private final JiraFieldService fieldService;
    private final JiraNfeedService nfeedService;

    public JiraCreateParametersService(JiraClient jira,
                                       JiraFieldService fieldService,
                                       JiraNfeedService nfeedService) {
        this.jira = jira;
        this.fieldService = fieldService;
        this.nfeedService = nfeedService;
    }

    public CreateParameters load(String projectKey, String issueTypeId)
            throws IOException, InterruptedException {
        String sample = null;
        try {
            JSONObject root = new JSONObject(jira.get("/rest/api/2/search?jql="
                    + enc("project=" + projectKey + " AND issuetype=" + issueTypeId + " ORDER BY created DESC")
                    + "&fields=key&maxResults=1"));
            JSONArray issues = root.optJSONArray("issues");
            if (issues != null && !issues.isEmpty()) {
                sample = issues.getJSONObject(0).optString("key", null);
            }
        } catch (Exception ignored) { }
        return load(projectKey, issueTypeId, sample);
    }

    public CreateParameters load(String projectKey, String issueTypeId, String context)
            throws IOException, InterruptedException {
        Map<String, JiraFieldService.FieldMeta> meta = fieldService.getCreatemeta(projectKey, issueTypeId);
        List<JiraFieldService.AllowedValue> priorities =
                meta.containsKey("priority") ? meta.get("priority").allowedValues() : List.of();
        List<JiraFieldService.AllowedValue> securityLevels =
                meta.containsKey("security") ? meta.get("security").allowedValues() : List.of();
        List<JiraFieldService.AllowedValue> versions = loadVersions(projectKey);
        List<JiraFieldService.AllowedValue> components = loadComponents(projectKey);

        Map<String, List<JiraFieldService.AllowedValue>> nfeed = new LinkedHashMap<>();
        if (context != null) {
            for (JiraFieldService.FieldMeta fm : meta.values()) {
                if (fm.custom() != null && fm.custom().contains("nfeed")) {
                    nfeed.put(fm.id(), nfeedService.loadOptions(fm.id(), context));
                }
            }
        }
        return new CreateParameters(projectKey, issueTypeId, context,
                priorities, securityLevels, versions, versions, components, nfeed);
    }

    private List<JiraFieldService.AllowedValue> loadVersions(String projectKey) {
        try {
            JSONArray arr = new JSONArray(jira.get("/rest/api/2/project/" + enc(projectKey) + "/versions"));
            List<JiraFieldService.AllowedValue> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new JiraFieldService.AllowedValue(o.optString("id"), o.optString("name")));
            }
            return out;
        } catch (Exception e) { return List.of(); }
    }

    private List<JiraFieldService.AllowedValue> loadComponents(String projectKey) {
        try {
            JSONArray arr = new JSONArray(jira.get("/rest/api/2/project/" + enc(projectKey) + "/components"));
            List<JiraFieldService.AllowedValue> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new JiraFieldService.AllowedValue(o.optString("id"), o.optString("name")));
            }
            return out;
        } catch (Exception e) { return List.of(); }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
```

## jira/JiraUserService.java

```
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
```

## jira/SessionLoader.java

```
package ru.miacomsoft.sonar.jira;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SessionLoader {

    // ===== ДОБАВЛЕНО: baseUrl (из строки "Request URL" в session.txt) =====
    public record SessionInfo(String cookieHeader, String username, String baseUrl) {}

    public static SessionInfo loadDefault() throws IOException {
        String prop = System.getProperty("jira.session");
        Path p = (prop != null && !prop.isBlank()) ? Path.of(prop) : Path.of("session.txt");
        return load(p);
    }

    public static SessionInfo load(Path sessionFile) throws IOException {
        if (!Files.exists(sessionFile)) {
            throw new IOException("session.txt not found: " + sessionFile.toAbsolutePath());
        }
        List<String> lines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
        String cookie = null, username = null, baseUrl = null;

        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty()) continue;

            if (t.regionMatches(true, 0, "cookie:", 0, 7)) {
                cookie = t.substring(7).trim(); continue;
            }
            if (t.regionMatches(true, 0, "x-ausername:", 0, 12)) {
                username = t.substring(12).trim(); continue;
            }

            // ===== ДОБАВЛЕНО: разбор "Request URL" → baseUrl =====
            if (t.equalsIgnoreCase("Request URL") && i + 1 < lines.size()) {
                String urlLine = lines.get(i + 1).trim();
                try {
                    URI u = URI.create(urlLine);
                    String scheme = u.getScheme() == null ? "https" : u.getScheme();
                    int port = u.getPort();
                    baseUrl = scheme + "://" + u.getHost()
                            + (port > 0 && port != 80 && port != 443 ? ":" + port : "");
                } catch (Exception ignored) {}
                i++;
                continue;
            }

            if (t.equalsIgnoreCase("cookie") && i + 1 < lines.size()) {
                StringBuilder sb = new StringBuilder(lines.get(i + 1).trim());
                int j = i + 2;
                while (j < lines.size() && !lines.get(j).isEmpty()
                        && Character.isWhitespace(lines.get(j).charAt(0))) {
                    sb.append(' ').append(lines.get(j).trim());
                    j++;
                }
                cookie = sb.toString(); i = j - 1;
            } else if (t.equalsIgnoreCase("x-ausername") && i + 1 < lines.size()) {
                username = lines.get(i + 1).trim(); i++;
            }
        }
        if (cookie == null || cookie.isEmpty()) {
            throw new IllegalArgumentException("Cookie header not found in " + sessionFile);
        }
        return new SessionInfo(cookie, username, baseUrl);
    }
}
```

## server/ApiServer.java

```
package ru.miacomsoft.sonar.server;

import com.sun.net.httpserver.HttpServer;
import ru.miacomsoft.sonar.server.handlers.*;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ApiServer {

    private ApiServer() {}

    public static void start(String host, int port, int threads, ServerState state)
            throws Exception {

        Path promptsDir = resolvePromptsDir();
        PromptTemplate template = new PromptTemplate(promptsDir);

        System.out.println("[ApiServer] prompts dir: " + promptsDir);
        List<String> availablePrompts = template.listAvailable();
        System.out.println("[ApiServer] available prompts: "
                + (availablePrompts.isEmpty() ? "<none>" : String.join(", ", availablePrompts)));
        if (availablePrompts.isEmpty()) {
            System.err.println("[ApiServer] ВНИМАНИЕ: в каталоге промптов нет ни одного .txt. "
                    + "Положите туда status-comment.txt, bulk-status-comment.txt, "
                    + "auto-resolve.txt, analyze-issue.txt.");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/api/session",        new SessionHandler(state));
        server.createContext("/api/session/reload", new SessionReloadHandler(state));
        server.createContext("/api/refresh",        new RefreshHandler(state));
        server.createContext("/api/refresh/issue",  new RefreshIssueHandler(state));
        server.createContext("/api/status",         new StatusHandler(state));

        server.createContext("/api/health",    new HealthHandler(state));
        server.createContext("/api/measures",  new MeasuresHandler(state));
        server.createContext("/api/metadata",  new MetadataHandler(state));
        server.createContext("/api/accepted",  new AcceptedHandler(state));
        server.createContext("/api/summary",   new SummaryHandler(state));
        server.createContext("/api/matrix",    new MatrixHandler(state));
        server.createContext("/api/perfile",   new PerFileHandler(state));
        server.createContext("/api/details",   new DetailsHandler(state));
        server.createContext("/api/issues",    new IssuesHandler(state));
        server.createContext("/api/keys",      new KeysHandler(state));
        server.createContext("/api/snippet",   new SnippetHandler(state));
        server.createContext("/api/sources/full", new SourceFullHandler(state));
        server.createContext("/api/sources",   new SourceHandler(state));
        server.createContext("/api/changelog", new ChangelogHandler(state));
        server.createContext("/api/projects",  new ProjectsHandler(state));

        server.createContext("/api/team_mapping", new TeamMappingHandler(state));

        server.createContext("/api/tags",      new TagsHandler(state));
        server.createContext("/api/set_tags",  new SetTagsHandler(state));

        server.createContext("/api/mark",      new MarkHandler(state));
        server.createContext("/api/cancel",    new CancelHandler(state));

        server.createContext("/api/prompt/build", new BuildPromptHandler(state, template));
        server.createContext("/api/prompt/list",  new ListPromptsHandler(state, template));
        server.createContext("/api/facets",       new FacetsHandler(state));
        server.createContext("/api/stats/projects", new StatsProjectsHandler(state));

        server.createContext("/api/export-stream",   new ExportStreamHandler(state));
        server.createContext("/api/export-download", new ExportDownloadHandler(state));

        // ===== ИСПРАВЛЕНО: убран дубликат create-issue =====
        server.createContext("/api/jira/config",       new CreateJiraIssueHandler(state));
        server.createContext("/api/jira/create-issue", new CreateJiraIssueHandler(state));
        server.createContext("/api/jira/epics",        new CreateJiraIssueHandler(state));
        server.createContext("/api/jira/teams",        new CreateJiraIssueHandler(state));

        Path www = resolveWwwDir();
        if (www != null) {
            server.createContext("/", new StaticHandler(www));
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        server.setExecutor(pool);
        server.start();

        System.out.println("===================================================");
        System.out.println(" Sonar REST API server started");
        System.out.println(" Bind:        " + host + ":" + port);
        System.out.println(" Prompts dir: " + promptsDir);
        System.out.println(" Prompts:     " + (availablePrompts.isEmpty()
                ? "<none>" : String.join(", ", availablePrompts)));
        System.out.println(" Endpoints:");
        System.out.println("   GET  /api/prompt/build?name=<n>&project=<p>&issueKey=<k>");
        System.out.println("   GET  /api/prompt/list");
        System.out.println("   GET  /api/export-stream?format=<f>&project=<p>...");
        System.out.println("   GET  /api/export-download?file=<path>");
        System.out.println("   GET  /api/jira/config");
        System.out.println("   POST /api/jira/create-issue?project=<p>...");
        System.out.println("===================================================");
    }

    private static Path resolveWwwDir() {
        String sys = System.getProperty("sonar.www");
        if (sys != null && !sys.isBlank()) {
            Path p = Path.of(sys).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
        }
        try {
            var cs = ApiServer.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path loc = Path.of(cs.getLocation().toURI()).toAbsolutePath();
                Path dir = Files.isDirectory(loc) ? loc : loc.getParent();
                if (dir != null) {
                    Path beside = dir.resolve("www");
                    if (Files.isDirectory(beside)) return beside;
                    Path parent = dir.resolve("..").resolve("www").normalize();
                    if (Files.isDirectory(parent)) return parent;
                }
            }
        } catch (Exception ignored) {}
        Path cwd = Path.of("www").toAbsolutePath().normalize();
        return Files.isDirectory(cwd) ? cwd : null;
    }

    private static Path resolvePromptsDir() {
        String sys = System.getProperty("sonar.prompts");
        if (sys != null && !sys.isBlank()) {
            Path p = Path.of(sys).toAbsolutePath().normalize();
            try { Files.createDirectories(p); } catch (Exception ignored) {}
            return p;
        }
        try {
            var cs = ApiServer.class.getProtectionDomain().getCodeSource();
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

## server/AutoProcessor.java

```
package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarClient.SessionExpiredException;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.handlers.PromptBuilder;
import ru.miacomsoft.sonar.server.ollama.OllamaClient;
import ru.miacomsoft.sonar.server.ollama.OllamaResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

public class AutoProcessor {

    private static final int MAX_CONSECUTIVE_NETWORK_ERRORS = 3;

    public interface ProgressCallback {
        void update(Progress p);
    }

    public static class Progress {
        public String phase;
        public int    current;
        public int    total;
        public String issueKey;
        public String detail;
    }

    public static class Stats {
        public int    total;
        public int    success;
        public int    failed;
        public boolean cancelled;
        public boolean sessionExpired;
        public boolean abortedByNetwork;
        public String  abortReason;
        public String  failedFile;
        public final List<String> failedKeys   = new ArrayList<>();
        public final Map<String, Integer> byStatus       = new TreeMap<>();
        public final Map<String, Integer> byFixDecision  = new TreeMap<>();
    }

    public static Stats processAll(ServerState state, String project,
                                   String sev, String qual,
                                   OllamaConfig config,
                                   PromptTemplate template,
                                   ProgressCallback onProgress,
                                   BooleanSupplier stopChecker) throws Exception {

        SonarIssuesApi api = state.newApi(project);
        int totalCount = api.count(sev, qual);

        Stats stats = new Stats();
        stats.total = totalCount;
        if (totalCount == 0) {
            Progress p = new Progress();
            p.phase = "complete"; p.total = 0; p.current = 0;
            if (onProgress != null) onProgress.update(p);
            return stats;
        }

        List<String> availableTags = new ArrayList<>();
        try {
            List<String> raw = api.fetchTags(null);
            if (raw != null) availableTags.addAll(raw);
        } catch (Exception e) {
            System.err.println("[auto] tags fetch failed: " + e.getMessage());
        }

        OllamaClient ollama = new OllamaClient(config);
        SonarBulkApi bulk   = state.newBulk();
        bulk.setSilent(true);

        int i = 0;
        int consecutiveNetworkErrors = 0;
        long runT0 = System.currentTimeMillis();

        int page = 1;
        int pageSize = 100;
        boolean hasMore = true;

        while (hasMore) {
            if (stopChecker != null && stopChecker.getAsBoolean()) {
                stats.cancelled = true;
                System.err.println("[auto] остановка запрошена пользователем");
                break;
            }

            List<Issue> pageIssues;
            try {
                pageIssues = api.fetchOpenPage(sev, qual, page, pageSize);
            } catch (Exception e) {
                System.err.println("[auto] Ошибка загрузки страницы " + page + ": " + e.getMessage());
                break;
            }

            if (pageIssues.isEmpty()) break;
            if (pageIssues.size() < pageSize) hasMore = false;

            for (Issue it : pageIssues) {
                if (stopChecker != null && stopChecker.getAsBoolean()) {
                    stats.cancelled = true;
                    System.err.println("[auto] остановка запрошена пользователем");
                    hasMore = false;
                    break;
                }

                i++;
                long issueT0 = System.currentTimeMillis();

                Progress prog = new Progress();
                prog.current  = i;
                prog.total    = totalCount;
                prog.issueKey = it.key;
                prog.phase    = "preparing";
                if (onProgress != null) onProgress.update(prog);

                try {
                    if (it.ruleName == null || it.ruleName.isEmpty()) {
                        try { it.ruleName = api.fetchRuleName(it.rule); }
                        catch (Exception e) { it.ruleName = it.rule; }
                    }

                    List<String> snippet;
                    try { snippet = api.fetchSnippet(it.key); }
                    catch (Exception e) { snippet = new ArrayList<>(); }

                    String prompt;
                    try {
                        Map<String, String> params =
                                PromptBuilder.buildAutoResolveParams(it, snippet, availableTags);
                        prompt = template.render("auto-resolve", params);
                        if (prompt == null || prompt.isBlank()) {
                            System.err.println("[auto] rendered prompt is blank for " + it.key
                                    + ", using fallback");
                            prompt = PromptBuilder.buildAutoResolvePrompt(it, snippet, availableTags);
                        }
                    } catch (Exception e) {
                        System.err.println("[auto] template render failed for " + it.key
                                + ", using fallback: " + e.getMessage());
                        prompt = PromptBuilder.buildAutoResolvePrompt(it, snippet, availableTags);
                    }

                    prog.phase  = "ollama";
                    prog.detail = config.getModel();
                    if (onProgress != null) onProgress.update(prog);

                    String raw = ollama.chat(prompt);

                    OllamaResponse parsed = OllamaResponse.parse(raw);
                    if (parsed.status == null || parsed.status.isEmpty()) {
                        throw new Exception("Не удалось извлечь статус из ответа LLM");
                    }

                    prog.phase  = "applying";
                    prog.detail = parsed.status;
                    if (onProgress != null) onProgress.update(prog);

                    bulk.change(List.of(it), parsed.status, parsed.comment);

                    Set<String> finalTags = new LinkedHashSet<>();
                    if (it.tags != null) finalTags.addAll(it.tags);
                    if (parsed.tags.contains("require-fix"))     finalTags.remove("not-require-fix");
                    if (parsed.tags.contains("not-require-fix")) finalTags.remove("require-fix");
                    finalTags.addAll(parsed.tags);

                    if (!finalTags.isEmpty()) {
                        try {
                            bulk.setTags(List.of(it), String.join(",", finalTags));
                        } catch (Exception e) {
                            System.err.println("[auto] setTags " + it.key + ": " + e.getMessage());
                        }
                    }

                    it.status = parsed.status;
                    it.tags   = new ArrayList<>(finalTags);

                    for (String t : parsed.tags) {
                        if (!availableTags.contains(t)) availableTags.add(t);
                    }

                    stats.success++;
                    stats.byStatus.merge(parsed.status, 1, Integer::sum);
                    stats.byFixDecision.merge(parsed.fixDecision, 1, Integer::sum);

                    long dt = System.currentTimeMillis() - issueT0;
                    prog.phase  = "done";
                    prog.detail = parsed.status + " [" + String.join(",", parsed.tags) + "]"
                            + formatTime(dt);
                    if (onProgress != null) onProgress.update(prog);

                    consecutiveNetworkErrors = 0;

                } catch (SessionExpiredException see) {
                    stats.sessionExpired = true;
                    stats.failed++;
                    stats.failedKeys.add(it.key);

                    prog.phase  = "failed";
                    prog.detail = "SESSION EXPIRED — остановка";
                    if (onProgress != null) onProgress.update(prog);
                    hasMore = false;
                    break;

                } catch (Exception e) {
                    stats.failed++;
                    stats.failedKeys.add(it.key);

                    boolean isNetwork = isNetworkError(e);
                    if (isNetwork) {
                        consecutiveNetworkErrors++;
                    } else {
                        consecutiveNetworkErrors = 0;
                    }

                    long dt = System.currentTimeMillis() - issueT0;
                    prog.phase  = "failed";
                    prog.detail = e.getMessage() + formatTime(dt);
                    if (onProgress != null) onProgress.update(prog);

                    if (isNetwork && consecutiveNetworkErrors >= MAX_CONSECUTIVE_NETWORK_ERRORS) {
                        stats.abortedByNetwork = true;
                        stats.abortReason = consecutiveNetworkErrors
                                + " подряд сетевых ошибок: " + e.getMessage();
                        System.err.println("[auto] ABORT: " + stats.abortReason);
                        hasMore = false;
                        break;
                    }
                }
            }
            page++;
        }

        if (!stats.failedKeys.isEmpty()) {
            try {
                Path out = Path.of("auto-failed-" + sanitize(project) + "-"
                        + System.currentTimeMillis() + ".txt");
                StringBuilder sb = new StringBuilder();
                sb.append("# Auto processing failed keys\n");
                sb.append("# project:    ").append(project).append('\n');
                sb.append("# finished:   ").append(Instant.now()).append('\n');
                sb.append("# total:      ").append(stats.total).append('\n');
                sb.append("# success:    ").append(stats.success).append('\n');
                sb.append("# failed:     ").append(stats.failed).append('\n');
                if (stats.sessionExpired) sb.append("# sessionExpired: true\n");
                if (stats.abortedByNetwork) sb.append("# abortedByNetwork: ").append(stats.abortReason).append('\n');
                sb.append("# ---\n");
                for (String k : stats.failedKeys) sb.append(k).append('\n');
                Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
                stats.failedFile = out.toString();
            } catch (Exception e) {
                System.err.println("[auto] не удалось сохранить failed keys: " + e.getMessage());
            }
        }

        Progress done = new Progress();
        done.phase   = "complete";
        done.total   = totalCount;
        done.current = i;
        if (onProgress != null) onProgress.update(done);

        long totalSec = (System.currentTimeMillis() - runT0) / 1000;
        System.out.printf("[auto] завершено за %dm %ds%n", totalSec / 60, totalSec % 60);

        return stats;
    }

    private static boolean isNetworkError(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();
        String lower = msg.toLowerCase();
        return lower.contains("connection refused")
                || lower.contains("connect timed out")
                || lower.contains("failed to connect")
                || lower.contains("connection reset")
                || lower.contains("no route to host")
                || e instanceof java.net.ConnectException
                || e instanceof java.net.http.HttpTimeoutException;
    }

    private static String formatTime(long ms) {
        if (ms < 1000) return " (" + ms + "ms)";
        return " (" + String.format("%.1f", ms / 1000.0) + "s)";
    }

    private static String sanitize(String s) {
        if (s == null) return "project";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
```

## server/Console.java

```
package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.server.console.AutoCommands;
import ru.miacomsoft.sonar.server.console.ConsoleContext;
import ru.miacomsoft.sonar.server.console.ConsoleHelp;
import ru.miacomsoft.sonar.server.console.MarkCommands;
import ru.miacomsoft.sonar.server.console.OllamaCommands;
import ru.miacomsoft.sonar.server.console.PromptCommands;
import ru.miacomsoft.sonar.server.console.ReportCommands;
import ru.miacomsoft.sonar.server.console.SessionCommands;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Точка входа консольного интерфейса.
 */
public class Console {

    private final ConsoleContext ctx;
    private final Object consoleLock = new Object();
    private final Thread thread;

    private final SessionCommands sessionCmd;
    private final ReportCommands  reportCmd;
    private final PromptCommands  promptCmd;
    private final MarkCommands    markCmd;
    private final OllamaCommands  ollamaCmd;
    private final AutoCommands    autoCmd;

    public Console(ServerState state, OllamaConfig ollamaConfig, Path consoleStateFile) {
        this.ctx = new ConsoleContext(state, ollamaConfig, consoleStateFile);

        this.sessionCmd = new SessionCommands(ctx);
        this.reportCmd  = new ReportCommands(ctx);
        this.promptCmd  = new PromptCommands(ctx);
        this.markCmd    = new MarkCommands(ctx);
        this.ollamaCmd  = new OllamaCommands(ctx);
        this.autoCmd    = new AutoCommands(ctx, consoleLock);

        this.thread = new Thread(this::loop, "console");
        this.thread.setDaemon(true);
    }

    public Console(ServerState state, OllamaConfig ollamaConfig) {
        this(state, ollamaConfig, Path.of("console.properties"));
    }

    public void start() { thread.start(); }
    public void stop()  { ctx.setRunning(false); }

    // ==================== main loop ====================

    private void loop() {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println();
        System.out.println("===================================================");
        System.out.println(" Console ready. Type 'help' for commands.");
        System.out.println(" Prompts dir: " + ctx.template().getPromptsDir());
        System.out.println(" Console state: " + ctx.consoleStateFile());
        System.out.println("===================================================");
        try {
            while (ctx.isRunning()) {
                synchronized (consoleLock) {
                    System.out.print(prompt());
                    System.out.flush();
                }
                String line = r.readLine();
                if (line == null) { ctx.setRunning(false); break; }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    dispatch(line);
                } catch (Exception e) {
                    System.err.println("[console] " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[console] loop terminated: " + e.getMessage());
        }
        System.out.println("[console] stopped");
    }

    private String prompt() {
        String p = ctx.getProject() == null ? "" : ctx.getProject();
        String mode = ctx.isAutoRunning() ? "[auto]" : "";
        return "sonar" + mode + ":" + p + "> ";
    }

    // ==================== dispatch ====================

    private void dispatch(String line) throws Exception {
        String[] tokens = line.split("\\s+");
        String cmd = tokens[0].toLowerCase(Locale.ROOT);
        String[] a = Arrays.copyOfRange(tokens, 1, tokens.length);

        switch (cmd) {
            case "help", "?", "h"           -> ConsoleHelp.print();
            case "quit", "exit", "q"        -> {
                if (ctx.isAutoRunning()) {
                    System.out.println("[auto] сначала выполните 'stop', затем 'quit'.");
                } else {
                    ctx.setRunning(false);
                }
            }
            case "status"                   -> cmdStatus();
            case "health"                   -> cmdHealth();
            case "project"                  -> cmdProject(a);
            case "session"                  -> sessionCmd.handle(a);
            case "refresh"                  -> reportCmd.handleRefresh(a);
            case "summary"                  -> reportCmd.handleSummary(a);
            case "qual-summary"             -> reportCmd.handleQualSummary(a);
            case "matrix"                   -> reportCmd.handleMatrix(a);
            case "perfile"                  -> reportCmd.handlePerFile(a);
            case "details"                  -> reportCmd.handleDetails(a);
            case "issues"                   -> reportCmd.handleIssues(a);
            case "keys"                     -> reportCmd.handleKeys(a);
            case "snippet"                  -> reportCmd.handleSnippet(a);
            case "team-zip"                 -> reportCmd.handleTeamZip(a);
            case "team-html"                -> reportCmd.handleTeamHtml(a);
            case "mark"                     -> markCmd.handleMark(a);
            case "cancel"                   -> markCmd.handleCancel(a);
            case "cancelall", "cancel-all"  -> markCmd.handleCancelAll(a);
            case "ollama"                   -> ollamaCmd.handle(a);
            case "prompt"                   -> promptCmd.handle(a);
            case "auto", "autoall"          -> autoCmd.handle(a);
            case "stop"                     -> autoCmd.stop();
            default -> System.out.println("Unknown command: '" + cmd + "'. Try 'help'");
        }
    }

    // ==================== basic commands ====================

    private void cmdStatus() {
        ServerState state = ctx.state();
        System.out.println("session:");
        System.out.println("  ready    = " + state.isSessionReady());
        System.out.println("  path     = " + state.getSessionFile());
        SessionManager sm = state.getSession();
        if (sm != null) {
            System.out.println("  url      = " + sm.getUrl());
            System.out.println("  user     = " + sm.getUser());
            System.out.println("  authType = " + sm.getAuthType());
            System.out.println("  hasToken = " + sm.hasToken());
            System.out.println("  hasCookie= " + sm.hasCookie());
        }
        System.out.println("snapshot:");
        System.out.println("  status   = " + state.getSnapshotStatus());
        System.out.println("  phase    = " + (state.getSnapshotPhase() == null ? "" : state.getSnapshotPhase())
                + (state.getSnapshotProgressTotal() > 0
                ? " (" + state.getSnapshotProgress() + "/" + state.getSnapshotProgressTotal() + ")"
                : ""));
        if (state.getSnapshotError() != null) {
            System.out.println("  error    = " + state.getSnapshotError());
        }
        Snapshot s = state.getSnapshot();
        if (s != null) {
            System.out.println("  project  = " + s.projectKey);
            System.out.println("  issues   = " + s.issues.size());
            System.out.println("  snippets = " + s.snippets.size());
            System.out.println("  builtAt  = " + s.builtAt);
            System.out.println("  host     = " + s.host);
        }
        System.out.println("defaultProject = "
                + (state.getDefaultProject().isEmpty() ? "<not set>" : state.getDefaultProject()));
        System.out.println("consoleProject = "
                + (ctx.getProject() == null || ctx.getProject().isEmpty()
                ? "<not set>" : ctx.getProject()));
        System.out.println("consoleState   = " + ctx.consoleStateFile());
        System.out.println("autoRunning    = " + ctx.isAutoRunning());
        System.out.println("promptsDir     = " + ctx.template().getPromptsDir());
    }

    private void cmdHealth() {
        ServerState state = ctx.state();
        System.out.println("status        = ok");
        System.out.println("sessionReady  = " + state.isSessionReady());
        System.out.println("snapshotReady = " + state.isSnapshotReady());
        System.out.println("snapshotStatus= " + state.getSnapshotStatus());
    }

    private void cmdProject(String[] a) {
        ServerState state = ctx.state();
        if (a.length == 0) {
            System.out.println("current project = "
                    + (ctx.getProject() == null || ctx.getProject().isEmpty()
                    ? "<not set>" : ctx.getProject()));
            System.out.println("defaultProject  = "
                    + (state.getDefaultProject().isEmpty() ? "<not set>" : state.getDefaultProject()));
            System.out.println("consoleState    = " + ctx.consoleStateFile());
            return;
        }
        if ("clear".equalsIgnoreCase(a[0])) {
            ctx.clearProject();
            System.out.println("[console] project cleared (state saved)");
            return;
        }
        ctx.setProject(a[0]);
        System.out.println("[console] console project = " + a[0] + " (state saved)");
    }
}
```

## server/OllamaConfig.java

```
package ru.miacomsoft.sonar.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Персистентная конфигурация Ollama.
 * Хранится в файле *.properties, переживает перезапуск приложения.
 */
public class OllamaConfig {

    private static final String DEFAULT_URL   = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "llama3";
    private static final int    DEFAULT_TIMEOUT_SEC = 300;
    private static final double DEFAULT_TEMPERATURE = 0.2;

    private final Path configFile;

    private String  url;
    private String  model;
    private int     timeoutSeconds;
    private double  temperature;

    public OllamaConfig(Path configFile) {
        this.configFile = configFile;
        load();
    }

    private void load() {
        Properties p = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("[OllamaConfig] load failed: " + e.getMessage());
            }
        }
        this.url            = p.getProperty("url",   DEFAULT_URL);
        this.model          = p.getProperty("model", DEFAULT_MODEL);
        this.timeoutSeconds = parseInt(p.getProperty("timeout"), DEFAULT_TIMEOUT_SEC);
        this.temperature    = parseDouble(p.getProperty("temperature"), DEFAULT_TEMPERATURE);
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("url",         url);
        p.setProperty("model",       model);
        p.setProperty("timeout",     String.valueOf(timeoutSeconds));
        p.setProperty("temperature", String.valueOf(temperature));
        try {
            Path parent = configFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(configFile)) {
                p.store(out, "Ollama configuration");
            }
            System.out.println("[OllamaConfig] saved: " + configFile);
        } catch (IOException e) {
            System.err.println("[OllamaConfig] save failed: " + e.getMessage());
        }
    }

    public boolean isConfigured() {
        return url != null && !url.isBlank() && model != null && !model.isBlank();
    }

    public Path getConfigFile() { return configFile; }
    public String  getUrl()          { return url; }
    public String  getModel()        { return model; }
    public int     getTimeoutSeconds() { return timeoutSeconds; }
    public double  getTemperature()  { return temperature; }

    public void setUrl(String url)           { this.url = url; }
    public void setModel(String model)       { this.model = model; }
    public void setTimeoutSeconds(int s)     { this.timeoutSeconds = s; }
    public void setTemperature(double t)     { this.temperature = t; }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }
}
```

## server/console/MarkCommands.java

```
package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Команды пометок: mark (одиночный/массовый), cancel, cancelall.
 * Плюс общий хелпер {@link #autoRefreshIssue(String)} — точечное обновление
 * одного issue в снимке после изменения статуса.
 */
public class MarkCommands {

    private static final Set<String> KNOWN_TRANSITIONS = Set.of(
            "accept", "falsepositive", "confirm", "resolve", "wontfix", "reopen");

    private final ConsoleContext ctx;

    public MarkCommands(ConsoleContext ctx) {
        this.ctx = ctx;
    }

    public void handleMark(String[] a) throws Exception {
        if (a.length < 1) { printMarkHelp(); return; }
        if (KNOWN_TRANSITIONS.contains(a[0].toLowerCase(Locale.ROOT))) {
            markBulk(a);
        } else {
            markOne(a);
        }
    }

    public void handleCancel(String[] a) throws Exception {
        if (a.length < 1) { System.out.println("usage: cancel <KEY>"); return; }
        if (!ctx.requireProject()) return;

        String key = a[0];
        System.out.println("[cancel] " + key + " -> reopen ...");

        Issue stub = new Issue();
        stub.key = key;
        try {
            Set<String> failed = ctx.state().newBulk().reopenIssues(List.of(stub));
            if (failed.contains(key)) {
                System.err.println("[cancel] FAIL: reopen failed");
                return;
            }
            System.out.println("[cancel] OK");
            autoRefreshIssue(key);
        } catch (Exception e) {
            System.err.println("[cancel] FAIL: " + e.getMessage());
        }
    }

    public void handleCancelAll(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        ServerState state = ctx.state();

        boolean confirm = false;
        List<String> rest = new ArrayList<>();
        for (String t : a) {
            if ("--yes".equals(t)) confirm = true;
            else rest.add(t);
        }
        String[] sq = new String[]{"MEDIUM", "MAINTAINABILITY"};
        if (rest.size() > 0) sq[0] = rest.get(0).toUpperCase(Locale.ROOT);
        if (rest.size() > 1) sq[1] = rest.get(1).toUpperCase(Locale.ROOT);

        SonarIssuesApi api = state.newApi(project);
        List<Issue> issues = api.fetchAll(sq[0], sq[1]);

        System.out.printf("%nproject  = %s%n", project);
        System.out.printf("severity = %s%n", sq[0]);
        System.out.printf("quality  = %s%n", sq[1]);
        System.out.printf("issues   = %d%n", issues.size());

        if (!confirm) {
            System.out.println("\nDRY RUN — ничего не изменено.");
            System.out.println("Чтобы применить: cancelall " + sq[0] + " " + sq[1] + " --yes");
            return;
        }

        System.out.println("\nОткрываю issues обратно (reopen)...");
        Set<String> failed = state.newBulk().reopenIssues(issues);
        System.out.printf("[console] reopened %d / %d (failed: %d)%n",
                issues.size() - failed.size(), issues.size(), failed.size());
        if (!failed.isEmpty()) {
            System.err.println("failed keys: " + String.join(", ", failed));
        }

        if (state.isSnapshotReady()
                && state.getSnapshot().projectKey.equals(project)
                && !state.isSnapshotBuilding()) {
            state.buildSnapshotAsync(project);
            System.out.println("[console] snapshot rebuild started in background");
        }
    }

    // ==================== internals ====================

    private void printMarkHelp() {
        System.out.println("usage:");
        System.out.println("  mark <KEY> <transition> [comment...]");
        System.out.println("  mark <transition> [SEV] [QUAL] [--yes]");
        System.out.println("  transition = accept | falsepositive | confirm | resolve | wontfix | reopen");
    }

    private void markOne(String[] a) throws Exception {
        if (a.length < 2) { printMarkHelp(); return; }
        if (!ctx.requireProject()) return;

        String key        = a[0];
        String transition = a[1];
        String comment    = a.length > 2
                ? String.join(" ", Arrays.copyOfRange(a, 2, a.length))
                : "";

        System.out.println("[mark] " + key + " -> " + transition
                + (comment.isEmpty() ? "" : " (comment: " + comment + ")") + " ...");

        SonarBulkApi bulk = ctx.state().newBulk();
        try {
            Issue stub = new Issue();
            stub.key = key;

            if ("reopen".equalsIgnoreCase(transition)) {
                Set<String> failed = bulk.reopenIssues(List.of(stub));
                if (failed.contains(key)) {
                    System.err.println("[mark] FAIL: reopen failed");
                    return;
                }
            } else {
                bulk.change(List.of(stub), transition, comment);
            }
            System.out.println("[mark] OK");
            autoRefreshIssue(key);
        } catch (Exception e) {
            System.err.println("[mark] FAIL: " + e.getMessage());
        }
    }

    private void markBulk(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        ServerState state = ctx.state();

        boolean confirm = false;
        List<String> rest = new ArrayList<>();
        for (String t : a) {
            if ("--yes".equals(t)) confirm = true;
            else rest.add(t);
        }
        String transition = rest.get(0);
        String[] sq = new String[]{"MEDIUM", "MAINTAINABILITY"};
        if (rest.size() > 1) sq[0] = rest.get(1).toUpperCase(Locale.ROOT);
        if (rest.size() > 2) sq[1] = rest.get(2).toUpperCase(Locale.ROOT);

        SonarIssuesApi api = state.newApi(project);
        List<Issue> issues = api.fetchAll(sq[0], sq[1]);
        SonarBulkApi bulk = state.newBulk();

        System.out.printf("%nproject    = %s%n", project);
        System.out.printf("severity   = %s%n", sq[0]);
        System.out.printf("quality    = %s%n", sq[1]);
        System.out.printf("transition = %s%n", transition);
        System.out.printf("issues     = %d%n", issues.size());
        System.out.printf("batches    = %d (размер %d)%n",
                bulk.batchCount(issues.size()), state.getBatchSize());

        if (!confirm) {
            System.out.println("\nDRY RUN — ничего не изменено.");
            System.out.println("Чтобы применить: mark " + transition + " "
                    + sq[0] + " " + sq[1] + " --yes");
            return;
        }

        System.out.println("\nПрименяю переход...");
        if ("reopen".equalsIgnoreCase(transition)) {
            Set<String> failed = bulk.reopenIssues(issues);
            System.out.printf("[console] reopened %d / %d (failed: %d)%n",
                    issues.size() - failed.size(), issues.size(), failed.size());
            if (!failed.isEmpty()) {
                System.err.println("failed keys: " + String.join(", ", failed));
            }
        } else {
            List<String> responses = bulk.change(issues, transition, "");
            System.out.printf("[console] applied to %d issues in %d batches%n",
                    issues.size(), responses.size());
        }

        if (state.isSnapshotReady()
                && state.getSnapshot().projectKey.equals(project)
                && !state.isSnapshotBuilding()) {
            state.buildSnapshotAsync(project);
            System.out.println("[console] snapshot rebuild started in background "
                    + "('status' to monitor)");
        }
    }

    private void autoRefreshIssue(String key) {
        ServerState state = ctx.state();
        String project = ctx.getProject();
        if (state.isSnapshotReady()
                && state.getSnapshot().projectKey.equals(project)
                && !state.isSnapshotBuilding()) {
            try {
                state.refreshIssue(project, key);
            } catch (Exception e) {
                System.err.println("[refresh] auto-refresh failed: " + e.getMessage());
            }
        }
    }
}
```

## server/exporters/StatsHtmlExporter.java

```
package ru.miacomsoft.sonar.server.exporters;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Генерирует HTML-отчёт статистики по проектам.
 * Формат: карточки-категории qual → sev → rule → tag, внутри каждой — карточки значений.
 */
public final class StatsHtmlExporter {

    private StatsHtmlExporter() {}

    public static String export(JSONObject data) {
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());

        JSONObject projects = data.optJSONObject("projects");
        JSONObject totals   = data.optJSONObject("totals");
        if (projects == null) projects = new JSONObject();
        if (totals == null)   totals = new JSONObject();

        int requested = data.optInt("projectsRequested", 0);
        int ok        = data.optInt("projectsOk", 0);
        int failed    = requested - ok;

        String sonarBaseUrl = data.optString("sonarBaseUrl", "");
        String branch       = data.optString("branch", "");
        String statusesRaw  = data.optString("statuses", "");
        String statusesUrl  = sanitizeStatusesForUrl(statusesRaw);

        List<String> keys = new ArrayList<>(projects.keySet());
        Collections.sort(keys);

        StringBuilder html = new StringBuilder(16384);

        // ---------- HEAD ----------
        html.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Статистика по проектам — ").append(esc(now)).append("</title>\n");
        html.append("<style>\n").append(STYLES).append("</style>\n</head>\n<body>\n");

        // ---------- HEADER ----------
        html.append("<h1>📊 Статистика по проектам</h1>\n");
        html.append("<div class=\"meta\">Сгенерировано: ").append(esc(now)).append("</div>\n");

        // ---------- FILTERS BOX ----------
        html.append("<div class=\"filters-box\">\n");
        html.append("<div class=\"row\"><span class=\"label\">Проектов запрошено:</span><b>")
                .append(requested).append("</b></div>\n");
        html.append("<div class=\"row\"><span class=\"label\">Успешно:</span><b>")
                .append(ok).append("</b>");
        if (failed > 0) {
            html.append(" · <span style=\"color:#de350b;\">Ошибок: ").append(failed).append("</span>");
        }
        html.append("</div>\n");

        String incl = tagsToString(data.optJSONArray("includedTags"));
        if (!incl.isEmpty()) {
            html.append("<div class=\"row\"><span class=\"label\">Включённые теги:</span><b>")
                    .append(esc(incl)).append("</b></div>\n");
        }
        String excl = tagsToString(data.optJSONArray("excludedTags"));
        if (!excl.isEmpty()) {
            html.append("<div class=\"row\"><span class=\"label\">Исключённые теги:</span><b>")
                    .append(esc(excl)).append("</b></div>\n");
        }
        html.append("<div class=\"row\"><span class=\"label\">Статусы:</span><b>")
                .append(statusesRaw.isEmpty() ? "все" : esc(statusesRaw)).append("</b></div>\n");
        html.append("</div>\n");

        // ---------- TOTALS ----------
        if (totals.has("totalIssues")) {
            html.append("<div class=\"summary-block\">\n");
            html.append("<h2>📈 Суммарно по всем проектам</h2>\n");
            html.append("<div class=\"totals-row\">\n");
            html.append("<div class=\"total-badge\"><span class=\"total-label\">Всего issues</span>")
                    .append("<span class=\"total-value\">").append(totals.optInt("totalIssues", 0))
                    .append("</span></div>\n");
            html.append("<div class=\"total-badge\"><span class=\"total-label\">Всего файлов</span>")
                    .append("<span class=\"total-value\">").append(totals.optInt("totalFiles", 0))
                    .append("</span></div>\n");
            html.append("</div>\n");
            html.append(renderUnifiedBreakdown(totals, "", "", statusesUrl, ""));
            html.append("</div>\n");
        }

        // ---------- PROJECTS ----------
        html.append("<h2 style=\"color:#172b4d;border-bottom:2px solid #0052cc;")
                .append("margin-top:32px;font-size:20px;\">")
                .append("Проекты (").append(keys.size()).append(")</h2>\n");

        if (keys.isEmpty()) {
            html.append("<div style=\"text-align:center;color:#6b778c;padding:40px;")
                    .append("font-style:italic;\">Нет данных по проектам</div>\n");
        } else {
            for (String key : keys) {
                JSONObject p = projects.optJSONObject(key);
                if (p == null) p = new JSONObject();

                String dashboardUrl = null;
                if (sonarBaseUrl != null && !sonarBaseUrl.isBlank()) {
                    String base = sonarBaseUrl.replaceAll("/+$", "");
                    dashboardUrl = base + "/dashboard?id=" + urlEnc(key)
                            + "&codeScope=overall";
                }

                html.append("<div class=\"project-block\">\n");
                html.append("<div class=\"project-header\"><span class=\"project-icon\">📁</span>");
                if (dashboardUrl != null) {
                    html.append("<h2><a href=\"").append(esc(dashboardUrl))
                            .append("\" target=\"_blank\" rel=\"noopener noreferrer\" ")
                            .append("title=\"Открыть dashboard проекта в SonarQube\">")
                            .append(esc(key)).append("</a></h2>");
                } else {
                    html.append("<h2>").append(esc(key)).append("</h2>");
                }
                html.append("</div>\n");

                if (p.has("error")) {
                    html.append("<div style=\"color:#de350b;padding:12px;background:#ffebe6;")
                            .append("border-radius:6px;\">❌ Ошибка: ")
                            .append(esc(p.optString("error", ""))).append("</div>\n");
                } else {
                    html.append("<div class=\"totals-row\">\n");
                    html.append("<div class=\"total-badge\"><span class=\"total-label\">Всего issues</span>")
                            .append("<span class=\"total-value\">").append(p.optInt("totalIssues", 0))
                            .append("</span></div>\n");
                    html.append("<div class=\"total-badge\"><span class=\"total-label\">Всего файлов</span>")
                            .append("<span class=\"total-value\">").append(p.optInt("totalFiles", 0))
                            .append("</span></div>\n");
                    html.append("</div>\n");
                    html.append(renderUnifiedBreakdown(p, sonarBaseUrl, key, statusesUrl, branch));
                }

                html.append("</div>\n");
            }
        }

        // ---------- FOOTER ----------
        html.append("<div class=\"footer\">SonarQube Stats Report · ")
                .append(esc(now)).append("</div>\n");

        html.append(MULTI_SELECT_SCRIPT);

        html.append("</body>\n</html>\n");

        return html.toString();
    }

    // ============================================================
    // Рендер секций-карточек
    // ============================================================

    private static String renderUnifiedBreakdown(JSONObject p,
                                                 String sonarBaseUrl,
                                                 String projectKey,
                                                 String statuses,
                                                 String branch) {
        JSONObject bySev  = p.optJSONObject("bySeverity");
        JSONObject byQual = p.optJSONObject("byQuality");
        JSONObject byRule = p.optJSONObject("byRule");
        JSONObject byTag  = p.optJSONObject("byTag");

        if (isEmpty(byQual) && isEmpty(bySev) && isEmpty(byRule) && isEmpty(byTag)) {
            return "<div class=\"no-data\">— нет данных —</div>\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"breakdown-cards\">\n");
        sb.append(renderCategoryCard("qual", "Качество (qual)", byQual, "quality",
                sonarBaseUrl, projectKey, statuses, branch));
        sb.append(renderCategoryCard("sev",  "Severity (sev)",  bySev,  "severity",
                sonarBaseUrl, projectKey, statuses, branch));
        sb.append(renderCategoryCard("rule", "Правила (rule)",  byRule, "rule",
                sonarBaseUrl, projectKey, statuses, branch));
        sb.append(renderCategoryCard("tag",  "Теги (tag)",      byTag,  "tag",
                sonarBaseUrl, projectKey, statuses, branch));
        sb.append("</div>\n");
        return sb.toString();
    }

    private static String renderCategoryCard(String prefix,
                                             String label,
                                             JSONObject values,
                                             String cssClass,
                                             String sonarBaseUrl,
                                             String projectKey,
                                             String statuses,
                                             String branch) {
        if (isEmpty(values)) return "";

        List<Row> rows = new ArrayList<>();
        for (String key : values.keySet()) {
            int count = values.optInt(key, 0);
            if (count <= 0) continue;
            rows.add(new Row(key, count));
        }
        rows.sort((a, b) -> {
            if (b.count != a.count) return b.count - a.count;
            return a.param.compareTo(b.param);
        });
        if (rows.isEmpty()) return "";

        int totalInCategory = 0;
        for (Row r : rows) totalInCategory += r.count;

        // Теперь и "tag", и "rule" являются мультивыборными
        boolean isSelectable = "tag".equals(prefix) || "rule".equals(prefix);
        boolean canBuildUrls = sonarBaseUrl != null && !sonarBaseUrl.isBlank()
                && projectKey != null && !projectKey.isBlank();

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"breakdown-category breakdown-category-")
                .append(cssClass).append("\">\n");

        // ---------- header ----------
        sb.append("<div class=\"breakdown-category-header\">")
                .append("<div style=\"display:flex; align-items:center; gap:8px; flex-wrap:wrap;\">")
                .append("<span class=\"category-label\">").append(esc(label)).append("</span>");

        if (isSelectable && canBuildUrls) {
            String base = sonarBaseUrl.replaceAll("/+$", "");
            String filterType = "tag".equals(prefix) ? "tags" : "rules";

            sb.append("<button class=\"multi-sel-open\" type=\"button\" ")
                    .append("style=\"display:none;\" ")
                    .append("data-base=\"").append(esc(base)).append("\" ")
                    .append("data-project=\"").append(esc(projectKey)).append("\" ")
                    .append("data-filter-type=\"").append(filterType).append("\" ")
                    .append("data-branch=\"").append(esc(branch == null ? "" : branch)).append("\" ")
                    .append("title=\"Открыть SonarQube с выбранными параметрами\">")
                    .append("📂 <span class=\"multi-sel-label\">")
                    .append(esc(projectKey)).append(": —</span>")
                    .append("</button>");
        }

        sb.append("</div>")
                .append("<span class=\"category-total\">").append(totalInCategory).append("</span>")
                .append("</div>\n");

        // ---------- values ----------
        sb.append("<div class=\"breakdown-values\">\n");
        for (Row r : rows) {
            String url = buildCardUrl(prefix, r.param, sonarBaseUrl, projectKey, statuses, branch);

            if (isSelectable && url != null) {
                // Мультивыборный элемент (тег или правило)
                sb.append("<div class=\"breakdown-card breakdown-card-select\">")
                        .append("<label class=\"multi-sel-cb-label\" ")
                        .append("title=\"Добавить в мультивыбор\">")
                        .append("<input type=\"checkbox\" class=\"multi-sel-cb\" ")
                        .append("value=\"").append(esc(r.param)).append("\">")
                        .append("</label>")
                        .append("<a href=\"").append(esc(url)).append("\" ")
                        .append("class=\"breakdown-card-link breakdown-card-select-link\" ")
                        .append("target=\"_blank\" rel=\"noopener noreferrer\" ")
                        .append("title=\"Открыть SonarQube с одним значением\">")
                        .append("<div class=\"breakdown-card-title\">")
                        .append("<span class=\"param-prefix\">").append(esc(prefix)).append(":</span> ")
                        .append("<span class=\"param-value\">").append(esc(r.param)).append("</span>")
                        .append("</div>")
                        .append("<div class=\"breakdown-card-count\">").append(r.count).append("</div>")
                        .append("</a>")
                        .append("</div>\n");
            } else if (url != null) {
                sb.append("<a href=\"").append(esc(url)).append("\" ")
                        .append("class=\"breakdown-card breakdown-card-link\" ")
                        .append("target=\"_blank\" rel=\"noopener noreferrer\" ")
                        .append("title=\"Открыть в SonarQube\">")
                        .append("<div class=\"breakdown-card-title\">")
                        .append("<span class=\"param-prefix\">").append(esc(prefix)).append(":</span> ")
                        .append("<span class=\"param-value\">").append(esc(r.param)).append("</span>")
                        .append("</div>")
                        .append("<div class=\"breakdown-card-count\">").append(r.count).append("</div>")
                        .append("</a>\n");
            } else {
                sb.append("<div class=\"breakdown-card\">")
                        .append("<div class=\"breakdown-card-title\">")
                        .append("<span class=\"param-prefix\">").append(esc(prefix)).append(":</span> ")
                        .append("<span class=\"param-value\">").append(esc(r.param)).append("</span>")
                        .append("</div>")
                        .append("<div class=\"breakdown-card-count\">").append(r.count).append("</div>")
                        .append("</div>\n");
            }
        }
        sb.append("</div>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    // ============================================================
    // URL → SonarQube (одиночная карточка)
    // ============================================================

    private static String buildCardUrl(String prefix, String rawValue,
                                       String sonarBaseUrl, String projectKey,
                                       String statuses, String branch) {
        if (sonarBaseUrl == null || sonarBaseUrl.isBlank()) return null;
        if (projectKey == null || projectKey.isBlank()) return null;
        if (rawValue == null || rawValue.isEmpty()) return null;
        if ("UNKNOWN".equalsIgnoreCase(rawValue)) return null;

        String base = sonarBaseUrl.replaceAll("/+$", "");

        List<String> params = new ArrayList<>();

        if (statuses != null && !statuses.isEmpty()) {
            params.add("issueStatuses=" + urlEnc(statuses));
        }

        switch (prefix) {
            case "qual":
                params.add("impactSoftwareQualities=" + urlEnc(rawValue));
                break;
            case "sev":
                params.add("impactSeverities=" + urlEnc(rawValue));
                break;
            case "rule":
                params.add("rules=" + urlEnc(rawValue));
                break;
            case "tag":
                params.add("tags=" + urlEnc(rawValue));
                break;
            default:
                return null;
        }

        if (branch != null && !branch.isEmpty()) {
            params.add("branch=" + urlEnc(branch));
        }

        params.add("id=" + urlEnc(projectKey));

        return base + "/project/issues?" + String.join("&", params);
    }

    private static String sanitizeStatusesForUrl(String statuses) {
        if (statuses == null || statuses.isEmpty()) return "";
        Set<String> valid = new TreeSet<>();
        for (String s : statuses.split(",")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            switch (t) {
                case "REOPENED": valid.add("OPEN");            break;
                case "CLOSED":   valid.add("FIXED");           break;
                case "WONTFIX":  valid.add("FALSE_POSITIVE");  break;
                case "RESOLVED": valid.add("FIXED");           break;
                default:
                    if (Set.of("OPEN", "CONFIRMED", "FALSE_POSITIVE",
                            "ACCEPTED", "FIXED").contains(t)) {
                        valid.add(t);
                    }
            }
        }
        return String.join(",", valid);
    }

    private static String urlEnc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean isEmpty(JSONObject o) {
        return o == null || o.isEmpty();
    }

    // ============================================================
    // Прочее
    // ============================================================

    private static String tagsToString(org.json.JSONArray arr) {
        if (arr == null || arr.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(arr.optString(i, ""));
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static class Row {
        final String param;
        final int count;
        Row(String param, int count) { this.param = param; this.count = count; }
    }

    // ============================================================
    // JavaScript для мультивыбора
    // ============================================================

    private static final String MULTI_SELECT_SCRIPT =
            "<script>\n" +
                    "(function() {\n" +
                    "  function buildUrl(btn, values) {\n" +
                    "    var base = btn.dataset.base || '';\n" +
                    "    var proj = btn.dataset.project || '';\n" +
                    "    var fType = btn.dataset.filterType || 'tags';\n" +
                    "    var br   = btn.dataset.branch || '';\n" +
                    "    var params = [];\n" +
                    "    params.push(fType + '=' + encodeURIComponent(values.join(',')));\n" +
                    "    if (br) params.push('branch=' + encodeURIComponent(br));\n" +
                    "    params.push('id=' + encodeURIComponent(proj));\n" +
                    "    return base + '/project/issues?' + params.join('&');\n" +
                    "  }\n" +
                    "\n" +
                    "  function updateBtn(cb) {\n" +
                    "    var cat = cb.closest('.breakdown-category');\n" +
                    "    if (!cat) return;\n" +
                    "    var checked = cat.querySelectorAll('.multi-sel-cb:checked');\n" +
                    "    var btn = cat.querySelector('.multi-sel-open');\n" +
                    "    if (!btn) return;\n" +
                    "    var proj = btn.dataset.project || '';\n" +
                    "    var vals = Array.prototype.slice.call(checked)\n" +
                    "      .map(function(c){ return c.value; }).sort();\n" +
                    "\n" +
                    "    if (vals.length > 0) {\n" +
                    "      var label = proj + ': ' + vals.join(', ');\n" +
                    "      btn.querySelector('.multi-sel-label').textContent = label;\n" +
                    "      btn.title = 'Открыть: ' + buildUrl(btn, vals);\n" +
                    "      btn.style.display = 'inline-flex';\n" +
                    "    } else {\n" +
                    "      btn.querySelector('.multi-sel-label').textContent = proj + ': —';\n" +
                    "      btn.title = 'Открыть SonarQube с выбранными параметрами';\n" +
                    "      btn.style.display = 'none';\n" +
                    "    }\n" +
                    "  }\n" +
                    "\n" +
                    "  // 1) Основной путь: <label> переключает <input> нативно\n" +
                    "  document.querySelectorAll('.multi-sel-cb').forEach(function(cb) {\n" +
                    "    cb.addEventListener('change', function() { updateBtn(cb); });\n" +
                    "  });\n" +
                    "\n" +
                    "  // 2) Клик по карточке\n" +
                    "  document.querySelectorAll('.breakdown-card-select-link').forEach(function(link) {\n" +
                    "    link.addEventListener('click', function(e) {\n" +
                    "      var card = link.closest('.breakdown-card-select');\n" +
                    "      if (!card) return;\n" +
                    "      var cat = link.closest('.breakdown-category');\n" +
                    "      if (!cat) return;\n" +
                    "      var cb = card.querySelector('.multi-sel-cb');\n" +
                    "      if (!cb) return;\n" +
                    "\n" +
                    "      var checked = cat.querySelectorAll('.multi-sel-cb:checked');\n" +
                    "\n" +
                    "      // 2a) hit-area чекбокса\n" +
                    "      var cbRect = cb.getBoundingClientRect();\n" +
                    "      var pad = 15;\n" +
                    "      if (e.clientX >= cbRect.left - pad && e.clientX <= cbRect.right + pad &&\n" +
                    "          e.clientY >= cbRect.top - pad && e.clientY <= cbRect.bottom + pad) {\n" +
                    "        e.preventDefault();\n" +
                    "        e.stopPropagation();\n" +
                    "        cb.checked = !cb.checked;\n" +
                    "        updateBtn(cb);\n" +
                    "        return;\n" +
                    "      }\n" +
                    "\n" +
                    "      // 2b) Если есть отмеченные — мультивыборный URL (без issueStatuses)\n" +
                    "      if (checked.length > 0) {\n" +
                    "        e.preventDefault();\n" +
                    "        e.stopPropagation();\n" +
                    "\n" +
                    "        if (!cb.checked) {\n" +
                    "          cb.checked = true;\n" +
                    "          updateBtn(cb);\n" +
                    "        }\n" +
                    "\n" +
                    "        var vals = Array.prototype.slice.call(cat.querySelectorAll('.multi-sel-cb:checked'))\n" +
                    "          .map(function(c){ return c.value; }).sort();\n" +
                    "        var btn = cat.querySelector('.multi-sel-open');\n" +
                    "        window.open(buildUrl(btn, vals), '_blank');\n" +
                    "      }\n" +
                    "    }, true);\n" +
                    "  });\n" +
                    "\n" +
                    "  // 3) Синяя кнопка «<projectKey>: tag1, tag2»\n" +
                    "  document.querySelectorAll('.multi-sel-open').forEach(function(btn) {\n" +
                    "    btn.addEventListener('click', function(e) {\n" +
                    "      e.preventDefault();\n" +
                    "      var cat = btn.closest('.breakdown-category');\n" +
                    "      if (!cat) return;\n" +
                    "      var checked = cat.querySelectorAll('.multi-sel-cb:checked');\n" +
                    "      if (!checked.length) return;\n" +
                    "      var vals = Array.prototype.slice.call(checked)\n" +
                    "        .map(function(c){ return c.value; }).sort();\n" +
                    "      window.open(buildUrl(btn, vals), '_blank');\n" +
                    "    });\n" +
                    "  });\n" +
                    "})();\n" +
                    "</script>\n";

    // ============================================================
    // CSS
    // ============================================================

    private static final String STYLES =
            "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;" +
                    "line-height:1.5;color:#172b4d;max-width:1200px;margin:0 auto;padding:24px 20px 40px;background:#fafbfc;}\n" +
                    "h1{color:#0052cc;margin:0 0 4px;font-size:26px;}\n" +
                    "h2{color:#172b4d;margin:0 0 16px;font-size:18px;border-bottom:2px solid #0052cc;padding-bottom:8px;}\n" +
                    ".meta{color:#6b778c;font-size:13px;margin-bottom:24px;}\n" +
                    ".filters-box{background:#fff;border:1px solid #dfe1e6;border-radius:8px;padding:14px 18px;margin-bottom:24px;font-size:13px;line-height:1.8;}\n" +
                    ".filters-box .row{display:flex;gap:8px;}\n" +
                    ".filters-box .label{color:#6b778c;min-width:140px;}\n" +
                    ".filters-box b{color:#172b4d;}\n" +
                    ".summary-block{background:linear-gradient(135deg,#0052cc 0%,#0747a6 100%);color:#fff;border-radius:10px;padding:22px 26px;margin-bottom:32px;box-shadow:0 4px 14px rgba(0,82,204,0.2);}\n" +
                    ".summary-block h2{color:#fff;border-bottom-color:rgba(255,255,255,0.35);}\n" +
                    ".summary-block .total-badge{background:rgba(255,255,255,0.15);border-color:rgba(255,255,255,0.35);}\n" +
                    ".summary-block .total-label{color:rgba(255,255,255,0.85);}\n" +
                    ".summary-block .total-value{color:#fff;}\n" +
                    ".project-block{background:#fff;border:1px solid #dfe1e6;border-radius:10px;padding:22px 26px;margin-bottom:24px;box-shadow:0 1px 3px rgba(0,0,0,0.06);}\n" +
                    ".project-header{display:flex;align-items:center;gap:8px;margin-bottom:18px;padding-bottom:4px;}\n" +
                    ".project-header h2{margin:0;border:none;padding:0;font-size:17px;font-family:monospace;word-break:break-all;}\n" +
                    ".project-header h2 a{color:inherit;text-decoration:none;}\n" +
                    ".project-header h2 a:hover{color:#0052cc;text-decoration:underline;}\n" +
                    ".project-icon{font-size:20px;}\n" +
                    ".totals-row{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:20px;}\n" +
                    ".total-badge{display:flex;flex-direction:column;align-items:center;background:#f4f5f7;border:1px solid #dfe1e6;border-radius:8px;padding:12px 24px;min-width:130px;}\n" +
                    ".total-label{font-size:10.5px;color:#6b778c;text-transform:uppercase;letter-spacing:0.6px;font-weight:600;}\n" +
                    ".total-value{font-size:26px;font-weight:700;color:#0052cc;margin-top:2px;font-variant-numeric:tabular-nums;}\n" +
                    ".breakdown-cards{display:flex;flex-direction:column;gap:16px;}\n" +
                    ".breakdown-category{background:#f9fafb;border:1px solid #dfe1e6;border-radius:10px;padding:14px 16px 16px;}\n" +
                    ".breakdown-category-header{display:flex;justify-content:space-between;align-items:center;font-size:12px;text-transform:uppercase;letter-spacing:0.6px;font-weight:700;color:#6b778c;padding-bottom:10px;margin-bottom:12px;border-bottom:1px dashed #dfe1e6;}\n" +
                    ".breakdown-category-header .category-label{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;}\n" +
                    ".breakdown-category-header .category-total{font-family:monospace;font-size:13px;font-weight:700;color:#0052cc;background:#fff;border:1px solid #dfe1e6;border-radius:6px;padding:2px 10px;font-variant-numeric:tabular-nums;}\n" +
                    ".breakdown-values{display:flex;flex-wrap:wrap;gap:10px;}\n" +
                    ".breakdown-card{display:flex;flex-direction:column;gap:4px;background:#fff;border:1px solid #dfe1e6;border-radius:8px;padding:10px 14px;min-width:140px;flex:0 0 auto;transition:box-shadow .15s,border-color .15s;}\n" +
                    ".breakdown-card:hover{border-color:#b3bac5;box-shadow:0 2px 6px rgba(0,0,0,0.06);}\n" +
                    ".breakdown-card-title{font-family:monospace;font-size:12px;line-height:1.3;}\n" +
                    ".breakdown-card-title .param-prefix{color:#6b778c;font-weight:600;}\n" +
                    ".breakdown-card-title .param-value{color:#172b4d;}\n" +
                    ".breakdown-card-count{font-size:20px;font-weight:700;color:#0052cc;font-variant-numeric:tabular-nums;}\n" +
                    ".no-data{text-align:center;color:#6b778c;font-style:italic;padding:20px;font-size:13px;}\n" +
                    ".footer{text-align:center;color:#6b778c;font-size:12px;margin-top:40px;padding-top:20px;border-top:1px solid #dfe1e6;}\n" +
                    "a.breakdown-card{text-decoration:none;color:inherit;cursor:pointer;}\n" +
                    "a.breakdown-card:visited{color:inherit;}\n" +
                    "a.breakdown-card:hover{border-color:#0052cc;box-shadow:0 3px 10px rgba(0,82,204,0.18);text-decoration:none;}\n" +
                    "a.breakdown-card:focus-visible{outline:2px solid #0052cc;outline-offset:2px;}\n" +
                    "a.breakdown-card .breakdown-card-title::after{content:' ↗';font-size:10px;color:#6b778c;margin-left:2px;}\n" +

                    ".breakdown-card-select{padding:0;position:relative;min-height:0;overflow:visible;}\n" +
                    ".multi-sel-cb-label{position:absolute;left:0;top:0;bottom:0;width:34px;" +
                    "display:flex;align-items:center;justify-content:center;" +
                    "z-index:10;cursor:pointer;pointer-events:auto;}\n" +
                    ".multi-sel-cb{width:16px;height:16px;margin:0;cursor:pointer;accent-color:#0052cc;pointer-events:auto;}\n" +
                    ".breakdown-card-select .breakdown-card-select-link{display:flex;flex-direction:column;gap:4px;" +
                    "padding:10px 14px 10px 34px;text-decoration:none;color:inherit;" +
                    "width:100%;box-sizing:border-box;}\n" +
                    ".breakdown-card-select:hover{border-color:#0052cc;box-shadow:0 3px 10px rgba(0,82,204,0.18);}\n" +
                    ".breakdown-card-select .breakdown-card-select-link:focus-visible{outline:2px solid #0052cc;outline-offset:-2px;border-radius:6px;}\n" +
                    ".breakdown-card-select .breakdown-card-select-link:hover .breakdown-card-title::after{content:' ↗';font-size:10px;color:#6b778c;margin-left:2px;}\n" +

                    ".multi-sel-open{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;" +
                    "background:#0052cc;color:#fff;border:none;border-radius:4px;" +
                    "font-size:11px;font-weight:600;cursor:pointer;" +
                    "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;" +
                    "text-transform:none;letter-spacing:0;transition:background .15s;" +
                    "max-width:560px;}\n" +
                    ".multi-sel-open:hover{background:#0747a6;}\n" +
                    ".multi-sel-open:focus-visible{outline:2px solid #0052cc;outline-offset:2px;}\n" +
                    ".multi-sel-open .multi-sel-label{font-family:monospace;font-weight:700;" +
                    "overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" +
                    "max-width:520px;display:inline-block;vertical-align:middle;}\n" +

                    ".summary-block .breakdown-category{background:rgba(255,255,255,0.08);border-color:rgba(255,255,255,0.2);}\n" +
                    ".summary-block .breakdown-category-header{color:rgba(255,255,255,0.85);border-bottom-color:rgba(255,255,255,0.25);}\n" +
                    ".summary-block .breakdown-category-header .category-total{color:#fff;background:rgba(255,255,255,0.15);border-color:rgba(255,255,255,0.35);}\n" +
                    ".summary-block .breakdown-card{background:rgba(255,255,255,0.15);border-color:rgba(255,255,255,0.3);}\n" +
                    ".summary-block .breakdown-card:hover{border-color:rgba(255,255,255,0.5);box-shadow:none;}\n" +
                    ".summary-block .breakdown-card-title .param-prefix{color:rgba(255,255,255,0.7);}\n" +
                    ".summary-block .breakdown-card-title .param-value{color:#fff;}\n" +
                    ".summary-block .breakdown-card-count{color:#fff;}\n" +
                    ".summary-block .no-data{color:rgba(255,255,255,0.8);}\n" +
                    "@media print{body{background:#fff;}.project-block,.summary-block,.breakdown-category,.breakdown-card{break-inside:avoid;box-shadow:none;}}\n";
}
```

## server/exporters/TxtExporter.java

```
package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

public class TxtExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("Issue: ").append(issue.key).append("\n");
        sb.append("Ссылка: https://").append(host).append("/project/issues?id=").append(projectName).append("&open=").append(issue.key).append("\n");
        sb.append("Правило: ").append(issue.rule).append("\n");
        sb.append("Файл: ").append(issue.component).append(":").append(issue.line).append("\n");
        sb.append("Сообщение: ").append(issue.message).append("\n\n");
        return sb.toString();
    }
}
```

## server/handlers/PromptBuilder.java

````
package ru.miacomsoft.sonar.server.handlers;

import ru.miacomsoft.sonar.Issue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Строит текст промпта для LLM из данных issue.
 * Зеркально соответствует buildStatusCommentPrompt() из
 * web/tabs/perfile/prompts.js.
 *
 * С 2025: добавлены build*Params(...) — они возвращают Map для
 * PromptTemplate.render(...), чтобы консольный режим использовал
 * ровно те же файлы из prompts/, что и web-версия.
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    public static final class Lang {
        public final String name;
        public final String codeFence;
        Lang(String name, String codeFence) { this.name = name; this.codeFence = codeFence; }
    }

    public static String defaultStatusLabel(String status) {
        if (status == null) return "";
        switch (status.toLowerCase()) {
            case "accept":        return "Принять (Это ошибка, но исправлена будет позднее)";
            case "falsepositive": return "Ложно (Анализ содержит ошибки)";
            case "confirm":       return "Подтвердить";
            case "resolve":       return "Исправлено";
            case "wontfix":       return "Не исправлять";
            default:              return status;
        }
    }

    public static Lang detectLanguage(String component) {
        String path = (component == null) ? "" : component;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(0, colon);
        String lower = path.toLowerCase();

        if (lower.endsWith(".d.ts")) return new Lang("TypeScript", "typescript");

        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return new Lang("неизвестный (определи по коду)", "");
        }
        String ext = lower.substring(dot + 1);

        switch (ext) {
            case "php":  return new Lang("PHP", "php");
            case "js": case "mjs": case "cjs": return new Lang("JavaScript", "javascript");
            case "jsx":  return new Lang("JavaScript (React)", "jsx");
            case "ts":   return new Lang("TypeScript", "typescript");
            case "tsx":  return new Lang("TypeScript (React)", "tsx");
            case "vue":  return new Lang("Vue", "vue");
            case "html": return new Lang("HTML", "html");
            case "css":  return new Lang("CSS", "css");
            case "scss": return new Lang("SCSS", "scss");
            case "less": return new Lang("LESS", "less");
            case "java": return new Lang("Java", "java");
            case "kt": case "kts": return new Lang("Kotlin", "kotlin");
            case "groovy": return new Lang("Groovy", "groovy");
            case "scala": return new Lang("Scala", "scala");
            case "sql":  return new Lang("SQL", "sql");
            case "pks": case "pkb": case "pls": case "plb":
            case "prc": case "fnc": case "trg": case "pck":
                return new Lang("PL/SQL", "plsql");
            case "py": return new Lang("Python", "python");
            case "go": return new Lang("Go", "go");
            case "rb": return new Lang("Ruby", "ruby");
            case "cs": return new Lang("C#", "csharp");
            case "cpp": case "cc": case "cxx": case "hpp": case "hxx": return new Lang("C++", "cpp");
            case "c": case "h": return new Lang("C/C++", "c");
            case "rs": return new Lang("Rust", "rust");
            case "swift": return new Lang("Swift", "swift");
            case "xml": return new Lang("XML", "xml");
            case "yaml": case "yml": return new Lang("YAML", "yaml");
            case "json": return new Lang("JSON", "json");
            case "sh": case "bash": return new Lang("Shell", "bash");
            default: return new Lang(ext.toUpperCase(), ext);
        }
    }

    public static String highlightForPrompt(List<String> snippet, int targetLine) {
        if (snippet == null || snippet.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Pattern lineNum = Pattern.compile("^\\s*(\\d+)\\s*\\|");

        for (int i = 0; i < snippet.size(); i++) {
            String line = snippet.get(i);
            if (i > 0) sb.append('\n');

            if (line.contains("§HL_START§") || line.contains("§HL_END§")) {
                sb.append(line.replace("§HL_START§", ">>> ").replace("§HL_END§", " <<<"));
                continue;
            }

            if (targetLine > 0) {
                Matcher m = lineNum.matcher(line);
                if (m.find()) {
                    try {
                        if (Integer.parseInt(m.group(1)) == targetLine) {
                            sb.append(line.replaceFirst("^(\\s*\\d+\\s*\\|)", "$1 >>> "))
                                    .append(" <<<");
                            continue;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            sb.append(line);
        }
        return sb.toString();
    }

    // ==================== params для PromptTemplate.render ====================

    /**
     * Общие поля для одного issue (используются во всех 4 шаблонах).
     * Параметры соответствуют [TAG] из prompts/*.txt.
     */
    private static Map<String, String> baseIssueParams(Issue issue, String ruleName,
                                                       List<String> snippet,
                                                       List<String> availableTags,
                                                       String fullFileCode) {
        Map<String, String> params = new LinkedHashMap<>();

        Lang lang = detectLanguage(issue.component);
        String codeHighlighted = highlightForPrompt(snippet, issue.line).trim();
        String codeRaw = (snippet == null) ? "" : String.join("\n", snippet)
                .replace("§HL_START§", "")
                .replace("§HL_END§", "");

        String severity = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");

        String ruleCtx = (ruleName != null && !ruleName.isEmpty())
                ? ruleName
                : (issue.rule != null ? issue.rule : "");

        String component = issue.component != null ? issue.component : "";
        String location = component + (issue.line > 0 ? ":" + issue.line : "");

        String tagsList = (availableTags != null && !availableTags.isEmpty())
                ? String.join(", ", availableTags)
                : "любые релевантные";

        params.put("RULE_KEY",         issue.rule != null ? issue.rule : "");
        params.put("RULE_NAME",        ruleName != null ? ruleName : "");
        params.put("RULE_NAME_OR_KEY", ruleCtx);
        params.put("MESSAGE",          issue.message != null ? issue.message : "");
        params.put("COMPONENT",        component);
        params.put("FILE",             component);
        params.put("LINE",             issue.line > 0 ? String.valueOf(issue.line) : "");
        params.put("LOCATION",         location);
        params.put("SEVERITY",         severity);
        params.put("LANGUAGE",         lang.name);
        params.put("LANGUAGE_FENCE",   lang.codeFence);
        params.put("CODE",             codeHighlighted);
        params.put("CODE_RAW",         codeRaw);
        params.put("FULL_FILE_CODE",   fullFileCode != null ? fullFileCode : "");
        params.put("TAGS_LIST",        tagsList);
        params.put("ISSUES_COUNT",     "1");
        params.put("ISSUES_LIST",      "1. " + (issue.key != null ? issue.key : "") + " | "
                + (issue.rule != null ? issue.rule : "") + " | " + location + " | "
                + (issue.message != null ? issue.message : ""));

        return params;
    }

    /** Перегрузка без fullFileCode — для обратной совместимости. */
    public static Map<String, String> buildAutoResolveParams(
            Issue issue, List<String> snippet, List<String> availableTags) {
        return buildAutoResolveParams(issue, snippet, availableTags, "");
    }

    /** Для prompts/auto-resolve.txt */
    public static Map<String, String> buildAutoResolveParams(
            Issue issue, List<String> snippet, List<String> availableTags, String fullFileCode) {
        String ruleName = issue.ruleName != null ? issue.ruleName : "";
        return baseIssueParams(issue, ruleName, snippet, availableTags, fullFileCode);
    }

    /** Перегрузка без fullFileCode — для обратной совместимости. */
    public static Map<String, String> buildAnalyzeIssueParams(
            Issue issue, String ruleName, List<String> snippet, List<String> availableTags) {
        return buildAnalyzeIssueParams(issue, ruleName, snippet, availableTags, "");
    }

    /** Для prompts/analyze-issue.txt */
    public static Map<String, String> buildAnalyzeIssueParams(
            Issue issue, String ruleName, List<String> snippet, List<String> availableTags, String fullFileCode) {
        return baseIssueParams(issue, ruleName, snippet, availableTags, fullFileCode);
    }

    /** Перегрузка без fullFileCode — для обратной совместимости. */
    public static Map<String, String> buildStatusCommentParams(
            Issue issue, String ruleName, List<String> snippet,
            String status, String statusLabel, List<String> availableTags) {
        return buildStatusCommentParams(issue, ruleName, snippet, status, statusLabel, availableTags, "");
    }

    /** Для prompts/status-comment.txt */
    public static Map<String, String> buildStatusCommentParams(
            Issue issue, String ruleName, List<String> snippet,
            String status, String statusLabel, List<String> availableTags, String fullFileCode) {
        Map<String, String> params = baseIssueParams(issue, ruleName, snippet, availableTags, fullFileCode);
        String label = (statusLabel != null && !statusLabel.isEmpty())
                ? statusLabel
                : defaultStatusLabel(status);
        params.put("STATUS",       status != null ? status : "");
        params.put("STATUS_LABEL", label);
        return params;
    }

    /** Для prompts/bulk-status-comment.txt */
    public static Map<String, String> buildBulkStatusCommentParams(
            List<Issue> issues, String status, String statusLabel,
            List<String> availableTags) {
        Map<String, String> params = new LinkedHashMap<>();

        StringBuilder listSb = new StringBuilder();
        for (int i = 0; i < issues.size(); i++) {
            Issue it = issues.get(i);
            String location = (it.component != null ? it.component : "")
                    + (it.line > 0 ? ":" + it.line : "");
            if (i > 0) listSb.append('\n');
            listSb.append(i + 1).append(". ")
                    .append(it.key).append(" | ")
                    .append(it.rule).append(" | ")
                    .append(location).append(" | ")
                    .append(it.message != null ? it.message : "");
        }

        String tagsList = (availableTags != null && !availableTags.isEmpty())
                ? String.join(", ", availableTags)
                : "любые релевантные";

        String label = (statusLabel != null && !statusLabel.isEmpty())
                ? statusLabel
                : defaultStatusLabel(status);

        params.put("ISSUES_LIST",  listSb.toString());
        params.put("ISSUES_COUNT", String.valueOf(issues.size()));
        params.put("TAGS_LIST",    tagsList);
        params.put("STATUS",       status != null ? status : "");
        params.put("STATUS_LABEL", label);

        // Пустые значения для тегов, которые использует шаблон, но не bulk-режим
        putEmptyIfAbsent(params, "RULE_KEY");
        putEmptyIfAbsent(params, "RULE_NAME");
        putEmptyIfAbsent(params, "RULE_NAME_OR_KEY");
        putEmptyIfAbsent(params, "MESSAGE");
        putEmptyIfAbsent(params, "COMPONENT");
        putEmptyIfAbsent(params, "FILE");
        putEmptyIfAbsent(params, "LINE");
        putEmptyIfAbsent(params, "LOCATION");
        putEmptyIfAbsent(params, "SEVERITY");
        putEmptyIfAbsent(params, "LANGUAGE");
        putEmptyIfAbsent(params, "LANGUAGE_FENCE");
        putEmptyIfAbsent(params, "CODE");
        putEmptyIfAbsent(params, "CODE_RAW");
        putEmptyIfAbsent(params, "FULL_FILE_CODE");

        return params;
    }

    private static void putEmptyIfAbsent(Map<String, String> m, String k) {
        if (!m.containsKey(k)) m.put(k, "");
    }

    // ==================== Fallback: жёстко зашитый промпт ====================

    public static String buildAutoResolvePrompt(Issue issue, List<String> snippet,
                                                List<String> availableTags) {
        String code = highlightForPrompt(snippet, issue.line).trim();
        Lang lang = detectLanguage(issue.component);
        String severity = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");

        String tagsList = (availableTags != null && !availableTags.isEmpty())
                ? String.join(", ", availableTags)
                : "любые релевантные";

        String filePath = issue.component != null ? issue.component : "";
        String ruleCtx  = (issue.ruleName != null && !issue.ruleName.isEmpty())
                ? issue.ruleName
                : (issue.rule != null ? issue.rule : "");

        StringBuilder sb = new StringBuilder();
        sb.append("Ты — опытный разработчик и эксперт по SonarQube.\n\n");
        sb.append("ЗАДАЧА: определить статус, критичность, решение о фиксе, теги и обоснование.\n\n");
        sb.append("ОПРЕДЕЛЕНИЯ СТАТУСОВ:\n");
        sb.append("- falsepositive: SonarQube ошибся.\n");
        sb.append("- accept: реальная проблема, откладываем.\n");
        sb.append("- confirm: реальная и срочная.\n");
        sb.append("- resolve: уже исправлено.\n");
        sb.append("- wontfix: не будем исправлять.\n\n");
        sb.append("=== РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===\n");
        sb.append("Верни РОВНО ОДИН из двух тегов в поле \"Теги:\"\n");
        sb.append("- \"require-fix\"     — дефект РЕАЛЬНЫЙ, НУЖНО исправить.\n");
        sb.append("- \"not-require-fix\" — исправление не требуется.\n\n");
        sb.append("ДОСТУПНЫЕ ТЕГИ (используй ТОЛЬКО из этого списка): ").append(tagsList).append("\n");
        sb.append("В поле \"Теги:\" верни СНАЧАЛА require-fix или not-require-fix,\n");
        sb.append("затем 1–2 тега СТРОГО из списка выше.\n\n");
        sb.append("Контекст:\n");
        sb.append("- Правило: ").append(ruleCtx).append("\n");
        sb.append("- Сообщение: ").append(issue.message != null ? issue.message : "").append("\n");
        sb.append("- Файл: ").append(filePath).append(issue.line > 0 ? ":" + issue.line : "").append("\n");
        sb.append("- Severity: ").append(severity).append("\n");
        sb.append("- Язык: ").append(lang.name).append("\n\n");
        sb.append("Код:\n");
        sb.append("```").append(lang.codeFence).append("\n");
        sb.append(code).append("\n");
        sb.append("```\n\n");
        sb.append("ФОРМАТ ОТВЕТА (СТРОГО, без отклонений):\n");
        sb.append("1. Строка 1: \"Статус: <accept|falsepositive|confirm|resolve|wontfix>\"\n");
        sb.append("2. Строка 2: \"Критичность: <подтверждаю|завышена|занижена> — <обоснование>\"\n");
        sb.append("3. Строка 3: \"Теги: <require-fix|not-require-fix>, <tag2>, <tag3>\"\n");
        sb.append("4. Строки 4+: 1–3 предложения.\n\n");
        sb.append("Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.\n");
        return sb.toString();
    }
}
````

## server/handlers/RefreshHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

public class RefreshHandler extends BaseHandler {

    public RefreshHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        // Так как мы работаем напрямую с SonarQube, снимок не нужен.
        // Просто возвращаем OK, чтобы фронтенд перерисовал вкладку.
        JSONObject o = new JSONObject();
        o.put("status", "ok");
        o.put("message", "Данные всегда актуальны (прямые запросы к SonarQube)");
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/RefreshIssueHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.util.Map;

/**
 * POST /api/refresh/issue?project=KEY&key=ISSUE_KEY
 * Синхронный точечный refresh одного issue:
 *   - обновляет issue в снимке (если он всё ещё OPEN/CONFIRMED);
 *   - удаляет его из снимка (если статус изменился на RESOLVED/CLOSED/...);
 *   - удаляет (если в Sonar уже нет).
 * При отсутствии снимка — просто возвращает свежие данные из Sonar без мутации.
 */
public class RefreshIssueHandler extends BaseHandler {

    public RefreshIssueHandler(ServerState state) {
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
        if (key == null || key.isEmpty()) key = param(q, "issueKey", null);
        if (key == null || key.isEmpty()) {
            sendError(ex, 400, "Параметр key (или issueKey) обязателен");
            return;
        }

        ServerState.RefreshResult r = state.refreshIssue(project, key);

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("key", key);
        o.put("removed", r.removed);
        if (r.reason != null) o.put("reason", r.reason);
        if (r.issue != null) o.put("issue", r.issue.toJson());

        // snapshotFor(...) унаследован из BaseHandler (protected) — используем напрямую.
        Snapshot snapshot = snapshotFor(project);
        o.put("snapshotUpdated", snapshot != null);
        if (snapshot != null) {
            o.put("snapshotIssuesCount", snapshot.issues.size());
        }

        sendJson(ex, 200, o);
    }
}
```

## server/handlers/SourceHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GET /api/sources/lines?key=COMPONENT_KEY&from=1&to=1002
 *
 * Возвращает полный листинг кода файла из SonarQube (включая подсветку синтаксиса, если она есть).
 * Параметр key должен быть полным ключом компонента (например, MEDMIS-tMIS.mis:Cache/FileCache.inc).
 */
public class SourceHandler extends BaseHandler {

    public SourceHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);

        String key = param(q, "key", null);
        if (key == null || key.isEmpty()) {
            sendError(ex, 400, "Параметр key обязателен (например: MEDMIS-tMIS.mis:Cache/FileCache.inc)");
            return;
        }

        // По умолчанию запрашиваем с 1-й строки, и до 100000-й (чтобы захватить весь файл)
        int from = paramInt(q, "from", 1);
        int to = paramInt(q, "to", 100000);

        String url = "/api/sources/lines?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&from=" + from + "&to=" + to;

        // Получаем сырой ответ от SonarQube
        String resp = state.getClient().get(url);

        // Парсим в JSONObject и отправляем клиенту как есть
        JSONObject result = new JSONObject(resp);

        sendJson(ex, 200, result);
    }
}
```

## server/handlers/StatusHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.SessionManager;
import ru.miacomsoft.sonar.server.Snapshot;

public class StatusHandler extends BaseHandler {

    public StatusHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        JSONObject root = new JSONObject();

        JSONObject session = new JSONObject();
        session.put("ready", state.isSessionReady());
        session.put("path", state.getSessionFile() == null
                ? "" : state.getSessionFile().toString());

        SessionManager sm = state.getSession();
        if (sm != null) {
            session.put("url",       sm.getUrl());
            session.put("host",      sm.getHost());
            session.put("user",      sm.getUser());
            session.put("authType",  sm.getAuthType());
            session.put("hasToken",  sm.hasToken());
            session.put("hasCookie", sm.hasCookie());
        }
        root.put("session", session);

        JSONObject snap = new JSONObject();
        snap.put("status", state.getSnapshotStatus().name());
        snap.put("phase",  state.getSnapshotPhase() == null ? "" : state.getSnapshotPhase());
        snap.put("progress",      state.getSnapshotProgress());
        snap.put("progressTotal", state.getSnapshotProgressTotal());
        if (state.getSnapshotStartedAt() != null) {
            snap.put("startedAt", state.getSnapshotStartedAt().toString());
        }
        if (state.getSnapshotError() != null) {
            snap.put("error", state.getSnapshotError());
        }
        Snapshot s = state.getSnapshot();
        if (s != null) {
            snap.put("projectKey",    s.projectKey);
            snap.put("builtAt",       s.builtAt.toString());
            snap.put("issuesCount",   s.issues.size());
            snap.put("snippetsCount", s.snippets.size());
            snap.put("host",          s.host);
        }
        root.put("snapshot", snap);

        root.put("defaultProject",     state.getDefaultProject());
        root.put("warmupSnippets",     state.isWarmupSnippets());
        root.put("warmupSnippetLimit", state.getWarmupSnippetLimit());

        sendJson(ex, 200, root);
    }
}
```

## server/handlers/TagsHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;

public class TagsHandler extends BaseHandler {

    public TagsHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        // Теги в SonarQube глобальные, поэтому не вызываем resolveProject(ex).
        Map<String, String> q = parseQuery(ex);
        String query = param(q, "q", "");

        // SonarIssuesApi требует projectKey в конструкторе, но метод fetchTags его не использует.
        String project = param(q, "project", "default");

        SonarIssuesApi api = new SonarIssuesApi(state.getClient(), project);
        List<String> tags = api.fetchTags(query);

        JSONArray arr = new JSONArray();
        for (String t : tags) arr.put(t);

        JSONObject o = new JSONObject();
        o.put("tags", arr);
        sendJson(ex, 200, o);
    }
}
```

