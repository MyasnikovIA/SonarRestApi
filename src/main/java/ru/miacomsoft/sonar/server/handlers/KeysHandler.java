package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;

public class KeysHandler extends BaseHandler {

    public KeysHandler(ServerState state) {
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
        int ps = paramInt(q, "ps", 500);

        SonarIssuesApi api = newApi(project);
        // 6 аргументов
        JSONObject rawResponse = api.fetchPageRaw(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, rule, page, ps);

        JSONArray rawIssues = rawResponse.optJSONArray("issues");
        JSONArray keys = new JSONArray();
        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                keys.put(rawIssues.getJSONObject(i).optString("key"));
            }
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("rule", rule != null ? rule : "");
        o.put("total", rawResponse.optInt("total", 0));
        o.put("p", page);
        o.put("ps", ps);
        o.put("keys", keys);

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}