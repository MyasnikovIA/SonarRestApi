package ru.miacomsoft.sonar.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Парсит team_mapping.csv нового формата:
 *
 *   Колонка «Форма»              — путь к форме (например /Forms/ARMMainDoc/main_doc.frm)
 *   Колонка «Переопределяется»   — пути-алиасы (UserFormsXXX/...) того же файла
 *   Колонка «Компонент»          — подсистема / модуль (например "Статистика стационара")
 *   Колонка «Владелец тМИС»      — команда-владелец (например "HM3")
 *
 * Для каждого файла хранится запись (team, component) — оба поля опциональны.
 * Поиск выполняется по нормализованному пути (без префикса проекта и без ведущего слэша).
 *
 * Совместимость: findTeam(String) возвращает только команду (для legacy-кода).
 * Новый метод findInfo(String) возвращает полную запись FileInfo.
 */
public class TeamMapping {

    public static final Path FILE_PATH = Path.of("team_mapping.csv").toAbsolutePath().normalize();

    // ---------- Запись о файле ----------
    public static final class FileInfo {
        public final String team;       // "" если не задана
        public final String component;  // "" если не задан

        public FileInfo(String team, String component) {
            this.team      = (team      == null ? "" : team.trim());
            this.component = (component == null ? "" : component.trim());
        }

        public boolean isEmpty()      { return team.isEmpty() && component.isEmpty(); }
        public boolean hasTeam()      { return !team.isEmpty(); }
        public boolean hasComponent() { return !component.isEmpty(); }

        @Override
        public String toString() {
            return "FileInfo{team='" + team + "', component='" + component + "'}";
        }
    }

    private final Map<String, FileInfo> pathToInfo = new HashMap<>();

    // ---- Статистика (для UI) ----
    private int totalRows           = 0;
    private int rowsWithTeam        = 0;
    private int rowsWithComponent   = 0;
    private int rowsWithBoth        = 0;

    public static TeamMapping load() {
        TeamMapping m = new TeamMapping();
        m.loadInternal();
        return m;
    }

    public static boolean exists() {
        return Files.isRegularFile(FILE_PATH);
    }

    // ============================================================
    // Загрузка
    // ============================================================

