package ru.miacomsoft.sonar_gui.common;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Утилиты для повторяющихся UI-операций: тёмные кнопки, поля, тосты,
 * обрезка строк, HTML-экранирование.
 */
public final class UiHelpers {

    private UiHelpers() {}

    // ============ стилизованные компоненты ============

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

    // ============ тост ============

    /**
     * Показывает всплывающее уведомление в правом нижнем углу экрана.
     * Автоматически закрывается через 3.5 секунды.
     */
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

    // ============ утилиты строк ============

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

    /** Возвращает строку, безопасную для HTML-рендеринга в JLabel. */
    public static String wrapHtml(String s) {
        return "<html>" + escHtml(s) + "</html>";
    }

    // ============ модальные диалоги ============

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

    /** Универсальный «изолирующий» контейнер с отступами. */
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