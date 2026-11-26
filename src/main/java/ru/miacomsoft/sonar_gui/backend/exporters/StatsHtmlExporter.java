package ru.miacomsoft.sonar_gui.backend.exporters;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * HTML-отчёт «статистика по проектам» (аналог web/tabs/report/exportStats.js).
 * Принимает агрегат {projects: {...}, totals: {...}} и возвращает HTML.
 */
public final class StatsHtmlExporter {

    private StatsHtmlExporter() {}

    public static String export(JSONObject data) {
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());

        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"UTF-8\">\n");
        html.append("<title>Статистика по проектам — ").append(esc(now)).append("</title>\n");
        html.append("<style>\n");
        html.append("body{font-family:system-ui,sans-serif;max-width:1200px;margin:0 auto;padding:24px;background:#fafbfc;color:#172b4d;}\n");
        html.append("h1{color:#0052cc;}\n");
        html.append("h2{color:#172b4d;border-bottom:2px solid #0052cc;padding-bottom:8px;}\n");
        html.append(".project-block{background:#fff;border:1px solid #dfe1e6;border-radius:10px;padding:22px;margin-bottom:24px;}\n");
        html.append("table{width:100%;border-collapse:collapse;font-size:13px;}\n");
        html.append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #ebecf0;}\n");
        html.append("tr:hover td{background:#f4f5f7;}\n");
        html.append(".count{text-align:right;font-weight:700;color:#0052cc;}\n");
        html.append(".param-prefix{color:#6b778c;font-family:monospace;}\n");
        html.append("</style></head><body>\n");

        html.append("<h1>📊 Статистика по проектам</h1>\n");
        html.append("<div style=\"color:#6b778c;margin-bottom:24px;\">Сгенерировано: ")
                .append(esc(now)).append("</div>\n");

        JSONObject totals = data.optJSONObject("totals");
        if (totals != null) {
            html.append("<div class=\"project-block\" style=\"background:linear-gradient(135deg,#0052cc,#0747a6);color:#fff;\">\n");
            html.append("<h2 style=\"color:#fff;border-bottom-color:rgba(255,255,255,.35);\">Суммарно по всем проектам</h2>\n");
            html.append(renderBreakdown(totals));
            html.append("</div>\n");
        }

        JSONObject projects = data.optJSONObject("projects");
        if (projects != null) {
            for (String key : new TreeMap<>(toMap(projects)).keySet()) {
                JSONObject p = projects.optJSONObject(key);
                if (p == null) continue;
                if (p.has("error")) {
                    html.append("<div class=\"project-block\">\n");
                    html.append("<h2>").append(esc(key)).append("</h2>\n");
                    html.append("<div style=\"color:#de350b;\">Ошибка: ")
                            .append(esc(p.optString("error", "?"))).append("</div>\n");
                    html.append("</div>\n");
                    continue;
                }
                html.append("<div class=\"project-block\">\n");
                html.append("<h2>").append(esc(key)).append("</h2>\n");
                html.append(renderBreakdown(p));
                html.append("</div>\n");
            }
        }

        html.append("</body></html>\n");
        return html.toString();
    }

    private static String renderBreakdown(JSONObject p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table>\n<thead><tr><th>Параметр</th><th class=\"count\">Количество</th></tr></thead>\n<tbody>\n");

        appendMap(sb, p.optJSONObject("bySeverity"), "sev");
        appendMap(sb, p.optJSONObject("byQuality"),  "qual");
        appendMap(sb, p.optJSONObject("byTag"),      "tag");
        appendMap(sb, p.optJSONObject("byRule"),     "rule");

        sb.append("</tbody></table>\n");
        return sb.toString();
    }

    private static void appendMap(StringBuilder sb, JSONObject m, String prefix) {
        if (m == null) return;
        TreeMap<String, Integer> sorted = new TreeMap<>((a, b) -> {
            int va = m.optInt(a, 0);
            int vb = m.optInt(b, 0);
            if (va != vb) return vb - va;
            return a.compareTo(b);
        });
        for (String k : m.keySet()) sorted.put(k, m.optInt(k, 0));
        for (Map.Entry<String, Integer> e : sorted.entrySet()) {
            if (e.getValue() <= 0) continue;
            sb.append("<tr><td><span class=\"param-prefix\">").append(esc(prefix))
                    .append(":</span> ").append(esc(e.getKey())).append("</td>");
            sb.append("<td class=\"count\">").append(e.getValue()).append("</td></tr>\n");
        }
    }

    private static Map<String, Object> toMap(JSONObject o) {
        Map<String, Object> m = new TreeMap<>();
        for (String k : o.keySet()) m.put(k, Boolean.TRUE);
        return m;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}