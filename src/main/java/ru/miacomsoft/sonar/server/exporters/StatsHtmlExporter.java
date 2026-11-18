package ru.miacomsoft.sonar.server.exporters;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Генерирует HTML-отчёт статистики по проектам.
 * Формат: единая таблица параметров вида "sev: HIGH", "qual: MAINTAINABILITY", "tag: require-fix".
 */
public final class StatsHtmlExporter {

    private StatsHtmlExporter() {}

    public static String export(JSONObject data) {
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());

        JSONObject projects = data.optJSONObject("projects");
        JSONObject totals   = data.optJSONObject("totals");
        if (projects == null) projects = new JSONObject();
        if (totals == null)   totals = new JSONObject();

        int requested = data.optInt("projectsRequested", 0);
        int ok        = data.optInt("projectsOk", 0);
        int failed    = requested - ok;

        List<String> keys = new ArrayList<>(projects.keySet());
        Collections.sort(keys);

        StringBuilder html = new StringBuilder(16384);

        // ---------- HEAD ----------
        html.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Статистика по проектам — ").append(esc(now)).append("</title>\n");
        html.append("<style>\n").append(STYLES).append("</style>\n</head>\n<body>\n");

        // ---------- HEADER ----------
        html.append("<h1>📊 Статистика по проектам</h1>\n");
        html.append("<div class=\"meta\">Сгенерировано: ").append(esc(now)).append("</div>\n");

        // ---------- FILTERS BOX ----------
        html.append("<div class=\"filters-box\">\n");
        html.append("<div class=\"row\"><span class=\"label\">Проектов запрошено:</span><b>")
                .append(requested).append("</b></div>\n");
        html.append("<div class=\"row\"><span class=\"label\">Успешно:</span><b>").append(ok).append("</b>");
        if (failed > 0) {
            html.append(" · <span style=\"color:#de350b;\">Ошибок: ").append(failed).append("</span>");
        }
        html.append("</div>\n");

        String incl = tagsToString(data.optJSONArray("includedTags"));
        if (!incl.isEmpty()) {
            html.append("<div class=\"row\"><span class=\"label\">Включённые теги:</span><b>")
                    .append(esc(incl)).append("</b></div>\n");
        }
        String excl = tagsToString(data.optJSONArray("excludedTags"));
        if (!excl.isEmpty()) {
            html.append("<div class=\"row\"><span class=\"label\">Исключённые теги:</span><b>")
                    .append(esc(excl)).append("</b></div>\n");
        }
        String statuses = data.optString("statuses", "");
        html.append("<div class=\"row\"><span class=\"label\">Статусы:</span><b>")
                .append(statuses.isEmpty() ? "все" : esc(statuses)).append("</b></div>\n");
        html.append("</div>\n");

        // ---------- TOTALS ----------
        if (totals.has("totalIssues")) {
            html.append("<div class=\"summary-block\">\n");
            html.append("<h2>📈 Суммарно по всем проектам</h2>\n");
            html.append("<div class=\"totals-row\">\n");
            html.append("<div class=\"total-badge\"><span class=\"total-label\">Всего issues</span>")
                    .append("<span class=\"total-value\">").append(totals.optInt("totalIssues", 0))
                    .append("</span></div>\n");
            html.append("<div class=\"total-badge\"><span class=\"total-label\">Всего файлов</span>")
                    .append("<span class=\"total-value\">").append(totals.optInt("totalFiles", 0))
                    .append("</span></div>\n");
            html.append("</div>\n");
            html.append(renderUnifiedBreakdown(totals));
            html.append("</div>\n");
        }

        // ---------- PROJECTS ----------
        html.append("<h2 style=\"color:#172b4d;border-bottom:2px solid #0052cc;margin-top:32px;font-size:20px;\">")
                .append("Проекты (").append(keys.size()).append(")</h2>\n");

