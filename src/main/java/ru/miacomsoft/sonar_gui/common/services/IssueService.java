package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Обёртка над GET /api/perfile и /api/issues.
 *
 * Используется вкладками Per-File и Report для получения списка issue.
 * Внутренний DTO {@link IssueDto} изолирует GUI от "сырого" JSON
 * сервера и упрощает работу таблиц.
 */
public class IssueService {

    public record IssueDto(
            String key,
            String rule,
            String ruleName,
            String severity,
            String status,
            String component,
            int    line,
            String message,
            String type,
            List<String> tags
    ) {
        public boolean isProcessed() {
            return status != null
                    && !status.equalsIgnoreCase("OPEN")
                    && !status.equalsIgnoreCase("REOPENED");
        }
    }

    public record PerFilePage(int total, List<FileGroup> files) {}

    public record FileGroup(String file, List<IssueDto> issues) {}

    private final SonarApiClient api;

    public IssueService(SonarApiClient api) {
        this.api = api;
    }

    /** GET /api/perfile — постранично, группировка по файлам. */
    public PerFilePage fetchPerFilePage(String query, int page, int pageSize) throws Exception {
        String url = "/api/perfile?" + query
                + "&p=" + page
                + "&ps=" + pageSize;
        JSONObject root = api.getJson(url);
        int total = root.optInt("total", 0);

        List<FileGroup> groups = new ArrayList<>();
        JSONArray filesArr = root.optJSONArray("files");
        if (filesArr != null) {
            for (int i = 0; i < filesArr.length(); i++) {
                JSONObject f = filesArr.getJSONObject(i);
                String file = f.optString("file", "(unknown)");
                List<IssueDto> issues = new ArrayList<>();
                JSONArray iss = f.optJSONArray("issues");
                if (iss != null) {
                    for (int j = 0; j < iss.length(); j++) {
                        issues.add(parseIssue(iss.getJSONObject(j)));
                    }
                }
                groups.add(new FileGroup(file, issues));
            }
        }
        return new PerFilePage(total, groups);
    }

    /** GET /api/issues — плоский список, обычно ps >= 10000 на одну страницу. */
    public List<IssueDto> fetchAllIssues(String query) throws Exception {
        String url = "/api/issues?" + query;
        JSONObject root = api.getJson(url);
        JSONArray arr = root.optJSONArray("issues");
        List<IssueDto> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                out.add(parseIssue(arr.getJSONObject(i)));
            }
        }
        return out;
    }

    // ============ INTERNAL ============

    private static IssueDto parseIssue(JSONObject o) {
        List<String> tags = new ArrayList<>();
        JSONArray tagsArr = o.optJSONArray("tags");
        if (tagsArr != null) {
            for (int i = 0; i < tagsArr.length(); i++) {
                tags.add(tagsArr.optString(i, ""));
            }
        }
        String sev = o.optString("impactSeverity", "");
        if (sev.isBlank()) sev = o.optString("severity", "");

        return new IssueDto(
                o.optString("key", ""),
                o.optString("rule", ""),
                o.optString("ruleName", ""),
                sev,
                o.optString("status", ""),
                o.optString("component", "(unknown)"),
                o.optInt("line", 0),
                o.optString("message", ""),
                o.optString("type", ""),
                tags
        );
    }
}