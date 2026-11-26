# Combined part 3 of 5

- files: 21
- bytes: 135963

## jira/JiraEpicService.java

```
package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class JiraEpicService {

    private final JiraClient jira;

    public JiraEpicService(JiraClient jira) {
        this.jira = jira;
    }

    public List<JiraFieldService.AllowedValue> getEpics(String projectKey) {
        List<JiraFieldService.AllowedValue> out = new ArrayList<>();
        try {
            // Новый запрос через Greenhopper API
            String url = "/rest/greenhopper/1.0/epics?searchQuery=&projectKey="
                    + URLEncoder.encode(projectKey, StandardCharsets.UTF_8)
                    + "&maxResults=10000&hideDone=true";

            String resp = jira.get(url);
            JSONObject root = new JSONObject(resp);
            JSONArray epicLists = root.optJSONArray("epicLists");

            if (epicLists != null) {
                for (int i = 0; i < epicLists.length(); i++) {
                    JSONObject listObj = epicLists.getJSONObject(i);
                    JSONArray epicNames = listObj.optJSONArray("epicNames");
                    if (epicNames != null) {
                        for (int j = 0; j < epicNames.length(); j++) {
                            JSONObject epic = epicNames.getJSONObject(j);
                            String key = epic.optString("key");
                            String name = epic.optString("name");

                            // Адаптируем под формат AllowedValue: id = key, value = name
                            if (key != null && !key.isEmpty()) {
                                out.add(new JiraFieldService.AllowedValue(key, name));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[JiraEpicService] Ошибка загрузки эпиков: " + e.getMessage());
        }
        return out;
    }
}
```

## jira/JiraIssueAttachmentService.java

```
package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.jira.JiraClient;
import ru.miacomsoft.sonar.jira.JiraIssueCommentService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    // ===== ДОБАВЛЕНО: универсальная загрузка произвольного файла (ZIP и т.п.) =====
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
            System.out.println("  INFO: uploaded " + name + " → " + url);
        }
        return out;
    }

    public void addCommentWithLinks(String issueKey, String text,
                                    List<AttachmentInfo> attachments)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder(text == null ? "" : text);
        for (AttachmentInfo a : attachments) {
            body.append("\n* [").append(a.filename()).append("|").append(a.url()).append("]");
        }
        commentService.add(issueKey, body.toString());
        System.out.println("  INFO: комментарий со ссылками добавлен");
    }

    public int count(String issueIdOrKey) throws IOException, InterruptedException {
        JSONObject f = new JSONObject(jira.get("/rest/api/2/issue/"
                + URLEncoder.encode(issueIdOrKey, StandardCharsets.UTF_8) + "?fields=attachment"))
                .getJSONObject("fields");
        return f.has("attachment") ? f.getJSONArray("attachment").length() : 0;
    }
}
```

## jira/JiraIssueTransitionService.java

```
package ru.miacomsoft.sonar.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class JiraIssueTransitionService {

    private final JiraClient jira;

    public JiraIssueTransitionService(JiraClient jira) { this.jira = jira; }

    public JSONArray getAvailableTransitions(String issueIdOrKey)
            throws IOException, InterruptedException {
        return parseArrayOrWrapper(jira.get("/rest/api/2/issue/" + enc(issueIdOrKey)
                + "/transitions?expand=transitions.fields"), "transitions");
    }

    public JSONArray getResolutions() throws IOException, InterruptedException {
        return parseArrayOrWrapper(jira.get("/rest/api/2/resolution"), "resolutions");
    }

    public void transitionTo(String issueIdOrKey, String targetNameOrId, String resolutionId)
            throws IOException, InterruptedException {
        JSONArray transitions = getAvailableTransitions(issueIdOrKey);
        String chosen = null;
        for (int i = 0; i < transitions.length(); i++) {
            JSONObject t = transitions.getJSONObject(i);
            String id = t.optString("id");
            String name = t.optString("name", "");
            JSONObject to = t.optJSONObject("to");
            String toName = to != null ? to.optString("name", "") : "";
            if (id.equals(targetNameOrId) || name.equalsIgnoreCase(targetNameOrId)
                    || toName.equalsIgnoreCase(targetNameOrId)) {
                chosen = id; break;
            }
        }
        if (chosen == null) {
            throw new IllegalStateException("Transition '" + targetNameOrId + "' not found. Available: "
                    + describe(transitions));
        }
        JSONObject payload = new JSONObject().put("transition", new JSONObject().put("id", chosen));
        if (resolutionId != null) {
            payload.put("fields", new JSONObject().put("resolution",
                    new JSONObject().put("id", resolutionId)));
        }
        jira.post("/rest/api/2/issue/" + enc(issueIdOrKey) + "/transitions", payload.toString());
    }

    public boolean tryTransition(String issueIdOrKey, String target, String resolutionId) {
        try { transitionTo(issueIdOrKey, target, resolutionId); return true; }
        catch (Exception e) { return false; }
    }

    public void listAvailableTransitions(String issueIdOrKey) {
        System.out.println("-- transitions " + issueIdOrKey + " --");
        try {
            JSONArray arr = getAvailableTransitions(issueIdOrKey);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                JSONObject to = t.optJSONObject("to");
                System.out.printf("  [%s] %-30s → %s%n",
                        t.optString("id"), t.optString("name"),
                        to == null ? "?" : to.optString("name"));
            }
        } catch (Exception e) { System.out.println("  " + e.getMessage()); }
    }

    public String findResolutionId(String... candidates) {
        try {
            JSONArray rs = getResolutions();
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.getJSONObject(i);
                String name = r.optString("name", "");
                for (String c : candidates) if (name.equalsIgnoreCase(c)) return r.optString("id");
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONArray parseArrayOrWrapper(String body, String key) {
        if (body == null) return new JSONArray();
        String t = body.trim();
        if (t.isEmpty()) return new JSONArray();
        if (t.startsWith("[")) return new JSONArray(t);
        JSONArray arr = new JSONObject(t).optJSONArray(key);
        return arr == null ? new JSONArray() : arr;
    }

    private static String describe(JSONArray arr) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.getJSONObject(i);
            JSONObject to = t.optJSONObject("to");
            out.add(t.optString("name") + " → " + (to == null ? "?" : to.optString("name")));
        }
        return out.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
```

## server/BaseHandler.java

```
package ru.miacomsoft.sonar.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseHandler implements HttpHandler {

    protected final ServerState state;

    protected BaseHandler(ServerState state) {
        this.state = state;
    }

    @Override
    public final void handle(HttpExchange ex) throws IOException {
        try {
            addCorsHeaders(ex);
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!allowedMethod(ex.getRequestMethod())) {
                sendError(ex, 405, "Method Not Allowed: " + ex.getRequestMethod());
                return;
            }
            handleRequest(ex);
        } catch (SessionNotReadyException e) {
            safeSendError(ex, 503, e.getMessage());
        } catch (IllegalArgumentException e) {
            safeSendError(ex, 400, e.getMessage());
        } catch (Exception e) {
            safeSendError(ex, 500, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    protected boolean allowedMethod(String method) {
        return "GET".equalsIgnoreCase(method);
    }

    protected abstract void handleRequest(HttpExchange ex) throws Exception;

    // ================= project resolution =================

    protected String resolveProject(HttpExchange ex) {
        String p = parseQuery(ex).get("project");
        if (p == null || p.isEmpty()) p = state.getDefaultProject();
        if (p == null || p.isEmpty()) {
            Snapshot s = state.getSnapshot();
            if (s != null) p = s.projectKey;
        }
        if (p == null || p.isEmpty()) {
            throw new IllegalArgumentException(
                    "Проект не задан. Укажите ?project=KEY, " +
                            "либо задайте SONAR_PROJECT / --project=KEY при старте сервера.");
        }
        return p.trim();
    }

    // ================= snapshot-aware helpers =================

    protected SonarIssuesApi newApi(String projectKey) {
        return state.newApi(projectKey);
    }

    protected SonarBulkApi newBulk() {
        return state.newBulk();
    }

    protected List<String> fetchSnippetCached(String project, String issueKey) throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) {
            List<String> cached = s.snippet(issueKey);
            if (!cached.isEmpty()) return cached;
        }
        return newApi(project).fetchSnippet(issueKey);
    }

    protected void putSource(JSONObject o, String project) {
        Snapshot s = snapshotFor(project);
        o.put("source", s != null ? "snapshot" : "live");
        if (s != null) o.put("snapshotBuiltAt", s.builtAt.toString());
    }

    // ================= query helpers =================

    protected Map<String, String> parseQuery(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return q;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int idx = part.indexOf('=');
            try {
                String k = idx >= 0
                        ? URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8)
                        : URLDecoder.decode(part, StandardCharsets.UTF_8);
                String v = idx >= 0
                        ? URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8)
                        : "";
                q.put(k, v);
            } catch (Exception ignored) {}
        }
        return q;
    }

    protected static String param(Map<String, String> q, String name, String def) {
        String v = q.get(name);
        return (v == null || v.isEmpty()) ? def : v;
    }

    protected static int paramInt(Map<String, String> q, String name, int def) {
        String v = q.get(name);
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    protected static boolean paramBool(Map<String, String> q, String name, boolean def) {
        String v = q.get(name);
        if (v == null || v.isEmpty()) return def;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    // ================= response =================

    protected void sendJson(HttpExchange ex, int status, JSONObject obj) throws IOException {
        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected void sendError(HttpExchange ex, int status, String message) throws IOException {
        JSONObject err = new JSONObject();
        err.put("error", message == null ? "unknown" : message);
        err.put("status", status);
        sendJson(ex, status, err);
    }

    private void safeSendError(HttpExchange ex, int status, String message) {
        try { sendError(ex, status, message); } catch (IOException ignore) {}
    }

    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Requested-With");
        ex.getResponseHeaders().set("Access-Control-Max-Age", "86400");
    }

    protected Snapshot snapshotFor(String projectKey) {
        Snapshot s = state.getSnapshot();
        if (s != null && s.projectKey.equals(projectKey)) return s;
        return null;
    }
}
```

## server/JsonResponse.java

```
package ru.miacomsoft.sonar.server;

public class JsonResponse {
}
```

## server/QueryParams.java

```
package ru.miacomsoft.sonar.server;

public class QueryParams {
}
```

## server/console/AutoCommands.java

