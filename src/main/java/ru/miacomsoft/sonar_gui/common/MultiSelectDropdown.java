package ru.miacomsoft.sonar_gui.common;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MultiSelectDropdown extends JPanel {

    private final String label;
    private final JButton button;
    private final JPopupMenu popup;
    private final JTextField searchField = UiHelpers.darkField("");
    private final JPanel listPanel = new JPanel();
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<String> allItems = new ArrayList<>();
    private final Runnable onChange;

    public MultiSelectDropdown(String label, Runnable onChange) {
        this.label = label;
        this.onChange = onChange;

        setLayout(new BorderLayout());
        setOpaque(false);

        button = UiHelpers.secondaryButton(label + " (0)");
        button.addActionListener(e -> togglePopup());
        add(button, BorderLayout.CENTER);

        popup = new JPopupMenu();
        popup.setLayout(new BorderLayout());

        JPanel content = new JPanel(new BorderLayout(4, 4));
        content.setBackground(GuiStyle.BG);
        content.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        content.setPreferredSize(new Dimension(320, 340));

        searchField.setToolTipText("Поиск...");
        searchField.getDocument().addDocumentListener(new SimpleDocListener(this::rebuildList));
        content.add(searchField, BorderLayout.NORTH);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(GuiStyle.BG2);

        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(GuiStyle.BG2);
        content.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        footer.setBackground(GuiStyle.BG);
        JButton clear = UiHelpers.secondaryButton("Сбросить");
        clear.addActionListener(e -> { selected.clear(); rebuildList(); refreshButton(); fireChange(); });
        JButton close = UiHelpers.primaryButton("Готово");
        close.addActionListener(e -> popup.setVisible(false));
        footer.add(clear);
        footer.add(close);
        content.add(footer, BorderLayout.SOUTH);

        popup.add(content, BorderLayout.CENTER);
    }

    public void setItems(List<String> items) {
        allItems.clear();
        allItems.addAll(items);
        // удаляем из selected то, чего больше нет
        selected.retainAll(allItems);
        rebuildList();
        refreshButton();
    }

    public void setSelected(String... items) {
        selected.clear();
        for (String t : items) {
            if (allItems.contains(t)) selected.add(t);
        }
        rebuildList();
        refreshButton();
    }

    public void setSelected(Set<String> items) {
        selected.clear();
        for (String t : items) {
            if (allItems.contains(t)) selected.add(t);
        }
        rebuildList();
        refreshButton();
    }

    public Set<String> getSelected() {
        return new LinkedHashSet<>(selected);
    }

    /** Возвращает выбранные значения через запятую. Если пусто — "ALL". */
    public String getSelectedCsv() {
        if (selected.isEmpty() || selected.contains("ALL")) return "ALL";
        return String.join(",", selected);
    }

    public void clearSelection() {
        selected.clear();
        rebuildList();
        refreshButton();
        fireChange();
    }

    private void togglePopup() {
        popup.show(button, 0, button.getHeight());
    }

    private void rebuildList() {
        String q = searchField.getText().trim().toLowerCase();
        listPanel.removeAll();
        for (String item : allItems) {
            if (!q.isEmpty() && !item.toLowerCase().contains(q)) continue;
            JCheckBox cb = new JCheckBox(item);
            cb.setOpaque(false);
            cb.setForeground(GuiStyle.TEXT);
            cb.setFont(GuiStyle.FONT_BASE);
            cb.setSelected(selected.contains(item));
            cb.addActionListener(e -> {
                if (cb.isSelected()) selected.add(item);
                else selected.remove(item);
                refreshButton();
                fireChange();
            });
            listPanel.add(cb);
        }
        if (listPanel.getComponentCount() == 0) {
            listPanel.add(UiHelpers.dimLabel("— нет —"));
        }
        listPanel.revalidate();
        listPanel.repaint();
        popup.pack();
    }

    private void refreshButton() {
        button.setText(label + " (" + selected.size() + ")");
    }

    private void fireChange() {
        if (onChange != null) onChange.run();
    }

    private static class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable cb;
        SimpleDocListener(Runnable cb) { this.cb = cb; }
        public void insertUpdate(javax.swing.event.DocumentEvent e)  { cb.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e)  { cb.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { cb.run(); }
    }
}