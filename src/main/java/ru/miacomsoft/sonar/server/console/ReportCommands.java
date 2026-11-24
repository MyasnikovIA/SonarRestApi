package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.Reporter;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.util.List;
import java.util.Map;

/**
 * Команды отчётов и обновления снимка:
 *   refresh [wait|issue <KEY>], summary, matrix, perfile,
 *   details, issues, keys, snippet.
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

        Issue issue = null;
        Snapshot snap = ctx.snapshotFor(project);
        if (snap != null) issue = snap.byKey.get(key);

        SonarIssuesApi api = ctx.state().newApi(project);
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

        List<String> snippet = ctx.fetchSnippetCached(project, key);
        if (snippet.isEmpty()) System.out.println("(фрагмент кода недоступен)");
        else for (String line : snippet) System.out.println(line);
        System.out.println();
    }
}