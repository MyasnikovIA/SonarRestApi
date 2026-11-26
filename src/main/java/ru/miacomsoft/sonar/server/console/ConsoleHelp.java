package ru.miacomsoft.sonar.server.console;

/**
 * Единственная ответственность — вывод длинного текста справки.
 * Вынесено из Console, чтобы не раздувать основной класс.
 */
public final class ConsoleHelp {

    private ConsoleHelp() {}

    public static void print() {
        System.out.println("""
        Console commands (все команды — дубли REST-эндпоинтов):

        Основное:
          help, ?                 — эта справка
          quit, exit, q           — остановить консоль (сервер продолжит работу)
          status                  — состояние сессии, снимка и auto-обработки
          health                  — краткая проверка
          project [KEY | clear]   — показать/задать консольный project
                                    (последний выбор запоминается в console.properties
                                     и восстанавливается при следующем запуске,
                                     если не задан --project=)

        Сессия (session.properties — стандартный SonarQube Web API):
          session show            — прочитать session.properties
          session set <path>      — заменить session.properties из файла + reload + auto build
          session reload          — перечитать session.properties с диска
          session save <path>     — сохранить текущий raw в файл

        Снимок:
          refresh                 — пересобрать снимок в фоне (async)
          refresh wait            — пересобрать снимок синхронно
          refresh issue <KEY>     — обновить один issue в снимке

        Отчёты (по умолчанию sev=MEDIUM, qual=MAINTAINABILITY):
          summary  [SEV] [QUAL]              — сводка + facets
          matrix   [SEV] [QUAL]              — матрица file × rule
          perfile  [SEV] [QUAL]              — замечания по файлам
          details  [SEV] [QUAL] [snippets=N] — подробно с фрагментами кода
          issues   [SEV] [QUAL]              — плоский список
          keys     [SEV] [QUAL]              — только ключи
          snippet  <KEY>                     — один issue + код

          qual-summary [QUAL]:[SEV]...       — Сводный отчет по категориям (Quality) и уровням (Severity).
                                               Показывает количество ошибок для каждой категории в проекте.
                                               Исключает замечания с тегом 'not-require-fix'.
                                               Если аргументы не указаны, выводит все категории со всеми уровнями.
                                               [QUAL] — SECURITY, RELIABILITY, MAINTAINABILITY, SECURITY_HOTSPOT, ALL
                                               [SEV] — BLOCKER, HIGH, MEDIUM, LOW, INFO, ALL
                                               Пример 1 (все категории, все уровни):
                                                 qual-summary
                                               Пример 2 (конкретные категории и уровни):
                                                 qual-summary SECURITY:HIGH,BLOCKER RELIABILITY:LOW
                                               Пример 3 (одна категория, все уровни):
                                                 qual-summary SECURITY:ALL

          team-zip [SEV] [QUAL] [имя_файла.zip] — Формирует отчет 'Команды и Модули'
                                               по всему проекту. Раскладывает файлы
                                               по папкам (Отдел/Модуль) и упаковывает в ZIP.
                                               Исключает замечания с тегом 'not-require-fix'.
                                               [SEV] — критичность (BLOCKER, HIGH, MEDIUM, LOW, INFO, ALL).
                                               [QUAL] — качество (SECURITY, RELIABILITY, MAINTAINABILITY, SECURITY_HOTSPOT, ALL).
                                               Можно передать несколько через запятую.
                                               Пример 1: team-zip HIGH ALL report.zip
                                               Пример 2: team-zip BLOCKER,MEDIUM RELIABILITY,SECURITY C:\\reports\\sonar.zip

          team-html [SEV] [QUAL] <каталог>    — Формирует отчет 'Команды и Модули' в виде 
                                               HTML-файлов (без архивации). 
                                               Сначала все файлы генерируются во временном 
                                               общем каталоге, а затем физически перемещаются 
                                               в подкаталоги: <каталог>/Отдел/Модуль/.
                                               В каждом HTML-файле есть информация об ошибке,
                                               фрагмент кода и полный листинг исходника.
                                               Исключает замечания с тегом 'not-require-fix'.
                                               [SEV] и [QUAL] аналогичны team-zip.
                                               Пример 1: team-html HIGH ALL C:\\Reports\\MyProj
                                               Пример 2: team-html BLOCKER,MEDIUM MAINTAINABILITY C:\\Reports\\MyProj

        Пометки:
          mark <KEY> <transition> [comment...]  — пометить один issue
          mark <transition> [SEV] [QUAL] [--yes] — пометить всё по фильтру
              transition = accept | falsepositive | confirm | resolve | wontfix | reopen
              без --yes — dry-run
          cancel <KEY>                          — снять пометку (reopen)
          cancelall [SEV] [QUAL] [--yes]        — снять пометки по фильтру

        Промпты (из prompts/*.txt, как /api/prompt/build в web):
          prompt list                                       — список шаблонов
          prompt <name> <KEY> [status=accept] [statusLabel=...]
          prompt <name> keys=A,B,C [status=...]             — группа issues
          prompt <name> sev=ALL qual=ALL [status=...]       — по фильтру
          prompt <name> ... save=<path>                     — сохранить в файл

          Примеры:
            prompt status-comment 30637c2f-... status=accept
            prompt bulk-status-comment keys=30637c2f-...,abc123 status=accept
            prompt analyze-issue 30637c2f-...
            prompt auto-resolve 30637c2f-... save=/tmp/prompt.txt

          Допустимые <name>:
            status-comment          → prompts/status-comment.txt
            bulk-status-comment     → prompts/bulk-status-comment.txt
            analyze-issue           → prompts/analyze-issue.txt
            auto-resolve            → prompts/auto-resolve.txt

        Ollama (LLM для авто-обработки):
          ollama show                         — текущие настройки
          ollama set url <URL>                — например http://localhost:11434
          ollama set model <MODEL>            — например llama3 или qwen2.5-coder
          ollama set timeout <SECONDS>        — таймаут запроса (по умолчанию 300)
          ollama set temperature <FLOAT>      — температура (по умолчанию 0.2)
          ollama test                         — проверить соединение и список моделей
          ollama reload                       — перечитать *.properties с диска

        Авто-обработка (массовая, аналог кнопки «Добавить автоматически комментарий»):
          auto [SEV] [QUAL]           — dry-run: показать, что будет обработано
          auto [SEV] [QUAL] --yes     — запустить обработку (синхронно)
          auto key <KEY>              — dry-run по одному issue
          auto key <KEY> --yes        — обработать один issue
        """);
    }
}