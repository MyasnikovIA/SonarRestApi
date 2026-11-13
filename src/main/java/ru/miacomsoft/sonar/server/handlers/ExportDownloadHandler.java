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
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        String mime = "application/octet-stream";
        if ("txt".equals(ext))  mime = "text/plain";
        if ("md".equals(ext))   mime = "text/markdown";
        if ("html".equals(ext)) mime = "text/html";
        if ("zip".equals(ext))  mime = "application/zip";

        ex.getResponseHeaders().set("Content-Type", mime + "; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        ex.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }

        // НЕ удаляем сразу — даём возможность перекачать файл.
        // Реальная очистка произойдёт при остановке JVM.
        try {
            file.toFile().deleteOnExit();
            System.out.println("[ExportDownload] Отдан файл: " + file.toAbsolutePath()
                    + " (" + bytes.length + " байт, удалится при остановке JVM)");
        } catch (Exception e) {
            System.err.println("[ExportDownload] Не удалось запланировать удаление: " + e.getMessage());
        }
    }
}