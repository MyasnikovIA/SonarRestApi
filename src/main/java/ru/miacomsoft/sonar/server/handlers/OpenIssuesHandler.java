package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;

/**
 * GET /api/open_issues?project=KEY[&sev=ALL][&qual=ALL][&snippets=true]
 *
 * Возвращает все НЕОБРАБОТАННЫЕ замечания (status = OPEN или REOPENED).
 * Обработанные (ACCEPTED, CONFIRMED, RESOLVED, FALSE_POSITIVE, WONTFIX)
 * сюда не попадают.
 *
 * По умолчанию (?snippets=true) для каждого issue подтягивается:
 *   - ruleName      — человекочитаемое имя правила
 *   - snippet       — массив строк кода с маркерами §HL_START§ / §HL_END§
 *
 * Это позволяет клиенту (HermesAgent) не делать отдельный /api/snippet
 * на каждое замечание: полный контекст для промпта уже в ответе.
 *
 * ?snippets=false — быстрый режим: только список issue без кода и ruleName.
 *
 * Каждый элемент из issues можно передать в /api/prompt?issueKey=KEY
 * (там те же данные используются для сборки промпта).
 */
public class OpenIssuesHandler extends BaseHandler {

    public OpenIssuesHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        String sev      = param(q, "sev",  "ALL");
        String qual     = param(q, "qual", "ALL");
        boolean withCode = paramBool(q, "snippets", true);

        SonarIssuesApi api = newApi(project);
        List<Issue> issues = api.fetchOpen(sev, qual);

        JSONArray arr = new JSONArray();
        for (Issue it : issues) {
            JSONObject io = it.toJson();

            // ruleName — дотягиваем из кэша (fetchOpen его не заполняет)
            if (io.optString("ruleName", "").isEmpty()
                    && it.rule != null && !it.rule.isEmpty()) {
                try { io.put("ruleName", api.fetchRuleName(it.rule)); }
                catch (Exception ignored) { io.put("ruleName", ""); }
            }

            // snippet — код с маркерами §HL_START§ / §HL_END§
            if (withCode) {
                try {
                    List<String> snippet = fetchSnippetCached(project, it.key);
                    io.put("snippet", new JSONArray(snippet));
                } catch (Exception e) {
                    io.put("snippet", new JSONArray());
                    io.put("snippetError", e.getMessage());
                }
            } else {
                io.put("snippet", new JSONArray());
            }

            arr.put(io);
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("statuses", new JSONArray(new String[]{"OPEN", "REOPENED"}));
        o.put("snippetsIncluded", withCode);
        o.put("total", issues.size());
        o.put("issues", arr);
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}