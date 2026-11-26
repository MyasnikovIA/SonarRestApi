package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.MultiSelectDropdown;
import ru.miacomsoft.sonar_gui.common.Reloadable;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.TagsService;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.ReportExportDialog;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ReportFilterPanel extends JPanel implements Reloadable {

    private static final String NO_TAGS_KEY = "__NO_TAGS__";

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

        // ================= TOP BAR =================
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(GuiStyle.BG2);
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // Row 1: Header + Export
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

        // Row 2: Filters
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

        // ================= CENTER (Подсказка) =================
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

        // Инициализация списка тегов (без запроса issues)
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
        // При переключении вкладки ничего не делаем — фильтры остаются как есть.
        // При необходимости можно обновить список тегов под текущий проект.
        if (ctx.hasProject()) {
            initFiltersAsync();
        }
    }

    private void openExportMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(GuiStyle.BG2);
        menu.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER, 1));

        for (ReportExportType type : ReportExportType.values()) {
            // Можно добавить фильтр по типам, если нужно исключить какие-то форматы
            menu.add(menuItem(type.title, () -> startExport(type)));
        }
        menu.show(exportBtn, 0, exportBtn.getHeight());
    }

    private void startExport(ReportExportType type) {
        if (!ctx.hasProject()) {
            UiHelpers.showToast(this, "Выберите проект в верхней панели", true);
            return;
        }

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
        filter.expectedTotal  = -1; // Не важно для этого режима

        System.out.println("[ReportFilterPanel] Экспорт " + type
                + " · режим=ALL (только фильтры)");

        new ReportExportDialog(ctx, type, null, filter).setVisible(true);
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