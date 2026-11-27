package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.backend.jira.JiraClient;
import ru.miacomsoft.sonar_gui.backend.jira.JiraConfig;
import ru.miacomsoft.sonar_gui.backend.jira.SessionLoader;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.MultiSelectDropdown;
import ru.miacomsoft.sonar_gui.common.Reloadable;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.TagsService;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.JiraHeaderDialog;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.MultiProjectSelectDialog;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.ReportExportDialog;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.prefs.Preferences;

public class ReportFilterPanel extends JPanel implements Reloadable {

    private static final String NO_TAGS_KEY = "__NO_TAGS__";
    private static final Preferences PREFS = Preferences.userNodeForPackage(ReportFilterPanel.class);
    private static final String LAST_DIR_KEY = "last_report12_dir";

    private final GuiContext ctx;

    private final MultiSelectDropdown statusDropdown;
    private final MultiSelectDropdown sevDropdown;
    private final MultiSelectDropdown qualDropdown;
    private final MultiSelectDropdown tagsDropdown;
    private final MultiSelectDropdown excludedTagsDropdown;
    private final MultiSelectDropdown rulesDropdown;
    private final JTextField textField;
    private final JButton exportBtn;
    private final JLabel headerLabel;

    public ReportFilterPanel(GuiContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout());
        setBackground(GuiStyle.BG);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(GuiStyle.BG2);
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.setOpaque(false);

        headerLabel = new JLabel("Массовая выгрузка отчетов");
        headerLabel.setForeground(GuiStyle.ACCENT);
        headerLabel.setFont(GuiStyle.FONT_TITLE);
        row1.add(headerLabel, BorderLayout.WEST);

        JPanel row1Right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row1Right.setOpaque(false);
        exportBtn = UiHelpers.primaryButton("Запустить отчет");
        exportBtn.addActionListener(e -> openExportMenu());
        row1Right.add(exportBtn);
        row1.add(row1Right, BorderLayout.EAST);
        top.add(row1);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.setOpaque(false);

        statusDropdown = new MultiSelectDropdown("Статус (Status)", this::noop);
        statusDropdown.setItems(Arrays.asList(
                "OPEN", "CONFIRMED", "REOPENED", "ACCEPTED", "FALSE_POSITIVE",
                "WONTFIX", "FIXED", "CLOSED", "RESOLVED", "ALL"
        ));
        statusDropdown.setSelected("ALL");
        statusDropdown.setPreferredSize(new Dimension(180, 26));
        row2.add(statusDropdown);

        sevDropdown = new MultiSelectDropdown("Критичность (SEV)", this::noop);
        sevDropdown.setItems(Arrays.asList("BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO", "ALL"));
        sevDropdown.setSelected(ctx.getSeverity() != null ? ctx.getSeverity() : "ALL");
        sevDropdown.setPreferredSize(new Dimension(180, 26));
        row2.add(sevDropdown);

        qualDropdown = new MultiSelectDropdown("Качество (QUAL)", this::noop);
        qualDropdown.setItems(Arrays.asList("SECURITY", "RELIABILITY", "MAINTAINABILITY", "SECURITY_HOTSPOT", "ALL"));
        qualDropdown.setSelected(ctx.getQuality() != null ? ctx.getQuality() : "ALL");
        qualDropdown.setPreferredSize(new Dimension(180, 26));
        row2.add(qualDropdown);

        tagsDropdown = new MultiSelectDropdown("🏷️ Теги (Tags)", this::noop);
        tagsDropdown.setPreferredSize(new Dimension(160, 26));
        row2.add(tagsDropdown);

        excludedTagsDropdown = new MultiSelectDropdown("🚫 Исключить (Exclude)", this::noop);
        excludedTagsDropdown.setPreferredSize(new Dimension(200, 26));
        row2.add(excludedTagsDropdown);

        rulesDropdown = new MultiSelectDropdown("📏 Правила (Rules)", this::noop);
        rulesDropdown.setPreferredSize(new Dimension(180, 26));
        row2.add(rulesDropdown);

        textField = UiHelpers.darkField("");
        textField.setPreferredSize(new Dimension(200, 26));
        textField.setToolTipText("Фильтр по ключу/файлу/сообщению");
        row2.add(textField);

