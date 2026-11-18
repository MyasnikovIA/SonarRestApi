package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
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
 *   "statuses":     "OPEN,CONFIRMED"     // опционально
 * }
 *
 * Ответ — SSE-поток:
 *   event: progress  data: {stage, message, current, total, projectIndex, projectTotal, projectKey, elapsedMs}
 *   event: complete  data: {file, downloadName, total, elapsedMs}
 *   event: error     data: {message}
 *
 * По завершении (event: complete) — HTML-файл уже лежит на сервере,
 * клиент скачивает его через /api/export-download?file=...
 */
public class StatsProjectsHandler extends BaseHandler {

    private static final int PAGE_SIZE       = 500;   // лимит SonarQube
    private static final int CHUNK_SIZE      = 3000;  // размер «фрагмента» для логов/прогресса

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

        Set<String> includedTags = parseTags(req.optJSONArray("includedTags"));
        Set<String> excludedTags = parseTags(req.optJSONArray("excludedTags"));

        String statusesParam = req.optString("statuses", SonarIssuesApi.ACTIVE_STATUSES);
        final String statuses = (statusesParam == null || statusesParam.isBlank())
                ? SonarIssuesApi.ACTIVE_STATUSES
                : statusesParam.trim();

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
                            includedTags, excludedTags,
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
            response.put("statuses", statuses);
            response.put("projectsRequested", projects.size());
            response.put("projectsOk", okProjects);
            response.put("projectsFailed", projects.size() - okProjects);
            response.put("projects", byProject);
            response.put("totals", aggregated.toJson());

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

    private Set<String> parseTags(JSONArray arr) {
        Set<String> tags = new LinkedHashSet<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String t = arr.optString(i, "").trim();
                if (!t.isEmpty()) tags.add(t);
            }
        }
        return tags;
    }

    private JSONObject computeForProject(
            String projectKey, String statuses,
            Set<String> includedTags, Set<String> excludedTags,
            OutputStream out, int projIdx, int projTotal, long startMs
    ) throws Exception {

        SonarIssuesApi api = state.newApi(projectKey);

        // Прогресс-слушатель: эмитит SSE-события по каждой странице (500 issues).
        // SonarIssuesApi внутри сам разбивает на страницы и при >10000 — по файлам.
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

            if (it.component != null && !it.component.isEmpty()) {
                files.add(it.component);
            }
        }

        stats.totalFiles = files.size();

        // Финальный прогресс по проекту
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

    private boolean matchesTagFilter(Issue it, Set<String> included, Set<String> excluded) {
        List<String> itTags = it.tags != null ? it.tags : Collections.emptyList();

        if (!included.isEmpty()) {
            boolean has = false;
            for (String t : included) {
                if (itTags.contains(t)) { has = true; break; }
            }
            if (!has) return false;
        }

        if (!excluded.isEmpty()) {
            for (String t : excluded) {
                if (itTags.contains(t)) return false;
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
    // Аккумулятор (как было)
    // ============================================================

    private static class Stats {
        int totalIssues;
        int totalFiles;
        final Map<String, Integer> bySeverity = new TreeMap<>();
        final Map<String, Integer> byQuality  = new TreeMap<>();
        final Map<String, Integer> byTag      = new TreeMap<>();

        void merge(JSONObject other) {
            if (other == null) return;
            totalIssues += other.optInt("totalIssues", 0);
            totalFiles  += other.optInt("totalFiles", 0);
            mergeMap(other.optJSONObject("bySeverity"), bySeverity);
            mergeMap(other.optJSONObject("byQuality"),  byQuality);
            mergeMap(other.optJSONObject("byTag"),      byTag);
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
            return o;
        }
    }
}