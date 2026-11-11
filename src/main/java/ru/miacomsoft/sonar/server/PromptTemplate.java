package ru.miacomsoft.sonar.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Загружает шаблоны промптов из каталога и подставляет [TAG] → значение.
 *
 * Синтаксис тэгов: [NAME] где NAME = [A-Z_][A-Z0-9_]*.
 * Всё остальное (включая [0], [info], [text with space]) не трогается.
 * Неизвестные тэги заменяются на пустую строку.
 */
public class PromptTemplate {

    private static final Pattern TAG_RE = Pattern.compile("\\[([A-Z_][A-Z0-9_]*)\\]");

    private final Path promptsDir;

    public PromptTemplate(Path promptsDir) {
        this.promptsDir = promptsDir;
    }

    public Path getPromptsDir() {
        return promptsDir;
    }

    public boolean exists(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!name.matches("[A-Za-z0-9_.-]+")) return false;
        return Files.isRegularFile(promptsDir.resolve(name + ".txt"));
    }

    /** Список доступных имён промптов (без .txt), отсортированный. */
    public List<String> listAvailable() {
        if (!Files.isDirectory(promptsDir)) return Collections.emptyList();
        try (Stream<Path> s = Files.list(promptsDir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".txt"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public String loadRaw(String name) throws Exception {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Имя промпта не задано");
        }
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Недопустимое имя промпта: " + name);
        }
        Path file = promptsDir.resolve(name + ".txt");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                    "Промпт не найден: " + name + " (каталог: " + promptsDir + ")");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public String render(String name, Map<String, String> params) throws Exception {
        return renderString(loadRaw(name), params);
    }

    public static String renderString(String raw, Map<String, String> params) {
        if (raw == null) return "";
        if (params == null) params = new LinkedHashMap<>();

        Matcher m = TAG_RE.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tag = m.group(1);
            String val = params.get(tag);
            if (val == null) val = "";
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}