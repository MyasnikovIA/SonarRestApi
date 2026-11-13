package ru.miacomsoft.sonar;

import ru.miacomsoft.sonar.server.ApiServer;
import ru.miacomsoft.sonar.server.BootstrapFiles;
import ru.miacomsoft.sonar.server.Console;
import ru.miacomsoft.sonar.server.OllamaConfig;
import ru.miacomsoft.sonar.server.ServerState;

import java.nio.file.Path;

public class Main {

    private static final int DEFAULT_PORT = 6060;

    public static void main(String[] args) throws Exception {
        String host = "0.0.0.0";
        int port = DEFAULT_PORT;
        int threads = 8;
        int batchSize = SonarBulkApi.DEFAULT_BATCH_SIZE;
        long delayMs = SonarBulkApi.DEFAULT_DELAY_MS;
        String sessionFile = "session.properties";
        String project = System.getenv().getOrDefault("SONAR_PROJECT", "");

        // ИЗМЕНЕНИЕ: Отключаем сборку снимка при старте, так как перешли на прямые запросы к SonarQube
        boolean warmup = false;

        boolean asyncWarmup = false;
        boolean warmupSnippets = false; // Сниппеты тоже не кэшируем глобально
        int snippetLimit = 0;
        boolean console = true;

        String ollamaConfigFile  = "ollama.properties";
        String consoleConfigFile = "console.properties";

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--host="))                host = a.substring("--host=".length());
            else if (a.startsWith("--port="))           port = Integer.parseInt(a.substring("--port=".length()));
            else if (a.startsWith("--threads="))        threads = Integer.parseInt(a.substring("--threads=".length()));
            else if (a.startsWith("--batch="))          batchSize = Integer.parseInt(a.substring("--batch=".length()));
            else if (a.startsWith("--delay="))          delayMs = Long.parseLong(a.substring("--delay=".length()));
            else if (a.startsWith("--session="))        sessionFile = a.substring("--session=".length());
            else if (a.startsWith("--session-properties=")) sessionFile = a.substring("--session-properties=".length());
            else if (a.startsWith("--project="))        project = a.substring("--project=".length());
            else if (a.startsWith("--snippet-limit="))  snippetLimit = Integer.parseInt(a.substring("--snippet-limit=".length()));
            else if (a.startsWith("--ollama-config="))  ollamaConfigFile = a.substring("--ollama-config=".length());
            else if (a.startsWith("--console-config=")) consoleConfigFile = a.substring("--console-config=".length());

                // Если пользователь явно захочет собрать снимок, он может передать --warmup
            else if ("--warmup".equals(a))              warmup = true;
            else if ("--no-warmup".equals(a))           warmup = false;
            else if ("--async-warmup".equals(a))        asyncWarmup = true;
            else if ("--snippets".equals(a))            warmupSnippets = true;
            else if ("--no-snippets".equals(a))         warmupSnippets = false;
            else if ("--no-console".equals(a))          console = false;
            else if (i == 0)                            sessionFile = a;
            else if (i == 1)                            project = a;
        }

        // ---------- BOOTSTRAP ----------
        Path sessionPath = Path.of(sessionFile);
        Path promptsDir  = BootstrapFiles.resolvePromptsDir();
        BootstrapFiles.ensureAll(sessionPath, promptsDir);

        ServerState state = new ServerState(
                sessionPath, project, batchSize, delayMs,
                warmupSnippets, snippetLimit);

        OllamaConfig ollamaConfig = new OllamaConfig(Path.of(ollamaConfigFile));
        System.out.println("[main] Ollama config: " + ollamaConfig.getConfigFile()
                + " (url=" + ollamaConfig.getUrl()
                + ", model=" + ollamaConfig.getModel() + ")");

        if (console) {
            new Console(state, ollamaConfig, Path.of(consoleConfigFile)).start();
            System.out.println("[main] Console state: " + consoleConfigFile);
        } else {
            System.out.println("[main] Console disabled (--no-console)");
        }

        if (warmup) {
            if (!state.isSessionReady()) {
                System.out.println("[Warmup] SKIP: сессия не загружена");
            } else if (project == null || project.isEmpty()) {
                System.out.println("[Warmup] SKIP: project не задан.");
            } else if (asyncWarmup) {
                System.out.println("[Warmup] async: запускаю пересбор снимка в фоне");
                state.buildSnapshotAsync(project);
            } else {
                System.out.println("[Warmup] sync: собираю снимок для " + project + " ...");
                try {
                    state.buildSnapshotSync(project);
                } catch (Exception e) {
                    System.err.println("[Warmup] FAILED: " + e.getMessage());
                }
            }
        } else {
            System.out.println("[Warmup] отключён (--no-warmup). Работаем напрямую через SonarQube API.");
        }

        ApiServer.start(host, port, threads, state);
        Thread.currentThread().join();
    }
}