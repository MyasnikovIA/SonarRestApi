package ru.miacomsoft.sonar_gui.backend.exporters;

import ru.miacomsoft.sonar_gui.backend.Issue;

public class MdExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        String link = "https://" + host + "/project/issues?id=" + projectName
                + "&open=" + issue.key;
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(issue.key).append("\n\n");
        sb.append("- **Ссылка:** [Открыть](").append(link).append(")\n");
        sb.append("- **Правило:** ").append(issue.rule).append("\n");
        sb.append("- **Файл:** `").append(issue.component).append(":")
                .append(issue.line).append("`\n");
        sb.append("- **Сообщение:** ").append(issue.message).append("\n\n");
        return sb.toString();
    }
}