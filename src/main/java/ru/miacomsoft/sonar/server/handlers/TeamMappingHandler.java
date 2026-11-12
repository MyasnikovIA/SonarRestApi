package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GET /api/team_mapping  — вернуть CSV-файл
 * PUT /api/team_mapping  — сохранить CSV-файл (в теле запроса)
 * DELETE /api/team_mapping — удалить CSV-файл
 */
public class TeamMappingHandler extends BaseHandler {

    private static final Path FILE_PATH = Path.of("team_mapping.csv").toAbsolutePath().normalize();

    public TeamMappingHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "GET".equalsIgnoreCase(method) ||
                "PUT".equalsIgnoreCase(method) ||
                "POST".equalsIgnoreCase(method) ||
                "DELETE".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();

        if ("GET".equalsIgnoreCase(method)) {
            if (!Files.exists(FILE_PATH)) {
                sendJson(ex, 404, new JSONObject().put("error", "Файл не найден"));
                return;
            }
            byte[] bytes = Files.readAllBytes(FILE_PATH);
            ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        else if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)) {
            byte[] body = ex.getRequestBody().readAllBytes();
            if (body.length == 0) {
                sendError(ex, 400, "Пустое тело запроса");
                return;
            }
            Files.write(FILE_PATH, body);
            sendJson(ex, 200, new JSONObject().put("saved", true).put("path", FILE_PATH.toString()));
        }
        else if ("DELETE".equalsIgnoreCase(method)) {
            Files.deleteIfExists(FILE_PATH);
            sendJson(ex, 200, new JSONObject().put("deleted", true));
        }
    }
}