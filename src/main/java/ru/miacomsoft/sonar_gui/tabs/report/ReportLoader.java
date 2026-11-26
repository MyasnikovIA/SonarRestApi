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
    private final Consumer<Object[]> onProgress;

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
        System.out.println("[ReportLoader] Начало фоновой загрузки данных для отчета...");
        try {
            return issueService.fetchAllIssues(
                    ctx.getProject(), customSev, customQual,
                    ctx.isHideProcessed(), customStatuses,
                    (current, total, message) -> {
                        if (isCancelled()) {
                            // Если загрузчик отменён, прерываем выполнение (по возможности)
                            throw new RuntimeException("Отменено пользователем");
                        }
                        publish(new Object[]{current, total, message});
                    });
        } catch (Exception e) {
            if (isCancelled() || "Отменено пользователем".equals(e.getMessage())) {
                System.out.println("[ReportLoader] Загрузка отменена.");
                return null;
            }
            System.err.println("[ReportLoader] Ошибка в doInBackground: " + e.getMessage());
            throw e;
        }
    }

    @Override
    protected void process(List<Object[]> chunks) {
        if (isCancelled()) return;
        if (onProgress != null && !chunks.isEmpty()) {
            onProgress.accept(chunks.get(chunks.size() - 1));
        }
    }

    @Override
    protected void done() {
        if (isCancelled()) {
            System.out.println("[ReportLoader] Загрузка была отменена, обновление UI пропущено.");
            return;
        }
        try {
            List<IssueService.IssueDto> result = get();
            if (result != null && onSuccess != null) {
                onSuccess.accept(result);
            }
            System.out.println("[ReportLoader] Фоновая загрузка успешно завершена.");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("[ReportLoader] Ошибка при завершении загрузки: " + cause.getMessage());
            cause.printStackTrace();
            if (onError != null) onError.accept(cause);
        }
    }
}