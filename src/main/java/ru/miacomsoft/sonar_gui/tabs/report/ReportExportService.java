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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Локальный экспорт (без HTTP-сервера).
 *
 * Источники:
 *   - keysCsv — конкретные issue, выбранные пользователем;
 *   - BaseFilter — сбор issue по фильтрам SonarQube.
 *
 * Форматы: JIRA / MARKDOWN / TXT / CONFLUENCE — построчно;
 *          HTML_FILES / HTML_FILES_TEAMS / LLM_MD_ZIP / TEAM_MODULE_ZIP — ZIP.
 *
 * Прогресс отдаётся через {@link ProgressListener}. Отмена — {@link #setCancelled(boolean)}.
 */
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

    // ============================================================
    // ПУБЛИЧНЫЙ ВХОД
    // ============================================================

    public void export(ReportExportType type, List<String> keysCsv, BaseFilter filter) {
        try {
            doExport(type, keysCsv, filter);
        } catch (Throwable t) {
            if (listener != null) listener.onError(t);
        }
    }

    private void doExport(ReportExportType type, List<String> keysCsv, BaseFilter filter)
            throws Exception {
        if (!ctx.isSessionReady()) {
            throw new IllegalStateException(
                    "Сессия SonarQube не готова — заполните session.properties");
        }

        List<Issue> issues;
        if (keysCsv != null && !keysCsv.isEmpty()) {
            issues = loadByKeys(filter != null ? filter.project : ctx.getProject(), keysCsv);
        } else {
            if (filter == null) throw new IllegalArgumentException("Нет ни keys, ни фильтров");
            issues = collectByFilters(filter);
        }

        if (cancelled) return;
        if (issues.isEmpty()) {
            if (listener != null)
                listener.onError(new IllegalStateException("Нет данных для экспорта"));
            return;
        }

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
            try { Files.deleteIfExists(outFile); } catch (Exception ignored) {}
            return;
        }

        if (listener != null) {
            listener.onDone(outFile, suggestedName(type,
                    filter != null ? filter.project : ctx.getProject()));
        }
    }

    // ============================================================
    // СБОР ISSUES
    // ============================================================

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
                    for (int j = 0; j < arr.length(); j++) {
                        out.add(Issue.from(arr.getJSONObject(j), project));
                    }
                }
            } catch (Exception e) {
                for (String k : batch) {
                    try {
                        Issue it = api.fetchIssue(k);
                        if (it != null) out.add(it);
                    } catch (Exception ignored) {}
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
        String statuses = f.hideProcessed
                ? SonarIssuesApi.ACTIVE_STATUSES
                : SonarIssuesApi.ALL_STATUSES;

        if (listener != null)
            listener.onProgress(0, 0, "fetching", "Загрузка issues из SonarQube…");

        List<Issue> all = api.fetchAllByStatuses(statuses, f.severity, f.quality);
        if (cancelled) return List.of();

        List<Issue> filtered = new ArrayList<>();
        String q = f.filterText == null ? "" : f.filterText.trim().toLowerCase();

        for (Issue it : all) {
            if (cancelled) return filtered;

            // statusFilter
            if (f.statusFilter != null && !f.statusFilter.isEmpty()
                    && !"ALL".equalsIgnoreCase(f.statusFilter)) {
                boolean isProcessed = it.status != null
                        && !it.status.equals("OPEN") && !it.status.equals("REOPENED");
                if ("PROCESSED".equalsIgnoreCase(f.statusFilter) && !isProcessed) continue;
                if ("UNPROCESSED".equalsIgnoreCase(f.statusFilter) && isProcessed) continue;
            }

            // tags
            if (!f.tags.isEmpty()) {
                List<String> itTags = it.tags != null ? it.tags : List.of();
                boolean hasMatch = false;
                for (String t : f.tags) {
                    if (NO_TAGS_MARKER.equals(t)) {
                        if (itTags.isEmpty()) hasMatch = true;
                    } else if (itTags.contains(t)) {
                        hasMatch = true;
                    }
                    if (hasMatch) break;
                }
                if (!hasMatch) continue;
            }

            // rules
            if (!f.rules.isEmpty() && !f.rules.contains(it.rule)) continue;

            // text
            if (!q.isEmpty()) {
                boolean m = (it.key != null && it.key.toLowerCase().contains(q))
                        || (it.message != null && it.message.toLowerCase().contains(q))
                        || (it.component != null && it.component.toLowerCase().contains(q));
                if (!m) continue;
            }

            // excludedTags
            if (!f.excludedTags.isEmpty()) {
                List<String> itTags = it.tags != null ? it.tags : List.of();
                boolean hasExcluded = false;
                for (String t : f.excludedTags) {
                    if (NO_TAGS_MARKER.equals(t)) {
                        if (itTags.isEmpty()) hasExcluded = true;
                    } else if (itTags.contains(t)) {
                        hasExcluded = true;
                    }
                    if (hasExcluded) break;
                }
                if (hasExcluded) continue;
            }

            filtered.add(it);
        }

        if (listener != null)
            listener.onProgress(filtered.size(), filtered.size(), "filtered",
                    String.format("Отобрано %d из %d", filtered.size(), all.size()));
        return filtered;
    }

    // ============================================================
    // ТЕКСТОВЫЕ ФОРМАТЫ
    // ============================================================

    private Path exportTextFormat(ReportExportType type, List<Issue> issues) throws Exception {
        int total = issues.size();
        String project = ctx.getProject();
        String host = hostOf();

        StringBuilder sb = new StringBuilder();
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());

        switch (type) {
            case JIRA -> {
                sb.append("h3. Проект: ").append(project).append("\n");
                sb.append("*Всего issues:* ").append(total)
                        .append("\n*Сгенерировано:* ").append(now).append("\n\n");
            }
            case MARKDOWN -> {
                sb.append("# Отчет по проекту: ").append(project).append("\n\n");
                sb.append("**Сгенерировано:** ").append(now)
                        .append("\n**Всего issues:** ").append(total).append("\n\n---\n\n");
            }
            case TXT -> {
                sb.append("=== Отчет по проекту: ").append(project).append(" ===\n");
                sb.append("Сгенерировано: ").append(now)
                        .append("\nВсего issues: ").append(total).append("\n\n");
            }
            case CONFLUENCE -> {
                sb.append("<style>mark.sonar-issue-mark{background-color:rgba(248,81,73,.25);")
                        .append("border-bottom:2px solid #f85149;}</style>\n");
                sb.append("<h2>Проект: ").append(esc(project)).append("</h2>\n");
                sb.append("<p><em>Сгенерировано: ").append(esc(now))
                        .append(" · Всего: ").append(total).append("</em></p>\n<hr/>\n");
            }
            default -> {}
        }

        SonarIssuesApi api = ctx.state().newApi(project);

        for (int i = 0; i < issues.size(); i++) {
            if (cancelled) break;
            Issue it = issues.get(i);

            List<String> snippet;
            try { snippet = api.fetchSnippet(it.key); }
            catch (Exception e) { snippet = List.of(); }
            it.snippet = snippet;

            switch (type) {
                case JIRA -> sb.append(JiraExporter.exportSingle(it, project, host)).append("\n");
                case MARKDOWN -> {
                    sb.append(MdExporter.exportSingle(it, project, host));
                    if (snippet != null && !snippet.isEmpty()) {
                        sb.append("**Фрагмент кода:**\n```\n");
                        for (String line : snippet)
                            sb.append(line.replace("§HL_START§", ">>> ")
                                    .replace("§HL_END§", " <<<")).append('\n');
                        sb.append("```\n\n");
                    }
                    sb.append("---\n\n");
                }
                case TXT -> {
                    sb.append(TxtExporter.exportSingle(it, project, host));
                    if (snippet != null && !snippet.isEmpty()) {
                        sb.append("Код:\n");
                        for (String line : snippet)
                            sb.append(line.replace("§HL_START§", ">>> ")
                                    .replace("§HL_END§", " <<<")).append('\n');
                    }
                    sb.append("\n----------------------------------------\n\n");
                }
                case CONFLUENCE -> sb.append(ConfluenceExporter.exportSingle(it, project, host, snippet));
                default -> {}
            }

            if (listener != null) {
                listener.onProgress(i + 1, total, "generating",
                        String.format("Формат %s: %d/%d", type.title, i + 1, total));
            }
        }

        Path out = Files.createTempFile("sonar_report_", "." + type.ext);
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    // ============================================================
    // ZIP-ФОРМАТЫ
    // ============================================================

    private Path exportHtmlZip(List<Issue> issues, boolean withTeams) throws Exception {
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
                try {
                    List<String> s = api.fetchSnippet(it.key);
                    if (!s.isEmpty()) snippets.put(it.key, s);
                } catch (Exception ignored) {}
                loaded++;
                if (listener != null && (loaded % 10 == 0 || loaded == total)) {
                    listener.onProgress(loaded, total, "snippets",
                            String.format("Фрагменты кода: %d/%d", loaded, total));
                }
            }
        }

        Path zip = Files.createTempFile("sonar_report_", ".zip");
        TeamMapping mapping = withTeams ? TeamMapping.load() : null;

        if (listener != null)
            listener.onProgress(total, total, "generating", "Сборка ZIP…");

        HtmlPerFileExporter.writeZip(zip, byFile, snippets, project, host, withTeams, mapping);
        return zip;
    }

    private Path exportLlmMdZip(List<Issue> issues) throws Exception {
        String project = ctx.getProject();
        String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);

        if (listener != null)
            listener.onProgress(issues.size(), issues.size(), "generating",
                    "Сборка LLM MD ZIP…");

        Path zip = Files.createTempFile("sonar_llm_", ".zip");
        SonarIssuesApi api = ctx.state().newApi(project);
        LlmMdExporter.writeZip(zip, byFile, api, project, host);
        return zip;
    }

    private Path exportTeamModuleZip(List<Issue> issues) throws Exception {
        String project = ctx.getProject();
        String host = hostOf();
        Map<String, List<Issue>> byFile = groupByFile(issues);

        if (listener != null)
            listener.onProgress(issues.size(), issues.size(), "generating",
                    "Сборка Team/Module ZIP…");

        Path zip = Files.createTempFile("sonar_team_", ".zip");
        SonarIssuesApi api = ctx.state().newApi(project);
        TeamMapping mapping = TeamMapping.load();
        TeamModuleMdExporter.writeZip(zip, byFile, api, project, host, mapping);
        return zip;
    }

    // ============================================================
    // UTILS
    // ============================================================

    private Map<String, List<Issue>> groupByFile(List<Issue> issues) {
        Map<String, List<Issue>> byFile = new TreeMap<>();
        for (Issue it : issues) {
            String comp = it.component != null ? it.component : "(unknown)";
            byFile.computeIfAbsent(comp, k -> new ArrayList<>()).add(it);
        }
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
        String safe = (project == null ? "report" : project)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) safe = "report";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return safe + "_" + stamp + "." + type.ext;
    }

    private static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return s == null ? "" : s; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}