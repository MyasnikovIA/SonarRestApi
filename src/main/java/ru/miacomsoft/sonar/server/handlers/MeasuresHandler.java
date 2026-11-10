package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Measures;
import ru.miacomsoft.sonar.SonarMeasuresApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;

/**
 * GET /api/measures?project=KEY[&metricKeys=a,b,c]
 *
 * Возвращает агрегированные метрики проекта:
 *   - Quality Gate
 *   - общее число issues (violations) и разбивку по видам
 *   - accepted / high impact accepted
 *   - новые issues
 *   - размер (lines, ncloc, ncloc по языкам)
 *   - покрытие, дубликаты
 *   - рейтинги A..E
 *
 * metricKeys можно переопределить вручную (по умолчанию — полный набор).
 */
public class MeasuresHandler extends BaseHandler {

    public MeasuresHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String metricKeys = param(q, "metricKeys", null);

        SonarMeasuresApi api = (metricKeys == null)
                ? new SonarMeasuresApi(state.getClient())
                : new SonarMeasuresApi(state.getClient(), metricKeys);

        Measures m = api.fetch(project);
        JSONObject o = m.toJson();
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}