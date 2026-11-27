package ru.miacomsoft.sonar_gui;

import ru.miacomsoft.sonar_gui.backend.BootstrapFiles;
import ru.miacomsoft.sonar_gui.backend.PromptTemplate;
import ru.miacomsoft.sonar_gui.backend.ServerState;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaConfig;
import ru.miacomsoft.sonar_gui.common.DarkTitleBarUtil;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.frame.MainFrame;
import ru.miacomsoft.sonar_gui.frame.SettingsDialog;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    private static final String DEFAULT_SETTINGS = "app.properties";

    public static void main(String[] args) {
        String settingsArg = DEFAULT_SETTINGS;

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--settings=")) {
                settingsArg = args[i].substring("--settings=".length());
            } else if (args[i].startsWith("--session=")) { // Обратная совместимость
                settingsArg = args[i].substring("--session=".length());
            }
        }

        Path settingsPath = Path.of(settingsArg).toAbsolutePath().normalize();
        Path promptsDir  = BootstrapFiles.resolvePromptsDir();

        // Проверяем, существует ли файл ДО bootstrap (для первого запуска)
        boolean isFirstRun = !Files.exists(settingsPath);

        // === Bootstrap: создать ВСЕ конфиги по умолчанию при первом запуске ===
        BootstrapFiles.ensureAll(settingsPath, promptsDir);

        ServerState  state    = new ServerState(settingsPath);
        OllamaConfig ollama   = new OllamaConfig(settingsPath);
        PromptTemplate template = new PromptTemplate(promptsDir);

        GuiContext ctx = new GuiContext(state, ollama, template, settingsPath, promptsDir);

        SwingUtilities.invokeLater(() -> {
            try {
                // Кросс-платформенный LAF, чтобы тёмная тема работала стабильно.
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {}

            GuiStyle.applyGlobalTheme();

            MainFrame frame = new MainFrame(ctx);
            DarkTitleBarUtil.apply(frame);
            frame.setVisible(true);

            // Если это первый запуск — открываем настройки
            if (isFirstRun) {
                SwingUtilities.invokeLater(() -> {
                    SettingsDialog dialog = new SettingsDialog(ctx, () -> {
                        System.out.println("[Main] Первичная настройка завершена.");
                        // Можно перезагрузить активную вкладку, если нужно
                    });
                    dialog.setVisible(true);
                });
            }
        });
    }
}