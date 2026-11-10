package ru.miacomsoft.sonar.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Персистентная конфигурация Ollama.
 * Хранится в файле *.properties, переживает перезапуск приложения.
 */
public class OllamaConfig {

    private static final String DEFAULT_URL   = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "llama3";
    private static final int    DEFAULT_TIMEOUT_SEC = 300;
    private static final double DEFAULT_TEMPERATURE = 0.2;

    private final Path configFile;

    private String  url;
    private String  model;
    private int     timeoutSeconds;
    private double  temperature;

    public OllamaConfig(Path configFile) {
        this.configFile = configFile;
        load();
    }

    private void load() {
        Properties p = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("[OllamaConfig] load failed: " + e.getMessage());
            }
        }
        this.url            = p.getProperty("url",   DEFAULT_URL);
        this.model          = p.getProperty("model", DEFAULT_MODEL);
        this.timeoutSeconds = parseInt(p.getProperty("timeout"), DEFAULT_TIMEOUT_SEC);
        this.temperature    = parseDouble(p.getProperty("temperature"), DEFAULT_TEMPERATURE);
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("url",         url);
        p.setProperty("model",       model);
        p.setProperty("timeout",     String.valueOf(timeoutSeconds));
        p.setProperty("temperature", String.valueOf(temperature));
        try {
            Path parent = configFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(configFile)) {
                p.store(out, "Ollama configuration");
            }
            System.out.println("[OllamaConfig] saved: " + configFile);
        } catch (IOException e) {
            System.err.println("[OllamaConfig] save failed: " + e.getMessage());
        }
    }

    public boolean isConfigured() {
        return url != null && !url.isBlank() && model != null && !model.isBlank();
    }

    public Path getConfigFile() { return configFile; }
    public String  getUrl()          { return url; }
    public String  getModel()        { return model; }
    public int     getTimeoutSeconds() { return timeoutSeconds; }
    public double  getTemperature()  { return temperature; }

    public void setUrl(String url)           { this.url = url; }
    public void setModel(String model)       { this.model = model; }
    public void setTimeoutSeconds(int s)     { this.timeoutSeconds = s; }
    public void setTemperature(double t)     { this.temperature = t; }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }
}