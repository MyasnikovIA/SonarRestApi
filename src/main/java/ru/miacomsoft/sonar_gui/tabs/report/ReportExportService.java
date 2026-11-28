package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;
import ru.miacomsoft.sonar_gui.backend.TeamMapping;
import ru.miacomsoft.sonar_gui.backend.exporters.*;
import ru.miacomsoft.sonar_gui.common.GuiContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;

public class ReportExportService {

    public interface ProgressListener {
        void onProgress(int current, int total, String stage, String message);
        void onDone(Path file, String suggestedName);
        void onError(Throwable t);
    }

    public static class BaseFilter {
        public String project;
        public String severity = "ALL";
        public String quality  = "ALL";
        public boolean hideProcessed = false;
        public Set<String> tags         = new LinkedHashSet<>();
        public Set<String> excludedTags = new LinkedHashSet<>();
        public Set<String> rules        = new LinkedHashSet<>();
        public String filterText        = "";
        public String statusFilter      = "ALL";
        public int expectedTotal        = -1;
        public List<String> selectedProjects = new ArrayList<>();
        public String outputFilePath = null;
        public boolean createJiraTasks = false;
    }

    private static final String NO_TAGS_MARKER = "__NO_TAGS__";
    private static final int PAGE_SIZE = 3000;
    private static final int SONAR_HARD_LIMIT = 10000;

    private final GuiContext ctx;
    private final ProgressListener listener;
    private volatile boolean cancelled = false;
    private long startTimeMs;