        top.add(row2);
        add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(GuiStyle.BG);
        JLabel hint = new JLabel("<html><div style='text-align:center;color:#8b949e;font-size:14px;'>" +
                "Выберите фильтры и нажмите <b>«Запустить отчет»</b>.<br><br>" +
                "Отчет будет сформирован по данным из SonarQube<br>" +
                "без предварительной загрузки списка ошибок на экран." +
                "</div></html>");
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        center.add(hint);
        add(center, BorderLayout.CENTER);

        initFiltersAsync();
    }

    private void noop() {}

    private void initFiltersAsync() {
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                return new TagsService(ctx).fetchTags(ctx.getProject());
            }
            @Override protected void done() {
                try {
                    List<String> tags = get();
                    List<String> tagItems = new ArrayList<>(tags);
                    tagItems.add(0, NO_TAGS_KEY);
                    tagsDropdown.setItems(tagItems);
                    excludedTagsDropdown.setItems(tagItems);
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    @Override
    public void reload() {
        if (ctx.hasProject()) {
            initFiltersAsync();
        }
    }

    private void openExportMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(GuiStyle.BG2);
        menu.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER, 1));

        for (ReportExportType type : ReportExportType.values()) {
            menu.add(menuItem(type.title, () -> startExport(type)));
        }
        menu.show(exportBtn, 0, exportBtn.getHeight());
    }

    private void startExport(ReportExportType type) {
        if (type == ReportExportType.MULTI_PROJECT_SUMMARY || type == ReportExportType.QUAL_TEAM_PROJECT_HTML) {
            new MultiProjectSelectDialog(ctx, result -> {
                if (result.projects() == null || result.projects().isEmpty()) {
                    UiHelpers.showToast(this, "Не выбрано ни одного проекта", true);
                    return;
                }

                if (result.createJira()) {
                    checkJiraAndProceed(result.createJira(), () -> proceedWithExport(type, result));
                } else {
                    proceedWithExport(type, result);
                }
            }).setVisible(true);
            return;
        }

        if (!ctx.hasProject()) {
            UiHelpers.showToast(this, "Выберите проект в верхней панели", true);
            return;
        }

        ReportExportService.BaseFilter filter = buildBaseFilter();
        new ReportExportDialog(ctx, type, null, filter).setVisible(true);
    }

    private void proceedWithExport(ReportExportType type, MultiProjectSelectDialog.Result result) {
        ReportExportService.BaseFilter filter = buildBaseFilter();
        filter.selectedProjects = result.projects();
        filter.createJiraTasks = result.createJira();

        if (type == ReportExportType.MULTI_PROJECT_SUMMARY) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Куда сохранить сводный отчет?");
            chooser.setFileFilter(new FileNameExtensionFilter("HTML Report (*.html)", "html"));

            String defaultName = "multi_project_summary_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".html";

            String lastDir = PREFS.get(LAST_DIR_KEY, System.getProperty("user.home"));
            File lastDirFile = new File(lastDir);
            if (lastDirFile.exists() && lastDirFile.isDirectory()) {
                chooser.setCurrentDirectory(lastDirFile);
            }

            chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), defaultName));

            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }

            File selectedFile = chooser.getSelectedFile();
            if (selectedFile.getParentFile() != null) {
                PREFS.put(LAST_DIR_KEY, selectedFile.getParentFile().getAbsolutePath());
            }

            String outputPath = selectedFile.getAbsolutePath();
            if (!outputPath.toLowerCase().endsWith(".html")) {
                outputPath += ".html";
            }
            filter.outputFilePath = outputPath;

            new ReportExportDialog(ctx, type, null, filter).setVisible(true);

        } else if (type == ReportExportType.QUAL_TEAM_PROJECT_HTML) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Выберите корневой каталог для глубокого отчета");

            String lastDir = PREFS.get(LAST_DIR_KEY, System.getProperty("user.home"));
            File lastDirFile = new File(lastDir);
            if (lastDirFile.exists() && lastDirFile.isDirectory()) {
                chooser.setCurrentDirectory(lastDirFile);
            }

            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File selectedDir = chooser.getSelectedFile();
                PREFS.put(LAST_DIR_KEY, selectedDir.getAbsolutePath());
                new ReportExportDialog(ctx, type, null, filter, selectedDir.toPath()).setVisible(true);
            }
        }
    }

    private void checkJiraAndProceed(boolean createJira, Runnable onSuccess) {
        if (!createJira) {
            onSuccess.run();
            return;
        }

        exportBtn.setEnabled(false);
        UiHelpers.showToast(this, "Проверка подключения к Jira...", false);

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                SessionLoader.SessionInfo session = SessionLoader.loadDefault();
                JiraClient client = new JiraClient(session.baseUrl(), session.cookieHeader());
                client.get("/rest/api/2/myself");
                return null;
            }

            @Override
            protected void done() {
                exportBtn.setEnabled(true);
                try {
                    get();
                    checkEpicAndProceed(onSuccess);
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        JiraHeaderDialog dialog = new JiraHeaderDialog(ctx);
                        dialog.setVisible(true);
                        if (dialog.isSavedAndValid()) {
                            checkEpicAndProceed(onSuccess);
                        } else {
                            UiHelpers.showToast(ReportFilterPanel.this, "Создание задач в Jira отменено из-за отсутствия авторизации", true);
                        }
                    });
                }
            }
        }.execute();
    }

    private void checkEpicAndProceed(Runnable onSuccess) {
        JiraConfig jcfg = JiraConfig.getInstance();
        boolean missingEpics = false;

        for (String q : new String[]{"SECURITY", "RELIABILITY", "MAINTAINABILITY", "SECURITY_HOTSPOT"}) {
            for (String s : new String[]{"BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"}) {
                if (jcfg.getEpic(q, s) == null || jcfg.getEpic(q, s).isBlank()) {
                    missingEpics = true;
                    break;
                }
            }
        }

        if (missingEpics && (jcfg.defaultEpic() == null || jcfg.defaultEpic().isBlank())) {
            String epic = JOptionPane.showInputDialog(this,
                    "В настройках Jira не заполнены эпики для некоторых уровней.\n" +
                            "Пожалуйста, укажите эпик по умолчанию (например, MEDDEV-12345):",
                    "Эпик по умолчанию", JOptionPane.QUESTION_MESSAGE);

            if (epic == null || epic.isBlank()) {
                UiHelpers.showToast(this, "Эпик по умолчанию не указан. Действие отменено.", true);
                return;
            }

            try {
                Path file = ctx.sessionFile();
                Properties p = new Properties();
                if (Files.exists(file)) {
                    try (var in = Files.newInputStream(file)) { p.load(in); }
                }
                p.setProperty("jira.epic.default", epic.trim());
                try (var out = Files.newOutputStream(file)) {
                    p.store(out, "app.properties");
                }
                JiraConfig.getInstance().reload();
                UiHelpers.showToast(this, "Эпик по умолчанию сохранен: " + epic.trim(), false);
            } catch (Exception ex) {
                UiHelpers.showError(this, "Ошибка сохранения эпика: " + ex.getMessage());
                return;
            }
        }

        onSuccess.run();
    }

    private ReportExportService.BaseFilter buildBaseFilter() {
        ReportExportService.BaseFilter filter = new ReportExportService.BaseFilter();
        filter.project        = ctx.getProject();
        filter.severity       = sevDropdown.getSelectedCsv();
        filter.quality        = qualDropdown.getSelectedCsv();
        filter.hideProcessed  = ctx.isHideProcessed();
        filter.tags           = tagsDropdown.getSelected();
        filter.excludedTags   = excludedTagsDropdown.getSelected();
        filter.rules          = rulesDropdown.getSelected();
        filter.filterText     = textField.getText().trim();
        filter.statusFilter   = statusDropdown.getSelectedCsv();
        filter.expectedTotal  = -1;
        return filter;
    }

    private JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setBackground(GuiStyle.BG2);
        item.setForeground(GuiStyle.TEXT);
        item.setFont(GuiStyle.FONT_BASE);
        item.addActionListener(e -> action.run());
        return item;
    }
}