package ru.miacomsoft.sonar_gui;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.frame.MainFrame;

import javax.swing.*;

/**
 * Точка входа GUI-клиента Sonar REST API.
 *
 * По умолчанию подключается к локальному серверу http://localhost:6060
 * (тот самый Java-сервер из соседнего пакета ru.miacomsoft.sonar.server).
 * Адрес можно переопределить через первый аргумент командной строки или
 * переменную окружения SONAR_API_URL.
 */
public class Main {

    private static final String DEFAULT_BASE_URL = "http://localhost:6060";

    public static void main(String[] args) {
        String baseUrl = resolveBaseUrl(args);

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to default LAF
            }

            GuiContext ctx = new GuiContext(baseUrl);
            MainFrame frame = new MainFrame(ctx);
            frame.setVisible(true);
        });
    }

    private static String resolveBaseUrl(String[] args) {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return args[0].trim();
        }
        String env = System.getenv("SONAR_API_URL");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return DEFAULT_BASE_URL;
    }
}