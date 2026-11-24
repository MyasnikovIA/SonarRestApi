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

          Синтаксис:
            auto [SEV] [QUAL]           — dry-run: показать, что будет обработано
            auto [SEV] [QUAL] --yes     — запустить обработку (синхронно)
            auto key <KEY>              — dry-run по одному issue
            auto key <KEY> --yes        — обработать один issue

          Примеры комбинаций:
            auto ALL ALL                        → dry-run всех OPEN/REOPENED
            auto ALL ALL --yes                  → обработать ВСЁ
            auto HIGH ALL --yes                 → только HIGH (любое качество)
            auto HIGH SECURITY --yes            → только HIGH + SECURITY
            auto MEDIUM ALL --yes               → только MEDIUM
            auto MEDIUM MAINTAINABILITY --yes   → только MEDIUM + MAINTAINABILITY
            auto LOW ALL --yes                  → только LOW
            auto INFO ALL --yes                 → только INFO
            auto ALL SECURITY --yes             → любые SEV, но только SECURITY
            auto ALL MAINTAINABILITY --yes      → любые SEV, только MAINTAINABILITY
            auto ALL RELIABILITY --yes          → любые SEV, только RELIABILITY
            auto key MYPROJ-1234                → dry-run одного issue
            auto key MYPROJ-1234 --yes          → обработать один issue

          Допустимые SEV:   HIGH | MEDIUM | LOW | INFO | ALL
          Допустимые QUAL:  MAINTAINABILITY | RELIABILITY | SECURITY | ALL

          Промпт берётся из prompts/auto-resolve.txt — тот же, что и в web.
          Если файла нет (или он пуст) — используется встроенный fallback.
          Теги: сохраняются существующие + добавляются предложенные LLM
          (require-fix ↔ not-require-fix заменяют друг друга).

          Во время выполнения:
            * обработка идёт СИНХРОННО — консоль не принимает других команд;
            * результат по каждому issue печатается в stdout:
                [N/M] KEY → OK  accept [tags] (12.4s)
                [N/M] KEY → FAIL: reason (0.3s)
            * для досрочной остановки используйте Ctrl+C.

          После завершения:
            * итоговая статистика печатается в stdout;
            * если не было 401/сетевых ошибок — снимок пересобирается в фоне
              (status — прогресс).
        """);
    }
}