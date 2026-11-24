package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.SessionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Команда {@code session *}: работа с session.properties
 * (show / set / reload / save).
 */
public class SessionCommands {

    private final ConsoleContext ctx;

    public SessionCommands(ConsoleContext ctx) {
        this.ctx = ctx;
    }

    public void handle(String[] a) throws Exception {
        if (a.length == 0) { sessionHelp(); return; }
        ServerState state = ctx.state();
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "show" -> {
                String raw = state.readRawFile();
                System.out.println("path    = " + state.getSessionFile());
                System.out.println("exists  = " + !raw.isEmpty());
                if (!raw.isEmpty()) {
                    System.out.println("--- raw ---");
                    System.out.println(raw);
                    System.out.println("-----------");
                }
                SessionManager sm = state.getSession();
                if (sm != null) {
                    System.out.println("url     = " + sm.getUrl());
                    System.out.println("user    = " + sm.getUser());
                    System.out.println("auth    = " + sm.getAuthType());
                    System.out.println("hasToken= " + sm.hasToken());
                }
            }
            case "set" -> {
                if (a.length < 2) { System.out.println("usage: session set <path>"); return; }
                String raw = Files.readString(Path.of(a[1]), StandardCharsets.UTF_8);
                state.update(raw);
                System.out.println("[console] session updated from " + a[1]);
                if (state.getSession() != null) {
                    System.out.println("          url  = " + state.getSession().getUrl());
                    System.out.println("          auth = " + state.getSession().getAuthType());
                }
                if (ctx.requireProject()) {
                    state.buildSnapshotAsync(ctx.getProject());
                    System.out.println("[console] snapshot rebuild started in background");
                }
            }
            case "reload" -> {
                state.reload();
                System.out.println("[console] reloaded from " + state.getSessionFile());
                if (state.getSession() != null) {
                    System.out.println("          url  = " + state.getSession().getUrl());
                    System.out.println("          auth = " + state.getSession().getAuthType());
                }
            }
            case "save" -> {
                if (a.length < 2) { System.out.println("usage: session save <path>"); return; }
                String raw = state.readRawFile();
                Files.writeString(Path.of(a[1]), raw, StandardCharsets.UTF_8);
                System.out.println("[console] saved to " + a[1]);
            }
            default -> sessionHelp();
        }
    }

    private void sessionHelp() {
        System.out.println("session sub-commands:");
        System.out.println("  session show              — прочитать session.properties");
        System.out.println("  session set <path>        — заменить session.properties + reload + auto build");
        System.out.println("  session reload            — перечитать session.properties с диска");
        System.out.println("  session save <path>       — сохранить текущий текст в файл");
    }
}