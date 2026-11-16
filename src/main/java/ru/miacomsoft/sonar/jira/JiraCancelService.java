package ru.miacomsoft.sonar.jira;

import ru.miacomsoft.sonar.jira.JiraIssueCommentService;
import ru.miacomsoft.sonar.jira.JiraIssueDeleteService;
import ru.miacomsoft.sonar.jira.JiraIssueTransitionService;

import java.io.IOException;

public class JiraCancelService {

    private final JiraIssueDeleteService deleteService;
    private final JiraIssueTransitionService transitionService;
    private final JiraIssueCommentService commentService;

    public JiraCancelService(JiraIssueDeleteService deleteService,
                             JiraIssueTransitionService transitionService,
                             JiraIssueCommentService commentService) {
        this.deleteService = deleteService;
        this.transitionService = transitionService;
        this.commentService = commentService;
    }

    public String deleteOrClose(String issueIdOrKey) throws IOException, InterruptedException {
        // 1) DELETE
        try {
            deleteService.delete(issueIdOrKey);
            return "deleted";
        } catch (IllegalStateException e) {
            String m = e.getMessage();
            if (m == null || !m.contains("HTTP 403")) throw e;
            System.out.println("  INFO: нет прав на удаление " + issueIdOrKey + " — закрываю/отменяю");
        }

        String wonDo = transitionService.findResolutionId(
                "Won't Do", "Won't Fix", "Не будет сделано", "Отклонено", "Не будет исправлено");

        // 2) Прямое закрытие
        for (String t : new String[]{"Closed","Закрыто","Done","Готово","Resolved","Решено"}) {
            if (transitionService.tryTransition(issueIdOrKey, t, wonDo)) {
                System.out.println("  INFO: закрыто через '" + t + "'"); return "closed";
            }
            if (transitionService.tryTransition(issueIdOrKey, t, null)) {
                System.out.println("  INFO: закрыто через '" + t + "'"); return "closed";
            }
        }

        // 3) Комментарий
        try {
            commentService.add(issueIdOrKey, "Задача на удаление");
            System.out.println("  INFO: комментарий добавлен");
        } catch (Exception e) { System.out.println("  WARN: комментарий: " + e.getMessage()); }

        // 3a) Новая → К разработке
        for (String t : new String[]{"К разработке","To Development","In Development"}) {
            if (transitionService.tryTransition(issueIdOrKey, t, null)) {
                System.out.println("  INFO: шаг 1 — '" + t + "'"); break;
            }
        }
        // 3b) К разработке → Анализ
        for (String t : new String[]{"Вернуть на анализ","Анализ","Analysis"}) {
            if (transitionService.tryTransition(issueIdOrKey, t, null)) {
                System.out.println("  INFO: шаг 2 — '" + t + "'"); break;
            }
        }
        // 3c) Анализ → Отменена
        for (String t : new String[]{"Отклонить задачу","Отменена","Отменено",
                "Cancelled","Cancel","Отклонена","Отклонено"}) {
            if (transitionService.tryTransition(issueIdOrKey, t, wonDo)) {
                System.out.println("  INFO: шаг 3 — '" + t + "'"); return "cancelled";
            }
            if (transitionService.tryTransition(issueIdOrKey, t, null)) {
                System.out.println("  INFO: шаг 3 — '" + t + "' (без resolution)"); return "cancelled";
            }
        }
        System.out.println("  WARN: не удалось ни удалить, ни отменить " + issueIdOrKey);
        return "not-removed";
    }
}