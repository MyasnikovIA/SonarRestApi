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
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class ExportStreamHandler extends BaseHandler {

    private static final int SONAR_PAGE_LIMIT = 10000;

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
                + ", keys=" + (keys == null ? "нет" : keys.size()));

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        try {
            if (keys != null && !keys.isEmpty()) {
                runExportByKeys(os, project, format, keys, sev, qual);
            } else {
                runExport(os, project, format, sev, qual, rule, hideProcessed,
                        tagsParam, excludedTagsParam, selectedRulesParam, filterText, statusFilter);
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

    /** Потокобезопасная отправка SSE-события. */
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
                // Клиент отключился — не спамим
            } else {
                System.err.println("[ExportStream] Ошибка отправки события: " + m);
            }
        }
    }

    private void sendProgress(OutputStream os, long startMs, String stage,
                              int current, int total, String message) {
        long elapsedMs = System.currentTimeMillis() - startMs;
        if (total > 0 && current > total) current = total;
        int displayCurrent = (total > 0) ? Math.min(current, total) : current;
        try {
            sendEvent(os, "progress", new JSONObject()
                    .put("stage", stage)
                    .put("total", total)
                    .put("current", displayCurrent)
                    .put("elapsedMs", elapsedMs)
                    .put("elapsedText", formatDuration(elapsedMs))
                    .put("message", message));
        } catch (Exception ignored) {}
    }

    private static boolean is10000LimitError(Throwable e) {
        return e != null && e.getMessage() != null && e.getMessage().contains("10000");
    }

    private static boolean isZipFormat(String format) {
        return "html-files".equals(format) || "html-files-teams".equals(format);
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

    /**
     * Фаза загрузки сниппетов (только ZIP-режим). Параллельно до 8 потоков.
     * Пополняет issueSnippets. Шлёт SSE progress каждые 25 issues.
     */
    private void fetchSnippetsParallel(OutputStream os, long startMs, String project,
                                       Map<String, List<Issue>> issuesByFile,
                                       Map<String, List<String>> issueSnippets,
                                       int snippetCurrent, int snippetTotal) {
        if (issuesByFile == null || issuesByFile.isEmpty()) return;
        int totalForSnippets = 0;
        for (List<Issue> lst : issuesByFile.values()) totalForSnippets += lst.size();
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

        for (List<Issue> lst : issuesByFile.values()) {
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

    private static void appendIssue(boolean zipMode,
                                    StringBuilder reportContent,
                                    Map<String, List<Issue>> issuesByFile,
                                    String format, Issue it, String project, String host) {
        if (zipMode) {
            String comp = it.component != null ? it.component : "(unknown)";
            issuesByFile.computeIfAbsent(comp, k -> new ArrayList<>()).add(it);
        } else if ("jira".equals(format)) reportContent.append(JiraExporter.exportSingle(it, project, host));
        else if ("md".equals(format)) reportContent.append(MdExporter.exportSingle(it, project, host));
        else if ("txt".equals(format)) reportContent.append(TxtExporter.exportSingle(it, project, host));
        else if ("html".equals(format)) reportContent.append(HtmlExporter.exportSingle(it, project, host));
        else if ("confluence".equals(format)) reportContent.append(ConfluenceExporter.exportSingle(it, project, host));
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

    // =========================================================
    // runExportByKeys
    // =========================================================
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
        sendProgress(os, startMs, "start", 0, total, "Начинаем сбор по " + total + " ключам…");

        StringBuilder reportContent = new StringBuilder();
        Map<String, List<Issue>> issuesByFile = zipMode ? new TreeMap<>() : null;
        Map<String, List<String>> issueSnippets = zipMode ? new HashMap<>() : null;
        int loadedCount = 0;
        Set<String> seenKeys = new HashSet<>();

        if (!zipMode) {
            if ("jira".equals(format)) reportContent.append("h3. Проект: ").append(project).append("\n\n");
            else if ("md".equals(format)) reportContent.append("# Отчет по проекту: ").append(project).append("\n\n");
            else if ("txt".equals(format)) reportContent.append("=== Отчет по проекту: ").append(project).append(" ===\n\n");
            else if ("html".equals(format)) {
                reportContent.append("<!DOCTYPE html><html><head><meta charset='utf-8'></head><body>\n");
                reportContent.append("<h1>Проект: ").append(project).append("</h1>\n");
            } else if ("confluence".equals(format)) {
                reportContent.append("<h2>Проект: ").append(project).append("</h2>\n");
            }
        }

        int batchSize = 100;
        for (int i = 0; i < uniqueKeys.size(); i += batchSize) {
            List<String> batch = uniqueKeys.subList(i, Math.min(i + batchSize, uniqueKeys.size()));
            String keysCsv = String.join(",", batch);
            try {
                JSONObject raw = api.fetchIssuesByKeys(keysCsv);
                JSONArray arr = raw.optJSONArray("issues");
                if (arr != null) {
                    for (int j = 0; j < arr.length(); j++) {
                        Issue it = Issue.from(arr.getJSONObject(j), project);
                        if (!seenKeys.add(it.key)) continue;
                        appendIssue(zipMode, reportContent, issuesByFile, format, it, project, host);
                        loadedCount++;
                    }
                }
            } catch (Exception e) {
                System.err.println("[ExportStream] Ошибка при запросе пачки ключей: " + e.getMessage());
            }
            sendProgress(os, startMs, "fetching", loadedCount, total,
                    "Пакет " + (i / batchSize + 1) + " из " + ((uniqueKeys.size() + batchSize - 1) / batchSize));
        }

        // ZIP: фаза сниппетов
        if (zipMode) {
            fetchSnippetsParallel(os, startMs, project, issuesByFile, issueSnippets, loadedCount, total);
        }

        long totalMs = System.currentTimeMillis() - startMs;
        Path tempFile;

        if (zipMode) {
            boolean useTeams = "html-files-teams".equals(format);
            TeamMapping mapping = useTeams ? TeamMapping.load() : null;
            tempFile = Files.createTempFile("sonar_report_", ".zip");
            int entries = HtmlPerFileExporter.writeZip(tempFile, issuesByFile, issueSnippets,
                    project, host, useTeams, mapping);
            System.out.println("[ExportStream] runExportByKeys ZIP: " + entries + " файлов, "
                    + loadedCount + " issues, " + Files.size(tempFile) + " байт, "
                    + "сниппетов " + issueSnippets.size() + ", за " + formatDuration(totalMs));
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

        sendEvent(os, "complete", new JSONObject()
                .put("file", tempFile.toAbsolutePath().toString())
                .put("total", loadedCount)
                .put("expectedTotal", total)
                .put("elapsedMs", totalMs)
                .put("elapsedText", formatDuration(totalMs))
                .put("message", "Файл сформирован за " + formatDuration(totalMs)));
    }

    // =========================================================
    // runExport
    // =========================================================
    private void runExport(OutputStream os, String project, String format,
                           String sev, String qual, String rule, boolean hideProcessed,
                           String tagsParam, String excludedTagsParam,
                           String selectedRulesParam, String filterText,
                           String statusFilter) throws Exception {
        long startMs = System.currentTimeMillis();
        SonarIssuesApi api = state.newApi(project);
        String host = state.getSession() != null ? state.getSession().getHost() : "sonar.bars.group";

        String statuses = hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : null;
        boolean zipMode = isZipFormat(format);

        System.out.println("[ExportStream] Фильтры: sev=" + sev + ", qual=" + qual
                + ", rule=" + (rule == null ? "нет" : rule)
                + ", hideProcessed=" + hideProcessed
                + ", tags=" + tagsParam + ", excludedTags=" + excludedTagsParam
                + ", selectedRules=" + selectedRulesParam
                + ", filterText=" + filterText + ", statusFilter=" + statusFilter
                + (zipMode ? " [ZIP MODE]" : ""));

        sendProgress(os, startMs, "count", 0, 0, "Подсчет общего количества замечаний…");

        JSONObject firstRaw = api.fetchPageRaw(statuses, sev, qual, rule, 1, 1);
        int total = firstRaw.optInt("total", 0);
        if (total == 0) {
            sendEvent(os, "error", new JSONObject().put("message", "Нет замечаний для выгрузки"));
            return;
        }

        System.out.println("[ExportStream] Всего замечаний (до post-фильтров): " + total
                + " (подсчёт занял " + formatDuration(System.currentTimeMillis() - startMs) + ")");

        sendProgress(os, startMs, "start", 0, total,
                "Начинаем сбор данных (" + total + " замечаний до post-фильтров)…");

        StringBuilder reportContent = new StringBuilder();
        Map<String, List<Issue>> issuesByFile = zipMode ? new TreeMap<>() : null;
        Map<String, List<String>> issueSnippets = zipMode ? new HashMap<>() : null;
        int loadedCount = 0;
        Set<String> seenKeys = new HashSet<>();

        if (!zipMode) {
            if ("jira".equals(format)) reportContent.append("h3. Проект: ").append(project).append("\n\n");
            else if ("md".equals(format)) reportContent.append("# Отчет по проекту: ").append(project).append("\n\n");
            else if ("txt".equals(format)) reportContent.append("=== Отчет по проекту: ").append(project).append(" ===\n\n");
            else if ("html".equals(format)) {
                reportContent.append("<!DOCTYPE html><html><head><meta charset='utf-8'></head><body>\n");
                reportContent.append("<h1>Проект: ").append(project).append("</h1>\n");
            } else if ("confluence".equals(format)) {
                reportContent.append("<h2>Проект: ").append(project).append("</h2>\n");
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
            } catch (Exception e) {
                System.err.println("[ExportStream] Не удалось получить фасеты правил: " + e.getMessage());
                rulesToProcess.add(null);
                ruleCounts.put(null, total);
            }
        }

        List<String> projectFiles = null;

        for (String currentRule : rulesToProcess) {
            long ruleStartMs = System.currentTimeMillis();
            int expectedCount = ruleCounts.getOrDefault(currentRule, -1);

            System.out.println("[ExportStream] ▶ Обработка правила: "
                    + (currentRule == null ? "все" : currentRule)
                    + (expectedCount >= 0 ? " (ожидается " + expectedCount + ")" : ""));

            boolean needFileSplit = expectedCount > SONAR_PAGE_LIMIT;
            int ruleTotal = expectedCount > 0 ? expectedCount : -1;
            int ruleLoaded = 0;
            int pageSize = SonarIssuesApi.FETCH_PAGE_SIZE;

            if (needFileSplit) {
                System.out.println("[ExportStream] Правило " + currentRule + " имеет " + expectedCount
                        + " issues > " + SONAR_PAGE_LIMIT + " → сразу file-split.");
            } else {
                int page = 1;
                while (true) {
                    try {
                        JSONObject raw = api.fetchPageRaw(statuses, sev, qual, currentRule, page, pageSize);
                        if (ruleTotal < 0) {
                            ruleTotal = raw.optInt("total", 0);
                            System.out.println("[ExportStream] Правило " + currentRule
                                    + ": всего " + ruleTotal + " замечаний");
                        }
                        JSONArray arr = raw.optJSONArray("issues");
                        if (arr == null || arr.length() == 0) break;

                        for (int i = 0; i < arr.length(); i++) {
                            Issue it = Issue.from(arr.getJSONObject(i), project);
                            if (shouldSkip(it, selectedTags, excludedTags, selectedRules, qText, statusFilter)) continue;
                            if (!seenKeys.add(it.key)) continue;
                            appendIssue(zipMode, reportContent, issuesByFile, format, it, project, host);
                            loadedCount++;
                            ruleLoaded++;
                        }

                        sendProgress(os, startMs, "fetching", loadedCount, total,
                                "Правило: " + (currentRule == null ? "все" : currentRule));
                        page++;

                        if (ruleTotal > 0 && ruleLoaded >= ruleTotal) break;

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

                for (String file : filesToProcess) {
                    int filePage = 1;
                    int fileTotal = -1;
                    while (true) {
                        try {
                            JSONObject raw = api.fetchPageRawForComponent(statuses, sev, qual,
                                    currentRule, file, filePage, pageSize);
                            if (fileTotal < 0) fileTotal = raw.optInt("total", 0);

                            JSONArray arr = raw.optJSONArray("issues");
                            if (arr == null || arr.length() == 0) break;

                            for (int i = 0; i < arr.length(); i++) {
                                Issue it = Issue.from(arr.getJSONObject(i), project);
                                if (shouldSkip(it, selectedTags, excludedTags, selectedRules, qText, statusFilter)) continue;
                                if (!seenKeys.add(it.key)) continue;
                                appendIssue(zipMode, reportContent, issuesByFile, format, it, project, host);
                                loadedCount++;
                                ruleLoaded++;
                            }
                            filePage++;

                            sendProgress(os, startMs, "fetching", loadedCount, total,
                                    "Правило: " + currentRule + " · файл: " + file
                                            + (ruleTotal > 0 ? " (" + ruleLoaded + "/" + ruleTotal + ")" : ""));

                            if (fileTotal > 0 && filePage * pageSize > fileTotal) break;

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
            System.out.println("[ExportStream] ✔ Правило " + (currentRule == null ? "все" : currentRule)
                    + ": собрано " + ruleLoaded + (ruleTotal > 0 ? "/" + ruleTotal : "")
                    + " issues за " + formatDuration(ruleMs));
        }

        // ZIP: фаза сниппетов
        if (zipMode) {
            fetchSnippetsParallel(os, startMs, project, issuesByFile, issueSnippets, loadedCount, total);
        }

        long totalMs = System.currentTimeMillis() - startMs;
        Path tempFile;

        if (zipMode) {
            boolean useTeams = "html-files-teams".equals(format);
            TeamMapping mapping = useTeams ? TeamMapping.load() : null;
            tempFile = Files.createTempFile("sonar_report_", ".zip");
            int entries = HtmlPerFileExporter.writeZip(tempFile, issuesByFile, issueSnippets,
                    project, host, useTeams, mapping);
            System.out.println("[ExportStream] runExport ZIP: " + entries + " файлов, "
                    + loadedCount + " issues, " + Files.size(tempFile) + " байт, "
                    + "сниппетов " + issueSnippets.size() + ", за " + formatDuration(totalMs));
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

        sendEvent(os, "complete", new JSONObject()
                .put("file", tempFile.toAbsolutePath().toString())
                .put("total", loadedCount)
                .put("expectedTotal", total)
                .put("elapsedMs", totalMs)
                .put("elapsedText", formatDuration(totalMs))
                .put("message", "Файл сформирован за " + formatDuration(totalMs)));
    }
}