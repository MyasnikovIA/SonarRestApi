package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ConfluenceExporter {

    public static String exportSingle(Issue issue, String projectName, String host) {
        return exportSingle(issue, projectName, host, null);
    }

    /**
     * @param snippet — фрагмент кода в формате "%4d | code",
     *                  может содержать §HL_START§ / §HL_END§.
     *                  null / пусто → блок кода не выводится.
     */
    public static String exportSingle(Issue issue, String projectName, String host,
                                      List<String> snippet) {
        StringBuilder sb = new StringBuilder(1024);

        String link = "https://" + host + "/project/issues?id=" + urlEnc(projectName)
                + "&open=" + urlEnc(issue.key);
        String ruleName = (issue.ruleName != null && !issue.ruleName.isEmpty())
                ? " — " + issue.ruleName : "";
        String tagsStr = (issue.tags == null || issue.tags.isEmpty())
                ? "нет" : String.join(", ", issue.tags);
        String lastComment = getLastComment(issue);

        sb.append("<h3>").append(esc(issue.key)).append("</h3>\n");

        if (issue.line > 0) {
            sb.append("<p><b>Строка:</b> ").append(issue.line).append("</p>\n");
        } else {
            sb.append("<p><b>Строка:</b> <em>(issue на весь файл)</em></p>\n");
        }

        sb.append("<p><b>Ссылка:</b> <a href=\"").append(esc(link))
                .append("\" target=\"_blank\">").append(esc(link)).append("</a></p>\n");
        sb.append("<p><b>Правило:</b> ")
                .append(esc(issue.rule)).append(esc(ruleName)).append("</p>\n");
        sb.append("<p><b>Файл:</b> ").append(esc(issue.component)).append("</p>\n");
        sb.append("<p><b>Теги:</b> ").append(esc(tagsStr)).append("</p>\n");
        sb.append("<p><b>Сообщение:</b> ").append(esc(issue.message)).append("</p>\n");
        sb.append("<p><b>Пользовательский комментарий:</b> ")
                .append(esc(lastComment)).append("</p>\n");

        if (snippet != null && !snippet.isEmpty()) {
            sb.append("<details>\n<summary>Показать фрагмент кода");
            if (issue.line <= 0) {
                sb.append(" (первые ").append(snippet.size()).append(" строк файла)");
            }
            sb.append("</summary>\n<pre><code>");
            for (int i = 0; i < snippet.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(renderSnippetLine(snippet.get(i)));
            }
            sb.append("</code></pre>\n</details>\n");
        }

        sb.append("<br/><hr/><br/>\n");
        return sb.toString();
    }

    /**
     * Экранирует HTML и превращает §HL_START§/§HL_END§ в &lt;mark&gt;.
     * Пустые &lt;mark&gt;&lt;/mark&gt; вычищаются.
     */
    private static String renderSnippetLine(String line) {
        if (line == null) return "";
        String result = esc(line)
                .replace("§HL_START§", "<mark class=\"sonar-issue-mark\">")
                .replace("§HL_END§", "</mark>");
        return result.replace("<mark class=\"sonar-issue-mark\"></mark>", "");
    }

    private static String getLastComment(Issue issue) {
        if (issue.comments == null || issue.comments.isEmpty()) return "нет";
        Issue.Comment latest = null;
        for (Issue.Comment c : issue.comments) {
            if (latest == null
                    || String.valueOf(c.createdAt).compareTo(String.valueOf(latest.createdAt)) > 0) {
                latest = c;
            }
        }
        if (latest == null) return "нет";
        if (latest.markdown != null && !latest.markdown.isEmpty()) return latest.markdown;
        return latest.htmlText != null ? latest.htmlText : "нет";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}