        if (keys.isEmpty()) {
            html.append("<div style=\"text-align:center;color:#6b778c;padding:40px;font-style:italic;\">")
                    .append("Нет данных по проектам</div>\n");
        } else {
            for (String key : keys) {
                JSONObject p = projects.optJSONObject(key);
                if (p == null) p = new JSONObject();

                html.append("<div class=\"project-block\">\n");
                html.append("<div class=\"project-header\"><span class=\"project-icon\">📁</span>")
                        .append("<h2>").append(esc(key)).append("</h2></div>\n");

                if (p.has("error")) {
                    html.append("<div style=\"color:#de350b;padding:12px;background:#ffebe6;")
                            .append("border-radius:6px;\">❌ Ошибка: ")
                            .append(esc(p.optString("error", ""))).append("</div>\n");
                } else {
                    html.append("<div class=\"totals-row\">\n");
                    html.append("<div class=\"total-badge\"><span class=\"total-label\">Всего issues</span>")
                            .append("<span class=\"total-value\">").append(p.optInt("totalIssues", 0))
                            .append("</span></div>\n");
                    html.append("<div class=\"total-badge\"><span class=\"total-label\">Всего файлов</span>")
                            .append("<span class=\"total-value\">").append(p.optInt("totalFiles", 0))
                            .append("</span></div>\n");
                    html.append("</div>\n");
                    html.append(renderUnifiedBreakdown(p));
                }

                html.append("</div>\n");
            }
        }

        // ---------- FOOTER ----------
        html.append("<div class=\"footer\">SonarQube Stats Report · ").append(esc(now)).append("</div>\n");
        html.append("</body>\n</html>\n");

