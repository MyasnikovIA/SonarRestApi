package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SetTagsHandler extends BaseHandler {

    public SetTagsHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        resolveProject(ex); // Валидация проекта
        Map<String, String> q = parseQuery(ex);
        String keysStr = param(q, "keys", "");
        String tagsStr = param(q, "tags", "");

        if (keysStr.isEmpty()) {
            sendError(ex, 400, "Параметр keys обязателен");
            return;
        }

        String[] keys = keysStr.split(",");
        List<Issue> issues = new ArrayList<>();
        for (String k : keys) {
            if (!k.trim().isEmpty()) {
                Issue stub = new Issue();
                stub.key = k.trim();
                issues.add(stub);
            }
        }

        SonarBulkApi bulk = newBulk();
        bulk.setTags(issues, tagsStr);

        JSONObject o = new JSONObject();
        o.put("success", true);
        o.put("count", issues.size());
        o.put("tags", tagsStr);
        sendJson(ex, 200, o);
    }
}