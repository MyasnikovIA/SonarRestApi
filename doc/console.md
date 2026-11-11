# Работа с программой в интерактивной консоли

Помимо REST API, приложение имеет **встроенную интерактивную консоль** — она запускается автоматически вместе с сервером и позволяет выполнять те же операции без HTTP. Каждая команда дублирует соответствующий REST-эндпоинт, но результат печатается в человекочитаемом виде. **AI-обработка через Ollama** и **рендер промптов из `prompts/*.txt`** — доступны только в консоли и web-интерфейсе (отдельного REST-эндпоинта для них нет).

## Запуск

```bash
# Минимум — REST + консоль на 0.0.0.0:6060, session.txt из текущей папки
java -jar SonarRestApi.jar

# С проектом по умолчанию и авто-прогревом снимка
java -jar SonarRestApi.jar --project=MEDMIS-MISmobile.doctor_api_spec

# Через переменную окружения
SONAR_PROJECT=MEDMIS-MISmobile.doctor_api_spec java -jar SonarRestApi.jar

# Явный файл сессии
java -jar SonarRestApi.jar --session=/etc/sonar/session.txt \
                           --project=MEDMIS-MISmobile.doctor_api_spec

# Только REST, без консоли (демон)
java -jar SonarRestApi.jar --no-console
```

**После запуска в консоли появится:**

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
 Endpoints:
   GET  /api/prompt/build?name=<n>&project=<p>&issueKey=<k>
   GET  /api/prompt/list
===================================================

sonar:MEDMIS-MISmobile.doctor_api_spec>
```

Строка `Prompts dir:` показывает, откуда будут браться шаблоны промптов. Порядок поиска каталога: `-Dsonar.prompts=...` → `<jar-dir>/prompts` → `<jar-parent>/prompts` → `./prompts`.

Приглашение показывает текущий «консольный» project. Он **независим** от `defaultProject` HTTP-сервера — можно смотреть один проект в консоли, а REST-клиенты пусть работают с другим.

---

## 1. Справочная информация

### `help` — справка по всем командам

```
sonar:MEDMIS-MISmobile.doctor_api_spec> help
```

Выведет полный список команд, сгруппированных по разделам:

- Основное (`help`, `status`, `health`, `project`, `quit`)
- Сессия (`session show/set/reload/save`)
- Снимок (`refresh`, `refresh wait`, `refresh issue <KEY>`)
- Отчёты (`summary`, `matrix`, `perfile`, `details`, `issues`, `keys`, `snippet`)
- Пометки (`mark`, `cancel`, `cancelall`)
- Промпты (`prompt list`, `prompt <name> ...`)
- Ollama (`ollama show/set/test/reload`)
- Авто-обработка (`auto`, `auto key`, `stop`)

### `status` — состояние сессии, снимка, auto-обработки

```
sonar:MEDMIS-MISmobile.doctor_api_spec> status
```

**Пример вывода:**
```
session:
  ready    = true
  path     = session.txt
  host     = sonar.example.com
  hasCookie= true
  hasXsrf  = true
snapshot:
  status   = READY
  phase    = done
  project  = MEDMIS-MISmobile.doctor_api_spec
  issues   = 342
  snippets = 342
  builtAt  = 2025-11-07T12:34:56Z
  host     = sonar.example.com
defaultProject = MEDMIS-MISmobile.doctor_api_spec
consoleProject = MEDMIS-MISmobile.doctor_api_spec
autoRunning    = false
promptsDir     = /opt/sonar-rest-api/prompts
```

Если снимок ещё строится — увидите:
```
snapshot:
  status   = BUILDING
  phase    = resolving_rules (150/342)
  ...
```

Если идёт AI-обработка — `autoRunning = true`.

### `health` — краткая проверка

```
sonar:MEDMIS-MISmobile.doctor_api_spec> health
status        = ok
sessionReady  = true
snapshotReady = true
snapshotStatus= READY
```

### `project` — переключить консольный project

```
sonar:MEDMIS-MISmobile.doctor_api_spec> project
current project = MEDMIS-MISmobile.doctor_api_spec
defaultProject  = MEDMIS-MISmobile.doctor_api_spec

sonar:MEDMIS-MISmobile.doctor_api_spec> project MEDMIS-MISmobile.jobby
[console] console project = MEDMIS-MISmobile.jobby

sonar:MEDMIS-MISmobile.jobby> project clear
[console] project cleared
```

> Переключение `project` **не меняет** HTTP-`defaultProject` — REST-клиенты продолжают работать с прежним проектом. Для работы консоли с новым проектом нужен `refresh`.

---

## 2. Управление сессией

### `session show` — прочитать `session.txt`

```
sonar:MEDMIS-MISmobile.doctor_api_spec> session show
path    = session.txt
exists  = true
--- raw ---
Host: sonar.example.com
Cookie: ...
X-XSRF-TOKEN: ...
-----------
host    = sonar.example.com
hasXsrf = true
```

### `session set <path>` — заменить `session.txt` из файла

Удобно, когда вытащили новый дамп из браузера в файл `new_session.txt`:

```
sonar:MEDMIS-MISmobile.doctor_api_spec> session set new_session.txt
[console] session updated from new_session.txt
          host = sonar.example.com
