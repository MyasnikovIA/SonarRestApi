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
import java.util.TreeMap;

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
        Map<String, Integer> byRules;
        Map<String, Integer> byFiles;
        Map<String, Integer> bySev;

        if ("SECURITY_HOTSPOT".equalsIgnoreCase(qual)) {
            // Hotspots живут в /api/hotspots/search, не в issues.
            // Снимок их не содержит — тянем live.
            List<Issue> list = newApi(project).fetchHotspots(sev);

            total = list.size();
            byRules = new TreeMap<>();
            byFiles = new TreeMap<>();
            bySev   = new TreeMap<>();

            for (Issue i : list) {
                if (i.rule != null && !i.rule.isEmpty()) {
                    byRules.merge(i.rule, 1, Integer::sum);
                }
                if (i.component != null && !i.component.isEmpty()) {
                    byFiles.merge(i.component, 1, Integer::sum);
                }
                String key = (i.severity != null && !i.severity.isEmpty())
                        ? i.severity
                        : "UNKNOWN";
                bySev.merge(key, 1, Integer::sum);
            }
        } else {
            Snapshot snap = snapshotFor(project);
            if (snap != null) {
                List<Issue> filtered = snap.filter(sev, qual);
                total   = filtered.size();
                byRules = snap.facetRules(sev, qual);
                byFiles = snap.facetFiles(sev, qual);
                bySev   = snap.facetSeverities(sev, qual);
            } else {
                SonarIssuesApi api = newApi(project);
                total   = api.count(sev, qual);
                byRules = api.facet(sev, qual, "rules");
                byFiles = api.facet(sev, qual, "files");
                bySev   = api.facet(sev, qual, "severities");
            }
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