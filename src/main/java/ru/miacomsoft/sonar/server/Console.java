package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.server.console.AutoCommands;
import ru.miacomsoft.sonar.server.console.ConsoleContext;
import ru.miacomsoft.sonar.server.console.ConsoleHelp;
import ru.miacomsoft.sonar.server.console.MarkCommands;
import ru.miacomsoft.sonar.server.console.OllamaCommands;
import ru.miacomsoft.sonar.server.console.PromptCommands;
import ru.miacomsoft.sonar.server.console.ReportCommands;
import ru.miacomsoft.sonar.server.console.SessionCommands;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Точка входа консольного интерфейса.
 *
 * Отвечает только за:
 *   - чтение stdin и диспетчеризацию команд;
 *   - базовые команды (help/status/health/project);
 *   - хранение общего {@link ConsoleContext}, через который работают остальные
 *     подкоманды из пакета {@code ru.miacomsoft.sonar.server.console}.
 *
 * Вся предметная логика вынесена в отдельные классы (Session/Report/Prompt/
 * Mark/Ollama/Auto Commands).
 */
public class Console {

    private final ConsoleContext ctx;
    private final Object consoleLock = new Object();
    private final Thread thread;

    private final SessionCommands sessionCmd;
    private final ReportCommands  reportCmd;
    private final PromptCommands  promptCmd;
    private final MarkCommands    markCmd;
    private final OllamaCommands  ollamaCmd;
    private final AutoCommands    autoCmd;

    public Console(ServerState state, OllamaConfig ollamaConfig, Path consoleStateFile) {
        this.ctx = new ConsoleContext(state, ollamaConfig, consoleStateFile);

        this.sessionCmd = new SessionCommands(ctx);
        this.reportCmd  = new ReportCommands(ctx);
        this.promptCmd  = new PromptCommands(ctx);
        this.markCmd    = new MarkCommands(ctx);
        this.ollamaCmd  = new OllamaCommands(ctx);
        this.autoCmd    = new AutoCommands(ctx, consoleLock);

        this.thread = new Thread(this::loop, "console");
        this.thread.setDaemon(true);
    }

    public Console(ServerState state, OllamaConfig ollamaConfig) {
        this(state, ollamaConfig, Path.of("console.properties"));
    }

    public void start() { thread.start(); }
    public void stop()  { ctx.setRunning(false); }

    // ==================== main loop ====================

