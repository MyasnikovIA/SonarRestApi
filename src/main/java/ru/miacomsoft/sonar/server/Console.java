package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Интерактивная консоль. Дублирует REST-эндпоинты.
 */
public class Console {

    private static final Set<String> KNOWN_TRANSITIONS = Set.of(
            "accept", "falsepositive", "confirm", "resolve", "wontfix", "reopen");

    private final ServerState state;
    private volatile String project;
    private volatile boolean running = true;
    private final Thread thread;

    public Console(ServerState state) {
        this.state = state;
        this.project = state.getDefaultProject() != null ? state.getDefaultProject() : "";
        this.thread = new Thread(this::loop, "console");
        this.thread.setDaemon(true);
    }

    public void start() { thread.start(); }
    public void stop()  { running = false; }

    // ==================== main loop ====================

    private void loop() {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println();
        System.out.println("===================================================");
        System.out.println(" Console ready. Type 'help' for commands.");
        System.out.println("===================================================");
        try {
            while (running) {
                System.out.print(prompt());
                System.out.flush();
                String line = r.readLine();
                if (line == null) { running = false; break; }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    dispatch(line);
                } catch (Exception e) {
                    System.err.println("[console] " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[console] loop terminated: " + e.getMessage());
        }
        System.out.println("[console] stopped");
    }

    private String prompt() {
        String p = project == null ? "" : project;
        return "sonar" + (p.isEmpty() ? "" : ":" + p) + "> ";
    }

    // ==================== dispatch ====================

    private void dispatch(String line) throws Exception {
        String[] tokens = line.split("\\s+");
        String cmd = tokens[0].toLowerCase(Locale.ROOT);
        String[] a = Arrays.copyOfRange(tokens, 1, tokens.length);

        switch (cmd) {
            case "help", "?", "h"        -> help();
            case "quit", "exit", "q"     -> { running = false; }
            case "status"                -> cmdStatus();
            case "health"                -> cmdHealth();
            case "project"               -> cmdProject(a);
            case "session"               -> cmdSession(a);
            case "refresh"               -> cmdRefresh(a);
            case "summary"               -> cmdSummary(a);
            case "matrix"                -> cmdMatrix(a);
            case "perfile"               -> cmdPerFile(a);
            case "details"               -> cmdDetails(a);
            case "issues"                -> cmdIssues(a);
            case "keys"                  -> cmdKeys(a);
            case "snippet"               -> cmdSnippet(a);
            case "mark"                  -> cmdMark(a);          // single или bulk (детект)
            case "cancel"                -> cmdCancel(a);        // single reopen
            case "cancelall", "cancel-all" -> cmdCancelAll(a);   // bulk reopen
            default -> System.out.println("Unknown command: '" + cmd + "'. Try 'help'");
        }
    }

    // ==================== basic ====================

    private void cmdStatus() {
        System.out.println("session:");
        System.out.println("  ready    = " + state.isSessionReady());
        System.out.println("  path     = " + state.getSessionFile());
        if (state.isSessionReady()) {
            System.out.println("  host     = " + state.getSession().host);
            System.out.println("  hasCookie= " + (state.getSession().cookie != null
                    && !state.getSession().cookie.isEmpty()));
            System.out.println("  hasXsrf  = " + (state.getSession().xsrf != null
                    && !state.getSession().xsrf.isEmpty()));
        }
        System.out.println("snapshot:");
        System.out.println("  status   = " + state.getSnapshotStatus());
        System.out.println("  phase    = " + (state.getSnapshotPhase() == null ? "" : state.getSnapshotPhase())
                + (state.getSnapshotProgressTotal() > 0
                ? " (" + state.getSnapshotProgress() + "/" + state.getSnapshotProgressTotal() + ")"
                : ""));
        if (state.getSnapshotError() != null) {
            System.out.println("  error    = " + state.getSnapshotError());
        }
        Snapshot s = state.getSnapshot();
        if (s != null) {
            System.out.println("  project  = " + s.projectKey);
            System.out.println("  issues   = " + s.issues.size());
            System.out.println("  snippets = " + s.snippets.size());
            System.out.println("  builtAt  = " + s.builtAt);
            System.out.println("  host     = " + s.host);
        }
        System.out.println("defaultProject = "
                + (state.getDefaultProject().isEmpty() ? "<not set>" : state.getDefaultProject()));
        System.out.println("consoleProject = "
                + (project == null || project.isEmpty() ? "<not set>" : project));
    }

    private void cmdHealth() {
        System.out.println("status        = ok");
        System.out.println("sessionReady  = " + state.isSessionReady());
        System.out.println("snapshotReady = " + state.isSnapshotReady());
        System.out.println("snapshotStatus= " + state.getSnapshotStatus());
    }

    private void cmdProject(String[] a) {
        if (a.length == 0) {
            System.out.println("current project = "
                    + (project == null || project.isEmpty() ? "<not set>" : project));
            System.out.println("defaultProject  = "
                    + (state.getDefaultProject().isEmpty() ? "<not set>" : state.getDefaultProject()));
            return;
        }
        if ("clear".equalsIgnoreCase(a[0])) {
            project = "";
            System.out.println("[console] project cleared");
            return;
        }
        project = a[0];
        System.out.println("[console] console project = " + project);
    }

    // ==================== session ====================

    private void cmdSession(String[] a) throws Exception {
        if (a.length == 0) { sessionHelp(); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "show" -> {
                String raw = state.readRawFile();
                System.out.println("path    = " + state.getSessionFile());
                System.out.println("exists  = " + !raw.isEmpty());
                if (!raw.isEmpty()) {
                    System.out.println("--- raw ---");
                    System.out.println(raw);
                    System.out.println("-----------");
                }
                if (state.isSessionReady()) {
                    System.out.println("host    = " + state.getSession().host);
                    System.out.println("hasXsrf = " + (state.getSession().xsrf != null
                            && !state.getSession().xsrf.isEmpty()));
                }
            }
            case "set" -> {
                if (a.length < 2) { System.out.println("usage: session set <path>"); return; }
                String raw = Files.readString(Path.of(a[1]), StandardCharsets.UTF_8);
                state.update(raw);
                System.out.println("[console] session updated from " + a[1]);
                System.out.println("          host = " + state.getSession().host);
                if (requireProject()) {
                    state.buildSnapshotAsync(project);
                    System.out.println("[console] snapshot rebuild started in background");
                }
            }
            case "reload" -> {
                state.reload();
                System.out.println("[console] reloaded from " + state.getSessionFile());
                if (state.isSessionReady()) System.out.println("          host = " + state.getSession().host);
            }
            case "save" -> {
                if (a.length < 2) { System.out.println("usage: session save <path>"); return; }
                String raw = state.readRawFile();
                Files.writeString(Path.of(a[1]), raw, StandardCharsets.UTF_8);
                System.out.println("[console] saved to " + a[1]);
            }
            default -> sessionHelp();
        }
    }

    private void sessionHelp() {
        System.out.println("session sub-commands:");
        System.out.println("  session show              — прочитать session.txt");
        System.out.println("  session set <path>        — заменить session.txt + reload + auto build");
        System.out.println("  session reload            — перечитать session.txt с диска");
        System.out.println("  session save <path>       — сохранить текущий raw в файл");
    }

    // ==================== refresh ====================

    private void cmdRefresh(String[] a) throws Exception {
        if (a.length > 0 && "issue".equalsIgnoreCase(a[0])) {
            if (a.length < 2) { System.out.println("usage: refresh issue <KEY>"); return; }
            if (!requireProject()) return;
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
            if (!requireProject()) return;
            System.out.println("[console] full refresh (sync), please wait...");
            try {
                Snapshot s = state.buildSnapshotSync(project);
                System.out.printf("[console] done: %d issues, %d snippets, builtAt=%s%n",
                        s.issues.size(), s.snippets.size(), s.builtAt);
            } catch (Exception e) {
                System.err.println("[console] refresh failed: " + e.getMessage());
            }
            return;
        }
        if (!requireProject()) return;
        if (state.isSnapshotBuilding()) {
            System.out.println("[console] build already in progress — use 'status' to monitor");
            return;
        }
        boolean started = state.buildSnapshotAsync(project);
        System.out.println("[console] refresh " + (started ? "started" : "not started")
                + " for " + project + ". Use 'status' to monitor.");
    }

    // ==================== reports ====================

    private void cmdSummary(String[] a) throws Exception {
        if (!requireProject()) return;
        String[] sq = sevQual(a, 0);
        int total;
        Map<String, Integer> byRules, byFiles, bySev;
        Snapshot snap = snapshotFor(project);
        if (snap != null) {
            total   = snap.filter(sq[0], sq[1]).size();
            byRules = snap.facetRules(sq[0], sq[1]);
            byFiles = snap.facetFiles(sq[0], sq[1]);
            bySev   = snap.facetSeverities(sq[0], sq[1]);
        } else {
            SonarIssuesApi api = state.newApi(project);
            total   = api.count(sq[0], sq[1]);
            byRules = api.facet(sq[0], sq[1], "rules");
            byFiles = api.facet(sq[0], sq[1], "files");
            bySev   = api.facet(sq[0], sq[1], "severities");
        }
        System.out.printf("%nВсего %s/%s: %d%n", sq[0], sq[1], total);
        ru.miacomsoft.sonar.Reporter.printFacet("По правилам", byRules);
        ru.miacomsoft.sonar.Reporter.printFacet("По файлам", byFiles);
        ru.miacomsoft.sonar.Reporter.printFacet("По severity", bySev);
    }

    private void cmdMatrix(String[] a) throws Exception {
        if (!requireProject()) return;
        String[] sq = sevQual(a, 0);
        List<Issue> issues = fetchIssues(project, sq[0], sq[1]);
        System.out.println("Всего: " + issues.size());
        ru.miacomsoft.sonar.Reporter.printMatrix(issues);
    }

    private void cmdPerFile(String[] a) throws Exception {
        if (!requireProject()) return;
        String[] sq = sevQual(a, 0);
        Map<String, List<Issue>> byFile = fetchIssuesByFile(project, sq[0], sq[1]);
        ru.miacomsoft.sonar.Reporter.printPerFile(byFile);
    }

    private void cmdDetails(String[] a) throws Exception {
        if (!requireProject()) return;
        String[] sq = sevQual(a, 0);
        int snippets = 5;
        if (a.length > 2) {
            try { snippets = Integer.parseInt(a[2]); } catch (NumberFormatException ignore) {}
        }
        Map<String, List<Issue>> byFile = fetchIssuesByFile(project, sq[0], sq[1]);
        SonarIssuesApi api = state.newApi(project);
        ru.miacomsoft.sonar.Reporter.printDetailedByFileWithSnippets(byFile, api, snippets);
    }

    private void cmdIssues(String[] a) throws Exception {
        if (!requireProject()) return;
        String[] sq = sevQual(a, 0);
        List<Issue> issues = fetchIssues(project, sq[0], sq[1]);
        System.out.printf("%n%d issues (%s/%s)%n", issues.size(), sq[0], sq[1]);
        for (Issue it : issues) {
            String sev = it.impactSeverity != null && !it.impactSeverity.isEmpty()
                    ? it.impactSeverity : it.severity;
            System.out.printf("  %-10s %-6s %-40s %-12s %s%n",
                    it.key, sev, it.component + ":" + it.line, it.rule,
                    shorten(it.message, 80));
        }
    }

    private void cmdKeys(String[] a) throws Exception {
        if (!requireProject()) return;
        String[] sq = sevQual(a, 0);
        List<Issue> issues = fetchIssues(project, sq[0], sq[1]);
        System.out.printf("%n%d keys (%s/%s)%n", issues.size(), sq[0], sq[1]);
        for (Issue it : issues) System.out.println("  " + it.key);
    }

    private void cmdSnippet(String[] a) throws Exception {
        if (a.length < 1) { System.out.println("usage: snippet <KEY>"); return; }
        if (!requireProject()) return;
        String key = a[0];

        Issue issue = null;
        Snapshot snap = snapshotFor(project);
        if (snap != null) issue = snap.byKey.get(key);

        SonarIssuesApi api = state.newApi(project);
        if (issue == null) {
            issue = api.fetchIssue(key);
            if (issue == null) { System.out.println("[console] issue not found: " + key); return; }
            try { issue.ruleName = api.fetchRuleName(issue.rule); } catch (Exception ignored) {}
        }

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println(issue.message);
        if (issue.ruleName != null && !issue.ruleName.isEmpty()) System.out.println(issue.ruleName);
        System.out.println(issue.rule + " | " + issue.component + " | L" + issue.line);
        System.out.println("status = " + issue.status + " | severity = "
                + (issue.impactSeverity != null && !issue.impactSeverity.isEmpty()
                ? issue.impactSeverity : issue.severity));
        System.out.println("=".repeat(80));

        List<String> snippet = fetchSnippetCached(project, key);
        if (snippet.isEmpty()) System.out.println("(фрагмент кода недоступен)");
        else for (String line : snippet) System.out.println(line);
        System.out.println();
    }

    // ==================== mark ====================

    private void cmdMark(String[] a) throws Exception {
        if (a.length < 1) { printMarkHelp(); return; }
        // если первый токен — известный transition → bulk; иначе single
        if (KNOWN_TRANSITIONS.contains(a[0].toLowerCase(Locale.ROOT))) {
            cmdMarkBulk(a);
        } else {
            cmdMarkOne(a);
        }
    }

    private void printMarkHelp() {
        System.out.println("usage:");
        System.out.println("  mark <KEY> <transition> [comment...]");
        System.out.println("  mark <transition> [SEV] [QUAL] [--yes]");
        System.out.println("  transition = accept | falsepositive | confirm | resolve | wontfix | reopen");
    }

    /** mark <KEY> <transition> [comment...] — пометить один issue. */
    private void cmdMarkOne(String[] a) throws Exception {
        if (a.length < 2) { printMarkHelp(); return; }
        if (!requireProject()) return;

        String key = a[0];
        String transition = a[1];
        String comment = a.length > 2
                ? String.join(" ", Arrays.copyOfRange(a, 2, a.length))
                : "";

        System.out.println("[mark] " + key + " -> " + transition
                + (comment.isEmpty() ? "" : " (comment: " + comment + ")") + " ...");

        SonarBulkApi bulk = state.newBulk();
        try {
            Issue stub = new Issue();
            stub.key = key;

            if ("reopen".equalsIgnoreCase(transition)) {
                Set<String> failed = bulk.reopenIssues(List.of(stub));
                if (failed.contains(key)) {
                    System.err.println("[mark] FAIL: reopen failed");
                    return;
                }
            } else {
                bulk.change(List.of(stub), transition, comment);
            }
            System.out.println("[mark] OK");
            autoRefreshIssue(key);
        } catch (Exception e) {
            System.err.println("[mark] FAIL: " + e.getMessage());
        }
    }

    /** mark <transition> [SEV] [QUAL] [--yes] — массовая пометка по фильтру. */
    private void cmdMarkBulk(String[] a) throws Exception {
        if (!requireProject()) return;

        boolean confirm = false;
        List<String> rest = new ArrayList<>();
        for (String t : a) {
            if ("--yes".equals(t)) confirm = true;
            else rest.add(t);
        }
        String transition = rest.get(0);
        String[] sq = new String[]{"MEDIUM", "MAINTAINABILITY"};
        if (rest.size() > 1) sq[0] = rest.get(1).toUpperCase(Locale.ROOT);
        if (rest.size() > 2) sq[1] = rest.get(2).toUpperCase(Locale.ROOT);

        SonarIssuesApi api = state.newApi(project);
        List<Issue> issues = api.fetchAll(sq[0], sq[1]);
        SonarBulkApi bulk = state.newBulk();

        System.out.printf("%nproject    = %s%n", project);
        System.out.printf("severity   = %s%n", sq[0]);
        System.out.printf("quality    = %s%n", sq[1]);
        System.out.printf("transition = %s%n", transition);
        System.out.printf("issues     = %d%n", issues.size());
        System.out.printf("batches    = %d (размер %d)%n",
                bulk.batchCount(issues.size()), state.getBatchSize());

        if (!confirm) {
            System.out.println("\nDRY RUN — ничего не изменено.");
            System.out.println("Чтобы применить: mark " + transition + " "
                    + sq[0] + " " + sq[1] + " --yes");
            return;
        }

        System.out.println("\nПрименяю переход...");
        if ("reopen".equalsIgnoreCase(transition)) {
            Set<String> failed = bulk.reopenIssues(issues);
            System.out.printf("[console] reopened %d / %d (failed: %d)%n",
                    issues.size() - failed.size(), issues.size(), failed.size());
            if (!failed.isEmpty()) {
                System.err.println("failed keys: " + String.join(", ", failed));
            }
        } else {
            List<String> responses = bulk.change(issues, transition, "");
            System.out.printf("[console] applied to %d issues in %d batches%n",
                    issues.size(), responses.size());
        }

        if (state.isSnapshotReady()
                && state.getSnapshot().projectKey.equals(project)
                && !state.isSnapshotBuilding()) {
            state.buildSnapshotAsync(project);
            System.out.println("[console] snapshot rebuild started in background "
                    + "('status' to monitor)");
        }
    }

    /** cancel <KEY> — снять пометку (reopen) с одного issue. */
    private void cmdCancel(String[] a) throws Exception {
        if (a.length < 1) { System.out.println("usage: cancel <KEY>"); return; }
        if (!requireProject()) return;

        String key = a[0];
        System.out.println("[cancel] " + key + " -> reopen ...");

        Issue stub = new Issue();
        stub.key = key;
        try {
            Set<String> failed = state.newBulk().reopenIssues(List.of(stub));
            if (failed.contains(key)) {
                System.err.println("[cancel] FAIL: reopen failed");
                return;
            }
            System.out.println("[cancel] OK");
            autoRefreshIssue(key);
        } catch (Exception e) {
            System.err.println("[cancel] FAIL: " + e.getMessage());
        }
    }

    /** cancelall [SEV] [QUAL] [--yes] — массовый reopen по фильтру. */
    private void cmdCancelAll(String[] a) throws Exception {
        if (!requireProject()) return;

        boolean confirm = false;
        List<String> rest = new ArrayList<>();
        for (String t : a) {
            if ("--yes".equals(t)) confirm = true;
            else rest.add(t);
        }
        String[] sq = new String[]{"MEDIUM", "MAINTAINABILITY"};
        if (rest.size() > 0) sq[0] = rest.get(0).toUpperCase(Locale.ROOT);
        if (rest.size() > 1) sq[1] = rest.get(1).toUpperCase(Locale.ROOT);

        SonarIssuesApi api = state.newApi(project);
        List<Issue> issues = api.fetchAll(sq[0], sq[1]);

        System.out.printf("%nproject  = %s%n", project);
        System.out.printf("severity = %s%n", sq[0]);
        System.out.printf("quality  = %s%n", sq[1]);
        System.out.printf("issues   = %d%n", issues.size());

        if (!confirm) {
            System.out.println("\nDRY RUN — ничего не изменено.");
            System.out.println("Чтобы применить: cancelall " + sq[0] + " " + sq[1] + " --yes");
            return;
        }

        System.out.println("\nОткрываю issues обратно (reopen)...");
        Set<String> failed = state.newBulk().reopenIssues(issues);
        System.out.printf("[console] reopened %d / %d (failed: %d)%n",
                issues.size() - failed.size(), issues.size(), failed.size());
        if (!failed.isEmpty()) {
            System.err.println("failed keys: " + String.join(", ", failed));
        }

        if (state.isSnapshotReady()
                && state.getSnapshot().projectKey.equals(project)
                && !state.isSnapshotBuilding()) {
            state.buildSnapshotAsync(project);
            System.out.println("[console] snapshot rebuild started in background");
        }
    }

    private void autoRefreshIssue(String key) {
        if (state.isSnapshotReady()
                && state.getSnapshot().projectKey.equals(project)
                && !state.isSnapshotBuilding()) {
            try {
                state.refreshIssue(project, key);
            } catch (Exception e) {
                System.err.println("[refresh] auto-refresh failed: " + e.getMessage());
            }
        }
    }

    // ==================== helpers ====================

    private boolean requireProject() {
        if (project == null || project.isEmpty()) {
            System.out.println("[console] project не задан. Используйте 'project <KEY>' "
                    + "или задайте --project / SONAR_PROJECT при старте.");
            return false;
        }
        return true;
    }

    private Snapshot snapshotFor(String projectKey) {
        Snapshot s = state.getSnapshot();
        return (s != null && s.projectKey.equals(projectKey)) ? s : null;
    }

    private List<Issue> fetchIssues(String project, String sev, String qual) throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) return s.filter(sev, qual);
        return state.newApi(project).fetchAll(sev, qual);
    }

