package ru.miacomsoft.sonar_gui.common;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

import java.awt.Window;

/**
 * Утилита для включения темной темы нативной заголовочной панели окна
 * (title bar) в Windows 10 / 11.
 *
 * Работает через вызов WinAPI DwmSetWindowAttribute из dwmapi.dll.
 * Требует зависимость JNA (jna + jna-platform).
 */
public final class DarkTitleBarUtil {

    private DarkTitleBarUtil() {}

    public interface Dwmapi extends Library {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class);

        int DwmSetWindowAttribute(HWND hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);
    }

    /**
     * Применяет темную тему к нативной рамке окна.
     * Вызывать после создания окна, лучше всего перед setVisible(true).
     */
    public static void apply(Window window) {
        if (window == null) return;

        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("windows")) return; // Работает только на Windows

        try {
            HWND hwnd = getHWND(window);
            if (hwnd == null) return;

            // DWMWA_USE_IMMERSIVE_DARK_MODE = 20 (Windows 10 20H1+ / Windows 11)
            // DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19 (Windows 10 1809 - 1909)
            IntByReference darkMode = new IntByReference(1);
            int result = Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 20, darkMode, 4);
            if (result != 0) {
                Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 19, darkMode, 4);
            }
        } catch (Throwable ignored) {
            // Если JNA не подключена или что-то пошло не так — молча пропускаем.
        }
    }

    private static HWND getHWND(Window window) {
        try {
            // Используем JNA platform для получения HWND (работает на Java 9+)
            Class<?> clazz = Class.forName("com.sun.jna.platform.java.awt.JavaAWTUtils");
            java.lang.reflect.Method method = clazz.getMethod("getHWND", Window.class);
            return (HWND) method.invoke(null, window);
        } catch (Exception e) {
            return null;
        }
    }
}