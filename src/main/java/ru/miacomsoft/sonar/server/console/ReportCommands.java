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