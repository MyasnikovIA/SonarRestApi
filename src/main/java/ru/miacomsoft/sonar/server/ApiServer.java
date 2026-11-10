package ru.miacomsoft.sonar.server;

import com.sun.net.httpserver.HttpServer;
import ru.miacomsoft.sonar.server.handlers.*;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ApiServer {

    private ApiServer() {}

    public static void start(String host, int port, int threads, ServerState state)
            throws Exception {

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        // Сессия
        server.createContext("/api/session",        new SessionHandler(state));
        server.createContext("/api/session/reload", new SessionReloadHandler(state));

        // Снимок
        server.createContext("/api/refresh",        new RefreshHandler(state));
        server.createContext("/api/refresh/issue",  new RefreshIssueHandler(state));
        server.createContext("/api/status",         new StatusHandler(state));

        // Данные
        server.createContext("/api/health",    new HealthHandler(state));
        server.createContext("/api/measures",  new MeasuresHandler(state));
        server.createContext("/api/summary",   new SummaryHandler(state));
        server.createContext("/api/matrix",    new MatrixHandler(state));
        server.createContext("/api/perfile",   new PerFileHandler(state));
        server.createContext("/api/details",   new DetailsHandler(state));
        server.createContext("/api/issues",    new IssuesHandler(state));
        server.createContext("/api/keys",      new KeysHandler(state));
        server.createContext("/api/snippet",   new SnippetHandler(state));

        // Изменения
        server.createContext("/api/mark",      new MarkHandler(state));
        server.createContext("/api/cancel",    new CancelHandler(state));

        // ==== Статика (WWW) ====
        Path www = resolveWwwDir();
        if (www != null) {
            server.createContext("/", new StaticHandler(www));
        }

        // ВАЖНО: регистрируем ВСЕ контексты до server.start() — иначе
        // первые запросы могут получить 404, пока контексты ещё не видны.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        server.setExecutor(pool);
        server.start();

        System.out.println("===================================================");
        System.out.println(" Sonar REST API server started");
        System.out.println(" Bind:        " + host + ":" + port);
        System.out.println(" Session:     " + state.getSessionFile()
                + (state.isSessionReady() ? " (loaded)" : " (NOT loaded)"));
        System.out.println(" Project def: " + (state.getDefaultProject().isEmpty()
                ? "<not set, pass ?project=... per request>"
                : state.getDefaultProject()));
        System.out.println(" Threads:     " + threads);
        System.out.println(" Endpoints:");
        System.out.println("   GET  /api/health");
        System.out.println("   GET  /api/status");
        System.out.println("   GET  /api/session");
        System.out.println("   PUT  /api/session                    (body = raw session text)");
        System.out.println("   POST /api/session/reload");
        System.out.println("   POST /api/refresh?project=&wait=true|false");
        System.out.println("   POST /api/refresh/issue?project=&key=");
        System.out.println("   GET  /api/measures?project=");
        System.out.println("   GET  /api/summary?project=&sev=&qual=");
        System.out.println("   GET  /api/matrix?project=&sev=&qual=");
        System.out.println("   GET  /api/perfile?project=&sev=&qual=");
        System.out.println("   GET  /api/details?project=&sev=&qual=&snippets=");
        System.out.println("   GET  /api/issues?project=&sev=&qual=");
        System.out.println("   GET  /api/keys?project=&sev=&qual=");
        System.out.println("   GET  /api/snippet?project=&issueKey=");
        System.out.println("   POST /api/mark?project=&key=&transition=&comment=&dryRun=");
        System.out.println("   POST /api/mark?project=&sev=&qual=&transition=&comment=&dryRun=");
        System.out.println("   POST /api/cancel?project=&key=&dryRun=");
        System.out.println("   POST /api/cancel?project=&sev=&qual=&dryRun=");
        System.out.println("   GET  /                              (WWW UI)");
        if (www == null) {
            System.out.println("   ⚠  WWW каталог не найден — UI недоступен");
        }
        System.out.println("===================================================");
    }

    /** Ищем каталог WWW: -Dsonar.www → рядом с JAR → target/../WWW → ./WWW. */
    private static Path resolveWwwDir() {
        String sys = System.getProperty("sonar.www");
        if (sys != null && !sys.isBlank()) {
            Path p = Path.of(sys).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
        }
        try {
            var cs = ApiServer.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path loc = Path.of(cs.getLocation().toURI()).toAbsolutePath();
                Path dir = Files.isDirectory(loc) ? loc : loc.getParent();
                if (dir != null) {
                    Path beside = dir.resolve("www");
                    if (Files.isDirectory(beside)) return beside;
                    Path parent = dir.resolve("..").resolve("www").normalize();
                    if (Files.isDirectory(parent)) return parent;
                }
            }
        } catch (Exception ignored) {}
        Path cwd = Path.of("www").toAbsolutePath().normalize();
        return Files.isDirectory(cwd) ? cwd : null;
    }
}