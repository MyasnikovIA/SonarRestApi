package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;
import java.util.TreeMap;

public class FacetsHandler extends BaseHandler {

    public FacetsHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        String sev = param(q, "sev", "ALL");
        String qual = param(q, "qual", "ALL");
        String rule = param(q, "rule", null);
        String facet = param(q, "facet", "rules");
        boolean hideProcessed = paramBool(q, "hideProcessed", false);

        String statuses = hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : null;

        SonarIssuesApi api = newApi(project);
        Map<String, Integer> data = api.facetWithStatuses(statuses, sev, qual, rule, facet);

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("facet", facet);
        o.put("values", new JSONObject(data));

        sendJson(ex, 200, o);
    }
}