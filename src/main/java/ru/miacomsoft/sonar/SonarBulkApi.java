package ru.miacomsoft.sonar;

import java.util.*;

public class SonarBulkApi {

    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final long DEFAULT_DELAY_MS = 500;
    public static final long REOPEN_DELAY_MS = 120;

    private final SonarClient client;
    private final int batchSize;
    private final long delayMs;

    public SonarBulkApi(SonarClient client) {
        this(client, DEFAULT_BATCH_SIZE, DEFAULT_DELAY_MS);
    }

    public SonarBulkApi(SonarClient client, int batchSize, long delayMs) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize > 0");
        if (delayMs < 0) throw new IllegalArgumentException("delayMs >= 0");
        this.client = client;
        this.batchSize = batchSize;
        this.delayMs = delayMs;
    }

    public int batchCount(int totalIssues) {
        return (totalIssues + batchSize - 1) / batchSize;
    }

    public Map<String, Object> dryRun(List<Issue> issues, String transition, String comment) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("issues", issues.size());
        r.put("transition", transition);
        r.put("comment", comment);
        r.put("batchSize", batchSize);
        r.put("batches", batchCount(issues.size()));
        return r;
    }

    public List<String> change(List<Issue> issues, String transition, String comment) throws Exception {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < issues.size(); i += batchSize) {
            List<Issue> batch = issues.subList(i, Math.min(i + batchSize, issues.size()));

            StringBuilder keys = new StringBuilder();
            for (Issue it : batch) {
                if (keys.length() > 0) keys.append(',');
                keys.append(it.key);
            }

            Map<String, String> form = new LinkedHashMap<>();
            form.put("issues", keys.toString());
            form.put("do_transition", transition);
            if (comment != null && !comment.isEmpty()) {
                form.put("comment", comment);
            }

            String resp = client.postForm("/api/issues/bulk_change", form);
            results.add(resp);
            System.out.printf("Батч %d/%d (%d): OK%n",
                    (i / batchSize + 1), batchCount(issues.size()), batch.size());

            if (i + batchSize < issues.size() && delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        return results;
    }

    public void doTransition(String issueKey, String transition) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("issue", issueKey);
        form.put("transition", transition);
        client.postForm("/api/issues/do_transition", form);
    }

    public void addComment(String issueKey, String text) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("issue", issueKey);
        form.put("text", text);
        client.postForm("/api/issues/add_comment", form);
    }

    // НОВОЕ: Массовое обновление тегов
    public void setTags(List<Issue> issues, String tagsCsv) throws Exception {
        if (issues.isEmpty()) return;
        List<String> keys = new ArrayList<>();
        for (Issue it : issues) keys.add(it.key);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("issues", String.join(",", keys));
        form.put("tags", tagsCsv);
        client.postForm("/api/issues/set_tags", form);
    }

    public Set<String> reopenIssues(List<Issue> issues) throws Exception {
        Set<String> failed = new HashSet<>();
        int total = issues.size();
        for (int i = 0; i < total; i++) {
            Issue it = issues.get(i);
            try {
                doTransition(it.key, "reopen");
                System.out.printf("reopen [%d/%d] %s: OK%n", i + 1, total, it.key);
            } catch (Exception e) {
                System.out.printf("reopen [%d/%d] %s: FAIL %s%n",
                        i + 1, total, it.key, e.getMessage());
                failed.add(it.key);
            }
            if (i < total - 1) Thread.sleep(REOPEN_DELAY_MS);
        }
        return failed;
    }
}