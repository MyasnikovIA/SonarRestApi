package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.MultiSelectDropdown;
import ru.miacomsoft.sonar_gui.common.Reloadable;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.IssueService;
import ru.miacomsoft.sonar_gui.common.services.TagsService;
import ru.miacomsoft.sonar_gui.tabs.report.dialogs.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

public class ReportPanel extends JPanel implements Reloadable {

    private static final String NO_TAGS_KEY = "__NO_TAGS__";

    private final GuiContext ctx;

    private final JCheckBox masterCheckbox;
    private final MultiSelectDropdown statusDropdown;
    private final MultiSelectDropdown sevDropdown;
    private final MultiSelectDropdown qualDropdown;
    private final MultiSelectDropdown tagsDropdown;
    private final MultiSelectDropdown excludedTagsDropdown;
    private final MultiSelectDropdown rulesDropdown;
    private final JTextField textField;
    private final JButton applyFilterBtn;
    private final JButton exportBtn;

    private final JProgressBar progressBar;
    private final JPanel cardsContainer;
    private final JLabel headerLabel;

    private List<IssueService.IssueDto> allIssues = new ArrayList<>();
    private List<ReportFileCard> cards = new ArrayList<>();
    private String filterText = "";
    private boolean isAllSelected = false;

    // Ссылка на текущий активный загрузчик для возможности отмены
    private ReportLoader currentLoader;

    public ReportPanel(GuiContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout());
        setBackground(GuiStyle.BG);

        // ================= TOP BAR =================
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(GuiStyle.BG2);
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // Row 1: Master + Header + Export
        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.setOpaque(false);
        JPanel row1Left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row1Left.setOpaque(false);

