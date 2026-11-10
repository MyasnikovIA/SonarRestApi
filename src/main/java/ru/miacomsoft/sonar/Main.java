package ru.miacomsoft.sonar;

import ru.miacomsoft.sonar.server.ApiServer;
import ru.miacomsoft.sonar.server.Console;
import ru.miacomsoft.sonar.server.ServerState;

import java.nio.file.Path;

/**
 * Точка входа.
 *
 * Без аргументов: REST на 0.0.0.0:6060 + Console, session.txt из CWD.
 *
 * Аргументы / system properties:
 *   --host=0.0.0.0            адрес REST (по умолчанию 0.0.0.0)
 *   --port=6060               порт REST (по умолчанию 6060)
 *   --threads=8               пул потоков HTTP
 *   --batch=500               размер батча для bulk_change
 *   --delay=500               пауза между батчами, мс
 *   --session=session.txt     путь к session.txt (иначе CWD/session.txt)
 *   --project=KEY             проект по умолчанию (иначе env SONAR_PROJECT)
 *   --snippet-limit=N         ограничить число префетченных сниппетов
 *   --no-warmup               не собирать снимок на старте
 *   --async-warmup            собирать снимок в фоне
 *   --no-snippets             не префетчить сниппеты в снимке
 *   --no-console              не запускать интерактивную консоль (демон)
 */
public class Main {

    private static final int DEFAULT_PORT = 6060;

    public static void main(String[] args) throws Exception {
        String host = "0.0.0.0";
        int port = DEFAULT_PORT;
        int threads = 8;
        int batchSize = SonarBulkApi.DEFAULT_BATCH_SIZE;
        long delayMs = SonarBulkApi.DEFAULT_DELAY_MS;
        String sessionFile = "session.txt";
        String project = System.getenv().getOrDefault("SONAR_PROJECT", "");
        boolean warmup = true;
        boolean asyncWarmup = false;
        boolean warmupSnippets = true;
        int snippetLimit = 0;
        boolean console = true;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--host="))                host = a.substring("--host=".length());
            else if (a.startsWith("--port="))           port = Integer.parseInt(a.substring("--port=".length()));
            else if (a.startsWith("--threads="))        threads = Integer.parseInt(a.substring("--threads=".length()));
            else if (a.startsWith("--batch="))          batchSize = Integer.parseInt(a.substring("--batch=".length()));
            else if (a.startsWith("--delay="))          delayMs = Long.parseLong(a.substring("--delay=".length()));
            else if (a.startsWith("--session="))        sessionFile = a.substring("--session=".length());
            else if (a.startsWith("--project="))        project = a.substring("--project=".length());
            else if (a.startsWith("--snippet-limit="))  snippetLimit = Integer.parseInt(a.substring("--snippet-limit=".length()));
            else if ("--no-warmup".equals(a))           warmup = false;
            else if ("--async-warmup".equals(a))        asyncWarmup = true;
            else if ("--no-snippets".equals(a))         warmupSnippets = false;
            else if ("--no-console".equals(a))          console = false;
            else if (i == 0)                            sessionFile = a;
            else if (i == 1)                            project = a;
        }

        ServerState state = new ServerState(
                Path.of(sessionFile), project, batchSize, delayMs,
                warmupSnippets, snippetLimit);

        // Консоль запускаем до прогрева, чтобы пользователь видел прогресс
        // и мог переключать project / запускать refresh вручную.
        if (console) {
            new Console(state).start();
        } else {
            System.out.println("[main] Console disabled (--no-console)");
        }

        // ===== прогрев снимка =====
        if (warmup) {
            if (!state.isSessionReady()) {
                System.out.println("[Warmup] SKIP: сессия не загружена");
            } else if (project == null || project.isEmpty()) {
                System.out.println("[Warmup] SKIP: project не задан. "
                        + "Задайте SONAR_PROJECT / --project=KEY или запустите 'refresh' в консоли / POST /api/refresh");
            } else if (asyncWarmup) {
                System.out.println("[Warmup] async: запускаю пересбор снимка в фоне");
                state.buildSnapshotAsync(project);
            } else {
                System.out.println("[Warmup] sync: собираю снимок для " + project + " ...");
                try {
                    state.buildSnapshotSync(project);
                } catch (Exception e) {
                    System.err.println("[Warmup] FAILED: " + e.getMessage()
                            + ". Сервер всё равно стартует; снимок можно пересобрать через 'refresh' или POST /api/refresh");
                }
            }
        } else {
            System.out.println("[Warmup] отключён (--no-warmup)");
        }

        ApiServer.start(host, port, threads, state);
        Thread.currentThread().join();
    }
}