package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.handlers.PromptBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Команда {@code prompt *}: рендер промптов из prompts/*.txt
 * (status-comment, bulk-status-comment, analyze-issue, auto-resolve).
 */
public class PromptCommands {

    private final ConsoleContext ctx;

    public PromptCommands(ConsoleContext ctx) {
        this.ctx = ctx;
    }

    public void handle(String[] a) throws Exception {
        if (a.length == 0 || "list".equalsIgnoreCase(a[0]) || "help".equalsIgnoreCase(a[0])) {
            promptHelp();
            return;
        }

        String name = a[0];
        if (!ctx.template().exists(name)) {
            System.out.println("[prompt] шаблон не найден: " + name);
            List<String> avail = ctx.template().listAvailable();
            System.out.println("Доступные: " + (avail.isEmpty() ? "<пусто>" : String.join(", ", avail)));
            return;
        }

        if (!ctx.requireProject()) return;

        String project     = ctx.getProject();
        String issueKey    = null;
        String keysCsv     = null;
        String sev         = "ALL";
        String qual        = "ALL";
        String status      = "accept";
        String statusLabel = null;
        String savePath    = null;

        for (int i = 1; i < a.length; i++) {
            String t = a[i];
            if      (t.startsWith("keys="))        keysCsv     = t.substring("keys=".length());
            else if (t.startsWith("sev="))         sev         = t.substring("sev=".length()).toUpperCase(Locale.ROOT);
            else if (t.startsWith("qual="))        qual        = t.substring("qual=".length()).toUpperCase(Locale.ROOT);
            else if (t.startsWith("status="))      status      = t.substring("status=".length());
            else if (t.startsWith("statusLabel=")) statusLabel = t.substring("statusLabel=".length());
            else if (t.startsWith("save="))        savePath    = t.substring("save=".length());
            else if (issueKey == null)             issueKey    = t;
        }

        SonarIssuesApi api = ctx.state().newApi(project);
        List<String> availableTags;
        try { availableTags = new ArrayList<>(api.fetchTags(null)); }
        catch (Exception e) { availableTags = new ArrayList<>(); }

        String prompt;

        switch (name) {
            case "status-comment", "auto-resolve", "analyze-issue" -> {
                Issue it = null;
                if (issueKey != null) {
                    it = api.fetchIssue(issueKey);
                } else if (keysCsv != null) {
                    String firstKey = keysCsv.split(",")[0].trim();
                    if (!firstKey.isEmpty()) it = api.fetchIssue(firstKey);
                } else {
                    List<Issue> list = ctx.fetchIssues(project, sev, qual);
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
                prompt = ctx.template().render(name, params);
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
                    issues = ctx.fetchIssues(project, sev, qual);
                }

                if (issues.isEmpty()) {
                    System.out.println("[prompt] нет issues для выбранного набора");
                    return;
                }

                Map<String, String> params = PromptBuilder.buildBulkStatusCommentParams(
                        issues, status, statusLabel, availableTags);
                prompt = ctx.template().render(name, params);
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
        System.out.println("Доступные шаблоны в " + ctx.template().getPromptsDir() + ":");
        List<String> names = ctx.template().listAvailable();
        if (names.isEmpty()) {
            System.out.println("  <пусто> — создайте prompts/*.txt");
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
    }
}