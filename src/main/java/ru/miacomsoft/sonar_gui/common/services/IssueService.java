package ru.miacomsoft.sonar_gui.common.services;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;
import ru.miacomsoft.sonar_gui.common.GuiContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class IssueService {

    public interface FetchProgress {
        void onProgress(int current, int total, String message);
    }

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

    private final GuiContext ctx;

    public IssueService(GuiContext ctx) {
        this.ctx = ctx;
    }

    // ============ PER-FILE ============

    public PerFilePage fetchPerFilePage(String project, String sev, String qual,
                                        boolean hideProcessed,
                                        int page, int pageSize) throws Exception {
        if (!ctx.isSessionReady()) throw new IllegalStateException("Сессия не готова");

        SonarIssuesApi api = ctx.state().newApi(project);
        String statuses = hideProcessed
                ? SonarIssuesApi.ACTIVE_STATUSES
                : SonarIssuesApi.ALL_STATUSES;

        JSONObject raw = api.fetchPageRaw(statuses, sev, qual, null, page, pageSize);
        int total = raw.optInt("total", 0);

        JSONArray arr = raw.optJSONArray("issues");
        Map<String, List<IssueDto>> byFile = new LinkedHashMap<>();

        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Issue issue = Issue.from(arr.getJSONObject(i), project);
                IssueDto dto = toDto(issue);
                byFile.computeIfAbsent(dto.component(), k -> new ArrayList<>()).add(dto);
            }
        }

        List<FileGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<IssueDto>> e : byFile.entrySet()) {
            groups.add(new FileGroup(e.getKey(), e.getValue()));
        }
        return new PerFilePage(total, groups);
    }

    // ============ REPORT (все issues с чанкованием и прогрессом) ============

    public List<IssueDto> fetchAllIssues(String project, String sev, String qual,
                                         boolean hideProcessed, String statusesStr,
                                         FetchProgress progress) throws Exception {
        if (!ctx.isSessionReady()) throw new IllegalStateException("Сессия не готова");

        SonarIssuesApi api = ctx.state().newApi(project);

        // Если выбрано ALL или пусто — используем ACTIVE или ALL_STATUSES
        String actualStatuses = statusesStr;
        if (actualStatuses == null || actualStatuses.isEmpty() || "ALL".equalsIgnoreCase(actualStatuses)) {
            actualStatuses = hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : SonarIssuesApi.ALL_STATUSES;
        }

        List<Issue> all = new ArrayList<>();
        int page = 1;
        int total = -1;
        // SonarQube режет ps до 500 (максимум), но мы запрашиваем логические пачки по 3000
        // через цикл страниц.
        int pageSize = 500;

        while (total < 0 || all.size() < total) {
            JSONObject raw = api.fetchPageRaw(actualStatuses, sev, qual, null, page, pageSize);
            total = raw.optInt("total", 0);
            JSONArray arr = raw.optJSONArray("issues");
            if (arr == null || arr.length() == 0) break;

            for (int i = 0; i < arr.length(); i++) {
                all.add(Issue.from(arr.getJSONObject(i), project));
            }

            if (progress != null) {
                progress.onProgress(all.size(), total,
                        String.format("Загрузка: %d из %d", all.size(), total));
            }

            page++;
            if (page > 1000) break; // safety
        }

        List<IssueDto> out = new ArrayList<>(all.size());
        for (Issue it : all) out.add(toDto(it));

        if (progress != null && total > 0) {
            progress.onProgress(total, total, "Загрузка завершена");
        }
        return out;
    }

    // ============ INTERNAL ============

    private IssueDto toDto(Issue issue) {
        String sev = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");

        return new IssueDto(
                issue.key != null ? issue.key : "",
                issue.rule != null ? issue.rule : "",
                issue.ruleName != null ? issue.ruleName : "",
                sev,
                issue.status != null ? issue.status : "",
                issue.component != null ? issue.component : "(unknown)",
                issue.line,
                issue.message != null ? issue.message : "",
                issue.type != null ? issue.type : "",
                issue.tags != null ? issue.tags : new ArrayList<>()
        );
    }
}