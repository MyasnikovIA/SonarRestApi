package ru.miacomsoft.sonar_gui.backend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Строит параметры для PromptTemplate.
 * Копия ru.miacomsoft.sonar.server.handlers.PromptBuilder
 * с локализацией Issue в backend-пакете.
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    public static final class Lang {
        public final String name;
        public final String codeFence;
        Lang(String name, String codeFence) { this.name = name; this.codeFence = codeFence; }
    }

    public static String defaultStatusLabel(String status) {
        if (status == null) return "";
        return switch (status.toLowerCase()) {
            case "accept"        -> "Принять (Это ошибка, но исправлена будет позднее)";
            case "falsepositive" -> "Ложно (Анализ содержит ошибки)";
            case "confirm"       -> "Подтвердить";
            case "resolve"       -> "Исправлено";
            case "wontfix"       -> "Не исправлять";
            default              -> status;
        };
    }

    public static Lang detectLanguage(String component) {
        String path = (component == null) ? "" : component;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(0, colon);
        String lower = path.toLowerCase();

        if (lower.endsWith(".d.ts")) return new Lang("TypeScript", "typescript");

        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1)
            return new Lang("неизвестный (определи по коду)", "");
        String ext = lower.substring(dot + 1);

        return switch (ext) {
            case "php"  -> new Lang("PHP", "php");
            case "js", "mjs", "cjs" -> new Lang("JavaScript", "javascript");
            case "jsx"  -> new Lang("JavaScript (React)", "jsx");
            case "ts"   -> new Lang("TypeScript", "typescript");
            case "tsx"  -> new Lang("TypeScript (React)", "tsx");
            case "vue"  -> new Lang("Vue", "vue");
            case "html" -> new Lang("HTML", "html");
            case "css"  -> new Lang("CSS", "css");
            case "scss" -> new Lang("SCSS", "scss");
            case "less" -> new Lang("LESS", "less");
            case "java" -> new Lang("Java", "java");
            case "kt", "kts" -> new Lang("Kotlin", "kotlin");
            case "groovy" -> new Lang("Groovy", "groovy");
            case "scala"  -> new Lang("Scala", "scala");
            case "sql"    -> new Lang("SQL", "sql");
            case "pks", "pkb", "pls", "plb", "prc", "fnc", "trg", "pck" ->
                    new Lang("PL/SQL", "plsql");
            case "py"   -> new Lang("Python", "python");
            case "go"   -> new Lang("Go", "go");
            case "rb"   -> new Lang("Ruby", "ruby");
            case "cs"   -> new Lang("C#", "csharp");
            case "cpp", "cc", "cxx", "hpp", "hxx" -> new Lang("C++", "cpp");
            case "c", "h" -> new Lang("C/C++", "c");
            case "rs"     -> new Lang("Rust", "rust");
            case "swift"  -> new Lang("Swift", "swift");
            case "xml"    -> new Lang("XML", "xml");
            case "yaml", "yml" -> new Lang("YAML", "yaml");
            case "json"   -> new Lang("JSON", "json");
            case "sh", "bash" -> new Lang("Shell", "bash");
            default -> new Lang(ext.toUpperCase(), ext);
        };
    }

    public static String highlightForPrompt(List<String> snippet, int targetLine) {
        if (snippet == null || snippet.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Pattern lineNum = Pattern.compile("^\\s*(\\d+)\\s*\\|");
        for (int i = 0; i < snippet.size(); i++) {
            String line = snippet.get(i);
            if (i > 0) sb.append('\n');
            if (line.contains("§HL_START§") || line.contains("§HL_END§")) {
                sb.append(line.replace("§HL_START§", ">>> ").replace("§HL_END§", " <<<"));
                continue;
            }
            if (targetLine > 0) {
                Matcher m = lineNum.matcher(line);
                if (m.find()) {
                    try {
                        if (Integer.parseInt(m.group(1)) == targetLine) {
                            sb.append(line.replaceFirst("^(\\s*\\d+\\s*\\|)", "$1 >>> "))
                                    .append(" <<<");
                            continue;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static Map<String, String> baseIssueParams(Issue issue, String ruleName,
                                                       List<String> snippet,
                                                       List<String> availableTags,
                                                       String fullFileCode) {
        Map<String, String> params = new LinkedHashMap<>();

        Lang lang = detectLanguage(issue.component);
        String codeHighlighted = highlightForPrompt(snippet, issue.line).trim();
        String codeRaw = (snippet == null) ? "" : String.join("\n", snippet)
                .replace("§HL_START§", "").replace("§HL_END§", "");

        String severity = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");

        String ruleCtx = (ruleName != null && !ruleName.isEmpty())
                ? ruleName
                : (issue.rule != null ? issue.rule : "");

        String component = issue.component != null ? issue.component : "";
        String location = component + (issue.line > 0 ? ":" + issue.line : "");

        String tagsList = (availableTags != null && !availableTags.isEmpty())
                ? String.join(", ", availableTags)
                : "любые релевантные";

        params.put("RULE_KEY",         issue.rule != null ? issue.rule : "");
        params.put("RULE_NAME",        ruleName != null ? ruleName : "");
        params.put("RULE_NAME_OR_KEY", ruleCtx);
        params.put("MESSAGE",          issue.message != null ? issue.message : "");
        params.put("COMPONENT",        component);
        params.put("FILE",             component);
        params.put("LINE",             issue.line > 0 ? String.valueOf(issue.line) : "");
        params.put("LOCATION",         location);
        params.put("SEVERITY",         severity);
        params.put("LANGUAGE",         lang.name);
        params.put("LANGUAGE_FENCE",   lang.codeFence);
        params.put("CODE",             codeHighlighted);
        params.put("CODE_RAW",         codeRaw);
        params.put("FULL_FILE_CODE",   fullFileCode != null ? fullFileCode : "");
        params.put("TAGS_LIST",        tagsList);
        params.put("ISSUES_COUNT",     "1");
        params.put("ISSUES_LIST",      "1. " + (issue.key != null ? issue.key : "") + " | "
                + (issue.rule != null ? issue.rule : "") + " | " + location + " | "
                + (issue.message != null ? issue.message : ""));

        return params;
    }

    public static Map<String, String> buildAutoResolveParams(Issue issue, List<String> snippet,
                                                             List<String> availableTags) {
        return buildAutoResolveParams(issue, snippet, availableTags, "");
    }

    public static Map<String, String> buildAutoResolveParams(Issue issue, List<String> snippet,
                                                             List<String> availableTags,
                                                             String fullFileCode) {
        String ruleName = issue.ruleName != null ? issue.ruleName : "";
        return baseIssueParams(issue, ruleName, snippet, availableTags, fullFileCode);
    }

    public static Map<String, String> buildAnalyzeIssueParams(Issue issue, String ruleName,
                                                              List<String> snippet,
                                                              List<String> availableTags) {
        return buildAnalyzeIssueParams(issue, ruleName, snippet, availableTags, "");
    }

    public static Map<String, String> buildAnalyzeIssueParams(Issue issue, String ruleName,
                                                              List<String> snippet,
                                                              List<String> availableTags,
                                                              String fullFileCode) {
        return baseIssueParams(issue, ruleName, snippet, availableTags, fullFileCode);
    }

    public static Map<String, String> buildStatusCommentParams(Issue issue, String ruleName,
                                                               List<String> snippet,
                                                               String status, String statusLabel,
                                                               List<String> availableTags) {
        return buildStatusCommentParams(issue, ruleName, snippet, status, statusLabel,
                availableTags, "");
    }

    public static Map<String, String> buildStatusCommentParams(Issue issue, String ruleName,
                                                               List<String> snippet,
                                                               String status, String statusLabel,
                                                               List<String> availableTags,
                                                               String fullFileCode) {
        Map<String, String> params = baseIssueParams(issue, ruleName, snippet,
                availableTags, fullFileCode);
        String label = (statusLabel != null && !statusLabel.isEmpty())
                ? statusLabel
                : defaultStatusLabel(status);
        params.put("STATUS",       status != null ? status : "");
        params.put("STATUS_LABEL", label);
        return params;
    }

    public static Map<String, String> buildBulkStatusCommentParams(
            List<Issue> issues, String status, String statusLabel,
            List<String> availableTags) {
        Map<String, String> params = new LinkedHashMap<>();

        StringBuilder listSb = new StringBuilder();
        for (int i = 0; i < issues.size(); i++) {
            Issue it = issues.get(i);
            String location = (it.component != null ? it.component : "")
                    + (it.line > 0 ? ":" + it.line : "");
            if (i > 0) listSb.append('\n');
            listSb.append(i + 1).append(". ")
                    .append(it.key).append(" | ")
                    .append(it.rule).append(" | ")
                    .append(location).append(" | ")
                    .append(it.message != null ? it.message : "");
        }

        String tagsList = (availableTags != null && !availableTags.isEmpty())
                ? String.join(", ", availableTags) : "любые релевантные";
        String label = (statusLabel != null && !statusLabel.isEmpty())
                ? statusLabel : defaultStatusLabel(status);

        params.put("ISSUES_LIST",  listSb.toString());
        params.put("ISSUES_COUNT", String.valueOf(issues.size()));
        params.put("TAGS_LIST",    tagsList);
        params.put("STATUS",       status != null ? status : "");
        params.put("STATUS_LABEL", label);

        putEmptyIfAbsent(params, "RULE_KEY");
        putEmptyIfAbsent(params, "RULE_NAME");
        putEmptyIfAbsent(params, "RULE_NAME_OR_KEY");
        putEmptyIfAbsent(params, "MESSAGE");
        putEmptyIfAbsent(params, "COMPONENT");
        putEmptyIfAbsent(params, "FILE");
        putEmptyIfAbsent(params, "LINE");
        putEmptyIfAbsent(params, "LOCATION");
        putEmptyIfAbsent(params, "SEVERITY");
        putEmptyIfAbsent(params, "LANGUAGE");
        putEmptyIfAbsent(params, "LANGUAGE_FENCE");
        putEmptyIfAbsent(params, "CODE");
        putEmptyIfAbsent(params, "CODE_RAW");
        putEmptyIfAbsent(params, "FULL_FILE_CODE");
        return params;
    }

    private static void putEmptyIfAbsent(Map<String, String> m, String k) {
        if (!m.containsKey(k)) m.put(k, "");
    }

    /** Fallback: жёстко зашитый auto-resolve без шаблона. */
    public static String buildAutoResolvePrompt(Issue issue, List<String> snippet,
                                                List<String> availableTags) {
        String code = highlightForPrompt(snippet, issue.line).trim();
        Lang lang = detectLanguage(issue.component);
        String severity = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");
        String tagsList = (availableTags != null && !availableTags.isEmpty())
                ? String.join(", ", availableTags) : "любые релевантные";
        String filePath = issue.component != null ? issue.component : "";
        String ruleCtx  = (issue.ruleName != null && !issue.ruleName.isEmpty())
                ? issue.ruleName : (issue.rule != null ? issue.rule : "");

        StringBuilder sb = new StringBuilder();
        sb.append("Ты — опытный разработчик и эксперт по SonarQube.\n\n");
        sb.append("ЗАДАЧА: определить статус, критичность, решение о фиксе, теги и обоснование.\n\n");
        sb.append("ОПРЕДЕЛЕНИЯ СТАТУСОВ:\n");
        sb.append("- falsepositive: SonarQube ошибся.\n");
        sb.append("- accept: реальная проблема, откладываем.\n");
        sb.append("- confirm: реальная и срочная.\n");
        sb.append("- resolve: уже исправлено.\n");
        sb.append("- wontfix: не будем исправлять.\n\n");
        sb.append("=== РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===\n");
        sb.append("Верни РОВНО ОДИН из двух тегов в поле \"Теги:\"\n");
        sb.append("- \"require-fix\"     — дефект РЕАЛЬНЫЙ, НУЖНО исправить.\n");
        sb.append("- \"not-require-fix\" — исправление не требуется.\n\n");
        sb.append("ДОСТУПНЫЕ ТЕГИ: ").append(tagsList).append("\n");
        sb.append("Контекст:\n");
        sb.append("- Правило: ").append(ruleCtx).append("\n");
        sb.append("- Сообщение: ").append(issue.message != null ? issue.message : "").append("\n");
        sb.append("- Файл: ").append(filePath)
                .append(issue.line > 0 ? ":" + issue.line : "").append("\n");
        sb.append("- Severity: ").append(severity).append("\n");
        sb.append("- Язык: ").append(lang.name).append("\n\n");
        sb.append("Код:\n```").append(lang.codeFence).append("\n").append(code).append("\n```\n\n");
        sb.append("ФОРМАТ ОТВЕТА (СТРОГО):\n");
        sb.append("1. Строка 1: \"Статус: <accept|falsepositive|confirm|resolve|wontfix>\"\n");
        sb.append("2. Строка 2: \"Критичность: <подтверждаю|завышена|занижена> — <обоснование>\"\n");
        sb.append("3. Строка 3: \"Теги: <require-fix|not-require-fix>, <tag2>, <tag3>\"\n");
        sb.append("4. Строки 4+: 1–3 предложения.\n");
        return sb.toString();
    }
}