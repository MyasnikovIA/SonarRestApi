package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.handlers.PromptBuilder;
import ru.miacomsoft.sonar.server.ollama.OllamaClient;
import ru.miacomsoft.sonar.server.ollama.OllamaResponse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Console {

    private static final Set<String> KNOWN_TRANSITIONS = Set.of(
            "accept", "falsepositive", "confirm", "resolve", "wontfix", "reopen");

    private final ServerState   state;
    private final OllamaConfig  ollamaConfig;
    private final PromptTemplate template;

    private volatile String    project;
    private volatile boolean   running = true;

    private volatile boolean autoRunning       = false;
    private volatile boolean autoStopRequested = false;

    private final Object consoleLock = new Object();
    private final Thread thread;

    public Console(ServerState state, OllamaConfig ollamaConfig) {
        this.state        = state;
        this.ollamaConfig = ollamaConfig;
        this.project      = state.getDefaultProject() != null ? state.getDefaultProject() : "";
        this.template     = new PromptTemplate(BootstrapFiles.resolvePromptsDir());
        this.thread       = new Thread(this::loop, "console");
        this.thread.setDaemon(true);
    }

    public void start() { thread.start(); }
    public void stop()  { running = false; }

    private void loop() {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println();
        System.out.println("===================================================");
        System.out.println(" Console ready. Type 'help' for commands.");
        System.out.println(" Prompts dir: " + template.getPromptsDir());
        System.out.println("===================================================");
        try {
            while (running) {
                synchronized (consoleLock) {
                    System.out.print(prompt());
                    System.out.flush();
                }
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
        String mode = autoRunning ? "[auto]" : "";
        return "sonar" + mode + ":" + p + "> ";
    }

    private void dispatch(String line) throws Exception {
        String[] tokens = line.split("\\s+");
        String cmd = tokens[0].toLowerCase(Locale.ROOT);
        String[] a = Arrays.copyOfRange(tokens, 1, tokens.length);

        switch (cmd) {
            case "help", "?", "h"           -> help();
            case "quit", "exit", "q"        -> {
                if (autoRunning) {
                    System.out.println("[auto] сначала выполните 'stop', затем 'quit'.");
                } else {
                    running = false;
                }
            }
            case "status"                   -> cmdStatus();
            case "health"                   -> cmdHealth();
            case "project"                  -> cmdProject(a);
            case "session"                  -> cmdSession(a);
            case "refresh"                  -> cmdRefresh(a);
            case "summary"                  -> cmdSummary(a);
            case "matrix"                   -> cmdMatrix(a);
            case "perfile"                  -> cmdPerFile(a);
            case "details"                  -> cmdDetails(a);
            case "issues"                   -> cmdIssues(a);
            case "keys"                     -> cmdKeys(a);
            case "snippet"                  -> cmdSnippet(a);
            case "mark"                     -> cmdMark(a);
            case "cancel"                   -> cmdCancel(a);
            case "cancelall", "cancel-all"  -> cmdCancelAll(a);
            case "ollama"                   -> cmdOllama(a);
            case "prompt"                   -> cmdPrompt(a);
            case "auto", "autoall"          -> cmdAuto(a);
            case "stop"                     -> cmdStop();
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
        System.out.println("autoRunning    = " + autoRunning);
        System.out.println("promptsDir     = " + template.getPromptsDir());
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

    // ==================== prompt ====================

    private void cmdPrompt(String[] a) throws Exception {
        if (a.length == 0 || "list".equalsIgnoreCase(a[0]) || "help".equalsIgnoreCase(a[0])) {
            promptHelp();
            return;
        }

        String name = a[0];
        if (!template.exists(name)) {
            System.out.println("[prompt] шаблон не найден: " + name);
            List<String> avail = template.listAvailable();
            System.out.println("Доступные: " + (avail.isEmpty() ? "<пусто>" : String.join(", ", avail)));
            return;
        }

        if (!requireProject()) return;

        String issueKey = null;
        String keysCsv = null;
        String sev = "ALL";
        String qual = "ALL";
        String status = "accept";
        String statusLabel = null;
        String savePath = null;

        for (int i = 1; i < a.length; i++) {
            String t = a[i];
            if (t.startsWith("keys="))         keysCsv     = t.substring("keys=".length());
            else if (t.startsWith("sev="))     sev         = t.substring("sev=".length()).toUpperCase(Locale.ROOT);
            else if (t.startsWith("qual="))    qual        = t.substring("qual=".length()).toUpperCase(Locale.ROOT);
            else if (t.startsWith("status="))  status      = t.substring("status=".length());
            else if (t.startsWith("statusLabel=")) statusLabel = t.substring("statusLabel=".length());
            else if (t.startsWith("save="))    savePath    = t.substring("save=".length());
            else if (issueKey == null)         issueKey    = t;
        }

        SonarIssuesApi api = state.newApi(project);
        List<String> availableTags;
        try { availableTags = new ArrayList<>(api.fetchTags(null)); }
        catch (Exception e) { availableTags = new ArrayList<>(); }

        String prompt;

        switch (name) {
            case "status-comment", "auto-resolve", "analyze-issue" -> {
                // Одиночный issue
                Issue it = null;
                if (issueKey != null) {
                    it = api.fetchIssue(issueKey);
                } else if (keysCsv != null) {
                    String firstKey = keysCsv.split(",")[0].trim();
                    if (!firstKey.isEmpty()) it = api.fetchIssue(firstKey);
                } else {
                    List<Issue> list = fetchIssues(project, sev, qual);
                    if (!list.isEmpty()) it = list.get(0);
                }

                if (it == null) {
                    System.out.println("[prompt] issue не найден");
                    return;
                }

                String ruleName = it.ruleName;
                if (ruleName == null || ruleName.isEmpty()) {
                    try { ruleName = api.fetchRuleName(it.rule); }
                    catch (Exception e) { ruleName = it.rule; }
                }

                List<String> snippet;
                try { snippet = api.fetchSnippet(it.key); }
                catch (Exception e) { snippet = new ArrayList<>(); }

                Map<String, String> params;
                if ("status-comment".equals(name)) {
                    params = PromptBuilder.buildStatusCommentParams(
                            it, ruleName, snippet, status, statusLabel, availableTags);
                } else {
                    params = PromptBuilder.buildAnalyzeIssueParams(
                            it, ruleName, snippet, availableTags);
                }
                prompt = template.render(name, params);
            }

            case "bulk-status-comment" -> {
                List<Issue> issues;
                if (keysCsv != null) {
                    issues = new ArrayList<>();
                    for (String raw : keysCsv.split(",")) {
                        String k = raw.trim();
                        if (k.isEmpty()) continue;
                        Issue it = api.fetchIssue(k);
                        if (it != null) issues.add(it);
                    }
                } else {
                    issues = fetchIssues(project, sev, qual);
                }

                if (issues.isEmpty()) {
                    System.out.println("[prompt] нет issues для выбранного набора");
                    return;
                }

                Map<String, String> params = PromptBuilder.buildBulkStatusCommentParams(
                        issues, status, statusLabel, availableTags);
                prompt = template.render(name, params);
            }

            default -> {
                System.out.println("[prompt] неизвестный шаблон: " + name);
                System.out.println("Поддерживается: status-comment | bulk-status-comment | analyze-issue | auto-resolve");
                return;
            }
        }

        if (prompt == null || prompt.isBlank()) {
            System.out.println("[prompt] промпт пуст — проверьте файл prompts/" + name + ".txt");
            return;
        }

        if (savePath != null) {
            Path out = Path.of(savePath);
            Files.writeString(out, prompt, StandardCharsets.UTF_8);
            System.out.println("[prompt] сохранено в " + out.toAbsolutePath()
                    + " (" + prompt.length() + " chars)");
        } else {
            System.out.println();
            System.out.println("===================================================");
            System.out.println(" Prompt: " + name + "  (" + prompt.length() + " chars)");
            System.out.println("===================================================");
            System.out.println(prompt);
            System.out.println("===================================================");
        }
    }

    private void promptHelp() {
        System.out.println("Доступные шаблоны в " + template.getPromptsDir() + ":");
        List<String> names = template.listAvailable();
        if (names.isEmpty()) {
            System.out.println("  <пусто> — создайте prompts/auto-resolve.txt, status-comment.txt,");
            System.out.println("            bulk-status-comment.txt, analyze-issue.txt");
        } else {
            for (String n : names) System.out.println("  " + n);
        }
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  prompt list                                    — список шаблонов");
        System.out.println("  prompt <name> <KEY> [status=accept] [statusLabel=...]");
        System.out.println("  prompt <name> keys=A,B,C [status=...]");
        System.out.println("  prompt <name> sev=ALL qual=ALL [status=...]");
        System.out.println("  prompt <name> ... save=<path>                  — сохранить в файл");
        System.out.println();
        System.out.println("Примеры:");
        System.out.println("  prompt status-comment 30637c2f-... status=accept");
        System.out.println("  prompt bulk-status-comment keys=30637c2f-...,abc123 status=accept");
        System.out.println("  prompt analyze-issue 30637c2f-...");
        System.out.println("  prompt auto-resolve 30637c2f-... save=/tmp/prompt.txt");
    }

    // ==================== mark ====================

    private void cmdMark(String[] a) throws Exception {
        if (a.length < 1) { printMarkHelp(); return; }
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

    // ==================== ollama ====================

    private void cmdOllama(String[] a) throws Exception {
        if (a.length == 0 || "show".equalsIgnoreCase(a[0])) {
            System.out.println("Ollama settings  (" + ollamaConfig.getConfigFile() + ")");
            System.out.println("  url         = " + ollamaConfig.getUrl());
            System.out.println("  model       = " + ollamaConfig.getModel());
            System.out.println("  timeout     = " + ollamaConfig.getTimeoutSeconds() + "s");
            System.out.println("  temperature = " + ollamaConfig.getTemperature());
            System.out.println();
            System.out.println("Usage:");
            System.out.println("  ollama show");
            System.out.println("  ollama set url <URL>");
            System.out.println("  ollama set model <MODEL>");
            System.out.println("  ollama set timeout <SECONDS>");
            System.out.println("  ollama set temperature <FLOAT>");
            System.out.println("  ollama test");
            System.out.println("  ollama reload");
            return;
        }

        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (a.length < 3) {
                    System.out.println("usage: ollama set <url|model|timeout|temperature> <value>");
                    return;
                }
                switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "url"         -> ollamaConfig.setUrl(a[2]);
                    case "model"       -> ollamaConfig.setModel(a[2]);
                    case "timeout"     -> ollamaConfig.setTimeoutSeconds(Integer.parseInt(a[2]));
                    case "temperature" -> ollamaConfig.setTemperature(Double.parseDouble(a[2]));
                    default -> { System.out.println("unknown key: " + a[1]); return; }
                }
                ollamaConfig.save();
                System.out.println("[ollama] saved");
            }
            case "test" -> {
                try {
                    new OllamaClient(ollamaConfig).testConnection();
                    System.out.println("[ollama] OK: " + ollamaConfig.getUrl());
                } catch (Exception e) {
                    System.err.println("[ollama] FAIL: " + e.getMessage());
                }
            }
            case "reload" -> {
                OllamaConfig fresh = new OllamaConfig(ollamaConfig.getConfigFile());
                ollamaConfig.setUrl(fresh.getUrl());
                ollamaConfig.setModel(fresh.getModel());
                ollamaConfig.setTimeoutSeconds(fresh.getTimeoutSeconds());
                ollamaConfig.setTemperature(fresh.getTemperature());
                System.out.println("[ollama] reloaded from " + ollamaConfig.getConfigFile());
            }
            default -> System.out.println("unknown sub-command: " + sub);
        }
    }

    // ==================== auto ====================

    private void cmdAuto(String[] a) throws Exception {
        if (!requireProject()) return;

        if (!ollamaConfig.isConfigured()) {
            System.out.println("[auto] Ollama не настроена.");
            System.out.println("       Установите: ollama set url http://host:port");
            System.out.println("                   ollama set model llama3");
            return;
        }

        if (autoRunning) {
            System.out.println("[auto] уже выполняется. Используйте 'stop' для остановки.");
            return;
        }

        boolean confirm = false;
        List<String> rest = new ArrayList<>();
        for (String t : a) {
            if ("--yes".equals(t)) confirm = true;
            else rest.add(t);
        }

        if (!rest.isEmpty() && "key".equalsIgnoreCase(rest.get(0))) {
            if (rest.size() < 2) {
                System.out.println("usage: auto key <ISSUE_KEY> [--yes]");
                return;
            }
            cmdAutoSingle(rest.get(1), confirm);
            return;
        }

        String[] sq = sevQual(rest.toArray(new String[0]), 0);
        SonarIssuesApi api = state.newApi(project);
        List<Issue> issues = api.fetchOpen(sq[0], sq[1]);

        System.out.printf("%nproject    = %s%n", project);
        System.out.printf("severity   = %s%n", sq[0]);
        System.out.printf("quality    = %s%n", sq[1]);
        System.out.printf("issues     = %d  (OPEN / REOPENED)%n", issues.size());
        System.out.printf("ollama     = %s  (model: %s)%n",
                ollamaConfig.getUrl(), ollamaConfig.getModel());
        System.out.printf("prompt     = %s/auto-resolve.txt%n", template.getPromptsDir());

        if (issues.isEmpty()) {
            System.out.println("\nНет issues для обработки.");
            return;
        }

        if (!confirm) {
            System.out.println("\nDRY RUN — ничего не изменено.");
            System.out.println("Первые " + Math.min(5, issues.size()) + " issues:");
            for (int i = 0; i < Math.min(5, issues.size()); i++) {
                Issue it = issues.get(i);
                System.out.printf("  %-20s  %-12s  %s%n",
                        it.key,
                        it.impactSeverity != null && !it.impactSeverity.isEmpty()
                                ? it.impactSeverity : it.severity,
                        shorten(it.message, 70));
            }
            if (issues.size() > 5) {
                System.out.println("  ... и ещё " + (issues.size() - 5));
            }
            System.out.println("\nЧтобы запустить обработку: auto "
                    + sq[0] + " " + sq[1] + " --yes");
            return;
        }

        startAutoBulk(sq[0], sq[1], issues.size());
    }

    private void startAutoBulk(String sev, String qual, int totalIssues) {
        autoRunning       = true;
        autoStopRequested = false;

        System.out.println("\n=== Запуск массовой обработки через Ollama ===\n");
        System.out.println("[auto] промпт: " + template.getPromptsDir() + "/auto-resolve.txt");
        System.out.println("[auto] запущено в фоне. Для остановки введите 'stop'.");

        final long t0 = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            AutoProcessor.Stats stats = null;
            Exception err = null;
            try {
                stats = AutoProcessor.processAll(
                        state, project, sev, qual, ollamaConfig, template,
                        this::printProgress,
                        () -> autoStopRequested
                );
            } catch (Exception e) {
                err = e;
            } finally {
                long elapsed = System.currentTimeMillis() - t0;
                autoRunning       = false;
                autoStopRequested = false;

                synchronized (consoleLock) {
                    System.out.println();
                    if (err != null) {
                        System.out.println("[auto] FAIL: " + err.getMessage());
                    }
                    if (stats != null) {
                        System.out.println("=== Результат ===");
                        System.out.printf("Всего:         %d%n", stats.total);
                        System.out.printf("Успешно:       %d%n", stats.success);
                        System.out.printf("Ошибок:        %d%n", stats.failed);
                        if (stats.cancelled) {
                            System.out.println("Остановлено:   да (по команде 'stop')");
                        }
                        if (stats.sessionExpired) {
                            System.out.println("Остановлено:   да (SESSION EXPIRED — HTTP 401)");
                        }
                        if (stats.abortedByNetwork) {
                            System.out.println("Остановлено:   да (" + stats.abortReason + ")");
                        }
                        System.out.printf("Время:         %.1f сек%n", elapsed / 1000.0);
                        if (!stats.byStatus.isEmpty()) {
                            System.out.println("По статусам:   " + stats.byStatus);
                        }
                        if (!stats.byFixDecision.isEmpty()) {
                            System.out.println("По фикс-тегам: " + stats.byFixDecision);
                        }
                        if (!stats.failedKeys.isEmpty()) {
                            int show = Math.min(stats.failedKeys.size(), 20);
                            System.out.println("Failed keys (первые " + show + " из "
                                    + stats.failedKeys.size() + "):");
                            for (int i = 0; i < show; i++) {
                                System.out.println("  " + stats.failedKeys.get(i));
                            }
                            if (stats.failedFile != null) {
                                System.out.println("Полный список: " + stats.failedFile);
                            }
                        }

                        if (stats.sessionExpired) {
                            System.out.println();
                            System.out.println("##################################################");
                            System.out.println("#  ⚠  СЕССИЯ SONARQUBE ИСТЕКЛА (HTTP 401)        #");
                            System.out.println("##################################################");
                            System.out.println("Обработано " + stats.success + " из " + stats.total
                                    + " issues, дальше — стоп.");
                            System.out.println();
                            System.out.println("Что делать:");
                            System.out.println("  1. Откройте SonarQube в браузере ("
                                    + (state.isSessionReady() ? state.getSession().host : "?") + ")");
                            System.out.println("  2. DevTools → Network → любой API-запрос");
                            System.out.println("  3. Скопируйте заголовки Cookie и X-XSRF-TOKEN в session.txt");
                            System.out.println("  4. session reload");
                            System.out.println("  5. auto " + sev + " " + qual + " --yes"
                                    + "   — продолжит с того же места");
                            System.out.println();
                        }

                        if (stats.abortedByNetwork) {
                            System.out.println();
                            System.out.println("##################################################");
                            System.out.println("#  ⚠  OLLAMA / СЕТЬ НЕДОСТУПНЫ                      #");
                            System.out.println("##################################################");
                            System.out.println("Причина: " + stats.abortReason);
                            System.out.println("Проверьте: ollama test");
                            System.out.println();
                        }

                        boolean shouldRebuild = !stats.sessionExpired && !stats.abortedByNetwork
                                && stats.success > 0
                                && state.isSnapshotReady()
                                && state.getSnapshot().projectKey.equals(project)
                                && !state.isSnapshotBuilding();
                        if (shouldRebuild) {
                            System.out.println("[auto] Обновляю снимок в фоне (status — прогресс)");
                            state.buildSnapshotAsync(project);
                        }
                    }
                }
            }
        }, "auto-processor");
        t.setDaemon(true);
        t.start();
    }

    private void cmdAutoSingle(String key, boolean confirm) throws Exception {
        SonarIssuesApi api = state.newApi(project);
        Issue it = api.fetchIssue(key);
        if (it == null) {
            System.out.println("[auto] issue не найден: " + key);
            return;
        }

        System.out.printf("%nkey        = %s%n", it.key);
        System.out.printf("status     = %s%n", it.status);
        System.out.printf("component  = %s:%d%n", it.component, it.line);
        System.out.printf("rule       = %s%n", it.rule);
        System.out.printf("message    = %s%n", shorten(it.message, 120));
        System.out.printf("ollama     = %s  (model: %s)%n",
                ollamaConfig.getUrl(), ollamaConfig.getModel());
        System.out.printf("prompt     = %s/auto-resolve.txt%n", template.getPromptsDir());

        if (!confirm) {
            System.out.println("\nDRY RUN — добавьте --yes, чтобы применить.");
            return;
        }

        try {
            String ruleName = it.ruleName;
            if (ruleName == null || ruleName.isEmpty()) {
                try { ruleName = api.fetchRuleName(it.rule); }
                catch (Exception e) { ruleName = it.rule; }
                it.ruleName = ruleName;
            }
            List<String> snippet;
            try { snippet = api.fetchSnippet(it.key); }
            catch (Exception e) { snippet = new ArrayList<>(); }

            List<String> tags = new ArrayList<>();
            try { tags = api.fetchTags(null); }
            catch (Exception e) { /* ignore */ }

            String prompt;
            try {
                Map<String, String> params =
                        PromptBuilder.buildAutoResolveParams(it, snippet, tags);
                prompt = template.render("auto-resolve", params);
                if (prompt == null || prompt.isBlank()) {
                    System.err.println("[auto] rendered prompt is blank, using fallback");
                    prompt = PromptBuilder.buildAutoResolvePrompt(it, snippet, tags);
                }
            } catch (Exception e) {
                System.err.println("[auto] template render failed, using fallback: " + e.getMessage());
                prompt = PromptBuilder.buildAutoResolvePrompt(it, snippet, tags);
            }

            System.out.println("[auto] запрос к Ollama...");
            String raw = new OllamaClient(ollamaConfig).chat(prompt);

            OllamaResponse parsed = OllamaResponse.parse(raw);
            if (parsed.status.isEmpty()) {
                System.err.println("[auto] Не удалось извлечь статус из ответа.");
                return;
            }

            System.out.printf("[auto] статус     = %s%n", parsed.status);
            System.out.printf("[auto] теги       = %s%n", parsed.tags);
            System.out.printf("[auto] комментарий:%n%s%n", parsed.comment);

            SonarBulkApi bulk = state.newBulk();
            bulk.change(List.of(it), parsed.status, parsed.comment);

            Set<String> finalTags = new LinkedHashSet<>();
            if (it.tags != null) finalTags.addAll(it.tags);
            if (parsed.tags.contains("require-fix"))     finalTags.remove("not-require-fix");
            if (parsed.tags.contains("not-require-fix")) finalTags.remove("require-fix");
            finalTags.addAll(parsed.tags);

            if (!finalTags.isEmpty()) {
                try { bulk.setTags(List.of(it), String.join(",", finalTags)); }
                catch (Exception e) { System.err.println("[auto] setTags: " + e.getMessage()); }
            }

            System.out.println("[auto] OK");

            if (state.isSnapshotReady() && !state.isSnapshotBuilding()) {
                state.refreshIssue(project, it.key);
            }
        } catch (Exception e) {
            System.err.println("[auto] FAIL: " + e.getMessage());
        }
    }

    private void cmdStop() {
        synchronized (consoleLock) {
            if (!autoRunning) {
                System.out.println("[auto] обработка не запущена.");
                return;
            }
            autoStopRequested = true;
            System.out.println("[auto] запрошена остановка "
                    + "(текущий issue будет доработан, затем цикл завершится).");
        }
    }

    private void printProgress(AutoProcessor.Progress p) {
        if (p == null || p.total == 0) return;
        String line;
        String color;
        switch (p.phase) {
            case "preparing" -> { line = String.format("[%d/%d] %s — подготовка...",
                    p.current, p.total, p.issueKey); color = "\u001B[0m"; }
            case "ollama"    -> { line = String.format("[%d/%d] %s — Ollama (%s)...",
                    p.current, p.total, p.issueKey, p.detail); color = "\u001B[0m"; }
            case "applying"  -> { line = String.format("[%d/%d] %s — применяю %s...",
                    p.current, p.total, p.issueKey, p.detail); color = "\u001B[0m"; }
            case "done"      -> { line = String.format("[%d/%d] %s → %s  OK",
                    p.current, p.total, p.issueKey, p.detail); color = "\u001B[32m"; }
            case "failed"    -> { line = String.format("[%d/%d] %s → FAIL: %s",
                    p.current, p.total, p.issueKey, p.detail); color = "\u001B[31m"; }
            default          -> { return; }
        }
        System.err.println(color + line + "\u001B[0m");
    }

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
          status                  — состояние сессии, снимка и auto-обработки
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

        Промпты (из prompts/*.txt, как /api/prompt/build в web):
          prompt list                                       — список шаблонов
          prompt <name> <KEY> [status=accept] [statusLabel=...]
          prompt <name> keys=A,B,C [status=...]             — группа issues
          prompt <name> sev=ALL qual=ALL [status=...]       — по фильтру
          prompt <name> ... save=<path>                     — сохранить в файл

          Примеры:
            prompt status-comment 30637c2f-... status=accept
            prompt bulk-status-comment keys=30637c2f-...,abc123 status=accept
            prompt analyze-issue 30637c2f-...
            prompt auto-resolve 30637c2f-... save=/tmp/prompt.txt

          Допустимые <name>:
            status-comment          → prompts/status-comment.txt
            bulk-status-comment     → prompts/bulk-status-comment.txt
            analyze-issue           → prompts/analyze-issue.txt
            auto-resolve            → prompts/auto-resolve.txt

        Ollama (LLM для авто-обработки):
          ollama show                         — текущие настройки
          ollama set url <URL>                — например http://localhost:11434
          ollama set model <MODEL>            — например llama3 или qwen2.5-coder
          ollama set timeout <SECONDS>        — таймаут запроса (по умолчанию 300)
          ollama set temperature <FLOAT>      — температура (по умолчанию 0.2)
          ollama test                         — проверить соединение и список моделей
          ollama reload                       — перечитать *.properties с диска

        Авто-обработка (массовая, аналог кнопки «Добавить автоматически комментарий»):

          Синтаксис:
            auto [SEV] [QUAL]           — dry-run: показать, что будет обработано
            auto [SEV] [QUAL] --yes     — запустить обработку в фоне
            auto key <KEY>              — dry-run по одному issue
            auto key <KEY> --yes        — обработать один issue
            stop                        — graceful-остановка фоновой обработки

          Примеры комбинаций:
            auto ALL ALL                        → dry-run всех OPEN/REOPENED
            auto ALL ALL --yes                  → обработать ВСЁ (все SEV, все QUAL)
            auto HIGH ALL --yes                 → только HIGH (любое качество)
            auto HIGH SECURITY --yes            → только HIGH + SECURITY
            auto MEDIUM ALL --yes               → только MEDIUM
            auto MEDIUM MAINTAINABILITY --yes   → только MEDIUM + MAINTAINABILITY
            auto LOW ALL --yes                  → только LOW
            auto INFO ALL --yes                 → только INFO
            auto ALL SECURITY --yes             → любые SEV, но только SECURITY
            auto ALL MAINTAINABILITY --yes      → любые SEV, только MAINTAINABILITY
            auto ALL RELIABILITY --yes          → любые SEV, только RELIABILITY
            auto key MYPROJ-1234                → dry-run одного issue
            auto key MYPROJ-1234 --yes          → обработать один issue

          Допустимые SEV:   HIGH | MEDIUM | LOW | INFO | ALL
          Допустимые QUAL:  MAINTAINABILITY | RELIABILITY | SECURITY | ALL

          Промпт берётся из prompts/auto-resolve.txt — тот же, что и в web.
          Если файла нет (или он пуст) — используется встроенный fallback.
          Теги: сохраняются существующие + добавляются предложенные LLM
          (require-fix ↔ not-require-fix заменяют друг друга).

          Во время выполнения:
            * обработка идёт в фоновом потоке — консоль принимает команды;
            * прогресс выводится в stderr (строки [N/M] ISSUE → status OK/FAIL);
            * приглашение показывает маркер: sonar[auto]:proj>;
            * команда 'stop' завершит текущий issue и корректно остановит цикл;
            * 'quit' при активной auto не сработает — сначала 'stop'.

          После успешного завершения (не отменённого):
            * снимок автоматически пересобирается в фоне (status — прогресс);
            * итоговая статистика печатается в stdout.
        """);
    }
}