        masterCheckbox = new JCheckBox();
        masterCheckbox.setOpaque(false);
        masterCheckbox.setToolTipText("Выделить всё (или экспортировать по фильтрам без загрузки)");
        masterCheckbox.addActionListener(e -> {
            isAllSelected = masterCheckbox.isSelected();
            for (ReportFileCard c : cards) c.setGlobalSelectAll(isAllSelected);
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

        tagsDropdown = new MultiSelectDropdown("🏷️ Теги (Tags)", this::applyFilters);
        tagsDropdown.setPreferredSize(new Dimension(160, 26));
        row2.add(tagsDropdown);

        excludedTagsDropdown = new MultiSelectDropdown("🚫 Исключить (Exclude)", this::applyFilters);
        excludedTagsDropdown.setPreferredSize(new Dimension(200, 26));
        row2.add(excludedTagsDropdown);

        rulesDropdown = new MultiSelectDropdown("📏 Правила (Rules)", this::applyFilters);
        rulesDropdown.setPreferredSize(new Dimension(180, 26));
        row2.add(rulesDropdown);

        textField = UiHelpers.darkField("");
        textField.setPreferredSize(new Dimension(200, 26));
        textField.setToolTipText("Фильтр по ключу/файлу/сообщению");
        textField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { filterText = textField.getText(); applyFilters(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { filterText = textField.getText(); applyFilters(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterText = textField.getText(); applyFilters(); }
        });
        row2.add(textField);

        applyFilterBtn = UiHelpers.primaryButton("Применить фильтр");
        applyFilterBtn.addActionListener(e -> reload());
        row2.add(applyFilterBtn);

        top.add(row2);

        // Progress Bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        progressBar.setBackground(GuiStyle.BG3);
        progressBar.setForeground(GuiStyle.ACCENT);
        top.add(progressBar);

        add(top, BorderLayout.NORTH);

        // ================= CENTER =================
        cardsContainer = new JPanel();
        cardsContainer.setLayout(new BoxLayout(cardsContainer, BoxLayout.Y_AXIS));
        cardsContainer.setBackground(GuiStyle.BG);
        cardsContainer.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JScrollPane scroll = new JScrollPane(cardsContainer);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(GuiStyle.BG);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // Инициализация списка тегов и правил
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
        if (!ctx.hasProject()) {
            cardsContainer.removeAll();
            cardsContainer.add(emptyLabel("Выберите проект в верхней панели"));
            cardsContainer.revalidate();
            cardsContainer.repaint();
            return;
        }

        // Отменяем предыдущую загрузку, если она еще работает
        if (currentLoader != null && !currentLoader.isDone()) {
            System.out.println("[ReportPanel] Отмена предыдущей загрузки...");
            currentLoader.cancel(true);
        }

        cardsContainer.removeAll();
        cardsContainer.add(emptyLabel("Загрузка данных..."));
        cardsContainer.revalidate();
        cardsContainer.repaint();

        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        exportBtn.setEnabled(false);

        String sevCsv = sevDropdown.getSelectedCsv();
        String qualCsv = qualDropdown.getSelectedCsv();
        String statCsv = statusDropdown.getSelectedCsv();

        currentLoader = new ReportLoader(ctx, sevCsv, qualCsv, statCsv,
                this::onLoaded,
                this::onError,
                this::onProgress);
        currentLoader.execute();
    }

    private void onProgress(Object[] p) {
        int cur = (int) p[0];
        int total = (int) p[1];
        String msg = (String) p[2];
        progressBar.setIndeterminate(false);
        if (total > 0) {
            progressBar.setMaximum(total);
            progressBar.setValue(cur);
            progressBar.setString(msg);
        } else {
            progressBar.setIndeterminate(true);
            progressBar.setString(msg);
        }
    }

    private void onLoaded(List<IssueService.IssueDto> issues) {
        this.allIssues = new ArrayList<>(issues);
        progressBar.setVisible(false);
        exportBtn.setEnabled(true);

        // Сбрасываем глобальное выделение при перезагрузке
        isAllSelected = false;
        masterCheckbox.setSelected(false);

        rebuildCards();
        loadRulesFacet();
        updateHeader();
    }

    private void onError(Throwable t) {
        progressBar.setVisible(false);
        cardsContainer.removeAll();
        cardsContainer.add(emptyLabel("Ошибка: " + t.getMessage()));
        cardsContainer.revalidate();
        cardsContainer.repaint();
    }

    private void loadRulesFacet() {
        Set<String> rules = new LinkedHashSet<>();
        for (IssueService.IssueDto it : allIssues) {
            if (it.rule() != null && !it.rule().isBlank()) rules.add(it.rule());
        }
        rulesDropdown.setItems(new ArrayList<>(rules));
    }

    private void rebuildCards() {
        List<IssueService.IssueDto> sorted = new ArrayList<>(allIssues);
        sorted.sort(Comparator
                .comparing(IssueService.IssueDto::rule)
                .thenComparing(IssueService.IssueDto::component)
                .thenComparingInt(IssueService.IssueDto::line));

        Map<String, List<IssueService.IssueDto>> grouped = new TreeMap<>();
        boolean groupByRule = !rulesDropdown.getSelected().isEmpty();

        for (IssueService.IssueDto it : sorted) {
            String key = groupByRule ? it.rule() : it.component();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(it);
        }

        cardsContainer.removeAll();
        cards.clear();

        for (Map.Entry<String, List<IssueService.IssueDto>> e : grouped.entrySet()) {
            String title = groupByRule ? "📏 " + e.getKey() : "📁 " + e.getKey();

            ReportFileCard card = new ReportFileCard(title, e.getValue(),
                    new ReportFileCard.Delegate() {
                        @Override public void onSelectionChanged() { updateExportBtn(); }
                        @Override public void onIssueDoubleClick(IssueService.IssueDto issue) {
                            new ReportIssueDialog(ctx, issue, ReportPanel.this::reload).setVisible(true);
                        }
                        @Override public void onIssueContextMenu(MouseEvent ev, IssueService.IssueDto issue, List<IssueService.IssueDto> selected) {
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

    private void applyFilters() {
        Set<String> tags = tagsDropdown.getSelected();
        Set<String> excludedTags = excludedTagsDropdown.getSelected();
        Set<String> rules = rulesDropdown.getSelected();
        String statusFilter = "ALL";

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
                + " · файлов: " + visible + " / " + cards.size()
                + " · issues: " + totalShown + " / " + allIssues.size());
        updateExportBtn();
    }

    private void updateHeader() {
        headerLabel.setText("Report · " + ctx.getProject()
                + " · файлов: " + cards.size() + " · issues: " + allIssues.size());
    }

    private List<IssueService.IssueDto> collectSelected() {
        List<IssueService.IssueDto> out = new ArrayList<>();
        for (ReportFileCard c : cards) {
            if (isAllSelected && !c.isVisible()) continue;
            out.addAll(c.getSelectedIssues());
        }
        return out;
    }

    private void updateExportBtn() {
        int n = isAllSelected ? visibleCount() : collectSelected().size();
        // Кнопка видна, если что-то выбрано ИЛИ если активен режим "Выбрать всё"
        exportBtn.setVisible(n > 0 || isAllSelected);
        exportBtn.setText(isAllSelected
                ? "Действия со всеми (" + n + ")"
                : "Действия с выбранными (" + n + ")");
    }

    private int visibleCount() {
        int total = 0;
        for (ReportFileCard c : cards) if (c.isVisible()) total += c.getAllIssues().size();
        return total;
    }

    private void showContextMenu(MouseEvent e, IssueService.IssueDto issue, List<IssueService.IssueDto> selected) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(GuiStyle.BG2);
        menu.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER, 1));

        menu.add(menuItem("📝 Сгенерировать промпт", () -> new ReportPromptDialog(ctx, issue).setVisible(true)));
        menu.add(menuItem("Посмотреть детали", () -> new ReportIssueDialog(ctx, issue, this::reload).setVisible(true)));
        menu.add(menuItem("🏷️ Редактировать теги", () -> new ReportTagsDialog(ctx, selected, this::reload).setVisible(true)));

        menu.addSeparator();
        menu.add(menuItem("Выделить всё", () -> {
            masterCheckbox.setSelected(true);
            isAllSelected = true;
            for (ReportFileCard c : cards) c.setGlobalSelectAll(true);
            updateExportBtn();
        }));
        menu.add(menuItem("Снять выделение", () -> {
            masterCheckbox.setSelected(false);
            isAllSelected = false;
            for (ReportFileCard c : cards) c.setGlobalSelectAll(false);
            updateExportBtn();
        }));

        boolean hasUnmarked = selected.stream().anyMatch(i -> !i.isProcessed());
        if (hasUnmarked) {
            menu.addSeparator();
            menu.add(menuItem("Добавить комментарий (" + selected.size() + ")", () -> new ReportBulkDialog(ctx, selected, this::reload).setVisible(true)));
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
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
        List<String> keysCsv = null;
        int expectedTotal;

        if (!isAllSelected) {
            // Ручной выбор
            List<IssueService.IssueDto> sel = collectSelected();
            if (sel.isEmpty()) {
                UiHelpers.showToast(this, "Ничего не выбрано", true);
                return;
            }
            keysCsv = new ArrayList<>();
            for (IssueService.IssueDto it : sel) keysCsv.add(it.key());
            expectedTotal = keysCsv.size();
        } else {
            // Режим "Выбрать всё" (может быть вызван даже при 0 загруженных строк)
            expectedTotal = visibleCount();
            // ОШИБКА БЫЛА ЗДЕСЬ: мы блокировали экспорт при 0 issues. Теперь мы этого не делаем.
            // ReportExportService сам подсчитает точное количество и выгрузит данные.
        }

        ReportExportService.BaseFilter filter = new ReportExportService.BaseFilter();
        filter.project        = ctx.getProject();
        filter.severity       = sevDropdown.getSelectedCsv();
        filter.quality        = qualDropdown.getSelectedCsv();
        filter.hideProcessed  = ctx.isHideProcessed();
        filter.tags           = tagsDropdown.getSelected();
        filter.excludedTags   = excludedTagsDropdown.getSelected();
        filter.rules          = rulesDropdown.getSelected();
        filter.filterText     = filterText;
        filter.statusFilter   = statusDropdown.getSelectedCsv();
        filter.expectedTotal  = expectedTotal;

        System.out.println("[ReportPanel] Экспорт " + type
                + " · режим=" + (isAllSelected ? "ALL (по фильтрам)" : "SELECTED")
                + " · expectedTotal=" + expectedTotal);

        new ReportExportDialog(ctx, type, keysCsv, filter).setVisible(true);
    }

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