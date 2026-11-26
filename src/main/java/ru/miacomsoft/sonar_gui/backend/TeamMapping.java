package ru.miacomsoft.sonar_gui.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Парсит team_mapping.csv.
 * Копия ru.miacomsoft.sonar.server.TeamMapping без изменений.
 */
public class TeamMapping {

    public static final Path FILE_PATH = Path.of("team_mapping.csv").toAbsolutePath().normalize();

    public static final class FileInfo {
        public final String team;
        public final String component;

        public FileInfo(String team, String component) {
            this.team      = (team      == null ? "" : team.trim());
            this.component = (component == null ? "" : component.trim());
        }

        public boolean isEmpty()      { return team.isEmpty() && component.isEmpty(); }
        public boolean hasTeam()      { return !team.isEmpty(); }
        public boolean hasComponent() { return !component.isEmpty(); }

        @Override public String toString() {
            return "FileInfo{team='" + team + "', component='" + component + "'}";
        }
    }

    private final Map<String, FileInfo> pathToInfo = new HashMap<>();
    private int totalRows = 0;
    private int rowsWithTeam = 0;
    private int rowsWithComponent = 0;
    private int rowsWithBoth = 0;

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
            System.out.println("[TeamMapping] " + FILE_PATH + " не найден");
            return;
        }
        try {
            String text = Files.readString(FILE_PATH, StandardCharsets.UTF_8);
            List<List<String>> rows = parseCsv(text);
            if (rows.isEmpty()) return;

            List<String> header = rows.get(0);
            if (!header.isEmpty()) header.set(0, header.get(0).replace("\uFEFF", "").trim());

            int colForm       = findColumnExact(header, "Форма");
            int colComponent  = findColumnExact(header, "Компонент");
            int colTeam       = findColumnExact(header, "Владелец тМИС");
            int colOverridden = findColumnExact(header, "Переопределяется формами");

            if (colForm < 0) {
                System.err.println("[TeamMapping] не найдена колонка 'Форма'");
                return;
            }
            if (colTeam < 0 && colComponent < 0) {
                System.err.println("[TeamMapping] нет ни 'Владелец тМИС', ни 'Компонент'");
                return;
            }

            for (int r = 1; r < rows.size(); r++) {
                processRow(rows.get(r), colForm, colComponent, colTeam, false);
            }
            if (colOverridden >= 0) {
                for (int r = 1; r < rows.size(); r++) {
                    processRow(rows.get(r), colOverridden, colComponent, colTeam, true);
                }
            }

            System.out.println("[TeamMapping] Загружено " + pathToInfo.size() + " путей");
        } catch (Exception e) {
            System.err.println("[TeamMapping] Ошибка чтения " + FILE_PATH + ": " + e.getMessage());
        }
    }

    private void processRow(List<String> cells, int colPath,
                            int colComponent, int colTeam, boolean asAlias) {
        if (cells.size() <= colPath) return;
        String raw = cells.get(colPath);
        if (raw == null || raw.isBlank()) return;

        String team      = (colTeam      >= 0 && cells.size() > colTeam)      ? cells.get(colTeam).trim()      : "";
        String component = (colComponent >= 0 && cells.size() > colComponent) ? cells.get(colComponent).trim() : "";
        if (team.isEmpty() && component.isEmpty()) return;

        if (!asAlias) {
            totalRows++;
            if (!team.isEmpty()) rowsWithTeam++;
            if (!component.isEmpty()) rowsWithComponent++;
            if (!team.isEmpty() && !component.isEmpty()) rowsWithBoth++;
        }

        FileInfo info = new FileInfo(team, component);
        for (String part : raw.split("\\r?\\n")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            while (p.endsWith("\"")) p = p.substring(0, p.length() - 1).trim();
            if (p.isEmpty()) continue;
            String key = normPath(p);
            if (asAlias) pathToInfo.putIfAbsent(key, info);
            else         pathToInfo.put(key, info);
        }
    }

    public String findTeam(String component) {
        FileInfo info = findInfo(component);
        return info == null ? "" : info.team;
    }

    public FileInfo findInfo(String component) {
        if (component == null || component.isEmpty()) return null;
        String path = component;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        String normalized = normPath(path);

        FileInfo info = pathToInfo.get(normalized);
        if (info != null) return info;

        String noSlash = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        info = pathToInfo.get(noSlash);
        if (info != null) return info;

        if (!normalized.startsWith("/")) info = pathToInfo.get("/" + normalized);
        return info;
    }

    public int getTotalRows()         { return totalRows; }
    public int getRowsWithTeam()      { return rowsWithTeam; }
    public int getRowsWithComponent() { return rowsWithComponent; }
    public int getRowsWithBoth()      { return rowsWithBoth; }
    public int getPathCount()         { return pathToInfo.size(); }

    private static String normPath(String s) {
        String n = s.replace('\\', '/').trim();
        while (n.startsWith("\"")) n = n.substring(1);
        while (n.endsWith("\""))   n = n.substring(0, n.length() - 1);
        n = n.toLowerCase(Locale.ROOT);
        if (!n.startsWith("/")) n = "/" + n;
        n = n.replaceAll("/{2,}", "/");
        return n;
    }

    private static String normalizeHeader(String s) {
        if (s == null) return "";
        return s.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
    }

    private static int findColumnExact(List<String> header, String name) {
        String target = normalizeHeader(name);
        for (int i = 0; i < header.size(); i++) {
            if (normalizeHeader(header.get(i)).equals(target)) return i;
        }
        return -1;
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;

        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        int i = 0, len = text.length();

        while (i < len) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') { cur.append('"'); i += 2; continue; }
                    inQuotes = false; i++; continue;
                }
                cur.append(c); i++; continue;
            }
            if (c == '"' && cur.length() == 0) { inQuotes = true; i++; continue; }
            if (c == ',') { row.add(cur.toString()); cur.setLength(0); i++; continue; }
            if (c == '\n') {
                row.add(cur.toString()); cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) result.add(row);
                row = new ArrayList<>(); i++; continue;
            }
            if (c == '\r') { i++; continue; }
            cur.append(c); i++;
        }
        if (cur.length() > 0 || !row.isEmpty()) { row.add(cur.toString()); result.add(row); }
        return result;
    }
}