```
package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.AutoProcessor;
import ru.miacomsoft.sonar.server.OllamaConfig;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.handlers.PromptBuilder;
import ru.miacomsoft.sonar.server.ollama.OllamaClient;
import ru.miacomsoft.sonar.server.ollama.OllamaResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Команды авто-обработки через Ollama:
 *   auto [SEV] [QUAL] [--yes]     — массовая (синхронно)
 *   auto key <KEY> [--yes]        — одиночная
 *   stop                          — запросить остановку массовой обработки
 *
 * Также содержит printProgress() (callback для AutoProcessor.processAll)
 * и вспомогательный вывод итоговой статистики.
 */
public class AutoCommands {

    private final ConsoleContext ctx;
    private final Object consoleLock;

    public AutoCommands(ConsoleContext ctx, Object consoleLock) {
        this.ctx = ctx;
        this.consoleLock = consoleLock;
    }

    public void handle(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        OllamaConfig cfg = ctx.ollamaConfig();

        if (!cfg.isConfigured()) {
            System.out.println("[auto] Ollama не настроена.");
            System.out.println("       Установите: ollama set url http://host:port");
            System.out.println("                   ollama set model llama3");
            return;
        }

        if (ctx.isAutoRunning()) {
            System.out.println("[auto] уже выполняется. Используйте 'stop' для остановки.");
            return;
        }

        boolean confirm = false;
        List<String> rest = new ArrayList<>();
        for (String t : a) {
            if ("--yes".equals(t)) confirm = true;
            else rest.add(t);
        }

        if (!rest.isEmpty() && "key".equalsIgnoreCase(rest.get(0))) {
            if (rest.size() < 2) {
                System.out.println("usage: auto key <ISSUE_KEY> [--yes]");
                return;
            }
            autoSingle(rest.get(1), confirm);
            return;
        }

        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(rest.toArray(new String[0]), 0);
        SonarIssuesApi api = ctx.state().newApi(project);
        List<Issue> issues = api.fetchOpen(sq[0], sq[1]);

        System.out.printf("%nproject    = %s%n", project);
        System.out.printf("severity   = %s%n", sq[0]);
        System.out.printf("quality    = %s%n", sq[1]);
        System.out.printf("issues     = %d  (OPEN / REOPENED)%n", issues.size());
        System.out.printf("ollama     = %s  (model: %s)%n", cfg.getUrl(), cfg.getModel());
        System.out.printf("prompt     = %s/auto-resolve.txt%n", ctx.template().getPromptsDir());

        if (issues.isEmpty()) {
            System.out.println("\nНет issues для обработки.");
            return;
        }

        if (!confirm) {
            System.out.println("\nDRY RUN — ничего не изменено.");
            System.out.println("Первые " + Math.min(5, issues.size()) + " issues:");
            for (int i = 0; i < Math.min(5, issues.size()); i++) {
                Issue it = issues.get(i);
                System.out.printf("  %-20s  %-12s  %s%n",
                        it.key,
                        it.impactSeverity != null && !it.impactSeverity.isEmpty()
                                ? it.impactSeverity : it.severity,
                        ConsoleContext.shorten(it.message, 70));
            }
            if (issues.size() > 5) {
                System.out.println("  ... и ещё " + (issues.size() - 5));
            }
            System.out.println("\nЧтобы запустить обработку: auto "
                    + sq[0] + " " + sq[1] + " --yes");
            return;
        }

        startAutoBulk(sq[0], sq[1], issues.size());
    }

    /** {@code stop}: сбрасывает флаг остановки текущей auto-обработки. */
    public void stop() {
        synchronized (consoleLock) {
            if (!ctx.isAutoRunning()) {
                System.out.println("[auto] обработка не запущена.");
                return;
            }
            ctx.setAutoStopRequested(true);
            System.out.println("[auto] запрошена остановка "
                    + "(текущий issue будет доработан, затем цикл завершится).");
        }
    }

    // ==================== bulk ====================

    private void startAutoBulk(String sev, String qual, int totalIssues) {
        if (ctx.isAutoRunning()) {
            System.out.println("[auto] уже выполняется.");
            return;
        }
        ctx.setAutoRunning(true);
        ctx.setAutoStopRequested(false);

        System.out.println("\n=== Запуск массовой обработки через Ollama ===");
        System.out.println("[auto] промпт: " + ctx.template().getPromptsDir() + "/auto-resolve.txt");
        System.out.println("[auto] синхронный режим — Ctrl+C для досрочной остановки\n");

        final long t0 = System.currentTimeMillis();
        AutoProcessor.Stats stats = null;
        Exception err = null;

        try {
            stats = AutoProcessor.processAll(
                    ctx.state(), ctx.getProject(), sev, qual,
                    ctx.ollamaConfig(), ctx.template(),
                    this::printProgress,
                    ctx::isAutoStopRequested
            );
        } catch (Exception e) {
            err = e;
        } finally {
            ctx.setAutoRunning(false);
            ctx.setAutoStopRequested(false);
        }

        long elapsed = System.currentTimeMillis() - t0;

        System.out.println();
        if (err != null) {
            System.out.println("[auto] FAIL: " + describeThrowable(err));
        }
        if (stats != null) {
            printStats(stats, elapsed, sev, qual);
        }
    }

    private void printStats(AutoProcessor.Stats stats, long elapsed, String sev, String qual) {
        ServerState state = ctx.state();
        String project = ctx.getProject();

        System.out.println("=== Результат ===");
        System.out.printf("Всего:         %d%n", stats.total);
        System.out.printf("Успешно:       %d%n", stats.success);
        System.out.printf("Ошибок:        %d%n", stats.failed);
        if (stats.sessionExpired) {
            System.out.println("Остановлено:   да (SESSION EXPIRED — HTTP 401)");
        }
        if (stats.abortedByNetwork) {
            System.out.println("Остановлено:   да (" + stats.abortReason + ")");
        }
        System.out.printf("Время:         %.1f сек%n", elapsed / 1000.0);
        if (!stats.byStatus.isEmpty()) {
            System.out.println("По статусам:   " + stats.byStatus);
        }
        if (!stats.byFixDecision.isEmpty()) {
            System.out.println("По фикс-тегам: " + stats.byFixDecision);
        }
        if (!stats.failedKeys.isEmpty()) {
            int show = Math.min(stats.failedKeys.size(), 20);
            System.out.println("Failed keys (первые " + show + " из "
                    + stats.failedKeys.size() + "):");
            for (int i = 0; i < show; i++) {
                System.out.println("  " + stats.failedKeys.get(i));
            }
            if (stats.failedFile != null) {
                System.out.println("Полный список: " + stats.failedFile);
            }
        }

        if (stats.sessionExpired) {
            System.out.println();
            System.out.println("##################################################");
            System.out.println("#  ⚠  ТОКЕН / СЕССИЯ SONARQUBE НЕДЕЙСТВИТЕЛЬНЫ   #");
            System.out.println("##################################################");
            System.out.println("Обработано " + stats.success + " из " + stats.total
                    + " issues, дальше — стоп.");
            System.out.println();
            System.out.println("Что делать:");
            System.out.println("  1. Сгенерируйте новый токен: "
                    + (state.getSession() != null ? state.getSession().getUrl() : "?")
                    + "/account/security");
            System.out.println("  2. Обновите session.properties (sonar.token=...)");
            System.out.println("  3. session reload");
            System.out.println("  4. auto " + sev + " " + qual + " --yes");
            System.out.println();
        }

        if (stats.abortedByNetwork) {
            System.out.println();
            System.out.println("##################################################");
            System.out.println("#  ⚠  OLLAMA / СЕТЬ НЕДОСТУПНЫ                      #");
            System.out.println("##################################################");
            System.out.println("Причина: " + stats.abortReason);
            System.out.println("Проверьте: ollama test");
            System.out.println();
        }

        boolean shouldRebuild = !stats.sessionExpired && !stats.abortedByNetwork
                && stats.success > 0
                && state.isSnapshotReady()
                && state.getSnapshot().projectKey.equals(project)
                && !state.isSnapshotBuilding();
        if (shouldRebuild) {
            System.out.println("[auto] Обновляю снимок в фоне (status — прогресс)");
            state.buildSnapshotAsync(project);
        }
    }

    // ==================== single ====================

    private void autoSingle(String key, boolean confirm) throws Exception {
        ServerState state = ctx.state();
        String project    = ctx.getProject();
        OllamaConfig cfg  = ctx.ollamaConfig();

        SonarIssuesApi api = state.newApi(project);
        Issue it = api.fetchIssue(key);
        if (it == null) {
            System.out.println("[auto] issue не найден: " + key);
            return;
        }

        System.out.printf("%nkey        = %s%n", it.key);
        System.out.printf("status     = %s%n", it.status);
        System.out.printf("component  = %s:%d%n", it.component, it.line);
        System.out.printf("rule       = %s%n", it.rule);
        System.out.printf("message    = %s%n", ConsoleContext.shorten(it.message, 120));
        System.out.printf("ollama     = %s  (model: %s)%n", cfg.getUrl(), cfg.getModel());
        System.out.printf("prompt     = %s/auto-resolve.txt%n", ctx.template().getPromptsDir());

        if (!confirm) {
            System.out.println("\nDRY RUN — добавьте --yes, чтобы применить.");
            return;
        }

        try {
            String ruleName = it.ruleName;
            if (ruleName == null || ruleName.isEmpty()) {
                try { ruleName = api.fetchRuleName(it.rule); }
                catch (Exception e) { ruleName = it.rule; }
                it.ruleName = ruleName;
            }

            List<String> snippet;
            try { snippet = api.fetchSnippet(it.key); }
            catch (Exception e) { snippet = new ArrayList<>(); }

            List<String> tags = new ArrayList<>();
            try { tags = api.fetchTags(null); }
            catch (Exception e) { /* ignore */ }

            String prompt;
            try {
                Map<String, String> params =
                        PromptBuilder.buildAutoResolveParams(it, snippet, tags);
                prompt = ctx.template().render("auto-resolve", params);
                if (prompt == null || prompt.isBlank()) {
                    System.err.println("[auto] rendered prompt is blank, using fallback");
                    prompt = PromptBuilder.buildAutoResolvePrompt(it, snippet, tags);
                }
            } catch (Exception e) {
                System.err.println("[auto] template render failed, using fallback: " + e.getMessage());
                prompt = PromptBuilder.buildAutoResolvePrompt(it, snippet, tags);
            }

            System.out.println("[auto] запрос к Ollama...");
            String raw = new OllamaClient(cfg).chat(prompt);

            OllamaResponse parsed = OllamaResponse.parse(raw);
            if (parsed.status.isEmpty()) {
                System.err.println("[auto] Не удалось извлечь статус из ответа.");
                return;
            }

            System.out.printf("[auto] статус     = %s%n", parsed.status);
            System.out.printf("[auto] теги       = %s%n", parsed.tags);
            System.out.printf("[auto] комментарий:%n%s%n", parsed.comment);

            SonarBulkApi bulk = state.newBulk();
            bulk.change(List.of(it), parsed.status, parsed.comment);

            Set<String> finalTags = new LinkedHashSet<>();
            if (it.tags != null) finalTags.addAll(it.tags);
            if (parsed.tags.contains("require-fix"))     finalTags.remove("not-require-fix");
            if (parsed.tags.contains("not-require-fix")) finalTags.remove("require-fix");
            finalTags.addAll(parsed.tags);

            if (!finalTags.isEmpty()) {
                try { bulk.setTags(List.of(it), String.join(",", finalTags)); }
                catch (Exception e) { System.err.println("[auto] setTags: " + e.getMessage()); }
            }

            System.out.println("[auto] OK");

            if (state.isSnapshotReady() && !state.isSnapshotBuilding()) {
                state.refreshIssue(project, it.key);
            }
        } catch (Exception e) {
            System.err.println("[auto] FAIL: " + e.getMessage());
        }
    }

    // ==================== progress ====================

    private void printProgress(AutoProcessor.Progress p) {
        if (p == null || p.total == 0) return;
        String line;
        switch (p.phase) {
            case "preparing" -> line = String.format("[%d/%d] %s — подготовка...",
                    p.current, p.total, p.issueKey);
            case "ollama"    -> line = String.format("[%d/%d] %s — Ollama (%s)...",
                    p.current, p.total, p.issueKey, p.detail);
            case "applying"  -> line = String.format("[%d/%d] %s — применяю %s...",
                    p.current, p.total, p.issueKey, p.detail);
            case "done"      -> line = String.format("[%d/%d] %s → OK  %s",
                    p.current, p.total, p.issueKey, p.detail);
            case "failed"    -> line = String.format("[%d/%d] %s → FAIL: %s",
                    p.current, p.total, p.issueKey, p.detail);
            default          -> { return; }
        }
        System.out.println(line);
        System.out.flush();
    }

    private static String describeThrowable(Throwable e) {
        if (e == null) return "unknown";
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            return e.getClass().getName();
        }
        return e.getClass().getSimpleName() + ": " + msg;
    }
}
```

## server/console/PromptCommands.java

