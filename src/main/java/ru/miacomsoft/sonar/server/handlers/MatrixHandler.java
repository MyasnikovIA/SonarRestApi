package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
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

        List<Issue> issues = fetchIssues(project, sev, qual);

        Set<String> rules = new TreeSet<>();
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();
        for (Issue it : issues) {
            rules.add(it.rule);
            matrix.computeIfAbsent(it.component, k -> new TreeMap<>())
                    .merge(it.rule, 1, Integer::sum);
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
        o.put("total", issues.size());
        o.put("rules", new JSONArray(rules));
        o.put("rows", rows);
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}