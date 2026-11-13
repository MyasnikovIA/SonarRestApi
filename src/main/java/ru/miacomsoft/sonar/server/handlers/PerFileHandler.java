package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class PerFileHandler extends BaseHandler {

    public PerFileHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String sev = param(q, "sev", "MEDIUM");
        String qual = param(q, "qual", "MAINTAINABILITY");
        int page = paramInt(q, "p", 1);
        int ps = paramInt(q, "ps", 50);

        SonarIssuesApi api = newApi(project);
        JSONObject rawResponse = api.fetchPageRaw("OPEN,REOPENED", sev, qual, page, ps);
        JSONArray rawIssues = rawResponse.optJSONArray("issues");

        Map<String, List<Issue>> byFile = new TreeMap<>();
        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                Issue it = Issue.from(rawIssues.getJSONObject(i), project);
                byFile.computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
            }
        }

        JSONArray files = new JSONArray();
        int totalIssuesOnPage = 0;
        for (Map.Entry<String, List<Issue>> e : byFile.entrySet()) {
            List<Issue> issues = e.getValue();
            totalIssuesOnPage += issues.size();

            JSONArray arr = new JSONArray();
            for (Issue it : issues) arr.put(it.toJson());

            JSONObject f = new JSONObject();
            f.put("file", e.getKey());
            f.put("count", issues.size());
            f.put("issues", arr);
            files.put(f);
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("total", rawResponse.optInt("total", 0));
        o.put("p", page);
        o.put("ps", ps);
        o.put("filesCount", byFile.size());
        o.put("issuesCount", totalIssuesOnPage);
        o.put("files", files);

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}