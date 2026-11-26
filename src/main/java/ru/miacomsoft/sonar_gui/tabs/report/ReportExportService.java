package ru.miacomsoft.sonar_gui.tabs.report;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;
import ru.miacomsoft.sonar_gui.client.SseListener;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Обёртка над /api/export-stream + /api/export-download.
 * Блокирующий вызов (выполняется из SwingWorker).
 */
public class ReportExportService {

    public interface ProgressListener {
        void onProgress(JSONObject data);
        void onComplete(JSONObject data);
        void onError(Throwable t);
    }

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);

    private final SonarApiClient api;
    private final HttpClient downloadHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public ReportExportService(SonarApiClient api) {
        this.api = api;
    }

    /**
     * Запускает SSE-экспорт.
     * @param keysCsv null → сервер соберёт по фильтрам (GET);
     *                не null → POST с {"keys":[...]} (только выбранные).
     */
    public void streamExport(ReportExportType type, String baseQuery,
                             List<String> keysCsv, ProgressListener listener) throws Exception {
        StringBuilder url = new StringBuilder("/api/export-stream?");
        url.append(baseQuery);
        url.append("&format=").append(SonarApiClient.enc(type.code));

        SseListener sse = new SseListener() {
            @Override public void onEvent(String event, JSONObject data) {
                if ("progress".equals(event)) {
                    if (listener != null) listener.onProgress(data);
                } else if ("complete".equals(event)) {
                    if (listener != null) listener.onComplete(data);
                } else if ("error".equals(event)) {
                    String msg = data.optString("message", "Неизвестная ошибка сервера");
                    if (listener != null) listener.onError(new RuntimeException(msg));
                }
            }
            @Override public void onComplete() { /* уже обработано в onEvent */ }
            @Override public void onError(Throwable t) {
                if (listener != null) listener.onError(t);
            }
        };

        if (keysCsv == null || keysCsv.isEmpty()) {
            api.streamSse(url.toString(), sse);
        } else {
            JSONObject body = new JSONObject();
            body.put("keys", keysCsv);
            api.streamSsePost(url.toString(), body.toString(), sse);
        }
    }

    /**
     * Скачивает файл, отданный сервером (после события complete).
     */
    public void downloadFile(String serverFilePath, String downloadName, Path targetFile)
            throws Exception {
        StringBuilder url = new StringBuilder(api.getBaseUrl())
                .append("/api/export-download?file=").append(SonarApiClient.enc(serverFilePath));
        if (downloadName != null && !downloadName.isBlank()) {
            url.append("&name=").append(SonarApiClient.enc(downloadName));
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "SonarGui/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> resp = downloadHttp.send(req,
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            try (InputStream es = resp.body()) {
                String err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("HTTP " + resp.statusCode() + ": " + err);
            }
        }
        try (InputStream in = resp.body()) {
            Files.copy(in, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Построение query-строки для экспорта из текущих фильтров. */
    public static String buildBaseQuery(String project, String severity, String quality,
                                        String statusFilter, Set<String> tags,
                                        Set<String> excludedTags, Set<String> rules,
                                        String filterText, int expectedTotal) {
        StringBuilder sb = new StringBuilder();
        if (project != null && !project.isBlank()) {
            sb.append("project=").append(SonarApiClient.enc(project));
        }
        if (sb.length() > 0) sb.append('&');
        sb.append("sev=").append(SonarApiClient.enc(severity == null ? "ALL" : severity));
        sb.append("&qual=").append(SonarApiClient.enc(quality == null ? "ALL" : quality));
        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equals(statusFilter)) {
            sb.append("&statusFilter=").append(SonarApiClient.enc(statusFilter));
        }
        if (tags != null && !tags.isEmpty()) {
            sb.append("&tags=").append(SonarApiClient.enc(String.join(",", tags)));
        }
        if (excludedTags != null && !excludedTags.isEmpty()) {
            sb.append("&excludedTags=").append(SonarApiClient.enc(String.join(",", excludedTags)));
        }
        if (rules != null && !rules.isEmpty()) {
            sb.append("&selectedRules=").append(SonarApiClient.enc(String.join(",", rules)));
        }
        if (filterText != null && !filterText.isBlank()) {
            sb.append("&filterText=").append(SonarApiClient.enc(filterText));
        }
        if (expectedTotal > 0) {
            sb.append("&expectedTotal=").append(expectedTotal);
        }
        return sb.toString();
    }
}