    private Map<String, List<Issue>> fetchIssuesByFile(String project, String sev, String qual)
            throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) return s.filterByFile(sev, qual);
        return state.newApi(project).issuesByFile(sev, qual);
    }

    private List<String> fetchSnippetCached(String project, String key) throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) {
            List<String> cached = s.snippet(key);
            if (!cached.isEmpty()) return cached;
        }
        return state.newApi(project).fetchSnippet(key);
    }

    private static String[] sevQual(String[] a, int from) {
        String sev = a.length > from ? a[from].toUpperCase(Locale.ROOT) : "MEDIUM";
        String qual = a.length > from + 1 ? a[from + 1].toUpperCase(Locale.ROOT)
                : "MAINTAINABILITY";
        return new String[]{sev, qual};
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private void help() {
        System.out.println("""
            Console commands (все команды — дубли REST-эндпоинтов):

            Основное:
              help, ?                 — эта справка
              quit, exit, q           — остановить консоль (сервер продолжит работу)
              status                  — состояние сессии и снимка
              health                  — краткая проверка
              project [KEY | clear]   — показать/задать консольный project

            Сессия:
              session show            — прочитать session.txt
              session set <path>      — заменить session.txt из файла + reload + auto build
              session reload          — перечитать session.txt с диска
              session save <path>     — сохранить текущий raw в файл

            Снимок:
              refresh                 — пересобрать снимок в фоне (async)
              refresh wait            — пересобрать снимок синхронно
              refresh issue <KEY>     — обновить один issue в снимке

            Отчёты (по умолчанию sev=MEDIUM, qual=MAINTAINABILITY):
              summary  [SEV] [QUAL]              — сводка + facets
              matrix   [SEV] [QUAL]              — матрица file × rule
              perfile  [SEV] [QUAL]              — замечания по файлам
              details  [SEV] [QUAL] [snippets=N] — подробно с фрагментами кода
              issues   [SEV] [QUAL]              — плоский список
              keys     [SEV] [QUAL]              — только ключи
              snippet  <KEY>                     — один issue + код

            Пометки:
              mark <KEY> <transition> [comment...]  — пометить один issue
              mark <transition> [SEV] [QUAL] [--yes] — пометить всё по фильтру
                  transition = accept | falsepositive | confirm | resolve | wontfix | reopen
                  без --yes — dry-run
              cancel <KEY>                          — снять пометку (reopen)
              cancelall [SEV] [QUAL] [--yes]        — снять пометки по фильтру

            SEV  = MEDIUM | HIGH | LOW | INFO | ALL
            QUAL = MAINTAINABILITY | RELIABILITY | SECURITY | ALL
            """);
    }
}