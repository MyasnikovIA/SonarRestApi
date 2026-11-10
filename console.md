# Работа с программой в интерактивной консоли

Помимо REST API, приложение имеет **встроенную интерактивную консоль** — она запускается автоматически вместе с сервером и позволяет выполнять те же операции без HTTP. Каждая команда дублирует соответствующий REST-эндпоинт, но результат печатается в человекочитаемом виде.

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
 ...
===================================================

sonar:MEDMIS-MISmobile.doctor_api_spec>
```

Приглашение показывает текущий «консольный» project. Он **независим** от `defaultProject` HTTP-сервера — можно смотреть один проект в консоли, а REST-клиенты пусть работают с другим.

---

## 1. Справочная информация

### `help` — справка по всем командам

```
sonar:MEDMIS-MISmobile.doctor_api_spec> help
```

Выведет полный список команд с описанием (см. ниже разделы 2–10).

### `status` — состояние сессии, снимка, прогресс

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
```

Если снимок ещё строится — увидите:
```
snapshot:
  status   = BUILDING
  phase    = resolving_rules (150/342)
  ...
```

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

## 12. Завершение работы

### `quit` / `exit` / `q` — остановить консоль

```
sonar:MEDMIS-MISmobile.doctor_api_spec> quit
[console] stopped
[main] shutdown complete
```

> Если консоль работала вместе с REST — REST также остановится (сервер завершает JVM).
> Если вы запускали с `--no-console`, для остановки — `Ctrl+C` или `kill` процесса (по SIGTERM сработает shutdown hook, REST закроется корректно).

---

## Полный справочник команд

| Команда | Аналог в REST | Описание |
|---|---|---|
| `help`, `?`, `h` | — | справка |
| `status` | `/api/status` | состояние сессии и снимка |
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
| `quit`, `exit`, `q` | — | выход |

**Значения:**
- `SEV` = `MEDIUM` \| `HIGH` \| `LOW` \| `INFO` \| `ALL`
- `QUAL` = `MAINTAINABILITY` \| `RELIABILITY` \| `SECURITY` \| `ALL`
- `transition` = `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen`

---

## Типовой сеанс работы

```bash
$ SONAR_PROJECT=MEDMIS-MISmobile.doctor_api_spec java -jar SonarRestApi.jar
...
===================================================
 Console ready. Type 'help' for commands.
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

sonar:MEDMIS-MISmobile.doctor_api_spec> mark 9ede9b09-5875-4fbf-8a21-200346c8e415 accept "Проверено, ложное срабатывание"
[mark] 9ede9b09-... -> accept (comment: Проверено, ложное срабатывание) ...
Батч 1/1 (1): OK
[mark] OK

sonar:MEDMIS-MISmobile.doctor_api_spec> mark accept MEDIUM MAINTAINABILITY --yes
Применяю переход...
[console] applied to 87 issues in 1 batches
[console] snapshot rebuild started in background ('status' to monitor)

sonar:MEDMIS-MISmobile.doctor_api_spec> refresh issue 1a2b3c4d-5e6f-7890-abcd-ef1234567890
[console] refreshing issue 1a2b3c4d-... ...
  removed = true
  reason  = status_changed
  snapshot issues = 341

sonar:MEDMIS-MISmobile.doctor_api_spec> cancelall MEDIUM MAINTAINABILITY --yes
...
[console] reopened 86 / 86 (failed: 0)

sonar:MEDMIS-MISmobile.doctor_api_spec> quit
[console] stopped
[main] shutdown complete
```

## Ключевые отличия консоли от REST

| | REST API | Console |
|---|---|---|
| Формат вывода | JSON | человекочитаемый |
| `project` по умолчанию | `defaultProject` из env/CLI | `project <KEY>` (своя переменная) |
| Dry-run | `?dryRun=true` (по умолчанию) | без `--yes` (по умолчанию) |
| Доступ | по сети | только локально (stdin процесса) |
| Авторизация | Bearer (если включён) | не нужна |
| Подтверждение массовых операций | нет | `--yes` или интерактивно |