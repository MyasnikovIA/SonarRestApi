package ru.miacomsoft.sonar.server.exporters;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Генерирует HTML-отчёт статистики по проектам.
 * Формат: карточки-категории qual → sev → tag, внутри каждой — карточки значений.
 * Для project-блоков карточки значений кликабельны и ведут на SonarQube UI.
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

        // -------- параметры для ссылок на SonarQube --------
        String sonarBaseUrl = data.optString("sonarBaseUrl", "");
        String branch       = data.optString("branch", "");
        String statusesRaw  = data.optString("statuses", "");
        // для URL используем MQR-валидные статусы, чтобы ссылки открывались без 400
        String statusesUrl  = sanitizeStatusesForUrl(statusesRaw);

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
        html.append("<div class=\"row\"><span class=\"label\">Статусы:</span><b>")
                .append(statusesRaw.isEmpty() ? "все" : esc(statusesRaw)).append("</b></div>\n");
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
            // totals — без ссылок: projectKey пустой, sonarBaseUrl пустой.
            html.append(renderUnifiedBreakdown(totals, "", "", statusesUrl, ""));
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
                    // project-block — со ссылками
                    html.append(renderUnifiedBreakdown(p, sonarBaseUrl, key, statusesUrl, branch));
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
    // Рендер секций-карточек
    // ============================================================

    private static String renderUnifiedBreakdown(JSONObject p,
                                                 String sonarBaseUrl,
                                                 String projectKey,
                                                 String statuses,
                                                 String branch) {
        JSONObject bySev  = p.optJSONObject("bySeverity");
        JSONObject byQual = p.optJSONObject("byQuality");
        JSONObject byTag  = p.optJSONObject("byTag");

        if (isEmpty(byQual) && isEmpty(bySev) && isEmpty(byTag)) {
            return "<div class=\"no-data\">— нет данных —</div>\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"breakdown-cards\">\n");
        sb.append(renderCategoryCard("qual", "Качество (qual)", byQual, "quality",
                sonarBaseUrl, projectKey, statuses, branch));
        sb.append(renderCategoryCard("sev",  "Severity (sev)",  bySev,  "severity",
                sonarBaseUrl, projectKey, statuses, branch));
        sb.append(renderCategoryCard("tag",  "Теги (tag)",      byTag,  "tag",
                sonarBaseUrl, projectKey, statuses, branch));
        sb.append("</div>\n");
        return sb.toString();
    }

    private static String renderCategoryCard(String prefix,
                                             String label,
                                             JSONObject values,
                                             String cssClass,
                                             String sonarBaseUrl,
                                             String projectKey,
                                             String statuses,
                                             String branch) {
        if (isEmpty(values)) return "";

        List<Row> rows = new ArrayList<>();
        for (String key : values.keySet()) {
            int count = values.optInt(key, 0);
            if (count <= 0) continue;
            rows.add(new Row(key, count));
        }
        rows.sort((a, b) -> {
            if (b.count != a.count) return b.count - a.count;
            return a.param.compareTo(b.param);
        });
        if (rows.isEmpty()) return "";

        int totalInCategory = 0;
        for (Row r : rows) totalInCategory += r.count;

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"breakdown-category breakdown-category-")
                .append(cssClass).append("\">\n");

        sb.append("<div class=\"breakdown-category-header\">")
                .append("<span class=\"category-label\">").append(esc(label)).append("</span>")
                .append("<span class=\"category-total\">").append(totalInCategory).append("</span>")
                .append("</div>\n");

        sb.append("<div class=\"breakdown-values\">\n");
        for (Row r : rows) {
            String url = buildCardUrl(prefix, r.param, sonarBaseUrl, projectKey, statuses, branch);

            if (url != null) {
                sb.append("<a href=\"").append(esc(url)).append("\" ")
                        .append("class=\"breakdown-card breakdown-card-link\" ")
                        .append("target=\"_blank\" rel=\"noopener noreferrer\" ")
                        .append("title=\"Открыть в SonarQube\">");
            } else {
                sb.append("<div class=\"breakdown-card\">");
            }

            sb.append("<div class=\"breakdown-card-title\">")
                    .append("<span class=\"param-prefix\">").append(esc(prefix)).append(":</span> ")
                    .append("<span class=\"param-value\">").append(esc(r.param)).append("</span>")
                    .append("</div>")
                    .append("<div class=\"breakdown-card-count\">").append(r.count).append("</div>");

            sb.append(url != null ? "</a>\n" : "</div>\n");
        }
        sb.append("</div>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    // ============================================================
    // URL → SonarQube
    // ============================================================

    /**
     * Формирует URL на страницу /project/issues для карточки.
     *
     * Пример результирующего URL (для тега not-require-fix):
     *   {base}/project/issues
     *     ?issueStatuses=ACCEPTED%2CCONFIRMED%2CFALSE_POSITIVE%2CFIXED%2COPEN
     *     &tags=not-require-fix
     *     &branch=master
     *     &id=MEDMIS-MISmobile.max-service
     *
     * Порядок параметров (как в SonarQube UI):
     *   1) issueStatuses
     *   2) impactSoftwareQualities | impactSeverities | tags
     *   3) branch
     *   4) id (projectKey)
     *
     * Возвращает null, если ссылку построить нельзя
     * (нет base URL / projectKey / значения, либо значение = UNKNOWN) —
     * в этом случае карточка рендерится как обычный <div>.
     */
    private static String buildCardUrl(String prefix, String rawValue,
                                       String sonarBaseUrl, String projectKey,
                                       String statuses, String branch) {
        if (sonarBaseUrl == null || sonarBaseUrl.isBlank()) return null;
        if (projectKey == null || projectKey.isBlank()) return null;
        if (rawValue == null || rawValue.isEmpty()) return null;
        if ("UNKNOWN".equalsIgnoreCase(rawValue)) return null;

        String base = sonarBaseUrl.replaceAll("/+$", "");

        List<String> params = new ArrayList<>();

        // 1. issueStatuses — первым (как в примере SonarQube UI)
        if (statuses != null && !statuses.isEmpty()) {
            params.add("issueStatuses=" + urlEnc(statuses));
        }

        // 2. Параметр фильтра (qual / sev / tag)
        switch (prefix) {
            case "qual":
                params.add("impactSoftwareQualities=" + urlEnc(rawValue));
                break;
            case "sev":
                params.add("impactSeverities=" + urlEnc(rawValue));
                break;
            case "tag":
                params.add("tags=" + urlEnc(rawValue));
                break;
            default:
                return null;
        }

        // 3. branch
        if (branch != null && !branch.isEmpty()) {
            params.add("branch=" + urlEnc(branch));
        }

        // 4. id проекта
        params.add("id=" + urlEnc(projectKey));

        return base + "/project/issues?" + String.join("&", params);
    }

    /**
     * Приводит произвольный набор статусов к MQR-валидным значениям,
     * принимаемым SonarQube в параметре issueStatuses, и сортирует их
     * по алфавиту — ровно так, как это делает сам SonarQube UI.
     *
     * Пример: "OPEN,WONTFIX,CONFIRMED,FALSE_POSITIVE,FIXED,ACCEPTED,REOPENED,CLOSED"
     *   → "ACCEPTED,CONFIRMED,FALSE_POSITIVE,FIXED,OPEN"
     *   → URL: issueStatuses=ACCEPTED%2CCONFIRMED%2CFALSE_POSITIVE%2CFIXED%2COPEN
     *
     * Используем TreeSet вместо LinkedHashSet, чтобы порядок был стабильным
     * и совпадал с эталонным SonarQube. Это влияет только на внешний вид URL —
     * на содержимое страницы SonarQube порядок статусов не влияет.
     */
    private static String sanitizeStatusesForUrl(String statuses) {
        if (statuses == null || statuses.isEmpty()) return "";
        Set<String> valid = new TreeSet<>();
        for (String s : statuses.split(",")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            switch (t) {
                case "REOPENED": valid.add("OPEN");            break;
                case "CLOSED":   valid.add("FIXED");           break;
                case "WONTFIX":  valid.add("FALSE_POSITIVE");  break;
                case "RESOLVED": valid.add("FIXED");           break;
                default:
                    if (Set.of("OPEN", "CONFIRMED", "FALSE_POSITIVE",
                            "ACCEPTED", "FIXED").contains(t)) {
                        valid.add(t);
                    }
            }
        }
        return String.join(",", valid);
    }

    private static String urlEnc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean isEmpty(JSONObject o) {
        return o == null || o.isEmpty();
    }

    // ============================================================
    // Прочее
    // ============================================================

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
    // CSS
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
                    ".breakdown-cards{display:flex;flex-direction:column;gap:16px;}\n" +
                    ".breakdown-category{background:#f9fafb;border:1px solid #dfe1e6;border-radius:10px;padding:14px 16px 16px;}\n" +
                    ".breakdown-category-header{display:flex;justify-content:space-between;align-items:center;font-size:12px;text-transform:uppercase;letter-spacing:0.6px;font-weight:700;color:#6b778c;padding-bottom:10px;margin-bottom:12px;border-bottom:1px dashed #dfe1e6;}\n" +
                    ".breakdown-category-header .category-label{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;}\n" +
                    ".breakdown-category-header .category-total{font-family:monospace;font-size:13px;font-weight:700;color:#0052cc;background:#fff;border:1px solid #dfe1e6;border-radius:6px;padding:2px 10px;font-variant-numeric:tabular-nums;}\n" +
                    ".breakdown-values{display:flex;flex-wrap:wrap;gap:10px;}\n" +
                    ".breakdown-card{display:flex;flex-direction:column;gap:4px;background:#fff;border:1px solid #dfe1e6;border-radius:8px;padding:10px 14px;min-width:140px;flex:0 0 auto;transition:box-shadow .15s,border-color .15s;}\n" +
                    ".breakdown-card:hover{border-color:#b3bac5;box-shadow:0 2px 6px rgba(0,0,0,0.06);}\n" +
                    ".breakdown-card-title{font-family:monospace;font-size:12px;line-height:1.3;}\n" +
                    ".breakdown-card-title .param-prefix{color:#6b778c;font-weight:600;}\n" +
                    ".breakdown-card-title .param-value{color:#172b4d;}\n" +
                    ".breakdown-card-count{font-size:20px;font-weight:700;color:#0052cc;font-variant-numeric:tabular-nums;}\n" +
                    ".no-data{text-align:center;color:#6b778c;font-style:italic;padding:20px;font-size:13px;}\n" +
                    ".footer{text-align:center;color:#6b778c;font-size:12px;margin-top:40px;padding-top:20px;border-top:1px solid #dfe1e6;}\n" +
                    "a.breakdown-card{text-decoration:none;color:inherit;cursor:pointer;}\n" +
                    "a.breakdown-card:visited{color:inherit;}\n" +
                    "a.breakdown-card:hover{border-color:#0052cc;box-shadow:0 3px 10px rgba(0,82,204,0.18);text-decoration:none;}\n" +
                    "a.breakdown-card:focus-visible{outline:2px solid #0052cc;outline-offset:2px;}\n" +
                    "a.breakdown-card .breakdown-card-title::after{content:' ↗';font-size:10px;color:#6b778c;margin-left:2px;}\n" +
                    ".summary-block .breakdown-category{background:rgba(255,255,255,0.08);border-color:rgba(255,255,255,0.2);}\n" +
                    ".summary-block .breakdown-category-header{color:rgba(255,255,255,0.85);border-bottom-color:rgba(255,255,255,0.25);}\n" +
                    ".summary-block .breakdown-category-header .category-total{color:#fff;background:rgba(255,255,255,0.15);border-color:rgba(255,255,255,0.35);}\n" +
                    ".summary-block .breakdown-card{background:rgba(255,255,255,0.15);border-color:rgba(255,255,255,0.3);}\n" +
                    ".summary-block .breakdown-card:hover{border-color:rgba(255,255,255,0.5);box-shadow:none;}\n" +
                    ".summary-block .breakdown-card-title .param-prefix{color:rgba(255,255,255,0.7);}\n" +
                    ".summary-block .breakdown-card-title .param-value{color:#fff;}\n" +
                    ".summary-block .breakdown-card-count{color:#fff;}\n" +
                    ".summary-block .no-data{color:rgba(255,255,255,0.8);}\n" +
                    "@media print{body{background:#fff;}.project-block,.summary-block,.breakdown-category,.breakdown-card{break-inside:avoid;box-shadow:none;}}\n";
}