package ru.miacomsoft.sonar_gui.backend.jira;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Конфиг Jira из единого app.properties + словарь эпиков.
 */
public final class JiraConfig {

    public static final String DEFAULT_URL        = "https://jira-medmis.bars.group";
    public static final String DEFAULT_ISSUE_TYPE = "10206";
    public static final String DEFAULT_PROJECT    = "MEDDEV";
    public static final String DEFAULT_SESSION    = "session.txt";

    private static volatile JiraConfig INSTANCE;
    private final Path configFile;

    private String baseUrl;
    private String sessionFile;
    private String issueType;
    private String defaultProject;
    private String team;
    private String labels;
    private String components;
    private String assignee;

    // Словарь эпиков (ключ: "qual.sev" в нижнем регистре)
    private final Map<String, String> epics = new HashMap<>();

    private JiraConfig(Path configFile) {
        this.configFile = configFile;
        load();
    }

    public static JiraConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (JiraConfig.class) {
                if (INSTANCE == null) {
                    String cfgPath = System.getProperty("jira.config", "app.properties");
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
        this.team           = "HM12";
        this.labels         = "";
        this.components     = "";
        this.assignee       = "me";
        epics.clear();

        // Значения по умолчанию для эпиков (только заданные пользователем)
        epics.put("security.blocker", "MEDDEV-172524");
        epics.put("security.high", "MEDDEV-172525");
        epics.put("security.medium", "");
        epics.put("security.low", "");
        epics.put("security.info", "");

        epics.put("reliability.blocker", "MEDDEV-172527");
        epics.put("reliability.high", "MEDDEV-172526");
        epics.put("reliability.medium", "");
        epics.put("reliability.low", "");
        epics.put("reliability.info", "");

        epics.put("maintainability.blocker", "MEDDEV-172528");
        epics.put("maintainability.high", "MEDDEV-172529");
        epics.put("maintainability.medium", "");
        epics.put("maintainability.low", "");
        epics.put("maintainability.info", "");

        epics.put("security_hotspot.blocker", "");
        epics.put("security_hotspot.high", "");
        epics.put("security_hotspot.medium", "");
        epics.put("security_hotspot.low", "");
        epics.put("security_hotspot.info", "");

        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                Properties p = new Properties();
                p.load(reader);
                baseUrl        = safe(p.getProperty("jira.url"),            baseUrl);
                sessionFile    = safe(p.getProperty("jira.session"),        sessionFile);
                issueType      = safe(p.getProperty("jira.issueType"),      issueType);
                defaultProject = safe(p.getProperty("jira.defaultProject"), defaultProject);
                team           = safe(p.getProperty("jira.team"),           team);
                labels         = safe(p.getProperty("jira.labels"),         labels);
                components     = safe(p.getProperty("jira.components"),     components);
                assignee       = safe(p.getProperty("jira.assignee"),       assignee);

                // Чтение эпиков (все, что есть в файле)
                for (String key : p.stringPropertyNames()) {
                    if (key.startsWith("jira.epic.")) {
                        String epicKey = key.substring("jira.epic.".length()).toLowerCase();
                        epics.put(epicKey, safe(p.getProperty(key), ""));
                    }
                }
            } catch (Exception e) {
                System.err.println("[JiraConfig] " + configFile + ": " + e.getMessage());
            }
        }
    }

    private static String safe(String v, String def) {
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    public String baseUrl()        { return baseUrl; }
    public String sessionFile()    { return sessionFile; }
    public String issueType()      { return issueType; }
    public String defaultProject() { return defaultProject; }
    public String team()           { return team; }
    public String labels()         { return labels; }
    public String components()     { return components; }
    public String assignee()       { return assignee; }
    public Path   configFile()     { return configFile; }

    /**
     * Возвращает ID эпика по категории и уровню.
     * Например: getEpic("SECURITY", "BLOCKER") -> "MEDDEV-172524"
     */
    public String getEpic(String qual, String sev) {
        if (qual == null || sev == null) return "";
        String key = (qual + "." + sev).toLowerCase();
        return epics.getOrDefault(key, "");
    }

    public Map<String, String> getEpics() {
        return new HashMap<>(epics);
    }
}