package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GET /api/sources/full?key=COMPONENT_KEY&from=1&to=1002&format=json|txt|html
 *
 * Возвращает объединенный листинг кода файла.
 * - format=json (по умолчанию) — возвращает JSON с чистым текстом.
 * - format=txt — возвращает чистый текст (text/plain).
 * - format=html — возвращает HTML-страницу с подсветкой синтаксиса (text/html).
 */
public class SourceFullHandler extends BaseHandler {

    public SourceFullHandler(ServerState state) {
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

        // Формат ответа: json (по умолчанию), txt или html
        String format = param(q, "format", "json").toLowerCase();

        String url = "/api/sources/lines?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&from=" + from + "&to=" + to;

        // Получаем сырой ответ от SonarQube
        String resp = state.getClient().get(url);
        JSONObject root = new JSONObject(resp);
        JSONArray sources = root.optJSONArray("sources");

        StringBuilder sb = new StringBuilder();
        if (sources != null) {
            for (int i = 0; i < sources.length(); i++) {
                JSONObject lineObj = sources.getJSONObject(i);
                String code = lineObj.optString("code", "");

                // Для txt и json форматов убираем HTML-теги и декодируем сущности
                if (!"html".equals(format)) {
                    code = cleanHtml(code);
                }

                sb.append(code);
                if (i < sources.length() - 1) {
                    sb.append("\n");
                }
            }
        }

        if ("txt".equals(format)) {
            // Отдаем чистый текст
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else if ("html".equals(format)) {
            // Отдаем HTML с минимальной стилизацией для подсветки SonarQube
            String html = "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n"
                    + "<title>" + key + "</title>\n"
                    + "<style>\n"
                    + "body { background-color: #0d1117; color: #e6edf3; font-family: monospace; padding: 20px; }\n"
                    + "pre { margin: 0; white-space: pre-wrap; word-wrap: break-word; font-size: 14px; }\n"
                    + "/* Классы подсветки SonarQube */\n"
                    + ".k { color: #ff7b72; font-weight: bold; } /* keyword */\n"
                    + ".s { color: #a5d6ff; } /* string */\n"
                    + ".c, .cd, .j { color: #8b949e; font-style: italic; } /* comments */\n"
                    + ".sym { color: #79c0ff; } /* symbols */\n"
                    + "</style>\n</head>\n<body>\n<pre><code>"
                    + sb.toString()
                    + "</code></pre>\n</body>\n</html>";

            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            // По умолчанию (json)
            JSONObject result = new JSONObject();
            result.put("key", key);
            result.put("format", "json");
            result.put("code", sb.toString());
            result.put("linesCount", sources != null ? sources.length() : 0);
            sendJson(ex, 200, result);
        }
    }

    /**
     * Очищает строку от HTML-тегов и декодирует основные HTML-сущности.
     */
    private static String cleanHtml(String html) {
        if (html == null) return "";
        // Удаляем все HTML-теги (например, <span class="k">)
        String res = html.replaceAll("<[^>]+>", "");
        // Декодируем сущности
        return res.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}