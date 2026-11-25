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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Команды отчётов и обновления снимка.
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
            total   = api.count(sq[0], sq[1]);
            byRules = api.facet(sq[0], sq[1], "rules");
            byFiles = api.facet(sq[0], sq[1], "files");
            bySev   = api.facet(sq[0], sq[1], "severities");
        }
        System.out.printf("%nВсего %s/%s: %d%n", sq[0], sq[1], total);
        Reporter.printFacet("По правилам", byRules);
        Reporter.printFacet("По файлам", byFiles);
        Reporter.printFacet("По severity", bySev);
    }

    public void handleMatrix(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(a, 0);
        List<Issue> issues = ctx.fetchIssues(project, sq[0], sq[1]);
        System.out.println("Всего: " + issues.size());
        Reporter.printMatrix(issues);
    }

    public void handlePerFile(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(a, 0);
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
        Map<String, List<Issue>> byFile = ctx.fetchIssuesByFile(project, sq[0], sq[1]);
        SonarIssuesApi api = ctx.state().newApi(project);
        Reporter.printDetailedByFileWithSnippets(byFile, api, snippets);
    }

    public void handleIssues(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(a, 0);
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

    /**
     * Сбор issues с учетом фильтров SEV и QUAL.
     * Обратите внимание: qual передается напрямую в методы SonarIssuesApi:
     *  - api.facetWithStatuses(..., sev, qual, ...)
     *  - api.fetchPageRaw(..., sev, qual, ...)
     *  - api.fetchPageRawForComponent(..., sev, qual, ...)
     */
    private Map<String, List<Issue>> collectIssues(SonarIssuesApi api, String project, String sev, String qual) throws Exception {
        Map<String, List<Issue>> issuesByFile = new TreeMap<>();
        int totalDownloaded = 0;
        int totalFilteredOut = 0;
        int totalKept = 0;

        System.out.println("[team-report] 1. Запрос списка всех правил в проекте...");
        // ИСПОЛЬЗОВАНИЕ QUAL:
        Map<String, Integer> ruleCounts = api.facetWithStatuses(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, null, "rules");
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
            System.out.printf("[team-report] --- Блок %d/%d (правил: %d) ---\n", i + 1, ruleChunks.size(), chunk.size());

            int page = 1;
            while (true) {
                try {
                    // ИСПОЛЬЗОВАНИЕ QUAL:
                    JSONObject raw = api.fetchPageRaw(
                            SonarIssuesApi.ACTIVE_STATUSES, sev, qual,
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
                        String comp = it.component != null ? it.component : "(unknown)";
                        issuesByFile.computeIfAbsent(comp, k -> new ArrayList<>()).add(it);
                        totalKept++;
                    }

                    if (arr.length() < 500) break;
                    page++;
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("10000")) {
                        System.out.println("[team-report]   !!! Достигнут лимит (10000). Разбиваем по файлам...");
                        // ИСПОЛЬЗОВАНИЕ QUAL:
                        Map<String, Integer> fileCounts = api.facetWithStatuses(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, rulesCsv, "files");
                        if (fileCounts != null) {
                            for (String file : fileCounts.keySet()) {
                                int fPage = 1;
                                while (true) {
                                    // ИСПОЛЬЗОВАНИЕ QUAL:
                                    JSONObject fRaw = api.fetchPageRawForComponent(
                                            SonarIssuesApi.ACTIVE_STATUSES, sev, qual,
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

    public void handleTeamZip(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        ServerState state = ctx.state();
        SonarIssuesApi api = state.newApi(project);

        String sev = "ALL";
        String qual = "ALL";
        String outName = null;

        // Регулярные выражения для SEV (включая BLOCKER) и QUAL
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

        // Регулярные выражения для SEV (включая BLOCKER) и QUAL
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