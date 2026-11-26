package ru.miacomsoft.sonar_gui.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Создание конфигурационных файлов при первом запуске.
 *
 * Создаются (если отсутствуют):
 *   - session.properties  (SonarQube)
 *   - ollama.properties   (LLM)
 *   - jira.properties     (Jira)
 *   - session.txt         (Cookies Jira)
 *   - team_mapping.csv    (Команды/Модули)
 *   - prompts/*.txt       (4 шаблона промптов)
 *
 * Существующие файлы никогда не перезаписываются.
 */
public final class BootstrapFiles {

    private BootstrapFiles() {}

    // ============================================================
    // Публичное API
    // ============================================================

    /**
     * Полный bootstrap. Вызывается из Main один раз при старте.
     *
     * @param sessionFile абсолютный путь к session.properties (SonarQube)
     * @param ollamaFile  абсолютный путь к ollama.properties
     * @param promptsDir  каталог prompts/
     */
    public static void ensureAll(Path sessionFile, Path ollamaFile, Path promptsDir) {
        ensureSession(sessionFile);
        ensureOllama(ollamaFile);
        ensureJira();
        ensureTeamMapping();
        ensureSessionTxt();
        ensurePrompts(promptsDir);
    }

    /** Обратная совместимость: старый вызов без ollamaFile. */
    public static void ensureAll(Path sessionFile, Path promptsDir) {
        ensureAll(sessionFile, Path.of("ollama.properties"), promptsDir);
    }

    // ============================================================
    // SonarQube
    // ============================================================

    public static void ensureSession(Path sessionFile) {
        if (sessionFile == null) return;
        try {
            if (Files.exists(sessionFile)) return;
            Path parent = sessionFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(sessionFile, Defaults.SESSION_PROPERTIES_TEMPLATE,
                    StandardCharsets.UTF_8);
            System.out.println("[Bootstrap] Создан " + sessionFile.toAbsolutePath()
                    + " — заполните sonar.url/sonar.token через меню «Файл → Настройки…».");
        } catch (Exception e) {
            System.err.println("[Bootstrap] не удалось создать " + sessionFile
                    + ": " + e.getMessage());
        }
    }

    // ============================================================
    // Ollama
    // ============================================================

    public static void ensureOllama(Path ollamaFile) {
        if (ollamaFile == null) return;
        try {
            if (Files.exists(ollamaFile)) return;
            Path parent = ollamaFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(ollamaFile, Defaults.OLLAMA_PROPERTIES_TEMPLATE,
                    StandardCharsets.UTF_8);
            System.out.println("[Bootstrap] Создан " + ollamaFile.toAbsolutePath()
                    + " — укажите url/model через меню «Файл → Настройки…».");
        } catch (Exception e) {
            System.err.println("[Bootstrap] не удалось создать " + ollamaFile
                    + ": " + e.getMessage());
        }
    }

    // ============================================================
    // Jira
    // ============================================================

    public static void ensureJira() {
        createIfMissing(Path.of("jira.properties"),
                Defaults.JIRA_PROPERTIES_TEMPLATE,
                "jira.properties");
    }

    public static void ensureSessionTxt() {
        createIfMissing(Path.of("session.txt"),
                Defaults.SESSION_TXT_TEMPLATE,
                "session.txt");
    }

    // ============================================================
    // Команды (CSV)
    // ============================================================

    public static void ensureTeamMapping() {
        createIfMissing(Path.of("team_mapping.csv"),
                Defaults.TEAM_MAPPING_CSV_TEMPLATE,
                "team_mapping.csv");
    }

    // ============================================================
    // Prompts
    // ============================================================

    public static void ensurePrompts(Path promptsDir) {
        if (promptsDir == null) return;
        try { Files.createDirectories(promptsDir); }
        catch (Exception e) { return; }
        ensurePrompt(promptsDir, "auto-resolve",        Defaults.PROMPT_AUTO_RESOLVE);
        ensurePrompt(promptsDir, "status-comment",      Defaults.PROMPT_STATUS_COMMENT);
        ensurePrompt(promptsDir, "bulk-status-comment", Defaults.PROMPT_BULK_STATUS_COMMENT);
        ensurePrompt(promptsDir, "analyze-issue",       Defaults.PROMPT_ANALYZE_ISSUE);
    }

    private static void ensurePrompt(Path dir, String name, String content) {
        try {
            Path file = dir.resolve(name + ".txt");
            if (Files.exists(file)) return;
            Files.writeString(file, content, StandardCharsets.UTF_8);
            System.out.println("[Bootstrap] created " + file.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[Bootstrap] " + name + ".txt: " + e.getMessage());
        }
    }

    // ============================================================
    // Утилиты
    // ============================================================

    private static void createIfMissing(Path file, String template, String label) {
        try {
            Path abs = file.toAbsolutePath().normalize();
            if (Files.exists(abs)) return;
            Path parent = abs.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(abs, template, StandardCharsets.UTF_8);
            System.out.println("[Bootstrap] создан " + label + " → " + abs);
        } catch (Exception e) {
            System.err.println("[Bootstrap] не удалось создать " + label + ": "
                    + e.getMessage());
        }
    }

    public static Path resolvePromptsDir() {
        String sys = System.getProperty("sonar.prompts");
        if (sys != null && !sys.isBlank()) {
            Path p = Path.of(sys).toAbsolutePath().normalize();
            try { Files.createDirectories(p); } catch (Exception ignored) {}
            return p;
        }
        try {
            var cs = BootstrapFiles.class.getProtectionDomain().getCodeSource();
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