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
                    && !status.equalsIgnoreCase("CONFIRMED")
                    && !status.equalsIgnoreCase("REOPENED")
                    && !status.equalsIgnoreCase("ACCEPTED");
        }
    }

    public record PerFilePage(int total, List<FileGroup> files) {}
    public record FileGroup(String file, List<IssueDto> issues) {}

    private final GuiContext ctx;

    public IssueService(GuiContext ctx) {
        this.ctx = ctx;
    }

    public PerFilePage fetchPerFilePage(String project, String sev, String qual,
                                        boolean hideProcessed,
                                        int page, int pageSize) throws Exception {
        if (!ctx.isSessionReady()) {
            System.err.println("[IssueService] Ошибка: сессия не готова (Per-File).");
            throw new IllegalStateException("Сессия не готова");
        }

        System.out.println("[IssueService] Загрузка страницы Per-File (page=" + page + ", ps=" + pageSize + ")...");
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
        System.out.println("[IssueService] Per-File загружено. Всего issues: " + total + ", файлов на странице: " + groups.size());
        return new PerFilePage(total, groups);
    }

    public List<IssueDto> fetchAllIssues(String project, String sev, String qual,
                                         boolean hideProcessed, String statusesStr,
                                         FetchProgress progress) throws Exception {
        if (!ctx.isSessionReady()) {
            System.err.println("[IssueService] Ошибка: сессия не готова (Report).");
            throw new IllegalStateException("Сессия не готова");
        }

        System.out.println("[IssueService] Запуск полной загрузки issues для Report...");
        SonarIssuesApi api = ctx.state().newApi(project);

        String actualStatuses = statusesStr;
        if (actualStatuses == null || actualStatuses.isEmpty() || "ALL".equalsIgnoreCase(actualStatuses)) {
            actualStatuses = hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : SonarIssuesApi.ALL_STATUSES;
        }

        SonarIssuesApi.FetchProgressListener listener = null;
        if (progress != null) {
            listener = (current, total, message) -> progress.onProgress(current, total, message);
        }

        try {
            List<Issue> all = api.fetchAllByStatuses(actualStatuses, sev, qual, listener);
            List<IssueDto> out = new ArrayList<>(all.size());
            for (Issue it : all) out.add(toDto(it));
            System.out.println("[IssueService] Report загрузка завершена. Извлечено: " + out.size());
            return out;
        } catch (Exception e) {
            System.err.println("[IssueService] Ошибка при загрузке issues для Report: " + e.getMessage());
            throw e;
        }
    }

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