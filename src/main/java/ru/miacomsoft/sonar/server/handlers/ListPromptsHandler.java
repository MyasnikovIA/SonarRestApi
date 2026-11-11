package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.PromptTemplate;
import ru.miacomsoft.sonar.server.ServerState;

/**
 * GET /api/prompt/list
 *
 * Диагностика: показывает, какой каталог промптов видит сервер
 * и какие .txt в нём лежат. Помогает отличить «маршрут не работает»
 * от «файла нет».
 */
public class ListPromptsHandler extends BaseHandler {

    private final PromptTemplate template;

    public ListPromptsHandler(ServerState state, PromptTemplate template) {
        super(state);
        this.template = template;
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        JSONObject o = new JSONObject();
        String abs;
        boolean dirExists;
        try {
            abs = template.getPromptsDir().toAbsolutePath().normalize().toString();
            dirExists = java.nio.file.Files.isDirectory(template.getPromptsDir());
        } catch (Exception e) {
            abs = String.valueOf(template.getPromptsDir());
            dirExists = false;
        }
        o.put("dir", abs);
        o.put("dirExists", dirExists);

        JSONArray names = new JSONArray();
        for (String n : template.listAvailable()) names.put(n);
        o.put("prompts", names);
        o.put("count", names.length());

        sendJson(ex, 200, o);
    }
}