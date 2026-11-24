package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.TeamMapping;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GET    /api/team_mapping           — скачать CSV (text/csv)
 * GET    /api/team_mapping?info=1    — статистика в JSON
 * PUT    /api/team_mapping           — загрузить CSV (text/plain)
 * POST   /api/team_mapping           — то же, что PUT (алиас)
 * DELETE /api/team_mapping           — удалить CSV
 */
public class TeamMappingHandler extends BaseHandler {

    private static final Path FILE_PATH = Path.of("team_mapping.csv").toAbsolutePath().normalize();

    public TeamMappingHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "POST".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();

        if ("GET".equalsIgnoreCase(method)) {
            handleGet(ex);
        } else if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)) {
            handlePut(ex);
        } else {
            handleDelete(ex);
        }
    }

    // ============================================================
    // GET
    // ============================================================

    private void handleGet(HttpExchange ex) throws Exception {
        String query = ex.getRequestURI().getQuery();
        boolean infoMode = query != null && (
                query.contains("info=1") || query.contains("info=true") || query.contains("format=json"));

        if (infoMode) {
            sendStatsJson(ex);
            return;
        }

        if (!Files.exists(FILE_PATH)) {
            sendJson(ex, 404, new JSONObject().put("error", "Файл не найден"));
            return;
        }

        byte[] bytes = Files.readAllBytes(FILE_PATH);
        ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"team_mapping.csv\"");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendStatsJson(HttpExchange ex) throws Exception {
        JSONObject o = new JSONObject();
        o.put("exists", Files.isRegularFile(FILE_PATH));
        o.put("path",   FILE_PATH.toString());
        o.put("fileSize", Files.isRegularFile(FILE_PATH) ? Files.size(FILE_PATH) : 0);

        if (Files.isRegularFile(FILE_PATH)) {
            TeamMapping tm = TeamMapping.load();
            o.put("totalRows",         tm.getTotalRows());
            o.put("rowsWithTeam",      tm.getRowsWithTeam());
            o.put("rowsWithComponent", tm.getRowsWithComponent());
            o.put("rowsWithBoth",      tm.getRowsWithBoth());
            o.put("pathCount",         tm.getPathCount());
        }
        sendJson(ex, 200, o);
    }

    // ============================================================
    // PUT / POST
    // ============================================================

    private void handlePut(HttpExchange ex) throws Exception {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            sendError(ex, 400, "Пустое тело запроса");
            return;
        }

        Files.write(FILE_PATH, body);

        TeamMapping tm = TeamMapping.load();

        JSONObject stats = new JSONObject();
        stats.put("totalRows",         tm.getTotalRows());
        stats.put("rowsWithTeam",      tm.getRowsWithTeam());
        stats.put("rowsWithComponent", tm.getRowsWithComponent());
        stats.put("rowsWithBoth",      tm.getRowsWithBoth());
        stats.put("pathCount",         tm.getPathCount());

        JSONObject resp = new JSONObject();
        resp.put("saved",    true);
        resp.put("path",     FILE_PATH.toString());
        resp.put("fileSize", body.length);
        resp.put("stats",    stats);

        sendJson(ex, 200, resp);
    }

    // ============================================================
    // DELETE
    // ============================================================

    private void handleDelete(HttpExchange ex) throws Exception {
        Files.deleteIfExists(FILE_PATH);
        sendJson(ex, 200, new JSONObject().put("deleted", true));
    }
}