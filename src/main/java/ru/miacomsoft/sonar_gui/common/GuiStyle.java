package ru.miacomsoft.sonar_gui.common;

import javax.swing.*;
import java.awt.*;

public final class GuiStyle {

    public static final Color BG        = new Color(0x0d1117);
    public static final Color BG2       = new Color(0x161b22);
    public static final Color BG3       = new Color(0x1f2630);
    public static final Color BORDER    = new Color(0x30363d);
    public static final Color TEXT      = new Color(0xe6edf3);
    public static final Color TEXT_DIM  = new Color(0x8b949e);
    public static final Color ACCENT    = new Color(0x58a6ff);
    public static final Color ACCENT2   = new Color(0x1f6feb);
    public static final Color OK        = new Color(0x3fb950);
    public static final Color WARN      = new Color(0xd29922);
    public static final Color ERR       = new Color(0xf85149);

    public static final Font FONT_BASE  = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    public static final Font FONT_BOLD  = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    public static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 16);
    public static final Font FONT_MONO  = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private GuiStyle() {}

    public static Color severityColor(String sev) {
        if (sev == null) return TEXT;
        return switch (sev.toUpperCase()) {
            case "BLOCKER", "HIGH"   -> ERR;
            case "CRITICAL", "MAJOR",
                 "MEDIUM"            -> WARN;
            case "MINOR", "LOW"      -> ACCENT;
            default                  -> TEXT_DIM;
        };
    }

    public static void applyGlobalTheme() {
        UIManager.put("control", BG2);
        UIManager.put("info", BG2);
        UIManager.put("nimbusBase", BG3);
        UIManager.put("nimbusAlertYellow", WARN);
        UIManager.put("nimbusDisabledText", TEXT_DIM);
        UIManager.put("nimbusFocus", ACCENT);
        UIManager.put("nimbusGreen", OK);
        UIManager.put("nimbusInfoBlue", ACCENT);
        UIManager.put("nimbusLightBackground", BG);
        UIManager.put("nimbusOrange", WARN);
        UIManager.put("nimbusRed", ERR);
        UIManager.put("nimbusSelectedText", Color.WHITE);
        UIManager.put("nimbusSelectionBackground", ACCENT2);
        UIManager.put("text", TEXT);

        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("OptionPane.messageForeground", TEXT);

        // Таблицы
        UIManager.put("Table.background", BG2);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.selectionBackground", ACCENT2);
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("TableHeader.background", BG3);
        UIManager.put("TableHeader.foreground", TEXT);
        UIManager.put("TableHeader.cellForeground", TEXT);

        // Выпадающие списки
        UIManager.put("ComboBox.background", BG);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", ACCENT2);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("ComboBox.buttonBackground", BG3);
        UIManager.put("ComboBox.buttonShadow", BORDER);
        UIManager.put("ComboBox.buttonDarkShadow", BORDER);
        UIManager.put("ComboBox.buttonHighlight", BG3);
        UIManager.put("ComboBox.popupBackground", BG); // <--- НОВОЕ

        // Вкладки
        UIManager.put("TabbedPane.background", BG2); // <--- НОВОЕ
        UIManager.put("TabbedPane.foreground", TEXT); // <--- НОВОЕ
        UIManager.put("TabbedPane.selected", BG); // <--- НОВОЕ
        UIManager.put("TabbedPane.selectedForeground", ACCENT); // <--- НОВОЕ
        UIManager.put("TabbedPane.contentAreaColor", BG); // <--- НОВОЕ
        UIManager.put("TabbedPane.focus", ACCENT); // <--- НОВОЕ

        // Текстовые поля
        UIManager.put("TextField.background", BG);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("TextArea.background", BG2);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("TextArea.caretForeground", TEXT);
        UIManager.put("PasswordField.background", BG);
        UIManager.put("PasswordField.foreground", TEXT);

        // Скроллы
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("Viewport.background", BG);

        // Меню
        UIManager.put("MenuBar.background", BG2);
        UIManager.put("MenuBar.foreground", TEXT);
        UIManager.put("Menu.background", BG2);
        UIManager.put("Menu.foreground", TEXT);
        UIManager.put("Menu.selectionBackground", ACCENT2);
        UIManager.put("Menu.selectionForeground", Color.WHITE);
        UIManager.put("MenuItem.background", BG2);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("MenuItem.selectionBackground", ACCENT2);
        UIManager.put("MenuItem.selectionForeground", Color.WHITE);
        UIManager.put("PopupMenu.background", BG2);
        UIManager.put("PopupMenu.foreground", TEXT);

        UIManager.put("CheckBox.background", BG);
        UIManager.put("CheckBox.foreground", TEXT);

        UIManager.put("ScrollBar.background", BG2);
        UIManager.put("ScrollBar.thumb", BG3);
        UIManager.put("ScrollBar.track", BG);
    }
}