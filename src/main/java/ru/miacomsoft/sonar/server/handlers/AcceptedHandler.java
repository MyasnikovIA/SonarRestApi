package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * GET /api/accepted?project=KEY&sev=ALL&qual=ALL&facets=true
 *
 * Список уже обработанных (ACCEPTED) issues проекта.
 *
 * Фасеты (severities / types / impactSoftwareQualities / impactSeverities)
 * считаются локально по уже загруженному списку — без дополнительных запросов к SonarQube.
 *
 * Аналог официального запроса:
 *   /api/issues/search?...&issueStatuses=ACCEPTED&ps=100
 *     &facets=cleanCodeAttributeCategories,impactSoftwareQualities,
 *             severities,types,impactSeverities,codeVariants
 *     &additionalFields=_all
 */
public class AcceptedHandler extends BaseHandler {

    public AcceptedHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        String sev            = param(q, "sev",  "ALL");
        String qual           = param(q, "qual", "ALL");
        boolean includeFacets = paramBool(q, "facets", true);

        SonarIssuesApi api = newApi(project);
        List<Issue> issues = api.fetchAccepted(sev, qual);

        // Предзаполняем ruleName один раз на уникальное правило (кэш в ServerState)
        Set<String> uniqueRules = new HashSet<>();
        for (Issue it : issues) {
            if (it.rule != null && !it.rule.isEmpty()) uniqueRules.add(it.rule);
        }
        for (String r : uniqueRules) {
            try { api.fetchRuleName(r); } catch (Exception ignored) {}
        }

        JSONArray arr = new JSONArray();
        for (Issue it : issues) {
            JSONObject io = it.toJson();
            io.put("ruleName", api.fetchRuleName(it.rule));
            arr.put(io);
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("total", issues.size());
        o.put("issues", arr);

        if (includeFacets) {
            o.put("facets", buildFacets(issues));
        }

        putSource(o, project);
        sendJson(ex, 200, o);
    }

    // ==================== facets ====================

    private JSONObject buildFacets(List<Issue> issues) {
        Map<String, Integer> bySeverity         = new TreeMap<>();
        Map<String, Integer> byType             = new TreeMap<>();
        Map<String, Integer> byImpactQuality    = new TreeMap<>();
        Map<String, Integer> byImpactSeverity   = new TreeMap<>();
        Map<String, Integer> byResolution       = new TreeMap<>();

        for (Issue it : issues) {
            if (it.severity != null && !it.severity.isEmpty()) {
                bySeverity.merge(it.severity, 1, Integer::sum);
            }
            if (it.type != null && !it.type.isEmpty()) {
                byType.merge(it.type, 1, Integer::sum);
            }
            if (it.resolution != null && !it.resolution.isEmpty()) {
                byResolution.merge(it.resolution, 1, Integer::sum);
            }
            for (Issue.Impact im : it.impacts) {
                if (im.softwareQuality != null && !im.softwareQuality.isEmpty()) {
                    byImpactQuality.merge(im.softwareQuality, 1, Integer::sum);
                }
                if (im.severity != null && !im.severity.isEmpty()) {
                    byImpactSeverity.merge(im.severity, 1, Integer::sum);
                }
            }
        }

        JSONObject f = new JSONObject();
        f.put("severities",              new JSONObject(bySeverity));
        f.put("types",                   new JSONObject(byType));
        f.put("resolutions",             new JSONObject(byResolution));
        f.put("impactSoftwareQualities", new JSONObject(byImpactQuality));
        f.put("impactSeverities",        new JSONObject(byImpactSeverity));
        return f;
    }
}