```
package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.handlers.PromptBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Команда {@code prompt *}: рендер промптов из prompts/*.txt
 * (status-comment, bulk-status-comment, analyze-issue, auto-resolve).
 */
public class PromptCommands {

    private final ConsoleContext ctx;

    public PromptCommands(ConsoleContext ctx) {
        this.ctx = ctx;
    }

    public void handle(String[] a) throws Exception {
        if (a.length == 0 || "list".equalsIgnoreCase(a[0]) || "help".equalsIgnoreCase(a[0])) {
            promptHelp();
            return;
        }

        String name = a[0];
        if (!ctx.template().exists(name)) {
            System.out.println("[prompt] шаблон не найден: " + name);
            List<String> avail = ctx.template().listAvailable();
            System.out.println("Доступные: " + (avail.isEmpty() ? "<пусто>" : String.join(", ", avail)));
            return;
        }

        if (!ctx.requireProject()) return;

        String project     = ctx.getProject();
        String issueKey    = null;
        String keysCsv     = null;
        String sev         = "ALL";
        String qual        = "ALL";
        String status      = "accept";
        String statusLabel = null;
        String savePath    = null;

        for (int i = 1; i < a.length; i++) {
            String t = a[i];
            if      (t.startsWith("keys="))        keysCsv     = t.substring("keys=".length());
            else if (t.startsWith("sev="))         sev         = t.substring("sev=".length()).toUpperCase(Locale.ROOT);
            else if (t.startsWith("qual="))        qual        = t.substring("qual=".length()).toUpperCase(Locale.ROOT);
            else if (t.startsWith("status="))      status      = t.substring("status=".length());
            else if (t.startsWith("statusLabel=")) statusLabel = t.substring("statusLabel=".length());
            else if (t.startsWith("save="))        savePath    = t.substring("save=".length());
            else if (issueKey == null)             issueKey    = t;
        }

        SonarIssuesApi api = ctx.state().newApi(project);
        List<String> availableTags;
        try { availableTags = new ArrayList<>(api.fetchTags(null)); }
        catch (Exception e) { availableTags = new ArrayList<>(); }

        String prompt;

        switch (name) {
            case "status-comment", "auto-resolve", "analyze-issue" -> {
                Issue it = null;
                if (issueKey != null) {
                    it = api.fetchIssue(issueKey);
                } else if (keysCsv != null) {
                    String firstKey = keysCsv.split(",")[0].trim();
                    if (!firstKey.isEmpty()) it = api.fetchIssue(firstKey);
                } else {
                    List<Issue> list = ctx.fetchIssues(project, sev, qual);
                    if (!list.isEmpty()) it = list.get(0);
                }

                if (it == null) {
                    System.out.println("[prompt] issue не найден");
                    return;
                }

                String ruleName = it.ruleName;
                if (ruleName == null || ruleName.isEmpty()) {
                    try { ruleName = api.fetchRuleName(it.rule); }
                    catch (Exception e) { ruleName = it.rule; }
                }

                List<String> snippet;
                try { snippet = api.fetchSnippet(it.key); }
                catch (Exception e) { snippet = new ArrayList<>(); }

                Map<String, String> params;
                if ("status-comment".equals(name)) {
                    params = PromptBuilder.buildStatusCommentParams(
                            it, ruleName, snippet, status, statusLabel, availableTags);
                } else {
                    params = PromptBuilder.buildAnalyzeIssueParams(
                            it, ruleName, snippet, availableTags);
                }
                prompt = ctx.template().render(name, params);
            }

            case "bulk-status-comment" -> {
                List<Issue> issues;
                if (keysCsv != null) {
                    issues = new ArrayList<>();
                    for (String raw : keysCsv.split(",")) {
                        String k = raw.trim();
                        if (k.isEmpty()) continue;
                        Issue it = api.fetchIssue(k);
                        if (it != null) issues.add(it);
                    }
                } else {
                    issues = ctx.fetchIssues(project, sev, qual);
                }

                if (issues.isEmpty()) {
                    System.out.println("[prompt] нет issues для выбранного набора");
                    return;
                }

                Map<String, String> params = PromptBuilder.buildBulkStatusCommentParams(
                        issues, status, statusLabel, availableTags);
                prompt = ctx.template().render(name, params);
            }

            default -> {
                System.out.println("[prompt] неизвестный шаблон: " + name);
                System.out.println("Поддерживается: status-comment | bulk-status-comment | analyze-issue | auto-resolve");
                return;
            }
        }

        if (prompt == null || prompt.isBlank()) {
            System.out.println("[prompt] промпт пуст — проверьте файл prompts/" + name + ".txt");
            return;
        }

        if (savePath != null) {
            Path out = Path.of(savePath);
            Files.writeString(out, prompt, StandardCharsets.UTF_8);
            System.out.println("[prompt] сохранено в " + out.toAbsolutePath()
                    + " (" + prompt.length() + " chars)");
        } else {
            System.out.println();
            System.out.println("===================================================");
            System.out.println(" Prompt: " + name + "  (" + prompt.length() + " chars)");
            System.out.println("===================================================");
            System.out.println(prompt);
            System.out.println("===================================================");
        }
    }

    private void promptHelp() {
        System.out.println("Доступные шаблоны в " + ctx.template().getPromptsDir() + ":");
        List<String> names = ctx.template().listAvailable();
        if (names.isEmpty()) {
            System.out.println("  <пусто> — создайте prompts/*.txt");
        } else {
            for (String n : names) System.out.println("  " + n);
        }
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  prompt list                                    — список шаблонов");
        System.out.println("  prompt <name> <KEY> [status=accept] [statusLabel=...]");
        System.out.println("  prompt <name> keys=A,B,C [status=...]");
        System.out.println("  prompt <name> sev=ALL qual=ALL [status=...]");
        System.out.println("  prompt <name> ... save=<path>                  — сохранить в файл");
    }
}
```

## server/console/ReportCommands.java

