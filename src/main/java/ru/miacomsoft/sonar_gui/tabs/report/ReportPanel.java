package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.MultiSelectDropdown;
import ru.miacomsoft.sonar_gui.common.Reloadable;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.IssueService;
import ru.miacomsoft.sonar_gui.common.services.TagsService;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.ReportBulkDialog;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.ReportExportDialog;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.ReportIssueDialog;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.ReportPromptDialog;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.ReportTagsDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Вкладка «Отчет (Jira/MD/TXT)» — аналог web/tabs/report.
 *
 * Особенности:
 *   - загружает ВСЕ issues одним запросом (ps=10000);
 *   - фильтры: status (ALL/UNPROCESSED/PROCESSED), tags, excludedTags,
 *     rules, text;
 *   - карточки по файлам с чекбоксами;
 *   - контекстное меню с экспортом (Jira/MD/TXT/Confluence/HTML/LLM/Team).
 */
public class ReportPanel extends JPanel implements Reloadable {

    private static final String NO_TAGS_KEY = "__NO_TAGS__";
    private static final String NO_TAGS_LABEL = "(Без тегов)";

    private final GuiContext ctx;

    // top
    private final JCheckBox masterCheckbox;
    private final JComboBox<String> statusCombo;
    private final MultiSelectDropdown tagsDropdown;
    private final MultiSelectDropdown excludedTagsDropdown;
    private final MultiSelectDropdown rulesDropdown;
    private final JTextField textField;
    private final JButton exportBtn;

    private final JPanel cardsContainer;
    private final JLabel headerLabel;

    // data
    private List<IssueService.IssueDto> allIssues = new ArrayList<>();
    private List<ReportFileCard> cards = new ArrayList<>();
    private String filterText = "";
    private String statusFilter = "ALL";
    private boolean isAllSelected = false;

    public ReportPanel(GuiContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout());
        setBackground(GuiStyle.BG);

        // ---------- TOP ----------
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(GuiStyle.BG2);
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // Row 1: master + header + export
        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.setOpaque(false);

        JPanel row1Left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row1Left.setOpaque(false);

        masterCheckbox = new JCheckBox();
        masterCheckbox.setOpaque(false);
        masterCheckbox.setToolTipText("Выделить всё");
        masterCheckbox.addActionListener(e -> {
            isAllSelected = masterCheckbox.isSelected();
            for (ReportFileCard c : cards) c.setAllSelected(isAllSelected);
            updateExportBtn();
        });
        row1Left.add(masterCheckbox);

        headerLabel = new JLabel("Report");
        headerLabel.setForeground(GuiStyle.ACCENT);
        headerLabel.setFont(GuiStyle.FONT_TITLE);
        row1Left.add(headerLabel);
        row1.add(row1Left, BorderLayout.WEST);

        JPanel row1Right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row1Right.setOpaque(false);

        exportBtn = UiHelpers.primaryButton("Действия (0)");
        exportBtn.setVisible(false);
        exportBtn.addActionListener(e -> openExportMenu());
        row1Right.add(exportBtn);

        row1.add(row1Right, BorderLayout.EAST);
        top.add(row1);

        // Row 2: filters
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.setOpaque(false);

        row2.add(UiHelpers.dimLabel("Статус:"));
        statusCombo = UiHelpers.darkCombo(
                new String[]{"ALL", "UNPROCESSED", "PROCESSED"}, "ALL");
        statusCombo.setPreferredSize(new Dimension(150, 26));
        statusCombo.addActionListener(e -> {
            statusFilter = (String) statusCombo.getSelectedItem();
            applyFilters();
        });
        row2.add(statusCombo);

        tagsDropdown = new MultiSelectDropdown("🏷️ Теги", this::applyFilters);
        tagsDropdown.setPreferredSize(new Dimension(150, 26));
        row2.add(tagsDropdown);

        excludedTagsDropdown = new MultiSelectDropdown("🚫 Исключить", this::applyFilters);
        excludedTagsDropdown.setPreferredSize(new Dimension(170, 26));
        row2.add(excludedTagsDropdown);

        rulesDropdown = new MultiSelectDropdown("📏 Правила", this::applyFilters);
        rulesDropdown.setPreferredSize(new Dimension(160, 26));
        row2.add(rulesDropdown);

