package ru.miacomsoft.sonar_gui.tabs.report.dialogs;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.ProjectsService;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MultiProjectSelectDialog extends JDialog {

    private final GuiContext ctx;
    private final Consumer<List<String>> onSelected;

    private final JTextField searchField = UiHelpers.darkField("");
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> projectList = new JList<>(listModel);
    private final Set<String> selectedProjects = new LinkedHashSet<>();
    private final List<String> allProjects = new ArrayList<>();
    private final JButton applyBtn;

    public MultiProjectSelectDialog(GuiContext ctx, Consumer<List<String>> onSelected) {
        super((Frame) null, "Выбор проектов для сводного отчета", true);
        this.ctx = ctx;
        this.onSelected = onSelected;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(600, 600);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JLabel header = new JLabel("Выберите проекты (отметьте галочками):");
        header.setForeground(GuiStyle.ACCENT);
        header.setFont(GuiStyle.FONT_TITLE);
        root.add(header, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setOpaque(false);

        searchField.setPreferredSize(new Dimension(0, 30));
        searchField.setToolTipText("Поиск по имени или ключу проекта");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { filterList(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { filterList(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterList(); }
        });
        center.add(searchField, BorderLayout.NORTH);

        projectList.setBackground(GuiStyle.BG2);
        projectList.setForeground(GuiStyle.TEXT);
        projectList.setFont(GuiStyle.FONT_MONO);
        projectList.setCellRenderer(new CheckboxListCellRenderer());
        projectList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = projectList.locationToIndex(e.getPoint());
                if (index >= 0) {
                    String proj = listModel.getElementAt(index);
                    if (selectedProjects.contains(proj)) {
                        selectedProjects.remove(proj);
                    } else {
                        selectedProjects.add(proj);
                    }
                    projectList.repaint();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(projectList);
        scroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        scroll.getViewport().setBackground(GuiStyle.BG2);
        center.add(scroll, BorderLayout.CENTER);

        root.add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);

        JButton cancelBtn = UiHelpers.secondaryButton("Отмена");
        cancelBtn.addActionListener(e -> dispose());
        footer.add(cancelBtn);

        applyBtn = UiHelpers.primaryButton("Построить отчет");
        applyBtn.setEnabled(false);
        applyBtn.addActionListener(e -> {
            if (!selectedProjects.isEmpty()) {
                dispose();
                if (onSelected != null) onSelected.accept(new ArrayList<>(selectedProjects));
            }
        });
        footer.add(applyBtn);

        root.add(footer, BorderLayout.SOUTH);

        loadProjects();
    }

    private void loadProjects() {
        new SwingWorker<List<ProjectsService.ProjectItem>, Void>() {
            @Override protected List<ProjectsService.ProjectItem> doInBackground() throws Exception {
                return new ProjectsService(ctx).fetchProjects();
            }
            @Override protected void done() {
                try {
                    List<ProjectsService.ProjectItem> list = get();
                    allProjects.clear();
                    for (ProjectsService.ProjectItem p : list) {
                        allProjects.add(p.key());
                    }
                    filterList();
                } catch (Exception e) {
                    UiHelpers.showError(MultiProjectSelectDialog.this, "Ошибка загрузки проектов: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void filterList() {
        String q = searchField.getText().trim().toLowerCase();
        listModel.clear();
        for (String proj : allProjects) {
            if (q.isEmpty() || proj.toLowerCase().contains(q)) {
                listModel.addElement(proj);
            }
        }
        applyBtn.setEnabled(!selectedProjects.isEmpty());
    }

    private class CheckboxListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JCheckBox cb = new JCheckBox(value.toString());
            cb.setOpaque(true);
            cb.setBackground(isSelected ? GuiStyle.ACCENT2 : GuiStyle.BG2);
            cb.setForeground(GuiStyle.TEXT);
            cb.setFont(GuiStyle.FONT_MONO);
            cb.setSelected(selectedProjects.contains(value.toString()));
            return cb;
        }
    }
}