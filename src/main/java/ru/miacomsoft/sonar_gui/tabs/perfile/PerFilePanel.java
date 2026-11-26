package ru.miacomsoft.sonar_gui.tabs.perfile;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.Reloadable;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.IssueService;
import ru.miacomsoft.sonar_gui.common.services.MarkService;
import ru.miacomsoft.sonar_gui.tabs.perfile.dialogs.PerFileBulkDialog;
import ru.miacomsoft.sonar_gui.tabs.perfile.dialogs.PerFileIssueDialog;
import ru.miacomsoft.sonar_gui.tabs.perfile.dialogs.PerFilePromptDialog;
import ru.miacomsoft.sonar_gui.tabs.perfile.dialogs.PerFileTagsDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class PerFilePanel extends JPanel implements Reloadable {

    private static final int PAGE_SIZE = 3000;

    private final GuiContext ctx;

    private final JCheckBox masterCheckbox;
    private final JCheckBox hideProcessedCheckbox;
    private final JTextField filterField;
    private final JButton bulkButton;
    private final JPanel cardsContainer;
    private final JLabel headerLabel;
    private final JButton prevBtn;
    private final JButton nextBtn;
    private final JLabel pageLabel;

    private int page = 1;
    private int total = 0;
    private int totalPages = 1;
    private List<PerFileCard> cards = new ArrayList<>();
    private String filterText = "";
    private boolean isAllSelected = false;

    // Данные для режима "Выбрать всё"
    private List<IssueService.IssueDto> allSelectedIssues = null;
    private SwingWorker<List<IssueService.IssueDto>, Void> allSelectWorker = null;

    public PerFilePanel(GuiContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout());
        setBackground(GuiStyle.BG);

        // ---------- TOP ----------
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBackground(GuiStyle.BG2);
        topBar.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel topLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        topLeft.setOpaque(false);

        masterCheckbox = new JCheckBox();
        masterCheckbox.setOpaque(false);
        masterCheckbox.setToolTipText("Выделить всё (все записи по текущим фильтрам)");
        masterCheckbox.addActionListener(e -> {
            isAllSelected = masterCheckbox.isSelected();
            for (PerFileCard c : cards) c.setGlobalSelectAll(isAllSelected);

            if (isAllSelected) {
                triggerAllSelectFetch();
            } else {
                cancelAllSelectFetch();
                allSelectedIssues = null;
                updateBulkButton();
            }
        });
        topLeft.add(masterCheckbox);

        headerLabel = new JLabel("Per-File");
        headerLabel.setForeground(GuiStyle.ACCENT);
        headerLabel.setFont(GuiStyle.FONT_TITLE);
        topLeft.add(headerLabel);
        topBar.add(topLeft, BorderLayout.WEST);

        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        topRight.setOpaque(false);

        hideProcessedCheckbox = new JCheckBox("Скрыть обработанные");
        hideProcessedCheckbox.setOpaque(false);
        hideProcessedCheckbox.setForeground(GuiStyle.TEXT);
        hideProcessedCheckbox.setSelected(ctx.isHideProcessed());
        hideProcessedCheckbox.addActionListener(e -> {
            ctx.setHideProcessed(hideProcessedCheckbox.isSelected());
            reload();
        });
        topRight.add(hideProcessedCheckbox);

        filterField = UiHelpers.darkField("");
        filterField.setPreferredSize(new Dimension(240, 26));
        filterField.addActionListener(e -> applyTextFilter());
        topRight.add(filterField);

        JButton applyFilterBtn = UiHelpers.secondaryButton("Применить");
        applyFilterBtn.addActionListener(e -> applyTextFilter());
        topRight.add(applyFilterBtn);

        // Кнопка массовых действий (изначально скрыта, появляется при выборе)
        bulkButton = UiHelpers.primaryButton("Действия с выбранными (0)");
        bulkButton.setVisible(false);
        bulkButton.addActionListener(e -> openBulkDialog());
        topRight.add(bulkButton);

        topBar.add(topRight, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // ---------- CARDS ----------
        cardsContainer = new JPanel();
        cardsContainer.setLayout(new BoxLayout(cardsContainer, BoxLayout.Y_AXIS));
        cardsContainer.setBackground(GuiStyle.BG);
        cardsContainer.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JScrollPane scroll = new JScrollPane(cardsContainer);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(GuiStyle.BG);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // ---------- PAGINATION ----------
        JPanel pageBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        pageBar.setBackground(GuiStyle.BG2);
        pageBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, GuiStyle.BORDER));

        prevBtn = UiHelpers.secondaryButton("← Назад");
        prevBtn.addActionListener(e -> { if (page > 1) { page--; reload(); } });
        pageBar.add(prevBtn);

        pageLabel = UiHelpers.dimLabel("Стр. 1 из 1");
        pageBar.add(pageLabel);

        nextBtn = UiHelpers.secondaryButton("Вперед →");
        nextBtn.addActionListener(e -> { if (page < totalPages) { page++; reload(); } });
        pageBar.add(nextBtn);

        add(pageBar, BorderLayout.SOUTH);

        ctx.onProjectChanged(c -> { page = 1; reload(); });
        ctx.onFiltersChanged(c -> { page = 1; reload(); });

        reload();
    }

    @Override
    public void reload() {
        // Сбрасываем состояние выбора при любой перезагрузке
        masterCheckbox.setSelected(false);
        isAllSelected = false;
        cancelAllSelectFetch();
        allSelectedIssues = null;

        if (!ctx.hasProject()) {
            cardsContainer.removeAll();
            cardsContainer.add(emptyLabel("Выберите проект в верхней панели"));
            cardsContainer.revalidate();
            cardsContainer.repaint();
            updateBulkButton();
            return;
        }

        cardsContainer.removeAll();
        cardsContainer.add(emptyLabel("Загрузка…"));
        cardsContainer.revalidate();
        cardsContainer.repaint();

        new PerFileLoader(ctx, page, PAGE_SIZE, this::onLoaded, this::onError).execute();
    }

    private void onLoaded(IssueService.PerFilePage result) {
        this.total = result.total();
        this.totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        this.cards = new ArrayList<>();

        cardsContainer.removeAll();

        if (result.files().isEmpty()) {
            cardsContainer.add(emptyLabel("Нет замечаний по фильтру"));
        } else {
            for (IssueService.FileGroup group : result.files()) {
                PerFileCard card = new PerFileCard(group, new PerFileCard.Delegate() {
                    @Override public void onSelectionChanged() { updateBulkButton(); }
                    @Override public void onIssueDoubleClick(IssueService.IssueDto issue) {
                        openIssueDialog(issue);
                    }
                    @Override public void onIssueContextMenu(MouseEvent e,
                                                             IssueService.IssueDto issue,
                                                             List<IssueService.IssueDto> selected) {
                        showContextMenu(e, issue, selected);
                    }
                });
                cards.add(card);
                cardsContainer.add(card);
                cardsContainer.add(Box.createVerticalStrut(10));
            }
        }

        cardsContainer.revalidate();
        cardsContainer.repaint();

        headerLabel.setText("Per-File · " + ctx.getProject()
                + " · " + ctx.getSeverity() + "/" + ctx.getQuality()
                + " · Всего: " + total);

        pageLabel.setText("Стр. " + page + " из " + totalPages);
        prevBtn.setEnabled(page > 1);
        nextBtn.setEnabled(page < totalPages);

        applyTextFilter();
        updateBulkButton();
    }

    private void onError(Throwable t) {
        cardsContainer.removeAll();
        cardsContainer.add(emptyLabel("Ошибка: " + t.getMessage()));
        cardsContainer.revalidate();
        cardsContainer.repaint();
        updateBulkButton();
    }

    /**
     * Запуск асинхронной загрузки всех issues по текущим фильтрам для подсчёта.
     */
    private void triggerAllSelectFetch() {
        if (allSelectWorker != null && !allSelectWorker.isDone()) {
            allSelectWorker.cancel(true);
        }
        allSelectedIssues = null;
        updateBulkButton(); // Переводит кнопку в режим "Подсчёт..."

        allSelectWorker = new SwingWorker<>() {
            @Override protected List<IssueService.IssueDto> doInBackground() throws Exception {
                IssueService service = new IssueService(ctx);
                // Загружаем всё, что подходит под глобальные фильтры
                List<IssueService.IssueDto> all = service.fetchAllIssues(
                        ctx.getProject(), ctx.getSeverity(), ctx.getQuality(),
                        ctx.isHideProcessed(), "ALL", null);

                // Применяем локальный текстовый фильтр, если он есть
                if (!filterText.isEmpty()) {
                    List<IssueService.IssueDto> filtered = new ArrayList<>();
                    for (IssueService.IssueDto it : all) {
                        if (isCancelled()) break;
                        boolean m = (it.key() != null && it.key().toLowerCase().contains(filterText))
                                || (it.message() != null && it.message().toLowerCase().contains(filterText))
                                || (it.component() != null && it.component().toLowerCase().contains(filterText));
                        if (m) filtered.add(it);
                    }
                    return filtered;
                }
                return all;
            }

            @Override protected void done() {
                if (isCancelled() || !isAllSelected) return;
                try {
                    allSelectedIssues = get();
                    updateBulkButton();
                } catch (Exception e) {
                    UiHelpers.showToast(PerFilePanel.this, "Ошибка подсчёта: " + e.getMessage(), true);
                    bulkButton.setText("Ошибка подсчёта");
                    bulkButton.setEnabled(false);
                }
            }
        };
        allSelectWorker.execute();
    }

    private void cancelAllSelectFetch() {
        if (allSelectWorker != null) {
            allSelectWorker.cancel(true);
            allSelectWorker = null;
        }
    }

    private List<IssueService.IssueDto> collectSelected() {
        if (isAllSelected) {
            return allSelectedIssues != null ? allSelectedIssues : new ArrayList<>();
        }
        List<IssueService.IssueDto> out = new ArrayList<>();
        for (PerFileCard c : cards) out.addAll(c.getSelectedIssues());
        return out;
    }

    private void updateBulkButton() {
        if (isAllSelected) {
            bulkButton.setVisible(true);
            if (allSelectWorker != null && !allSelectWorker.isDone()) {
                bulkButton.setText("Подсчёт всех записей...");
                bulkButton.setEnabled(false);
            } else if (allSelectedIssues != null) {
                bulkButton.setText("Действия со всеми (" + allSelectedIssues.size() + ")");
                bulkButton.setEnabled(true);
            } else {
                bulkButton.setText("Подсчёт всех записей...");
                bulkButton.setEnabled(false);
            }
        } else {
            int n = collectSelected().size();
            bulkButton.setVisible(n > 0);
            bulkButton.setEnabled(true);
            bulkButton.setText("Действия с выбранными (" + n + ")");
        }
    }

    private void openIssueDialog(IssueService.IssueDto issue) {
        new PerFileIssueDialog(ctx, issue, this::reload).setVisible(true);
    }

    private void openBulkDialog() {
        List<IssueService.IssueDto> sel = collectSelected();
        if (sel.isEmpty()) {
            UiHelpers.showToast(this, "Нет данных для обработки", true);
            return;
        }
        new PerFileBulkDialog(ctx, sel, this::reload).setVisible(true);
    }

    private void showContextMenu(MouseEvent e, IssueService.IssueDto issue,
                                 List<IssueService.IssueDto> selected) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(GuiStyle.BG2);
        menu.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER, 1));

        menu.add(menuItem("📝 Сгенерировать промпт",
                () -> new PerFilePromptDialog(ctx, issue).setVisible(true)));
        menu.add(menuItem("Посмотреть детали", () -> openIssueDialog(issue)));
        menu.add(menuItem("🏷️ Редактировать теги",
                () -> new PerFileTagsDialog(ctx, selected, this::reload).setVisible(true)));

        menu.addSeparator();

        menu.add(menuItem("Выделить всё", () -> {
            masterCheckbox.setSelected(true);
            isAllSelected = true;
            for (PerFileCard c : cards) c.setGlobalSelectAll(true);
            triggerAllSelectFetch();
        }));
        menu.add(menuItem("Снять выделение", () -> {
            masterCheckbox.setSelected(false);
            isAllSelected = false;
            for (PerFileCard c : cards) c.setGlobalSelectAll(false);
            cancelAllSelectFetch();
            allSelectedIssues = null;
            updateBulkButton();
        }));

        boolean hasUnmarked = selected.stream().anyMatch(i -> !i.isProcessed());
        boolean hasMarked   = selected.stream().anyMatch(IssueService.IssueDto::isProcessed);

        if (hasUnmarked) {
            menu.addSeparator();
            menu.add(menuItem("Добавить комментарий (" + selected.size() + ")",
                    () -> new PerFileBulkDialog(ctx, selected, this::reload).setVisible(true)));
        }
        if (hasMarked) {
            menu.addSeparator();
            menu.add(menuItem("Отменить комментарий (Reopen) (" + selected.size() + ")",
                    () -> reopenSelected(selected)));
        }

        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void reopenSelected(List<IssueService.IssueDto> selected) {
        List<IssueService.IssueDto> reopenable = selected.stream()
                .filter(IssueService.IssueDto::isProcessed)
                .toList();
        if (reopenable.isEmpty()) return;
        if (!UiHelpers.confirm(this, "Reopen " + reopenable.size() + " issues?")) return;

        new SwingWorker<int[], Void>() {
            @Override protected int[] doInBackground() {
                int ok = 0, fail = 0;
                MarkService markService = new MarkService(ctx);
                for (IssueService.IssueDto it : reopenable) {
                    try {
                        String transition = "CONFIRMED".equals(it.status())
                                ? "unconfirm" : "reopen";
                        markService.markOne(ctx.getProject(), it.key(), transition, "");
                        ok++;
                    } catch (Exception ex) {
                        fail++;
                    }
                }
                return new int[]{ok, fail};
            }
            @Override protected void done() {
                try {
                    int[] r = get();
                    UiHelpers.showToast(PerFilePanel.this,
                            "Reopen: OK " + r[0] + ", ошибок " + r[1], r[1] > 0);
                    reload();
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void applyTextFilter() {
        filterText = filterField.getText().trim().toLowerCase();
        for (PerFileCard card : cards) {
            boolean matches = filterText.isEmpty()
                    || card.getFileName().toLowerCase().contains(filterText);
            if (!matches) {
                for (IssueService.IssueDto it : card.getAllIssues()) {
                    if ((it.message() != null && it.message().toLowerCase().contains(filterText))
                            || (it.rule() != null && it.rule().toLowerCase().contains(filterText))) {
                        matches = true;
                        break;
                    }
                }
            }
            card.setVisible(matches);
        }
        cardsContainer.revalidate();
        cardsContainer.repaint();

        // Если включен глобальный выбор, пересчитываем данные с учётом текста
        if (isAllSelected) {
            triggerAllSelectFetch();
        } else {
            updateBulkButton();
        }
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