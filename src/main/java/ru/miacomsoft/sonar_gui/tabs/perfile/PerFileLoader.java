package ru.miacomsoft.sonar_gui.tabs.perfile;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.services.IssueService;

import javax.swing.*;
import java.util.function.Consumer;

/** SwingWorker для постраничной загрузки /api/perfile. */
public class PerFileLoader extends SwingWorker<IssueService.PerFilePage, Void> {

    private final IssueService issueService;
    private final String query;
    private final int page;
    private final int pageSize;
    private final Consumer<IssueService.PerFilePage> onSuccess;
    private final Consumer<Throwable> onError;

    public PerFileLoader(GuiContext ctx, int page, int pageSize,
                         Consumer<IssueService.PerFilePage> onSuccess,
                         Consumer<Throwable> onError) {
        this.issueService = new IssueService(ctx.api());
        this.query = ctx.commonQuery();
        this.page = page;
        this.pageSize = pageSize;
        this.onSuccess = onSuccess;
        this.onError = onError;
    }

    @Override
    protected IssueService.PerFilePage doInBackground() throws Exception {
        return issueService.fetchPerFilePage(query, page, pageSize);
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