    public ReportExportService(GuiContext ctx, ProgressListener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void setCancelled(boolean c) { this.cancelled = c; }
    public boolean isCancelled() { return cancelled; }

    private String elapsedStr() {
        if (startTimeMs == 0) return "00:00";
        long ms = System.currentTimeMillis() - startTimeMs;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format("%02d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    private void log(String msg) {
        System.out.println("[" + elapsedStr() + "] " + msg);
    }

    private void logErr(String msg) {
        System.err.println("[" + elapsedStr() + "] " + msg);
    }

    public void export(ReportExportType type, List<String> keysCsv, BaseFilter filter, Path targetDir) {
        startTimeMs = System.currentTimeMillis();
        log("[ReportExportService] ▶ Запуск экспорта: " + type + (targetDir != null ? " → " + targetDir : ""));
        try {
            doExport(type, keysCsv, filter, targetDir);
        } catch (Throwable t) {
            if (cancelled || (t instanceof InterruptedException && "cancelled".equals(t.getMessage()))) {
                log("[ReportExportService] ⏹ Экспорт отменен пользователем.");
                return;
            }
            logErr("[ReportExportService] ✖ Критическая ошибка экспорта: " + t.getMessage());
            t.printStackTrace();
            if (listener != null) listener.onError(t);
        }
    }

    private void doExport(ReportExportType type, List<String> keysCsv, BaseFilter filter, Path targetDir) throws Exception {
        if (!ctx.isSessionReady()) {
            throw new IllegalStateException("Сессия SonarQube не готова");
        }

        List<String> failedProjects = new ArrayList<>();

        Path outPath;
        if (type == ReportExportType.MULTI_PROJECT_SUMMARY) {
            if (filter == null || filter.selectedProjects == null || filter.selectedProjects.isEmpty()) {
                throw new IllegalArgumentException("Не выбрано ни одного проекта для сводного отчета.");
            }
            Path outputPath = null;
            if (filter.outputFilePath != null && !filter.outputFilePath.isBlank()) {
                outputPath = Path.of(filter.outputFilePath);
            }
            outPath = exportMultiProjectSummary(filter.selectedProjects, outputPath, filter, failedProjects);
        } else if (type == ReportExportType.QUAL_TEAM_PROJECT_HTML) {
            if (filter == null || filter.selectedProjects == null || filter.selectedProjects.isEmpty()) {
                throw new IllegalArgumentException("Не выбрано ни одного проекта.");
            }
            if (targetDir == null) {
                throw new IllegalArgumentException("Не указан каталог сохранения.");
            }
            outPath = exportQualProjectHtml(filter.selectedProjects, targetDir, filter, failedProjects);
        } else {
            List<Issue> issues;
            if (keysCsv != null && !keysCsv.isEmpty()) {
                log("[ReportExportService] Экспорт по списку ключей (" + keysCsv.size() + " шт)...");
                issues = loadByKeys(filter != null ? filter.project : ctx.getProject(), keysCsv);
            } else {
                if (filter == null) throw new IllegalArgumentException("Нет ни keys, ни фильтров");
                log("[ReportExportService] Экспорт по фильтрам (постранично)…");
                issues = collectByFilters(filter);
            }

            if (cancelled) return;
            if (issues.isEmpty()) {
                logErr("[ReportExportService] Нет данных для экспорта.");
                if (listener != null) listener.onError(new IllegalStateException("Нет данных"));
                return;
            }

            log("[ReportExportService] Собрано " + issues.size() + " issues. Начало генерации...");

            switch (type) {
                case JIRA, MARKDOWN, TXT, CONFLUENCE -> outPath = exportTextFormat(type, issues);
                case HTML_FILES                       -> outPath = exportHtml(issues, false, targetDir);
                case HTML_FILES_TEAMS                 -> outPath = exportHtml(issues, true, targetDir);
                case TEAM_HTML                        -> outPath = exportTeamHtml(issues, targetDir);
                case QUAL_TEAM_HTML                   -> outPath = exportQualTeamHtml(issues, targetDir);
                case QUAL_SUMMARY                     -> outPath = exportQualSummary(issues);
                case LLM_MD_ZIP                       -> outPath = exportLlmMd(issues, targetDir);
                case TEAM_MODULE_ZIP                  -> outPath = exportTeamModule(issues, targetDir);
                default -> throw new IllegalArgumentException("Неизвестный формат: " + type);
            }
        }

        if (cancelled) {
            log("[ReportExportService] Отмена после генерации.");
            if (targetDir == null) {
                try { Files.deleteIfExists(outPath); } catch (Exception ignored) {}
            }
            return;
        }

        log("[ReportExportService] ✔ Экспорт завершен за " + elapsedStr() + ". Результат: " + outPath);
        if (listener != null) {
            listener.onDone(outPath, suggestedName(type, filter != null ? filter.project : ctx.getProject()));
        }
    }

    // ============ MULTI-PROJECT SUMMARY (12) ============

    private Path exportMultiProjectSummary(List<String> projectKeys, Path outputFilePath, BaseFilter filter, List<String> failedProjects) throws Exception {
        String host = hostOf();
        String[] qualities = {"SECURITY", "RELIABILITY", "MAINTAINABILITY", "SECURITY_HOTSPOT"};
        String[] severities = {"BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"};

        StringBuilder html = new StringBuilder();
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());

        html.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Сводный отчет по проектам</title>\n");
        html.append("<style>\n");
        html.append("body{font-family:system-ui,sans-serif;max-width:1200px;margin:0 auto;padding:20px;background:#fafbfc;color:#172b4d;}\n");
        html.append("h1,h2,h3{color:#0052cc;}\n");
        html.append(".project-block{background:#fff;border:1px solid #dfe1e6;border-radius:8px;padding:20px;margin-bottom:30px;box-shadow:0 1px 3px rgba(0,0,0,.05);}\n");
        html.append(".qual-block{margin-top:15px;padding-left:15px;border-left:3px solid #dfe1e6;}\n");
        html.append(".sev-row{margin:8px 0 8px 20px;padding:8px 12px;background:#f4f5f7;border-radius:4px;display:flex;justify-content:space-between;align-items:center;}\n");
        html.append(".sev-name{font-weight:600;color:#172b4d;width:120px;}\n");
        html.append(".sev-stats{color:#6b778c;}\n");
        html.append(".count{font-weight:700;color:#0052cc;margin-right:15px;}\n");
        html.append(".error-block{background:#ffebe6;border:1px solid #ff8f73;border-radius:8px;padding:20px;margin-top:30px;}\n");
        html.append(".error-block h2{color:#de350b;}\n");
        html.append("a{color:#0052cc;text-decoration:none;}\n");
        html.append("a:hover{text-decoration:underline;}\n");
        html.append("</style>\n</head>\n<body>\n");

        html.append("<h1>📊 Сводный отчет по проектам</h1>\n");
        html.append("<p><em>Сгенерировано: ").append(now).append("</em></p>\n<hr/>\n");

        int totalProjects = projectKeys.size();
        int processedProjects = 0;

        for (String project : projectKeys) {
            if (cancelled) throw new InterruptedException("cancelled");
            processedProjects++;

            SonarIssuesApi api = ctx.state().newApi(project);
            Map<String, Map<String, Integer>> issueCounts = new TreeMap<>();
            Map<String, Map<String, Set<String>>> fileCounts = new TreeMap<>();
            for (String q : qualities) {
                issueCounts.put(q, new LinkedHashMap<>());
                fileCounts.put(q, new LinkedHashMap<>());
                for (String s : severities) {
                    issueCounts.get(q).put(s, 0);
                    fileCounts.get(q).put(s, new HashSet<>());
                }
            }

            log("[ReportExportService] Multi-Summary: загрузка проекта " + project + " (" + processedProjects + "/" + totalProjects + ")");

            List<Issue> rawIssues = new ArrayList<>();
            try {
                String statuses = resolveStatuses(filter);
                rawIssues = api.fetchAllByParts(statuses, filter.severity, filter.quality,
                        (cur, tot, msg) -> {
                            if (listener != null) {
                                listener.onProgress(cur, tot, "fetching", project + ": " + msg);
                            }
                            if (cur % 1000 == 0) {
                                log("[ReportExportService] " + project + ": " + msg);
                            }
                        });
            } catch (Exception e) {
                logErr("[ReportExportService] Ошибка загрузки проекта " + project + ": " + e.getMessage());
                failedProjects.add(project + " — " + e.getMessage());
                continue;
            }

            if (cancelled) throw new InterruptedException("cancelled");

            Map<String, Issue> uniqueIssues = new LinkedHashMap<>();
            for (Issue it : rawIssues) {
                if (passesClientFilters(it, filter)) {
                    uniqueIssues.put(it.key, it);
                }
            }

            for (Issue it : uniqueIssues.values()) {
                if (it.impacts != null && !it.impacts.isEmpty()) {
                    for (Issue.Impact im : it.impacts) {
                        String qual = im.softwareQuality != null ? im.softwareQuality.toUpperCase() : null;
                        String sev = im.severity != null ? im.severity.toUpperCase() : null;
                        if (qual != null && sev != null && issueCounts.containsKey(qual) && issueCounts.get(qual).containsKey(sev)) {
                            issueCounts.get(qual).put(sev, issueCounts.get(qual).get(sev) + 1);
                            fileCounts.get(qual).get(sev).add(it.component);
                        }
                    }
                } else if (it.severity != null) {
                    String qual = "MAINTAINABILITY";
                    String sev = it.severity.toUpperCase();
                    if (issueCounts.containsKey(qual) && issueCounts.get(qual).containsKey(sev)) {
                        issueCounts.get(qual).put(sev, issueCounts.get(qual).get(sev) + 1);
                        fileCounts.get(qual).get(sev).add(it.component);
                    }
                }
            }

            String projectUrl = "https://" + host + "/dashboard?id=" + urlEnc(project);
            html.append("<div class=\"project-block\">\n");
            html.append("<h2><a href=\"").append(esc(projectUrl)).append("\" target=\"_blank\">").append(esc(project)).append("</a></h2>\n");

            for (String q : qualities) {
                int totalForQual = issueCounts.get(q).values().stream().mapToInt(Integer::intValue).sum();
                if (totalForQual > 0) {
                    String qualUrl = "https://" + host + "/project/issues?id=" + urlEnc(project) + "&impactSoftwareQualities=" + urlEnc(q);
                    html.append("<div class=\"qual-block\">\n");
                    html.append("<h3><a href=\"").append(esc(qualUrl)).append("\" target=\"_blank\">").append(esc(q)).append("</a> (Всего: ").append(totalForQual).append(")</h3>\n");

                    for (String s : severities) {
                        int cnt = issueCounts.get(q).get(s);
                        int files = fileCounts.get(q).get(s).size();
                        if (cnt > 0) {
                            String sevUrl = "https://" + host + "/project/issues?id=" + urlEnc(project) + "&impactSoftwareQualities=" + urlEnc(q) + "&impactSeverities=" + urlEnc(s);
                            html.append("<div class=\"sev-row\">\n");
                            html.append("  <a class=\"sev-name\" href=\"").append(esc(sevUrl)).append("\" target=\"_blank\">").append(esc(s)).append("</a>\n");
                            html.append("  <div class=\"sev-stats\">");
                            html.append("    <span class=\"count\">Ошибок: ").append(cnt).append("</span>");
                            html.append("    <span>Файлов: ").append(files).append("</span>");
                            html.append("  </div>\n");
                            html.append("</div>\n");
                        }
                    }
                    html.append("</div>\n");
                }
            }
            html.append("</div>\n");
        }

        // Добавляем блок с ошибками, если есть недоступные проекты
        if (!failedProjects.isEmpty()) {
            html.append("<div class=\"error-block\">\n");
            html.append("<h2>⚠ Ошибки доступа к проектам</h2>\n");
            html.append("<p>Следующие проекты не удалось загрузить:</p>\n<ul>\n");
            for (String err : failedProjects) {
                html.append("<li>").append(esc(err)).append("</li>\n");
            }
            html.append("</ul>\n</div>\n");
        }

        html.append("</body>\n</html>\n");

        if (outputFilePath != null) {
            Path parent = outputFilePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(outputFilePath, html.toString(), StandardCharsets.UTF_8);
            log("[ReportExportService] Multi-Summary: отчет сохранен в " + outputFilePath);
            return outputFilePath;
        } else {
            Path out = Files.createTempFile("sonar_multisummary_", ".html");
            Files.writeString(out, html.toString(), StandardCharsets.UTF_8);
            return out;
        }
    }

    // ============ QUAL TEAM PROJECT HTML (13) ============

    private Path exportQualProjectHtml(List<String> projects, Path targetDir, BaseFilter filter, List<String> failedProjects) throws Exception {
        log("[ReportExportService] Отчет 13: запуск глубокой выгрузки по проектам...");
        QualProjectHtmlExporter.writeDirectory(targetDir, projects, ctx, filter, failedProjects, listener);

        // Если есть ошибки, прикрепляем лог в корень отчета
        if (!failedProjects.isEmpty()) {
            Path errorLog = targetDir.resolve("_errors.log");
            StringBuilder sb = new StringBuilder();
            sb.append("СПИСОК ПРОЕКТОВ, К КОТОРЫМ НЕТ ДОСТУПА:\n");
            sb.append("=========================================\n");
            for (String err : failedProjects) {
                sb.append(err).append("\n");
            }
            Files.writeString(errorLog, sb.toString(), StandardCharsets.UTF_8);
            log("[ReportExportService] Отчет 13: создан файл с ошибками " + errorLog);
        }

        return targetDir;
    }

    // ============ ОСТАЛЬНЫЕ МЕТОДЫ (без изменений) ============

    private List<Issue> loadByKeys(String project, List<String> keys) throws Exception {
        SonarIssuesApi api = ctx.state().newApi(project);
        List<Issue> out = new ArrayList<>();
        int batchSize = 100;
        int total = keys.size();

        for (int i = 0; i < keys.size(); i += batchSize) {
            if (cancelled) return out;
            int end = Math.min(i + batchSize, keys.size());
            List<String> batch = keys.subList(i, end);
            String keysCsv = String.join(",", batch);

            try {
                org.json.JSONObject raw = new org.json.JSONObject(
                        ctx.state().getClient().get("/api/issues/search?issues="
                                + urlEnc(keysCsv) + "&additionalFields=" + urlEnc("_all")));
                org.json.JSONArray arr = raw.optJSONArray("issues");
                if (arr != null) {
                    for (int j = 0; j < arr.length(); j++) out.add(Issue.from(arr.getJSONObject(j), project));
                }
            } catch (Exception e) {
                for (String k : batch) {
                    try { Issue it = api.fetchIssue(k); if (it != null) out.add(it); } catch (Exception ignored) {}
                }
            }
            if (listener != null) {
                listener.onProgress(out.size(), total, "fetching",
                        String.format("Загрузка issues: %d/%d", out.size(), total));
            }
        }
        return out;
    }

    private List<Issue> collectByFilters(BaseFilter f) throws Exception {
        SonarIssuesApi api = ctx.state().newApi(f.project);

        String statuses = resolveStatuses(f);
        String rulesCsv = f.rules.isEmpty() ? null : String.join(",", f.rules);

        boolean hasNoTagsMarker   = f.tags.contains(NO_TAGS_MARKER);
        boolean hasNoTagsExcluded = f.excludedTags.contains(NO_TAGS_MARKER);

        String tagsCsv    = (hasNoTagsMarker   || f.tags.isEmpty()) ? null : String.join(",", f.tags);
        String notTagsCsv = (hasNoTagsExcluded || f.excludedTags.isEmpty()) ? null : String.join(",", f.excludedTags);

        if (listener != null) listener.onProgress(0, 0, "fetching", "Подсчёт количества issues по фильтрам…");
        log("[ReportExportService] Запрос общего количества issues...");

        int serverTotal = -1;
        try {
            org.json.JSONObject count = api.fetchPageRaw(
                    statuses, f.severity, f.quality, rulesCsv, tagsCsv, notTagsCsv, 1, 1);
            serverTotal = count.optInt("total", -1);
            log("[ReportExportService] Сервер вернул total=" + serverTotal);
        } catch (Exception e) {
            logErr("[ReportExportService] Не удалось подсчитать total: " + e.getMessage());
        }

        List<Issue> result = new ArrayList<>();

        if (serverTotal > SONAR_HARD_LIMIT) {
            log("[ReportExportService] total=" + serverTotal
                    + " > " + SONAR_HARD_LIMIT + " — разбиение по файлам...");
            SonarIssuesApi.FetchProgressListener apiProgress = null;
            if (listener != null) {
                apiProgress = (cur, tot, msg) -> listener.onProgress(cur, tot, "fetching", msg);
            }
            List<Issue> all = api.fetchAllByParts(statuses, f.severity, f.quality, apiProgress);
            for (Issue it : all) {
                if (cancelled) break;
                if (passesClientFilters(it, f)) result.add(it);
            }
        } else {
            log("[ReportExportService] Постраничная загрузка по "
                    + PAGE_SIZE + " (total=" + serverTotal + ")…");
            int page = 1;
            while (true) {
                if (cancelled) return result;

                org.json.JSONObject raw = api.fetchPageRaw(
                        statuses, f.severity, f.quality, rulesCsv, tagsCsv, notTagsCsv,
                        page, PAGE_SIZE);
                org.json.JSONArray arr = raw.optJSONArray("issues");
                if (arr == null || arr.isEmpty()) break;

                int batchSize = arr.length();
                for (int i = 0; i < batchSize; i++) {
                    Issue issue = Issue.from(arr.getJSONObject(i), f.project);
                    if (passesClientFilters(issue, f)) result.add(issue);
                }

                log("[ReportExportService] Страница " + page + " загружена (" + result.size() + " issues)");

                if (listener != null) {
                    int displayTotal = serverTotal >= 0 ? serverTotal : result.size();
                    listener.onProgress(result.size(), displayTotal, "fetching",
                            String.format("Загружено %d из %d (страница %d)", result.size(), displayTotal, page));
                }

                if (batchSize < PAGE_SIZE) break;
                page++;
            }
        }

        log("[ReportExportService] Отобрано " + result.size() + " issues после клиентских фильтров.");
        if (listener != null) {
            listener.onProgress(result.size(), result.size(), "filtered",
                    String.format("Отобрано %d issues", result.size()));
        }
        return result;
    }

    public static String resolveStatuses(BaseFilter f) {
        String s = f.statusFilter;
        if (s == null || s.isEmpty() || "ALL".equalsIgnoreCase(s)) {
            return f.hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : SonarIssuesApi.ALL_STATUSES;
        }
        return s;
    }

    public static boolean passesClientFilters(Issue issue, BaseFilter f) {
        if (!f.tags.isEmpty()) {
            List<String> itTags = issue.tags != null ? issue.tags : List.of();
            boolean hasMatch = false;
            for (String t : f.tags) {
                if (NO_TAGS_MARKER.equals(t)) { if (itTags.isEmpty()) hasMatch = true; }
                else if (itTags.contains(t))  hasMatch = true;
                if (hasMatch) break;
            }
            if (!hasMatch) return false;
        }

        if (!f.excludedTags.isEmpty()) {
            List<String> itTags = issue.tags != null ? issue.tags : List.of();
            boolean hasExcluded = false;
            for (String t : f.excludedTags) {
                if (NO_TAGS_MARKER.equals(t)) { if (itTags.isEmpty()) hasExcluded = true; }
                else if (itTags.contains(t))  hasExcluded = true;
                if (hasExcluded) break;
            }
            if (hasExcluded) return false;
        }

        if (!f.rules.isEmpty() && !f.rules.contains(issue.rule)) return false;

        if (f.filterText != null && !f.filterText.isBlank()) {
            String q = f.filterText.trim().toLowerCase();
            boolean m = (issue.key != null && issue.key.toLowerCase().contains(q))
                    || (issue.message != null && issue.message.toLowerCase().contains(q))
                    || (issue.component != null && issue.component.toLowerCase().contains(q));
            if (!m) return false;
        }
        return true;
    }

    private Path exportTextFormat(ReportExportType type, List<Issue> issues) throws Exception {
        int total = issues.size();
        String project = ctx.getProject();
        String host = hostOf();
        StringBuilder sb = new StringBuilder();
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());
        switch (type) {
            case JIRA -> sb.append("h3. Проект: ").append(project).append("\n*Всего issues:* ").append(total).append("\n*Сгенерировано:* ").append(now).append("\n\n");
            case MARKDOWN -> sb.append("# Отчет по проекту: ").append(project).append("\n\n**Сгенерировано:** ").append(now).append("\n**Всего issues:** ").append(total).append("\n\n---\n\n");
            case TXT -> sb.append("=== Отчет по проекту: ").append(project).append(" ===\nСгенерировано: ").append(now).append("\nВсего issues: ").append(total).append("\n\n");
            case CONFLUENCE -> sb.append("<style>mark.sonar-issue-mark{background-color:rgba(248,81,73,.25);border-bottom:2px solid #f85149;}</style>\n<h2>Проект: ").append(esc(project)).append("</h2>\n<p><em>Сгенерировано: ").append(esc(now)).append(" · Всего: ").append(total).append("</em></p>\n<hr/>\n");
            default -> {}
        }
        SonarIssuesApi api = ctx.state().newApi(project);
        log("[ReportExportService] Формат " + type.title + " — начало генерации.");
        for (int i = 0; i < issues.size(); i++) {
            if (cancelled) break;
            Issue it = issues.get(i);
            List<String> snippet; try { snippet = api.fetchSnippet(it.key); } catch (Exception e) { snippet = List.of(); }
            it.snippet = snippet;
            switch (type) {
                case JIRA -> sb.append(JiraExporter.exportSingle(it, project, host)).append("\n");
                case MARKDOWN -> {
                    sb.append(MdExporter.exportSingle(it, project, host));
                    if (!snippet.isEmpty()) {
                        sb.append("**Фрагмент кода:**\n```\n");
                        for (String line : snippet) sb.append(line.replace("§HL_START§", ">>> ").replace("§HL_END§", " <<<")).append('\n');
                        sb.append("```\n\n");
                    }
                    sb.append("---\n\n");
                }
                case TXT -> {
                    sb.append(TxtExporter.exportSingle(it, project, host));
                    if (!snippet.isEmpty()) {
                        sb.append("Код:\n");
                        for (String line : snippet) sb.append(line.replace("§HL_START§", ">>> ").replace("§HL_END§", " <<<")).append('\n');
                    }
                    sb.append("\n----------------------------------------\n\n");
                }
                case CONFLUENCE -> sb.append(ConfluenceExporter.exportSingle(it, project, host, snippet));
                default -> {}
            }
            if (listener != null && (i % 10 == 0 || i == total - 1)) {
                listener.onProgress(i + 1, total, "generating", String.format("Формат %s: %d/%d", type.title, i + 1, total));
                log("[ReportExportService] " + type.title + ": " + (i + 1) + "/" + total);
            }
        }
        Path out = Files.createTempFile("sonar_report_", "." + type.ext);
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        log("[ReportExportService] Файл записан: " + out);
        return out;
    }

