package ru.miacomsoft.sonar_gui.common.services;

import ru.miacomsoft.sonar_gui.backend.ollama.OllamaClient;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaResponse;
import ru.miacomsoft.sonar_gui.common.GuiContext;

/**
 * Общий хелпер для кнопки «Получить резолюцию ИИ».
 *
 *   buildPrompt(name, issueKey)  → PromptService.buildForIssue
 *   ask(name, issueKey)          → OllamaClient.chat(prompt) + OllamaResponse.parse
 */
public class AiResolveService {

    public record ResolveResult(String prompt, String rawResponse, OllamaResponse parsed) {}

    private final GuiContext ctx;

    public AiResolveService(GuiContext ctx) {
        this.ctx = ctx;
    }

    /** Построить промпт для одного issue. */
    public String buildPrompt(String promptName, String issueKey,
                              String status, String statusLabel) throws Exception {
        PromptService promptService = new PromptService(ctx);
        return promptService.buildForIssue(
                ctx.getProject(), promptName, issueKey, status, statusLabel);
    }

    /** Полный цикл: промпт → Ollama → разбор. */
    public ResolveResult ask(String promptName, String issueKey,
                             String status, String statusLabel) throws Exception {
        if (!ctx.ollamaConfig().isConfigured()) {
            throw new IllegalStateException(
                    "Ollama не настроена. Укажите url/model в меню "
                            + "«Файл → Настройки Ollama».");
        }

        String prompt = buildPrompt(promptName, issueKey, status, statusLabel);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException(
                    "Промпт пуст. Проверьте prompts/" + promptName + ".txt");
        }

        OllamaClient client = new OllamaClient(ctx.ollamaConfig());
        String raw = client.chat(prompt);
        OllamaResponse parsed = OllamaResponse.parse(raw);
        return new ResolveResult(prompt, raw, parsed);
    }
}