        textField = UiHelpers.darkField("");
        textField.setPreferredSize(new Dimension(260, 26));
        textField.setToolTipText("Фильтр по ключу/файлу/сообщению");
        textField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { filterText = textField.getText(); applyFilters(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { filterText = textField.getText(); applyFilters(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterText = textField.getText(); applyFilters(); }
        });
        row2.add(textField);

        top.add(row2);

        add(top, BorderLayout.NORTH);

        // ---------- CENTER ----------
        cardsContainer = new JPanel();
        cardsContainer.setLayout(new BoxLayout(cardsContainer, BoxLayout.Y_AXIS));
        cardsContainer.setBackground(GuiStyle.BG);
        cardsContainer.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JScrollPane scroll = new JScrollPane(cardsContainer);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(GuiStyle.BG);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // ---------- LISTENERS ----------
        ctx.onProjectChanged(c -> reload());
        ctx.onFiltersChanged(c -> reload());

        reload();
    }

    @Override
    public void reload() {
        if (!ctx.hasProject()) {
            cardsContainer.removeAll();
            cardsContainer.add(emptyLabel("Выберите проект в верхней панели"));
            cardsContainer.revalidate();
            cardsContainer.repaint();
            return;
        }

        cardsContainer.removeAll();
        cardsContainer.add(emptyLabel("Загрузка всех issues…"));
        cardsContainer.revalidate();
        cardsContainer.repaint();

        new ReportLoader(ctx, this::onLoaded, this::onError).execute();
    }

    private void onLoaded(List<IssueService.IssueDto> issues) {
        this.allIssues = new ArrayList<>(issues);
        rebuildCards();
        loadFiltersAsync();
        updateHeader();
    }

    private void onError(Throwable t) {
        cardsContainer.removeAll();
        cardsContainer.add(emptyLabel("Ошибка: " + t.getMessage()));
        cardsContainer.revalidate();
        cardsContainer.repaint();
    }

    private void rebuildCards() {
        // группировка по файлу
        Map<String, List<IssueService.IssueDto>> byFile = new TreeMap<>();
        for (IssueService.IssueDto it : allIssues) {
            byFile.computeIfAbsent(it.component(), k -> new ArrayList<>()).add(it);
        }

        cardsContainer.removeAll();
        cards.clear();

        for (Map.Entry<String, List<IssueService.IssueDto>> e : byFile.entrySet()) {
            ReportFileCard card = new ReportFileCard(e.getKey(), e.getValue(),
                    new ReportFileCard.Delegate() {
                        @Override public void onSelectionChanged() { updateExportBtn(); }
                        @Override public void onIssueDoubleClick(IssueService.IssueDto issue) {
                            new ReportIssueDialog(ctx, issue, ReportPanel.this::reload).setVisible(true);
                        }
                        @Override public void onIssueContextMenu(MouseEvent ev,
                                                                 IssueService.IssueDto issue,
                                                                 List<IssueService.IssueDto> selected) {
                            showContextMenu(ev, issue, selected);
                        }
                    });
            cards.add(card);
            cardsContainer.add(card);
            cardsContainer.add(Box.createVerticalStrut(10));
        }

        cardsContainer.revalidate();
        cardsContainer.repaint();
        applyFilters();
    }

    private void loadFiltersAsync() {
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                return new TagsService(ctx.api()).fetchTags(ctx.getProject());
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

        // rules — уникальные из всех issues
        Set<String> rules = new LinkedHashSet<>();
        for (IssueService.IssueDto it : allIssues) {
            if (it.rule() != null && !it.rule().isBlank()) rules.add(it.rule());
        }
        rulesDropdown.setItems(new ArrayList<>(rules));
    }

    // ---------- filters ----------

    private void applyFilters() {
        Set<String> tags = tagsDropdown.getSelected();
        Set<String> excludedTags = excludedTagsDropdown.getSelected();
        Set<String> rules = rulesDropdown.getSelected();

        int visible = 0;
        int totalShown = 0;
        for (ReportFileCard card : cards) {
            boolean matches = card.matchesFilter(filterText, tags, excludedTags, rules, statusFilter);
            card.setVisible(matches);
            if (matches) {
                visible++;
                totalShown += card.getAllIssues().size();
            }
        }
        cardsContainer.revalidate();
        cardsContainer.repaint();

        headerLabel.setText("Report · " + ctx.getProject()
                + " · " + ctx.getSeverity() + "/" + ctx.getQuality()
                + " · файлов: " + visible + " / " + cards.size()
                + " · issues: " + totalShown + " / " + allIssues.size());
        updateExportBtn();
    }

