package ru.miacomsoft.sonar_gui.backend.exporters;

import ru.miacomsoft.sonar_gui.backend.Issue;

public class MdExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        String link = "https://" + host + "/project/issues?id=" + projectName + "&open=" + issue.key;
        String sourceLink = "https://" + host + "/code?id=" + projectName + "&selected=" + projectName + ":" + issue.component;
        String tagsStr = (issue.tags == null || issue.tags.isEmpty()) ? "нет" : String.join(", ", issue.tags);
        String lastComment = getLastComment(issue);

        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(issue.key).append("\n\n");
        sb.append("- **Ссылка на ошибку:** [Открыть](").append(link).append(")\n");
        sb.append("- **Ссылка на исходник:** [Открыть](").append(sourceLink).append(")\n");
        sb.append("- **Правило:** ").append(issue.rule).append("\n");
        sb.append("- **Файл:** `").append(issue.component).append(":").append(issue.line).append("`\n");
        sb.append("- **Теги:** ").append(tagsStr).append("\n");
        sb.append("- **Сообщение:** ").append(issue.message).append("\n");
        sb.append("- **Последний комментарий:** ").append(lastComment).append("\n\n");
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
}