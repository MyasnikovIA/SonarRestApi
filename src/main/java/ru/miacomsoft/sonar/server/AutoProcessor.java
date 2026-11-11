package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarClient.SessionExpiredException;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.handlers.PromptBuilder;
import ru.miacomsoft.sonar.server.ollama.OllamaClient;
import ru.miacomsoft.sonar.server.ollama.OllamaResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Массовая автоматическая обработка issues через Ollama.
 * Аналог JS-функционала "Добавить автоматически комментарий" из perfile/details.
 *
 * Поведение при ошибках:
 *   - SessionExpiredException (HTTP 401) — немедленная остановка всего прогона,
 *     флаг sessionExpired = true, инструкция пользователю выводится в Console;
 *   - 3 подряд сетевые ошибки (Ollama/сеть) — тоже остановка (нет смысла ждать);
 *   - любая другая ошибка — failed++, продолжаем со следующего issue;
 *   - по завершении — failedKeys записываются в файл auto-failed-*.txt.
 */
public class AutoProcessor {

    /** Порог подряд идущих сетевых ошибок для аварийной остановки. */
    private static final int MAX_CONSECUTIVE_NETWORK_ERRORS = 3;

    public interface ProgressCallback {
        void update(Progress p);
    }

    public static class Progress {
        public String phase;    // preparing | ollama | applying | done | failed | complete
        public int    current;
        public int    total;
        public String issueKey;
        public String detail;
    }

    public static class Stats {
        public int    total;
        public int    success;
        public int    failed;
        public boolean cancelled;
        public boolean sessionExpired;
        public boolean abortedByNetwork;
        public String  abortReason;
        public String  failedFile;                 // путь к сохранённому *.txt
        public final List<String> failedKeys   = new ArrayList<>();
        public final Map<String, Integer> byStatus       = new TreeMap<>();
        public final Map<String, Integer> byFixDecision  = new TreeMap<>();
    }

