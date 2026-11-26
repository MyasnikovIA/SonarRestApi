package ru.miacomsoft.sonar_gui.backend;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Утилиты фасетов — минимальная копия ru.miacomsoft.sonar.Reporter. */
public class Reporter {

    public static Map<String, Integer> facetByRule(List<Issue> issues) {
        Map<String, Integer> m = new TreeMap<>();
        for (Issue it : issues) m.merge(it.rule, 1, Integer::sum);
        return m;
    }

    public static Map<String, Integer> facetByFile(List<Issue> issues) {
        Map<String, Integer> m = new TreeMap<>();
        for (Issue it : issues) m.merge(it.component, 1, Integer::sum);
        return m;
    }

    public static Map<String, Integer> facetBySeverity(List<Issue> issues) {
        Map<String, Integer> m = new TreeMap<>();
        for (Issue it : issues) {
            String sev = (it.impactSeverity != null && !it.impactSeverity.isEmpty())
                    ? it.impactSeverity : it.severity;
            m.merge(sev == null ? "UNKNOWN" : sev, 1, Integer::sum);
        }
        return m;
    }

    public static TreeSet<String> allRules(List<Issue> issues) {
        TreeSet<String> s = new TreeSet<>();
        for (Issue it : issues) if (it.rule != null) s.add(it.rule);
        return s;
    }
}