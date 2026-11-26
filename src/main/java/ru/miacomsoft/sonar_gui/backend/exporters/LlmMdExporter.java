package ru.miacomsoft.sonar_gui.backend.exporters;

import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LlmMdExporter {

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String message);
    }

    public static int writeZip(Path zipPath, Map<String, List<Issue>> issuesByFile,
                               SonarIssuesApi api, String project, String host) throws Exception {
        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
                String file = entry.getKey();
                List<Issue> issues = entry.getValue();

                String fullSource = "";
                try { fullSource = api.fetchFullSource(project + ":" + file); }
                catch (Exception e) { System.err.println("[LlmMdExporter] не удалось получить код для " + file); }

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

    public static int writeDirectory(Path targetDir, Map<String, List<Issue>> issuesByFile,
                                     SonarIssuesApi api, String project, String host,
                                     ProgressCallback progress) throws Exception {
        Files.createDirectories(targetDir);
        int total = issuesByFile.size();
        int current = 0;

        for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
            current++;
            String file = entry.getKey();
            List<Issue> issues = entry.getValue();

            String fullSource = "";
            try { fullSource = api.fetchFullSource(project + ":" + file); }
            catch (Exception e) { System.err.println("[LlmMdExporter] не удалось получить код для " + file); }

            String mdContent = buildMarkdownPrompt(file, issues, fullSource, project, host);
            String safeName = makeFileName(file);
            Path filePath = targetDir.resolve(safeName + ".md");

            Files.writeString(filePath, mdContent, StandardCharsets.UTF_8);

            if (progress != null) {
                progress.onProgress(current, total, "Сохранение MD: " + current + "/" + total);
            }
        }
        return current;
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
            String sourceLink = "https://" + host + "/code?id=" + project + "&selected=" + project + ":" + it.component;
            String tagsStr = (it.tags == null || it.tags.isEmpty()) ? "нет" : String.join(", ", it.tags);
            String lastComment = getLastComment(it);

            sb.append("### Ошибка: ").append(it.key).append(lineStr).append("\n\n");
            String link = "https://" + host + "/project/issues?id=" + urlEnc(project) + "&open=" + urlEnc(it.key);
            sb.append("Ссылка на ошибку: ").append(link).append("\n\n");
            sb.append("Ссылка на исходник: ").append(sourceLink).append("\n\n");
            sb.append("Правило: ").append(it.rule);
            if (it.ruleName != null && !it.ruleName.isEmpty())
                sb.append(" — ").append(it.ruleName);
            sb.append("\n\n");
            sb.append("Теги: ").append(tagsStr).append("\n\n");
            sb.append("Сообщение: ").append(it.message).append("\n\n");
            sb.append("Последний комментарий: ").append(lastComment).append("\n\n");
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