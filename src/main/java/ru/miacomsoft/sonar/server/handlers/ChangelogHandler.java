package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;

/**
 * GET /api/changelog?issueKey=KEY
 */
public class ChangelogHandler extends BaseHandler {

    public ChangelogHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String issueKey = param(q, "issueKey", null);

        if (issueKey == null || issueKey.isEmpty()) {
            sendError(ex, 400, "Параметр issueKey обязателен");
            return;
        }

        JSONArray changelog = newApi(project).fetchChangelog(issueKey);

        JSONObject o = new JSONObject();
        o.put("changelog", changelog);

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}