package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.services.IssueService;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

public class ReportLoader extends SwingWorker<List<IssueService.IssueDto>, Object[]> {

    private final IssueService issueService;
    private final GuiContext ctx;
    private final String customSev;
    private final String customQual;
    private final String customStatuses;

    private final Consumer<List<IssueService.IssueDto>> onSuccess;
    private final Consumer<Throwable> onError;
    private final Consumer<Object[]> onProgress; // [current, total, message]

    public ReportLoader(GuiContext ctx, String customSev, String customQual, String customStatuses,
                        Consumer<List<IssueService.IssueDto>> onSuccess,
                        Consumer<Throwable> onError,
                        Consumer<Object[]> onProgress) {
        this.ctx = ctx;
        this.issueService = new IssueService(ctx);
        this.customSev = customSev;
        this.customQual = customQual;
        this.customStatuses = customStatuses;
        this.onSuccess = onSuccess;
        this.onError = onError;
        this.onProgress = onProgress;
    }

    @Override
    protected List<IssueService.IssueDto> doInBackground() throws Exception {
        return issueService.fetchAllIssues(
                ctx.getProject(), customSev, customQual,
                ctx.isHideProcessed(), customStatuses,
                (current, total, message) -> publish(new Object[]{current, total, message}));
    }

    @Override
    protected void process(List<Object[]> chunks) {
        if (onProgress != null && !chunks.isEmpty()) {
            // Берем последний чанк для обновления UI
            onProgress.accept(chunks.get(chunks.size() - 1));
        }
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