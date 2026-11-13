package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

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
        int page = paramInt(q, "p", 1);
        int ps = paramInt(q, "ps", 50); // По умолчанию 50 элементов на страницу

        SonarIssuesApi api = newApi(project);

        // Прокси-запрос напрямую, без парсинга в объекты
        JSONObject rawResponse = api.fetchPageRaw("OPEN,REOPENED", sev, qual, page, ps);

        // Опционально: добавляем source для отладки
        putSource(rawResponse, project);

        // Отдаем ответ как есть (в нем уже есть fields: total, p, ps, issues)
        sendJson(ex, 200, rawResponse);
    }
}