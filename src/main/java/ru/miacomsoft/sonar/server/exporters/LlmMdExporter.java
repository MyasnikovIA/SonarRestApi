package ru.miacomsoft.sonar.server.exporters;

import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LlmMdExporter {

    public static int writeZip(Path zipPath, Map<String, List<Issue>> issuesByFile,
                               SonarIssuesApi api, String project, String host,
                               OutputStream sseOut, long startMs) throws Exception {

        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
                String file = entry.getKey();
                List<Issue> issues = entry.getValue();

                // Отправка прогресса на клиент
                if (sseOut != null) {
                    JSONObject p = new JSONObject();
                    p.put("stage", "llm-md-gen");
                    p.put("message", "Генерация задания для LLM: " + file);
                    p.put("current", count);
                    p.put("total", issuesByFile.size());
                    p.put("elapsedMs", System.currentTimeMillis() - startMs);
                    String msg = "event: progress\ndata: " + p.toString() + "\n\n";
                    sseOut.write(msg.getBytes(StandardCharsets.UTF_8));
                    sseOut.flush();
                }

                String fullSource = "";
                try {
                    fullSource = api.fetchFullSource(project + ":" + file);
                } catch (Exception e) {
                    System.err.println("[LlmMdExporter] Не удалось получить код для " + file);
                }

                String mdContent = buildMarkdownPrompt(file, issues, fullSource, project, host, api);

                String safeName = makeFileName(file);
                ZipEntry ze = new ZipEntry(safeName + ".md");
                zos.putNextEntry(ze);
                zos.write(mdContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                count++;
            }
        }
        return count;
    }

    private static String buildMarkdownPrompt(String file, List<Issue> issues, String fullSource,
                                              String project, String host, SonarIssuesApi api) {
        StringBuilder sb = new StringBuilder();
        String lang = getLanguageExtension(file);

        sb.append("# Задание для LLM: Исправление ошибок в файле `").append(file).append("`\n\n");
        sb.append("Ты — опытный разработчик. Твоя задача — изучить найденные ошибки, комментарии пользователя и анализатора, а также исходный код файла. На основе этого тебе нужно переписать файл, исправив все описанные ошибки.\n\n");

        sb.append("## Найденные ошибки:\n\n");
        for (Issue it : issues) {
            String lineStr = it.line > 0 ? " (Строка: " + it.line + ")" : "";
            sb.append("### Ошибка: ").append(it.key).append(lineStr).append("\n\n");
            sb.append("Идентификатор ошибки: ").append(it.key).append(lineStr).append("\n\n");

            String link = "https://" + host + "/project/issues?id=" + urlEnc(project) + "&open=" + urlEnc(it.key);
            sb.append("Ссылка: ").append(link).append("\n\n");

            String fullComponentKey = project + ":" + it.component;
            sb.append("Ссылка на исходник: ").append(fullComponentKey).append("\n\n");

            sb.append("Правило: ").append(it.rule);
            if (it.ruleName != null && !it.ruleName.isEmpty()) {
                sb.append(" — ").append(it.ruleName);
            }
            sb.append("\n\n");

            String tagsStr = (it.tags == null || it.tags.isEmpty()) ? "нет" : String.join(", ", it.tags);
            sb.append("Теги: ").append(tagsStr).append("\n\n");

            sb.append("Сообщение: ").append(it.message).append("\n\n");

            String lastComment = getLastComment(it);
            if (!"нет".equals(lastComment)) {
                // Заменяем переносы строк на пробелы, чтобы комментарий выглядел единым блоком текста
                String singleLineComment = lastComment.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();
                sb.append("Пользовательский комментарий: ").append(singleLineComment).append("\n\n");
            } else {
                sb.append("Пользовательский комментарий: нет\n\n");
            }

            // --- ДОБАВЛЕНИЕ ФРАГМЕНТА КОДА С ПОДСВЕТКОЙ ---
            try {
                List<String> snippet = api.fetchSnippet(it.key);
                if (snippet != null && !snippet.isEmpty()) {
                    sb.append("Фрагмент кода с ошибкой:\n");
                    sb.append("```").append(lang).append("\n");
                    for (String snippetLine : snippet) {
                        // Превращаем маркеры SonarQube в читаемые для LLM указатели
                        String highlightedLine = snippetLine
                                .replace("§HL_START§", ">>> ")
                                .replace("§HL_END§", " <<<");
                        sb.append(highlightedLine).append("\n");
                    }
                    sb.append("```\n\n");
                }
            } catch (Exception e) {
                System.err.println("[LlmMdExporter] Не удалось загрузить фрагмент для " + it.key);
            }
            // -----------------------------------------------

            sb.append("---\n\n");
        }

        sb.append("## Исходный код файла:\n");
        sb.append("```").append(lang).append("\n");
        sb.append(fullSource).append("\n");
        sb.append("```\n\n");

        sb.append("## Инструкции:\n");
        sb.append("1. **Сохрани стиль написания кода**: отступы, именование переменных, архитектуру. Правь только те места, где обнаружены описанные ошибки.\n");
        sb.append("2. В начале ответа добавь короткий комментарий (для GIT), в котором перечисли, что именно было поправлено.\n");
        sb.append("3. После комментария выведи **ПОЛНЫЙ** листинг исправленного файла, обернутый в блок кода Markdown (```...```).\n");
        sb.append("4. Убедись, что в итоговом файле нет не исправленных ошибок из списка выше.\n");

        return sb.toString();
    }

    private static String makeFileName(String originalFilePath) {
        String cleanPath = originalFilePath;
        if (cleanPath.contains(":")) {
            String[] parts = cleanPath.split(":");
            cleanPath = parts[parts.length - 1];
        }
        cleanPath = cleanPath.replaceAll("[\\\\/]", "_");
        cleanPath = cleanPath.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleanPath;
    }

    private static String getLastComment(Issue issue) {
        if (issue.comments == null || issue.comments.isEmpty()) return "нет";
        Issue.Comment latest = null;
        for (Issue.Comment c : issue.comments) {
            if (latest == null || String.valueOf(c.createdAt).compareTo(String.valueOf(latest.createdAt)) > 0) {
                latest = c;
            }
        }
        if (latest == null) return "нет";
        if (latest.markdown != null && !latest.markdown.isEmpty()) return latest.markdown;
        return latest.htmlText != null ? latest.htmlText : "нет";
    }

    private static String getLanguageExtension(String file) {
        String lower = file.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts")) return "typescript";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".html")) return "html";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".cpp") || lower.endsWith(".h")) return "cpp";
        if (lower.endsWith(".cs")) return "csharp";
        return "text";
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}