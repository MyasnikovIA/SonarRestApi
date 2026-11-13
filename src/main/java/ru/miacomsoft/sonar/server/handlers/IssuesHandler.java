package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;

public class IssuesHandler extends BaseHandler {

    public IssuesHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String sev = param(q, "sev", "MEDIUM");
        String qual = param(q, "qual", "MAINTAINABILITY");
        String rule = param(q, "rule", null);
        int page = paramInt(q, "p", 1);
        int ps = paramInt(q, "ps", 50);

        // hideProcessed: true — только OPEN/CONFIRMED/REOPENED
        //               false — все статусы (включая ACCEPTED/RESOLVED/WONTFIX/...)
        // По умолчанию false, чтобы вкладка «Отчет» с фильтром statusFilter=ALL
        // видела реальное общее количество (а не только активные).
        boolean hideProcessed = paramBool(q, "hideProcessed", false);
        String statuses = hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : null;

        SonarIssuesApi api = newApi(project);

        // Фронт запрашивает сразу ВСЁ (ps >= 10000)
        if (ps >= 10000) {
            System.out.println("[IssuesHandler] Запрос на выгрузку ВСЕХ замечаний (hideProcessed=" + hideProcessed + "). Запускаем сбор...");

            List<Issue> allIssues = api.fetchAllByStatuses(statuses, sev, qual);

            System.out.println("[IssuesHandler] Сбор завершен. Всего собрано: " + allIssues.size());

            JSONObject response = new JSONObject();
            response.put("total", allIssues.size());
            response.put("p", 1);
            response.put("ps", allIssues.size());
            response.put("issues", new JSONArray(allIssues.stream().map(Issue::toJson).toList()));

            putSource(response, project);
            sendJson(ex, 200, response);
            return;
        }

        // Обычный прокси-запрос для постраничного вывода
        int validPs = Math.min(ps, SonarIssuesApi.FETCH_PAGE_SIZE);
        JSONObject rawResponse = api.fetchPageRaw(statuses, sev, qual, rule, page, validPs);

        putSource(rawResponse, project);
        sendJson(ex, 200, rawResponse);
    }
}