package ru.miacomsoft.sonar_gui.common.services;

import ru.miacomsoft.sonar_gui.common.GuiContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Список доступных тегов SonarQube + приоритетные (require-fix / not-require-fix).
 * Прямой вызов SonarIssuesApi.fetchTags.
 */
public class TagsService {

    public static final List<String> PRIORITY_TAGS =
            List.of("CWE", "not-require-fix", "require-fix");

    private final GuiContext ctx;

    public TagsService(GuiContext ctx) {
        this.ctx = ctx;
    }

    public List<String> fetchTags(String projectKey) throws Exception {
        if (!ctx.isSessionReady()) return new ArrayList<>(PRIORITY_TAGS);
        List<String> raw = ctx.state().newApi(projectKey).fetchTags(null);
        Set<String> tags = new LinkedHashSet<>();
        if (raw != null) {
            for (String t : raw) {
                if (t != null && !t.isBlank()) tags.add(t);
            }
        }
        for (String p : PRIORITY_TAGS) tags.add(p);
        return new ArrayList<>(tags);
    }
}