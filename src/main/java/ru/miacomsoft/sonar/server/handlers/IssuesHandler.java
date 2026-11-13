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

        SonarIssuesApi api = newApi(project);

        // Если фронтенд запрашивает сразу всё (ps >= 10000) или мы хотим выгрузить всё разом
        if (ps >= 10000) {
            System.out.println("[IssuesHandler] Запрос на выгрузку ВСЕХ замечаний. Запускаем сбор...");

            // Используем метод fetchAll, который умеет обходить лимит 10000
            List<Issue> allIssues = api.fetchAll(sev, qual);

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
        int validPs = Math.min(ps, 500);
        JSONObject rawResponse = api.fetchPageRaw(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, rule, page, validPs);

        putSource(rawResponse, project);
        sendJson(ex, 200, rawResponse);
    }
}