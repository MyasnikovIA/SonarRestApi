package ru.miacomsoft.sonar.server.exporters;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Генерирует HTML-отчёт статистики по проектам.
 * Формат: карточки-категории qual → sev → rule → tag, внутри каждой — карточки значений.
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

        String sonarBaseUrl = data.optString("sonarBaseUrl", "");
        String branch       = data.optString("branch", "");
        String statusesRaw  = data.optString("statuses", "");
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
        html.append("<div class=\"row\"><span class=\"label\">Успешно:</span><b>")
                .append(ok).append("</b>");
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
            html.append(renderUnifiedBreakdown(totals, "", "", statusesUrl, ""));
            html.append("</div>\n");
        }

        // ---------- PROJECTS ----------
        html.append("<h2 style=\"color:#172b4d;border-bottom:2px solid #0052cc;")
                .append("margin-top:32px;font-size:20px;\">")
                .append("Проекты (").append(keys.size()).append(")</h2>\n");

        if (keys.isEmpty()) {
            html.append("<div style=\"text-align:center;color:#6b778c;padding:40px;")
                    .append("font-style:italic;\">Нет данных по проектам</div>\n");
        } else {
            for (String key : keys) {
                JSONObject p = projects.optJSONObject(key);
                if (p == null) p = new JSONObject();

                String dashboardUrl = null;
                if (sonarBaseUrl != null && !sonarBaseUrl.isBlank()) {
                    String base = sonarBaseUrl.replaceAll("/+$", "");
                    dashboardUrl = base + "/dashboard?id=" + urlEnc(key)
                            + "&codeScope=overall";
                }

                html.append("<div class=\"project-block\">\n");
                html.append("<div class=\"project-header\"><span class=\"project-icon\">📁</span>");
                if (dashboardUrl != null) {
                    html.append("<h2><a href=\"").append(esc(dashboardUrl))
                            .append("\" target=\"_blank\" rel=\"noopener noreferrer\" ")
                            .append("title=\"Открыть dashboard проекта в SonarQube\">")
                            .append(esc(key)).append("</a></h2>");
                } else {
                    html.append("<h2>").append(esc(key)).append("</h2>");
                }
                html.append("</div>\n");

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
                    html.append(renderUnifiedBreakdown(p, sonarBaseUrl, key, statusesUrl, branch));
                }

                html.append("</div>\n");
            }
        }

        // ---------- FOOTER ----------
        html.append("<div class=\"footer\">SonarQube Stats Report · ")
                .append(esc(now)).append("</div>\n");

        html.append(MULTI_SELECT_SCRIPT);

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
        JSONObject byRule = p.optJSONObject("byRule");
        JSONObject byTag  = p.optJSONObject("byTag");

        if (isEmpty(byQual) && isEmpty(bySev) && isEmpty(byRule) && isEmpty(byTag)) {
            return "<div class=\"no-data\">— нет данных —</div>\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"breakdown-cards\">\n");
        sb.append(renderCategoryCard("qual", "Качество (qual)", byQual, "quality",
                sonarBaseUrl, projectKey, statuses, branch));
        sb.append(renderCategoryCard("sev",  "Severity (sev)",  bySev,  "severity",
                sonarBaseUrl, projectKey, statuses, branch));
        sb.append(renderCategoryCard("rule", "Правила (rule)",  byRule, "rule",
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

        // Теперь и "tag", и "rule" являются мультивыборными
        boolean isSelectable = "tag".equals(prefix) || "rule".equals(prefix);
        boolean canBuildUrls = sonarBaseUrl != null && !sonarBaseUrl.isBlank()
                && projectKey != null && !projectKey.isBlank();

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"breakdown-category breakdown-category-")
                .append(cssClass).append("\">\n");

        // ---------- header ----------
        sb.append("<div class=\"breakdown-category-header\">")
                .append("<div style=\"display:flex; align-items:center; gap:8px; flex-wrap:wrap;\">")
                .append("<span class=\"category-label\">").append(esc(label)).append("</span>");

        if (isSelectable && canBuildUrls) {
            String base = sonarBaseUrl.replaceAll("/+$", "");
            String filterType = "tag".equals(prefix) ? "tags" : "rules";

            sb.append("<button class=\"multi-sel-open\" type=\"button\" ")
                    .append("style=\"display:none;\" ")
                    .append("data-base=\"").append(esc(base)).append("\" ")
                    .append("data-project=\"").append(esc(projectKey)).append("\" ")
                    .append("data-filter-type=\"").append(filterType).append("\" ")
                    .append("data-branch=\"").append(esc(branch == null ? "" : branch)).append("\" ")
                    .append("title=\"Открыть SonarQube с выбранными параметрами\">")
                    .append("📂 <span class=\"multi-sel-label\">")
                    .append(esc(projectKey)).append(": —</span>")
                    .append("</button>");
        }

        sb.append("</div>")
                .append("<span class=\"category-total\">").append(totalInCategory).append("</span>")
                .append("</div>\n");

        // ---------- values ----------
        sb.append("<div class=\"breakdown-values\">\n");
        for (Row r : rows) {
            String url = buildCardUrl(prefix, r.param, sonarBaseUrl, projectKey, statuses, branch);

            if (isSelectable && url != null) {
                // Мультивыборный элемент (тег или правило)
                sb.append("<div class=\"breakdown-card breakdown-card-select\">")
                        .append("<label class=\"multi-sel-cb-label\" ")
                        .append("title=\"Добавить в мультивыбор\">")
                        .append("<input type=\"checkbox\" class=\"multi-sel-cb\" ")
                        .append("value=\"").append(esc(r.param)).append("\">")
                        .append("</label>")
                        .append("<a href=\"").append(esc(url)).append("\" ")
                        .append("class=\"breakdown-card-link breakdown-card-select-link\" ")
                        .append("target=\"_blank\" rel=\"noopener noreferrer\" ")
                        .append("title=\"Открыть SonarQube с одним значением\">")
                        .append("<div class=\"breakdown-card-title\">")
                        .append("<span class=\"param-prefix\">").append(esc(prefix)).append(":</span> ")
                        .append("<span class=\"param-value\">").append(esc(r.param)).append("</span>")
                        .append("</div>")
                        .append("<div class=\"breakdown-card-count\">").append(r.count).append("</div>")
                        .append("</a>")
                        .append("</div>\n");
            } else if (url != null) {
                sb.append("<a href=\"").append(esc(url)).append("\" ")
                        .append("class=\"breakdown-card breakdown-card-link\" ")
                        .append("target=\"_blank\" rel=\"noopener noreferrer\" ")
                        .append("title=\"Открыть в SonarQube\">")
                        .append("<div class=\"breakdown-card-title\">")
                        .append("<span class=\"param-prefix\">").append(esc(prefix)).append(":</span> ")
                        .append("<span class=\"param-value\">").append(esc(r.param)).append("</span>")
                        .append("</div>")
                        .append("<div class=\"breakdown-card-count\">").append(r.count).append("</div>")
                        .append("</a>\n");
            } else {
                sb.append("<div class=\"breakdown-card\">")
                        .append("<div class=\"breakdown-card-title\">")
                        .append("<span class=\"param-prefix\">").append(esc(prefix)).append(":</span> ")
                        .append("<span class=\"param-value\">").append(esc(r.param)).append("</span>")
                        .append("</div>")
                        .append("<div class=\"breakdown-card-count\">").append(r.count).append("</div>")
                        .append("</div>\n");
            }
        }
        sb.append("</div>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    // ============================================================
    // URL → SonarQube (одиночная карточка)
    // ============================================================

    private static String buildCardUrl(String prefix, String rawValue,
                                       String sonarBaseUrl, String projectKey,
                                       String statuses, String branch) {
        if (sonarBaseUrl == null || sonarBaseUrl.isBlank()) return null;
        if (projectKey == null || projectKey.isBlank()) return null;
        if (rawValue == null || rawValue.isEmpty()) return null;
        if ("UNKNOWN".equalsIgnoreCase(rawValue)) return null;

        String base = sonarBaseUrl.replaceAll("/+$", "");

        List<String> params = new ArrayList<>();

        if (statuses != null && !statuses.isEmpty()) {
            params.add("issueStatuses=" + urlEnc(statuses));
        }

        switch (prefix) {
            case "qual":
                params.add("impactSoftwareQualities=" + urlEnc(rawValue));
                break;
            case "sev":
                params.add("impactSeverities=" + urlEnc(rawValue));
                break;
            case "rule":
                params.add("rules=" + urlEnc(rawValue));
                break;
            case "tag":
                params.add("tags=" + urlEnc(rawValue));
                break;
            default:
                return null;
        }

        if (branch != null && !branch.isEmpty()) {
            params.add("branch=" + urlEnc(branch));
        }

        params.add("id=" + urlEnc(projectKey));

        return base + "/project/issues?" + String.join("&", params);
    }

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
    // JavaScript для мультивыбора
    // ============================================================

    private static final String MULTI_SELECT_SCRIPT =
            "<script>\n" +
                    "(function() {\n" +
                    "  function buildUrl(btn, values) {\n" +
                    "    var base = btn.dataset.base || '';\n" +
                    "    var proj = btn.dataset.project || '';\n" +
                    "    var fType = btn.dataset.filterType || 'tags';\n" +
                    "    var br   = btn.dataset.branch || '';\n" +
                    "    var params = [];\n" +
                    "    params.push(fType + '=' + encodeURIComponent(values.join(',')));\n" +
                    "    if (br) params.push('branch=' + encodeURIComponent(br));\n" +
                    "    params.push('id=' + encodeURIComponent(proj));\n" +
                    "    return base + '/project/issues?' + params.join('&');\n" +
                    "  }\n" +
                    "\n" +
                    "  function updateBtn(cb) {\n" +
                    "    var cat = cb.closest('.breakdown-category');\n" +
                    "    if (!cat) return;\n" +
                    "    var checked = cat.querySelectorAll('.multi-sel-cb:checked');\n" +
                    "    var btn = cat.querySelector('.multi-sel-open');\n" +
                    "    if (!btn) return;\n" +
                    "    var proj = btn.dataset.project || '';\n" +
                    "    var vals = Array.prototype.slice.call(checked)\n" +
                    "      .map(function(c){ return c.value; }).sort();\n" +
                    "\n" +
                    "    if (vals.length > 0) {\n" +
                    "      var label = proj + ': ' + vals.join(', ');\n" +
                    "      btn.querySelector('.multi-sel-label').textContent = label;\n" +
                    "      btn.title = 'Открыть: ' + buildUrl(btn, vals);\n" +
                    "      btn.style.display = 'inline-flex';\n" +
                    "    } else {\n" +
                    "      btn.querySelector('.multi-sel-label').textContent = proj + ': —';\n" +
                    "      btn.title = 'Открыть SonarQube с выбранными параметрами';\n" +
                    "      btn.style.display = 'none';\n" +
                    "    }\n" +
                    "  }\n" +
                    "\n" +
                    "  // 1) Основной путь: <label> переключает <input> нативно\n" +
                    "  document.querySelectorAll('.multi-sel-cb').forEach(function(cb) {\n" +
                    "    cb.addEventListener('change', function() { updateBtn(cb); });\n" +
                    "  });\n" +
                    "\n" +
                    "  // 2) Клик по карточке\n" +
                    "  document.querySelectorAll('.breakdown-card-select-link').forEach(function(link) {\n" +
                    "    link.addEventListener('click', function(e) {\n" +
                    "      var card = link.closest('.breakdown-card-select');\n" +
                    "      if (!card) return;\n" +
                    "      var cat = link.closest('.breakdown-category');\n" +
                    "      if (!cat) return;\n" +
                    "      var cb = card.querySelector('.multi-sel-cb');\n" +
                    "      if (!cb) return;\n" +
                    "\n" +
                    "      var checked = cat.querySelectorAll('.multi-sel-cb:checked');\n" +
                    "\n" +
                    "      // 2a) hit-area чекбокса\n" +
                    "      var cbRect = cb.getBoundingClientRect();\n" +
                    "      var pad = 15;\n" +
                    "      if (e.clientX >= cbRect.left - pad && e.clientX <= cbRect.right + pad &&\n" +
                    "          e.clientY >= cbRect.top - pad && e.clientY <= cbRect.bottom + pad) {\n" +
                    "        e.preventDefault();\n" +
                    "        e.stopPropagation();\n" +
                    "        cb.checked = !cb.checked;\n" +
                    "        updateBtn(cb);\n" +
                    "        return;\n" +
                    "      }\n" +
                    "\n" +
                    "      // 2b) Если есть отмеченные — мультивыборный URL (без issueStatuses)\n" +
                    "      if (checked.length > 0) {\n" +
                    "        e.preventDefault();\n" +
                    "        e.stopPropagation();\n" +
                    "\n" +
                    "        if (!cb.checked) {\n" +
                    "          cb.checked = true;\n" +
                    "          updateBtn(cb);\n" +
                    "        }\n" +
                    "\n" +
                    "        var vals = Array.prototype.slice.call(cat.querySelectorAll('.multi-sel-cb:checked'))\n" +
                    "          .map(function(c){ return c.value; }).sort();\n" +
                    "        var btn = cat.querySelector('.multi-sel-open');\n" +
                    "        window.open(buildUrl(btn, vals), '_blank');\n" +
                    "      }\n" +
                    "    }, true);\n" +
                    "  });\n" +
                    "\n" +
                    "  // 3) Синяя кнопка «<projectKey>: tag1, tag2»\n" +
                    "  document.querySelectorAll('.multi-sel-open').forEach(function(btn) {\n" +
                    "    btn.addEventListener('click', function(e) {\n" +
                    "      e.preventDefault();\n" +
                    "      var cat = btn.closest('.breakdown-category');\n" +
                    "      if (!cat) return;\n" +
                    "      var checked = cat.querySelectorAll('.multi-sel-cb:checked');\n" +
                    "      if (!checked.length) return;\n" +
                    "      var vals = Array.prototype.slice.call(checked)\n" +
                    "        .map(function(c){ return c.value; }).sort();\n" +
                    "      window.open(buildUrl(btn, vals), '_blank');\n" +
                    "    });\n" +
                    "  });\n" +
                    "})();\n" +
                    "</script>\n";

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
                    ".project-header h2 a{color:inherit;text-decoration:none;}\n" +
                    ".project-header h2 a:hover{color:#0052cc;text-decoration:underline;}\n" +
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

                    ".breakdown-card-select{padding:0;position:relative;min-height:0;overflow:visible;}\n" +
                    ".multi-sel-cb-label{position:absolute;left:0;top:0;bottom:0;width:34px;" +
                    "display:flex;align-items:center;justify-content:center;" +
                    "z-index:10;cursor:pointer;pointer-events:auto;}\n" +
                    ".multi-sel-cb{width:16px;height:16px;margin:0;cursor:pointer;accent-color:#0052cc;pointer-events:auto;}\n" +
                    ".breakdown-card-select .breakdown-card-select-link{display:flex;flex-direction:column;gap:4px;" +
                    "padding:10px 14px 10px 34px;text-decoration:none;color:inherit;" +
                    "width:100%;box-sizing:border-box;}\n" +
                    ".breakdown-card-select:hover{border-color:#0052cc;box-shadow:0 3px 10px rgba(0,82,204,0.18);}\n" +
                    ".breakdown-card-select .breakdown-card-select-link:focus-visible{outline:2px solid #0052cc;outline-offset:-2px;border-radius:6px;}\n" +
                    ".breakdown-card-select .breakdown-card-select-link:hover .breakdown-card-title::after{content:' ↗';font-size:10px;color:#6b778c;margin-left:2px;}\n" +

                    ".multi-sel-open{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;" +
                    "background:#0052cc;color:#fff;border:none;border-radius:4px;" +
                    "font-size:11px;font-weight:600;cursor:pointer;" +
                    "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;" +
                    "text-transform:none;letter-spacing:0;transition:background .15s;" +
                    "max-width:560px;}\n" +
                    ".multi-sel-open:hover{background:#0747a6;}\n" +
                    ".multi-sel-open:focus-visible{outline:2px solid #0052cc;outline-offset:2px;}\n" +
                    ".multi-sel-open .multi-sel-label{font-family:monospace;font-weight:700;" +
                    "overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" +
                    "max-width:520px;display:inline-block;vertical-align:middle;}\n" +

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