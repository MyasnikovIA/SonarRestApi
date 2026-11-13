package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.server.TeamMapping;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class HtmlPerFileExporter {

    /** Имя manifest-файла в ZIP (ведущий '_' — сортируется первым). */
    public static final String MANIFEST_NAME = "!files.csv";

    private HtmlPerFileExporter() {}

    /**
     * @param issueSnippets — Map issue.key → список строк фрагмента
     *                        (формат "%4d | code", может содержать §HL_START§ / §HL_END§).
     *                        null → без сниппетов.
     *
     * В архив попадают:
     *   - per-file HTML (по одному на компонент);
     *   - _files.txt с manifest:
     *       строка 1: projectName
     *       строки 2..N: "<original/file/path>;<entry_name_in_zip>"
     */
    public static int writeZip(Path target,
                               Map<String, List<Issue>> issuesByFile,
                               Map<String, List<String>> issueSnippets,
                               String projectName, String host,
                               boolean useTeams, TeamMapping mapping) throws IOException {
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(target));
             ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
            zos.setLevel(6);
            int written = 0;

            // Manifest: первая строка — ID проекта, потом "path;entryName"
            StringBuilder manifest = new StringBuilder(1024);
            manifest.append(projectName != null ? projectName : "").append('\n');

            for (Map.Entry<String, List<Issue>> e : issuesByFile.entrySet()) {
                String fileName = e.getKey();
                List<Issue> issues = e.getValue();

                String html = formatHtmlFile(issues, issueSnippets, projectName, host, fileName);
                String entryName = makeEntryName(fileName, useTeams, mapping);

                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(html.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                written++;

                // "<original component path>;<entry name in zip>"
                manifest.append(fileName).append(';').append(entryName).append('\n');
            }

            // _files.txt — манифест
            zos.putNextEntry(new ZipEntry(MANIFEST_NAME));
            zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            return written;
        }
    }

    private static String makeEntryName(String component, boolean useTeams, TeamMapping mapping) {
        String cleanPath = component;
        if (cleanPath.contains(":")) cleanPath = cleanPath.substring(cleanPath.indexOf(':') + 1);
        String safePath = cleanPath.replaceAll("[\\\\/]", "_").replaceAll("[^A-Za-z0-9._-]", "_");
        if (safePath.isEmpty()) safePath = "unknown";

        if (useTeams && mapping != null) {
            String team = mapping.findTeam(component);
            if (!team.isEmpty()) {
                String safeTeam = team.replaceAll("[^A-Za-z0-9._-]", "_");
                return "(" + safeTeam + ")_" + safePath + ".html";
            }
        }
        return safePath + ".html";
    }

    public static String formatHtmlFile(List<Issue> issues,
                                        Map<String, List<String>> issueSnippets,
                                        String projectName, String host, String fileName) {
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());
        StringBuilder html = new StringBuilder(4096);

        html.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Отчет: ").append(escapeHtml(projectName))
                .append(" - ").append(escapeHtml(fileName)).append("</title>\n");
        html.append("<style>\n");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;line-height:1.5;color:#172b4d;max-width:1000px;margin:0 auto;padding:20px;background:#fafbfc;}\n");
        html.append("h1,h2{color:#0052cc;margin-bottom:8px;}\n");
        html.append("a{color:#0052cc;text-decoration:none;}a:hover{text-decoration:underline;}\n");
        html.append(".issue{border:1px solid #dfe1e6;border-radius:4px;background:#fff;padding:16px;margin-bottom:16px;}\n");
        html.append("details{margin-top:12px;border:1px solid #dfe1e6;border-radius:4px;background:#f4f5f7;}\n");
        html.append("summary{cursor:pointer;padding:8px 12px;font-weight:600;color:#42526e;user-select:none;}\n");
        html.append("summary:hover{background:#ebecf0;}\n");
        html.append("pre{margin:0;padding:12px;background:#fff;overflow-x:auto;border-top:1px solid #dfe1e6;border-radius:0 0 4px 4px;font-family:monospace;font-size:13px;white-space:pre-wrap;}\n");
        html.append("mark.sonar-issue-mark{background-color:rgba(248,81,73,0.25);border-bottom:2px solid #f85149;color:inherit;padding:0 2px;border-radius:2px;}\n");
        html.append("</style>\n</head>\n<body>\n");
        html.append("<h1>Проект: ").append(escapeHtml(projectName)).append("</h1>\n");
        html.append("<h2>Файл: ").append(escapeHtml(fileName)).append("</h2>\n");
        html.append("<p><em>Сгенерировано: ").append(now)
                .append(" &middot; Всего issues: ").append(issues.size()).append("</em></p>\n<hr/>\n");

        for (Issue it : issues) {
            String link = "https://" + host + "/project/issues?id=" + urlEnc(projectName)
                    + "&open=" + urlEnc(it.key);
            String ruleName = (it.ruleName != null && !it.ruleName.isEmpty())
                    ? " — " + it.ruleName : "";
            String tagsStr = (it.tags == null || it.tags.isEmpty())
                    ? "нет" : String.join(", ", it.tags);
            String lastComment = getLastComment(it);

            html.append("<div class=\"issue\">\n");
            html.append("<p><strong>Идентификатор ошибки:</strong> ").append(escapeHtml(it.key))
                    .append(" (Строка: ").append(it.line).append(")</p>\n");
            html.append("<p><strong>Ссылка:</strong> <a href=\"").append(escapeHtml(link))
                    .append("\" target=\"_blank\">").append(escapeHtml(link)).append("</a></p>\n");
            html.append("<p><strong>Правило:</strong> ")
                    .append(escapeHtml(it.rule)).append(escapeHtml(ruleName)).append("</p>\n");
            html.append("<p><strong>Теги:</strong> ").append(escapeHtml(tagsStr)).append("</p>\n");
            html.append("<p><strong>Сообщение:</strong> ").append(escapeHtml(it.message)).append("</p>\n");
            html.append("<p><strong>Пользовательский комментарий:</strong> ")
                    .append(escapeHtml(lastComment)).append("</p>\n");

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

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static String renderSnippetLine(String line) {
        if (line == null) return "";
        return escapeHtml(line)
                .replace("§HL_START§", "<mark class=\"sonar-issue-mark\">")
                .replace("§HL_END§", "</mark>");
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

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}