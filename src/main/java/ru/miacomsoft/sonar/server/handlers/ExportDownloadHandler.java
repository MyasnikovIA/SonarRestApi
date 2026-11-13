package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ExportDownloadHandler extends BaseHandler {

    public ExportDownloadHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);
        String filePathStr = param(q, "file", null);

        if (filePathStr == null || filePathStr.isEmpty()) {
            sendError(ex, 400, "Параметр file обязателен");
            return;
        }

        Path file = Path.of(filePathStr);
        if (!Files.exists(file)) {
            sendError(ex, 404, "Файл не найден (возможно, уже удален)");
            return;
        }

        byte[] bytes = Files.readAllBytes(file);

        String fileName = file.getFileName().toString();
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1);
        String mime = "application/octet-stream";
        if ("txt".equals(ext)) mime = "text/plain";
        if ("md".equals(ext)) mime = "text/markdown";
        if ("html".equals(ext)) mime = "text/html";

        ex.getResponseHeaders().set("Content-Type", mime + "; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        ex.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }

        try {
            Files.delete(file);
            System.out.println("[ExportDownload] Временный файл удален: " + file.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ExportDownload] Ошибка удаления файла: " + e.getMessage());
        }
    }
}