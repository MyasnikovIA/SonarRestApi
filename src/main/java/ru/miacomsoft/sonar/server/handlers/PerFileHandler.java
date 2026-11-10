package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;

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

        Map<String, List<Issue>> byFile = fetchIssuesByFile(project, sev, qual);

        JSONArray files = new JSONArray();
        int totalIssues = 0;
        for (Map.Entry<String, List<Issue>> e : byFile.entrySet()) {
            List<Issue> issues = e.getValue();
            totalIssues += issues.size();

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
        o.put("filesCount", byFile.size());
        o.put("issuesCount", totalIssues);
        o.put("files", files);
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}