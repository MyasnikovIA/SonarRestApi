package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;
import ru.miacomsoft.sonar_gui.backend.TeamMapping;
import ru.miacomsoft.sonar_gui.backend.exporters.ConfluenceExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.HtmlPerFileExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.HtmlTeamPerFileExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.JiraExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.LlmMdExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.MdExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.QualTeamHtmlExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.TeamModuleMdExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.TxtExporter;
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
    }

    private static final String NO_TAGS_MARKER = "__NO_TAGS__";
    private static final int PAGE_SIZE = 3000;
    private static final int SONAR_HARD_LIMIT = 10000;

    private final GuiContext ctx;
    private final ProgressListener listener;
    private volatile boolean cancelled = false;

    public ReportExportService(GuiContext ctx, ProgressListener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void setCancelled(boolean c) { this.cancelled = c; }
    public boolean isCancelled() { return cancelled; }

    public void export(ReportExportType type, List<String> keysCsv, BaseFilter filter, Path targetDir) {
        System.out.println("[ReportExportService] Запуск экспорта в формате: " + type + (targetDir != null ? " в каталог " + targetDir : ""));
        try {
            doExport(type, keysCsv, filter, targetDir);
        } catch (Throwable t) {
            if (cancelled || (t instanceof InterruptedException && "cancelled".equals(t.getMessage()))) {
                System.out.println("[ReportExportService] Экспорт отменен пользователем.");
                return;
            }
            System.err.println("[ReportExportService] Критическая ошибка экспорта: " + t.getMessage());
            t.printStackTrace();
            if (listener != null) listener.onError(t);
        }
    }

    private void doExport(ReportExportType type, List<String> keysCsv, BaseFilter filter, Path targetDir) throws Exception {
        if (!ctx.isSessionReady()) {
            throw new IllegalStateException("Сессия SonarQube не готова");
        }

        List<Issue> issues;
        if (keysCsv != null && !keysCsv.isEmpty()) {
            System.out.println("[ReportExportService] Экспорт по списку ключей (" + keysCsv.size() + " шт)...");
            issues = loadByKeys(filter != null ? filter.project : ctx.getProject(), keysCsv);
        } else {
            if (filter == null) throw new IllegalArgumentException("Нет ни keys, ни фильтров");
            System.out.println("[ReportExportService] Экспорт по фильтрам (постранично)…");
            issues = collectByFilters(filter);
        }

        if (cancelled) return;
        if (issues.isEmpty()) {
            if (listener != null) listener.onError(new IllegalStateException("Нет данных"));
            return;
        }

        System.out.println("[ReportExportService] Собрано " + issues.size() + " issues. Генерация файла(ов)...");

        Path outPath;
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

        if (cancelled) {
            System.out.println("[ReportExportService] Отмена после генерации.");
            if (targetDir == null) {
                try { Files.deleteIfExists(outPath); } catch (Exception ignored) {}
            }
            return;
        }

        System.out.println("[ReportExportService] Экспорт успешно завершен. Результат: " + outPath);
        if (listener != null) {
            listener.onDone(outPath, suggestedName(type, filter != null ? filter.project : ctx.getProject()));
        }
    }

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

        int serverTotal = -1;
        try {
            org.json.JSONObject count = api.fetchPageRaw(
                    statuses, f.severity, f.quality, rulesCsv, tagsCsv, notTagsCsv, 1, 1);
            serverTotal = count.optInt("total", -1);
        } catch (Exception e) {
            System.err.println("[ReportExportService] Не удалось подсчитать total: " + e.getMessage());
        }

        List<Issue> result = new ArrayList<>();

        if (serverTotal > SONAR_HARD_LIMIT) {
            System.out.println("[ReportExportService] total=" + serverTotal
                    + " > " + SONAR_HARD_LIMIT + " — запуск разбиения по файлам...");
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
            System.out.println("[ReportExportService] Постраничная загрузка по "
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

                if (listener != null) {
                    int displayTotal = serverTotal >= 0 ? serverTotal : result.size();
                    listener.onProgress(result.size(), displayTotal, "fetching",
                            String.format("Загружено %d из %d (страница %d)", result.size(), displayTotal, page));
                }

                if (batchSize < PAGE_SIZE) break;
                page++;
            }
        }

        if (listener != null) {
            listener.onProgress(result.size(), result.size(), "filtered",
                    String.format("Отобрано %d issues", result.size()));
        }
        return result;
    }

    private String resolveStatuses(BaseFilter f) {
        String s = f.statusFilter;
        if (s == null || s.isEmpty() || "ALL".equalsIgnoreCase(s)) {
            return f.hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : SonarIssuesApi.ALL_STATUSES;
        }
        return s;
    }

    private boolean passesClientFilters(Issue issue, BaseFilter f) {
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
            if (listener != null) listener.onProgress(i + 1, total, "generating", String.format("Формат %s: %d/%d", type.title, i + 1, total));
        }
        Path out = Files.createTempFile("sonar_report_", "." + type.ext);
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
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

        if (listener != null) listener.onProgress(total, total, "generating", "Сохранение HTML файлов…");

        if (targetDir != null) {
            TeamMapping mapping = withTeams ? TeamMapping.load() : null;
            HtmlPerFileExporter.writeDirectory(targetDir, byFile, snippets, project, host, withTeams, mapping,
                    (cur, tot, msg) -> { if (listener != null) listener.onProgress(cur, tot, "writing", msg); });
            return targetDir;
        } else {
            Path zip = Files.createTempFile("sonar_report_", ".zip");
            TeamMapping mapping = withTeams ? TeamMapping.load() : null;
            HtmlPerFileExporter.writeZip(zip, byFile, snippets, project, host, withTeams, mapping);
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

        for (String file : byFile.keySet()) {
            if (cancelled) throw new InterruptedException("cancelled");
            try {
                String fullSource = api.fetchFullSource(project + ":" + file);
                fullSources.put(file, fullSource);
            } catch (Exception e) {
                System.err.println("[ReportExportService] Не удалось получить исходник для " + file);
            }
        }

        for (List<Issue> lst : byFile.values()) {
            for (Issue it : lst) {
                if (cancelled) throw new InterruptedException("cancelled");
                try { List<String> s = api.fetchSnippet(it.key); if (!s.isEmpty()) snippets.put(it.key, s); } catch (Exception ignored) {}
                loaded++;
                if (listener != null && (loaded % 10 == 0 || loaded == total))
                    listener.onProgress(loaded, total, "snippets", String.format("Загрузка данных: %d/%d", loaded, total));
            }
        }

        if (listener != null) listener.onProgress(total, total, "generating", "Сохранение Team HTML файлов…");
        TeamMapping mapping = TeamMapping.load();
        HtmlTeamPerFileExporter.writeDirectory(
                targetDir, byFile, snippets, fullSources, project, host, mapping,
                (cur, tot, msg) -> { if (listener != null) listener.onProgress(cur, tot, "writing", msg); }
        );
        return targetDir;
    }

    private Path exportQualTeamHtml(List<Issue> issues, Path targetDir) throws Exception {
        if (targetDir == null) {
            throw new IllegalArgumentException("Для отчета Qual+Team HTML необходимо указать каталог сохранения.");
        }

        String project = ctx.getProject();
        String host = hostOf();
        SonarIssuesApi api = ctx.state().newApi(project);

        // 1. Загрузка полных исходников для каждого уникального файла
        Set<String> uniqueFiles = new HashSet<>();
        for (Issue it : issues) uniqueFiles.add(it.component);

        Map<String, String> fullSources = new HashMap<>();
        int loadedSrc = 0;
        for (String file : uniqueFiles) {
            if (cancelled) throw new InterruptedException("cancelled");
            try {
                fullSources.put(file, api.fetchFullSource(project + ":" + file));
            } catch (Exception e) {
                System.err.println("[ReportExportService] Не удалось получить исходник для " + file);
            }
            loadedSrc++;
            if (listener != null && loadedSrc % 10 == 0) {
                listener.onProgress(loadedSrc, uniqueFiles.size(), "sources", "Загрузка исходников: " + loadedSrc + "/" + uniqueFiles.size());
            }
        }

        // 2. Загрузка фрагментов кода (сниппетов) для всех issues
        Map<String, List<String>> snippets = new HashMap<>();
        int loadedSnip = 0;
        for (Issue it : issues) {
            if (cancelled) throw new InterruptedException("cancelled");
            try {
                List<String> s = api.fetchSnippet(it.key);
                if (!s.isEmpty()) snippets.put(it.key, s);
            } catch (Exception ignored) {}
            loadedSnip++;
            if (listener != null && loadedSnip % 10 == 0) {
                listener.onProgress(loadedSnip, issues.size(), "snippets", "Загрузка фрагментов: " + loadedSnip + "/" + issues.size());
            }
        }

        // 3. Запуск многопоточной генерации
        if (listener != null) listener.onProgress(issues.size(), issues.size(), "generating", "Сохранение файлов (4 потока)…");

        TeamMapping mapping = TeamMapping.load();

        QualTeamHtmlExporter.writeDirectory(
                targetDir, issues, snippets, fullSources, project, host, mapping,
                (cur, tot, msg) -> { if (listener != null) listener.onProgress(cur, tot, "writing", msg); },
                () -> cancelled // Передаем BooleanSupplier для корректной отмены из потоков
        );

        return targetDir;
    }

    private Path exportQualSummary(List<Issue> issues) throws Exception {
        System.out.println("[ReportExportService] Подсчет статистики для qual-summary...");

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
        return out;
    }

    private Path exportLlmMd(List<Issue> issues, Path targetDir) throws Exception {
        String project = ctx.getProject();
        String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);

        if (listener != null) listener.onProgress(issues.size(), issues.size(), "generating", "Сохранение MD файлов…");

        if (targetDir != null) {
            LlmMdExporter.writeDirectory(targetDir, byFile, ctx.state().newApi(project), project, host,
                    (cur, tot, msg) -> { if (listener != null) listener.onProgress(cur, tot, "writing", msg); });
            return targetDir;
        } else {
            Path zip = Files.createTempFile("sonar_llm_", ".zip");
            LlmMdExporter.writeZip(zip, byFile, ctx.state().newApi(project), project, host);
            return zip;
        }
    }

    private Path exportTeamModule(List<Issue> issues, Path targetDir) throws Exception {
        String project = ctx.getProject();
        String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);

        if (listener != null) listener.onProgress(issues.size(), issues.size(), "generating", "Сохранение Team/Module файлов…");

        if (targetDir != null) {
            TeamModuleMdExporter.writeDirectory(targetDir, byFile, ctx.state().newApi(project), project, host, TeamMapping.load(),
                    (cur, tot, msg) -> { if (listener != null) listener.onProgress(cur, tot, "writing", msg); });
            return targetDir;
        } else {
            Path zip = Files.createTempFile("sonar_team_", ".zip");
            TeamModuleMdExporter.writeZip(zip, byFile, ctx.state().newApi(project), project, host, TeamMapping.load());
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