    private void loop() {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println();
        System.out.println("===================================================");
        System.out.println(" Console ready. Type 'help' for commands.");
        System.out.println(" Prompts dir: " + ctx.template().getPromptsDir());
        System.out.println(" Console state: " + ctx.consoleStateFile());
        System.out.println("===================================================");
        try {
            while (ctx.isRunning()) {
                synchronized (consoleLock) {
                    System.out.print(prompt());
                    System.out.flush();
                }
                String line = r.readLine();
                if (line == null) { ctx.setRunning(false); break; }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    dispatch(line);
                } catch (Exception e) {
                    System.err.println("[console] " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[console] loop terminated: " + e.getMessage());
        }
        System.out.println("[console] stopped");
    }

    private String prompt() {
        String p = ctx.getProject() == null ? "" : ctx.getProject();
        String mode = ctx.isAutoRunning() ? "[auto]" : "";
        return "sonar" + mode + ":" + p + "> ";
    }

    // ==================== dispatch ====================

    private void dispatch(String line) throws Exception {
        String[] tokens = line.split("\\s+");
        String cmd = tokens[0].toLowerCase(Locale.ROOT);
        String[] a = Arrays.copyOfRange(tokens, 1, tokens.length);

        switch (cmd) {
            case "help", "?", "h"           -> ConsoleHelp.print();
            case "quit", "exit", "q"        -> {
                if (ctx.isAutoRunning()) {
                    System.out.println("[auto] сначала выполните 'stop', затем 'quit'.");
                } else {
                    ctx.setRunning(false);
                }
            }
            case "status"                   -> cmdStatus();
            case "health"                   -> cmdHealth();
            case "project"                  -> cmdProject(a);
            case "session"                  -> sessionCmd.handle(a);
            case "refresh"                  -> reportCmd.handleRefresh(a);
            case "summary"                  -> reportCmd.handleSummary(a);
            case "matrix"                   -> reportCmd.handleMatrix(a);
            case "perfile"                  -> reportCmd.handlePerFile(a);
            case "details"                  -> reportCmd.handleDetails(a);
            case "issues"                   -> reportCmd.handleIssues(a);
            case "keys"                     -> reportCmd.handleKeys(a);
            case "snippet"                  -> reportCmd.handleSnippet(a);
            case "mark"                     -> markCmd.handleMark(a);
            case "cancel"                   -> markCmd.handleCancel(a);
            case "cancelall", "cancel-all"  -> markCmd.handleCancelAll(a);
            case "ollama"                   -> ollamaCmd.handle(a);
            case "prompt"                   -> promptCmd.handle(a);
            case "auto", "autoall"          -> autoCmd.handle(a);
            case "stop"                     -> autoCmd.stop();
            default -> System.out.println("Unknown command: '" + cmd + "'. Try 'help'");
        }
    }

    // ==================== basic commands ====================

    private void cmdStatus() {
        ServerState state = ctx.state();
        System.out.println("session:");
        System.out.println("  ready    = " + state.isSessionReady());
        System.out.println("  path     = " + state.getSessionFile());
        SessionManager sm = state.getSession();
        if (sm != null) {
            System.out.println("  url      = " + sm.getUrl());
            System.out.println("  user     = " + sm.getUser());
            System.out.println("  authType = " + sm.getAuthType());
            System.out.println("  hasToken = " + sm.hasToken());
            System.out.println("  hasCookie= " + sm.hasCookie());
        }
        System.out.println("snapshot:");
        System.out.println("  status   = " + state.getSnapshotStatus());
        System.out.println("  phase    = " + (state.getSnapshotPhase() == null ? "" : state.getSnapshotPhase())
                + (state.getSnapshotProgressTotal() > 0
                ? " (" + state.getSnapshotProgress() + "/" + state.getSnapshotProgressTotal() + ")"
                : ""));
        if (state.getSnapshotError() != null) {
            System.out.println("  error    = " + state.getSnapshotError());
        }
        Snapshot s = state.getSnapshot();
        if (s != null) {
            System.out.println("  project  = " + s.projectKey);
            System.out.println("  issues   = " + s.issues.size());
            System.out.println("  snippets = " + s.snippets.size());
            System.out.println("  builtAt  = " + s.builtAt);
            System.out.println("  host     = " + s.host);
        }
        System.out.println("defaultProject = "
                + (state.getDefaultProject().isEmpty() ? "<not set>" : state.getDefaultProject()));
        System.out.println("consoleProject = "
                + (ctx.getProject() == null || ctx.getProject().isEmpty()
                ? "<not set>" : ctx.getProject()));
        System.out.println("consoleState   = " + ctx.consoleStateFile());
        System.out.println("autoRunning    = " + ctx.isAutoRunning());
        System.out.println("promptsDir     = " + ctx.template().getPromptsDir());
    }

    private void cmdHealth() {
        ServerState state = ctx.state();
        System.out.println("status        = ok");
        System.out.println("sessionReady  = " + state.isSessionReady());
        System.out.println("snapshotReady = " + state.isSnapshotReady());
        System.out.println("snapshotStatus= " + state.getSnapshotStatus());
    }

    private void cmdProject(String[] a) {
        ServerState state = ctx.state();
        if (a.length == 0) {
            System.out.println("current project = "
                    + (ctx.getProject() == null || ctx.getProject().isEmpty()
                    ? "<not set>" : ctx.getProject()));
            System.out.println("defaultProject  = "
                    + (state.getDefaultProject().isEmpty() ? "<not set>" : state.getDefaultProject()));
            System.out.println("consoleState    = " + ctx.consoleStateFile());
            return;
        }
        if ("clear".equalsIgnoreCase(a[0])) {
            ctx.clearProject();
            System.out.println("[console] project cleared (state saved)");
            return;
        }
        ctx.setProject(a[0]);
        System.out.println("[console] console project = " + a[0] + " (state saved)");
    }
}