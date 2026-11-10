package ru.miacomsoft.sonar;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Reporter {

    public static void printFacet(String title, Map<String, Integer> data) {
        System.out.println("\n=== " + title + " ===");
        data.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("  %6d  %s%n", e.getValue(), e.getKey()));
    }

    public static void printGroups(String title, List<Issue> issues,
                                   Function<Issue, String> classifier, int examples) {
        Map<String, List<Issue>> groups = issues.stream()
                .collect(Collectors.groupingBy(classifier));

        System.out.println("\n=== " + title + " ===");
        groups.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .forEach(e -> {
                    System.out.printf("%n[%d] %s%n", e.getValue().size(), e.getKey());
                    e.getValue().stream().limit(examples).forEach(it ->
                            System.out.printf("    %-55s %s%n",
                                    it.component + ":" + it.line, shorten(it.message, 80)));
                });
    }

    public static void printMatrix(List<Issue> issues) {
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();
        Set<String> allRules = new TreeSet<>();

        for (Issue it : issues) {
            allRules.add(it.rule);
            matrix.computeIfAbsent(it.component, k -> new TreeMap<>())
                    .merge(it.rule, 1, Integer::sum);
        }

        System.out.println("\n=== Файл × правило ===");
        System.out.printf("%-35s", "file / rule");
        for (String r : allRules) System.out.printf("%10s", r.replace("php:", ""));
        System.out.println();

        for (Map.Entry<String, Map<String, Integer>> e : matrix.entrySet()) {
            System.out.printf("%-35s", shorten(e.getKey(), 34));
            for (String r : allRules) {
                Integer n = e.getValue().getOrDefault(r, 0);
                System.out.printf("%10s", n == 0 ? "" : n);
            }
            System.out.println();
        }
    }

    public static void printPerFile(Map<String, List<Issue>> byFile) {
        int totalIssues = byFile.values().stream().mapToInt(List::size).sum();
        System.out.printf("%n=== Замечания по файлам: %d файлов, %d замечаний ===%n",
                byFile.size(), totalIssues);

        byFile.forEach((file, issues) -> {
            System.out.printf("%n%s — %d:%n", file, issues.size());
            for (Issue it : issues) {
                String tags = it.tags.isEmpty() ? "" : " [" + String.join(",", it.tags) + "]";
                System.out.printf("  L%-6d %-12s %-8s %s%s%n",
                        it.line,
                        it.rule,
                        it.impactSeverity == null ? it.severity : it.impactSeverity,
                        shorten(it.message, 110),
                        tags);
            }
        });
    }

    public static void printDetailedByFileWithSnippets(Map<String, List<Issue>> byFile,
                                                       SonarIssuesApi api,
                                                       int maxSnippetsPerFile) throws Exception {
        int totalIssues = byFile.values().stream().mapToInt(List::size).sum();
        System.out.printf("%n=== ПОДРОБНЫЙ ОТЧЁТ ПО ФАЙЛАМ: %d файлов, %d замечаний ===%n",
                byFile.size(), totalIssues);

        for (Map.Entry<String, List<Issue>> entry : byFile.entrySet()) {
            String file = entry.getKey();
            List<Issue> issues = entry.getValue();

            System.out.printf("%n%n╔════════════════════════════════════════════════════════════════════════════════╗%n");
            System.out.printf("║ ФАЙЛ: %-70s ║%n", file);
            System.out.printf("║ Замечаний: %-66d ║%n", issues.size());
            System.out.printf("╚════════════════════════════════════════════════════════════════════════════════╝%n");

            int count = 0;
            for (Issue issue : issues) {
                if (count >= maxSnippetsPerFile) {
                    System.out.printf("%n  ... и ещё %d замечаний в этом файле ...%n",
                            issues.size() - maxSnippetsPerFile);
                    break;
                }

                String ruleName;
                try {
                    ruleName = api.fetchRuleName(issue.rule);
                } catch (Exception e) {
                    ruleName = "";
                }

                System.out.printf("%n  ┌─ Замечание #%d (строка %d) ──────────────────────────────────────────────%n",
                        count + 1, issue.line);
                System.out.printf("  │ %s%n", issue.message);
                if (!ruleName.isEmpty()) {
                    System.out.printf("  │ Правило: %s (%s)%n", ruleName, issue.rule);
                } else {
                    System.out.printf("  │ Правило: %s%n", issue.rule);
                }
                System.out.printf("  │ Серьёзность: %s%n",
                        issue.impactSeverity != null ? issue.impactSeverity : issue.severity);

                try {
                    List<String> snippet = api.fetchSnippet(issue.key);
                    if (!snippet.isEmpty()) {
                        System.out.println("  │ Код:");
                        for (String line : snippet) {
                            System.out.println("  │   " + line);
                        }
                    } else {
                        System.out.println("  │ (фрагмент кода недоступен)");
                    }
                } catch (Exception e) {
                    System.out.println("  │ (не удалось загрузить фрагмент кода: " + e.getMessage() + ")");
                }
                System.out.println("  └──────────────────────────────────────────────────────────────────────");

                count++;
            }
        }
        System.out.printf("%n%n=== КОНЕЦ ОТЧЁТА ===%n");
    }

    public static String perFileCsv(Map<String, List<Issue>> byFile) {
        StringBuilder sb = new StringBuilder(
                "file,line,rule,impactSeverity,type,message,tags,key\n");
        byFile.forEach((file, issues) -> {
            for (Issue it : issues) {
                sb.append(csv(file)).append(',')
                        .append(it.line).append(',')
                        .append(csv(it.rule)).append(',')
                        .append(csv(it.impactSeverity)).append(',')
                        .append(csv(it.type)).append(',')
                        .append(csv(it.message)).append(',')
                        .append(csv(String.join(";", it.tags))).append(',')
                        .append(csv(it.key)).append('\n');
            }
        });
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}