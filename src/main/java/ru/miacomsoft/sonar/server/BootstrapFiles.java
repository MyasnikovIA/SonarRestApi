package ru.miacomsoft.sonar.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * При старте приложения проверяет наличие файлов, без которых сервер
 * работает в «пустом» режиме, и создаёт недостающие:
 *   - session.txt
 *   - prompts/auto-resolve.txt
 *   - prompts/status-comment.txt
 *   - prompts/bulk-status-comment.txt
 *   - prompts/analyze-issue.txt
 *
 * Существующие файлы НИКОГДА не перезаписываются — если пользователь
 * правил промпт, изменения сохранятся.
 *
 * Все ошибки (нет прав, нет диска) логируются, но не валят старт:
 * ServerState и ApiServer уже умеют работать без session.txt / prompts/.
 */
public final class BootstrapFiles {

    private BootstrapFiles() {}

    /** Создать недостающие файлы. Ничего не перезаписывает. */
    public static void ensureAll(Path sessionFile, Path promptsDir) {
        ensureSession(sessionFile);
        ensurePrompts(promptsDir);
    }

    public static void ensureSession(Path sessionFile) {
        if (sessionFile == null) return;
        try {
            if (Files.exists(sessionFile)) return;
            Path parent = sessionFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(sessionFile, Defaults.SESSION_TEMPLATE, StandardCharsets.UTF_8);
            System.out.println("[Bootstrap] created " + sessionFile.toAbsolutePath()
                    + "  (заполните заголовками из DevTools браузера)");
        } catch (Exception e) {
            System.err.println("[Bootstrap] не удалось создать " + sessionFile
                    + ": " + e.getMessage());
        }
    }

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
    
    /**
     * Порядок поиска каталога prompts (с созданием при необходимости):
     *   1) -Dsonar.prompts=<abs path>
     *   2) <jar-dir>/prompts
     *   3) <jar-parent>/prompts
     *   4) ./prompts (cwd)
     */
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