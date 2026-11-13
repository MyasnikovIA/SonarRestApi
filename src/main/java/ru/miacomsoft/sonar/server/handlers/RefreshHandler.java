package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

public class RefreshHandler extends BaseHandler {

    public RefreshHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        // Так как мы работаем напрямую с SonarQube, снимок не нужен.
        // Просто возвращаем OK, чтобы фронтенд перерисовал вкладку.
        JSONObject o = new JSONObject();
        o.put("status", "ok");
        o.put("message", "Данные всегда актуальны (прямые запросы к SonarQube)");
        sendJson(ex, 200, o);
    }
}