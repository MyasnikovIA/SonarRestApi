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
        if (!ctx.isSessionReady()) return new ArrayList<>();

        List<ProjectItem> out = new ArrayList<>();
        int page = 1;
        int pageSize = 500;
        int total = -1;

        while (total < 0 || out.size() < total) {
            String url = "/api/components/search?qualifiers=TRK&p=" + page + "&ps=" + pageSize;
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
            page++;
            if (page > 50) break;
        }
        return out;
    }
}