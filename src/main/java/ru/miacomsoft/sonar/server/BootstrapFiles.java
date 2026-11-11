package ru.miacomsoft.sonar.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * При старте проверяет наличие файлов, без которых сервер работает
 * в «пустом» режиме, и создаёт недостающие:
 *   - session.properties       (в корне проекта / cwd)
 *   - prompts/auto-resolve.txt
 *   - prompts/status-comment.txt
 *   - prompts/bulk-status-comment.txt
 *   - prompts/analyze-issue.txt
 *
 * Существующие файлы НИКОГДА не перезаписываются.
 */
public final class BootstrapFiles {

    private BootstrapFiles() {}

    public static void ensureAll(Path sessionFile, Path promptsDir) {
        ensureSession(sessionFile);
        ensurePrompts(promptsDir);
    }

    // ============================================================
    // session.properties
    // ============================================================

    public static void ensureSession(Path sessionFile) {
        if (sessionFile == null) return;
        try {
            if (Files.exists(sessionFile)) {
                System.out.println("[Bootstrap] session file found: "
                        + sessionFile.toAbsolutePath());
                return;
            }

            Path parent = sessionFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);

            Files.writeString(sessionFile,
                    Defaults.SESSION_PROPERTIES_TEMPLATE,
                    StandardCharsets.UTF_8);

            System.out.println();
            System.out.println("==========================================================");
            System.out.println("[Bootstrap] Создан файл конфигурации:");
            System.out.println("            " + sessionFile.toAbsolutePath());
            System.out.println();
            System.out.println("            Заполните поля sonar.url и sonar.token,");
            System.out.println("            затем перезапустите сервер или выполните:");
            System.out.println("              PUT /api/session   (тело — текст файла)");
            System.out.println();
            System.out.println("            Сейчас сервер стартует БЕЗ сессии —");
            System.out.println("            запросы к SonarQube вернут 503,");
            System.out.println("            пока конфиг не будет заполнен.");
            System.out.println("==========================================================");
            System.out.println();

        } catch (Exception e) {
            System.err.println("[Bootstrap] не удалось создать " + sessionFile
                    + ": " + e.getMessage());
        }
    }

    // ============================================================
    // prompts/
    // ============================================================

    public static void ensurePrompts(Path promptsDir) {
        if (promptsDir == null) return;
        try {
            Files.createDirectories(promptsDir);
        } catch (Exception e) {
            System.err.println("[Bootstrap] не удалось создать каталог " + promptsDir
                    + ": " + e.getMessage());
            return;
        }
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
            System.err.println("[Bootstrap] не удалось создать " + name + ".txt: "
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