```
package ru.miacomsoft.sonar.server.console;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.Reporter;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;
import ru.miacomsoft.sonar.server.TeamMapping;
import ru.miacomsoft.sonar.server.exporters.HtmlPerFileFullExporter;
import ru.miacomsoft.sonar.server.exporters.TeamModuleMdExporter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Команды отчётов и обновления снимка.
 *
 * ИЗМЕНЕНО: везде, где раньше запрашивались issues/фасеты по статусам
 * SonarIssuesApi.ACTIVE_STATUSES (OPEN,CONFIRMED,REOPENED — БЕЗ ACCEPTED),
 * теперь используется SonarIssuesApi.ALL_STATUSES (OPEN,CONFIRMED,REOPENED,
 * ACCEPTED,FALSE_POSITIVE,RESOLVED,CLOSED,WONTFIX). Это касается:
 *   - handleSummary(...)          — сводка (count + facets);
 *   - countIssuesBySeverity(...)  — используется qual-summary;
 *   - collectIssues(...)          — используется team-zip и team-html.
 *
 * sanitizeStatuses() внутри SonarIssuesApi сам смапит legacy → MQR
 * (REOPENED→OPEN, RESOLVED→FIXED, CLOSED→FIXED, WONTFIX→FALSE_POSITIVE)
 * и отбросит статусы, неподдерживаемые сервером.
 *
 * Методы handleMatrix / handlePerFile / handleDetails / handleIssues /
 * handleKeys идут через ctx.fetchIssues(...) / ctx.fetchIssuesByFile(...),
 * которые уже исправлены в ConsoleContext на ALL_STATUSES.
 */
public class ReportCommands {

    private final ConsoleContext ctx;

    public ReportCommands(ConsoleContext ctx) {
        this.ctx = ctx;
    }

    // ==================== refresh ====================

    public void handleRefresh(String[] a) throws Exception {
        ServerState state = ctx.state();

        if (a.length > 0 && "issue".equalsIgnoreCase(a[0])) {
            if (a.length < 2) { System.out.println("usage: refresh issue <KEY>"); return; }
            if (!ctx.requireProject()) return;
            String project = ctx.getProject();
            String key = a[1];
            System.out.println("[console] refreshing issue " + key + " ...");
            ServerState.RefreshResult r = state.refreshIssue(project, key);
            System.out.println("  removed = " + r.removed);
            if (r.reason != null) System.out.println("  reason  = " + r.reason);
            if (r.issue != null) {
                Issue i = r.issue;
                System.out.printf("  issue   = %s [%s/%s] %s:%d%n",
                        i.key,
                        i.impactSeverity != null && !i.impactSeverity.isEmpty()
                                ? i.impactSeverity : i.severity,
                        i.status, i.component, i.line);
            }
            return;
        }

        if (a.length > 0 && "wait".equalsIgnoreCase(a[0])) {
            if (!ctx.requireProject()) return;
            System.out.println("[console] full refresh (sync), please wait...");
            try {
                Snapshot s = state.buildSnapshotSync(ctx.getProject());
                System.out.printf("[console] done: %d issues, %d snippets, builtAt=%s%n",
                        s.issues.size(), s.snippets.size(), s.builtAt);
            } catch (Exception e) {
                System.err.println("[console] refresh failed: " + e.getMessage());
            }
            return;
        }

        if (!ctx.requireProject()) return;
        if (state.isSnapshotBuilding()) {
            System.out.println("[console] build already in progress — use 'status' to monitor");
            return;
        }
        boolean started = state.buildSnapshotAsync(ctx.getProject());
        System.out.println("[console] refresh " + (started ? "started" : "not started")
                + " for " + ctx.getProject() + ". Use 'status' to monitor.");
    }

    // ==================== reports ====================

    public void handleSummary(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(a, 0);

        int total;
        Map<String, Integer> byRules, byFiles, bySev;
        Snapshot snap = ctx.snapshotFor(project);
        if (snap != null) {
            total   = snap.filter(sq[0], sq[1]).size();
            byRules = snap.facetRules(sq[0], sq[1]);
            byFiles = snap.facetFiles(sq[0], sq[1]);
            bySev   = snap.facetSeverities(sq[0], sq[1]);
        } else {
            SonarIssuesApi api = ctx.state().newApi(project);
            // ИЗМЕНЕНО: было api.count(...) / api.facet(...) — внутри ACTIVE_STATUSES
            // (без ACCEPTED). Теперь явно ALL_STATUSES для всех четырёх вызовов.
            total   = api.countWithStatuses(SonarIssuesApi.ALL_STATUSES, sq[0], sq[1]);
            byRules = api.facetWithStatuses(SonarIssuesApi.ALL_STATUSES, sq[0], sq[1], null, "rules");
            byFiles = api.facetWithStatuses(SonarIssuesApi.ALL_STATUSES, sq[0], sq[1], null, "files");
            bySev   = api.facetWithStatuses(SonarIssuesApi.ALL_STATUSES, sq[0], sq[1], null, "severities");
        }
        System.out.printf("%nВсего %s/%s: %d%n", sq[0], sq[1], total);
        Reporter.printFacet("По правилам", byRules);
        Reporter.printFacet("По файлам", byFiles);
        Reporter.printFacet("По severity", bySev);
    }

    public void handleQualSummary(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        SonarIssuesApi api = ctx.state().newApi(project);

        Map<String, String> qualToSevMap = new LinkedHashMap<>();
        if (a.length == 0) {
            qualToSevMap.put("SECURITY", "ALL");
            qualToSevMap.put("RELIABILITY", "ALL");
            qualToSevMap.put("MAINTAINABILITY", "ALL");
        } else {
            for (String arg : a) {
                if (arg.contains(":")) {
                    String[] parts = arg.split(":", 2);
                    qualToSevMap.put(parts[0].toUpperCase(), parts[1].toUpperCase());
                } else {
                    qualToSevMap.put(arg.toUpperCase(), "ALL");
                }
            }
        }

        System.out.println("\nПроект: " + project);
        for (Map.Entry<String, String> entry : qualToSevMap.entrySet()) {
            String qual = entry.getKey();
            String sevFilter = entry.getValue();

            System.out.println("  " + qual);

            Map<String, Integer> counts = countIssuesBySeverity(api, project, qual, sevFilter);
            for (Map.Entry<String, Integer> c : counts.entrySet()) {
                System.out.printf("    %-15s : %d\n", c.getKey(), c.getValue());
            }
        }
        System.out.println();
    }

    private Map<String, Integer> countIssuesBySeverity(SonarIssuesApi api, String project,
                                                       String qual, String sevFilter) throws Exception {
        // ИЗМЕНЕНО: заранее парсим набор запрошенных severity.
        // В counts будем класть ТОЛЬКО эти ключи — никакого автоматического
        // добавления MEDIUM/LOW, если их не запросили.
        Set<String> requestedSeverities = new LinkedHashSet<>();
        if ("ALL".equalsIgnoreCase(sevFilter)) {
            Collections.addAll(requestedSeverities, "BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO");
        } else {
            for (String s : sevFilter.split(",")) {
                String t = s.trim().toUpperCase();
                if (!t.isEmpty()) requestedSeverities.add(t);
            }
        }

        Map<String, Integer> counts = new TreeMap<>(Collections.reverseOrder());
        for (String s : requestedSeverities) counts.put(s, 0);

        // ИЗМЕНЕНО: ALL_STATUSES (в нашем проекте это "") — без фильтра по статусу,
        // сервер вернёт все статусы; фильтрация — только по notTags=not-require-fix.
        Map<String, Integer> ruleCounts = api.facetWithStatuses(
                SonarIssuesApi.ALL_STATUSES, sevFilter, qual, null, "rules");
        if (ruleCounts == null || ruleCounts.isEmpty()) return counts;

        List<Map.Entry<String, Integer>> sortedRules = new ArrayList<>(ruleCounts.entrySet());
        sortedRules.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));

        int CHUNK_SIZE = 3000;
        List<List<String>> ruleChunks = new ArrayList<>();
        List<String> currentChunk = new ArrayList<>();
        int currentSum = 0;

        for (Map.Entry<String, Integer> entry : sortedRules) {
            if (currentSum + entry.getValue() > CHUNK_SIZE && !currentChunk.isEmpty()) {
                ruleChunks.add(currentChunk);
                currentChunk = new ArrayList<>();
                currentSum = 0;
            }
            currentChunk.add(entry.getKey());
            currentSum += entry.getValue();
        }
        if (!currentChunk.isEmpty()) ruleChunks.add(currentChunk);

        for (List<String> chunk : ruleChunks) {
            String rulesCsv = String.join(",", chunk);
            int page = 1;
            while (true) {
                try {
                    JSONObject raw = api.fetchPageRaw(
                            SonarIssuesApi.ALL_STATUSES, sevFilter, qual,
                            rulesCsv, null, "not-require-fix", page, 500
                    );
                    JSONArray arr = raw.optJSONArray("issues");
                    if (arr == null || arr.length() == 0) break;

                    for (int j = 0; j < arr.length(); j++) {
                        Issue it = Issue.from(arr.getJSONObject(j), project);
                        if (it.tags != null && it.tags.contains("not-require-fix")) continue;

                        // ИСПРАВЛЕНО: считаем только issues, у которых есть impact
                        // с (quality == qual И severity в requestedSeverities).
                        String matchedSev = matchImpactSeverity(it, qual, requestedSeverities);
                        if (matchedSev != null) {
                            counts.put(matchedSev, counts.get(matchedSev) + 1);
                        }
                    }

                    if (arr.length() < 500) break;
                    page++;
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("10000")) {
                        Map<String, Integer> fileCounts = api.facetWithStatuses(
                                SonarIssuesApi.ALL_STATUSES, sevFilter, qual, rulesCsv, "files");
                        if (fileCounts != null) {
                            for (String file : fileCounts.keySet()) {
                                int fPage = 1;
                                while (true) {
                                    JSONObject fRaw = api.fetchPageRawForComponent(
                                            SonarIssuesApi.ALL_STATUSES, sevFilter, qual,
                                            rulesCsv, file, null, "not-require-fix", fPage, 500
                                    );
                                    JSONArray fArr = fRaw.optJSONArray("issues");
                                    if (fArr == null || fArr.length() == 0) break;
                                    for (int j = 0; j < fArr.length(); j++) {
                                        Issue it = Issue.from(fArr.getJSONObject(j), project);
                                        if (it.tags != null && it.tags.contains("not-require-fix")) continue;
                                        // ИСПРАВЛЕНО: та же логика — только matched impact.
                                        String matchedSev = matchImpactSeverity(it, qual, requestedSeverities);
                                        if (matchedSev != null) {
                                            counts.put(matchedSev, counts.get(matchedSev) + 1);
                                        }
                                    }
                                    if (fArr.length() < 500) break;
                                    fPage++;
                                }
                            }
                        }
                        break;
                    } else {
                        throw e;
                    }
                }
            }
        }
        return counts;
    }

    /**
     * Возвращает severity того impact'а, у которого одновременно:
     *   - softwareQuality == qual (без учёта регистра);
     *   - severity входит в requestedSeverities.
     * Если ни один impact не подошёл — null (issue не учитывается).
     *
     * Для legacy-issues без массива impacts используется старое поле severity.
     */
    private static String matchImpactSeverity(Issue it, String qual, Set<String> requestedSeverities) {
        if (it.impacts != null && !it.impacts.isEmpty()) {
            for (Issue.Impact im : it.impacts) {
                if (im == null) continue;
                if (im.softwareQuality == null || im.severity == null) continue;
                if (qual.equalsIgnoreCase(im.softwareQuality)
                        && requestedSeverities.contains(im.severity)) {
                    return im.severity;
                }
            }
            return null;
        }
        // legacy-mode
        String sev = it.severity != null ? it.severity.toUpperCase() : "";
        return requestedSeverities.contains(sev) ? sev : null;
    }



    public void handleMatrix(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(a, 0);
        // ctx.fetchIssues → уже использует ALL_STATUSES (см. ConsoleContext).
        List<Issue> issues = ctx.fetchIssues(project, sq[0], sq[1]);
        System.out.println("Всего: " + issues.size());
        Reporter.printMatrix(issues);
    }

    public void handlePerFile(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(a, 0);
        // ctx.fetchIssuesByFile → уже использует ALL_STATUSES.
        Map<String, List<Issue>> byFile = ctx.fetchIssuesByFile(project, sq[0], sq[1]);
        Reporter.printPerFile(byFile);
    }

    public void handleDetails(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(a, 0);
        int snippets = 5;
        if (a.length > 2) {
            try { snippets = Integer.parseInt(a[2]); } catch (NumberFormatException ignore) {}
        }
        // ctx.fetchIssuesByFile → уже использует ALL_STATUSES.
        Map<String, List<Issue>> byFile = ctx.fetchIssuesByFile(project, sq[0], sq[1]);
        SonarIssuesApi api = ctx.state().newApi(project);
        Reporter.printDetailedByFileWithSnippets(byFile, api, snippets);
    }

    public void handleIssues(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(a, 0);
        // ctx.fetchIssues → уже использует ALL_STATUSES.
        List<Issue> issues = ctx.fetchIssues(project, sq[0], sq[1]);
        System.out.printf("%n%d issues (%s/%s)%n", issues.size(), sq[0], sq[1]);
        for (Issue it : issues) {
            String sev = it.impactSeverity != null && !it.impactSeverity.isEmpty()
                    ? it.impactSeverity : it.severity;
            System.out.printf("  %-10s %-6s %-40s %-12s %s%n",
                    it.key, sev, it.component + ":" + it.line, it.rule,
                    ConsoleContext.shorten(it.message, 80));
        }
    }

    public void handleKeys(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(a, 0);
        // ctx.fetchIssues → уже использует ALL_STATUSES.
        List<Issue> issues = ctx.fetchIssues(project, sq[0], sq[1]);
        System.out.printf("%n%d keys (%s/%s)%n", issues.size(), sq[0], sq[1]);
        for (Issue it : issues) System.out.println("  " + it.key);
    }

    public void handleSnippet(String[] a) throws Exception {
        if (a.length < 1) { System.out.println("usage: snippet <KEY>"); return; }
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String key = a[0];
        SonarIssuesApi api = ctx.state().newApi(project);

        Issue it = api.fetchIssue(key);
        if (it == null) { System.out.println("[console] issue не найден: " + key); return; }

        String ruleName = it.ruleName;
        if (ruleName == null || ruleName.isEmpty()) {
            try { ruleName = api.fetchRuleName(it.rule); } catch (Exception e) { ruleName = ""; }
        }

        List<String> snippet;
        try { snippet = ctx.fetchSnippetCached(project, key); } catch (Exception e) { snippet = new ArrayList<>(); }

        System.out.println("===================================================");
        System.out.printf("key       = %s%n", it.key);
        System.out.printf("status    = %s%n", it.status);
        System.out.printf("severity  = %s%n", it.impactSeverity != null && !it.impactSeverity.isEmpty() ? it.impactSeverity : it.severity);
        System.out.printf("component = %s:%d%n", it.component, it.line);
        System.out.printf("rule      = %s%s%n", it.rule, (ruleName != null && !ruleName.isEmpty()) ? " (" + ruleName + ")" : "");
        System.out.printf("tags      = %s%n", (it.tags == null || it.tags.isEmpty()) ? "<none>" : String.join(", ", it.tags));
        System.out.println("\nmessage:\n  " + it.message + "\n\ncode:");
        if (snippet.isEmpty()) System.out.println("  <snippet not available>");
        else for (String line : snippet) System.out.println("  " + line);
        System.out.println("===================================================");
    }

    // ==================== TEAM REPORTS ====================

    private Map<String, List<Issue>> collectIssues(SonarIssuesApi api, String project,
                                                   String sev, String qual) throws Exception {
        Map<String, List<Issue>> issuesByFile = new TreeMap<>();
        int totalDownloaded = 0;
        int totalFilteredOut = 0;
        int totalKept = 0;

        // ИЗМЕНЕНО: заранее парсим фильтры sev/qual в множества для клиентской
        // проверки. null означает "ALL" (без ограничения).
        Set<String> sevSet  = parseFilter(sev);
        Set<String> qualSet = parseFilter(qual);

        System.out.println("[team-report] 1. Запрос списка всех правил в проекте...");
        // ALL_STATUSES (в нашем проекте пустая строка) — без фильтра по статусу.
        Map<String, Integer> ruleCounts = api.facetWithStatuses(
                SonarIssuesApi.ALL_STATUSES, sev, qual, null, "rules");
        if (ruleCounts == null || ruleCounts.isEmpty()) {
            throw new Exception("Не удалось получить список правил. Проверьте доступ к SonarQube.");
        }

        List<Map.Entry<String, Integer>> sortedRules = new ArrayList<>(ruleCounts.entrySet());
        sortedRules.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));

        int CHUNK_SIZE = 3000;
        List<List<String>> ruleChunks = new ArrayList<>();
        List<String> currentChunk = new ArrayList<>();
        int currentSum = 0;

        for (Map.Entry<String, Integer> entry : sortedRules) {
            if (currentSum + entry.getValue() > CHUNK_SIZE && !currentChunk.isEmpty()) {
                ruleChunks.add(currentChunk);
                currentChunk = new ArrayList<>();
                currentSum = 0;
            }
            currentChunk.add(entry.getKey());
            currentSum += entry.getValue();
        }
        if (!currentChunk.isEmpty()) ruleChunks.add(currentChunk);

        System.out.printf("[team-report]    Правил: %d, блоков: %d\n", sortedRules.size(), ruleChunks.size());

        for (int i = 0; i < ruleChunks.size(); i++) {
            List<String> chunk = ruleChunks.get(i);
            String rulesCsv = String.join(",", chunk);
            System.out.printf("[team-report] --- Блок %d/%d (правил: %d) ---\n",
                    i + 1, ruleChunks.size(), chunk.size());

            int page = 1;
            while (true) {
                try {
                    JSONObject raw = api.fetchPageRaw(
                            SonarIssuesApi.ALL_STATUSES, sev, qual,
                            rulesCsv, null, "not-require-fix", page, 500
                    );
                    JSONArray arr = raw.optJSONArray("issues");
                    if (arr == null || arr.length() == 0) break;

                    for (int j = 0; j < arr.length(); j++) {
                        Issue it = Issue.from(arr.getJSONObject(j), project);
                        totalDownloaded++;
                        if (it.tags != null && it.tags.contains("not-require-fix")) {
                            totalFilteredOut++;
                            continue;
                        }
                        // ИСПРАВЛЕНО: клиентская фильтрация по (quality, severity).
                        // Серверный фильтр impactSoftwareQualities/impactSeverities
                        // в SonarQube трактуется как независимые И (issue попадает
                        // в ответ, если есть хоть один impact нужного quality
                        // И хоть один impact нужной severity — даже у разных
                        // quality). Поэтому дополнительно проверяем, что у issue
                        // есть impact с ОДНОВРЕМЕННО quality==qual И severity∈sev.
                        if (!matchesSevQual(it, sevSet, qualSet)) {
                            totalFilteredOut++;
                            continue;
                        }
                        String comp = it.component != null ? it.component : "(unknown)";
                        issuesByFile.computeIfAbsent(comp, k -> new ArrayList<>()).add(it);
                        totalKept++;
                    }

                    if (arr.length() < 500) break;
                    page++;
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("10000")) {
                        System.out.println("[team-report]   !!! Достигнут лимит (10000). Разбиваем по файлам...");
                        Map<String, Integer> fileCounts = api.facetWithStatuses(
                                SonarIssuesApi.ALL_STATUSES, sev, qual, rulesCsv, "files");
                        if (fileCounts != null) {
                            for (String file : fileCounts.keySet()) {
                                int fPage = 1;
                                while (true) {
                                    JSONObject fRaw = api.fetchPageRawForComponent(
                                            SonarIssuesApi.ALL_STATUSES, sev, qual,
                                            rulesCsv, file, null, "not-require-fix", fPage, 500
                                    );
                                    JSONArray fArr = fRaw.optJSONArray("issues");
                                    if (fArr == null || fArr.length() == 0) break;
                                    for (int j = 0; j < fArr.length(); j++) {
                                        Issue it = Issue.from(fArr.getJSONObject(j), project);
                                        totalDownloaded++;
                                        if (it.tags != null && it.tags.contains("not-require-fix")) {
                                            totalFilteredOut++;
                                            continue;
                                        }
                                        // ИСПРАВЛЕНО: та же клиентская проверка.
                                        if (!matchesSevQual(it, sevSet, qualSet)) {
                                            totalFilteredOut++;
                                            continue;
                                        }
                                        String comp = it.component != null ? it.component : "(unknown)";
                                        issuesByFile.computeIfAbsent(comp, k -> new ArrayList<>()).add(it);
                                        totalKept++;
                                    }
                                    if (fArr.length() < 500) break;
                                    fPage++;
                                }
                            }
                        }
                        break;
                    } else {
                        throw e;
                    }
                }
            }
        }
        System.out.printf("[team-report] Сбор завершен. Получено: %d, отфильтровано: %d, принято: %d\n",
                totalDownloaded, totalFilteredOut, totalKept);
        return issuesByFile;
    }

    /**
     * Парсит значение фильтра (sev или qual) в множество.
     * null / пусто / "ALL" → null (= без ограничения).
     */
    private static Set<String> parseFilter(String v) {
        if (v == null || "ALL".equalsIgnoreCase(v)) return null;
        Set<String> set = new LinkedHashSet<>();
        for (String s : v.split(",")) {
            String t = s.trim().toUpperCase();
            if (!t.isEmpty()) set.add(t);
        }
        return set.isEmpty() ? null : set;
    }

    /**
     * Проверяет, есть ли у issue impact с ОДНОВРЕМЕННО
     *   (quality в qualSet ИЛИ qualSet==null)
     *   И (severity в sevSet ИЛИ sevSet==null).
     *
     * Это повторяет семантику SonarQube, где impact'ы сравниваются попарно,
     * а не независимо по quality и severity. Для legacy-issues без impacts
     * используется старое поле severity.
     */
    private static boolean matchesSevQual(Issue it, Set<String> sevSet, Set<String> qualSet) {
        if (it.impacts != null && !it.impacts.isEmpty()) {
            for (Issue.Impact im : it.impacts) {
                if (im == null) continue;
                boolean qOk = qualSet == null
                        || (im.softwareQuality != null && qualSet.contains(im.softwareQuality.toUpperCase()));
                boolean sOk = sevSet == null
                        || (im.severity != null && sevSet.contains(im.severity.toUpperCase()));
                if (qOk && sOk) return true;
            }
            return false;
        }
        // legacy-mode: только severity
        if (sevSet == null) return true;
        return it.severity != null && sevSet.contains(it.severity.toUpperCase());
    }

    public void handleTeamZip(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        ServerState state = ctx.state();
        SonarIssuesApi api = state.newApi(project);

        String sev = "ALL";
        String qual = "ALL";
        String outName = null;

        String validSevsRegex = "(?i)^(BLOCKER|CRITICAL|MAJOR|MINOR|INFO|HIGH|MEDIUM|LOW|ALL)(,(BLOCKER|CRITICAL|MAJOR|MINOR|INFO|HIGH|MEDIUM|LOW|ALL))*$";
        String validQualsRegex = "(?i)^(MAINTAINABILITY|RELIABILITY|SECURITY|SECURITY_HOTSPOT|ALL)(,(MAINTAINABILITY|RELIABILITY|SECURITY|SECURITY_HOTSPOT|ALL))*$";

        for (String arg : a) {
            if (arg.matches(validSevsRegex)) sev = arg.replace(" ", "").toUpperCase();
            else if (arg.matches(validQualsRegex)) qual = arg.replace(" ", "").toUpperCase();
            else if (!arg.startsWith("--")) outName = arg;
        }

        if (outName == null) outName = "report_team_module_" + sanitize(project) + "_" + System.currentTimeMillis() + ".zip";
        Path outPath = Path.of(outName).toAbsolutePath();

        System.out.println("\n[team-zip] ▶ СТАРТ (SEV=" + sev + ", QUAL=" + qual + ")");
        Map<String, List<Issue>> issuesByFile = collectIssues(api, project, sev, qual);
        if (issuesByFile.isEmpty()) { System.out.println("Нет данных."); return; }

        TeamMapping mapping = TeamMapping.load();
        String host = state.getSession() != null ? state.getSession().getHost() : "sonar.bars.group";

        System.out.println("[team-zip] Генерация ZIP...");
        int filesCount = TeamModuleMdExporter.writeZip(outPath, issuesByFile, api, project, host, mapping, null, System.currentTimeMillis());
        System.out.println("[team-zip] ✔ Готово! Файлов: " + filesCount + ". Путь: " + outPath);
    }

    public void handleTeamHtml(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        ServerState state = ctx.state();
        SonarIssuesApi api = state.newApi(project);

        String sev = "ALL";
        String qual = "ALL";
        String outDirStr = null;

        String validSevsRegex = "(?i)^(BLOCKER|CRITICAL|MAJOR|MINOR|INFO|HIGH|MEDIUM|LOW|ALL)(,(BLOCKER|CRITICAL|MAJOR|MINOR|INFO|HIGH|MEDIUM|LOW|ALL))*$";
        String validQualsRegex = "(?i)^(MAINTAINABILITY|RELIABILITY|SECURITY|SECURITY_HOTSPOT|ALL)(,(MAINTAINABILITY|RELIABILITY|SECURITY|SECURITY_HOTSPOT|ALL))*$";

        for (String arg : a) {
            if (arg.matches(validSevsRegex)) sev = arg.replace(" ", "").toUpperCase();
            else if (arg.matches(validQualsRegex)) qual = arg.replace(" ", "").toUpperCase();
            else if (!arg.startsWith("--")) outDirStr = arg;
        }

        if (outDirStr == null) {
            System.out.println("usage: team-html [SEV] [QUAL] <output_dir>");
            System.out.println("Пример: team-html HIGH ALL C:\\Reports\\MyProject");
            return;
        }

        Path rootDir = Path.of(outDirStr).toAbsolutePath();
        Path tempDir = rootDir.resolve("_all_files");

        Files.createDirectories(rootDir);
        Files.createDirectories(tempDir);

        System.out.println("\n[team-html] ▶ СТАРТ. SEV=" + sev + ", QUAL=" + qual);
        System.out.println("[team-html] Корневой каталог: " + rootDir);
        System.out.println("[team-html] Временный каталог: " + tempDir);

        Map<String, List<Issue>> issuesByFile = collectIssues(api, project, sev, qual);
        if (issuesByFile.isEmpty()) {
            System.out.println("[team-html] Нет данных для отчета.");
            return;
        }

        String host = state.getSession() != null ? state.getSession().getHost() : "sonar.bars.group";
        TeamMapping mapping = TeamMapping.load();

        System.out.println("[team-html] Шаг 1: Генерация HTML-файлов в общий каталог...");
        int count = 0;
        for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
            String file = entry.getKey();
            List<Issue> issues = entry.getValue();
            count++;

            if (count % 50 == 0 || count == 1 || count == issuesByFile.size()) {
                System.out.printf("[team-html]   Сгенерировано %d/%d (%.1f%%)\n", count, issuesByFile.size(), (count * 100.0) / issuesByFile.size());
            }

            String fullSource = "";
            try { fullSource = api.fetchFullSource(project + ":" + file); } catch (Exception ignored) {}

            for (Issue it : issues) {
                try { it.snippet = api.fetchSnippet(it.key); } catch (Exception ignored) {}
            }

            String html = HtmlPerFileFullExporter.formatHtmlFile(issues, project, host, file, fullSource);
            String safeFileName = sanitizeFileName(file) + ".html";
            Path tempFile = tempDir.resolve(safeFileName);
            Files.writeString(tempFile, html, StandardCharsets.UTF_8);
        }

        System.out.println("\n[team-html] Шаг 2: Раскладывание файлов по каталогам (Отдел / Модуль)...");
        count = 0;
        for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
            String file = entry.getKey();
            count++;

            TeamMapping.FileInfo info = (mapping != null) ? mapping.findInfo(file) : null;
            String team = (info != null && info.hasTeam()) ? info.team : "Отдел не определен";
            String component = (info != null && info.hasComponent()) ? info.component : "Модуль не определен";

            String safeTeam = sanitizePathPart(team);
            String safeComponent = sanitizePathPart(component);
            String safeFileName = sanitizeFileName(file) + ".html";

            Path targetDir = rootDir.resolve(safeTeam).resolve(safeComponent);
            Files.createDirectories(targetDir);

            Path sourceFile = tempDir.resolve(safeFileName);
            Path targetFile = targetDir.resolve(safeFileName);

            if (Files.exists(sourceFile)) {
                Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }

            if (count % 50 == 0 || count == 1 || count == issuesByFile.size()) {
                System.out.printf("[team-html]   Перемещено %d/%d\n", count, issuesByFile.size());
            }
        }

        try {
            Files.deleteIfExists(tempDir);
        } catch (Exception e) {
            System.out.println("[team-html] Временная папка не удалена (не пуста): " + tempDir);
        }

        System.out.println("\n[team-html] ✔ ГОТОВО! Отчет сформирован.");
        System.out.println("[team-html] Всего файлов: " + issuesByFile.size());
        System.out.println("[team-html] Путь: " + rootDir);
        System.out.println("=======================================================\n");
    }

    // ==================== UTILS ====================

    private static String sanitizePathPart(String s) {
        if (s == null || s.trim().isEmpty()) return "Без_названия";
        return s.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String sanitizeFileName(String path) {
        if (path.contains(":")) {
            String[] parts = path.split(":");
            path = parts[parts.length - 1];
        }
        path = path.replaceAll("[\\\\/]", "_");
        path = path.replaceAll("[^A-Za-z0-9._-]", "_");
        return path;
    }

    private static String sanitize(String s) {
        if (s == null) return "project";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
```

