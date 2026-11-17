package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GET /api/sources/lines?key=COMPONENT_KEY&from=1&to=1002
 *
 * Возвращает полный листинг кода файла из SonarQube (включая подсветку синтаксиса, если она есть).
 * Параметр key должен быть полным ключом компонента (например, MEDMIS-tMIS.mis:Cache/FileCache.inc).
 */
public class SourceHandler extends BaseHandler {

    public SourceHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);

        String key = param(q, "key", null);
        if (key == null || key.isEmpty()) {
            sendError(ex, 400, "Параметр key обязателен (например: MEDMIS-tMIS.mis:Cache/FileCache.inc)");
            return;
        }

        // По умолчанию запрашиваем с 1-й строки, и до 100000-й (чтобы захватить весь файл)
        int from = paramInt(q, "from", 1);
        int to = paramInt(q, "to", 100000);

        String url = "/api/sources/lines?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&from=" + from + "&to=" + to;

        // Получаем сырой ответ от SonarQube
        String resp = state.getClient().get(url);

        // Парсим в JSONObject и отправляем клиенту как есть
        JSONObject result = new JSONObject(resp);

        sendJson(ex, 200, result);
    }
}