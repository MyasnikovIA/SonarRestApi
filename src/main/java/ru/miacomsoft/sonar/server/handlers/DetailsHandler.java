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

public class DetailsHandler extends BaseHandler {

    public DetailsHandler(ServerState state) {
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
        int ps = paramInt(q, "ps", 10);
        int maxSnippets = paramInt(q, "snippets", 5);
        if (maxSnippets < 0) maxSnippets = 0;

        SonarIssuesApi api = newApi(project);
        // 6 аргументов
        JSONObject rawResponse = api.fetchPageRaw(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, rule, page, ps);
        JSONArray rawIssues = rawResponse.optJSONArray("issues");

        Map<String, List<Issue>> byFile = new TreeMap<>();
        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                Issue it = Issue.from(rawIssues.getJSONObject(i), project);
                byFile.computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
            }
        }

        JSONArray files = new JSONArray();
        int totalIssues = 0;
        for (Map.Entry<String, List<Issue>> e : byFile.entrySet()) {
            List<Issue> issues = e.getValue();
            totalIssues += issues.size();

            JSONArray issuesArr = new JSONArray();
            int n = 0;
            boolean isHotspot = "SECURITY_HOTSPOT".equalsIgnoreCase(qual);

            for (Issue it : issues) {
                if (n >= maxSnippets) break;
                JSONObject io = it.toJson();
                try { io.put("ruleName", it.ruleName != null && !it.ruleName.isEmpty()
                        ? it.ruleName : api.fetchRuleName(it.rule)); }
                catch (Exception ex2) { io.put("ruleName", ""); }
                if (!isHotspot) {
                    try { io.put("snippet", new JSONArray(fetchSnippetCached(project, it.key))); }
                    catch (Exception ex2) { io.put("snippet", new JSONArray()); }
                } else {
                    io.put("snippet", new JSONArray());
                }
                issuesArr.put(io);
                n++;
            }

            JSONObject f = new JSONObject();
            f.put("file", e.getKey());
            f.put("count", issues.size());
            f.put("returned", issuesArr.length());
            f.put("hasMore", issues.size() > issuesArr.length());
            f.put("remaining", Math.max(0, issues.size() - issuesArr.length()));
            f.put("issues", issuesArr);
            files.put(f);
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("rule", rule != null ? rule : "");
        o.put("total", rawResponse.optInt("total", 0));
        o.put("p", page);
        o.put("ps", ps);
        o.put("maxSnippetsPerFile", maxSnippets);
        o.put("filesCount", byFile.size());
        o.put("issuesCount", totalIssues);
        o.put("files", files);
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}