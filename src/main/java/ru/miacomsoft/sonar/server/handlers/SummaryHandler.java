package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.util.List;
import java.util.Map;

public class SummaryHandler extends BaseHandler {

    public SummaryHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String sev = param(q, "sev", "MEDIUM");
        String qual = param(q, "qual", "MAINTAINABILITY");

        int total;
        Map<String, Integer> byRules, byFiles, bySev;
        Snapshot snap = snapshotFor(project);
        if (snap != null) {
            List<Issue> filtered = snap.filter(sev, qual);
            total = filtered.size();
            byRules = snap.facetRules(sev, qual);
            byFiles = snap.facetFiles(sev, qual);
            bySev = snap.facetSeverities(sev, qual);
        } else {
            SonarIssuesApi api = newApi(project);
            total = api.count(sev, qual);
            byRules = api.facet(sev, qual, "rules");
            byFiles = api.facet(sev, qual, "files");
            bySev = api.facet(sev, qual, "severities");
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("total", total);
        o.put("byRules", new JSONObject(byRules));
        o.put("byFiles", new JSONObject(byFiles));
        o.put("bySeverities", new JSONObject(bySev));
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}