package ru.miacomsoft.sonar_gui;

import ru.miacomsoft.sonar_gui.backend.BootstrapFiles;
import ru.miacomsoft.sonar_gui.backend.PromptTemplate;
import ru.miacomsoft.sonar_gui.backend.ServerState;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaConfig;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.frame.MainFrame;

import javax.swing.*;
import java.nio.file.Path;

public class Main {

    private static final String DEFAULT_SESSION = "session.properties";
    private static final String DEFAULT_OLLAMA  = "ollama.properties";

    public static void main(String[] args) {
        String sessionArg = DEFAULT_SESSION;
        String ollamaArg  = DEFAULT_OLLAMA;

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--session="))      sessionArg = args[i].substring("--session=".length());
            else if (args[i].startsWith("--ollama="))  ollamaArg  = args[i].substring("--ollama=".length());
        }

        Path sessionPath = Path.of(sessionArg).toAbsolutePath().normalize();
        Path promptsDir  = BootstrapFiles.resolvePromptsDir();
        Path ollamaPath  = Path.of(ollamaArg).toAbsolutePath().normalize();

        BootstrapFiles.ensureAll(sessionPath, promptsDir);

        ServerState state = new ServerState(sessionPath);
        OllamaConfig ollama = new OllamaConfig(ollamaPath);
        PromptTemplate template = new PromptTemplate(promptsDir);

        GuiContext ctx = new GuiContext(state, ollama, template, sessionPath, promptsDir);

        SwingUtilities.invokeLater(() -> {
            try {
                // ВАЖНО: системный LAF (Windows/macOS) игнорирует цвета для JComboBox и JTabbedPane.
                // Используем CrossPlatform (Metal), чтобы тёмная тема работала везде.
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {}

            GuiStyle.applyGlobalTheme();

            MainFrame frame = new MainFrame(ctx);
            frame.setVisible(true);
        });
    }
}