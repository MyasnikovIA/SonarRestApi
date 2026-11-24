package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Команды пометок: mark (одиночный/массовый), cancel, cancelall.
 * Плюс общий хелпер {@link #autoRefreshIssue(String)} — точечное обновление
 * одного issue в снимке после изменения статуса.
 */
public class MarkCommands {

    private static final Set<String> KNOWN_TRANSITIONS = Set.of(
            "accept", "falsepositive", "confirm", "resolve", "wontfix", "reopen");

    private final ConsoleContext ctx;

    public MarkCommands(ConsoleContext ctx) {
        this.ctx = ctx;
    }

    public void handleMark(String[] a) throws Exception {
        if (a.length < 1) { printMarkHelp(); return; }
        if (KNOWN_TRANSITIONS.contains(a[0].toLowerCase(Locale.ROOT))) {
            markBulk(a);
        } else {
            markOne(a);
        }
    }

    public void handleCancel(String[] a) throws Exception {
        if (a.length < 1) { System.out.println("usage: cancel <KEY>"); return; }
        if (!ctx.requireProject()) return;

        String key = a[0];
        System.out.println("[cancel] " + key + " -> reopen ...");

        Issue stub = new Issue();
        stub.key = key;
        try {
            Set<String> failed = ctx.state().newBulk().reopenIssues(List.of(stub));
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

    public void handleCancelAll(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        ServerState state = ctx.state();

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

    // ==================== internals ====================

    private void printMarkHelp() {
        System.out.println("usage:");
        System.out.println("  mark <KEY> <transition> [comment...]");
        System.out.println("  mark <transition> [SEV] [QUAL] [--yes]");
        System.out.println("  transition = accept | falsepositive | confirm | resolve | wontfix | reopen");
    }

    private void markOne(String[] a) throws Exception {
        if (a.length < 2) { printMarkHelp(); return; }
        if (!ctx.requireProject()) return;

        String key        = a[0];
        String transition = a[1];
        String comment    = a.length > 2
                ? String.join(" ", Arrays.copyOfRange(a, 2, a.length))
                : "";

        System.out.println("[mark] " + key + " -> " + transition
                + (comment.isEmpty() ? "" : " (comment: " + comment + ")") + " ...");

        SonarBulkApi bulk = ctx.state().newBulk();
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

    private void markBulk(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        String project = ctx.getProject();
        ServerState state = ctx.state();

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

    private void autoRefreshIssue(String key) {
        ServerState state = ctx.state();
        String project = ctx.getProject();
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
}