## server/exporters/HtmlExporter.java

```
package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

public class HtmlExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='border:1px solid #ccc; margin:10px; padding:10px;'>");
        sb.append("<h3>").append(issue.key).append("</h3>");
        sb.append("<p><b>Ссылка:</b> <a href='https://").append(host).append("/project/issues?id=").append(projectName).append("&open=").append(issue.key).append("'>Открыть</a></p>");
        sb.append("<p><b>Правило:</b> ").append(issue.rule).append("</p>");
        sb.append("<p><b>Файл:</b> ").append(issue.component).append(":").append(issue.line).append("</p>");
        sb.append("<p><b>Сообщение:</b> ").append(issue.message).append("</p>");
        sb.append("</div>");
        return sb.toString();
    }
}
```

## server/exporters/LlmMdExporter.java

````
package ru.miacomsoft.sonar.server.exporters;

import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LlmMdExporter {

    public static int writeZip(Path zipPath, Map<String, List<Issue>> issuesByFile,
                               SonarIssuesApi api, String project, String host,
                               OutputStream sseOut, long startMs) throws Exception {

        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
                String file = entry.getKey();
                List<Issue> issues = entry.getValue();

                // Отправка прогресса на клиент
                if (sseOut != null) {
                    JSONObject p = new JSONObject();
                    p.put("stage", "llm-md-gen");
                    p.put("message", "Генерация задания для LLM: " + file);
                    p.put("current", count);
                    p.put("total", issuesByFile.size());
                    p.put("elapsedMs", System.currentTimeMillis() - startMs);
                    String msg = "event: progress\ndata: " + p.toString() + "\n\n";
                    sseOut.write(msg.getBytes(StandardCharsets.UTF_8));
                    sseOut.flush();
                }

                String fullSource = "";
                try {
                    fullSource = api.fetchFullSource(project + ":" + file);
                } catch (Exception e) {
                    System.err.println("[LlmMdExporter] Не удалось получить код для " + file);
                }

                String mdContent = buildMarkdownPrompt(file, issues, fullSource, project, host, api);

                String safeName = makeFileName(file);
                ZipEntry ze = new ZipEntry(safeName + ".md");
                zos.putNextEntry(ze);
                zos.write(mdContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                count++;
            }
        }
        return count;
    }

    private static String buildMarkdownPrompt(String file, List<Issue> issues, String fullSource,
                                              String project, String host, SonarIssuesApi api) {
        StringBuilder sb = new StringBuilder();
        String lang = getLanguageExtension(file);

        sb.append("# Задание для LLM: Исправление ошибок в файле `").append(file).append("`\n\n");
        sb.append("Ты — опытный разработчик. Твоя задача — изучить найденные ошибки, комментарии пользователя и анализатора, а также исходный код файла. На основе этого тебе нужно переписать файл, исправив все описанные ошибки.\n\n");

        sb.append("## Найденные ошибки:\n\n");
        for (Issue it : issues) {
            String lineStr = it.line > 0 ? " (Строка: " + it.line + ")" : "";
            sb.append("### Ошибка: ").append(it.key).append(lineStr).append("\n\n");
            sb.append("Идентификатор ошибки: ").append(it.key).append(lineStr).append("\n\n");

            String link = "https://" + host + "/project/issues?id=" + urlEnc(project) + "&open=" + urlEnc(it.key);
            sb.append("Ссылка: ").append(link).append("\n\n");

            String fullComponentKey = project + ":" + it.component;
            sb.append("Ссылка на исходник: ").append(fullComponentKey).append("\n\n");

            sb.append("Правило: ").append(it.rule);
            if (it.ruleName != null && !it.ruleName.isEmpty()) {
                sb.append(" — ").append(it.ruleName);
            }
            sb.append("\n\n");

            String tagsStr = (it.tags == null || it.tags.isEmpty()) ? "нет" : String.join(", ", it.tags);
            sb.append("Теги: ").append(tagsStr).append("\n\n");

            sb.append("Сообщение: ").append(it.message).append("\n\n");

            String lastComment = getLastComment(it);
            if (!"нет".equals(lastComment)) {
                // Заменяем переносы строк на пробелы, чтобы комментарий выглядел единым блоком текста
                String singleLineComment = lastComment.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();
                sb.append("Пользовательский комментарий: ").append(singleLineComment).append("\n\n");
            } else {
                sb.append("Пользовательский комментарий: нет\n\n");
            }

            // --- ДОБАВЛЕНИЕ ФРАГМЕНТА КОДА С ПОДСВЕТКОЙ ---
            try {
                List<String> snippet = api.fetchSnippet(it.key);
                if (snippet != null && !snippet.isEmpty()) {
                    sb.append("Фрагмент кода с ошибкой:\n");
                    sb.append("```").append(lang).append("\n");
                    for (String snippetLine : snippet) {
                        // Превращаем маркеры SonarQube в читаемые для LLM указатели
                        String highlightedLine = snippetLine
                                .replace("§HL_START§", ">>> ")
                                .replace("§HL_END§", " <<<");
                        sb.append(highlightedLine).append("\n");
                    }
                    sb.append("```\n\n");
                }
            } catch (Exception e) {
                System.err.println("[LlmMdExporter] Не удалось загрузить фрагмент для " + it.key);
            }
            // -----------------------------------------------

            sb.append("---\n\n");
        }

        sb.append("## Исходный код файла:\n");
        sb.append("```").append(lang).append("\n");
        sb.append(fullSource).append("\n");
        sb.append("```\n\n");

        sb.append("## Инструкции:\n");
        sb.append("1. **Сохрани стиль написания кода**: отступы, именование переменных, архитектуру. Правь только те места, где обнаружены описанные ошибки.\n");
        sb.append("2. В начале ответа добавь короткий комментарий (для GIT), в котором перечисли, что именно было поправлено.\n");
        sb.append("3. После комментария выведи **ПОЛНЫЙ** листинг исправленного файла, обернутый в блок кода Markdown (```...```).\n");
        sb.append("4. Убедись, что в итоговом файле нет не исправленных ошибок из списка выше.\n");

        return sb.toString();
    }

    private static String makeFileName(String originalFilePath) {
        String cleanPath = originalFilePath;
        if (cleanPath.contains(":")) {
            String[] parts = cleanPath.split(":");
            cleanPath = parts[parts.length - 1];
        }
        cleanPath = cleanPath.replaceAll("[\\\\/]", "_");
        cleanPath = cleanPath.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleanPath;
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

    private static String getLanguageExtension(String file) {
        String lower = file.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts")) return "typescript";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".html")) return "html";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".cpp") || lower.endsWith(".h")) return "cpp";
        if (lower.endsWith(".cs")) return "csharp";
        return "text";
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}
````

## server/exporters/TeamModuleMdExporter.java

````
package ru.miacomsoft.sonar.server.exporters;

import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.TeamMapping;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TeamModuleMdExporter {

    public static int writeZip(Path zipPath, Map<String, List<Issue>> issuesByFile,
                               SonarIssuesApi api, String project, String host,
                               TeamMapping mapping,
                               OutputStream sseOut, long startMs) throws Exception {

        int count = 0;
        int totalFiles = issuesByFile.size();

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
                String file = entry.getKey();
                List<Issue> issues = entry.getValue();
                count++;

                // --- ЛОГИРОВАНИЕ ДЛЯ КОНСОЛИ ---
                if (sseOut == null) {
                    // Выводим прогресс каждые 50 файлов или если файлов мало
                    if (count % 50 == 0 || count == 1 || count == totalFiles || totalFiles <= 50) {
                        long elapsed = System.currentTimeMillis() - startMs;
                        double speed = (double) elapsed / count;
                        long etaMs = (long) (speed * (totalFiles - count));
                        System.out.printf("[team-zip]   ZIP: %d/%d файлов (%.1f%%), ETA: %d сек. Текущий: %s\n",
                                count, totalFiles, (count * 100.0) / totalFiles, etaMs / 1000, file);
                    }
                } else {
                    // --- ЛОГИРОВАНИЕ ДЛЯ WEB (SSE) ---
                    JSONObject p = new JSONObject();
                    p.put("stage", "team-module-gen");
                    p.put("message", "Генерация отчета: " + file);
                    p.put("current", count);
                    p.put("total", totalFiles);
                    p.put("elapsedMs", System.currentTimeMillis() - startMs);
                    String msg = "event: progress\ndata: " + p.toString() + "\n\n";
                    sseOut.write(msg.getBytes(StandardCharsets.UTF_8));
                    sseOut.flush();
                }

                // Получаем информацию о команде и модуле
                TeamMapping.FileInfo info = (mapping != null) ? mapping.findInfo(file) : null;
                String team = (info != null && info.hasTeam()) ? info.team : "Отдел не определен";
                String component = (info != null && info.hasComponent()) ? info.component : "Модуль не определен";

                // Санитайзим имена каталогов
                String safeTeam = sanitizePathPart(team);
                String safeComponent = sanitizePathPart(component);

                String fullSource = "";
                try {
                    fullSource = api.fetchFullSource(project + ":" + file);
                } catch (Exception e) {
                    System.err.println("[TeamModuleMdExporter] Не удалось получить код для " + file);
                }

                String mdContent = buildMarkdown(file, issues, fullSource, project, host, api);
                String fileName = makeFileName(file);

                // Путь в архиве: Отдел / Модуль / Файл.md
                String entryPath = safeTeam + "/" + safeComponent + "/" + fileName + ".md";

                ZipEntry ze = new ZipEntry(entryPath);
                zos.putNextEntry(ze);
                zos.write(mdContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return count;
    }

    private static String buildMarkdown(String file, List<Issue> issues, String fullSource,
                                        String project, String host, SonarIssuesApi api) {
        StringBuilder sb = new StringBuilder();
        String lang = getLanguageExtension(file);

        sb.append("# Файл: `").append(file).append("`\n\n");
        sb.append("## Найденные ошибки:\n\n");

        for (Issue it : issues) {
            String lineStr = it.line > 0 ? " (Строка: " + it.line + ")" : "";
            sb.append("### Ошибка: ").append(it.key).append(lineStr).append("\n\n");

            String link = "https://" + host + "/project/issues?id=" + urlEnc(project) + "&open=" + urlEnc(it.key);
            sb.append("Ссылка: ").append(link).append("\n\n");

            sb.append("Правило: ").append(it.rule);
            if (it.ruleName != null && !it.ruleName.isEmpty()) {
                sb.append(" — ").append(it.ruleName);
            }
            sb.append("\n\n");
            sb.append("Сообщение: ").append(it.message).append("\n\n");

            String lastComment = getLastComment(it);
            if (!"нет".equals(lastComment)) {
                String singleLineComment = lastComment.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();
                sb.append("Пользовательский комментарий: ").append(singleLineComment).append("\n\n");
            }

            try {
                List<String> snippet = api.fetchSnippet(it.key);
                if (snippet != null && !snippet.isEmpty()) {
                    sb.append("Фрагмент кода с ошибкой:\n");
                    sb.append("```").append(lang).append("\n");
                    for (String snippetLine : snippet) {
                        String highlightedLine = snippetLine
                                .replace("§HL_START§", ">>> ")
                                .replace("§HL_END§", " <<<");
                        sb.append(highlightedLine).append("\n");
                    }
                    sb.append("```\n\n");
                }
            } catch (Exception e) {
                System.err.println("[TeamModuleMdExporter] Не удалось загрузить фрагмент для " + it.key);
            }
            sb.append("---\n\n");
        }

        sb.append("## Исходный код файла:\n");
        sb.append("```").append(lang).append("\n");
        sb.append(fullSource).append("\n");
        sb.append("```\n");

        return sb.toString();
    }

    private static String sanitizePathPart(String s) {
        if (s == null || s.trim().isEmpty()) return "Без_названия";
        // Заменяем недопустимые в Windows/Linux символы
        String out = s.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return out;
    }

    private static String makeFileName(String originalFilePath) {
        String cleanPath = originalFilePath;
        if (cleanPath.contains(":")) {
            String[] parts = cleanPath.split(":");
            cleanPath = parts[parts.length - 1];
        }
        cleanPath = cleanPath.replaceAll("[\\\\/]", "_");
        cleanPath = cleanPath.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleanPath;
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

    private static String getLanguageExtension(String file) {
        String lower = file.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts")) return "typescript";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".html")) return "html";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".cpp") || lower.endsWith(".h")) return "cpp";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".frm")) return "pascal";
        return "text";
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}
````

## server/handlers/BuildPromptHandler.java

````
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.PromptTemplate;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/prompt/build?name=<promptName>&project=KEY
 *     [&issueKey=KEY | &keys=A,B,C | &sev=ALL&qual=ALL]
 *     [&status=accept&statusLabel=...]
 */
public class BuildPromptHandler extends BaseHandler {

    private final PromptTemplate template;

    public BuildPromptHandler(ServerState state, PromptTemplate template) {
        super(state);
        this.template = template;
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);

        String name = param(q, "name", null);
        if (name == null || name.isEmpty()) {
            sendError(ex, 400,
                    "Параметр name обязателен (имя файла промпта без .txt)");
            return;
        }
        if (!template.exists(name)) {
            List<String> available = template.listAvailable();
            String availStr = available.isEmpty() ? "<пусто>" : String.join(", ", available);
            String absPath;
            try {
                absPath = template.getPromptsDir().toAbsolutePath().normalize().toString();
            } catch (Exception e) {
                absPath = String.valueOf(template.getPromptsDir());
            }
            System.err.println("[BuildPromptHandler] NOT FOUND: name=" + name
                    + " dir=" + absPath + " available=" + availStr);
            sendError(ex, 404,
                    "Промпт не найден: " + name
                            + ". Каталог: " + absPath
                            + ". Доступные промпты: " + availStr
                            + ". Создайте <dir>/" + name + ".txt или проверьте -Dsonar.prompts.");
            return;
        }

        String project = resolveProject(ex);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("PROJECT", project);

        String status      = param(q, "status", "");
        String statusLabel = param(q, "statusLabel", "");
        if ((statusLabel == null || statusLabel.isEmpty())
                && status != null && !status.isEmpty()) {
            statusLabel = PromptBuilder.defaultStatusLabel(status);
        }
        params.put("STATUS", status);
        params.put("STATUS_LABEL", statusLabel);

        List<String> availableTags = new ArrayList<>();
        try {
            List<String> raw = newApi(project).fetchTags(null);
            if (raw != null) availableTags.addAll(raw);
        } catch (Exception ignored) {
            // не критично
        }

        String issueKey = param(q, "issueKey", null);
        String keysCsv  = param(q, "keys",     null);
        String sev      = param(q, "sev",  "ALL");
        String qual     = param(q, "qual", "ALL");

        String fullCode = "";
        String issueComponent = "";

        if (issueKey != null && !issueKey.isEmpty()) {
            String[] result = buildSingle(project, issueKey, params, availableTags);
            fullCode = result[0];
            issueComponent = result[1];
        } else if (keysCsv != null && !keysCsv.isEmpty()) {
            buildByKeys(project, keysCsv, params, availableTags);
        } else {
            buildByFilter(project, sev, qual, params, availableTags);
        }

        String prompt = template.render(name, params);

        // ДОБАВЛЕНИЕ ПОЛНОГО КОДА В КОНЕЦ
        if (fullCode != null && !fullCode.isEmpty() && !prompt.contains(fullCode)) {
            // Закрываем блок кода фрагмента, если шаблон его не закрыл (нет ``` в конце)
            if (!prompt.trim().endsWith("```")) {
                prompt += "\n```\n";
            }

            String langFence = PromptBuilder.detectLanguage(issueComponent).codeFence;
            prompt += "\n=== ПОЛНЫЙ ЛИСТИНГ ФАЙЛА ===\n```" + langFence + "\n" + fullCode + "\n```";
        }

        JSONObject o = new JSONObject();
        o.put("promptName", name);
        o.put("promptLength", prompt.length());
        o.put("prompt", prompt);
        o.put("tagsAvailable", new JSONArray(availableTags));

        JSONObject paramsJson = new JSONObject();
        for (Map.Entry<String, String> e : params.entrySet()) {
            paramsJson.put(e.getKey(), e.getValue());
        }
        o.put("params", paramsJson);

        putSource(o, project);
        sendJson(ex, 200, o);
    }

    private String[] buildSingle(String project, String issueKey,
                                 Map<String, String> params,
                                 List<String> availableTags) throws Exception {
        SonarIssuesApi api = newApi(project);
        Issue issue = api.fetchIssue(issueKey);
        if (issue == null) {
            throw new IllegalArgumentException("Issue не найден: " + issueKey);
        }

        String ruleName;
        try {
            ruleName = api.fetchRuleName(issue.rule);
        } catch (Exception ignored) {
            ruleName = "";
        }

        List<String> snippet;
        try {
            snippet = fetchSnippetCached(project, issueKey);
        } catch (Exception e) {
            snippet = new ArrayList<>();
        }

        String fullCode = "";
        if (issue.component != null && !issue.component.isEmpty()) {
            String fullComponentKey = project + ":" + issue.component;
            fullCode = api.fetchFullSource(fullComponentKey);
        }

        if (issue.tags != null) {
            for (String t : issue.tags) {
                if (!availableTags.contains(t)) availableTags.add(t);
            }
        }

        fillIssueParams(issue, ruleName, snippet, fullCode, availableTags, params);

        return new String[] { fullCode, issue.component };
    }

    private void buildByKeys(String project, String keysCsv,
                             Map<String, String> params,
                             List<String> availableTags) throws Exception {
        SonarIssuesApi api = newApi(project);
        List<Issue> issues = new ArrayList<>();
        for (String raw : keysCsv.split(",")) {
            String key = raw.trim();
            if (key.isEmpty()) continue;
            Issue it = api.fetchIssue(key);
            if (it != null) issues.add(it);
        }
        fillBulkParams(issues, availableTags, params);
    }

    private void buildByFilter(String project, String sev, String qual,
                               Map<String, String> params,
                               List<String> availableTags) throws Exception {
        SonarIssuesApi api = newApi(project);
        List<Issue> issues = new ArrayList<>();

        try {
            JSONObject raw = api.fetchPageRaw(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, null, 1, 500);
            JSONArray arr = raw.optJSONArray("issues");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    issues.add(Issue.from(arr.getJSONObject(i), project));
                }
            }
        } catch (Exception e) {
            issues = newApi(project).fetchAll(sev, qual);
        }

        fillBulkParams(issues, availableTags, params);
    }

    private void fillIssueParams(Issue issue, String ruleName, List<String> snippet, String fullCode,
                                 List<String> availableTags,
                                 Map<String, String> params) {

        PromptBuilder.Lang lang = PromptBuilder.detectLanguage(issue.component);

        String codeHighlighted = PromptBuilder.highlightForPrompt(snippet, issue.line).trim();
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
        params.put("FULL_FILE_CODE",   fullCode != null ? fullCode : "");
        params.put("TAGS_LIST",        tagsList(availableTags));
        params.put("ISSUES_COUNT",     "1");
        params.put("ISSUES_LIST",      "1. " + issue.key + " | " + issue.rule
                + " | " + location + " | " + (issue.message != null ? issue.message : ""));
    }

    private void fillBulkParams(List<Issue> issues, List<String> availableTags,
                                Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < issues.size(); i++) {
            Issue it = issues.get(i);
            String location = (it.component != null ? it.component : "")
                    + (it.line > 0 ? ":" + it.line : "");
            if (i > 0) sb.append('\n');
            sb.append(i + 1).append(". ")
                    .append(it.key).append(" | ")
                    .append(it.rule).append(" | ")
                    .append(location).append(" | ")
                    .append(it.message != null ? it.message : "");
        }
        params.put("ISSUES_LIST",  sb.toString());
        params.put("ISSUES_COUNT", String.valueOf(issues.size()));
        params.put("TAGS_LIST",    tagsList(availableTags));

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
    }

    private static void putEmptyIfAbsent(Map<String, String> m, String k) {
        if (!m.containsKey(k)) m.put(k, "");
    }

    private static String tagsList(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "любые релевантные";
        return String.join(", ", tags);
    }
}
````

