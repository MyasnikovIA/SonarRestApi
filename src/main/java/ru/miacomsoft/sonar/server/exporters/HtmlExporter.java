package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

public class HtmlExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='border:1px solid #ccc; margin:10px; padding:10px;'>");
        sb.append("<h3>").append(issue.key).append("</h3>");
        sb.append("<p><b>Ссылка:</b> <a href='https://").append(host).append("/project/issues?id=").append(projectName).append("&open=").append(issue.key).append("'>Открыть</a></p>");
        sb.append("<p><b>Правило:</b> ").append(issue.rule).append("</p>");
        sb.append("<p><b>Файл:</b> ").append(issue.component).append(":").append(issue.line).append("</p>");
        sb.append("<p><b>Сообщение:</b> ").append(issue.message).append("</p>");
        sb.append("</div>");
        return sb.toString();
    }
}