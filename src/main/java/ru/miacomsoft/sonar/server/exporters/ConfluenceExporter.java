package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

public class ConfluenceExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>").append(issue.key).append("</h3>");
        sb.append("<p><b>Правило:</b> ").append(issue.rule).append("</p>");
        sb.append("<p><b>Файл:</b> ").append(issue.component).append(":").append(issue.line).append("</p>");
        sb.append("<p><b>Сообщение:</b> ").append(issue.message).append("</p>");
        sb.append("<hr/>");
        return sb.toString();
    }
}