## server/handlers/DetailsHandler.java

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

public class DetailsHandler extends BaseHandler {

    public DetailsHandler(ServerState state) {
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
        int ps = paramInt(q, "ps", 10);
        int maxSnippets = paramInt(q, "snippets", 5);
        if (maxSnippets < 0) maxSnippets = 0;

        SonarIssuesApi api = newApi(project);
        // 6 аргументов
        JSONObject rawResponse = api.fetchPageRaw(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, rule, page, ps);
        JSONArray rawIssues = rawResponse.optJSONArray("issues");

        Map<String, List<Issue>> byFile = new TreeMap<>();
        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                Issue it = Issue.from(rawIssues.getJSONObject(i), project);
                byFile.computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
            }
        }

        JSONArray files = new JSONArray();
        int totalIssues = 0;
        for (Map.Entry<String, List<Issue>> e : byFile.entrySet()) {
            List<Issue> issues = e.getValue();
            totalIssues += issues.size();

            JSONArray issuesArr = new JSONArray();
            int n = 0;
            boolean isHotspot = "SECURITY_HOTSPOT".equalsIgnoreCase(qual);

            for (Issue it : issues) {
                if (n >= maxSnippets) break;
                JSONObject io = it.toJson();
                try { io.put("ruleName", it.ruleName != null && !it.ruleName.isEmpty()
                        ? it.ruleName : api.fetchRuleName(it.rule)); }
                catch (Exception ex2) { io.put("ruleName", ""); }
                if (!isHotspot) {
                    try { io.put("snippet", new JSONArray(fetchSnippetCached(project, it.key))); }
                    catch (Exception ex2) { io.put("snippet", new JSONArray()); }
                } else {
                    io.put("snippet", new JSONArray());
                }
                issuesArr.put(io);
                n++;
            }

            JSONObject f = new JSONObject();
            f.put("file", e.getKey());
            f.put("count", issues.size());
            f.put("returned", issuesArr.length());
            f.put("hasMore", issues.size() > issuesArr.length());
            f.put("remaining", Math.max(0, issues.size() - issuesArr.length()));
            f.put("issues", issuesArr);
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
        o.put("maxSnippetsPerFile", maxSnippets);
        o.put("filesCount", byFile.size());
        o.put("issuesCount", totalIssues);
        o.put("files", files);
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/IssuesHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;

public class IssuesHandler extends BaseHandler {

    public IssuesHandler(ServerState state) {
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

        // hideProcessed: true — только OPEN/CONFIRMED/REOPENED
        //               false — все статусы (включая ACCEPTED/RESOLVED/WONTFIX/...)
        // По умолчанию false, чтобы вкладка «Отчет» с фильтром statusFilter=ALL
        // видела реальное общее количество (а не только активные).
        boolean hideProcessed = paramBool(q, "hideProcessed", false);
        String statuses = hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : null;

        SonarIssuesApi api = newApi(project);

        // Фронт запрашивает сразу ВСЁ (ps >= 10000)
        if (ps >= 10000) {
            System.out.println("[IssuesHandler] Запрос на выгрузку ВСЕХ замечаний (hideProcessed=" + hideProcessed + "). Запускаем сбор...");

            List<Issue> allIssues = api.fetchAllByStatuses(statuses, sev, qual);

            System.out.println("[IssuesHandler] Сбор завершен. Всего собрано: " + allIssues.size());

            JSONObject response = new JSONObject();
            response.put("total", allIssues.size());
            response.put("p", 1);
            response.put("ps", allIssues.size());
            response.put("issues", new JSONArray(allIssues.stream().map(Issue::toJson).toList()));

            putSource(response, project);
            sendJson(ex, 200, response);
            return;
        }

        // Обычный прокси-запрос для постраничного вывода
        int validPs = Math.min(ps, SonarIssuesApi.FETCH_PAGE_SIZE);
        JSONObject rawResponse = api.fetchPageRaw(statuses, sev, qual, rule, page, validPs);

        putSource(rawResponse, project);
        sendJson(ex, 200, rawResponse);
    }
}
```

## server/handlers/KeysHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;

public class KeysHandler extends BaseHandler {

    public KeysHandler(ServerState state) {
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
        int ps = paramInt(q, "ps", 500);

        SonarIssuesApi api = newApi(project);
        // 6 аргументов
        JSONObject rawResponse = api.fetchPageRaw(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, rule, page, ps);

        JSONArray rawIssues = rawResponse.optJSONArray("issues");
        JSONArray keys = new JSONArray();
        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                keys.put(rawIssues.getJSONObject(i).optString("key"));
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
        o.put("keys", keys);

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/ListPromptsHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.PromptTemplate;
import ru.miacomsoft.sonar.server.ServerState;

/**
 * GET /api/prompt/list
 *
 * Диагностика: показывает, какой каталог промптов видит сервер
 * и какие .txt в нём лежат. Помогает отличить «маршрут не работает»
 * от «файла нет».
 */
public class ListPromptsHandler extends BaseHandler {

    private final PromptTemplate template;

    public ListPromptsHandler(ServerState state, PromptTemplate template) {
        super(state);
        this.template = template;
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        JSONObject o = new JSONObject();
        String abs;
        boolean dirExists;
        try {
            abs = template.getPromptsDir().toAbsolutePath().normalize().toString();
            dirExists = java.nio.file.Files.isDirectory(template.getPromptsDir());
        } catch (Exception e) {
            abs = String.valueOf(template.getPromptsDir());
            dirExists = false;
        }
        o.put("dir", abs);
        o.put("dirExists", dirExists);

        JSONArray names = new JSONArray();
        for (String n : template.listAvailable()) names.put(n);
        o.put("prompts", names);
        o.put("count", names.length());

        sendJson(ex, 200, o);
    }
}
```

## server/handlers/SessionHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.SessionManager;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * GET /api/session  — прочитать session.properties.
 * PUT /api/session  — перезаписать session.properties + перезагрузить,
 *                     затем (по умолчанию) запустить async-пересбор снимка.
 *
 * Тело PUT — полный текст session.properties.
 */
public class SessionHandler extends BaseHandler {

    public SessionHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) readSession(ex);
        else writeSession(ex);
    }

    private void readSession(HttpExchange ex) throws Exception {
        String raw;
        try {
            raw = state.readRawFile();
        } catch (Exception e) {
            sendError(ex, 500, "Не удалось прочитать " + state.getSessionFile()
                    + ": " + e.getMessage());
            return;
        }

        JSONObject o = new JSONObject();
        o.put("path", state.getSessionFile() == null
                ? "" : state.getSessionFile().toString());
        o.put("exists", !raw.isEmpty());

        if (!raw.isEmpty()) {
            try {
                Properties p = new Properties();
                try (StringReader sr = new StringReader(raw)) { p.load(sr); }

                o.put("url",       p.getProperty("sonar.url", ""));
                o.put("user",      p.getProperty("sonar.user", ""));
                o.put("authType",  p.getProperty("sonar.auth", "bearer"));
                o.put("hasToken",  !p.getProperty("sonar.token", "").isBlank());
                o.put("hasCookie", !p.getProperty("sonar.cookie", "").isBlank());

                // raw отдаём, но с замаскированными секретами
                String redacted = raw
                        .replaceAll("(?m)^(\\s*sonar\\.token\\s*=).*$", "$1***REDACTED***")
                        .replaceAll("(?m)^(\\s*sonar\\.cookie\\s*=).*$", "$1***REDACTED***");
                o.put("raw", redacted);
            } catch (Exception e) {
                o.put("parseError", e.getMessage());
            }
        } else {
            o.put("raw", "");
        }
        sendJson(ex, 200, o);
    }

    private void writeSession(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);
        boolean build = paramBool(q, "build", true);
        String project = param(q, "project", null);

        byte[] bodyBytes = ex.getRequestBody().readAllBytes();
        String raw = new String(bodyBytes, StandardCharsets.UTF_8);
        if (raw == null || raw.isBlank()) {
            sendError(ex, 400, "Пустое тело запроса. Передайте текст session.properties");
            return;
        }

        try {
            state.update(raw);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendError(ex, 400, "Невалидный session.properties: " + e.getMessage());
            return;
        } catch (Exception e) {
            sendError(ex, 500, "Не удалось записать " + state.getSessionFile()
                    + ": " + e.getMessage());
            return;
        }

        String targetProject = (project != null && !project.isEmpty())
                ? project : state.getDefaultProject();
        boolean buildStarted = false;
        if (build && targetProject != null && !targetProject.isEmpty()) {
            try { buildStarted = state.buildSnapshotAsync(targetProject); }
            catch (Exception e) {
                System.err.println("[SessionHandler] async build not started: " + e.getMessage());
            }
        }

        SessionManager sm = state.getSession();
        JSONObject o = new JSONObject();
        o.put("saved", true);
        o.put("path", state.getSessionFile().toString());
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
        } else if (build) {
            o.put("note", "build не запущен: project не задан (укажите ?project= или --project=)");
        }
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/SummaryHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class SummaryHandler extends BaseHandler {

    public SummaryHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String sev = param(q, "sev", "MEDIUM");
        String qual = param(q, "qual", "MAINTAINABILITY");

        int total;
        Map<String, Integer> byRules;
        Map<String, Integer> byFiles;
        Map<String, Integer> bySev;

        if ("SECURITY_HOTSPOT".equalsIgnoreCase(qual)) {
            // Hotspots живут в /api/hotspots/search, не в issues.
            // Снимок их не содержит — тянем live.
            List<Issue> list = newApi(project).fetchHotspots(sev);

            total = list.size();
            byRules = new TreeMap<>();
            byFiles = new TreeMap<>();
            bySev   = new TreeMap<>();

            for (Issue i : list) {
                if (i.rule != null && !i.rule.isEmpty()) {
                    byRules.merge(i.rule, 1, Integer::sum);
                }
                if (i.component != null && !i.component.isEmpty()) {
                    byFiles.merge(i.component, 1, Integer::sum);
                }
                String key = (i.severity != null && !i.severity.isEmpty())
                        ? i.severity
                        : "UNKNOWN";
                bySev.merge(key, 1, Integer::sum);
            }
        } else {
            Snapshot snap = snapshotFor(project);
            if (snap != null) {
                List<Issue> filtered = snap.filter(sev, qual);
                total   = filtered.size();
                byRules = snap.facetRules(sev, qual);
                byFiles = snap.facetFiles(sev, qual);
                bySev   = snap.facetSeverities(sev, qual);
            } else {
                SonarIssuesApi api = newApi(project);
                total   = api.count(sev, qual);
                byRules = api.facet(sev, qual, "rules");
                byFiles = api.facet(sev, qual, "files");
                bySev   = api.facet(sev, qual, "severities");
            }
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("total", total);
        o.put("byRules", new JSONObject(byRules));
        o.put("byFiles", new JSONObject(byFiles));
        o.put("bySeverities", new JSONObject(bySev));
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/TeamMappingHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.TeamMapping;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GET    /api/team_mapping           — скачать CSV (text/csv)
 * GET    /api/team_mapping?info=1    — статистика в JSON
 * PUT    /api/team_mapping           — загрузить CSV (text/plain)
 * POST   /api/team_mapping           — то же, что PUT (алиас)
 * DELETE /api/team_mapping           — удалить CSV
 */
public class TeamMappingHandler extends BaseHandler {

    private static final Path FILE_PATH = Path.of("team_mapping.csv").toAbsolutePath().normalize();

    public TeamMappingHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "POST".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();

        if ("GET".equalsIgnoreCase(method)) {
            handleGet(ex);
        } else if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)) {
            handlePut(ex);
        } else {
            handleDelete(ex);
        }
    }

    // ============================================================
    // GET
    // ============================================================

    private void handleGet(HttpExchange ex) throws Exception {
        String query = ex.getRequestURI().getQuery();
        boolean infoMode = query != null && (
                query.contains("info=1") || query.contains("info=true") || query.contains("format=json"));

        if (infoMode) {
            sendStatsJson(ex);
            return;
        }

        if (!Files.exists(FILE_PATH)) {
            sendJson(ex, 404, new JSONObject().put("error", "Файл не найден"));
            return;
        }

        byte[] bytes = Files.readAllBytes(FILE_PATH);
        ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"team_mapping.csv\"");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendStatsJson(HttpExchange ex) throws Exception {
        JSONObject o = new JSONObject();
        o.put("exists", Files.isRegularFile(FILE_PATH));
        o.put("path",   FILE_PATH.toString());
        o.put("fileSize", Files.isRegularFile(FILE_PATH) ? Files.size(FILE_PATH) : 0);

        if (Files.isRegularFile(FILE_PATH)) {
            TeamMapping tm = TeamMapping.load();
            o.put("totalRows",         tm.getTotalRows());
            o.put("rowsWithTeam",      tm.getRowsWithTeam());
            o.put("rowsWithComponent", tm.getRowsWithComponent());
            o.put("rowsWithBoth",      tm.getRowsWithBoth());
            o.put("pathCount",         tm.getPathCount());
        }
        sendJson(ex, 200, o);
    }

    // ============================================================
    // PUT / POST
    // ============================================================

    private void handlePut(HttpExchange ex) throws Exception {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            sendError(ex, 400, "Пустое тело запроса");
            return;
        }

        Files.write(FILE_PATH, body);

        TeamMapping tm = TeamMapping.load();

        JSONObject stats = new JSONObject();
        stats.put("totalRows",         tm.getTotalRows());
        stats.put("rowsWithTeam",      tm.getRowsWithTeam());
        stats.put("rowsWithComponent", tm.getRowsWithComponent());
        stats.put("rowsWithBoth",      tm.getRowsWithBoth());
        stats.put("pathCount",         tm.getPathCount());

        JSONObject resp = new JSONObject();
        resp.put("saved",    true);
        resp.put("path",     FILE_PATH.toString());
        resp.put("fileSize", body.length);
        resp.put("stats",    stats);

        sendJson(ex, 200, resp);
    }

    // ============================================================
    // DELETE
    // ============================================================

    private void handleDelete(HttpExchange ex) throws Exception {
        Files.deleteIfExists(FILE_PATH);
        sendJson(ex, 200, new JSONObject().put("deleted", true));
    }
}
```

## server/ollama/OllamaResponse.java

```
package ru.miacomsoft.sonar.server.ollama;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OllamaResponse {

    private static final Pattern STATUS_RE =
            Pattern.compile("Статус:\\s*(accept|falsepositive|confirm|resolve|wontfix)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS_RE =
            Pattern.compile("Теги:\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);

    public String       status       = "";
    public String       comment      = "";
    public List<String> tags         = new ArrayList<>();
    public String       fixDecision  = "require-fix";
    public String       raw          = "";

    public static OllamaResponse parse(String raw) {
        OllamaResponse r = new OllamaResponse();
        if (raw == null) raw = "";
        r.raw = raw;

        String cleaned = raw.replaceAll("[*`_]", "");

        Matcher sm = STATUS_RE.matcher(cleaned);
        if (sm.find()) r.status = sm.group(1).toLowerCase();

        List<String> rawTags = new ArrayList<>();
        Matcher tm = TAGS_RE.matcher(cleaned);
        if (tm.find()) {
            String tagsStr = tm.group(1);
            for (String t : tagsStr.split("[,;\\s]+")) {
                String v = t.replaceAll("[*`_]", "").trim().toLowerCase();
                if (!v.isEmpty()) rawTags.add(v);
            }
        }

        boolean hasRequire    = rawTags.contains("require-fix");
        boolean hasNotRequire = rawTags.contains("not-require-fix");
        rawTags.removeIf(t -> t.equals("require-fix") || t.equals("not-require-fix"));

        if (hasRequire && !hasNotRequire)      r.fixDecision = "require-fix";
        else if (hasNotRequire && !hasRequire) r.fixDecision = "not-require-fix";
        else                                    r.fixDecision = "require-fix";

        r.tags.add(r.fixDecision);
        for (String t : rawTags) {
            if (r.tags.size() >= 3) break;
            r.tags.add(t);
        }

        StringBuilder cb = new StringBuilder();
        boolean inComment = false;
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("Статус:") || t.startsWith("Теги:")) continue;
            if (t.startsWith("Критичность:")) {
                inComment = true;
                cb.append(line).append('\n');
                continue;
            }
            if (inComment) cb.append(line).append('\n');
        }
        r.comment = cb.toString().trim();

        if (r.comment.isEmpty()) {
            StringBuilder alt = new StringBuilder();
            for (String line : raw.split("\\r?\\n")) {
                String t = line.trim();
                if (t.startsWith("Статус:") || t.startsWith("Теги:")) continue;
                alt.append(line).append('\n');
            }
            r.comment = alt.toString().trim();
        }
        return r;
    }
}
```

