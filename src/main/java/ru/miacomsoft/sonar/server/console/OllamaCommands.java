package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.server.OllamaConfig;
import ru.miacomsoft.sonar.server.ollama.OllamaClient;

import java.util.Locale;

/**
 * Команда {@code ollama *}: show / set / test / reload.
 */
public class OllamaCommands {

    private final ConsoleContext ctx;

    public OllamaCommands(ConsoleContext ctx) {
        this.ctx = ctx;
    }

    public void handle(String[] a) throws Exception {
        OllamaConfig cfg = ctx.ollamaConfig();

        if (a.length == 0 || "show".equalsIgnoreCase(a[0])) {
            System.out.println("Ollama settings  (" + cfg.getConfigFile() + ")");
            System.out.println("  url         = " + cfg.getUrl());
            System.out.println("  model       = " + cfg.getModel());
            System.out.println("  timeout     = " + cfg.getTimeoutSeconds() + "s");
            System.out.println("  temperature = " + cfg.getTemperature());
            System.out.println();
            System.out.println("Usage:");
            System.out.println("  ollama show");
            System.out.println("  ollama set url <URL>");
            System.out.println("  ollama set model <MODEL>");
            System.out.println("  ollama set timeout <SECONDS>");
            System.out.println("  ollama set temperature <FLOAT>");
            System.out.println("  ollama test");
            System.out.println("  ollama reload");
            return;
        }

        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (a.length < 3) {
                    System.out.println("usage: ollama set <url|model|timeout|temperature> <value>");
                    return;
                }
                switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "url"         -> cfg.setUrl(a[2]);
                    case "model"       -> cfg.setModel(a[2]);
                    case "timeout"     -> cfg.setTimeoutSeconds(Integer.parseInt(a[2]));
                    case "temperature" -> cfg.setTemperature(Double.parseDouble(a[2]));
                    default -> { System.out.println("unknown key: " + a[1]); return; }
                }
                cfg.save();
                System.out.println("[ollama] saved");
            }
            case "test" -> {
                try {
                    new OllamaClient(cfg).testConnection();
                    System.out.println("[ollama] OK: " + cfg.getUrl());
                } catch (Exception e) {
                    System.err.println("[ollama] FAIL: " + e.getMessage());
                }
            }
            case "reload" -> {
                OllamaConfig fresh = new OllamaConfig(cfg.getConfigFile());
                cfg.setUrl(fresh.getUrl());
                cfg.setModel(fresh.getModel());
                cfg.setTimeoutSeconds(fresh.getTimeoutSeconds());
                cfg.setTemperature(fresh.getTemperature());
                System.out.println("[ollama] reloaded from " + cfg.getConfigFile());
            }
            default -> System.out.println("unknown sub-command: " + sub);
        }
    }
}