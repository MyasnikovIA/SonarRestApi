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
 * Парсит product_mapping.csv (связь продуктов/команд с проектами SonarQube).
 * Позволяет по Sonar_ID (ключу проекта) получить информацию о продукте и команде.
 */
public class ProductMapping {

    public static final Path FILE_PATH = Path.of("product_mapping.csv").toAbsolutePath().normalize();

    public static final class ProductInfo {
        public final String product;
        public final String repository;
        public final String responsible;
        public final String subproduct;
        public final String teamOwner;
        public final String sonarId;

        public ProductInfo(String product, String repository, String responsible,
                           String subproduct, String teamOwner, String sonarId) {
            this.product    = (product == null ? "" : product.trim());
            this.repository = (repository == null ? "" : repository.trim());
            this.responsible= (responsible == null ? "" : responsible.trim());
            this.subproduct = (subproduct == null ? "" : subproduct.trim());
            this.teamOwner  = (teamOwner == null ? "" : teamOwner.trim());
            this.sonarId    = (sonarId == null ? "" : sonarId.trim());
        }

        @Override public String toString() {
            return "ProductInfo{product='" + product + "', team='" + teamOwner + "', sonarId='" + sonarId + "'}";
        }
    }

    // Ключ: Sonar_ID (ключ проекта в SonarQube), Значение: информация о продукте
    private final Map<String, ProductInfo> sonarIdToInfo = new HashMap<>();
    private int totalRows = 0;

    public static ProductMapping load() {
        ProductMapping m = new ProductMapping();
        m.loadInternal();
        return m;
    }

    public static boolean exists() {
        return Files.isRegularFile(FILE_PATH);
    }

    private void loadInternal() {
        if (!Files.isRegularFile(FILE_PATH)) {
            System.out.println("[ProductMapping] " + FILE_PATH + " не найден");
            return;
        }
        try {
            String text = Files.readString(FILE_PATH, StandardCharsets.UTF_8);
            List<List<String>> rows = parseCsv(text);
            if (rows.isEmpty()) return;

            List<String> header = rows.get(0);
            if (!header.isEmpty()) header.set(0, header.get(0).replace("\uFEFF", "").trim());

            // Поиск нужных колонок
            int colProduct    = findColumn(header, "Продукт");
            int colRepo       = findColumn(header, "Репозиторий");
            int colResponsible= findColumn(header, "Ответственный за обработку результатов сканирования");
            int colSubproduct = findColumn(header, "Субпродукт");
            int colTeamOwner  = findColumn(header, "Команда-владелец");
            int colSonarId    = findColumn(header, "Sonar_ID");

            if (colSonarId < 0) {
                System.err.println("[ProductMapping] Не найдена колонка 'Sonar_ID'");
                return;
            }

            for (int r = 1; r < rows.size(); r++) {
                List<String> cells = rows.get(r);
                if (cells.size() <= colSonarId) continue;

                String sonarId = cells.get(colSonarId).trim();
                if (sonarId.isEmpty()) continue; // Пропускаем строки без Sonar_ID

                String product    = getCol(cells, colProduct);
                String repo       = getCol(cells, colRepo);
                String resp       = getCol(cells, colResponsible);
                String subproduct = getCol(cells, colSubproduct);
                String teamOwner  = getCol(cells, colTeamOwner);

                ProductInfo info = new ProductInfo(product, repo, resp, subproduct, teamOwner, sonarId);
                sonarIdToInfo.put(sonarId, info);
                totalRows++;
            }

            System.out.println("[ProductMapping] Загружено " + sonarIdToInfo.size() + " записей о продуктах");
        } catch (Exception e) {
            System.err.println("[ProductMapping] Ошибка чтения " + FILE_PATH + ": " + e.getMessage());
        }
    }

    private static String getCol(List<String> cells, int col) {
        if (col < 0 || cells.size() <= col) return "";
        return cells.get(col);
    }

    /**
     * Возвращает информацию о продукте по ключу проекта SonarQube.
     */
    public ProductInfo findInfo(String sonarProjectKey) {
        if (sonarProjectKey == null || sonarProjectKey.isEmpty()) return null;
        return sonarIdToInfo.get(sonarProjectKey.trim());
    }

    public int getTotalRows() { return totalRows; }
    public int getProjectsCount() { return sonarIdToInfo.size(); }

    private static String normalizeHeader(String s) {
        if (s == null) return "";
        return s.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
    }

    private static int findColumn(List<String> header, String name) {
        String target = normalizeHeader(name);
        for (int i = 0; i < header.size(); i++) {
            if (normalizeHeader(header.get(i)).equals(target)) return i;
        }
        return -1;
    }

    // Универсальный CSV-парсер (обрабатывает кавычки и переводы строк внутри ячеек)
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