    public static Stats processAll(ServerState state, String project,
                                   String sev, String qual,
                                   OllamaConfig config,
                                   ProgressCallback onProgress,
                                   BooleanSupplier stopChecker) throws Exception {

        SonarIssuesApi api = state.newApi(project);
        List<Issue> issues = api.fetchOpen(sev, qual);  // OPEN (+REOPENED, если поддерживается)

        Stats stats = new Stats();
        stats.total = issues.size();
        if (issues.isEmpty()) {
            Progress p = new Progress();
            p.phase = "complete"; p.total = 0; p.current = 0;
            if (onProgress != null) onProgress.update(p);
            return stats;
        }

        // Доступные теги — 1 раз на прогон, пополняем по ходу
        List<String> availableTags = new ArrayList<>();
        try {
            List<String> raw = api.fetchTags(null);
            if (raw != null) availableTags.addAll(raw);
        } catch (Exception e) {
            System.err.println("[auto] tags fetch failed: " + e.getMessage());
        }

        OllamaClient ollama = new OllamaClient(config);
        SonarBulkApi bulk   = state.newBulk();
        bulk.setSilent(true);                    // ← убираем "Батч 1/1 ... OK"

        int i = 0;
        int consecutiveNetworkErrors = 0;
        long runT0 = System.currentTimeMillis();

        for (Issue it : issues) {
            // ---- graceful stop между итерациями ----
            if (stopChecker != null && stopChecker.getAsBoolean()) {
                stats.cancelled = true;
                System.err.println("[auto] остановка запрошена пользователем");
                break;
            }

            i++;
            long issueT0 = System.currentTimeMillis();

            Progress prog = new Progress();
            prog.current  = i;
            prog.total    = issues.size();
            prog.issueKey = it.key;
            prog.phase    = "preparing";
            if (onProgress != null) onProgress.update(prog);

            try {
                // ---------- 1. ruleName ----------
                if (it.ruleName == null || it.ruleName.isEmpty()) {
                    try { it.ruleName = api.fetchRuleName(it.rule); }
                    catch (Exception e) { it.ruleName = it.rule; }
                }

                // ---------- 2. snippet ----------
                List<String> snippet;
                try { snippet = api.fetchSnippet(it.key); }
                catch (Exception e) { snippet = new ArrayList<>(); }

                // ---------- 3. prompt ----------
                String prompt = PromptBuilder.buildAutoResolvePrompt(it, snippet, availableTags);

                // ---------- 4. Ollama ----------
                prog.phase  = "ollama";
                prog.detail = config.getModel();
                if (onProgress != null) onProgress.update(prog);

                String raw = ollama.chat(prompt);

                OllamaResponse parsed = OllamaResponse.parse(raw);
                if (parsed.status == null || parsed.status.isEmpty()) {
                    throw new Exception("Не удалось извлечь статус из ответа LLM");
                }

                // ---------- 5. Применяем статус ----------
                prog.phase  = "applying";
                prog.detail = parsed.status;
                if (onProgress != null) onProgress.update(prog);

                bulk.change(List.of(it), parsed.status, parsed.comment);

                // ---------- 6. Применяем теги ----------
                if (!parsed.tags.isEmpty()) {
                    try {
                        bulk.setTags(List.of(it), String.join(",", parsed.tags));
                    } catch (Exception e) {
                        System.err.println("[auto] setTags " + it.key + ": " + e.getMessage());
                    }
                }

                // ---------- 7. Локальное обновление + статистика ----------
                it.status = parsed.status;
                it.tags   = parsed.tags;

                for (String t : parsed.tags) {
                    if (!availableTags.contains(t)) availableTags.add(t);
                }

                stats.success++;
                stats.byStatus.merge(parsed.status, 1, Integer::sum);
                stats.byFixDecision.merge(parsed.fixDecision, 1, Integer::sum);

                long dt = System.currentTimeMillis() - issueT0;
                prog.phase  = "done";
                prog.detail = parsed.status + " [" + String.join(",", parsed.tags) + "]"
                        + formatTime(dt);
                if (onProgress != null) onProgress.update(prog);

                consecutiveNetworkErrors = 0;

            } catch (SessionExpiredException see) {
                // ---- сессия истекла: СТОП ----
                stats.sessionExpired = true;
                stats.failed++;
                stats.failedKeys.add(it.key);

                prog.phase  = "failed";
                prog.detail = "SESSION EXPIRED — остановка";
                if (onProgress != null) onProgress.update(prog);
                break;

            } catch (Exception e) {
                // ---- обычная ошибка ----
                stats.failed++;
                stats.failedKeys.add(it.key);

                boolean isNetwork = isNetworkError(e);
                if (isNetwork) {
                    consecutiveNetworkErrors++;
                } else {
                    consecutiveNetworkErrors = 0;
                }

                long dt = System.currentTimeMillis() - issueT0;
                prog.phase  = "failed";
                prog.detail = e.getMessage() + formatTime(dt);
                if (onProgress != null) onProgress.update(prog);

                if (isNetwork && consecutiveNetworkErrors >= MAX_CONSECUTIVE_NETWORK_ERRORS) {
                    stats.abortedByNetwork = true;
                    stats.abortReason = consecutiveNetworkErrors
                            + " подряд сетевых ошибок: " + e.getMessage();
                    System.err.println("[auto] ABORT: " + stats.abortReason);
                    break;
                }
            }
        }

        // ---- сохранение failed keys ----
        if (!stats.failedKeys.isEmpty()) {
            try {
                Path out = Path.of("auto-failed-" + sanitize(project) + "-"
                        + System.currentTimeMillis() + ".txt");
                StringBuilder sb = new StringBuilder();
                sb.append("# Auto processing failed keys\n");
                sb.append("# project:    ").append(project).append('\n');
                sb.append("# finished:   ").append(Instant.now()).append('\n');
                sb.append("# total:      ").append(stats.total).append('\n');
                sb.append("# success:    ").append(stats.success).append('\n');
                sb.append("# failed:     ").append(stats.failed).append('\n');
                if (stats.sessionExpired) sb.append("# sessionExpired: true\n");
                if (stats.abortedByNetwork) sb.append("# abortedByNetwork: ").append(stats.abortReason).append('\n');
                sb.append("# ---\n");
                for (String k : stats.failedKeys) sb.append(k).append('\n');
                Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
                stats.failedFile = out.toString();
            } catch (Exception e) {
                System.err.println("[auto] не удалось сохранить failed keys: " + e.getMessage());
            }
        }

        Progress done = new Progress();
        done.phase   = "complete";
        done.total   = issues.size();
        done.current = i;
        if (onProgress != null) onProgress.update(done);

        long totalSec = (System.currentTimeMillis() - runT0) / 1000;
        System.err.printf("[auto] завершено за %dm %ds%n", totalSec / 60, totalSec % 60);

        return stats;
    }

    // ==================== helpers ====================

    private static boolean isNetworkError(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();
        String lower = msg.toLowerCase();
        return lower.contains("connection refused")
                || lower.contains("connect timed out")
                || lower.contains("failed to connect")
                || lower.contains("connection reset")
                || lower.contains("no route to host")
                || e instanceof java.net.ConnectException
                || e instanceof java.net.http.HttpTimeoutException;
    }

    private static String formatTime(long ms) {
        if (ms < 1000) return " (" + ms + "ms)";
        return " (" + String.format("%.1f", ms / 1000.0) + "s)";
    }

    private static String sanitize(String s) {
        if (s == null) return "project";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}