    private void updateHeader() {
        headerLabel.setText("Report · " + ctx.getProject()
                + " · " + ctx.getSeverity() + "/" + ctx.getQuality()
                + " · файлов: " + cards.size() + " · issues: " + allIssues.size());
    }

    // ---------- selection ----------

    private List<IssueService.IssueDto> collectSelected() {
        List<IssueService.IssueDto> out = new ArrayList<>();
        for (ReportFileCard c : cards) out.addAll(c.getSelectedIssues());
        return out;
    }

    private void updateExportBtn() {
        int n = collectSelected().size();
        exportBtn.setVisible(n > 0 || isAllSelected);
        if (isAllSelected) {
            exportBtn.setText("Действия со всеми (" + visibleCount() + ")");
        } else {
            exportBtn.setText("Действия с выбранными (" + n + ")");
        }
    }

    private int visibleCount() {
        int total = 0;
        for (ReportFileCard c : cards) {
            if (c.isVisible()) total += c.getAllIssues().size();
        }
        return total;
    }

    // ---------- context menu ----------

    private void showContextMenu(MouseEvent e, IssueService.IssueDto issue,
                                 List<IssueService.IssueDto> selected) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(GuiStyle.BG2);
        menu.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER, 1));

        menu.add(menuItem("📝 Сгенерировать промпт",
                () -> new ReportPromptDialog(ctx, issue).setVisible(true)));
        menu.add(menuItem("Посмотреть детали",
                () -> new ReportIssueDialog(ctx, issue, this::reload).setVisible(true)));
        menu.add(menuItem("🏷️ Редактировать теги",
                () -> new ReportTagsDialog(ctx, selected, this::reload).setVisible(true)));

        menu.addSeparator();

        menu.add(menuItem("Выделить всё", () -> {
            masterCheckbox.setSelected(true);
            isAllSelected = true;
            for (ReportFileCard c : cards) c.setAllSelected(true);
            updateExportBtn();
        }));
        menu.add(menuItem("Снять выделение", () -> {
            masterCheckbox.setSelected(false);
            isAllSelected = false;
            for (ReportFileCard c : cards) c.setAllSelected(false);
            updateExportBtn();
        }));

        boolean hasUnmarked = selected.stream().anyMatch(i -> !i.isProcessed());
        if (hasUnmarked) {
            menu.addSeparator();
            menu.add(menuItem("Добавить комментарий (" + selected.size() + ")",
                    () -> new ReportBulkDialog(ctx, selected, this::reload).setVisible(true)));
        }

        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    // ---------- export ----------

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
        List<String> keysCsv = null;
        int expectedTotal;

        if (!isAllSelected) {
            List<IssueService.IssueDto> sel = collectSelected();
            if (sel.isEmpty()) {
                UiHelpers.showToast(this, "Ничего не выбрано", true);
                return;
            }
            keysCsv = new ArrayList<>();
            for (IssueService.IssueDto it : sel) keysCsv.add(it.key());
            expectedTotal = keysCsv.size();
        } else {
            expectedTotal = visibleCount();
        }

        String baseQuery = ReportExportService.buildBaseQuery(
                ctx.getProject(),
                ctx.getSeverity(),
                ctx.getQuality(),
                statusFilter,
                tagsDropdown.getSelected(),
                excludedTagsDropdown.getSelected(),
                rulesDropdown.getSelected(),
                filterText,
                expectedTotal);

        new ReportExportDialog(ctx, type, baseQuery, keysCsv).setVisible(true);
    }

    // ---------- ui helpers ----------

    private JLabel emptyLabel(String text) {
        JLabel l = new JLabel(UiHelpers.wrapHtml(text), SwingConstants.CENTER);
        l.setForeground(GuiStyle.TEXT_DIM);
        l.setFont(GuiStyle.FONT_TITLE);
        l.setAlignmentX(Component.CENTER_ALIGNMENT);
        return l;
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