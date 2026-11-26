package ru.miacomsoft.sonar_gui.tabs.report.dialogs;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.tabs.report.ReportExportService;
import ru.miacomsoft.sonar_gui.tabs.report.ReportExportType;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.prefs.Preferences;

public class ReportExportDialog extends JDialog {

    private static final Preferences PREFS = Preferences.userNodeForPackage(ReportExportDialog.class);
    private static final String LAST_DIR_KEY = "last_report_dir";

    private final GuiContext ctx;
    private final ReportExportType type;
    private final List<String> keysCsv;
    private final ReportExportService.BaseFilter filter;

    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLbl = UiHelpers.dimLabel("Инициализация…");
    private final JLabel counterLbl = UiHelpers.dimLabel("0 / 0");
    private final JLabel stageLbl = UiHelpers.dimLabel("—");
    private final JButton cancelBtn;

    private volatile ReportExportService service;
    private volatile boolean cancelled = false;
    private Path targetDirectory = null;
    private SwingWorker<Void, Void> worker;

    public ReportExportDialog(GuiContext ctx, ReportExportType type,
                              List<String> keysCsv, ReportExportService.BaseFilter filter) {
        super((Frame) null, "Экспорт: " + type.title, true);
        this.ctx = ctx;
        this.type = type;
        this.keysCsv = keysCsv;
        this.filter = filter;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(620, 240);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        setContentPane(root);

        JPanel info = new JPanel(new BorderLayout(0, 6));
        info.setOpaque(false);

        JPanel metaTop = new JPanel(new BorderLayout());
        metaTop.setOpaque(false);
        metaTop.add(statusLbl, BorderLayout.WEST);
        metaTop.add(stageLbl, BorderLayout.EAST);
        info.add(metaTop, BorderLayout.NORTH);

        progressBar.setBackground(GuiStyle.BG3);
        progressBar.setForeground(GuiStyle.ACCENT);
        progressBar.setStringPainted(false);
        info.add(progressBar, BorderLayout.CENTER);

        JPanel metaBottom = new JPanel(new BorderLayout());
        metaBottom.setOpaque(false);
        metaBottom.add(counterLbl, BorderLayout.WEST);
        metaBottom.add(UiHelpers.dimLabel("формат: " + type.title), BorderLayout.EAST);
        info.add(metaBottom, BorderLayout.SOUTH);

        root.add(info, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        cancelBtn = UiHelpers.dangerButton("Остановить");
        cancelBtn.addActionListener(e -> {
            cancelled = true;
            cancelBtn.setEnabled(false);
            cancelBtn.setText("Остановка…");
            if (service != null) service.setCancelled(true);
            if (worker != null) worker.cancel(true);
        });
        footer.add(cancelBtn);
        root.add(footer, BorderLayout.SOUTH);

        startExport();
    }

    private boolean isMultiFile() {
        return type == ReportExportType.HTML_FILES ||
                type == ReportExportType.HTML_FILES_TEAMS ||
                type == ReportExportType.TEAM_HTML ||
                type == ReportExportType.QUAL_TEAM_HTML ||
                type == ReportExportType.LLM_MD_ZIP ||
                type == ReportExportType.TEAM_MODULE_ZIP;
    }

    private void startExport() {
        if (isMultiFile()) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Выберите каталог для сохранения отчета");

            String lastDir = PREFS.get(LAST_DIR_KEY, System.getProperty("user.home"));
            File lastDirFile = new File(lastDir);
            if (lastDirFile.exists() && lastDirFile.isDirectory()) {
                chooser.setCurrentDirectory(lastDirFile);
            }

            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                dispose();
                return;
            }
            targetDirectory = chooser.getSelectedFile().toPath();
            PREFS.put(LAST_DIR_KEY, targetDirectory.toAbsolutePath().toString());

            statusLbl.setText("Каталог: " + targetDirectory.getFileName());
        }

        final Path finalTargetDir = targetDirectory;

        worker = new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                service = new ReportExportService(ctx, new ReportExportService.ProgressListener() {
                    @Override public void onProgress(int current, int total, String stage, String message) {
                        if (isCancelled()) return;
                        SwingUtilities.invokeLater(() -> updateProgress(current, total, stage, message));
                    }
                    @Override public void onDone(Path file, String suggestedName) {
                        if (isCancelled()) return;
                        SwingUtilities.invokeLater(() -> onExportComplete(file, suggestedName, finalTargetDir != null));
                    }
                    @Override public void onError(Throwable t) {
                        if (isCancelled()) return;
                        SwingUtilities.invokeLater(() -> onExportError(t));
                    }
                });
                service.export(type, keysCsv, filter, finalTargetDir);
                return null;
            }
        };
        worker.execute();
    }

    private void updateProgress(int current, int total, String stage, String message) {
        if (cancelled) return;
        if (message != null) statusLbl.setText(message);
        if (stage != null) stageLbl.setText("этап: " + stage);
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setValue((int) Math.min(100, (current * 100L) / total));
            counterLbl.setText(current + " / " + total);
        } else {
            progressBar.setIndeterminate(true);
            counterLbl.setText(String.valueOf(current));
        }
    }

    private void onExportComplete(Path resultPath, String suggestedName, boolean isDir) {
        if (cancelled) {
            dispose();
            return;
        }
        progressBar.setIndeterminate(false);
        progressBar.setValue(100);

        if (isDir) {
            statusLbl.setText("Готово. Отчет сохранен в каталог.");
            UiHelpers.showToast(this, "Сохранено в: " + resultPath.toAbsolutePath(), false);
            dispose();
            return;
        }

        statusLbl.setText("Готово. Выбор места сохранения…");

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Куда сохранить отчёт?");
        chooser.setSelectedFile(new File(suggestedName));
        int r = chooser.showSaveDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) {
            try { Files.deleteIfExists(resultPath); } catch (Exception ignored) {}
            dispose();
            return;
        }
        Path target = chooser.getSelectedFile().toPath();

        try {
            Files.copy(resultPath, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(resultPath);
            UiHelpers.showToast(this, "Сохранено: " + target.toAbsolutePath(), false);
            dispose();
        } catch (Exception e) {
            UiHelpers.showError(this, "Ошибка сохранения: " + e.getMessage());
        }
    }

    private void onExportError(Throwable t) {
        if (cancelled) { dispose(); return; }
        progressBar.setIndeterminate(false);
        statusLbl.setText("Ошибка: " + t.getMessage());
        statusLbl.setForeground(GuiStyle.ERR);
        UiHelpers.showError(this, "Ошибка экспорта: " + t.getMessage());
    }
}