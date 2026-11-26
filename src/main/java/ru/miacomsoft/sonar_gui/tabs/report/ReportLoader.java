package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.services.IssueService;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Загрузчик всех issues для вкладки Report — напрямую через IssueService.
 * Больше не делает HTTP к /api/issues.
 */
public class ReportLoader extends SwingWorker<List<IssueService.IssueDto>, Void> {

    private final IssueService issueService;
    private final GuiContext ctx;
    private final Consumer<List<IssueService.IssueDto>> onSuccess;
    private final Consumer<Throwable> onError;

    public ReportLoader(GuiContext ctx,
                        Consumer<List<IssueService.IssueDto>> onSuccess,
                        Consumer<Throwable> onError) {
        this.ctx = ctx;
        this.issueService = new IssueService(ctx);
        this.onSuccess = onSuccess;
        this.onError = onError;
    }

    @Override
    protected List<IssueService.IssueDto> doInBackground() throws Exception {
        return issueService.fetchAllIssues(
                ctx.getProject(), ctx.getSeverity(), ctx.getQuality(),
                ctx.isHideProcessed());
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