[console] snapshot rebuild started in background
```

После `session set` автоматически стартует фоновый пересбор снимка — смотреть прогресс через `status`.

### `session reload` — перечитать `session.txt` с диска

Если файл правили вручную (вне программы):

```
sonar:MEDMIS-MISmobile.doctor_api_spec> session reload
[console] reloaded from session.txt
          host = sonar.example.com
```

### `session save <path>` — сохранить текущий raw в файл

```
sonar:MEDMIS-MISmobile.doctor_api_spec> session save backup_session.txt
[console] saved to backup_session.txt
```

---

## 3. Управление снимком (refresh)

### `refresh` — полный пересбор в фоне

Быстрый вариант: команда возвращается сразу, сборка идёт в фоне.

```
sonar:MEDMIS-MISmobile.doctor_api_spec> refresh
[console] refresh started for MEDMIS-MISmobile.doctor_api_spec. Use 'status' to monitor.
```

Следить за прогрессом:

```
sonar:MEDMIS-MISmobile.doctor_api_spec> status
...
snapshot:
  status   = BUILDING
  phase    = fetching_snippets (150/342)
...
```

### `refresh wait` — полный пересбор синхронно

Блокирует консоль до завершения (REST продолжает отвечать параллельно):

```
sonar:MEDMIS-MISmobile.doctor_api_spec> refresh wait
[console] full refresh (sync), please wait...
[Snapshot] fetched 342 issues
[Snapshot] rule names resolved (342 issues)
[Snapshot] snippets fetched (342 / 342)
[Snapshot] build complete in 27s
[console] done: 342 issues, 342 snippets, builtAt=2025-11-07T13:10:00Z
```

### `refresh issue <KEY>` — точечный refresh одного issue

Если кто-то поправил код или закрыл issue в Sonar:

```
sonar:MEDMIS-MISmobile.doctor_api_spec> refresh issue 9ede9b09-5875-4fbf-8a21-200346c8e415
[console] refreshing issue 9ede9b09-5875-4fbf-8a21-200346c8e415 ...
  removed = true
  reason  = status_changed
  issue   = 9ede9b09-... [MEDIUM/OPEN] src/main/java/.../Consumer.java:42
  snapshot issues = 341
```

Возможные ответы:
- `removed = false` — issue обновлён (всё ещё `OPEN`/`CONFIRMED`);
- `removed = true, reason = status_changed` — issue закрыт, удалён из снимка;
- `removed = true, reason = not_found` — в Sonar его уже нет.

---

## 4. Сводка `summary` (аналог `/api/summary`)

**Формат:** `summary [SEV] [QUAL]`

По умолчанию `SEV=MEDIUM`, `QUAL=MAINTAINABILITY`.

### MEDIUM / MAINTAINABILITY

```
sonar:MEDMIS-MISmobile.doctor_api_spec> summary MEDIUM MAINTAINABILITY
```

**Пример вывода:**
```
Всего MEDIUM/MAINTAINABILITY: 87

=== По правилам ===
      9  java:S2259
      5  java:S2589
      3  java:S2201

=== По файлам ===
      4  src/main/java/ru/miacomsoft/KafkaConsumer.java
      3  src/main/java/ru/miacomsoft/Producer.java

=== По severity ===
     87  MEDIUM
```

### HIGH / ALL

```
sonar:MEDMIS-MISmobile.doctor_api_spec> summary HIGH ALL
```

### ALL / ALL — полная картина проекта

```
sonar:MEDMIS-MISmobile.doctor_api_spec> summary ALL ALL
```

### Без параметров (используются дефолты)

```
sonar:MEDMIS-MISmobile.doctor_api_spec> summary
```

---

## 5. Матрица `matrix` (аналог `/api/matrix`)

**Формат:** `matrix [SEV] [QUAL]`

Печатает таблицу «файл × правило». При большом числе правил уезжает за край терминала — используйте, когда правил немного.

```
sonar:MEDMIS-MISmobile.doctor_api_spec> matrix ALL ALL
```

**Пример вывода:**
```
Всего: 342

=== Файл × правило ===
file / rule                                  S2259     S2589     S2201
KafkaConsumer.java                               4         2         0
Producer.java                                    1         0         2
Controller.java                                  0         1         1
```

### Только по HIGH

```
sonar:MEDMIS-MISmobile.doctor_api_spec> matrix HIGH RELIABILITY
```

---

## 6. Замечания по файлам `perfile` (аналог `/api/perfile`)

**Формат:** `perfile [SEV] [QUAL]`

```
sonar:MEDMIS-MISmobile.doctor_api_spec> perfile MEDIUM RELIABILITY
```

**Пример вывода:**
```
=== Замечания по файлам: 23 файлов, 87 замечаний ===

src/main/java/ru/miacomsoft/KafkaConsumer.java — 4:
  L42    java:S2259   MEDIUM   "NullPointerException" will be thrown...  
  L78    java:S2589   MEDIUM   Boolean expressions should not be gratuitous...  
  L120   java:S2201   MEDIUM   Return values should not be ignored...  

