package ru.miacomsoft.sonar_gui.tabs.report.dialogs;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.ProjectsService;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class MultiProjectSelectDialog extends JDialog {

    public record Result(List<String> projects, boolean createJira) {}

    private final GuiContext ctx;
    private final Consumer<Result> onSelected;

    private final JTextArea searchArea = new JTextArea(4, 40);
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> projectList = new JList<>(listModel);
    private final Set<String> selectedProjects = new LinkedHashSet<>();
    private final List<String> allProjects = new ArrayList<>();
    private final JButton applyBtn;
    private final JLabel countLbl = UiHelpers.dimLabel("");
    private final JCheckBox createJiraCheckbox; // НОВОЕ ПОЛЕ

    public MultiProjectSelectDialog(GuiContext ctx, Consumer<Result> onSelected) {
        super((Frame) null, "Выбор проектов для сводного отчета", true);
        this.ctx = ctx;
        this.onSelected = onSelected;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(760, 800);
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

        searchArea.setFont(GuiStyle.FONT_MONO);
        searchArea.setBackground(GuiStyle.BG);
        searchArea.setForeground(GuiStyle.TEXT);
        searchArea.setCaretColor(GuiStyle.TEXT);
        searchArea.setLineWrap(true);
        searchArea.setWrapStyleWord(false);
        searchArea.setToolTipText(
                "Многострочный фильтр.\n" +
                        "• Каждая строка — отдельный независимый запрос (OR между строками).\n" +
                        "• Внутри одной строки фрагменты разделяются пробелом или ';'.\n" +
                        "• Все фрагменты внутри одной строки должны присутствовать в имени проекта (AND).\n" +
                        "Пример: 'MEDMIS mobile' найдёт проекты, содержащие и MEDMIS, и mobile.\n" +
                        "Можно вставить список проектов — по одному на строку.");

        searchArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { filterList(); }
            public void removeUpdate(DocumentEvent e)  { filterList(); }
            public void changedUpdate(DocumentEvent e) { filterList(); }
        });

        JScrollPane searchScroll = new JScrollPane(searchArea);
        searchScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(GuiStyle.BORDER),
                "Фильтр (разделители: новая строка, пробел, ';')",
                0, 0, GuiStyle.FONT_BOLD, GuiStyle.TEXT_DIM));
        searchScroll.setPreferredSize(new Dimension(0, 110));
        searchScroll.getViewport().setBackground(GuiStyle.BG);
        center.add(searchScroll, BorderLayout.NORTH);

        JPanel bulkPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bulkPanel.setOpaque(false);

        JButton selectAllFilteredBtn = UiHelpers.secondaryButton("✔ Выбрать все (отфильтрованные)");
        selectAllFilteredBtn.addActionListener(e -> {
            if (listModel.isEmpty()) return;
            for (int i = 0; i < listModel.size(); i++) {
                selectedProjects.add(listModel.getElementAt(i));
            }
            projectList.repaint();
            updateUi();
        });
        bulkPanel.add(selectAllFilteredBtn);

        JButton clearAllBtn = UiHelpers.dangerButton("✖ Снять все выделение");
        clearAllBtn.addActionListener(e -> {
            selectedProjects.clear();
            projectList.repaint();
            updateUi();
        });
        bulkPanel.add(clearAllBtn);

        center.add(bulkPanel, BorderLayout.SOUTH);

        projectList.setBackground(GuiStyle.BG2);
        projectList.setForeground(GuiStyle.TEXT);
        projectList.setFont(GuiStyle.FONT_MONO);
        projectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        projectList.setCellRenderer(new CheckboxListCellRenderer());

        projectList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int index = projectList.locationToIndex(e.getPoint());
                if (index >= 0) {
                    toggleIndex(index);
                }
            }
        });

        InputMap im = projectList.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = projectList.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleSelection");
        am.put("toggleSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int index = projectList.getSelectedIndex();
                if (index >= 0) {
                    toggleIndex(index);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(projectList);
        scroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        scroll.getViewport().setBackground(GuiStyle.BG2);
        center.add(scroll, BorderLayout.CENTER);

        root.add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);

        JPanel footerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        footerLeft.setOpaque(false);
        footerLeft.add(countLbl);
        footer.add(footerLeft, BorderLayout.WEST);

        JPanel footerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footerRight.setOpaque(false);

        // --- ДОБАВЛЕН ЧЕКБОКС JIRA ---
        createJiraCheckbox = new JCheckBox("Создать задачи в Jira");
        createJiraCheckbox.setOpaque(false);
        createJiraCheckbox.setForeground(GuiStyle.TEXT);
        createJiraCheckbox.setFont(GuiStyle.FONT_BOLD);
        footerRight.add(createJiraCheckbox);

        JButton cancelBtn = UiHelpers.secondaryButton("Отмена");
        cancelBtn.addActionListener(e -> dispose());
        footerRight.add(cancelBtn);

        applyBtn = UiHelpers.primaryButton("Построить отчет");
        applyBtn.setEnabled(false);
        applyBtn.addActionListener(e -> {
            if (!selectedProjects.isEmpty()) {
                dispose();
                if (onSelected != null) {
                    onSelected.accept(new Result(new ArrayList<>(selectedProjects), createJiraCheckbox.isSelected()));
                }
            }
        });
        footerRight.add(applyBtn);

        footer.add(footerRight, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        loadProjects();
    }

    private void toggleIndex(int index) {
        String proj = listModel.getElementAt(index);
        if (selectedProjects.contains(proj)) {
            selectedProjects.remove(proj);
        } else {
            selectedProjects.add(proj);
        }
        projectList.repaint();
        updateUi();
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
                    UiHelpers.showError(MultiProjectSelectDialog.this,
                            "Ошибка загрузки проектов: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void filterList() {
        String text = searchArea.getText();
        if (text == null) text = "";

        listModel.clear();

        if (text.trim().isEmpty()) {
            for (String proj : allProjects) listModel.addElement(proj);
        } else {
            String[] lines = text.split("\\r?\\n");
            for (String proj : allProjects) {
                String projLower = proj.toLowerCase();
                boolean anyLineMatches = false;

                for (String line : lines) {
                    String lineTrimmed = line.trim();
                    if (lineTrimmed.isEmpty()) continue;

                    String[] fragments = lineTrimmed.toLowerCase().split("[\\s;]+");
                    boolean allFragmentsMatch = true;
                    int validCount = 0;

                    for (String frag : fragments) {
                        if (frag.isEmpty()) continue;
                        validCount++;
                        if (!projLower.contains(frag)) {
                            allFragmentsMatch = false;
                            break;
                        }
                    }

                    if (allFragmentsMatch && validCount > 0) {
                        anyLineMatches = true;
                        break;
                    }
                }

                if (anyLineMatches) {
                    listModel.addElement(proj);
                }
            }
        }
        updateUi();
    }

    private void updateUi() {
        applyBtn.setEnabled(!selectedProjects.isEmpty());
        countLbl.setText("Показано: " + listModel.size() + " из " + allProjects.size()
                + " · выбрано: " + selectedProjects.size());
    }

    private class CheckboxListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
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