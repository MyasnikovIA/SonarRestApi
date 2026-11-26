package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;
import ru.miacomsoft.sonar_gui.backend.TeamMapping;
import ru.miacomsoft.sonar_gui.backend.exporters.ConfluenceExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.HtmlPerFileExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.JiraExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.LlmMdExporter;
import ru.miacomsoft.sonar_gui.backend.exporters.MdExporter;
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

    private final GuiContext ctx;
    private final ProgressListener listener;
    private volatile boolean cancelled = false;

    public ReportExportService(GuiContext ctx, ProgressListener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void setCancelled(boolean c) { this.cancelled = c; }
    public boolean isCancelled() { return cancelled; }

    public void export(ReportExportType type, List<String> keysCsv, BaseFilter filter) {
        System.out.println("[ReportExportService] Запуск экспорта в формате: " + type);
        try {
            doExport(type, keysCsv, filter);
        } catch (Throwable t) {
            System.err.println("[ReportExportService] Критическая ошибка экспорта: " + t.getMessage());
            t.printStackTrace();
            if (listener != null) listener.onError(t);
        }
    }

    private void doExport(ReportExportType type, List<String> keysCsv, BaseFilter filter) throws Exception {
        if (!ctx.isSessionReady()) {
            System.err.println("[ReportExportService] Сессия SonarQube не готова.");
            throw new IllegalStateException("Сессия SonarQube не готова");
        }

        List<Issue> issues;
        if (keysCsv != null && !keysCsv.isEmpty()) {
            System.out.println("[ReportExportService] Экспорт по списку ключей (" + keysCsv.size() + " шт)...");
            issues = loadByKeys(filter != null ? filter.project : ctx.getProject(), keysCsv);
        } else {
            if (filter == null) {
                System.err.println("[ReportExportService] Нет ни ключей, ни фильтров для экспорта.");
                throw new IllegalArgumentException("Нет ни keys, ни фильтров");
            }
            System.out.println("[ReportExportService] Экспорт по фильтрам...");
            issues = collectByFilters(filter);
        }

        if (cancelled) {
            System.out.println("[ReportExportService] Экспорт отменен пользователем.");
            return;
        }
        if (issues.isEmpty()) {
            System.out.println("[ReportExportService] Нет данных для экспорта.");
            if (listener != null) listener.onError(new IllegalStateException("Нет данных"));
            return;
        }

        System.out.println("[ReportExportService] Собрано " + issues.size() + " issues. Генерация файла...");
        Path outFile;
        switch (type) {
            case JIRA, MARKDOWN, TXT, CONFLUENCE -> outFile = exportTextFormat(type, issues);
            case HTML_FILES                       -> outFile = exportHtmlZip(issues, false);
            case HTML_FILES_TEAMS                 -> outFile = exportHtmlZip(issues, true);
            case LLM_MD_ZIP                       -> outFile = exportLlmMdZip(issues);
            case TEAM_MODULE_ZIP                  -> outFile = exportTeamModuleZip(issues);
            default -> throw new IllegalArgumentException("Неизвестный формат: " + type);
        }

        if (cancelled) {
            System.out.println("[ReportExportService] Отмена после генерации. Удаление временного файла.");
            try { Files.deleteIfExists(outFile); } catch (Exception ignored) {}
            return;
        }

        System.out.println("[ReportExportService] Экспорт успешно завершен. Файл: " + outFile);
        if (listener != null) {
            listener.onDone(outFile, suggestedName(type, filter != null ? filter.project : ctx.getProject()));
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
                System.err.println("[ReportExportService] Пакетная загрузка не удалась, переход на одиночные запросы: " + e.getMessage());
                for (String k : batch) {
                    try { Issue it = api.fetchIssue(k); if (it != null) out.add(it); } catch (Exception ignored) {}
                }
            }
            if (listener != null) {
                listener.onProgress(out.size(), total, "fetching", String.format("Загрузка issues: %d/%d", out.size(), total));
            }
        }
        return out;
    }

    private List<Issue> collectByFilters(BaseFilter f) throws Exception {
        SonarIssuesApi api = ctx.state().newApi(f.project);

        String statuses = f.statusFilter;
        if (statuses == null || statuses.isEmpty() || "ALL".equalsIgnoreCase(statuses)) {
            statuses = f.hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : SonarIssuesApi.ALL_STATUSES;
        }

        if (listener != null) listener.onProgress(0, 0, "fetching", "Загрузка issues из SonarQube…");

        SonarIssuesApi.FetchProgressListener apiProgress = (current, total, message) -> {
            if (listener != null) listener.onProgress(current, total, "fetching", message);
        };

        System.out.println("[ReportExportService] Запуск загрузки по фильтрам...");
        List<Issue> all = api.fetchAllByStatuses(statuses, f.severity, f.quality, apiProgress);

        if (cancelled) return List.of();

        System.out.println("[ReportExportService] Загружено " + all.size() + " issues. Применение фильтров...");
        List<Issue> filtered = new ArrayList<>();
        String q = f.filterText == null ? "" : f.filterText.trim().toLowerCase();

        for (Issue it : all) {
            if (cancelled) return filtered;

            // Теги
            if (!f.tags.isEmpty()) {
                List<String> itTags = it.tags != null ? it.tags : List.of();
                boolean hasMatch = false;
                for (String t : f.tags) {
                    if (NO_TAGS_MARKER.equals(t)) { if (itTags.isEmpty()) hasMatch = true; }
                    else if (itTags.contains(t)) hasMatch = true;
                    if (hasMatch) break;
                }
                if (!hasMatch) continue;
            }

            // Исключенные теги
            if (!f.excludedTags.isEmpty()) {
                List<String> itTags = it.tags != null ? it.tags : List.of();
                boolean hasExcluded = false;
                for (String t : f.excludedTags) {
                    if (NO_TAGS_MARKER.equals(t)) { if (itTags.isEmpty()) hasExcluded = true; }
                    else if (itTags.contains(t)) hasExcluded = true;
                    if (hasExcluded) break;
                }
                if (hasExcluded) continue;
            }

            // Правила
            if (!f.rules.isEmpty() && !f.rules.contains(it.rule)) continue;

            // Текст
            if (!q.isEmpty()) {
                boolean m = (it.key != null && it.key.toLowerCase().contains(q))
                        || (it.message != null && it.message.toLowerCase().contains(q))
                        || (it.component != null && it.component.toLowerCase().contains(q));
                if (!m) continue;
            }

            filtered.add(it);
        }

        System.out.println("[ReportExportService] Отфильтровано: " + filtered.size() + " из " + all.size());
        if (listener != null)
            listener.onProgress(filtered.size(), filtered.size(), "filtered", String.format("Отобрано %d из %d", filtered.size(), all.size()));
        return filtered;
    }

    // --- Вспомогательные методы экспорта ---

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

    private Path exportHtmlZip(List<Issue> issues, boolean withTeams) throws Exception {
        int total = issues.size();
        String project = ctx.getProject();
        String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);
        SonarIssuesApi api = ctx.state().newApi(project);
        Map<String, List<String>> snippets = new HashMap<>();
        int loaded = 0;
        System.out.println("[ReportExportService] Сборка HTML ZIP (withTeams=" + withTeams + "). Файлов: " + byFile.size());
        for (List<Issue> lst : byFile.values()) {
            for (Issue it : lst) {
                if (cancelled) throw new InterruptedException("cancelled");
                try { List<String> s = api.fetchSnippet(it.key); if (!s.isEmpty()) snippets.put(it.key, s); } catch (Exception ignored) {}
                loaded++;
                if (listener != null && (loaded % 10 == 0 || loaded == total)) listener.onProgress(loaded, total, "snippets", String.format("Фрагменты кода: %d/%d", loaded, total));
            }
        }
        Path zip = Files.createTempFile("sonar_report_", ".zip");
        TeamMapping mapping = withTeams ? TeamMapping.load() : null;
        if (listener != null) listener.onProgress(total, total, "generating", "Сборка ZIP…");
        HtmlPerFileExporter.writeZip(zip, byFile, snippets, project, host, withTeams, mapping);
        return zip;
    }

    private Path exportLlmMdZip(List<Issue> issues) throws Exception {
        String project = ctx.getProject(); String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);
        if (listener != null) listener.onProgress(issues.size(), issues.size(), "generating", "Сборка LLM MD ZIP…");
        Path zip = Files.createTempFile("sonar_llm_", ".zip");
        LlmMdExporter.writeZip(zip, byFile, ctx.state().newApi(project), project, host);
        return zip;
    }

    private Path exportTeamModuleZip(List<Issue> issues) throws Exception {
        String project = ctx.getProject(); String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);
        if (listener != null) listener.onProgress(issues.size(), issues.size(), "generating", "Сборка Team/Module ZIP…");
        Path zip = Files.createTempFile("sonar_team_", ".zip");
        TeamModuleMdExporter.writeZip(zip, byFile, ctx.state().newApi(project), project, host, TeamMapping.load());
        return zip;
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