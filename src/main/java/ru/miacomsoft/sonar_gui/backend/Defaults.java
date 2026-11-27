package ru.miacomsoft.sonar_gui.backend;

/**
 * Шаблоны файлов, которые приложение создаёт при первом запуске.
 * Существующие файлы никогда не перезаписываются.
 */
public final class Defaults {

    private Defaults() {}

    // ============================================================
    // app.properties — ЕДИНЫЙ ФАЙЛ НАСТРОЕК
    // ============================================================
    public static final String APP_PROPERTIES_TEMPLATE = """
            # ========================================================
            # app.properties — единый файл настроек приложения
            # ========================================================
            
            # --- SonarQube ---
            sonar.url=
            sonar.user=
            sonar.auth=bearer
            sonar.token=
            
            # --- Ollama (LLM) ---
            ollama.url=http://localhost:11434
            ollama.model=llama3
            ollama.timeout=300
            ollama.temperature=0.2
            
            # --- Jira ---
            jira.url=https://jira-medmis.bars.group
            jira.session=session.txt
            jira.issueType=10206
            jira.defaultProject=MEDDEV
            jira.team=HM12
            jira.assignee=me
            
            # --- Jira Epics (QUAL:SEV) ---
            jira.epic.security.blocker=MEDDEV-172524
            jira.epic.security.high=MEDDEV-172525
            jira.epic.reliability.blocker=MEDDEV-172527
            jira.epic.reliability.high=MEDDEV-172526
            jira.epic.maintainability.blocker=MEDDEV-172528
            jira.epic.maintainability.high=MEDDEV-172529
            """;

    // ============================================================
    // session.txt — файл сессии Jira
    // ============================================================
    public static final String SESSION_TXT_TEMPLATE = """
            # ========================================================
            # session.txt — сессия Jira (скопируйте запрос из браузера)
            # ========================================================
            Cookie:
            X-AUSERNAME:

            Request URL
            https://your-jira.example.com/browse/PROJECT-1
            """;

    // ============================================================
    // team_mapping.csv
    // ============================================================
    public static final String TEAM_MAPPING_CSV_TEMPLATE = """
            Форма,Компонент,Владелец тМИС,Переопределяется формами
            """;

    // ============================================================
    // prompts/auto-resolve.txt
    // ============================================================
    public static final String PROMPT_AUTO_RESOLVE = """
            Ты — опытный разработчик и эксперт по SonarQube.

            ЗАДАЧА: определить статус, критичность, решение о фиксе, теги и обоснование.

            ОПРЕДЕЛЕНИЯ СТАТУСОВ:
            - falsepositive: SonarQube ошибся.
            - accept: реальная проблема, откладываем.
            - confirm: реальная и срочная.
            - resolve: уже исправлено.
            - wontfix: не будем исправлять.

            === РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===
            Верни РОВНО ОДИН из двух тегов в поле "Теги:"
            - "require-fix"     — дефект РЕАЛЬНЫЙ, НУЖНО исправить.
            - "not-require-fix" — исправление не требуется.

            === ВЗАИМОИСКЛЮЧАЮЩИЕ ТЕГИ ===
            "require-fix" и "not-require-fix" — взаимоисключающие.
            Верни РОВНО ОДИН из них, НИКОГДА оба одновременно.

            ДОСТУПНЫЕ ТЕГИ: [TAGS_LIST]

            Контекст:
            - Правило: [RULE_NAME_OR_KEY]
            - Сообщение: [MESSAGE]
            - Файл: [LOCATION]
            - Severity: [SEVERITY]
            - Язык: [LANGUAGE]

            Код:
            ```[LANGUAGE_FENCE]```

            [CODE]
            """;

    // ============================================================
    // prompts/status-comment.txt
    // ============================================================
    public static final String PROMPT_STATUS_COMMENT = """
            Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).
            Проект мультиязычный. НЕ предполагай язык — он указан ниже.

            ЗАДАЧА:
            1. Подтверди или скорректируй выбранный пользователем статус: "[STATUS]" — [STATUS_LABEL]
            2. Определи реальную критичность.
            3. Дай РЕШЕНИЕ: нужно ли исправлять этот баг?
            4. Выбери 2–3 тега из списка доступных (обязательно с фикс-тегом).
            5. Объясни в 1–3 предложениях.

            ОПРЕДЕЛЕНИЯ СТАТУСОВ:
            - falsepositive: SonarQube ошибся, проблемы нет.
            - accept: реальная проблема, исправим позже (техдолг).
            - confirm: реальная и срочная проблема (security, потеря данных).
            - resolve: уже исправлено в коде.
            - wontfix: реальная проблема, но не будем исправлять.

            === РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===
            Верни РОВНО ОДИН из двух тегов:
            - "require-fix"     — баг РЕАЛЬНЫЙ, его НУЖНО исправить.
            - "not-require-fix" — баг НЕ требует исправления.

            ДОСТУПНЫЕ ТЕГИ (используй ТОЛЬКО из этого списка): [TAGS_LIST]

            Контекст:
            - Правило: [RULE_NAME_OR_KEY]
            - Сообщение: [MESSAGE]
            - Файл: [LOCATION]
            - Severity: [SEVERITY]
            - Язык: [LANGUAGE]

            Код:
            ```[LANGUAGE_FENCE]```
            [CODE]
            """;

    // ============================================================
    // prompts/bulk-status-comment.txt
    // ============================================================
    public static final String PROMPT_BULK_STATUS_COMMENT = """
            Ты — опытный разработчик и эксперт по SonarQube.
            Обработай ГРУППУ замечаний (одинаковый статус для всех).

            ЗАДАЧА:
            1. Подтверди статус "[STATUS]" — [STATUS_LABEL] для всей группы.
            2. Определи общую критичность.
            3. Дай ОДНО общее РЕШЕНИЕ: нужно ли исправлять эти баги?
            4. Предложи 2–3 общих тега (обязательно с фикс-тегом).
            5. Объясни в 1–3 предложениях.

            === РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===
            Верни РОВНО ОДИН из двух тегов:
            - "require-fix"     — группа содержит РЕАЛЬНЫЕ баги.
            - "not-require-fix" — исправление не требуется.

            ДОСТУПНЫЕ ТЕГИ: [TAGS_LIST]

            Замечания ([ISSUES_COUNT]):
            [ISSUES_LIST]

            ФОРМАТ ОТВЕТА (СТРОГО):
            1. "Статус: <accept|falsepositive|confirm|resolve|wontfix>"
            2. "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"
            3. "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"
            4. Строки 4+: 1–3 предложения.
            """;

    public static final String PROMPT_ANALYZE_ISSUE = PROMPT_AUTO_RESOLVE;
}