src/main/java/ru/miacomsoft/Producer.java — 3:
  L15    java:S2589   MEDIUM   Boolean expressions should not be gratuitous...  
  ...
```

---

## 7. Подробно с кодом `details` (аналог `/api/details`)

**Формат:** `details [SEV] [QUAL] [N]`

`N` — сколько issue на файл показывать с кодом (по умолчанию 5).

```
sonar:MEDMIS-MISmobile.doctor_api_spec> details MEDIUM MAINTAINABILITY 10
```

**Пример вывода (фрагмент):**
```
=== ПОДРОБНЫЙ ОТЧЁТ ПО ФАЙЛАМ: 23 файлов, 87 замечаний ===

╔════════════════════════════════════════════════════════════════════════════════╗
║ ФАЙЛ: src/main/java/ru/miacomsoft/KafkaConsumer.java                          ║
║ Замечаний: 45                                                                  ║
╚════════════════════════════════════════════════════════════════════════════════╝

  ┌─ Замечание #1 (строка 42) ──────────────────────────────────────────────
  │ "NullPointerException" will be thrown when invoking method "get()".
  │ Правило: Null pointers should not be dereferenced (java:S2259)
  │ Серьёзность: MEDIUM
  │ Код:
  │     40 | public void handle(Message m) {
  │     41 |     if (m.getPayload() != null) {
  │     42 |         process(m.getPayload().get());   // <- NPE
  │     43 |     }
  │     44 | }
  └──────────────────────────────────────────────────────────────────────
  ...
  ... и ещё 35 замечаний в этом файле ...
```

Если сниппеты не нужны — `details SEV QUAL 0`.

---

## 8. Плоский список issues (аналог `/api/issues`)

**Формат:** `issues [SEV] [QUAL]` (синоним — `list`)

```
sonar:MEDMIS-MISmobile.doctor_api_spec> issues HIGH ALL
```

**Пример вывода:**
```
17 issues (HIGH/ALL)
  9ede9b09-...  HIGH   src/main/java/.../KafkaConsumer.java:42   java:S2259   "NullPointerException" will be thrown...
  1a2b3c4d-...  HIGH   src/main/java/.../Producer.java:78        java:S2589   Boolean expressions should not be gratuitous...
  ...
```

---

## 9. Только ключи `keys` (аналог `/api/keys`)

**Формат:** `keys [SEV] [QUAL]`

Удобно, когда нужно быстро получить список для дальнейшего `mark`/`cancel` или копирования:

```
sonar:MEDMIS-MISmobile.doctor_api_spec> keys MEDIUM MAINTAINABILITY
```

**Пример вывода:**
```
87 keys (MEDIUM/MAINTAINABILITY)
  9ede9b09-5875-4fbf-8a21-200346c8e415
  1a2b3c4d-5e6f-7890-abcd-ef1234567890
  ...
```

---

## 10. Один issue со сниппетом `snippet` (аналог `/api/snippet`)

**Формат:** `snippet <KEY>`

```
sonar:MEDMIS-MISmobile.doctor_api_spec> snippet 9ede9b09-5875-4fbf-8a21-200346c8e415
```

**Пример вывода:**
```
================================================================================
"NullPointerException" will be thrown when invoking method "get()".
Null pointers should not be dereferenced
java:S2259 | src/main/java/ru/miacomsoft/KafkaConsumer.java | L42
status = OPEN | severity = MEDIUM
================================================================================
  40 | public void handle(Message m) {
  41 |     if (m.getPayload() != null) {
  42 |         process(m.getPayload().get());   // <- NPE
  43 |     }
  44 | }
```

Берёт данные из снимка (если есть), иначе идёт live в Sonar.

---

## 11. Пометки: `mark` и `cancel`

Все операции пометки поддерживают `--yes` или интерактивное подтверждение. По умолчанию — dry-run.

### 11.1 `mark <KEY> <transition> [comment...]` — пометить ОДИН issue

Переход + комментарий (комментарий прикрепляется к переходу, попадает в историю Sonar).

**Доступные transitions:** `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen`

#### Пример: пометить как «принято» с комментарием

```
sonar:MEDMIS-MISmobile.doctor_api_spec> mark 9ede9b09-5875-4fbf-8a21-200346c8e415 accept "Согласовано с командой"
[mark] 9ede9b09-... -> accept (comment: Согласовано с командой) ...
Батч 1/1 (1): OK
[mark] OK
```

#### Пример: пометить как ложное срабатывание

```
sonar:MEDMIS-MISmobile.doctor_api_spec> mark 1a2b3c4d-5e6f-7890-abcd-ef1234567890 falsepositive "Не является дефектом, код корректен"
[mark] 1a2b3c4d-... -> falsepositive (comment: Не является дефектом, код корректен) ...
Батч 1/1 (1): OK
[mark] OK
```

#### Пример: reopen через mark (эквивалент `cancel`)

```
sonar:MEDMIS-MISmobile.doctor_api_spec> mark 9ede9b09-5875-4fbf-8a21-200346c8e415 reopen
[mark] 9ede9b09-... -> reopen ...
reopen [1/1] 9ede9b09-...: OK
[mark] OK
```

> После `mark <KEY> ...` автоматически вызывается `refresh issue <KEY>` — снимок остаётся консистентным без полного пересбора.

### 11.2 `mark <transition> [SEV] [QUAL] [--yes]` — пометить МАССОВО по фильтру

Первым аргументом идёт **transition**, а не key — это автоматически распознаётся.

#### Dry-run (по умолчанию, ничего не меняет)

```
sonar:MEDMIS-MISmobile.doctor_api_spec> mark accept MEDIUM MAINTAINABILITY
project    = MEDMIS-MISmobile.doctor_api_spec
severity   = MEDIUM
quality    = MAINTAINABILITY
transition = accept
issues     = 87
batches    = 1 (размер 500)

