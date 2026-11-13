package ru.miacomsoft.sonar.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Парсит team_mapping.csv (колонка A — путь, первая не-путь в колонках C..Y — команда).
 * Используется в html-files-teams: каждый per-file HTML получает префикс "(Team)_" в имени.
 */
public class TeamMapping {

    public static final Path FILE_PATH = Path.of("team_mapping.csv").toAbsolutePath().normalize();

    private final Map<String, String> pathToTeam = new HashMap<>();

    public static TeamMapping load() {
        TeamMapping m = new TeamMapping();
        m.loadInternal();
        return m;
    }

    public static boolean exists() {
        return Files.isRegularFile(FILE_PATH);
    }

    private void loadInternal() {
        if (!Files.isRegularFile(FILE_PATH)) {
            System.out.println("[TeamMapping] " + FILE_PATH + " не найден — команды не подставляются");
            return;
        }
        try {
            List<String> lines = Files.readAllLines(FILE_PATH, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return;

            for (int i = 1; i < lines.size(); i++) {
                List<String> cells = parseCsvLine(lines.get(i));
                if (cells.size() < 3) continue;

                String team = findTeam(cells);
                if (team.isEmpty()) continue;

                for (int c = 0; c < Math.min(cells.size(), 3); c++) {
                    String path = cells.get(c).trim();
                    if (!path.isEmpty() && looksLikePath(path)) {
                        pathToTeam.put(normPath(path), team);
                    }
                }
            }
            System.out.println("[TeamMapping] Загружено " + pathToTeam.size() + " маппингов из " + FILE_PATH);
        } catch (Exception e) {
            System.err.println("[TeamMapping] Ошибка чтения " + FILE_PATH + ": " + e.getMessage());
        }
    }

    private static String findTeam(List<String> cells) {
        for (int c = 2; c < Math.min(cells.size(), 25); c++) {
            String val = cells.get(c).trim();
            if (val.isEmpty()) continue;
            if (looksLikePath(val)) continue;
            if (val.length() > 80) continue;
            return val;
        }
        return "";
    }

    private static boolean looksLikePath(String s) {
        return s.contains("/") || s.contains("\\")
                || s.contains(".frm") || s.contains(".php") || s.contains(".pas");
    }

    private static String normPath(String s) {
        String n = s.replace('\\', '/').toLowerCase();
        if (!n.startsWith("/")) n = "/" + n;
        return n;
    }

    /** Простой CSV-парсер с кавычками. */
    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"'); i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',' || c == ';') { cells.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    public String findTeam(String component) {
        if (component == null) return "";
        String path = component;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);

        String normalized = normPath(path);
        String team = pathToTeam.get(normalized);
        if (team != null && !team.isEmpty()) return team;

        String noSlash = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        team = pathToTeam.get(noSlash);
        if (team != null && !team.isEmpty()) return team;

        return "";
    }
}