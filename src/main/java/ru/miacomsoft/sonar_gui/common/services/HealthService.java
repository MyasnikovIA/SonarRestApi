package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;

/**
 * Обёртка над GET /api/health.
 * Используется TopBar для периодической проверки доступности сервера.
 */
public class HealthService {

    private final SonarApiClient api;

    public HealthService(SonarApiClient api) {
        this.api = api;
    }

    public JSONObject fetchHealth() throws Exception {
        return api.getJson("/api/health");
    }

    /** Быстрая проверка: сервер доступен и сессия готова. */
    public boolean isReady() {
        try {
            JSONObject d = fetchHealth();
            return d.optBoolean("sessionReady", false);
        } catch (Exception e) {
            return false;
        }
    }
}