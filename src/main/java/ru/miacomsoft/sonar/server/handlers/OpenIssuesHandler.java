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

public class OpenIssuesHandler extends BaseHandler {

    public OpenIssuesHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        String sev      = param(q, "sev",  "ALL");
        String qual     = param(q, "qual", "ALL");
        boolean withCode = paramBool(q, "snippets", true);
        int page = paramInt(q, "p", 1);
        int ps = paramInt(q, "ps", 50);

        SonarIssuesApi api = newApi(project);
        JSONObject rawResponse = api.fetchPageRaw("OPEN,REOPENED", sev, qual, page, ps);
        JSONArray rawIssues = rawResponse.optJSONArray("issues");

        JSONArray arr = new JSONArray();
        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                Issue it = Issue.from(rawIssues.getJSONObject(i), project);
                JSONObject io = it.toJson();

                if (io.optString("ruleName", "").isEmpty()
                        && it.rule != null && !it.rule.isEmpty()) {
                    try { io.put("ruleName", api.fetchRuleName(it.rule)); }
                    catch (Exception ignored) { io.put("ruleName", ""); }
                }

                if (withCode) {
                    try {
                        List<String> snippet = fetchSnippetCached(project, it.key);
                        io.put("snippet", new JSONArray(snippet));
                    } catch (Exception e) {
                        io.put("snippet", new JSONArray());
                        io.put("snippetError", e.getMessage());
                    }
                } else {
                    io.put("snippet", new JSONArray());
                }

                arr.put(io);
            }
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("total", rawResponse.optInt("total", 0));
        o.put("p", page);
        o.put("ps", ps);
        o.put("statuses", new JSONArray(new String[]{"OPEN", "REOPENED"}));
        o.put("snippetsIncluded", withCode);
        o.put("issues", arr);
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}