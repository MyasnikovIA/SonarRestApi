package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class MatrixHandler extends BaseHandler {

    public MatrixHandler(ServerState state) {
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
        JSONArray rawIssues = rawResponse.optJSONArray("issues");

        Set<String> rules = new TreeSet<>();
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();

        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                Issue it = Issue.from(rawIssues.getJSONObject(i), project);
                rules.add(it.rule);
                matrix.computeIfAbsent(it.component, k -> new TreeMap<>())
                        .merge(it.rule, 1, Integer::sum);
            }
        }

        JSONArray rows = new JSONArray();
        for (Map.Entry<String, Map<String, Integer>> e : matrix.entrySet()) {
            JSONObject row = new JSONObject();
            row.put("file", e.getKey());
            JSONObject counts = new JSONObject();
            int total = 0;
            for (String r : rules) {
                int n = e.getValue().getOrDefault(r, 0);
                counts.put(r, n);
                total += n;
            }
            row.put("counts", counts);
            row.put("total", total);
            rows.put(row);
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("rule", rule != null ? rule : "");
        o.put("total", rawResponse.optInt("total", 0));
        o.put("p", page);
        o.put("ps", ps);
        o.put("rules", new JSONArray(rules));
        o.put("rows", rows);
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}