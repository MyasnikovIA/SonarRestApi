package ru.miacomsoft.sonar_gui.backend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Обёртка над ответом /api/measures/component (копия). */
public class Measures {

    public String projectKey;
    public String alertStatus;
    public String qualityGateJson;

    public int violations;
    public int bugs;
    public int vulnerabilities;
    public int codeSmells;
    public int securityHotspots;

    public int maintainabilityIssues;
    public int reliabilityIssues;
    public int securityIssues;

    public int acceptedIssues;
    public int highImpactAcceptedIssues;

    public int newViolations;
    public int newBugs;
    public int newVulnerabilities;
    public int newCodeSmells;
    public int newAcceptedIssues;

    public int lines;
    public int ncloc;
    public int linesToCover;

    public double coverage;
    public double duplicatedLinesDensity;

    public double maintainabilityRating;
    public double reliabilityRating;
    public double securityRating;
    public double securityReviewRating;
    public double sqaleRating;

    public Map<String, Integer> nclocByLanguage = new LinkedHashMap<>();
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
            JSONObject period = o.optJSONObject("period");
            if ((value == null || value.isEmpty()) && period != null) {
                value = period.optString("value", "");
            }
            m.raw.put(key, value);
        }

        m.alertStatus     = m.raw.getOrDefault("alert_status", "");
        m.qualityGateJson = m.raw.getOrDefault("quality_gate_details", "");

        m.violations       = intOf(m.raw, "violations");
        m.bugs             = intOf(m.raw, "bugs");
        m.vulnerabilities  = intOf(m.raw, "vulnerabilities");
        m.codeSmells       = intOf(m.raw, "code_smells");
        m.securityHotspots = intOf(m.raw, "security_hotspots");

        m.maintainabilityIssues = intOf(m.raw, "software_quality_maintainability_issues");
        m.reliabilityIssues     = intOf(m.raw, "software_quality_reliability_issues");
        m.securityIssues        = intOf(m.raw, "software_quality_security_issues");

        m.acceptedIssues           = intOf(m.raw, "accepted_issues");
        m.highImpactAcceptedIssues = intOf(m.raw, "high_impact_accepted_issues");

        m.newViolations      = intOf(m.raw, "new_violations");
        m.newBugs            = intOf(m.raw, "new_bugs");
        m.newVulnerabilities = intOf(m.raw, "new_vulnerabilities");
        m.newCodeSmells      = intOf(m.raw, "new_code_smells");
        m.newAcceptedIssues  = intOf(m.raw, "new_accepted_issues");

        m.lines        = intOf(m.raw, "lines");
        m.ncloc        = intOf(m.raw, "ncloc");
        m.linesToCover = intOf(m.raw, "lines_to_cover");

        m.coverage               = doubleOf(m.raw, "coverage");
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
                    try { m.nclocByLanguage.put(lang,
                            Integer.parseInt(part.substring(idx + 1).trim())); }
                    catch (NumberFormatException ignore) {}
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
}