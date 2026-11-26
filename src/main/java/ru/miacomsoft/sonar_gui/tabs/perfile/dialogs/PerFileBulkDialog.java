package ru.miacomsoft.sonar_gui.tabs.perfile.dialogs;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.IssueService;
import ru.miacomsoft.sonar_gui.common.services.MarkService;
import ru.miacomsoft.sonar_gui.common.services.SetTagsService;
import ru.miacomsoft.sonar_gui.common.services.TagsService;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PerFileBulkDialog extends JDialog {

    private final GuiContext ctx;
    private final List<IssueService.IssueDto> issues;
    private final Runnable onDone;

    private final JComboBox<String> statusCombo;
    private final JTextArea commentArea = new JTextArea(4, 40);
    private final JPanel tagsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    private final Set<String> selectedTags = new LinkedHashSet<>();
    private final List<String> availableTags = new ArrayList<>();
    private final JButton applyBtn;

    public PerFileBulkDialog(GuiContext ctx, List<IssueService.IssueDto> issues, Runnable onDone) {
        super((Frame) null, "Массовое действие", true);
        this.ctx = ctx;
        this.issues = issues;
        this.onDone = onDone;

        if (!issues.isEmpty()) selectedTags.addAll(issues.get(0).tags());

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(720, 560);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JLabel header = new JLabel("Выбрано ошибок: " + issues.size());
        header.setForeground(GuiStyle.ACCENT);
        header.setFont(GuiStyle.FONT_TITLE);
        root.add(header, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusRow.setOpaque(false);
        statusRow.add(UiHelpers.dimLabel("Статус перехода:"));
        statusCombo = UiHelpers.darkCombo(new String[]{
                "accept", "falsepositive", "confirm", "resolve", "wontfix"
        }, "accept");
        statusCombo.setPreferredSize(new Dimension(200, 26));
        statusRow.add(statusCombo);
        center.add(statusRow);

        center.add(Box.createVerticalStrut(8));
        center.add(UiHelpers.dimLabel("Комментарий:"));
        commentArea.setFont(GuiStyle.FONT_MONO);
        commentArea.setBackground(GuiStyle.BG2);
        commentArea.setForeground(GuiStyle.TEXT);
        commentArea.setLineWrap(true);
        commentArea.setWrapStyleWord(true);
        JScrollPane commentScroll = new JScrollPane(commentArea);
        commentScroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        commentScroll.setPreferredSize(new Dimension(0, 100));
        center.add(commentScroll);

        center.add(Box.createVerticalStrut(8));
        center.add(UiHelpers.dimLabel("Теги:"));
        tagsPanel.setOpaque(false);
        JScrollPane tagsScroll = new JScrollPane(tagsPanel);
        tagsScroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        tagsScroll.setPreferredSize(new Dimension(0, 160));
        tagsScroll.getViewport().setBackground(GuiStyle.BG2);
        center.add(tagsScroll);

        root.add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        JButton cancelBtn = UiHelpers.secondaryButton("Отмена");
        cancelBtn.addActionListener(e -> dispose());
        footer.add(cancelBtn);
        applyBtn = UiHelpers.primaryButton("Применить ко всем");
        applyBtn.addActionListener(e -> doApply());
        footer.add(applyBtn);
        root.add(footer, BorderLayout.SOUTH);

        loadAvailableTags();
    }

    private void loadAvailableTags() {
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                return new TagsService(ctx).fetchTags(ctx.getProject());
            }
            @Override protected void done() {
                try {
                    List<String> tags = get();
                    availableTags.clear();
                    availableTags.addAll(tags);
                    for (String t : selectedTags) {
                        if (!availableTags.contains(t)) availableTags.add(t);
                    }
                    rebuildTagCheckboxes();
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void rebuildTagCheckboxes() {
        tagsPanel.removeAll();
        for (String t : availableTags) {
            JCheckBox cb = new JCheckBox(t);
            cb.setOpaque(false);
            cb.setForeground(GuiStyle.TEXT);
            cb.setFont(GuiStyle.FONT_BASE);
            cb.setSelected(selectedTags.contains(t));
            cb.addActionListener(e -> {
                if (cb.isSelected()) selectedTags.add(t);
                else selectedTags.remove(t);
            });
            tagsPanel.add(cb);
        }
        tagsPanel.revalidate();
        tagsPanel.repaint();
    }

    private void doApply() {
        String status = (String) statusCombo.getSelectedItem();
        String comment = commentArea.getText();
        String[] tags = selectedTags.toArray(new String[0]);

        applyBtn.setEnabled(false);
        applyBtn.setText("Применение…");

        new SwingWorker<int[], Void>() {
            @Override protected int[] doInBackground() {
                int ok = 0, fail = 0;
                MarkService markService = new MarkService(ctx);
                SetTagsService setTags = new SetTagsService(ctx);
                for (IssueService.IssueDto it : issues) {
                    try {
                        markService.markOne(ctx.getProject(), it.key(), status, comment);
                        if (!sameTags(it.tags(), selectedTags)) {
                            setTags.setTags(ctx.getProject(), it.key(), tags);
                        }
                        ok++;
                    } catch (Exception e) {
                        fail++;
                    }
                }
                return new int[]{ok, fail};
            }
            @Override protected void done() {
                applyBtn.setEnabled(true);
                applyBtn.setText("Применить ко всем");
                try {
                    int[] r = get();
                    UiHelpers.showToast(PerFileBulkDialog.this,
                            "Применено: " + r[0] + ", ошибок: " + r[1], r[1] > 0);
                    dispose();
                    if (onDone != null) onDone.run();
                } catch (Exception e) {
                    UiHelpers.showError(PerFileBulkDialog.this,
                            "Ошибка: " + e.getMessage());
                }
            }
        }.execute();
    }

    private static boolean sameTags(List<String> a, Set<String> b) {
        if (a == null) a = List.of();
        if (a.size() != b.size()) return false;
        return a.stream().allMatch(b::contains);
    }
}