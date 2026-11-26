package ru.miacomsoft.sonar_gui.tabs.perfile.dialogs;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.IssueService;
import ru.miacomsoft.sonar_gui.common.services.SetTagsService;
import ru.miacomsoft.sonar_gui.common.services.TagsService;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PerFileTagsDialog extends JDialog {

    private final GuiContext ctx;
    private final List<IssueService.IssueDto> issues;
    private final Runnable onDone;

    private final JTextField filterField = UiHelpers.darkField("");
    private final JPanel listPanel = new JPanel();
    private final Set<String> selectedTags = new LinkedHashSet<>();
    private final List<String> availableTags = new ArrayList<>();
    private final JLabel statusLbl;
    private final JButton saveBtn;

    public PerFileTagsDialog(GuiContext ctx, List<IssueService.IssueDto> issues, Runnable onDone) {
        super((Frame) null, "Редактирование тегов", true);
        this.ctx = ctx;
        this.issues = issues;
        this.onDone = onDone;

        if (!issues.isEmpty()) selectedTags.addAll(issues.get(0).tags());

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(600, 560);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JLabel header = new JLabel("Выбрано ошибок: " + issues.size());
        header.setForeground(GuiStyle.ACCENT);
        header.setFont(GuiStyle.FONT_TITLE);
        root.add(header, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.setOpaque(false);

        filterField.setPreferredSize(new Dimension(0, 28));
        filterField.setToolTipText("Фильтр по тегам");
        filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { rebuildList(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { rebuildList(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildList(); }
        });
        center.add(filterField, BorderLayout.NORTH);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(GuiStyle.BG2);
        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        scroll.getViewport().setBackground(GuiStyle.BG2);
        center.add(scroll, BorderLayout.CENTER);

        root.add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        statusLbl = UiHelpers.dimLabel("Загрузка тегов…");
        footer.add(statusLbl);
        JButton cancelBtn = UiHelpers.secondaryButton("Отмена");
        cancelBtn.addActionListener(e -> dispose());
        footer.add(cancelBtn);
        saveBtn = UiHelpers.primaryButton("Сохранить");
        saveBtn.setEnabled(false);
        saveBtn.addActionListener(e -> doSave());
        footer.add(saveBtn);
        root.add(footer, BorderLayout.SOUTH);

        loadTags();
    }

    private void loadTags() {
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
                    rebuildList();
                    statusLbl.setText("Тегов: " + availableTags.size());
                    saveBtn.setEnabled(true);
                } catch (Exception e) {
                    statusLbl.setText("Ошибка: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void rebuildList() {
        String q = filterField.getText().trim().toLowerCase();
        listPanel.removeAll();
        for (String t : availableTags) {
            if (!q.isEmpty() && !t.toLowerCase().contains(q)) continue;
            JCheckBox cb = new JCheckBox(t);
            cb.setOpaque(false);
            cb.setForeground(GuiStyle.TEXT);
            cb.setFont(GuiStyle.FONT_BASE);
            cb.setSelected(selectedTags.contains(t));
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            cb.addActionListener(e -> {
                if (cb.isSelected()) selectedTags.add(t);
                else selectedTags.remove(t);
            });
            listPanel.add(cb);
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    private void doSave() {
        String[] tags = selectedTags.toArray(new String[0]);
        saveBtn.setEnabled(false);
        saveBtn.setText("Сохранение…");

        new SwingWorker<int[], Void>() {
            @Override protected int[] doInBackground() {
                int ok = 0, fail = 0;
                SetTagsService setTags = new SetTagsService(ctx);
                for (IssueService.IssueDto it : issues) {
                    try {
                        setTags.setTags(ctx.getProject(), it.key(), tags);
                        ok++;
                    } catch (Exception e) {
                        fail++;
                    }
                }
                return new int[]{ok, fail};
            }
            @Override protected void done() {
                saveBtn.setEnabled(true);
                saveBtn.setText("Сохранить");
                try {
                    int[] r = get();
                    UiHelpers.showToast(PerFileTagsDialog.this,
                            "Теги сохранены: OK " + r[0] + ", ошибок " + r[1], r[1] > 0);
                    dispose();
                    if (onDone != null) onDone.run();
                } catch (Exception e) {
                    UiHelpers.showError(PerFileTagsDialog.this,
                            "Ошибка сохранения: " + e.getMessage());
                }
            }
        }.execute();
    }
}