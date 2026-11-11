# Обновлённый `сonsoleChat.md` (краткая шпаргалка)

Актуализирован с учётом нового функционала: `prompt`, `ollama`, `auto`, `stop`. Документ остаётся **кратким** — полное описание по-прежнему в `console.md`.

---

```markdown
# Инструкция по работе в интерактивной консоли

## Варианты имени файла

На выбор (в порядке предпочтения):

| Имя | Когда уместно |
|---|---|
| **`CONSOLE.md`** | ✅ рекомендуется — коротко, в верхнем регистре, рядом с `../README.md` |
| `CONSOLE_GUIDE.md` | если есть отдельный `GUIDE.md` для REST — параллельно |
| `docs/console.md` | если вся документация в подпапке `docs/` |
| `README-console.md` | если хочется подчеркнуть «часть README» |
| `QUICKSTART-console.md` | если это именно быстрый старт, а полный справочник — в `console.md` |

Совет: если у вас уже есть подробный `console.md` — новый файл назовите **`CONSOLE.md`** и сделайте его **кратким справочником** (шпаргалкой), сославшись в шапке: *«Полное описание — в `console.md`»*.

---

# CONSOLE.md

Краткая инструкция по интерактивной консоли `SonarRestApi`.

**Что нового:** промпты из `prompts/*.txt` (`prompt`), настройка LLM (`ollama`) и массовая AI-обработка через Ollama (`auto` + `stop`). Подробно — в разделе 3.6–3.8 и `console.md`.

## 1. Запуск

Консоль стартует **автоматически** вместе с REST-сервером.

```bash
# Минимум: REST на 0.0.0.0:6060 + консоль, session.txt из CWD
java -jar SonarRestApi.jar

# С проектом по умолчанию (иначе указывать ?project= / project в консоли)
java -jar SonarRestApi.jar --project=MEDMIS-MISmobile.doctor_api_spec

# Явный путь к session.txt
java -jar SonarRestApi.jar --session=/etc/sonar/session.txt \
                           --project=MEDMIS-MISmobile.doctor_api_spec

# Только REST, без консоли (демон)
java -jar SonarRestApi.jar --no-console
```

После старта видно:

```
===================================================
 Console ready. Type 'help' for commands.
 Prompts dir: prompts/
===================================================
[ServerState] сессия загружена из session.txt (host=sonar.example.com)
[Warmup] sync: собираю снимок для MEDMIS-MISmobile.doctor_api_spec ...
[Snapshot] fetched 342 issues
[Snapshot] rule names resolved (342 issues)
[Snapshot] snippets fetched (342 / 342)
[Snapshot] build complete in 27s
===================================================
 Sonar REST API server started
 Bind:        0.0.0.0:6060
 Prompts dir: prompts/
 Prompts:     analyze-issue, auto-resolve, bulk-status-comment, status-comment
===================================================

sonar:MEDMIS-MISmobile.doctor_api_spec> _
```

Приглашение `sonar:PROJECT>` показывает **консольный** project (независим от `defaultProject` HTTP-сервера). Строка `Prompts dir:` показывает, откуда берутся шаблоны промптов для `prompt` и `auto`.

---

## 2. Быстрый старт (типичный сценарий)

```
sonar:MY_PROJECT> status                                   # проверить сессию и снимок
sonar:MY_PROJECT> summary HIGH RELIABILITY                 # сколько issues
sonar:MY_PROJECT> details HIGH RELIABILITY 3               # посмотреть с кодом
sonar:MY_PROJECT> snippet <KEY>                            # один issue целиком
sonar:MY_PROJECT> mark <KEY> accept "Согласовано"          # пометить один
sonar:MY_PROJECT> mark accept MEDIUM MAINTAINABILITY --yes # пометить массово
sonar:MY_PROJECT> cancelall MEDIUM MAINTAINABILITY --yes   # откатить
sonar:MY_PROJECT> quit                                     # выход
```

С новым AI-функционалом:

```
sonar:MY_PROJECT> prompt status-comment <KEY> status=accept # посмотреть готовый промпт
sonar:MY_PROJECT> ollama show                               # настройки LLM
sonar:MY_PROJECT> auto HIGH SECURITY                        # dry-run AI-обработки
sonar:MY_PROJECT> auto HIGH SECURITY --yes                  # запустить в фоне
sonar[auto]:MY_PROJECT> stop                                # graceful-остановка
```

---

## 3. Справочник команд

### 3.1 Служебные

| Команда | Что делает |
|---|---|
| `help`, `?`, `h` | справка по всем командам |
| `status` | сессия + снимок + прогресс сборки + autoRunning |
| `health` | краткая проверка (`sessionReady`, `snapshotStatus`) |
| `project [KEY \| clear]` | показать/переключить консольный project |
| `quit`, `exit`, `q` | выход (REST тоже останавливается; при активной `auto` — отклоняется) |

### 3.2 Сессия

| Команда | Что делает |
|---|---|
| `session show` | прочитать текущий `session.txt` |
| `session set <path>` | заменить `session.txt` из файла + reload + auto build |
| `session reload` | перечитать `session.txt` с диска |
| `session save <path>` | сохранить текущий raw в файл |

### 3.3 Снимок

| Команда | Что делает |
|---|---|
| `refresh` | пересобрать снимок в фоне (async) |
| `refresh wait` | пересобрать синхронно (блокирует консоль) |
| `refresh issue <KEY>` | обновить/удалить один issue в снимке |

### 3.4 Отчёты

Формат: `<команда> [SEV] [QUAL] [...]` — по умолчанию `sev=MEDIUM`, `qual=MAINTAINABILITY`.

| Команда | Что выводит |
|---|---|
| `summary [SEV] [QUAL]` | всего + facets (правила/файлы/severities) |
| `matrix [SEV] [QUAL]` | таблица file × rule |
| `perfile [SEV] [QUAL]` | issues по файлам |
| `details [SEV] [QUAL] [N]` | подробно, с кодом (N issues на файл, по умолчанию 5) |
| `issues [SEV] [QUAL]` | плоский список |
| `keys [SEV] [QUAL]` | только ключи |
| `snippet <KEY>` | один issue + фрагмент кода |

### 3.5 Пометки

| Команда | Что делает |
|---|---|
| `mark <KEY> <transition> [comment...]` | пометить **один** issue |
| `mark <transition> [SEV] [QUAL] [--yes]` | пометить **все** по фильтру |
| `cancel <KEY>` | снять пометку с одного (reopen) |
| `cancelall [SEV] [QUAL] [--yes]` | снять пометки по фильтру |

### 3.6 Промпты (`prompt`) 🆕

Рендерит шаблон из `prompts/<name>.txt`, подставляя `[TAG]` значения. Результат печатается в stdout или сохраняется в файл (`save=<path>`).

| Команда | Что делает |
|---|---|
| `prompt list` | список доступных шаблонов в `prompts/` |
| `prompt <name> <KEY> [status=...] [statusLabel=...]` | промпт для одного issue |
| `prompt <name> keys=A,B,C [status=...]` | промпт для группы по ключам |
| `prompt <name> sev=ALL qual=ALL [status=...]` | промпт по фильтру |
| `prompt <name> ... save=<path>` | сохранить промпт в файл |

**Доступные `<name>`:**

| `<name>` | Файл | Назначение |
|---|---|---|
| `status-comment` | `prompts/status-comment.txt` | разбор одного issue с заданным статусом |
| `bulk-status-comment` | `prompts/bulk-status-comment.txt` | разбор группы |
| `analyze-issue` | `prompts/analyze-issue.txt` | общий анализ без статуса |
| `auto-resolve` | `prompts/auto-resolve.txt` | LLM сам выбирает статус (используется в `auto`) |

**Параметры:** `status=accept|falsepositive|confirm|resolve|wontfix` (по умолчанию `accept`), `statusLabel="..."` — человекочитаемое описание.

Примеры:

```
sonar:MY_PROJECT> prompt list
sonar:MY_PROJECT> prompt status-comment 9ede9b09-... status=accept
sonar:MY_PROJECT> prompt bulk-status-comment keys=A,B,C status=accept
sonar:MY_PROJECT> prompt auto-resolve 9ede9b09-... save=/tmp/auto.txt
```

Правки в `.txt` **применяются сразу** — файл читается при каждом вызове. Если файла нет — консоль сообщит «шаблон не найден» и покажет доступные.

### 3.7 Ollama (`ollama`) 🆕

| Команда | Что делает |
|---|---|
| `ollama show` | показать текущие настройки LLM |
| `ollama set url <URL>` | адрес Ollama (`http://localhost:11434`) |
| `ollama set model <MODEL>` | модель (`llama3`, `qwen2.5-coder:14b`, …) |
| `ollama set timeout <SECONDS>` | таймаут запроса (по умолчанию `300`) |
| `ollama set temperature <FLOAT>` | температура (по умолчанию `0.2`) |
| `ollama test` | проверить соединение и список доступных моделей |
| `ollama reload` | перечитать `ollama.properties` с диска |

Настройки персистентны (в `ollama.properties`). Один раз настроили — работает и в консоли, и в web (кнопка **⚙ Ollama**).

```
sonar:MY_PROJECT> ollama set url http://smwrap.ru:3397
sonar:MY_PROJECT> ollama set model qwen2.5-coder:14b
sonar:MY_PROJECT> ollama test
```

### 3.8 AI-обработка (`auto`) 🆕

Последовательный цикл по всем OPEN/REOPENED issues: промпт `prompts/auto-resolve.txt` → Ollama → парсинг ответа → `bulk_change` + теги. В web это кнопка **«Добавить автоматически комментарий»**.

| Команда | Что делает |
|---|---|
| `auto [SEV] [QUAL]` | dry-run: показать, что будет обработано |
| `auto [SEV] [QUAL] --yes` | запустить обработку в фоне |
| `auto key <KEY>` | dry-run по одному issue |
| `auto key <KEY> --yes` | обработать один issue |
| `stop` | graceful-остановка фоновой обработки |

**После запуска:** приглашение меняется на `sonar[auto]:PROJECT>`, прогресс идёт в stderr строками `[N/M] KEY → статус OK/FAIL`, доступны все команды кроме `quit`.

**Итог:** печатается блок статистики (`Всего / Успешно / Ошибок / Время / По статусам / По фикс-тегам / Failed keys`). Failed keys сохраняются в файл `auto-failed-<PROJECT>-<timestamp>.txt`.

**Важные детали:**
- Промпт берётся из `prompts/auto-resolve.txt` — тот же, что и в web. Правки в файле применяются к обоим.
- Если файла нет / он пуст — fallback на встроенный промпт (обработка не упадёт).
- При 3 подряд сетевых ошибках — авто-стоп. При HTTP 401 (истекла сессия) — немедленный стоп, обновить `session.txt`, `session reload`, перезапустить `auto`.
- После успешного завершения снимок пересобирается в фоне автоматически.

Примеры:

```
sonar:MY_PROJECT> auto ALL ALL --yes
sonar:MY_PROJECT> auto HIGH SECURITY --yes
sonar:MY_PROJECT> auto MEDIUM MAINTAINABILITY --yes
sonar:MY_PROJECT> auto key 9ede9b09-... --yes
sonar[auto]:MY_PROJECT> stop
```

---

## 4. Форматы параметров

| Параметр | Допустимые значения |
|---|---|
| `SEV` | `MEDIUM` \| `HIGH` \| `LOW` \| `INFO` \| `ALL` |
| `QUAL` | `MAINTAINABILITY` \| `RELIABILITY` \| `SECURITY` \| `ALL` |
| `transition` | `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen` |
| `<name>` (prompt) | `status-comment` \| `bulk-status-comment` \| `analyze-issue` \| `auto-resolve` |
| `status` (prompt) | `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` |

Регистр не важен (`high` = `HIGH`, `Accept` = `accept`).

---

## 5. Dry-run и подтверждение массовых операций

- Команды `mark <transition> …`, `cancelall …`, `auto …` **без `--yes`** работают в режиме **dry-run** — печатают план, ничего не меняют.
- С `--yes` — применяют.
- Одиночные `mark <KEY>`, `cancel <KEY>`, `auto key <KEY>` применяются **сразу только с `--yes`** (без него — dry-run).

Пример:

```
sonar:MY_PROJECT> auto HIGH SECURITY

project    = MY_PROJECT
severity   = HIGH
quality    = SECURITY
issues     = 17  (OPEN / REOPENED)
ollama     = http://smwrap.ru:3397  (model: qwen2.5-coder:14b)
prompt     = /opt/sonar/prompts/auto-resolve.txt

DRY RUN — ничего не изменено.
Чтобы запустить обработку: auto HIGH SECURITY --yes
```

---

## 6. Типичные сценарии

### Сменить сессию (перелогин в браузере)
```
sonar:MY_PROJECT> session set new_session.txt
[console] session updated from new_session.txt
          host = sonar.example.com
[console] snapshot rebuild started in background
```
Следить за прогрессом — `status`.

### Обновить один issue после правки кода
```
sonar:MY_PROJECT> refresh issue 9ede9b09-5875-4fbf-8a21-200346c8e415
[console] refreshing issue ... ...
  removed = false   (или true + reason=status_changed|not_found)
  issue   = 9ede9b09-... [MEDIUM/OPEN] src/main/.../Consumer.java:42
```

### Пометить всё HIGH/RELIABILITY как «принято»
```
sonar:MY_PROJECT> mark accept HIGH RELIABILITY --yes
```

### Откатить неудачный массовый accept
```
sonar:MY_PROJECT> cancelall HIGH RELIABILITY           # dry-run: сколько будет
sonar:MY_PROJECT> cancelall HIGH RELIABILITY --yes     # применить
```

### Посмотреть код под конкретным issue
```
sonar:MY_PROJECT> snippet 1a2b3c4d-5e6f-7890-abcd-ef1234567890
```

### Посмотреть готовый промпт для одного issue 🆕
```
sonar:MY_PROJECT> prompt status-comment 9ede9b09-... status=accept
===================================================
 Prompt: status-comment  (12480 chars)
===================================================
Ты — опытный разработчик и эксперт по SonarQube.
... (полный текст из prompts/status-comment.txt с подставленными [TAG])
===================================================
```
Если хочется отдать промпт во внешний инструмент:
```
sonar:MY_PROJECT> prompt status-comment 9ede9b09-... status=accept save=/tmp/prompt.txt
[prompt] сохранено в /tmp/prompt.txt (12480 chars)
```

### Прогнать AI-обработку по всем HIGH/SECURITY 🆕
```
# 1. Один раз настроить Ollama
sonar:MY_PROJECT> ollama set url http://smwrap.ru:3397
sonar:MY_PROJECT> ollama set model qwen2.5-coder:14b
sonar:MY_PROJECT> ollama test

# 2. Dry-run — посмотреть, что будет обработано
sonar:MY_PROJECT> auto HIGH SECURITY
...
DRY RUN — ничего не изменено.
Чтобы запустить обработку: auto HIGH SECURITY --yes

# 3. Запустить в фоне
sonar:MY_PROJECT> auto HIGH SECURITY --yes
=== Запуск массовой обработки через Ollama ===
[auto] промпт: /opt/sonar/prompts/auto-resolve.txt
[auto] запущено в фоне. Для остановки введите 'stop'.
sonar[auto]:MY_PROJECT>
```

Прогресс идёт в stderr:
```
[1/17] 9ede9b09-... — подготовка...
[1/17] 9ede9b09-... — Ollama (qwen2.5-coder:14b)...
[1/17] 9ede9b09-... — применяю accept...
[1/17] 9ede9b09-... → accept [require-fix,error-handling]  OK  (12.4s)
[2/17] 1a2b3c4d-... — подготовка...
...
```

### Остановить AI-обработку (graceful) 🆕
```
sonar[auto]:MY_PROJECT> stop
[auto] запрошена остановка (текущий issue будет доработан, затем цикл завершится).
...
=== Результат ===
Всего:         17
Успешно:       15
Ошибок:        2
Остановлено:   да (по команде 'stop')
Время:         184.3 сек
По статусам:   {accept=12, falsepositive=3}
По фикс-тегам: {require-fix=14, not-require-fix=1}
Failed keys (первые 2 из 2):
  1a2b3c4d-...
  abcdef01-...
Полный список: auto-failed-MY_PROJECT-1736998800000.txt
```

### Работать с другим проектом, не трогая REST
```
sonar:MY_PROJECT> project OTHER_PROJECT
[console] console project = OTHER_PROJECT
sonar:OTHER_PROJECT> refresh wait
```
HTTP-`defaultProject` при этом **не меняется** — REST-клиенты продолжают работать с прежним.

---

## 7. Отличия консоли от REST / Web

| | REST API | Console | Web UI |
|---|---|---|---|
| Формат вывода | JSON | человекочитаемый | интерактивные вкладки |
| Project по умолчанию | `--project` / `SONAR_PROJECT` | команда `project <KEY>` | поле в шапке |
| Dry-run | `?dryRun=false` — применить | `--yes` — применить | кнопка «Применить» |
| Доступ | по сети | только stdin процесса | через браузер |
| Авторизация | Bearer (если включён) | не нужна | не нужна |
| Автообновление после `mark`/`cancel` | нет | да, `refresh issue` для одиночных | неявное (перерисовка) |
| Промпты | `GET /api/prompt/build`, `/api/prompt/list` | `prompt list`, `prompt <name> ...` | кнопка «Сгенерировать промпт» |
| AI-обработка | ❌ нет отдельного REST-эндпоинта | `auto [SEV] [QUAL] [--yes]` | «Добавить автоматически комментарий» |
| Остановка AI | — | `stop` | кнопка «Остановить» |
| Настройка Ollama | — | `ollama set …` | ⚙ Ollama |

---

## 8. Завершение

```
sonar:MY_PROJECT> quit
[console] shutting down...
[main] shutdown complete
```

`quit`/`exit`/`q` останавливает **и консоль, и REST** — процесс выходит. Если нужен только отключённый ввод, но работающий REST — используйте `Ctrl+C` (сработает shutdown hook) или запускайте с `--no-console`.

> При активной `auto` команда `quit` **отклоняется** — сначала выполните `stop`. Это защита от случайной потери прогресса: если прервать `auto` через `Ctrl+C`/`kill`, SonarQube окажется в промежуточном состоянии (часть issues обработана, часть нет).

---

> Полное описание с примерами вывода — в `console.md`.
> Справочник по HTTP-эндпоинтам — в `Info.md`.
> Примеры curl-запросов — в `example.md`.
```

---

## Что добавлено / изменено относительно старой версии

| Раздел | Изменение |
|---|---|
| **Шапка** | Добавлена строка про новый функционал (`prompt`, `ollama`, `auto`, `stop`) + ссылка на `console.md` |
| **1. Запуск** | В выводе при старте теперь видна строка `Prompts dir:` и список доступных промптов |
| **2. Быстрый старт** | Добавлен блок «С новым AI-функционалом» — пример `prompt` / `ollama` / `auto` / `stop` |
| **3.1 Служебные** | В `status` добавлено упоминание `autoRunning`; в `quit` — пометка про отклонение при активной `auto` |
| **3.6 Промпты** | 🆕 Новая секция: таблица `prompt` + `<name>` + примеры |
| **3.7 Ollama** | 🆕 Новая секция: таблица `ollama` + пример настройки |
| **3.8 AI-обработка** | 🆕 Новая секция: `auto` + `stop` + важные детали (fallback, авто-стоп, статистика) |
| **4. Форматы** | Добавлены `<name>` и `status` для `prompt` |
| **5. Dry-run** | Добавлено `auto …` в список команд, работающих по `--yes` |
| **6. Сценарии** | Добавлены: «Посмотреть готовый промпт», «Прогнать AI-обработку», «Остановить AI-обработку» |
| **7. Отличия** | Добавлены столбцы для `prompt`, `auto`, `stop`, `ollama` (консоль vs REST vs Web) |
| **8. Завершение** | Добавлена пометка: при активной `auto` — `quit` отклоняется |