package ru.miacomsoft.sonar_gui.common;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class UiHelpers {

    private UiHelpers() {}

    public static JButton primaryButton(String text) {
        return styleButton(text, GuiStyle.ACCENT2, Color.WHITE);
    }

    public static JButton secondaryButton(String text) {
        return styleButton(text, GuiStyle.BG3, GuiStyle.TEXT);
    }

    public static JButton dangerButton(String text) {
        return styleButton(text, new Color(0x7a2020), Color.WHITE);
    }

    public static JButton styleButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setFont(GuiStyle.FONT_BOLD);
        Border pad = BorderFactory.createEmptyBorder(6, 14, 6, 14);
        btn.setBorder(pad);
        btn.setOpaque(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public static JTextField darkField(String initial) {
        JTextField f = new JTextField(initial == null ? "" : initial);
        f.setBackground(GuiStyle.BG);
        f.setForeground(GuiStyle.TEXT);
        f.setCaretColor(GuiStyle.TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GuiStyle.BORDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        f.setFont(GuiStyle.FONT_BASE);
        return f;
    }

    public static JComboBox<String> darkCombo(String[] values, String selected) {
        JComboBox<String> cb = new JComboBox<>(values);
        if (selected != null) cb.setSelectedItem(selected);
        cb.setBackground(GuiStyle.BG);
        cb.setForeground(GuiStyle.TEXT);
        cb.setFont(GuiStyle.FONT_BASE);

        // Рендерер для выпадающего списка (обязательно setOpaque(true))
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setOpaque(true); // <--- БЕЗ ЭТОГО ФОН БУДЕТ БЕЛЫМ
                lbl.setBackground(isSelected ? GuiStyle.ACCENT2 : GuiStyle.BG);
                lbl.setForeground(isSelected ? Color.WHITE : GuiStyle.TEXT);
                return lbl;
            }
        });

        // Базовый UI, чтобы системный LAF не рисовал белый фон на самой кнопке
        cb.setUI(new BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton btn = super.createArrowButton();
                btn.setBackground(GuiStyle.BG3);
                btn.setForeground(GuiStyle.TEXT);
                return btn;
            }
        });

        return cb;
    }

    public static JLabel dimLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(GuiStyle.TEXT_DIM);
        lbl.setFont(GuiStyle.FONT_BASE);
        return lbl;
    }

    public static JLabel titleLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(GuiStyle.ACCENT);
        lbl.setFont(GuiStyle.FONT_TITLE);
        return lbl;
    }

    public static void showToast(Component parent, String message, boolean isError) {
        Window owner = (parent == null) ? null : SwingUtilities.getWindowAncestor(parent);
        JWindow window = new JWindow(owner);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(GuiStyle.BG3);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(isError ? GuiStyle.ERR : GuiStyle.ACCENT, 1),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));

        JLabel label = new JLabel(message == null ? "" : message);
        label.setForeground(GuiStyle.TEXT);
        label.setFont(GuiStyle.FONT_BASE);
        panel.add(label, BorderLayout.CENTER);

        window.setContentPane(panel);
        window.pack();

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        window.setLocation(screen.width - window.getWidth() - 30,
                screen.height - window.getHeight() - 60);
        window.setVisible(true);

        Timer timer = new Timer(3500, e -> window.dispose());
        timer.setRepeats(false);
        timer.start();
    }

    public static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static String wrapHtml(String s) {
        return "<html>" + escHtml(s) + "</html>";
    }

    public static void showError(Component parent, String message) {
        JOptionPane.showMessageDialog(parent,
                message == null ? "Неизвестная ошибка" : message,
                "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    public static boolean confirm(Component parent, String message) {
        int r = JOptionPane.showConfirmDialog(parent, message, "Подтверждение",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return r == JOptionPane.YES_OPTION;
    }

    public static JPanel paddedPanel(LayoutManager layout, int pad) {
        JPanel p = new JPanel(layout);
        p.setBackground(GuiStyle.BG);
        p.setBorder(BorderFactory.createEmptyBorder(pad, pad, pad, pad));
        return p;
    }

    public static List<String> csvToList(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String t : csv.split(",")) {
            String v = t.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }
}