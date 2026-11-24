package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.AutoProcessor;
import ru.miacomsoft.sonar.server.OllamaConfig;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.handlers.PromptBuilder;
import ru.miacomsoft.sonar.server.ollama.OllamaClient;
import ru.miacomsoft.sonar.server.ollama.OllamaResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Команды авто-обработки через Ollama:
 *   auto [SEV] [QUAL] [--yes]     — массовая (синхронно)
 *   auto key <KEY> [--yes]        — одиночная
 *   stop                          — запросить остановку массовой обработки
 *
 * Также содержит printProgress() (callback для AutoProcessor.processAll)
 * и вспомогательный вывод итоговой статистики.
 */
public class AutoCommands {

    private final ConsoleContext ctx;
    private final Object consoleLock;

    public AutoCommands(ConsoleContext ctx, Object consoleLock) {
        this.ctx = ctx;
        this.consoleLock = consoleLock;
    }

    public void handle(String[] a) throws Exception {
        if (!ctx.requireProject()) return;
        OllamaConfig cfg = ctx.ollamaConfig();

        if (!cfg.isConfigured()) {
            System.out.println("[auto] Ollama не настроена.");
            System.out.println("       Установите: ollama set url http://host:port");
            System.out.println("                   ollama set model llama3");
            return;
        }

        if (ctx.isAutoRunning()) {
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
            autoSingle(rest.get(1), confirm);
            return;
        }

        String project = ctx.getProject();
        String[] sq = ConsoleContext.sevQual(rest.toArray(new String[0]), 0);
        SonarIssuesApi api = ctx.state().newApi(project);
        List<Issue> issues = api.fetchOpen(sq[0], sq[1]);

        System.out.printf("%nproject    = %s%n", project);
        System.out.printf("severity   = %s%n", sq[0]);
        System.out.printf("quality    = %s%n", sq[1]);
        System.out.printf("issues     = %d  (OPEN / REOPENED)%n", issues.size());
        System.out.printf("ollama     = %s  (model: %s)%n", cfg.getUrl(), cfg.getModel());
        System.out.printf("prompt     = %s/auto-resolve.txt%n", ctx.template().getPromptsDir());

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
                        ConsoleContext.shorten(it.message, 70));
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

    /** {@code stop}: сбрасывает флаг остановки текущей auto-обработки. */
    public void stop() {
        synchronized (consoleLock) {
            if (!ctx.isAutoRunning()) {
                System.out.println("[auto] обработка не запущена.");
                return;
            }
            ctx.setAutoStopRequested(true);
            System.out.println("[auto] запрошена остановка "
                    + "(текущий issue будет доработан, затем цикл завершится).");
        }
    }

    // ==================== bulk ====================

    private void startAutoBulk(String sev, String qual, int totalIssues) {
        if (ctx.isAutoRunning()) {
            System.out.println("[auto] уже выполняется.");
            return;
        }
        ctx.setAutoRunning(true);
        ctx.setAutoStopRequested(false);

        System.out.println("\n=== Запуск массовой обработки через Ollama ===");
        System.out.println("[auto] промпт: " + ctx.template().getPromptsDir() + "/auto-resolve.txt");
        System.out.println("[auto] синхронный режим — Ctrl+C для досрочной остановки\n");

        final long t0 = System.currentTimeMillis();
        AutoProcessor.Stats stats = null;
        Exception err = null;

        try {
            stats = AutoProcessor.processAll(
                    ctx.state(), ctx.getProject(), sev, qual,
                    ctx.ollamaConfig(), ctx.template(),
                    this::printProgress,
                    ctx::isAutoStopRequested
            );
        } catch (Exception e) {
            err = e;
        } finally {
            ctx.setAutoRunning(false);
            ctx.setAutoStopRequested(false);
        }

        long elapsed = System.currentTimeMillis() - t0;

        System.out.println();
        if (err != null) {
            System.out.println("[auto] FAIL: " + describeThrowable(err));
        }
        if (stats != null) {
            printStats(stats, elapsed, sev, qual);
        }
    }

    private void printStats(AutoProcessor.Stats stats, long elapsed, String sev, String qual) {
        ServerState state = ctx.state();
        String project = ctx.getProject();

        System.out.println("=== Результат ===");
        System.out.printf("Всего:         %d%n", stats.total);
        System.out.printf("Успешно:       %d%n", stats.success);
        System.out.printf("Ошибок:        %d%n", stats.failed);
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
            System.out.println("#  ⚠  ТОКЕН / СЕССИЯ SONARQUBE НЕДЕЙСТВИТЕЛЬНЫ   #");
            System.out.println("##################################################");
            System.out.println("Обработано " + stats.success + " из " + stats.total
                    + " issues, дальше — стоп.");
            System.out.println();
            System.out.println("Что делать:");
            System.out.println("  1. Сгенерируйте новый токен: "
                    + (state.getSession() != null ? state.getSession().getUrl() : "?")
                    + "/account/security");
            System.out.println("  2. Обновите session.properties (sonar.token=...)");
            System.out.println("  3. session reload");
            System.out.println("  4. auto " + sev + " " + qual + " --yes");
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

    // ==================== single ====================

    private void autoSingle(String key, boolean confirm) throws Exception {
        ServerState state = ctx.state();
        String project    = ctx.getProject();
        OllamaConfig cfg  = ctx.ollamaConfig();

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
        System.out.printf("message    = %s%n", ConsoleContext.shorten(it.message, 120));
        System.out.printf("ollama     = %s  (model: %s)%n", cfg.getUrl(), cfg.getModel());
        System.out.printf("prompt     = %s/auto-resolve.txt%n", ctx.template().getPromptsDir());

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
                prompt = ctx.template().render("auto-resolve", params);
                if (prompt == null || prompt.isBlank()) {
                    System.err.println("[auto] rendered prompt is blank, using fallback");
                    prompt = PromptBuilder.buildAutoResolvePrompt(it, snippet, tags);
                }
            } catch (Exception e) {
                System.err.println("[auto] template render failed, using fallback: " + e.getMessage());
                prompt = PromptBuilder.buildAutoResolvePrompt(it, snippet, tags);
            }

            System.out.println("[auto] запрос к Ollama...");
            String raw = new OllamaClient(cfg).chat(prompt);

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

    // ==================== progress ====================

    private void printProgress(AutoProcessor.Progress p) {
        if (p == null || p.total == 0) return;
        String line;
        switch (p.phase) {
            case "preparing" -> line = String.format("[%d/%d] %s — подготовка...",
                    p.current, p.total, p.issueKey);
            case "ollama"    -> line = String.format("[%d/%d] %s — Ollama (%s)...",
                    p.current, p.total, p.issueKey, p.detail);
            case "applying"  -> line = String.format("[%d/%d] %s — применяю %s...",
                    p.current, p.total, p.issueKey, p.detail);
            case "done"      -> line = String.format("[%d/%d] %s → OK  %s",
                    p.current, p.total, p.issueKey, p.detail);
            case "failed"    -> line = String.format("[%d/%d] %s → FAIL: %s",
                    p.current, p.total, p.issueKey, p.detail);
            default          -> { return; }
        }
        System.out.println(line);
        System.out.flush();
    }

    private static String describeThrowable(Throwable e) {
        if (e == null) return "unknown";
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            return e.getClass().getName();
        }
        return e.getClass().getSimpleName() + ": " + msg;
    }
}