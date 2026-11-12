package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

/**
 * GET /api/projects
 * Возвращает полный список доступных проектов в SonarQube.
 */
public class ProjectsHandler extends BaseHandler {

    public ProjectsHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        JSONArray allProjects = new JSONArray();
        int page = 1;
        int pageSize = 500;
        int total = -1;

        try {
            while (total < 0 || allProjects.length() < total) {
                // Используем /api/components/search?qualifiers=TRK, так как он доступен обычным пользователям
                String url = "/api/components/search?qualifiers=TRK&p=" + page + "&ps=" + pageSize;
                String resp = state.getClient().get(url);
                JSONObject root = new JSONObject(resp);

                JSONObject paging = root.optJSONObject("paging");
                if (paging != null) {
                    total = paging.optInt("total", 0);
                }

                JSONArray components = root.optJSONArray("components");
                if (components == null || components.length() == 0) {
                    break;
                }

                for (int i = 0; i < components.length(); i++) {
                    JSONObject comp = components.getJSONObject(i);
                    JSONObject proj = new JSONObject();
                    proj.put("key", comp.optString("key"));
                    proj.put("name", comp.optString("name"));
                    allProjects.put(proj);
                }

                page++;
                if (page > 50) break; // Safety limit
            }
        } catch (Exception e) {
            // Если нет прав даже на просмотр (крайне редко), просто возвращаем пустой список
            System.err.println("[ProjectsHandler] Не удалось получить список проектов: " + e.getMessage());
        }

        JSONObject result = new JSONObject();
        result.put("projects", allProjects);
        sendJson(ex, 200, result);
    }
}