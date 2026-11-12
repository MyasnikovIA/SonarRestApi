package ru.miacomsoft.sonar.server;

import com.sun.net.httpserver.HttpServer;
import ru.miacomsoft.sonar.server.handlers.*;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ApiServer {

    private ApiServer() {}

    public static void start(String host, int port, int threads, ServerState state)
            throws Exception {

        Path promptsDir = resolvePromptsDir();
        PromptTemplate template = new PromptTemplate(promptsDir);

        System.out.println("[ApiServer] prompts dir: " + promptsDir);
        List<String> availablePrompts = template.listAvailable();
        System.out.println("[ApiServer] available prompts: "
                + (availablePrompts.isEmpty() ? "<none>" : String.join(", ", availablePrompts)));
        if (availablePrompts.isEmpty()) {
            System.err.println("[ApiServer] ВНИМАНИЕ: в каталоге промптов нет ни одного .txt. "
                    + "Положите туда status-comment.txt, bulk-status-comment.txt, "
                    + "auto-resolve.txt, analyze-issue.txt.");
        }

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
        server.createContext("/api/projects",  new ProjectsHandler(state));

        // НОВЫЙ ЭНДПОИНТ
        server.createContext("/api/team_mapping", new TeamMappingHandler(state));

        server.createContext("/api/tags",      new TagsHandler(state));
        server.createContext("/api/set_tags",  new SetTagsHandler(state));

        server.createContext("/api/mark",      new MarkHandler(state));
        server.createContext("/api/cancel",    new CancelHandler(state));

        server.createContext("/api/prompt/build", new BuildPromptHandler(state, template));
        server.createContext("/api/prompt/list",  new ListPromptsHandler(state, template));

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
        System.out.println(" Prompts dir: " + promptsDir);
        System.out.println(" Prompts:     " + (availablePrompts.isEmpty()
                ? "<none>" : String.join(", ", availablePrompts)));
        System.out.println(" Endpoints:");
        System.out.println("   GET  /api/prompt/build?name=<n>&project=<p>&issueKey=<k>");
        System.out.println("   GET  /api/prompt/list");
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

    private static Path resolvePromptsDir() {
        String sys = System.getProperty("sonar.prompts");
        if (sys != null && !sys.isBlank()) {
            Path p = Path.of(sys).toAbsolutePath().normalize();
            try { Files.createDirectories(p); } catch (Exception ignored) {}
            return p;
        }
        try {
            var cs = ApiServer.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path loc = Path.of(cs.getLocation().toURI()).toAbsolutePath();
                Path dir = Files.isDirectory(loc) ? loc : loc.getParent();
                if (dir != null) {
                    Path beside = dir.resolve("prompts");
                    if (Files.isDirectory(beside)) return beside;
                    Path parent = dir.resolve("..").resolve("prompts").normalize();
                    if (Files.isDirectory(parent)) return parent;
                }
            }
        } catch (Exception ignored) {}

        Path cwd = Path.of("prompts").toAbsolutePath().normalize();
        try { Files.createDirectories(cwd); } catch (Exception ignored) {}
        return cwd;
    }
}