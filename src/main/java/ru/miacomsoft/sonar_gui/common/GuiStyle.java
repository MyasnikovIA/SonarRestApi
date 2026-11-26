package ru.miacomsoft.sonar_gui.common;

import java.awt.Color;
import java.awt.Font;

/**
 * Палитра и шрифты — синхронизированы с web/style.css.
 */
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
}