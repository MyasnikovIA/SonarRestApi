package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarBulkApi;
import ru.miacomsoft.sonar_gui.common.GuiContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Установка тегов issue напрямую через SonarBulkApi.setTags.
 */
public class SetTagsService {

    private final GuiContext ctx;

    public SetTagsService(GuiContext ctx) {
        this.ctx = ctx;
    }

    public JSONObject setTags(String projectKey, String keysCsv, String tagsCsv) throws Exception {
        if (!ctx.isSessionReady()) {
            System.err.println("[SetTagsService] Ошибка: сессия не готова.");
            throw new IllegalStateException("Сессия не готова");
        }

        System.out.println("[SetTagsService] Установка тегов [" + tagsCsv + "] для ключей: " + keysCsv);

        try {
            List<Issue> stubs = new ArrayList<>();
            if (keysCsv != null && !keysCsv.isBlank()) {
                for (String k : keysCsv.split(",")) {
                    String key = k.trim();
                    if (key.isEmpty()) continue;
                    Issue stub = new Issue();
                    stub.key = key;
                    stubs.add(stub);
                }
            }

            SonarBulkApi bulk = ctx.state().newBulk();
            bulk.setSilent(true);
            bulk.setTags(stubs, tagsCsv == null ? "" : tagsCsv);

            System.out.println("[SetTagsService] Теги успешно установлены для " + stubs.size() + " issues.");
            JSONObject o = new JSONObject();
            o.put("success", true);
            o.put("count", stubs.size());
            o.put("tags", tagsCsv == null ? "" : tagsCsv);
            return o;
        } catch (Exception e) {
            System.err.println("[SetTagsService] Ошибка при установке тегов: " + e.getMessage());
            throw e;
        }
    }

    public JSONObject setTags(String projectKey, String singleKey, String[] tags) throws Exception {
        String csv = (tags == null) ? "" : String.join(",", tags);
        return setTags(projectKey, singleKey, csv);
    }
}