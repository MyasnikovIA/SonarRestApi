package ru.miacomsoft.sonar_gui.backend.exporters;

import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Генерация ZIP с MD-промптами для LLM (аналог веб-экспорта llm-md-zip). */
public class LlmMdExporter {

    public static int writeZip(Path zipPath, Map<String, List<Issue>> issuesByFile,
                               SonarIssuesApi api, String project, String host) throws Exception {
        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
                String file = entry.getKey();
                List<Issue> issues = entry.getValue();

                String fullSource = "";
                try { fullSource = api.fetchFullSource(project + ":" + file); }
                catch (Exception e) {
                    System.err.println("[LlmMdExporter] не удалось получить код для " + file);
                }

                String mdContent = buildMarkdownPrompt(file, issues, fullSource, project, host);
                String safeName = makeFileName(file);
                zos.putNextEntry(new ZipEntry(safeName + ".md"));
                zos.write(mdContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }

    private static String buildMarkdownPrompt(String file, List<Issue> issues, String fullSource,
                                              String project, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Задание для LLM: Исправление ошибок в файле `").append(file).append("`\n\n");
        sb.append("Ты — опытный разработчик. Твоя задача — изучить найденные ошибки, "
                + "комментарии пользователя и анализатора, а также исходный код файла. "
                + "На основе этого тебе нужно переписать файл, исправив все описанные ошибки.\n\n");
        sb.append("## Найденные ошибки:\n\n");
        for (Issue it : issues) {
            String lineStr = it.line > 0 ? " (Строка: " + it.line + ")" : "";
            sb.append("### Ошибка: ").append(it.key).append(lineStr).append("\n\n");
            String link = "https://" + host + "/project/issues?id=" + urlEnc(project)
                    + "&open=" + urlEnc(it.key);
            sb.append("Ссылка: ").append(link).append("\n\n");
            sb.append("Правило: ").append(it.rule);
            if (it.ruleName != null && !it.ruleName.isEmpty())
                sb.append(" — ").append(it.ruleName);
            sb.append("\n\n");
            String tagsStr = (it.tags == null || it.tags.isEmpty())
                    ? "нет" : String.join(", ", it.tags);
            sb.append("Теги: ").append(tagsStr).append("\n\n");
            sb.append("Сообщение: ").append(it.message).append("\n\n");
        }
        if (fullSource != null && !fullSource.isEmpty()) {
            sb.append("## Полный исходный код файла\n\n```\n")
                    .append(fullSource).append("\n```\n\n");
        }
        sb.append("## Задача\n\n");
        sb.append("Перепиши файл целиком, исправив перечисленные ошибки. "
                + "Верни ТОЛЬКО исходный код файла без markdown-обёрток.\n");
        return sb.toString();
    }

    private static String makeFileName(String path) {
        String clean = path;
        if (clean.contains(":")) clean = clean.substring(clean.indexOf(':') + 1);
        return clean.replaceAll("[\\\\/]", "_").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}