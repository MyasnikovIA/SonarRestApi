package ru.miacomsoft.sonar_gui.backend;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Чтение параметров подключения SonarQube из единого app.properties.
 */
public class SessionManager {

    private final Path configFile;

    private String url;
    private String user;
    private String token;
    private String cookie;
    private String authType;

    public SessionManager(Path configFile) throws IOException {
        this.configFile = configFile;
        load();
    }

    public SessionManager(String rawContent) throws IOException {
        this.configFile = null;
        loadFromString(rawContent);
    }

    private void load() throws IOException {
        if (configFile == null || !Files.exists(configFile)) {
            throw new IllegalArgumentException("Файл конфигурации не найден: "
                    + (configFile == null ? "<null>" : configFile.toAbsolutePath()));
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        }
        applyProps(props);
    }

    private void loadFromString(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Пустое содержимое конфигурации");
        }
        Properties props = new Properties();
        try (StringReader sr = new StringReader(raw)) {
            props.load(sr);
        }
        applyProps(props);
    }

    private void applyProps(Properties props) {
        this.url      = trim(props.getProperty("sonar.url", ""));
        this.user     = trim(props.getProperty("sonar.user", ""));
        this.token    = trim(props.getProperty("sonar.token", ""));
        this.cookie   = trim(props.getProperty("sonar.cookie", ""));
        this.authType = trim(props.getProperty("sonar.auth", "bearer")).toLowerCase();

        if (url.isEmpty()) {
            throw new IllegalArgumentException("В конфигурации не задан sonar.url");
        }
        if ("cookie".equals(authType)) {
            if (cookie.isEmpty()) {
                throw new IllegalArgumentException("sonar.auth=cookie, но sonar.cookie пуст.");
            }
        } else if ("basic".equals(authType)) {
            if (token.isEmpty()) throw new IllegalArgumentException("sonar.auth=basic, но sonar.token пуст.");
            if (user.isEmpty()) throw new IllegalArgumentException("sonar.auth=basic, но sonar.user пуст.");
        } else {
            if (token.isEmpty()) throw new IllegalArgumentException("sonar.auth=bearer, но sonar.token пуст.");
        }
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    public String  getUrl()        { return url; }
    public String  getUser()       { return user; }
    public String  getToken()      { return token; }
    public String  getCookie()     { return cookie; }
    public String  getAuthType()   { return authType; }
    public Path    getConfigFile() { return configFile; }

    public boolean hasToken()  { return token != null && !token.isBlank(); }
    public boolean hasCookie() { return cookie != null && !cookie.isBlank(); }
    public boolean isCookieAuth() { return "cookie".equalsIgnoreCase(authType); }

    public String getHost() {
        try {
            java.net.URI u = java.net.URI.create(url);
            String h = u.getHost();
            int    p = u.getPort();
            if (h == null || h.isBlank()) return url;
            return (p > 0 && p != 80 && p != 443) ? h + ":" + p : h;
        } catch (Exception e) {
            return url;
        }
    }

    public String readRaw() {
        if (configFile == null || !Files.exists(configFile)) return "";
        try {
            return Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}