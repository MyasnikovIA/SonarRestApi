package ru.miacomsoft.sonar_gui.backend.exporters;

import ru.miacomsoft.sonar_gui.backend.Issue;

public class JiraExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("h4. ").append(issue.key).append("\n");
        sb.append("*Ссылка:* https://").append(host).append("/project/issues?id=")
                .append(projectName).append("&open=").append(issue.key).append("\n");
        sb.append("*Правило:* ").append(issue.rule).append("\n");
        sb.append("*Файл:* ").append(issue.component).append(":").append(issue.line).append("\n");
        sb.append("*Сообщение:* ").append(issue.message).append("\n");
        sb.append("{code}\n").append("// Код недоступен\n").append("{code}\n\n");
        return sb.toString();
    }
}