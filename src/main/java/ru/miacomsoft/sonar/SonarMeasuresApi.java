package ru.miacomsoft.sonar;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Тонкий клиент к /api/measures/component.
 * По умолчанию запрашивает полный набор метрик, который нужен UI
 * (эквивалент запроса, который делает сам SonarQube UI).
 */
public class SonarMeasuresApi {

    /** Полный набор метрик, который отдаёт официальный UI SonarQube. */
    public static final String DEFAULT_METRIC_KEYS =
            "alert_status,quality_gate_details,new_violations,accepted_issues," +
                    "new_accepted_issues,high_impact_accepted_issues," +
                    "software_quality_maintainability_issues," +
                    "software_quality_reliability_issues," +
                    "software_quality_security_issues," +
                    "bugs,new_bugs,reliability_rating," +
                    "software_quality_reliability_rating,new_reliability_rating," +
                    "new_software_quality_reliability_rating," +
                    "vulnerabilities,new_vulnerabilities,security_rating," +
                    "software_quality_security_rating,new_security_rating," +
                    "new_software_quality_security_rating," +
                    "security_hotspots,new_security_hotspots,security_hotspots_reviewed," +
                    "new_security_hotspots_reviewed,security_review_rating," +
                    "new_security_review_rating," +
                    "code_smells,new_code_smells,sqale_rating," +
                    "software_quality_maintainability_rating,new_maintainability_rating," +
                    "new_software_quality_maintainability_rating," +
                    "sqale_index,new_technical_debt,coverage,new_coverage," +
                    "lines_to_cover,new_lines_to_cover,tests," +
                    "duplicated_lines_density,new_duplicated_lines_density," +
                    "duplicated_blocks,ncloc,ncloc_language_distribution,projects," +
                    "lines,new_lines,violations";

    private final SonarClient client;
    private final String metricKeys;

    public SonarMeasuresApi(SonarClient client) {
        this(client, DEFAULT_METRIC_KEYS);
    }

    public SonarMeasuresApi(SonarClient client, String metricKeys) {
        this.client = client;
        this.metricKeys = (metricKeys == null || metricKeys.isEmpty())
                ? DEFAULT_METRIC_KEYS
                : metricKeys;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public Measures fetch(String projectKey) throws Exception {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey не задан");
        }
        String url = "/api/measures/component"
                + "?additionalFields=" + enc("period,metrics")
                + "&component=" + enc(projectKey)
                + "&metricKeys=" + enc(metricKeys);
        JSONObject root = new JSONObject(client.get(url));
        return Measures.from(root, projectKey);
    }
}