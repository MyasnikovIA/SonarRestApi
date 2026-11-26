package ru.miacomsoft.sonar_gui;

import ru.miacomsoft.sonar_gui.backend.BootstrapFiles;
import ru.miacomsoft.sonar_gui.backend.PromptTemplate;
import ru.miacomsoft.sonar_gui.backend.ServerState;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaConfig;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.frame.MainFrame;

import javax.swing.*;
import java.nio.file.Path;

/**
 * Точка входа GUI-клиента Sonar.
 *
 * Работает автономно:
 *   1) готовит session.properties и prompts/*.txt (BootstrapFiles);
 *   2) читает session.properties и подключается к SonarQube напрямую;
 *   3) читает ollama.properties;
 *   4) строит GuiContext с ServerState + OllamaConfig + PromptTemplate;
 *   5) открывает MainFrame.
 *
 * Никакого промежуточного HTTP-сервера (localhost:6060) не требуется.
 */
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

        // 1) Bootstrap: гарантируем наличие session.properties и prompts/*.txt
        BootstrapFiles.ensureAll(sessionPath, promptsDir);

        // 2) Backend: ServerState читает session.properties и создаёт SonarClient
        ServerState state = new ServerState(sessionPath);

        // 3) Ollama + промпты
        OllamaConfig ollama = new OllamaConfig(ollamaPath);
        PromptTemplate template = new PromptTemplate(promptsDir);

        System.out.println("[main] session:  " + sessionPath);
        System.out.println("[main] prompts:  " + promptsDir);
        System.out.println("[main] ollama:   " + ollamaPath);
        System.out.println("[main] sessionReady: " + state.isSessionReady());

        GuiContext ctx = new GuiContext(state, ollama, template, sessionPath, promptsDir);

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            MainFrame frame = new MainFrame(ctx);
            frame.setVisible(true);
        });
    }
}