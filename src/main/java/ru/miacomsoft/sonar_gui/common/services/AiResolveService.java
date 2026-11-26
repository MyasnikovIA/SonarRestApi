package ru.miacomsoft.sonar_gui.common.services;

import ru.miacomsoft.sonar_gui.backend.ollama.OllamaClient;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaResponse;
import ru.miacomsoft.sonar_gui.common.GuiContext;

/**
 * Общий хелпер для кнопки «Получить резолюцию ИИ».
 */
public class AiResolveService {

    public record ResolveResult(String prompt, String rawResponse, OllamaResponse parsed) {}

    private final GuiContext ctx;

    public AiResolveService(GuiContext ctx) {
        this.ctx = ctx;
    }

    public String buildPrompt(String promptName, String issueKey,
                              String status, String statusLabel) throws Exception {
        System.out.println("[AiResolveService] Подготовка промпта для ИИ (issue=" + issueKey + ")...");
        PromptService promptService = new PromptService(ctx);
        return promptService.buildForIssue(
                ctx.getProject(), promptName, issueKey, status, statusLabel);
    }

    public ResolveResult ask(String promptName, String issueKey,
                             String status, String statusLabel) throws Exception {
        if (!ctx.ollamaConfig().isConfigured()) {
            System.err.println("[AiResolveService] Ошибка: Ollama не настроена (url/model).");
            throw new IllegalStateException(
                    "Ollama не настроена. Укажите url/model в меню "
                            + "«Файл → Настройки Ollama».");
        }

        System.out.println("[AiResolveService] Запрос к ИИ для issue: " + issueKey);
        String prompt = buildPrompt(promptName, issueKey, status, statusLabel);
        if (prompt == null || prompt.isBlank()) {
            System.err.println("[AiResolveService] Ошибка: Промпт пуст.");
            throw new IllegalStateException(
                    "Промпт пуст. Проверьте prompts/" + promptName + ".txt");
        }

        try {
            OllamaClient client = new OllamaClient(ctx.ollamaConfig());
            System.out.println("[AiResolveService] Отправка запроса в Ollama (модель: " + ctx.ollamaConfig().getModel() + ")...");
            String raw = client.chat(prompt);

            System.out.println("[AiResolveService] Ответ от Ollama получен. Парсинг...");
            OllamaResponse parsed = OllamaResponse.parse(raw);

            System.out.println("[AiResolveService] Резолюция ИИ успешно обработана (статус: " + parsed.status + ").");
            return new ResolveResult(prompt, raw, parsed);
        } catch (Exception e) {
            System.err.println("[AiResolveService] Ошибка при обращении к ИИ: " + e.getMessage());
            throw e;
        }
    }
}