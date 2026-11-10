package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;

public class TagsHandler extends BaseHandler {

    public TagsHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        // Теги в SonarQube глобальные, поэтому не вызываем resolveProject(ex).
        Map<String, String> q = parseQuery(ex);
        String query = param(q, "q", "");

        // SonarIssuesApi требует projectKey в конструкторе, но метод fetchTags его не использует.
        String project = param(q, "project", "default");

        SonarIssuesApi api = new SonarIssuesApi(state.getClient(), project);
        List<String> tags = api.fetchTags(query);

        JSONArray arr = new JSONArray();
        for (String t : tags) arr.put(t);

        JSONObject o = new JSONObject();
        o.put("tags", arr);
        sendJson(ex, 200, o);
    }
}