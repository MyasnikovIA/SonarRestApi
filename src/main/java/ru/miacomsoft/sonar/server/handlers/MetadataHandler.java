package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GET /api/metadata?project=KEY
 *
 * Сводная справочная информация о проекте:
 *   - languages     : /api/languages/list
 *   - branches      : /api/project_branches/list?project=KEY
 *   - pullRequests  : /api/project_pull_requests/list?project=KEY
 *   - settings      : /api/settings/values?keys=sonar.announcement.*
 *
 * Каждый источник обёрнут в try/catch: если один недоступен — остальные возвращаются.
 */
public class MetadataHandler extends BaseHandler {

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public MetadataHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        JSONObject o = new JSONObject();
        o.put("project", project);

        if (paramBool(q, "languages", true))    o.put("languages",    safeArray(this::fetchLanguages));
        if (paramBool(q, "branches", true))     o.put("branches",     safeArray(() -> fetchBranches(project)));
        if (paramBool(q, "pullRequests", true)) o.put("pullRequests", safeArray(() -> fetchPullRequests(project)));
        if (paramBool(q, "settings", true))     o.put("settings",     safeObject(this::fetchSettings));

        putSource(o, project);
        sendJson(ex, 200, o);
    }

    private JSONArray fetchLanguages() throws Exception {
        JSONObject r = new JSONObject(state.getClient().get("/api/languages/list"));
        JSONArray a = r.optJSONArray("languages");
        return a != null ? a : new JSONArray();
    }

    private JSONArray fetchBranches(String project) throws Exception {
        String p = URLEncoder.encode(project, StandardCharsets.UTF_8);
        JSONObject r = new JSONObject(state.getClient()
                .get("/api/project_branches/list?project=" + p));
        JSONArray a = r.optJSONArray("branches");
        return a != null ? a : new JSONArray();
    }

    private JSONArray fetchPullRequests(String project) throws Exception {
        String p = URLEncoder.encode(project, StandardCharsets.UTF_8);
        JSONObject r = new JSONObject(state.getClient()
                .get("/api/project_pull_requests/list?project=" + p));
        JSONArray a = r.optJSONArray("pullRequests");
        return a != null ? a : new JSONArray();
    }

    private JSONObject fetchSettings() throws Exception {
        String keys = URLEncoder.encode(
                "sonar.announcement.displayMessage,sonar.announcement.message",
                StandardCharsets.UTF_8);
        return new JSONObject(state.getClient()
                .get("/api/settings/values?keys=" + keys));
    }

    private JSONArray safeArray(ThrowingSupplier<JSONArray> s) {
        try { return s.get(); } catch (Exception e) { return new JSONArray(); }
    }

    private JSONObject safeObject(ThrowingSupplier<JSONObject> s) {
        try { return s.get(); } catch (Exception e) { return new JSONObject(); }
    }
}