DRY RUN — ничего не изменено.
Чтобы применить: mark accept MEDIUM MAINTAINABILITY --yes
```

#### Применить

```
sonar:MEDMIS-MISmobile.doctor_api_spec> mark accept MEDIUM MAINTAINABILITY --yes
project    = MEDMIS-MISmobile.doctor_api_spec
severity   = MEDIUM
quality    = MAINTAINABILITY
transition = accept
issues     = 87
batches    = 1 (размер 500)

Применяю переход...
Батч 1/1 (87): OK
[console] applied to 87 issues in 1 batches
[console] snapshot rebuild started in background ('status' to monitor)
```

После успеха стартует фоновый пересбор снимка (все issues в снимке актуализируются).

#### Bulk accept только по HIGH/RELIABILITY

```
sonar:MEDMIS-MISmobile.doctor_api_spec> mark accept HIGH RELIABILITY --yes
```

#### Bulk falsepositive по MEDIUM/MAINTAINABILITY

```
sonar:MEDMIS-MISmobile.doctor_api_spec> mark falsepositive MEDIUM MAINTAINABILITY --yes
```

#### Bulk reopen через mark

```
sonar:MEDMIS-MISmobile.doctor_api_spec> mark reopen MEDIUM MAINTAINABILITY --yes
[console] reopened 85 / 87 (failed: 2)
failed keys: 9ede9b09-..., 1a2b3c4d-...
```

### 11.3 `cancel <KEY>` — снять пометку с одного issue (reopen)

```
sonar:MEDMIS-MISmobile.doctor_api_spec> cancel 9ede9b09-5875-4fbf-8a21-200346c8e415
[cancel] 9ede9b09-5875-4fbf-8a21-200346c8e415 -> reopen ...
reopen [1/1] 9ede9b09-5875-4fbf-8a21-200346c8e415: OK
[cancel] OK
```

После успеха — `refresh issue` для этого ключа (issue либо обновится, либо удалится из снимка).

### 11.4 `cancelall [SEV] [QUAL] [--yes]` — снять пометки массово

Алиас: `cancel-all`.

#### Dry-run

```
sonar:MEDMIS-MISmobile.doctor_api_spec> cancelall MEDIUM MAINTAINABILITY
project  = MEDMIS-MISmobile.doctor_api_spec
severity = MEDIUM
quality  = MAINTAINABILITY
issues   = 87

DRY RUN — ничего не изменено.
Чтобы применить: cancelall MEDIUM MAINTAINABILITY --yes
```

#### Применить

```
sonar:MEDMIS-MISmobile.doctor_api_spec> cancelall MEDIUM MAINTAINABILITY --yes
project  = MEDMIS-MISmobile.doctor_api_spec
severity = MEDIUM
quality  = MAINTAINABILITY
issues   = 87

