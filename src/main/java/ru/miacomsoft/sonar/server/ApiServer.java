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

        server.createContext("/api/session",        new SessionHandler(state));
        server.createContext("/api/session/reload", new SessionReloadHandler(state));
        server.createContext("/api/refresh",        new RefreshHandler(state));
        server.createContext("/api/refresh/issue",  new RefreshIssueHandler(state));
        server.createContext("/api/status",         new StatusHandler(state));

        server.createContext("/api/health",    new HealthHandler(state));
        server.createContext("/api/measures",  new MeasuresHandler(state));
        server.createContext("/api/metadata",  new MetadataHandler(state));
        server.createContext("/api/accepted",  new AcceptedHandler(state));
        server.createContext("/api/summary",   new SummaryHandler(state));
        server.createContext("/api/matrix",    new MatrixHandler(state));
        server.createContext("/api/perfile",   new PerFileHandler(state));
        server.createContext("/api/details",   new DetailsHandler(state));
        server.createContext("/api/issues",    new IssuesHandler(state));
        server.createContext("/api/keys",      new KeysHandler(state));
        server.createContext("/api/snippet",   new SnippetHandler(state));
        server.createContext("/api/changelog", new ChangelogHandler(state));

        // НОВОЕ: Эндпоинты для работы с тегами
        server.createContext("/api/tags",      new TagsHandler(state));
        server.createContext("/api/set_tags",  new SetTagsHandler(state));

        server.createContext("/api/mark",      new MarkHandler(state));
        server.createContext("/api/cancel",    new CancelHandler(state));

        Path www = resolveWwwDir();
        if (www != null) {
            server.createContext("/", new StaticHandler(www));
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        server.setExecutor(pool);
        server.start();

        System.out.println("===================================================");
        System.out.println(" Sonar REST API server started");
        System.out.println(" Bind:        " + host + ":" + port);
        System.out.println(" Endpoints:");
        System.out.println("   GET  /api/tags?q=");
        System.out.println("   POST /api/set_tags?keys=&tags=");
        System.out.println("   GET  /api/changelog?project=&issueKey=");
        System.out.println("===================================================");
    }

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