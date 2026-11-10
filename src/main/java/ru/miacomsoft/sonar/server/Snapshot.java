package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Неизменяемый снимок состояния проекта в Sonar.
 * Содержит полный список issues, привязанные ruleName и snippets,
 * а также готовые фильтры/facets, которые вычисляются на лету.
 *
 * Точечное обновление одного issue реализовано через copy-on-write:
 * withIssue / withoutIssue возвращают новый Snapshot.
 */
public class Snapshot {

    public interface ProgressCallback {
        void update(String phase, int current, int total);
        ProgressCallback NOOP = (phase, cur, tot) -> {};
    }

    public final Instant builtAt;
    public final String projectKey;
    public final String host;
    public final List<Issue> issues;
    public final Map<String, Issue> byKey;
    public final Map<String, List<String>> snippets;

    public Snapshot(Instant builtAt, String projectKey, String host,
                    List<Issue> issues, Map<String, List<String>> snippets) {
        this.builtAt = builtAt;
        this.projectKey = projectKey;
        this.host = host;
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));

        Map<String, Issue> m = new HashMap<>(Math.max(16, issues.size() * 2));
        for (Issue i : issues) m.put(i.key, i);
        this.byKey = Collections.unmodifiableMap(m);

        this.snippets = Collections.unmodifiableMap(new HashMap<>(snippets));
    }

    /**
     * Полный проход по проекту:
     *   1. fetchAll("ALL","ALL") — все CONFIRMED/OPEN issues
     *   2. fetchRuleName для каждого issue (кэш на уровне ServerState — быстро)
     *   3. fetchSnippet для первых N issues (если fetchSnippets == true)
     */
    public static Snapshot build(SonarIssuesApi api, String projectKey, String host,
                                 boolean fetchSnippets, int snippetLimit,
                                 ProgressCallback cb) throws Exception {
        if (cb == null) cb = ProgressCallback.NOOP;
        Instant start = Instant.now();

        cb.update("fetching_issues", 0, 0);
        List<Issue> issues = api.fetchAll("ALL", "ALL");
        int total = issues.size();
        cb.update("fetching_issues", total, total);
        System.out.printf("[Snapshot] fetched %d issues%n", total);

        int step = 25;
        for (int i = 0; i < total; i++) {
            try { issues.get(i).ruleName = api.fetchRuleName(issues.get(i).rule); }
            catch (Exception e) { issues.get(i).ruleName = ""; }
            if ((i + 1) % step == 0 || i + 1 == total) {
                cb.update("resolving_rules", i + 1, total);
            }
        }
        System.out.printf("[Snapshot] rule names resolved (%d issues)%n", total);

        Map<String, List<String>> snippets = new HashMap<>();
        if (fetchSnippets) {
            int limit = snippetLimit > 0 ? Math.min(snippetLimit, total) : total;
            int sstep = 10;
            for (int i = 0; i < limit; i++) {
                Issue it = issues.get(i);
                try { snippets.put(it.key, api.fetchSnippet(it.key)); }
                catch (Exception e) { snippets.put(it.key, List.of()); }
                if ((i + 1) % sstep == 0 || i + 1 == limit) {
                    cb.update("fetching_snippets", i + 1, limit);
                }
            }
            System.out.printf("[Snapshot] snippets fetched (%d / %d)%n", snippets.size(), limit);
        } else {
            cb.update("fetching_snippets", 0, 0);
        }

        cb.update("done", 0, 0);
        long sec = Duration.between(start, Instant.now()).getSeconds();
        System.out.printf("[Snapshot] build complete in %ds%n", sec);

        return new Snapshot(Instant.now(), projectKey, host, issues, snippets);
    }

    // ================= фильтры =================

    public List<Issue> filter(String sev, String qual) {
        if ("ALL".equals(sev) && "ALL".equals(qual)) return issues;
        List<Issue> out = new ArrayList<>();
        for (Issue i : issues) {
            if (i.matches(sev, qual)) out.add(i);
        }
        return out;
    }

    public Map<String, List<Issue>> filterByFile(String sev, String qual) {
        Map<String, List<Issue>> byFile = new TreeMap<>();
        for (Issue i : filter(sev, qual)) {
            byFile.computeIfAbsent(i.component, k -> new ArrayList<>()).add(i);
        }
        for (List<Issue> list : byFile.values()) {
            list.sort(Comparator.comparingInt(i -> i.line));
        }
        return byFile;
    }

    public Map<String, Integer> facetRules(String sev, String qual) {
        Map<String, Integer> m = new TreeMap<>();
        for (Issue i : filter(sev, qual)) m.merge(i.rule, 1, Integer::sum);
        return m;
    }

    public Map<String, Integer> facetFiles(String sev, String qual) {
        Map<String, Integer> m = new TreeMap<>();
        for (Issue i : filter(sev, qual)) m.merge(i.component, 1, Integer::sum);
        return m;
    }

    public Map<String, Integer> facetSeverities(String sev, String qual) {
        Map<String, Integer> m = new TreeMap<>();
        for (Issue i : filter(sev, qual)) {
            String s = (i.impactSeverity != null && !i.impactSeverity.isEmpty())
                    ? i.impactSeverity
                    : (i.severity != null ? i.severity : "UNKNOWN");
            m.merge(s, 1, Integer::sum);
        }
        return m;
    }

    public List<String> snippet(String issueKey) {
        return snippets.getOrDefault(issueKey, List.of());
    }

    // ================= copy-on-write =================

    public Snapshot withIssue(Issue updated) {
        return withIssueAndSnippet(updated, null);
    }

    public Snapshot withIssueAndSnippet(Issue updated, List<String> newSnippet) {
        List<Issue> newIssues = new ArrayList<>(issues.size());
        boolean replaced = false;
        for (Issue i : issues) {
            if (i.key.equals(updated.key)) {
                newIssues.add(updated);
                replaced = true;
            } else {
                newIssues.add(i);
            }
        }
        if (!replaced) newIssues.add(updated);

        Map<String, List<String>> newSnippets = new HashMap<>(snippets);
        if (newSnippet != null && !newSnippet.isEmpty()) {
            newSnippets.put(updated.key, newSnippet);
        }
        return new Snapshot(builtAt, projectKey, host, newIssues, newSnippets);
    }

    public Snapshot withoutIssue(String issueKey) {
        List<Issue> newIssues = new ArrayList<>(issues.size());
        for (Issue i : issues) {
            if (!i.key.equals(issueKey)) newIssues.add(i);
        }
        Map<String, List<String>> newSnippets = new HashMap<>(snippets);
        newSnippets.remove(issueKey);
        return new Snapshot(builtAt, projectKey, host, newIssues, newSnippets);
    }
}