    private Path exportHtml(List<Issue> issues, boolean withTeams, Path targetDir) throws Exception {
        int total = issues.size();
        String project = ctx.getProject();
        String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);
        SonarIssuesApi api = ctx.state().newApi(project);
        Map<String, List<String>> snippets = new HashMap<>();

        int loaded = 0;
        for (List<Issue> lst : byFile.values()) {
            for (Issue it : lst) {
                if (cancelled) throw new InterruptedException("cancelled");
                try { List<String> s = api.fetchSnippet(it.key); if (!s.isEmpty()) snippets.put(it.key, s); } catch (Exception ignored) {}
                loaded++;
                if (listener != null && (loaded % 10 == 0 || loaded == total))
                    listener.onProgress(loaded, total, "snippets", String.format("Фрагменты кода: %d/%d", loaded, total));
            }
        }

        log("[ReportExportService] Фрагменты кода загружены, начинаю запись HTML...");
        if (listener != null) listener.onProgress(total, total, "generating", "Сохранение HTML файлов…");

        if (targetDir != null) {
            TeamMapping mapping = withTeams ? TeamMapping.load() : null;
            HtmlPerFileExporter.writeDirectory(targetDir, byFile, snippets, project, host, withTeams, mapping,
                    (cur, tot, msg) -> {
                        if (listener != null) listener.onProgress(cur, tot, "writing", msg);
                        if (cur % 50 == 0 || cur == tot) log("[ReportExportService] Запись HTML: " + cur + "/" + tot);
                    });
            return targetDir;
        } else {
            Path zip = Files.createTempFile("sonar_report_", ".zip");
            TeamMapping mapping = withTeams ? TeamMapping.load() : null;
            HtmlPerFileExporter.writeZip(zip, byFile, snippets, project, host, withTeams, mapping);
            log("[ReportExportService] ZIP создан: " + zip);
            return zip;
        }
    }

    private Path exportTeamHtml(List<Issue> issues, Path targetDir) throws Exception {
        if (targetDir == null) {
            throw new IllegalArgumentException("Для отчета Team HTML необходимо указать каталог сохранения.");
        }
        String project = ctx.getProject();
        String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);
        SonarIssuesApi api = ctx.state().newApi(project);
        Map<String, List<String>> snippets = new HashMap<>();

        int loaded = 0;
        int total = issues.size();
        Map<String, String> fullSources = new HashMap<>();

        log("[ReportExportService] Team HTML: загрузка исходников (" + byFile.size() + " файлов)...");
        for (String file : byFile.keySet()) {
            if (cancelled) throw new InterruptedException("cancelled");
            try {
                String fullSource = api.fetchFullSource(project + ":" + file);
                fullSources.put(file, fullSource);
            } catch (Exception e) {
                logErr("[ReportExportService] Не удалось получить исходник для " + file);
            }
        }
        log("[ReportExportService] Исходники загружены, загрузка сниппетов...");

        for (List<Issue> lst : byFile.values()) {
            for (Issue it : lst) {
                if (cancelled) throw new InterruptedException("cancelled");
                try { List<String> s = api.fetchSnippet(it.key); if (!s.isEmpty()) snippets.put(it.key, s); } catch (Exception ignored) {}
                loaded++;
                if (listener != null && (loaded % 10 == 0 || loaded == total))
                    listener.onProgress(loaded, total, "snippets", String.format("Загрузка данных: %d/%d", loaded, total));
            }
        }

        log("[ReportExportService] Team HTML: начало записи файлов...");
        if (listener != null) listener.onProgress(total, total, "generating", "Сохранение Team HTML файлов…");
        TeamMapping mapping = TeamMapping.load();
        HtmlTeamPerFileExporter.writeDirectory(
                targetDir, byFile, snippets, fullSources, project, host, mapping,
                (cur, tot, msg) -> {
                    if (listener != null) listener.onProgress(cur, tot, "writing", msg);
                    if (cur % 50 == 0 || cur == tot) log("[ReportExportService] Запись Team HTML: " + cur + "/" + tot);
                }
        );
        return targetDir;
    }

    private Path exportQualTeamHtml(List<Issue> issues, Path targetDir) throws Exception {
        if (targetDir == null) {
            throw new IllegalArgumentException("Для отчета Qual+Team HTML необходимо указать каталог сохранения.");
        }
        Files.createDirectories(targetDir);
        log("[ReportExportService] Qual+Team HTML: корневой каталог создан: " + targetDir);

        String project = ctx.getProject();
        String host = hostOf();
        SonarIssuesApi api = ctx.state().newApi(project);
        TeamMapping mapping = TeamMapping.load();
        log("[ReportExportService] TeamMapping загружен. Запуск 4 потоков...");

        if (listener != null) {
            listener.onProgress(0, issues.size(), "generating", "Запуск 4 потоков генерации HTML…");
        }

        QualTeamHtmlExporter.writeDirectory(
                targetDir,
                issues,
                api,
                project,
                host,
                mapping,
                (cur, tot, msg) -> {
                    if (listener != null) listener.onProgress(cur, tot, "writing", msg);
                    if (cur % 25 == 0 || cur == tot) {
                        log("[ReportExportService] Qual+Team HTML: " + cur + "/" + tot);
                    }
                },
                () -> cancelled
        );

        log("[ReportExportService] Qual+Team HTML: все файлы записаны.");
        return targetDir;
    }

    private Path exportQualSummary(List<Issue> issues) throws Exception {
        log("[ReportExportService] Qual Summary: начало подсчета статистики...");

        Map<String, Map<String, Integer>> stats = new TreeMap<>();
        String[] qualities = {"SECURITY", "RELIABILITY", "MAINTAINABILITY", "SECURITY_HOTSPOT"};
        String[] severities = {"BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"};

        for (String q : qualities) {
            Map<String, Integer> sevMap = new LinkedHashMap<>();
            for (String s : severities) {
                sevMap.put(s, 0);
            }
            stats.put(q, sevMap);
        }

        int totalKept = 0;
        for (Issue it : issues) {
            if (cancelled) break;
            if (it.tags != null && it.tags.contains("not-require-fix")) continue;

            boolean counted = false;
            if (it.impacts != null && !it.impacts.isEmpty()) {
                for (Issue.Impact im : it.impacts) {
                    if (im.softwareQuality != null && stats.containsKey(im.softwareQuality.toUpperCase())) {
                        Map<String, Integer> sevMap = stats.get(im.softwareQuality.toUpperCase());
                        if (im.severity != null && sevMap.containsKey(im.severity.toUpperCase())) {
                            sevMap.put(im.severity.toUpperCase(), sevMap.get(im.severity.toUpperCase()) + 1);
                            counted = true;
                        }
                    }
                }
            } else if (it.severity != null) {
                String sev = it.severity.toUpperCase();
                if (Arrays.asList(severities).contains(sev)) {
                    Map<String, Integer> sevMap = stats.get("MAINTAINABILITY");
                    sevMap.put(sev, sevMap.get(sev) + 1);
                    counted = true;
                }
            }
            if (counted) totalKept++;
            if (listener != null && totalKept % 1000 == 0) {
                listener.onProgress(totalKept, issues.size(), "generating", "Подсчет статистики: " + totalKept);
                log("[ReportExportService] Qual Summary: обработано " + totalKept + " / " + issues.size());
            }
        }

        StringBuilder sb = new StringBuilder();
        String project = ctx.getProject();
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());

        sb.append("===================================================\n");
        sb.append(" Сводный отчет по категориям (Quality) и уровням (Severity)\n");
        sb.append("===================================================\n");
        sb.append("Проект: ").append(project).append("\n");
        sb.append("Сгенерировано: ").append(now).append("\n");
        sb.append("Всего замечаний (без not-require-fix): ").append(totalKept).append("\n");
        sb.append("===================================================\n\n");

        for (Map.Entry<String, Map<String, Integer>> entry : stats.entrySet()) {
            String qual = entry.getKey();
            Map<String, Integer> sevMap = entry.getValue();
            int totalForQual = sevMap.values().stream().mapToInt(Integer::intValue).sum();
            if (totalForQual == 0) continue;

            sb.append(qual).append(" (Всего: ").append(totalForQual).append(")\n");
            for (Map.Entry<String, Integer> sevEntry : sevMap.entrySet()) {
                sb.append(String.format("  %-15s : %d\n", sevEntry.getKey(), sevEntry.getValue()));
            }
            sb.append("\n");
        }
        sb.append("===================================================\n");

        Path out = Files.createTempFile("sonar_qualsummary_", ".txt");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        log("[ReportExportService] Qual Summary: файл записан: " + out);
        return out;
    }

    private Path exportLlmMd(List<Issue> issues, Path targetDir) throws Exception {
        String project = ctx.getProject();
        String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);

        log("[ReportExportService] LLM MD: начало генерации (" + byFile.size() + " файлов)...");
        if (listener != null) listener.onProgress(issues.size(), issues.size(), "generating", "Сохранение MD файлов…");

        if (targetDir != null) {
            LlmMdExporter.writeDirectory(targetDir, byFile, ctx.state().newApi(project), project, host,
                    (cur, tot, msg) -> {
                        if (listener != null) listener.onProgress(cur, tot, "writing", msg);
                        if (cur % 50 == 0 || cur == tot) log("[ReportExportService] Запись LLM MD: " + cur + "/" + tot);
                    });
            return targetDir;
        } else {
            Path zip = Files.createTempFile("sonar_llm_", ".zip");
            LlmMdExporter.writeZip(zip, byFile, ctx.state().newApi(project), project, host);
            log("[ReportExportService] LLM MD ZIP: " + zip);
            return zip;
        }
    }

    private Path exportTeamModule(List<Issue> issues, Path targetDir) throws Exception {
        String project = ctx.getProject();
        String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);

        log("[ReportExportService] Team Module: начало генерации (" + byFile.size() + " файлов)...");
        if (listener != null) listener.onProgress(issues.size(), issues.size(), "generating", "Сохранение Team/Module файлов…");

        if (targetDir != null) {
            TeamModuleMdExporter.writeDirectory(targetDir, byFile, ctx.state().newApi(project), project, host, TeamMapping.load(),
                    (cur, tot, msg) -> {
                        if (listener != null) listener.onProgress(cur, tot, "writing", msg);
                        if (cur % 50 == 0 || cur == tot) log("[ReportExportService] Запись Team Module: " + cur + "/" + tot);
                    });
            return targetDir;
        } else {
            Path zip = Files.createTempFile("sonar_team_", ".zip");
            TeamModuleMdExporter.writeZip(zip, byFile, ctx.state().newApi(project), project, host, TeamMapping.load());
            log("[ReportExportService] Team Module ZIP: " + zip);
            return zip;
        }
    }

    private Map<String, List<Issue>> groupByFile(List<Issue> issues) {
        Map<String, List<Issue>> byFile = new TreeMap<>();
        for (Issue it : issues) byFile.computeIfAbsent(it.component != null ? it.component : "(unknown)", k -> new ArrayList<>()).add(it);
        return byFile;
    }

    private String hostOf() {
        try {
            String url = ctx.state().getSession().getUrl();
            java.net.URI u = java.net.URI.create(url);
            String h = u.getHost();
            int p = u.getPort();
            if (h == null || h.isBlank()) return "sonar";
            return (p > 0 && p != 80 && p != 443) ? h + ":" + p : h;
        } catch (Exception e) { return "sonar"; }
    }

    private static String suggestedName(ReportExportType type, String project) {
        String safe = (project == null ? "report" : project).replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) safe = "report";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return safe + "_" + stamp + "." + type.ext;
    }

    private static String urlEnc(String s) { try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return s; } }
    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
}