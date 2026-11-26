package ru.miacomsoft.sonar_gui.backend.exporters;

import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.TeamMapping;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class HtmlTeamPerFileExporter {

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String message);
    }

    public static int writeDirectory(Path rootDir,
                                     Map<String, List<Issue>> issuesByFile,
                                     Map<String, List<String>> issueSnippets,
                                     Map<String, String> fullSources,
                                     String projectName, String host,
                                     TeamMapping mapping,
                                     ProgressCallback progress) throws IOException {
        Files.createDirectories(rootDir);
        int total = issuesByFile.size();
        int current = 0;

        for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
            current++;
            String file = entry.getKey();
            List<Issue> issues = entry.getValue();
            String fullSource = fullSources.getOrDefault(file, "");

            // 1. Определяем Отдел и Модуль
            TeamMapping.FileInfo info = (mapping != null) ? mapping.findInfo(file) : null;
            String team = (info != null && info.hasTeam()) ? info.team : "Отдел не определен";
            String module = (info != null && info.hasComponent()) ? info.component : "Модуль не определен";

            // 2. Санитайзим имена для файловой системы
            String safeTeam = sanitizePathPart(team);
            String safeModule = sanitizePathPart(module);
            String safeFileName = sanitizeFileName(file) + ".html";

            // 3. Создаем каталог <Отдел>/<Модуль>
            Path targetDir = rootDir.resolve(safeTeam).resolve(safeModule);
            Files.createDirectories(targetDir);

            // 4. Генерируем HTML
            String html = formatHtmlFile(issues, issueSnippets, projectName, host, file, fullSource);

            // 5. Сохраняем файл
            Path targetFile = targetDir.resolve(safeFileName);
            try {
                Files.writeString(targetFile, html, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IOException("Не удалось сохранить файл " + safeFileName + " в " + targetDir + ": " + e.getMessage(), e);
            }

            if (progress != null) {
                progress.onProgress(current, total, "Сохранение: " + safeTeam + "/" + safeModule + "/" + safeFileName);
            }
        }
        return current;
    }

    private static String formatHtmlFile(List<Issue> issues,
                                         Map<String, List<String>> issueSnippets,
                                         String projectName, String host,
                                         String fileName, String fullSource) {
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());
        StringBuilder html = new StringBuilder(8192);

        html.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Отчет: ").append(escapeHtml(projectName)).append(" - ")
                .append(escapeHtml(fileName)).append("</title>\n");
        html.append("<style>\n");
        html.append("body{font-family:system-ui,sans-serif;max-width:1000px;margin:0 auto;padding:20px;background:#fafbfc;color:#172b4d;}\n");
        html.append("h1,h2{color:#0052cc;}\n");
        html.append(".issue{border:1px solid #dfe1e6;border-radius:4px;background:#fff;padding:16px;margin-bottom:16px;}\n");
        html.append("details{margin-top:12px;border:1px solid #dfe1e6;border-radius:4px;background:#f4f5f7;}\n");
        html.append("summary{cursor:pointer;padding:8px 12px;font-weight:600;}\n");
        html.append("pre{margin:0;padding:12px;background:#fff;overflow-x:auto;font-family:monospace;font-size:13px;white-space:pre-wrap;}\n");
        html.append("mark.sonar-issue-mark{background-color:rgba(248,81,73,0.25);border-bottom:2px solid #f85149;}\n");
        html.append("</style>\n</head>\n<body>\n");

        html.append("<h1>Проект: ").append(escapeHtml(projectName)).append("</h1>\n");
        html.append("<h2>Файл: ").append(escapeHtml(fileName)).append("</h2>\n");
        html.append("<p><em>Сгенерировано: ").append(now)
                .append(" &middot; Всего issues: ").append(issues.size()).append("</em></p>\n<hr/>\n");

        for (Issue it : issues) {
            String link = "https://" + host + "/project/issues?id=" + urlEnc(projectName)
                    + "&open=" + urlEnc(it.key);
            String fullComponentKey = projectName + ":" + it.component;
            String sourceLink = "https://" + host + "/code?id=" + urlEnc(projectName)
                    + "&selected=" + urlEnc(fullComponentKey);
            String ruleName = (it.ruleName != null && !it.ruleName.isEmpty())
                    ? " — " + it.ruleName : "";
            String tagsStr = (it.tags == null || it.tags.isEmpty())
                    ? "нет" : String.join(", ", it.tags);
            String lastComment = getLastComment(it);

            html.append("<div class=\"issue\">\n");
            html.append("<p><strong>Идентификатор ошибки:</strong> ").append(escapeHtml(it.key));
            if (it.line > 0) html.append(" (Строка: ").append(it.line).append(")");
            html.append("</p>\n");
            html.append("<p><strong>Ссылка:</strong> <a href=\"").append(escapeHtml(link))
                    .append("\" target=\"_blank\">").append(escapeHtml(link)).append("</a></p>\n");
            html.append("<p><strong>Ссылка на исходник:</strong> <a href=\"")
                    .append(escapeHtml(sourceLink)).append("\" target=\"_blank\">")
                    .append(escapeHtml(fullComponentKey)).append("</a></p>\n");
            html.append("<p><strong>Правило:</strong> ").append(escapeHtml(it.rule))
                    .append(escapeHtml(ruleName)).append("</p>\n");
            html.append("<p><strong>Теги:</strong> ").append(escapeHtml(tagsStr)).append("</p>\n");
            html.append("<p><strong>Сообщение:</strong> ").append(escapeHtml(it.message)).append("</p>\n");
            html.append("<p><strong>Комментарий:</strong> ").append(escapeHtml(lastComment)).append("</p>\n");

            List<String> snippet = (issueSnippets != null) ? issueSnippets.get(it.key) : null;
            if (snippet != null && !snippet.isEmpty()) {
                html.append("<details>\n<summary>Показать фрагмент кода</summary>\n<pre><code>");
                for (int i = 0; i < snippet.size(); i++) {
                    if (i > 0) html.append('\n');
                    html.append(renderSnippetLine(snippet.get(i)));
                }
                html.append("</code></pre>\n</details>\n");
            }
            html.append("</div>\n");
        }

        if (fullSource != null && !fullSource.isEmpty()) {
            html.append("<div class=\"issue\" style=\"background:#f4f5f7;\">\n");
            html.append("<details>\n<summary>Показать полный исходный код файла</summary>\n<pre><code>");
            html.append(escapeHtml(fullSource));
            html.append("</code></pre>\n</details>\n");
            html.append("</div>\n");
        }

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static String renderSnippetLine(String line) {
        if (line == null) return "";
        String result = escapeHtml(line)
                .replace("§HL_START§", "<mark class=\"sonar-issue-mark\">")
                .replace("§HL_END§", "</mark>");
        return result.replace("<mark class=\"sonar-issue-mark\"></mark>", "");
    }

    private static String getLastComment(Issue issue) {
        if (issue.comments == null || issue.comments.isEmpty()) return "нет";
        Issue.Comment latest = null;
        for (Issue.Comment c : issue.comments) {
            if (latest == null || String.valueOf(c.createdAt)
                    .compareTo(String.valueOf(latest.createdAt)) > 0) {
                latest = c;
            }
        }
        if (latest == null) return "нет";
        if (latest.markdown != null && !latest.markdown.isEmpty()) return latest.markdown;
        return latest.htmlText != null ? latest.htmlText : "нет";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static String sanitizePathPart(String s) {
        if (s == null || s.trim().isEmpty()) return "Без_названия";
        // Удаляем недопустимые символы для имен папок в Windows/Linux
        return s.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String sanitizeFileName(String path) {
        if (path.contains(":")) {
            String[] parts = path.split(":");
            path = parts[parts.length - 1];
        }
        path = path.replaceAll("[\\\\/]", "_");
        path = path.replaceAll("[^A-Za-z0-9._-]", "_");
        // Ограничение длины, чтобы не словить ошибку MAX_PATH
        if (path.length() > 150) {
            path = path.substring(path.length() - 150);
        }
        return path;
    }
}