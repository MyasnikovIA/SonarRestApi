package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        String downloadName = param(q, "name", null);

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

        // Итоговое имя для Content-Disposition:
        //  - если клиент передал ?name= — используем его (санитайзим);
        //  - иначе — имя temp-файла.
        String useName = (downloadName != null && !downloadName.isBlank())
                ? sanitizeDownloadName(downloadName)
                : fileName;

        // RFC 5987 для не-ASCII (и заодно обычный filename= для совместимости)
        String asciiFallback = toAsciiFallback(useName);
        String encodedName = URLEncoder.encode(useName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        String contentDisposition = "attachment; filename=\"" + asciiFallback + "\""
                + "; filename*=UTF-8''" + encodedName;

        ex.getResponseHeaders().set("Content-Type", mime + "; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", contentDisposition);
        ex.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }

        try {
            file.toFile().deleteOnExit();
            System.out.println("[ExportDownload] Отдан файл: " + file.toAbsolutePath()
                    + " как \"" + useName + "\" (" + bytes.length + " байт)");
        } catch (Exception e) {
            System.err.println("[ExportDownload] Не удалось запланировать удаление: " + e.getMessage());
        }
    }

    /** Убирает из имени всё, что может сломать заголовок или пробить path traversal. */
    private static String sanitizeDownloadName(String raw) {
        if (raw == null) return "download";
        String s = raw
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\x00-\\x1F]", "_")
                .trim();
        // защита от пустого и от «.»
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) return "download";
        // не даём разрастись
        if (s.length() > 200) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 ? s.substring(dot) : "";
            s = s.substring(0, Math.min(200 - ext.length(), s.length())) + ext;
        }
        return s;
    }

    /** ASCII-версия имени для filename= (RFC 6266 fallback). */
    private static String toAsciiFallback(String s) {
        if (s == null) return "download";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F && c != '"' && c != '\\') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "download" : sb.toString();
    }
}