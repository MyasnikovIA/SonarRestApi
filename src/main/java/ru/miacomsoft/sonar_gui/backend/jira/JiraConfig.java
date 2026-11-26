package ru.miacomsoft.sonar_gui.backend.jira;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Конфиг Jira (jira.properties).
 * Копия ru.miacomsoft.sonar.jira.JiraConfig с локализацией пакета.
 */
public final class JiraConfig {

    public static final String DEFAULT_URL        = "https://jira-medmis.bars.group";
    public static final String DEFAULT_ISSUE_TYPE = "10206";
    public static final String DEFAULT_PROJECT    = "MEDDEV";
    public static final String DEFAULT_SESSION    = "session.txt";
    public static final String CONFIG_FILE_NAME   = "jira.properties";

    private static volatile JiraConfig INSTANCE;
    private final Path configFile;

    private String baseUrl;
    private String sessionFile;
    private String issueType;
    private String defaultProject;
    private String epic;
    private String priority;
    private String security;
    private String team;
    private String labels;
    private String components;
    private String assignee;

    private JiraConfig(Path configFile) {
        this.configFile = configFile;
        load();
    }

    public static JiraConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (JiraConfig.class) {
                if (INSTANCE == null) {
                    String cfgPath = System.getProperty("jira.config", CONFIG_FILE_NAME);
                    INSTANCE = new JiraConfig(Path.of(cfgPath));
                }
            }
        }
        return INSTANCE;
    }

    public void reload() { load(); }

    private void load() {
        this.baseUrl        = DEFAULT_URL;
        this.sessionFile    = DEFAULT_SESSION;
        this.issueType      = DEFAULT_ISSUE_TYPE;
        this.defaultProject = DEFAULT_PROJECT;
        this.epic           = "";
        this.priority       = "";
        this.security       = "";
        this.team           = "HM12";
        this.labels         = "";
        this.components     = "";
        this.assignee       = "me";

        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                Properties p = new Properties();
                p.load(reader);
                baseUrl        = safe(p.getProperty("jira.url"),            baseUrl);
                sessionFile    = safe(p.getProperty("jira.session"),        sessionFile);
                issueType      = safe(p.getProperty("jira.issueType"),      issueType);
                defaultProject = safe(p.getProperty("jira.defaultProject"), defaultProject);
                epic           = safe(p.getProperty("jira.epic"),           epic);
                priority       = safe(p.getProperty("jira.priority"),       priority);
                security       = safe(p.getProperty("jira.security"),       security);
                team           = safe(p.getProperty("jira.team"),           team);
                labels         = safe(p.getProperty("jira.labels"),         labels);
                components     = safe(p.getProperty("jira.components"),     components);
                assignee       = safe(p.getProperty("jira.assignee"),       assignee);
            } catch (Exception e) {
                System.err.println("[JiraConfig] " + configFile + ": " + e.getMessage());
            }
        }

        String override = System.getProperty("jira.url");
        if (override == null || override.isBlank()) override = System.getenv("JIRA_URL");
        if (override != null && !override.isBlank()) baseUrl = override.trim();
        while (baseUrl != null && baseUrl.endsWith("/"))
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    private static String safe(String v, String def) {
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    public String baseUrl()        { return baseUrl; }
    public String sessionFile()    { return sessionFile; }
    public String issueType()      { return issueType; }
    public String defaultProject() { return defaultProject; }
    public String epic()           { return epic; }
    public String priority()       { return priority; }
    public String security()       { return security; }
    public String team()           { return team; }
    public String labels()         { return labels; }
    public String components()     { return components; }
    public String assignee()       { return assignee; }
    public Path   configFile()     { return configFile; }
}