    private void loadInternal() {
        if (!Files.isRegularFile(FILE_PATH)) {
            System.out.println("[TeamMapping] " + FILE_PATH + " не найден — команды/компоненты не подставляются");
            return;
        }
        try {
            String text = Files.readString(FILE_PATH, StandardCharsets.UTF_8);
            List<List<String>> rows = parseCsv(text);
            if (rows.isEmpty()) return;

            List<String> header = rows.get(0);
            // Убираем BOM из первого заголовка
            if (!header.isEmpty()) {
                header.set(0, header.get(0).replace("\uFEFF", "").trim());
            }

            int colForm       = findColumnExact(header, "Форма");
            int colComponent  = findColumnExact(header, "Компонент");
            int colTeam       = findColumnExact(header, "Владелец тМИС");
            int colOverridden = findColumnExact(header, "Переопределяется формами");

            if (colForm < 0) {
                System.err.println("[TeamMapping] В " + FILE_PATH
                        + " не найдена колонка 'Форма'. Формат не распознан.");
                return;
            }
            if (colTeam < 0 && colComponent < 0) {
                System.err.println("[TeamMapping] В " + FILE_PATH
                        + " не найдены ни 'Владелец тМИС', ни 'Компонент'. "
                        + "Загружаем только пути без привязки.");
                return;
            }

            System.out.println("[TeamMapping] Колонки: "
                    + "Форма=" + colForm
                    + ", Компонент=" + colComponent
                    + ", Владелец тМИС=" + colTeam
                    + (colOverridden >= 0 ? ", Переопределяется=" + colOverridden : ""));

            // Проход 1: канонические пути (колонка «Форма»)
            for (int r = 1; r < rows.size(); r++) {
                processRow(rows.get(r), colForm, colComponent, colTeam, false);
            }

            // Проход 2: алиасы из «Переопределяется формами»
            // (только если путь ещё не был зарегистрирован напрямую)
            if (colOverridden >= 0) {
                for (int r = 1; r < rows.size(); r++) {
                    processRow(rows.get(r), colOverridden, colComponent, colTeam, true);
                }
            }

            System.out.println("[TeamMapping] Загружено " + pathToInfo.size()
                    + " путей (строк: " + totalRows
                    + ", с командой: " + rowsWithTeam
                    + ", с компонентом: " + rowsWithComponent
                    + ", с обоими: " + rowsWithBoth + ")");

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

        // Строка без team и без component — нечего сохранять
        if (team.isEmpty() && component.isEmpty()) return;

        if (!asAlias) {
            totalRows++;
            if (!team.isEmpty())      rowsWithTeam++;
            if (!component.isEmpty()) rowsWithComponent++;
            if (!team.isEmpty() && !component.isEmpty()) rowsWithBoth++;
        }

        FileInfo info = new FileInfo(team, component);

        // В колонке «Переопределяется» может быть несколько путей через \n
        for (String part : raw.split("\\r?\\n")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // Защита от мусорной кавычки в конце (встречается в реальных CSV)
            while (p.endsWith("\"")) p = p.substring(0, p.length() - 1).trim();
            if (p.isEmpty()) continue;

            String key = normPath(p);
            if (asAlias) {
                // Не перетираем канонические записи из первого прохода
                pathToInfo.putIfAbsent(key, info);
            } else {
                pathToInfo.put(key, info);
            }
        }
    }

    // ============================================================
    // Поиск
    // ============================================================

    /** Возвращает команду-владельца (или "") — legacy API. */
    public String findTeam(String component) {
        FileInfo info = findInfo(component);
        return info == null ? "" : info.team;
    }

    /** Возвращает полную запись (team + component) или null. */
    public FileInfo findInfo(String component) {
        if (component == null || component.isEmpty()) return null;

        // отрезаем префикс проекта SonarQube ("MYPROJ:/path/to/file.frm" → "/path/to/file.frm")
        String path = component;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);

        String normalized = normPath(path);

        FileInfo info = pathToInfo.get(normalized);
        if (info != null) return info;

        // попробовать без ведущего слэша
        String noSlash = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        info = pathToInfo.get(noSlash);
        if (info != null) return info;

        // попробовать со слэшем
        if (!normalized.startsWith("/")) {
            info = pathToInfo.get("/" + normalized);
        }
        return info;
    }

    // ---- Статистика для UI ----
    public int getTotalRows()         { return totalRows; }
    public int getRowsWithTeam()      { return rowsWithTeam; }
    public int getRowsWithComponent() { return rowsWithComponent; }
    public int getRowsWithBoth()      { return rowsWithBoth; }
    public int getPathCount()         { return pathToInfo.size(); }

    // ============================================================
    // Нормализация и утилиты
    // ============================================================

    private static String normPath(String s) {
        String n = s.replace('\\', '/').trim();
        // Очистка от возможных кавычек (CSV иногда бывает битым)
        while (n.startsWith("\"")) n = n.substring(1);
        while (n.endsWith("\""))   n = n.substring(0, n.length() - 1);
        n = n.toLowerCase(Locale.ROOT);
        if (!n.startsWith("/")) n = "/" + n;
        // убираем двойные слэши
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

    // ============================================================
    // CSV-парсер с поддержкой многострочных значений в кавычках
    // ============================================================

    /**
     * Устойчивый парсер:
     *   - значения в двойных кавычках могут содержать запятые и \n;
     *   - "\\"" внутри кавычек → литеральная кавычка;
     *   - одиночная кавычка ВНУТРИ незакавыченного значения трактуется
     *     как литерал (защита от битых CSV вроде "form.frm"").
     */
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
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cur.append(c);
                i++;
                continue;
            }

            // не в кавычках
            if (c == '"' && cur.length() == 0) {
                inQuotes = true;
                i++;
                continue;
            }
            if (c == ',') {
                row.add(cur.toString());
                cur.setLength(0);
                i++;
                continue;
            }
            if (c == '\n') {
                row.add(cur.toString());
                cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    result.add(row);
                }
                row = new ArrayList<>();
                i++;
                continue;
            }
            if (c == '\r') {
                i++;
                continue;
            }
            cur.append(c);
            i++;
        }

        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            result.add(row);
        }
        return result;
    }
}