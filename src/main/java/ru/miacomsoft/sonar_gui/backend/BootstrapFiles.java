package ru.miacomsoft.sonar_gui.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Создание конфигурационных файлов при первом запуске.
 */
public final class BootstrapFiles {

    private BootstrapFiles() {}

    /**
     * Полный bootstrap. Вызывается из Main один раз при старте.
     */
    public static void ensureAll(Path settingsFile, Path promptsDir) {
        ensureAppProperties(settingsFile);
        ensureTeamMapping();
        ensureProductMapping(); // Новый файл с продуктами
        ensureSessionTxt();
        ensurePrompts(promptsDir);
    }

    /**
     * Создает единый файл настроек app.properties.
     */
    public static void ensureAppProperties(Path settingsFile) {
        if (settingsFile == null) return;
        try {
            if (Files.exists(settingsFile)) return;
            Path parent = settingsFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(settingsFile, Defaults.APP_PROPERTIES_TEMPLATE, StandardCharsets.UTF_8);
            System.out.println("[Bootstrap] Создан " + settingsFile.toAbsolutePath()
                    + " — настройте подключение в меню «Файл → Настройки…».");
        } catch (Exception e) {
            System.err.println("[Bootstrap] Не удалось создать " + settingsFile + ": " + e.getMessage());
        }
    }

    /**
     * Создает файл сессии Jira (session.txt).
     */
    public static void ensureSessionTxt() {
        createIfMissing(Path.of("session.txt"),
                Defaults.SESSION_TXT_TEMPLATE,
                "session.txt");
    }

    /**
     * Создает CSV-файл привязки файлов к командам (team_mapping.csv).
     */
    public static void ensureTeamMapping() {
        createIfMissing(Path.of("team_mapping.csv"),
                Defaults.TEAM_MAPPING_CSV_TEMPLATE,
                "team_mapping.csv");
    }

    /**
     * Создает CSV-файл привязки проектов к продуктам (product_mapping.csv).
     */
    public static void ensureProductMapping() {
        createIfMissing(Path.of("product_mapping.csv"),
                Defaults.PRODUCT_MAPPING_CSV_TEMPLATE,
                "product_mapping.csv");
    }

    /**
     * Создает шаблоны промптов в каталоге prompts/.
     */
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