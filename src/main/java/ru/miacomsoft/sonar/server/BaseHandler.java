package ru.miacomsoft.sonar.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
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
        return p;
    }

    // ================= snapshot-aware helpers =================

    protected SonarIssuesApi newApi(String projectKey) {
        return state.newApi(projectKey);
    }

    protected SonarBulkApi newBulk() {
        return state.newBulk();
    }

    /** Возвращает Snapshot, если он построен именно для этого проекта, иначе null. */
    protected Snapshot snapshotFor(String projectKey) {
        Snapshot s = state.getSnapshot();
        if (s != null && s.projectKey.equals(projectKey)) return s;
        return null;
    }

    /** Получить список issues: из snapshot если есть, иначе live. */
    protected List<Issue> fetchIssues(String project, String sev, String qual) throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) return s.filter(sev, qual);
        return newApi(project).fetchAll(sev, qual);
    }

    /** Получить issues, сгруппированные по файлам. */
    protected Map<String, List<Issue>> fetchIssuesByFile(String project, String sev, String qual)
            throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) return s.filterByFile(sev, qual);
        return newApi(project).issuesByFile(sev, qual);
    }

    /** Facet (rules/files/severities) — из snapshot или live. */
    protected Map<String, Integer> fetchFacet(String project, String sev, String qual, String facet)
            throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) {
            switch (facet) {
                case "rules":      return s.facetRules(sev, qual);
                case "files":      return s.facetFiles(sev, qual);
                case "severities": return s.facetSeverities(sev, qual);
                default:           break;
            }
        }
        return newApi(project).facet(sev, qual, facet);
    }

    /** Сниппет с приоритетом snapshot-кэша, fallback на live. */
    protected List<String> fetchSnippetCached(String project, String issueKey) throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) {
            List<String> cached = s.snippet(issueKey);
            if (!cached.isEmpty()) return cached;
        }
        return newApi(project).fetchSnippet(issueKey);
    }

    /** Удобно добавить в JSON-ответ сведения об источнике данных. */
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
}