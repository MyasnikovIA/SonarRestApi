package ru.miacomsoft.sonar_gui.tabs.report.dialogs;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.tabs.report.ReportExportService;
import ru.miacomsoft.sonar_gui.tabs.report.ReportExportType;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;

/**
 * Диалог экспорта с SSE-прогрессом.
 *
 * Шаги:
 *   1. Показываем прогресс-бар;
 *   2. По complete — JFileChooser (default = downloadName);
 *   3. Скачиваем файл и сохраняем.
 */
public class ReportExportDialog extends JDialog {

    private final GuiContext ctx;
    private final ReportExportType type;
    private final String baseQuery;
    private final List<String> keysCsv;

    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLbl = UiHelpers.dimLabel("Инициализация…");
    private final JLabel counterLbl = UiHelpers.dimLabel("0 / 0");
    private final JLabel elapsedLbl = UiHelpers.dimLabel("0.0 с");
    private final JButton cancelBtn;

    private volatile boolean cancelled = false;

    public ReportExportDialog(GuiContext ctx, ReportExportType type,
                              String baseQuery, List<String> keysCsv) {
        super((Frame) null, "Экспорт: " + type.title, true);
        this.ctx = ctx;
        this.type = type;
        this.baseQuery = baseQuery;
        this.keysCsv = keysCsv;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(560, 220);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        setContentPane(root);

        JPanel info = new JPanel(new BorderLayout(0, 6));
        info.setOpaque(false);
        info.add(statusLbl, BorderLayout.NORTH);

        progressBar.setBackground(GuiStyle.BG3);
        progressBar.setForeground(GuiStyle.ACCENT);
        progressBar.setStringPainted(false);
        info.add(progressBar, BorderLayout.CENTER);

        JPanel meta = new JPanel(new BorderLayout());
        meta.setOpaque(false);
        meta.add(counterLbl, BorderLayout.WEST);
        meta.add(elapsedLbl, BorderLayout.EAST);
        info.add(meta, BorderLayout.SOUTH);

        root.add(info, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        cancelBtn = UiHelpers.dangerButton("Остановить");
        cancelBtn.addActionListener(e -> {
            cancelled = true;
            cancelBtn.setEnabled(false);
            cancelBtn.setText("Остановка…");
        });
        footer.add(cancelBtn);
        root.add(footer, BorderLayout.SOUTH);

        // Стартуем SSE в фоне.
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                ReportExportService svc = new ReportExportService(ctx.api());
                try {
                    svc.streamExport(type, baseQuery, keysCsv, new ReportExportService.ProgressListener() {
                        @Override public void onProgress(JSONObject data) {
                            if (cancelled) return;
                            SwingUtilities.invokeLater(() -> updateProgress(data));
                        }
                        @Override public void onComplete(JSONObject data) {
                            if (cancelled) return;
                            SwingUtilities.invokeLater(() -> onSseComplete(data));
                        }
                        @Override public void onError(Throwable t) {
                            if (cancelled) return;
                            SwingUtilities.invokeLater(() -> showError(t));
                        }
                    });
                } catch (Exception ex) {
                    if (!cancelled) {
                        SwingUtilities.invokeLater(() -> showError(ex));
                    }
                }
                return null;
            }
        }.execute();
    }

    private void updateProgress(JSONObject d) {
        String msg = d.optString("message", "");
        if (!msg.isEmpty()) statusLbl.setText(msg);
        int cur = d.optInt("current", 0);
        int total = d.optInt("total", 0);
        if (total > 0) {
            progressBar.setValue((int) Math.min(100, (cur * 100L) / total));
            counterLbl.setText(cur + " / " + total);
        } else {
            progressBar.setIndeterminate(true);
            counterLbl.setText(String.valueOf(cur));
        }
        if (d.has("elapsedMs")) {
            long ms = d.optLong("elapsedMs", 0);
            elapsedLbl.setText(String.format("%.1f с", ms / 1000.0));
        }
        if (d.has("etaMs")) {
            long eta = d.optLong("etaMs", -1);
            if (eta > 0) statusLbl.setText(msg + " · осталось " + (eta / 1000) + " с");
        }
    }

    private void onSseComplete(JSONObject d) {
        progressBar.setIndeterminate(false);
        progressBar.setValue(100);
        String serverFile = d.optString("file", "");
        String downloadName = d.optString("downloadName", "report." + type.ext);
        statusLbl.setText("Готово. Выбор места сохранения…");

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Куда сохранить отчёт?");
        chooser.setSelectedFile(new java.io.File(downloadName));
        int r = chooser.showSaveDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) {
            dispose();
            return;
        }
        Path target = chooser.getSelectedFile().toPath();

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                ReportExportService svc = new ReportExportService(ctx.api());
                svc.downloadFile(serverFile, downloadName, target);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UiHelpers.showToast(ReportExportDialog.this,
                            "Сохранено: " + target.toAbsolutePath(), false);
                    dispose();
                } catch (Exception e) {
                    UiHelpers.showError(ReportExportDialog.this,
                            "Ошибка скачивания: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void showError(Throwable t) {
        progressBar.setIndeterminate(false);
        statusLbl.setText("Ошибка: " + t.getMessage());
        statusLbl.setForeground(GuiStyle.ERR);
        UiHelpers.showError(this, "Ошибка экспорта: " + t.getMessage());
    }
}