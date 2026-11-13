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
        String rule = param(q, "rule", null);
        int page = paramInt(q, "p", 1);
        int ps = paramInt(q, "ps", 50);

        SonarIssuesApi api = newApi(project);
        // 6 аргументов
        JSONObject rawResponse = api.fetchPageRaw(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, rule, page, ps);

        putSource(rawResponse, project);
        sendJson(ex, 200, rawResponse);
    }
}