        return html.toString();
    }

    // ============================================================

    private static String renderUnifiedBreakdown(JSONObject p) {
        List<Row> rows = new ArrayList<>();
        rows.addAll(buildRows(p.optJSONObject("bySeverity"), "sev"));
        rows.addAll(buildRows(p.optJSONObject("byQuality"),  "qual"));
        rows.addAll(buildRows(p.optJSONObject("byTag"),      "tag"));

        if (rows.isEmpty()) {
            return "<div class=\"no-data\">— нет данных —</div>\n";
        }

        rows.sort((a, b) -> {
            if (b.count != a.count) return b.count - a.count;
            return a.param.compareTo(b.param);
        });

        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"breakdown-table\">\n");
        sb.append("<thead><tr><th>Параметр</th><th style=\"text-align:right;\">Количество</th></tr></thead>\n");
        sb.append("<tbody>\n");

        for (Row r : rows) {
            int colon = r.param.indexOf(": ");
            String prefix = colon >= 0 ? r.param.substring(0, colon) : r.param;
            String value  = colon >= 0 ? r.param.substring(colon + 2) : "";

            sb.append("<tr><td>")
                    .append("<span class=\"param-prefix\">").append(esc(prefix)).append(":</span> ")
                    .append("<span class=\"param-value\">").append(esc(value)).append("</span>")
                    .append("</td><td class=\"param-count\">").append(r.count).append("</td></tr>\n");
        }

        sb.append("</tbody>\n</table>\n");
        return sb.toString();
    }

    private static List<Row> buildRows(JSONObject obj, String prefix) {
        List<Row> rows = new ArrayList<>();
        if (obj == null) return rows;
        for (String key : obj.keySet()) {
            int count = obj.optInt(key, 0);
            if (count <= 0) continue;
            rows.add(new Row(prefix + ": " + key, count));
        }
        return rows;
    }

    private static String tagsToString(org.json.JSONArray arr) {
        if (arr == null || arr.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(arr.optString(i, ""));
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static class Row {
        final String param;
        final int count;
        Row(String param, int count) { this.param = param; this.count = count; }
    }

    // ============================================================

    private static final String STYLES =
            "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;" +
                    "line-height:1.5;color:#172b4d;max-width:1200px;margin:0 auto;padding:24px 20px 40px;background:#fafbfc;}\n" +
                    "h1{color:#0052cc;margin:0 0 4px;font-size:26px;}\n" +
                    "h2{color:#172b4d;margin:0 0 16px;font-size:18px;border-bottom:2px solid #0052cc;padding-bottom:8px;}\n" +
                    ".meta{color:#6b778c;font-size:13px;margin-bottom:24px;}\n" +
                    ".filters-box{background:#fff;border:1px solid #dfe1e6;border-radius:8px;padding:14px 18px;margin-bottom:24px;font-size:13px;line-height:1.8;}\n" +
                    ".filters-box .row{display:flex;gap:8px;}\n" +
                    ".filters-box .label{color:#6b778c;min-width:140px;}\n" +
                    ".filters-box b{color:#172b4d;}\n" +
                    ".summary-block{background:linear-gradient(135deg,#0052cc 0%,#0747a6 100%);color:#fff;border-radius:10px;padding:22px 26px;margin-bottom:32px;box-shadow:0 4px 14px rgba(0,82,204,0.2);}\n" +
                    ".summary-block h2{color:#fff;border-bottom-color:rgba(255,255,255,0.35);}\n" +
                    ".summary-block .breakdown-table{color:#fff;}\n" +
                    ".summary-block .breakdown-table th{color:rgba(255,255,255,0.85);border-bottom-color:rgba(255,255,255,0.3);}\n" +
                    ".summary-block .breakdown-table td{border-bottom-color:rgba(255,255,255,0.15);}\n" +
                    ".summary-block .param-prefix{color:rgba(255,255,255,0.7);}\n" +
                    ".summary-block .param-value{color:#fff;}\n" +
                    ".summary-block .param-count{color:#fff;}\n" +
                    ".summary-block .total-badge{background:rgba(255,255,255,0.15);border-color:rgba(255,255,255,0.35);}\n" +
                    ".summary-block .total-label{color:rgba(255,255,255,0.85);}\n" +
                    ".summary-block .total-value{color:#fff;}\n" +
                    ".project-block{background:#fff;border:1px solid #dfe1e6;border-radius:10px;padding:22px 26px;margin-bottom:24px;box-shadow:0 1px 3px rgba(0,0,0,0.06);}\n" +
                    ".project-header{display:flex;align-items:center;gap:8px;margin-bottom:18px;padding-bottom:4px;}\n" +
                    ".project-header h2{margin:0;border:none;padding:0;font-size:17px;font-family:monospace;word-break:break-all;}\n" +
                    ".project-icon{font-size:20px;}\n" +
                    ".totals-row{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:20px;}\n" +
                    ".total-badge{display:flex;flex-direction:column;align-items:center;background:#f4f5f7;border:1px solid #dfe1e6;border-radius:8px;padding:12px 24px;min-width:130px;}\n" +
                    ".total-label{font-size:10.5px;color:#6b778c;text-transform:uppercase;letter-spacing:0.6px;font-weight:600;}\n" +
                    ".total-value{font-size:26px;font-weight:700;color:#0052cc;margin-top:2px;font-variant-numeric:tabular-nums;}\n" +
                    ".breakdown-table{width:100%;border-collapse:collapse;font-size:13px;}\n" +
                    ".breakdown-table th{text-align:left;color:#6b778c;font-weight:700;padding:8px 12px;border-bottom:2px solid #dfe1e6;font-size:11px;text-transform:uppercase;letter-spacing:0.3px;}\n" +
                    ".breakdown-table td{padding:8px 12px;border-bottom:1px solid #ebecf0;}\n" +
                    ".breakdown-table tr:last-child td{border-bottom:none;}\n" +
                    ".breakdown-table tr:hover td{background:#f4f5f7;}\n" +
                    ".param-prefix{color:#6b778c;font-family:monospace;font-size:12px;font-weight:600;}\n" +
                    ".param-value{color:#172b4d;font-family:monospace;font-size:12.5px;}\n" +
                    ".param-count{text-align:right;font-weight:700;font-variant-numeric:tabular-nums;font-size:14px;color:#0052cc;}\n" +
                    ".no-data{text-align:center;color:#6b778c;font-style:italic;padding:20px;font-size:13px;}\n" +
                    ".footer{text-align:center;color:#6b778c;font-size:12px;margin-top:40px;padding-top:20px;border-top:1px solid #dfe1e6;}\n" +
                    "@media print{body{background:#fff;}.project-block,.summary-block{break-inside:avoid;box-shadow:none;}}\n";
}