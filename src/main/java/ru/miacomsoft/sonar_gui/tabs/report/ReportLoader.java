package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.services.IssueService;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Загружает ВСЕ issues проекта одним запросом (ps=10000),
 * как это делает web/tabs/report/index.js.
 */
public class ReportLoader extends SwingWorker<List<IssueService.IssueDto>, Void> {

    private final IssueService issueService;
    private final String query;
    private final Consumer<List<IssueService.IssueDto>> onSuccess;
    private final Consumer<Throwable> onError;

    public ReportLoader(GuiContext ctx,
                        Consumer<List<IssueService.IssueDto>> onSuccess,
                        Consumer<Throwable> onError) {
        this.issueService = new IssueService(ctx.api());
        this.query = ctx.commonQuery() + "&p=1&ps=10000";
        this.onSuccess = onSuccess;
        this.onError = onError;
    }

    @Override
    protected List<IssueService.IssueDto> doInBackground() throws Exception {
        return issueService.fetchAllIssues(query);
    }

    @Override
    protected void done() {
        try {
            if (onSuccess != null) onSuccess.accept(get());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (onError != null) onError.accept(cause);
        }
    }
}