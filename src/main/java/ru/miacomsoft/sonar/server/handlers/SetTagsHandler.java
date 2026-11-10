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
        String keysStr  = param(q, "keys",  "");
        String issueStr = param(q, "issue", "");
        String tagsStr  = param(q, "tags",  "");

// Новый клиент шлёт keys=KEY&issue=KEY; старый — только keys=KEY1,KEY2.
// Если keys пуст, но issue передан — используем issue как единственный ключ.
        if (keysStr.isEmpty() && !issueStr.isEmpty()) {
            keysStr = issueStr;
        }
        if (keysStr.isEmpty()) {
            sendError(ex, 400, "Параметр keys (или issue) обязателен");
            return;
        }

        List<Issue> issues = new ArrayList<>();
        for (String k : keysStr.split(",")) {
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