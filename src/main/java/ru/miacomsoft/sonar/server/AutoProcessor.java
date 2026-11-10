package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.handlers.PromptBuilder;
import ru.miacomsoft.sonar.server.ollama.OllamaClient;
import ru.miacomsoft.sonar.server.ollama.OllamaResponse;

import java.util.*;

/**
 * Массовая автоматическая обработка issues через Ollama.
 * Аналог JS-функционала "Добавить автоматически комментарий" из perfile/details.
 *
 * Для каждого issue:
 *   1. fetch ruleName (кэш) + snippet (кэш)
 *   2. buildAutoResolvePrompt → Ollama.chat
 *   3. parseOllamaResponse → status / tags / comment
 *   4. bulk.change  (SonarQube: do_transition + comment)
 *   5. bulk.setTags (SonarQube: /api/issues/set_tags)
 */
public class AutoProcessor {

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
        public final List<String> failedKeys   = new ArrayList<>();
        public final Map<String, Integer> byStatus       = new TreeMap<>();
        public final Map<String, Integer> byFixDecision  = new TreeMap<>();
    }

    public static Stats processAll(ServerState state, String project,
                                   String sev, String qual,
                                   OllamaConfig config,
                                   ProgressCallback onProgress) throws Exception {

        SonarIssuesApi api = state.newApi(project);
        List<Issue> issues = api.fetchOpen(sev, qual);  // OPEN + REOPENED

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

        int i = 0;
        for (Issue it : issues) {
            i++;
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

                prog.phase  = "done";
                prog.detail = parsed.status + " [" + String.join(",", parsed.tags) + "]";
                if (onProgress != null) onProgress.update(prog);

            } catch (Exception e) {
                stats.failed++;
                stats.failedKeys.add(it.key);
                prog.phase  = "failed";
                prog.detail = e.getMessage();
                if (onProgress != null) onProgress.update(prog);
            }
        }

        Progress done = new Progress();
        done.phase   = "complete";
        done.total   = issues.size();
        done.current = issues.size();
        if (onProgress != null) onProgress.update(done);

        return stats;
    }
}