Открываю issues обратно (reopen)...
reopen [1/87] 9ede9b09-...: OK
reopen [2/87] 1a2b3c4d-...: OK
...
[console] reopened 87 / 87 (failed: 0)
[console] snapshot rebuild started in background
```

---

## 12. Рендер промптов из файлов (`prompt`)

Шаблоны промптов лежат в `prompts/*.txt`. Правки в этих файлах **вступают в силу сразу** — файл читается при каждом вызове `prompt` (и при каждом обращении к `/api/prompt/build`).

### 12.1 Что умеет команда

| Действие | Синтаксис |
|---|---|
| Список доступных шаблонов | `prompt list` |
| Один issue по ключу | `prompt <name> <KEY> [status=...] [statusLabel=...]` |
| Группа issues по ключам | `prompt <name> keys=A,B,C [status=...]` |
| Все issues по фильтру | `prompt <name> sev=ALL qual=ALL [status=...]` |
| Сохранить в файл | `prompt <name> ... save=<path>` |

### 12.2 Доступные имена шаблонов

| `<name>` | Файл | Назначение |
|---|---|---|
| `status-comment` | `prompts/status-comment.txt` | Разбор одного issue с заданным статусом |
| `bulk-status-comment` | `prompts/bulk-status-comment.txt` | Разбор группы issues |
| `analyze-issue` | `prompts/analyze-issue.txt` | Общий анализ (без фиксации статуса) |
| `auto-resolve` | `prompts/auto-resolve.txt` | LLM сам выбирает статус (используется в `auto`) |

### 12.3 Параметры

- `status=accept|falsepositive|confirm|resolve|wontfix` — статус для `status-comment` / `bulk-status-comment` (по умолчанию `accept`).
- `statusLabel="..."` — человекочитаемое описание статуса (по умолчанию — из встроенного маппинга).
- `keys=A,B,C` — группа issue по ключам (для `bulk-status-comment`).
- `sev=ALL`, `qual=ALL` — фильтр по issues (для `bulk-status-comment`).
- `save=<path>` — записать результат в файл вместо stdout.

### 12.4 Примеры

#### `prompt list`

```
sonar:MEDMIS-MISmobile.doctor_api_spec> prompt list
Доступные шаблоны в /opt/sonar/prompts:
  analyze-issue
  auto-resolve
  bulk-status-comment
  status-comment
```

Если каталог пуст — консоль покажет, какие файлы ожидаются, и откуда они будут созданы при старте (через `BootstrapFiles`).

#### Промпт для одного issue (статус = accept)

```
sonar:MEDMIS-MISmobile.doctor_api_spec> prompt status-comment 9ede9b09-5875-4fbf-8a21-200346c8e415 status=accept
===================================================
 Prompt: status-comment  (12480 chars)
===================================================
Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).
...
(полный текст из prompts/status-comment.txt с подставленными [TAG])
===================================================
```

#### Промпт для группы issues

```
sonar:MEDMIS-MISmobile.doctor_api_spec> prompt bulk-status-comment keys=9ede9b09-...,1a2b3c4d-... status=accept
===================================================
 Prompt: bulk-status-comment  (8932 chars)
===================================================
...
```

#### Промпт по фильтру

```
sonar:MEDMIS-MISmobile.doctor_api_spec> prompt bulk-status-comment sev=HIGH qual=RELIABILITY status=accept
```

#### Промпт для одного issue с сохранением в файл

```
sonar:MEDMIS-MISmobile.doctor_api_spec> prompt auto-resolve 9ede9b09-... save=/tmp/auto.txt
[prompt] сохранено в /tmp/auto.txt (8493 chars)
```

Файл можно отдать в `curl`/`jq`/Ollama — весь текст промпта уже собран.

#### Промпт для анализа (без статуса)

```
sonar:MEDMIS-MISmobile.doctor_api_spec> prompt analyze-issue 9ede9b09-...
```

### 12.5 Поведение при отсутствии шаблона

- Если `prompts/<name>.txt` **не существует** — консоль выведет сообщение «шаблон не найден» и список доступных.
- Если файл существует, но **пустой** — консоль сообщит «промпт пуст».
- Если файл повреждён/содержит невалидные `[TAG]` — `PromptTemplate` подставит пустые строки для неизвестных тегов; результат может быть бессмысленным, но команда не упадёт.

---

## 13. Ollama: настройка LLM и массовая AI-обработка (`auto`)

Автоматическая обработка — это **последовательный цикл**: для каждого issue берётся промпт из `prompts/auto-resolve.txt`, отправляется в Ollama, ответ парсится, статус+комментарий+теги применяются через `bulk_change`. Эта же логика работает и в web (кнопка «Добавить автоматически комментарий»).

### 13.1 Настройка Ollama (`ollama`)

| Команда | Назначение |
|---|---|
| `ollama show` | Показать текущие настройки |
| `ollama set url <URL>` | Адрес Ollama (например `http://localhost:11434`) |
| `ollama set model <MODEL>` | Модель (`llama3`, `qwen2.5-coder:14b`, …) |
| `ollama set timeout <SECONDS>` | Таймаут запроса (по умолчанию `300`) |
| `ollama set temperature <FLOAT>` | Температура (по умолчанию `0.2`) |
| `ollama test` | Проверить соединение и список доступных моделей |
| `ollama reload` | Перечитать `ollama.properties` с диска |

Настройки персистентны — хранятся в `ollama.properties` рядом с JAR (или в `--ollama-config=<path>`).

#### Пример полной настройки

```
sonar:MEDMIS-MISmobile.doctor_api_spec> ollama set url http://smwrap.ru:3397
[ollama] saved

sonar:MEDMIS-MISmobile.doctor_api_spec> ollama set model qwen2.5-coder:14b
[ollama] saved

sonar:MEDMIS-MISmobile.doctor_api_spec> ollama test
Доступные модели:
  - qwen2.5-coder:14b
  - llama3:8b
[ollama] OK: http://smwrap.ru:3397
```

Если `ollama test` падает — это **сетевая проблема**, обработка через `auto` не будет работать. Настройте URL и убедитесь, что Ollama запущена с `OLLAMA_HOST=0.0.0.0 OLLAMA_ORIGINS='*'`.

### 13.2 Запуск массовой обработки (`auto`)

#### Синтаксис

```
auto [SEV] [QUAL]           # dry-run: показать, что будет обработано
auto [SEV] [QUAL] --yes     # запустить обработку в фоне
auto key <KEY>              # dry-run по одному issue
auto key <KEY> --yes        # обработать один issue
stop                        # graceful-остановка фоновой обработки
```

#### Допустимые SEV/QUAL

- `SEV` = `HIGH | MEDIUM | LOW | INFO | ALL`
- `QUAL` = `MAINTAINABILITY | RELIABILITY | SECURITY | ALL`

#### Примеры комбинаций

```
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
```

### 13.3 Dry-run — посмотреть, что будет обработано

```
sonar:MEDMIS-MISmobile.doctor_api_spec> auto HIGH SECURITY

project    = MEDMIS-MISmobile.doctor_api_spec
severity   = HIGH
quality    = SECURITY
issues     = 17  (OPEN / REOPENED)
ollama     = http://smwrap.ru:3397  (model: qwen2.5-coder:14b)
prompt     = /opt/sonar/prompts/auto-resolve.txt

DRY RUN — ничего не изменено.
Первые 5 issues:
  9ede9b09-...  HIGH  "NullPointerException" will be thrown when invoking method "get()".
  1a2b3c4d-...  HIGH  Boolean expressions should not be gratuitous.
  ...
  ... и ещё 12

Чтобы запустить обработку: auto HIGH SECURITY --yes
```

### 13.4 Боевой запуск

```
sonar:MEDMIS-MISmobile.doctor_api_spec> auto HIGH SECURITY --yes

=== Запуск массовой обработки через Ollama ===

[auto] промпт: /opt/sonar/prompts/auto-resolve.txt
[auto] запущено в фоне. Для остановки введите 'stop'.
```

После запуска приглашение меняется:

```
sonar[auto]:MEDMIS-MISmobile.doctor_api_spec>
```

Маркер `[auto]` показывает, что обработка идёт. В это время **все** команды (кроме `quit` — он заблокирован) доступны.

### 13.5 Прогресс (stderr)

Прогресс выводится в stderr, по одной строке на этап:

```
[1/17] 9ede9b09-... — подготовка...
[1/17] 9ede9b09-... — Ollama (qwen2.5-coder:14b)...
[1/17] 9ede9b09-... — применяю accept...
[1/17] 9ede9b09-... → accept [require-fix,error-handling]  OK  (12.4s)
[2/17] 1a2b3c4d-... — подготовка...
...
```

- `OK` — зелёный (`\u001B[32m`).
- `FAIL` — красный (`\u001B[31m`).
- В stderr — не конфликтует с stdin-эхом пользователя в stdout.

### 13.6 Остановка (`stop`)

```
sonar[auto]:MEDMIS-MISmobile.doctor_api_spec> stop
[auto] запрошена остановка (текущий issue будет доработан, затем цикл завершится).
```

`stop` — **graceful**: текущий issue дорабатывается до конца (или падает по ошибке), после чего цикл завершается. Уже обработанные issues остаются обработанными.

Пока `auto` активна, команда `quit` **отклоняется** — сначала нужно выполнить `stop`.

### 13.7 Итоговая статистика

После завершения (или `stop`) печатается блок:

```
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
Полный список: auto-failed-MEDMIS-MISmobile.doctor_api_spec-1736998800000.txt
[auto] Обновляю снимок в фоне (status — прогресс)
```

### 13.8 Что происходит при сбоях

| Ситуация | Поведение |
|---|---|
| HTTP 401 от SonarQube (сессия истекла) | Немедленный стоп всего прогона. Печатается блок «СЕССИЯ ИСТЕКЛА» с инструкцией обновить `session.txt`. |
| 3 подряд сетевые ошибки (Ollama/сеть) | Аварийный стоп. Печатается блок «OLLAMA/СЕТЬ НЕДОСТУПНЫ». |
| Одиночная ошибка (не сеть, не 401) | `failed++`, переход к следующему issue. |
| LLM вернул ответ без строки `Статус:` | `failed++`, лог в stderr, переход к следующему. |

Failed keys сохраняются в файл `auto-failed-<PROJECT>-<timestamp>.txt` рядом с JAR:

```
# Auto processing failed keys
# project:    MEDMIS-MISmobile.doctor_api_spec
# finished:   2025-11-07T14:02:33Z
# total:      17
# success:    15
# failed:     2
# ---
1a2b3c4d-...
abcdef01-...
```

Этот файл можно использовать для точечной повторной обработки (`auto key <KEY> --yes`).

### 13.9 Что сохраняется после `auto`

После **успешного** завершения (не прерванного `stop`/401/сетью):

- Снимок автоматически пересобирается в фоне (`status` — прогресс).
- Теги **сохраняются**: существующие у issue + предложенные LLM (`require-fix` ↔ `not-require-fix` заменяют друг друга).
- Статус применяется через `bulk_change` (один issue за запрос, батчами).

После остановки по 401 / сети — снимок **не** пересобирается (в нём могут быть неактуальные статусы).

### 13.10 Обработка одного issue (`auto key <KEY>`)

Синхронная, полезная для тестирования:

```
sonar:MEDMIS-MISmobile.doctor_api_spec> auto key 9ede9b09-5875-4fbf-8a21-200346c8e415

key        = 9ede9b09-...
status     = OPEN
component  = src/main/java/ru/miacomsoft/KafkaConsumer.java:42
rule       = java:S2259
message    = "NullPointerException" will be thrown ...
ollama     = http://smwrap.ru:3397  (model: qwen2.5-coder:14b)
prompt     = /opt/sonar/prompts/auto-resolve.txt

DRY RUN — добавьте --yes, чтобы применить.
```

С `--yes` — отправляет промпт в Ollama, парсит ответ, применяет статус/теги, обновляет `refresh issue`.

---

## 14. Завершение работы

### `quit` / `exit` / `q` — остановить консоль

```
sonar:MEDMIS-MISmobile.doctor_api_spec> quit
[console] stopped
[main] shutdown complete
```

> Если консоль работала вместе с REST — REST также остановится (сервер завершает JVM).
> Если вы запускали с `--no-console`, для остановки — `Ctrl+C` или `kill` процесса (по SIGTERM сработает shutdown hook, REST закроется корректно).

> Если `auto` в этот момент активна, `quit` будет отклонён — сначала `stop`.

---

## Полный справочник команд

| Команда | Аналог в REST / Web | Описание |
|---|---|---|
| `help`, `?`, `h` | — | справка |
| `status` | `/api/status` | состояние сессии, снимка, auto |
| `health` | `/api/health` | краткая проверка |
| `project [KEY\|clear]` | — | консольный project (не влияет на REST) |
| `session show` | `GET /api/session` | прочитать `session.txt` |
| `session set <path>` | `PUT /api/session` | заменить `session.txt` + auto build |
| `session reload` | `POST /api/session/reload` | перечитать с диска |
| `session save <path>` | — | сохранить raw в файл |
| `refresh` | `POST /api/refresh` | async пересбор |
| `refresh wait` | `POST /api/refresh?wait=true` | sync пересбор |
| `refresh issue <KEY>` | `POST /api/refresh/issue` | точечный refresh |
| `summary [SEV] [QUAL]` | `/api/summary` | сводка |
| `matrix [SEV] [QUAL]` | `/api/matrix` | матрица file × rule |
| `perfile [SEV] [QUAL]` | `/api/perfile` | issues по файлам |
| `details [SEV] [QUAL] [N]` | `/api/details` | с фрагментами кода |
| `issues [SEV] [QUAL]` / `list` | `/api/issues` | плоский список |
| `keys [SEV] [QUAL]` | `/api/keys` | только ключи |
| `snippet <KEY>` | `/api/snippet` | один issue + код |
| `mark <KEY> <transition> [comment]` | `POST /api/mark?key=...` | пометить один |
| `mark <transition> [SEV] [QUAL] [--yes]` | `POST /api/mark?sev=&qual=...` | пометить массово |
| `cancel <KEY>` | `POST /api/cancel?key=...` | снять с одного |
| `cancelall [SEV] [QUAL] [--yes]` | `POST /api/cancel?sev=&qual=...` | снять массово |
| `prompt list` | `GET /api/prompt/list` | список шаблонов |
| `prompt <name> <KEY> [params]` | `GET /api/prompt/build?...` | промпт для одного issue |
| `prompt <name> keys=A,B,C [params]` | `GET /api/prompt/build?...` | промпт для группы |
| `prompt <name> sev=&qual= [params]` | `GET /api/prompt/build?...` | промпт по фильтру |
| `prompt <name> ... save=<path>` | — | сохранить промпт в файл |
| `ollama show` | — | текущие настройки LLM |
| `ollama set url <URL>` | — | адрес Ollama |
| `ollama set model <MODEL>` | — | модель LLM |
| `ollama set timeout <SECONDS>` | — | таймаут запроса |
| `ollama set temperature <F>` | — | температура |
| `ollama test` | — | проверить соединение |
| `ollama reload` | — | перечитать `ollama.properties` |
| `auto [SEV] [QUAL]` | Web: «Добавить автоматически комментарий» | dry-run AI-обработки |
| `auto [SEV] [QUAL] --yes` | Web: то же, запуск | массовая AI-обработка |
| `auto key <KEY> [--yes]` | — | AI по одному issue |
| `stop` | Web: кнопка «Остановить» | graceful-остановка `auto` |
| `quit`, `exit`, `q` | — | выход |

**Значения:**
- `SEV` = `MEDIUM` \| `HIGH` \| `LOW` \| `INFO` \| `ALL`
- `QUAL` = `MAINTAINABILITY` \| `RELIABILITY` \| `SECURITY` \| `ALL`
- `transition` = `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen`
- `<name>` = `status-comment` \| `bulk-status-comment` \| `analyze-issue` \| `auto-resolve`

---

## Типовой сеанс работы

```bash
$ SONAR_PROJECT=MEDMIS-MISmobile.doctor_api_spec java -jar SonarRestApi.jar
...
===================================================
 Console ready. Type 'help' for commands.
 Prompts dir: prompts/
===================================================
[Warmup] sync: собираю снимок для MEDMIS-MISmobile.doctor_api_spec ...
[Snapshot] fetched 342 issues
[Snapshot] rule names resolved (342 issues)
[Snapshot] snippets fetched (342 / 342)
[Snapshot] build complete in 27s
===================================================
 Sonar REST API server started
 Bind:        0.0.0.0:6060
===================================================

sonar:MEDMIS-MISmobile.doctor_api_spec> status
...
snapshot:
  status   = READY
  issues   = 342
  ...
autoRunning    = false
promptsDir     = /opt/sonar/prompts

sonar:MEDMIS-MISmobile.doctor_api_spec> summary HIGH RELIABILITY
Всего HIGH/RELIABILITY: 17
=== По правилам ===
      9  java:S2259
      5  java:S2589
      3  java:S2201
...

sonar:MEDMIS-MISmobile.doctor_api_spec> details HIGH RELIABILITY 3
(печатается подробный отчёт с кодом)

sonar:MEDMIS-MISmobile.doctor_api_spec> snippet 9ede9b09-5875-4fbf-8a21-200346c8e415
================================================================================
"NullPointerException" will be thrown when invoking method "get()".
...
================================================================================

# (1) Посмотреть готовый промпт для одного issue
sonar:MEDMIS-MISmobile.doctor_api_spec> prompt status-comment 9ede9b09-... status=accept
===================================================
 Prompt: status-comment  (12480 chars)
===================================================
Ты — опытный разработчик ...
===================================================

# (2) Настроить Ollama
sonar:MEDMIS-MISmobile.doctor_api_spec> ollama set url http://smwrap.ru:3397
[ollama] saved
sonar:MEDMIS-MISmobile.doctor_api_spec> ollama set model qwen2.5-coder:14b
[ollama] saved
sonar:MEDMIS-MISmobile.doctor_api_spec> ollama test
[ollama] OK: http://smwrap.ru:3397

# (3) Ручная пометка одного issue
sonar:MEDMIS-MISmobile.doctor_api_spec> mark 9ede9b09-5875-4fbf-8a21-200346c8e415 accept "Проверено, ложное срабатывание"
[mark] 9ede9b09-... -> accept (comment: Проверено, ложное срабатывание) ...
Батч 1/1 (1): OK
[mark] OK

# (4) Массовая пометка по фильтру
sonar:MEDMIS-MISmobile.doctor_api_spec> mark accept MEDIUM MAINTAINABILITY --yes
Применяю переход...
[console] applied to 87 issues in 1 batches
[console] snapshot rebuild started in background ('status' to monitor)

# (5) Dry-run AI-обработки, затем боевой запуск
sonar:MEDMIS-MISmobile.doctor_api_spec> auto HIGH SECURITY
...
DRY RUN — ничего не изменено.
Чтобы запустить обработку: auto HIGH SECURITY --yes

sonar:MEDMIS-MISmobile.doctor_api_spec> auto HIGH SECURITY --yes
=== Запуск массовой обработки через Ollama ===
[auto] запущено в фоне. Для остановки введите 'stop'.
sonar[auto]:MEDMIS-MISmobile.doctor_api_spec>

# (6) Остановка auto (если передумали)
sonar[auto]:MEDMIS-MISmobile.doctor_api_spec> stop
[auto] запрошена остановка (текущий issue будет доработан, затем цикл завершится).
...
=== Результат ===
Всего:         17
Успешно:       15
Ошибок:        2
Остановлено:   да (по команде 'stop')
...

# (7) Точечный refresh после ручных правок
sonar:MEDMIS-MISmobile.doctor_api_spec> refresh issue 1a2b3c4d-5e6f-7890-abcd-ef1234567890
[console] refreshing issue 1a2b3c4d-... ...
  removed = true
  reason  = status_changed
  snapshot issues = 341

sonar:MEDMIS-MISmobile.doctor_api_spec> quit
[console] stopped
[main] shutdown complete
```

---

## Ключевые отличия консоли от REST / Web

| | REST API | Console | Web UI |
|---|---|---|---|
| Формат вывода | JSON | человекочитаемый | интерактивные вкладки |
| `project` по умолчанию | `defaultProject` из env/CLI | `project <KEY>` (своя переменная) | поле `project` в шапке |
| Dry-run | `?dryRun=true` (по умолчанию) | без `--yes` (по умолчанию) | модалка с кнопкой «Применить» |
| Доступ | по сети | только локально (stdin процесса) | через браузер |
| Авторизация | Bearer (если включён) | не нужна | не нужна |
| Подтверждение массовых операций | нет | `--yes` или интерактивно | диалог/чекбокс |
| Промпты | `GET /api/prompt/build`, `/api/prompt/list` | `prompt list`, `prompt <name> ...` | кнопка «Сгенерировать промпт» |
| AI-обработка | ❌ нет отдельного эндпоинта | `auto [SEV] [QUAL] [--yes]` | «Добавить автоматически комментарий» |
| Остановка AI | — | `stop` | кнопка «Остановить» |
| Настройка Ollama | — | `ollama set ...` | ⚙ Ollama |
| Экспорт в MD | — | — | 📥 Экспорт MD (вкладка Accepted) |

### Важно про `auto`

- **Промпт** для `auto` берётся из `prompts/auto-resolve.txt` — тот же файл, что и в web. Правки в файле применяются в консоли и в web одинаково.
- **Fallback**: если файла нет / файл пуст / render упал — `auto` использует встроенный промпт `PromptBuilder.buildAutoResolvePrompt`. Это гарантирует, что обработка не остановится из-за отсутствия файла.
- **Остановка**: `stop` — единственный способ корректно завершить auto, если она запущена. Kill процесса оставит SonarQube в промежуточном состоянии (какие-то issues обработаны, какие-то нет).
- **Итоговый файл `auto-failed-*.txt`** создаётся всегда, если были ошибки — можно использовать для повторной обработки.
