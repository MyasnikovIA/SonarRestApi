package ru.miacomsoft.sonar.server.handlers;

import ru.miacomsoft.sonar.Issue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Строит текст промпта для LLM из данных issue.
 * Зеркально соответствует buildStatusCommentPrompt() из
 * web/tabs/perfile/prompts.js.
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    public static final class Lang {
        public final String name;
        public final String codeFence;
        Lang(String name, String codeFence) { this.name = name; this.codeFence = codeFence; }
    }

    /** Маппинг статуса в человекочитаемое описание (как в UI). */
    public static String defaultStatusLabel(String status) {
        if (status == null) return "";
        switch (status.toLowerCase()) {
            case "accept":        return "Принять (Это ошибка, но исправлена будет позднее)";
            case "falsepositive": return "Ложно (Анализ содержит ошибки)";
            case "confirm":       return "Подтвердить";
            case "resolve":       return "Исправлено";
            case "wontfix":       return "Не исправлять";
            default:              return status;
        }
    }

    public static Lang detectLanguage(String component) {
        String path = (component == null) ? "" : component;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(0, colon);
        String lower = path.toLowerCase();

        if (lower.endsWith(".d.ts")) return new Lang("TypeScript", "typescript");

        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return new Lang("неизвестный (определи по коду)", "");
        }
        String ext = lower.substring(dot + 1);

        switch (ext) {
            case "php":  return new Lang("PHP", "php");
            case "js": case "mjs": case "cjs": return new Lang("JavaScript", "javascript");
            case "jsx":  return new Lang("JavaScript (React)", "jsx");
            case "ts":   return new Lang("TypeScript", "typescript");
            case "tsx":  return new Lang("TypeScript (React)", "tsx");
            case "vue":  return new Lang("Vue", "vue");
            case "html": return new Lang("HTML", "html");
            case "css":  return new Lang("CSS", "css");
            case "scss": return new Lang("SCSS", "scss");
            case "less": return new Lang("LESS", "less");
            case "java": return new Lang("Java", "java");
            case "kt": case "kts": return new Lang("Kotlin", "kotlin");
            case "groovy": return new Lang("Groovy", "groovy");
            case "scala": return new Lang("Scala", "scala");
            case "sql":  return new Lang("SQL", "sql");
            case "pks": case "pkb": case "pls": case "plb":
            case "prc": case "fnc": case "trg": case "pck":
                return new Lang("PL/SQL", "plsql");
            case "py": return new Lang("Python", "python");
            case "go": return new Lang("Go", "go");
            case "rb": return new Lang("Ruby", "ruby");
            case "cs": return new Lang("C#", "csharp");
            case "cpp": case "cc": case "cxx": case "hpp": case "hxx": return new Lang("C++", "cpp");
            case "c": case "h": return new Lang("C/C++", "c");
            case "rs": return new Lang("Rust", "rust");
            case "swift": return new Lang("Swift", "swift");
            case "xml": return new Lang("XML", "xml");
            case "yaml": case "yml": return new Lang("YAML", "yaml");
            case "json": return new Lang("JSON", "json");
            case "sh": case "bash": return new Lang("Shell", "bash");
            default: return new Lang(ext.toUpperCase(), ext);
        }
    }

    /** §HL_START§ / §HL_END§ → ">>> " / " <<<" (как в JS). */
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

    public static String buildStatusCommentPrompt(Issue issue, String ruleName,
                                                  List<String> snippet, String status,
                                                  String statusLabel, List<String> availableTags) {
        String code = highlightForPrompt(snippet, issue.line).trim();
        Lang lang = detectLanguage(issue.component);
        String severity = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");

        String tagsList = (availableTags != null && !availableTags.isEmpty())
                ? String.join(", ", availableTags)
                : "любые релевантные";

        String filePath = issue.component != null ? issue.component : "";
        String ruleCtx  = (ruleName != null && !ruleName.isEmpty())
                ? ruleName
                : (issue.rule != null ? issue.rule : "");

        StringBuilder sb = new StringBuilder();
        sb.append("Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).\n");
        sb.append("Проект мультиязычный. НЕ предполагай язык — он указан ниже.\n\n");

        sb.append("ЗАДАЧА:\n");
        sb.append("1. Подтверди или скорректируй выбранный пользователем статус: \"")
                .append(status).append("\" — ").append(statusLabel).append("\n");
        sb.append("2. Определи реальную критичность.\n");
        sb.append("3. Дай РЕШЕНИЕ: нужно ли исправлять этот баг?\n");
        sb.append("4. Выбери 2–3 тега из списка доступных (обязательно с фикс-тегом).\n");
        sb.append("5. Объясни в 1–3 предложениях.\n\n");

        sb.append("ОПРЕДЕЛЕНИЯ СТАТУСОВ:\n");
        sb.append("- falsepositive: SonarQube ошибся, проблемы нет.\n");
        sb.append("- accept: реальная проблема, исправим позже (техдолг).\n");
        sb.append("- confirm: реальная и срочная проблема (security, потеря данных).\n");
        sb.append("- resolve: уже исправлено в коде.\n");
        sb.append("- wontfix: реальная проблема, но не будем исправлять.\n\n");

        sb.append("=== КРИТИЧЕСКИ ВАЖНО: СГЕНЕРИРОВАННЫЙ КОД ===\n");
        sb.append("Файл считается СГЕНЕРИРОВАННЫМ, если ЛЮБОЙ из признаков верен:\n");
        sb.append("- путь содержит \"/Api/\" и имя файла заканчивается на \"Api.php\" (OpenAPI/Swagger client),\n");
        sb.append("- путь содержит \"/Model/\" (сгенерированные модели),\n");
        sb.append("- в коде есть маркеры: \"ObjectSerializer\", \"handleResponseWithDataType\", \"ApiException\",\n");
        sb.append("  \"$this->config\", \"$this->headerSelector\", \"buildQuery\", \"ObjectSerializer::deserialize\",\n");
        sb.append("- расширения: .pb.go, .pb.php, .pb.cc, .g.cs, *_generated.*, *_pb2.py, *-openapi.*, *.g.dart.\n\n");

        sb.append("Для СГЕНЕРИРОВАННОГО кода:\n");
        sb.append("- статус = accept ИЛИ wontfix (никогда confirm/resolve/falsepositive),\n");
        sb.append("- фикс-тег = ВСЕГДА \"not-require-fix\",\n");
        sb.append("- НЕ предлагай рефакторинг, объединение веток, добавление default в switch,\n");
        sb.append("  удаление дублирования — это нормальная особенность генератора,\n");
        sb.append("  правки затрутся при следующей регенерации,\n");
        sb.append("- ПРИМЕР: дублирование case 401 и case 422 в *Api.php — типичный сгенерированный\n");
        sb.append("  switch, править НЕ нужно, ставить not-require-fix.\n\n");

        sb.append("=== КРИТИЧЕСКИ ВАЖНО: ЯЗЫКОВЫЕ СИГНАЛЫ ===\n");
        sb.append("Если ты пишешь \"функционально работает корректно\", \"не влияет на работу\",\n");
        sb.append("\"нарушает читаемость но не ломает\", \"косметическая проблема\" — это ЯВНЫЙ СИГНАЛ\n");
        sb.append("поставить фикс-тег \"not-require-fix\", а не \"require-fix\".\n\n");

        sb.append("=== РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===\n");
        sb.append("Верни РОВНО ОДИН из двух тегов в поле \"Теги:\"\n");
        sb.append("- \"require-fix\"     — баг РЕАЛЬНЫЙ, в РУЧНОМ коде, его НУЖНО исправить.\n");
        sb.append("- \"not-require-fix\" — баг НЕ требует исправления: сгенерированный код,\n");
        sb.append("  false positive, стилистика без последствий, wontfix.\n\n");

        sb.append("СВЯЗЬ СТАТУСА И ФИКС-ТЕГА:\n");
        sb.append("- status=confirm       → require-fix\n");
        sb.append("- status=resolve       → not-require-fix (уже исправлено)\n");
        sb.append("- status=wontfix       → not-require-fix\n");
        sb.append("- status=falsepositive → not-require-fix\n");
        sb.append("- status=accept        → require-fix (ручной код) или not-require-fix (сгенерированный)\n\n");

        sb.append("ДОСТУПНЫЕ ТЕГИ (используй ТОЛЬКО из этого списка): ").append(tagsList).append("\n");
        sb.append("В поле \"Теги:\" верни СНАЧАЛА require-fix или not-require-fix,\n");
        sb.append("затем 1–2 тега СТРОГО из списка выше.\n\n");

        sb.append("Контекст:\n");
        sb.append("- Правило: ").append(ruleCtx).append("\n");
        sb.append("- Сообщение: ").append(issue.message != null ? issue.message : "").append("\n");
        sb.append("- Файл: ").append(filePath).append(issue.line > 0 ? ":" + issue.line : "").append("\n");
        sb.append("- Severity: ").append(severity).append("\n");
        sb.append("- Язык: ").append(lang.name).append("\n\n");

        sb.append("Код:\n");
        sb.append("```").append(lang.codeFence).append("\n");
        sb.append(code).append("\n");
        sb.append("```\n\n");

        sb.append("ФОРМАТ ОТВЕТА (СТРОГО, без отклонений):\n");
        sb.append("1. Строка 1: \"Статус: <accept|falsepositive|confirm|resolve|wontfix>\"\n");
        sb.append("2. Строка 2: \"Критичность: <подтверждаю|завышена|занижена> — <обоснование>\"\n");
        sb.append("3. Строка 3: \"Теги: <require-fix|not-require-fix>, <tag2>, <tag3>\"\n");
        sb.append("4. Строки 4+: 1–3 предложения.\n\n");
        sb.append("Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.\n");

        return sb.toString();
    }

    // ==================== NEW: авто-промпт (LLM сам выбирает статус) ====================

    public static String buildAutoResolvePrompt(Issue issue, List<String> snippet,
                                                List<String> availableTags) {
        String code = highlightForPrompt(snippet, issue.line).trim();
        Lang lang = detectLanguage(issue.component);
        String severity = (issue.impactSeverity != null && !issue.impactSeverity.isEmpty())
                ? issue.impactSeverity
                : (issue.severity != null ? issue.severity : "");

        String tagsList = (availableTags != null && !availableTags.isEmpty())
                ? String.join(", ", availableTags)
                : "любые релевантные";

        String filePath = issue.component != null ? issue.component : "";
        String ruleCtx  = (issue.ruleName != null && !issue.ruleName.isEmpty())
                ? issue.ruleName
                : (issue.rule != null ? issue.rule : "");

        StringBuilder sb = new StringBuilder();
        sb.append("Ты — опытный разработчик и эксперт по SonarQube.\n\n");

        sb.append("ЗАДАЧА: определить статус, критичность, решение о фиксе, теги и обоснование.\n\n");

        sb.append("ОПРЕДЕЛЕНИЯ СТАТУСОВ:\n");
        sb.append("- falsepositive: SonarQube ошибся.\n");
        sb.append("- accept: реальная проблема, откладываем.\n");
        sb.append("- confirm: реальная и срочная.\n");
        sb.append("- resolve: уже исправлено.\n");
        sb.append("- wontfix: не будем исправлять.\n\n");

        sb.append("=== КРИТИЧЕСКИ ВАЖНО: СГЕНЕРИРОВАННЫЙ КОД ===\n");
        sb.append("Файл СГЕНЕРИРОВАН, если ЛЮБОЙ признак верен:\n");
        sb.append("- путь содержит \"/Api/\" и имя файла заканчивается на \"Api.php\" (OpenAPI client),\n");
        sb.append("- путь содержит \"/Model/\" (сгенерированные модели),\n");
        sb.append("- маркеры в коде: \"ObjectSerializer\", \"handleResponseWithDataType\", \"ApiException\",\n");
        sb.append("  \"$this->config\", \"$this->headerSelector\", \"buildQuery\",\n");
        sb.append("- расширения: .pb.go, .pb.php, .pb.cc, .g.cs, *_generated.*, *_pb2.py, *-openapi.*, *.g.dart.\n");
        sb.append("Для СГЕНЕРИРОВАННОГО кода:\n");
        sb.append("- статус = accept ИЛИ wontfix (никогда confirm/resolve/falsepositive),\n");
        sb.append("- фикс-тег = ВСЕГДА \"not-require-fix\",\n");
        sb.append("- НЕ предлагай рефакторинг, объединение веток, добавление default в switch,\n");
        sb.append("  удаление дублирования — правки затрутся при следующей регенерации.\n\n");

        sb.append("=== ЯЗЫКОВЫЕ СИГНАЛЫ ===\n");
        sb.append("Если пишешь \"функционально работает корректно\", \"не влияет на работу\",\n");
        sb.append("\"нарушает читаемость но не ломает\", \"косметическая проблема\" —\n");
        sb.append("это ЯВНЫЙ СИГНАЛ поставить фикс-тег \"not-require-fix\", а не \"require-fix\".\n\n");

        sb.append("=== РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===\n");
        sb.append("Верни РОВНО ОДИН из двух тегов в поле \"Теги:\"\n");
        sb.append("- \"require-fix\"     — баг РЕАЛЬНЫЙ, в РУЧНОМ коде, его НУЖНО исправить.\n");
        sb.append("- \"not-require-fix\" — не требует исправления: сгенерированный код,\n");
        sb.append("  false positive, стилистика без последствий, wontfix.\n\n");

        sb.append("СВЯЗЬ СТАТУСА И ФИКС-ТЕГА:\n");
        sb.append("- status=confirm       → require-fix\n");
        sb.append("- status=resolve       → not-require-fix (уже исправлено)\n");
        sb.append("- status=wontfix       → not-require-fix\n");
        sb.append("- status=falsepositive → not-require-fix\n");
        sb.append("- status=accept        → require-fix (ручной код) или not-require-fix (сгенерированный)\n\n");

        sb.append("ДОСТУПНЫЕ ТЕГИ (используй ТОЛЬКО из этого списка): ").append(tagsList).append("\n");
        sb.append("В поле \"Теги:\" верни СНАЧАЛА require-fix или not-require-fix,\n");
        sb.append("затем 1–2 тега СТРОГО из списка выше.\n\n");

        sb.append("Контекст:\n");
        sb.append("- Правило: ").append(ruleCtx).append("\n");
        sb.append("- Сообщение: ").append(issue.message != null ? issue.message : "").append("\n");
        sb.append("- Файл: ").append(filePath).append(issue.line > 0 ? ":" + issue.line : "").append("\n");
        sb.append("- Severity: ").append(severity).append("\n");
        sb.append("- Язык: ").append(lang.name).append("\n\n");

        sb.append("Код:\n");
        sb.append("```").append(lang.codeFence).append("\n");
        sb.append(code).append("\n");
        sb.append("```\n\n");

        sb.append("ФОРМАТ ОТВЕТА (СТРОГО, без отклонений):\n");
        sb.append("1. Строка 1: \"Статус: <accept|falsepositive|confirm|resolve|wontfix>\"\n");
        sb.append("2. Строка 2: \"Критичность: <подтверждаю|завышена|занижена> — <обоснование>\"\n");
        sb.append("3. Строка 3: \"Теги: <require-fix|not-require-fix>, <tag2>, <tag3>\"\n");
        sb.append("4. Строки 4+: 1–3 предложения.\n\n");
        sb.append("Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.\n");

        return sb.toString();
    }
}