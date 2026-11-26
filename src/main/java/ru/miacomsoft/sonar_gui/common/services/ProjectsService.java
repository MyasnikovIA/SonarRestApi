package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.common.GuiContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Список доступных проектов SonarQube — напрямую через /api/components/search.
 */
public class ProjectsService {

    public record ProjectItem(String key, String name) {
        @Override public String toString() { return key; }
    }

    private final GuiContext ctx;

    public ProjectsService(GuiContext ctx) {
        this.ctx = ctx;
    }

    public List<ProjectItem> fetchProjects() throws Exception {
        if (!ctx.isSessionReady()) {
            System.err.println("[ProjectsService] Ошибка: сессия SonarQube не готова.");
            return new ArrayList<>();
        }

        List<ProjectItem> out = new ArrayList<>();
        int page = 1;
        // Для /api/components/search максимальный ps = 500 (ограничение SonarQube)
        int pageSize = 500;
        int total = -1;

        System.out.println("[ProjectsService] Начало загрузки списка проектов (pageSize=" + pageSize + ")...");

        while (total < 0 || out.size() < total) {
            String url = "/api/components/search?qualifiers=TRK&p=" + page + "&ps=" + pageSize;
            System.out.println("[ProjectsService] Запрос страницы " + page + "...");

            try {
                JSONObject root = new JSONObject(ctx.state().getClient().get(url));

                JSONObject paging = root.optJSONObject("paging");
                if (paging != null) total = paging.optInt("total", 0);

                JSONArray components = root.optJSONArray("components");
                if (components == null || components.length() == 0) break;

                for (int i = 0; i < components.length(); i++) {
                    JSONObject c = components.getJSONObject(i);
                    out.add(new ProjectItem(
                            c.optString("key", ""),
                            c.optString("name", "")));
                }

                System.out.println("[ProjectsService] Страница " + page + " обработана. Загружено: " + out.size() + " из " + total);
                page++;

                if (page > 100) { // safety limit
                    System.out.println("[ProjectsService] Достигнут лимит страниц (100). Прерывание.");
                    break;
                }
            } catch (Exception e) {
                System.err.println("[ProjectsService] Ошибка при загрузке проектов (страница " + page + "): " + e.getMessage());
                throw e;
            }
        }

        System.out.println("[ProjectsService] Загрузка проектов успешно завершена. Всего: " + out.size());
        return out;
    }
}