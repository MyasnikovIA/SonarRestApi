package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

public class TxtExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("Issue: ").append(issue.key).append("\n");
        sb.append("Ссылка: https://").append(host).append("/project/issues?id=").append(projectName).append("&open=").append(issue.key).append("\n");
        sb.append("Правило: ").append(issue.rule).append("\n");
        sb.append("Файл: ").append(issue.component).append(":").append(issue.line).append("\n");
        sb.append("Сообщение: ").append(issue.message).append("\n\n");
        return sb.toString();
    }
}