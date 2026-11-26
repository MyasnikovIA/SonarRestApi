package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.common.GuiContext;

/**
 * Проверка доступности SonarQube (напрямую, без промежуточного сервера).
 *
 * Возвращает структуру, совместимую с прежним /api/health:
 *   { status, sessionReady, sonarUrl }
 */
public class HealthService {

    private final GuiContext ctx;

    public HealthService(GuiContext ctx) {
        this.ctx = ctx;
    }

    public JSONObject fetchHealth() {
        JSONObject o = new JSONObject();
        o.put("status", "ok");
        o.put("sessionReady", ctx.isSessionReady());

        if (ctx.isSessionReady()) {
            try {
                o.put("sonarUrl", ctx.state().getSession().getUrl());
            } catch (Exception ignored) {}

            // Лёгкий ping к SonarQube — проверяем, что сервер реально отвечает.
            try {
                ctx.state().getClient().get("/api/system/ping");
                o.put("sonarReachable", true);
            } catch (Exception e) {
                o.put("sonarReachable", false);
                o.put("sonarError", e.getMessage());
            }
        } else {
            o.put("sonarReachable", false);
            o.put("sonarError", "session.properties не заполнен");
        }
        return o;
    }

    public boolean isReady() {
        JSONObject h = fetchHealth();
        return h.optBoolean("sessionReady", false)
                && h.optBoolean("sonarReachable", false);
    }
}