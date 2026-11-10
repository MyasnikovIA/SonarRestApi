package ru.miacomsoft.sonar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Обёртка над ответом /api/measures/component.
 * Хранит ключевые метрики, которые нужны для верхней панели UI:
 *   - Quality Gate status
 *   - общее число issues (violations)
 *   - разбивка по видам (legacy: bugs/vulnerabilities/code_smells,
 *     MQR: reliability/security/maintainability issues)
 *   - accepted / high impact accepted
 *   - размер (lines, ncloc, ncloc по языкам)
 *   - покрытие, дубликаты
 *   - рейтинги A..E (1..5)
 */
public class Measures {

    public String projectKey;
    public String alertStatus;              // OK | ERROR | WARN
    public String qualityGateJson;          // сырой quality_gate_details

    // Legacy-разбивка
    public int violations;
    public int bugs;
    public int vulnerabilities;
    public int codeSmells;
    public int securityHotspots;

    // MQR-разбивка (SonarQube 10.6+)
    public int maintainabilityIssues;
    public int reliabilityIssues;
    public int securityIssues;

    // Accepted
    public int acceptedIssues;
    public int highImpactAcceptedIssues;

    // New code
    public int newViolations;
    public int newBugs;
    public int newVulnerabilities;
    public int newCodeSmells;
    public int newAcceptedIssues;

    // Размер
    public int lines;
    public int ncloc;
    public int linesToCover;

    // Покрытие / дубликаты
    public double coverage;
    public double duplicatedLinesDensity;

    // Рейтинги: 1=A..5=E, 0 = не оценено
    public double maintainabilityRating;
    public double reliabilityRating;
    public double securityRating;
    public double securityReviewRating;
    public double sqaleRating;

    // Распределение ncloc по языкам
    public Map<String, Integer> nclocByLanguage = new LinkedHashMap<>();

    // Полный сырой набор метрик (key -> значение строкой)
    public Map<String, String> raw = new LinkedHashMap<>();

    public static Measures from(JSONObject root, String projectKey) {
        Measures m = new Measures();
        m.projectKey = projectKey;
        if (root == null) return m;

        JSONObject component = root.optJSONObject("component");
        if (component == null) return m;

        JSONArray measures = component.optJSONArray("measures");
        if (measures == null) return m;

        for (int i = 0; i < measures.length(); i++) {
            JSONObject o = measures.getJSONObject(i);
            String key = o.optString("metric", "");
            if (key.isEmpty()) continue;

            String value = o.optString("value", "");
            // Периодные метрики (new_*) хранят значение внутри period
            JSONObject period = o.optJSONObject("period");
            if ((value == null || value.isEmpty()) && period != null) {
                value = period.optString("value", "");
            }
            m.raw.put(key, value);
        }

        m.alertStatus      = m.raw.getOrDefault("alert_status", "");
        m.qualityGateJson  = m.raw.getOrDefault("quality_gate_details", "");

        m.violations            = intOf(m.raw, "violations");
        m.bugs                  = intOf(m.raw, "bugs");
        m.vulnerabilities       = intOf(m.raw, "vulnerabilities");
        m.codeSmells            = intOf(m.raw, "code_smells");
        m.securityHotspots      = intOf(m.raw, "security_hotspots");

        m.maintainabilityIssues = intOf(m.raw, "software_quality_maintainability_issues");
        m.reliabilityIssues     = intOf(m.raw, "software_quality_reliability_issues");
        m.securityIssues        = intOf(m.raw, "software_quality_security_issues");

        m.acceptedIssues           = intOf(m.raw, "accepted_issues");
        m.highImpactAcceptedIssues = intOf(m.raw, "high_impact_accepted_issues");

        m.newViolations         = intOf(m.raw, "new_violations");
        m.newBugs               = intOf(m.raw, "new_bugs");
        m.newVulnerabilities    = intOf(m.raw, "new_vulnerabilities");
        m.newCodeSmells         = intOf(m.raw, "new_code_smells");
        m.newAcceptedIssues     = intOf(m.raw, "new_accepted_issues");

        m.lines                 = intOf(m.raw, "lines");
        m.ncloc                 = intOf(m.raw, "ncloc");
        m.linesToCover          = intOf(m.raw, "lines_to_cover");

        m.coverage              = doubleOf(m.raw, "coverage");
        m.duplicatedLinesDensity = doubleOf(m.raw, "duplicated_lines_density");

        m.maintainabilityRating = doubleOf(m.raw, "software_quality_maintainability_rating");
        m.reliabilityRating     = doubleOf(m.raw, "software_quality_reliability_rating");
        m.securityRating        = doubleOf(m.raw, "software_quality_security_rating");
        m.securityReviewRating  = doubleOf(m.raw, "security_review_rating");
        m.sqaleRating           = doubleOf(m.raw, "sqale_rating");

        String nclocLang = m.raw.getOrDefault("ncloc_language_distribution", "");
        if (!nclocLang.isEmpty()) {
            for (String part : nclocLang.split(";")) {
                int idx = part.indexOf('=');
                if (idx > 0) {
                    String lang = part.substring(0, idx).trim();
                    try {
                        m.nclocByLanguage.put(lang,
                                Integer.parseInt(part.substring(idx + 1).trim()));
                    } catch (NumberFormatException ignore) {}
                }
            }
        }

        return m;
    }

    private static int intOf(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isEmpty()) return 0;
        try { return (int) Double.parseDouble(v); } catch (Exception e) { return 0; }
    }

    private static double doubleOf(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isEmpty()) return 0.0;
        try { return Double.parseDouble(v); } catch (Exception e) { return 0.0; }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("project", projectKey);
        o.put("alertStatus", alertStatus != null ? alertStatus : "");

        o.put("violations", violations);
        o.put("bugs", bugs);
        o.put("vulnerabilities", vulnerabilities);
        o.put("codeSmells", codeSmells);
        o.put("securityHotspots", securityHotspots);

        o.put("maintainabilityIssues", maintainabilityIssues);
        o.put("reliabilityIssues", reliabilityIssues);
        o.put("securityIssues", securityIssues);

        o.put("acceptedIssues", acceptedIssues);
        o.put("highImpactAcceptedIssues", highImpactAcceptedIssues);

        o.put("newViolations", newViolations);
        o.put("newBugs", newBugs);
        o.put("newVulnerabilities", newVulnerabilities);
        o.put("newCodeSmells", newCodeSmells);
        o.put("newAcceptedIssues", newAcceptedIssues);

        o.put("lines", lines);
        o.put("ncloc", ncloc);
        o.put("linesToCover", linesToCover);

        o.put("coverage", coverage);
        o.put("duplicatedLinesDensity", duplicatedLinesDensity);

        o.put("maintainabilityRating", maintainabilityRating);
        o.put("reliabilityRating", reliabilityRating);
        o.put("securityRating", securityRating);
        o.put("securityReviewRating", securityReviewRating);
        o.put("sqaleRating", sqaleRating);

        o.put("qualityGate", qualityGateJson != null ? qualityGateJson : "");

        JSONObject langs = new JSONObject();
        for (Map.Entry<String, Integer> e : nclocByLanguage.entrySet()) {
            langs.put(e.getKey(), e.getValue());
        }
        o.put("nclocByLanguage", langs);

        JSONObject rawObj = new JSONObject();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            rawObj.put(e.getKey(), e.getValue());
        }
        o.put("raw", rawObj);

        return o;
    }
}