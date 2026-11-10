# Примеры работы с консольным чатом

Ниже — тот же «каталог диалогов», что и для REST, но для **интерактивной консоли** (`java -jar SonarRestApi.jar`). Пользователь вводит текстовые команды, получает человекочитаемый вывод. Каждая команда дублирует REST-эндпоинт — но без JSON и HTTP.

---

## Как это устроено

```
[Пользователь]  ──stdin──▶  [Console]  ──внутри──▶  Sonar / Snapshot
                    ◀──stdout── (человекочитаемый вывод)
```

Консоль работает **локально** (stdin процесса), авторизация не нужна — сессия уже загружена из `session.txt`. REST-сервер работает параллельно в том же процессе.

Приглашение показывает текущий project:
```
sonar:MEDMIS-MISmobile.doctor_api_spec> _
```

---

## 1. Сводка — `summary`

**Пользователь вводит:**
```
summary MEDIUM MAINTAINABILITY
```

**Консоль отвечает:**
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

**Варианты той же команды:**
- `summary` — дефолты `MEDIUM/MAINTAINABILITY`
- `summary HIGH RELIABILITY`
- `summary ALL ALL` — полная картина проекта

**Аналог в REST:** `GET /api/summary?…`

---

## 2. Матрица file × rule — `matrix`

**Пользователь вводит:**
```
matrix ALL ALL
```

**Консоль отвечает:**
```
Всего: 342

=== Файл × правило ===
file / rule                                  S2259     S2589     S2201
KafkaConsumer.java                               4         2         0
Producer.java                                    1         0         2
Controller.java                                  0         1         1
```

**Подсказка:** при большом числе правил таблица «уезжает» — используйте `matrix HIGH RELIABILITY` или `details`.

**Аналог в REST:** `GET /api/matrix?…`

---

## 3. Замечания по файлам — `perfile`

**Пользователь вводит:**
```
perfile MEDIUM RELIABILITY
```

**Консоль отвечает:**
```
=== Замечания по файлам: 2 файлов, 17 замечаний ===

src/main/java/ru/miacomsoft/KafkaConsumer.java — 9:
  L42    java:S2259   MEDIUM   "NullPointerException" will be thrown...  
  L78    java:S2589   MEDIUM   Boolean expressions should not be gratuitous...  
  L120   java:S2201   MEDIUM   Return values should not be ignored...  

src/main/java/ru/miacomsoft/Producer.java — 8:
  L15    java:S2589   MEDIUM   Boolean expressions should not be gratuitous...  
  ...
```

**Естественное продолжение:** `details MEDIUM RELIABILITY 3` — там будет код.

**Аналог в REST:** `GET /api/perfile?…`

---

## 4. Подробно с кодом — `details`

**Пользователь вводит:**
```
details MEDIUM MAINTAINABILITY 10
```

**Консоль отвечает (фрагмент):**
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

**Третий аргумент** — сколько issues на файл (по умолчанию 5). `details MEDIUM MAINTAINABILITY 0` — без кода, только шапки.

**Аналог в REST:** `GET /api/details?…&snippets=10`

---

## 5. Плоский список — `issues` (синоним `list`)

**Пользователь вводит:**
```
issues HIGH ALL
```

**Консоль отвечает:**
```
17 issues (HIGH/ALL)
  9ede9b09-...  HIGH   src/main/java/.../KafkaConsumer.java:42   java:S2259   "NullPointerException" will be thrown...
  1a2b3c4d-...  HIGH   src/main/java/.../Producer.java:78        java:S2589   Boolean expressions should not be gratuitous...
  ...
```

Формат строки: `KEY  SEV  COMPONENT:LINE  RULE  MESSAGE(80)`.

**Аналог в REST:** `GET /api/issues?…`

---

## 6. Только ключи — `keys`

**Пользователь вводит:**
```
keys MEDIUM MAINTAINABILITY
```

**Консоль отвечает:**
```
87 keys (MEDIUM/MAINTAINABILITY)
  9ede9b09-5875-4fbf-8a21-200346c8e415
  1a2b3c4d-5e6f-7890-abcd-ef1234567890
  ...
```

Удобно для копирования ключа → дальше `snippet <KEY>` или `mark <KEY> accept`.

**Аналог в REST:** `GET /api/keys?…`

---

## 7. Один issue со сниппетом — `snippet`

**Пользователь вводит:**
```
snippet 9ede9b09-5875-4fbf-8a21-200346c8e415
```

**Консоль отвечает:**
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

Берёт из снимка, если есть; иначе идёт live в Sonar.

**Аналог в REST:** `GET /api/snippet?…&issueKey=…`

---

## 8. Пометки: `mark` / `cancel` / `cancelall`

### 8.1 `mark <KEY> <transition> [comment...]` — один issue

**Пользователь вводит:**
```
mark 9ede9b09-5875-4fbf-8a21-200346c8e415 accept "Согласовано с командой"
```

**Консоль отвечает:**
```
[mark] 9ede9b09-5875-4fbf-8a21-200346c8e415 -> accept (comment: Согласовано с командой) ...
Батч 1/1 (1): OK
[mark] OK
```

После успеха автоматически выполняется `refresh issue <KEY>` — снимок остаётся консистентным.

**Другие transitions:**
```
mark <KEY> falsepositive "Не является дефектом"
mark <KEY> confirm
mark <KEY> resolve
mark <KEY> wontfix
mark <KEY> reopen                      ← эквивалент cancel <KEY>
```

**Аналог в REST:** `POST /api/mark?key=…&transition=…&dryRun=false`

### 8.2 `mark <transition> [SEV] [QUAL] [--yes]` — массово

Бот/пользователь **сначала** видит dry-run:

**Ввод:**
```
mark accept MEDIUM MAINTAINABILITY
```

**Ответ:**
```
project    = MEDMIS-MISmobile.doctor_api_spec
severity   = MEDIUM
quality    = MAINTAINABILITY
transition = accept
issues     = 87
batches    = 1 (размер 500)

DRY RUN — ничего не изменено.
Чтобы применить: mark accept MEDIUM MAINTAINABILITY --yes
```

**Пользователь повторяет с `--yes`:**
```
mark accept MEDIUM MAINTAINABILITY --yes
```

**Ответ:**
```
Применяю переход...
Батч 1/1 (87): OK
[console] applied to 87 issues in 1 batches
[console] snapshot rebuild started in background ('status' to monitor)
```

> ⚠️ В массовом режиме комментарий не передаётся. Нужен комментарий — используйте одиночный `mark <KEY>`.

**Аналог в REST:** `POST /api/mark?sev=&qual=…&dryRun=false`

### 8.3 `cancel <KEY>` — снять пометку с одного

**Ввод:**
```
cancel 9ede9b09-5875-4fbf-8a21-200346c8e415
```

**Ответ:**
```
[cancel] 9ede9b09-5875-4fbf-8a21-200346c8e415 -> reopen ...
reopen [1/1] 9ede9b09-5875-4fbf-8a21-200346c8e415: OK
[cancel] OK
```

**Аналог в REST:** `POST /api/cancel?key=…&dryRun=false`

### 8.4 `cancelall [SEV] [QUAL] [--yes]` — массовый reopen

**Ввод (dry-run):**
```
cancelall MEDIUM MAINTAINABILITY
```

**Ответ:**
```
project  = MEDMIS-MISmobile.doctor_api_spec
severity = MEDIUM
quality  = MAINTAINABILITY
issues   = 87

DRY RUN — ничего не изменено.
Чтобы применить: cancelall MEDIUM MAINTAINABILITY --yes
```

**Ввод (применить):**
```
cancelall MEDIUM MAINTAINABILITY --yes
```

**Ответ (потоковый, per-issue):**
```
Открываю issues обратно (reopen)...
reopen [1/87] 9ede9b09-...: OK
reopen [2/87] 1a2b3c4d-...: OK
...
[console] reopened 87 / 87 (failed: 0)
[console] snapshot rebuild started in background
```

**Аналог в REST:** `POST /api/cancel?sev=&qual=…&dryRun=false`

---

## 9. Сквозной диалог (несколько команд подряд)

Так выглядит реальный сеанс в консоли:

```
sonar:MEDMIS-MISmobile.doctor_api_spec> status
snapshot:
  status   = READY
  project  = MEDMIS-MISmobile.doctor_api_spec
  issues   = 342
  ...

sonar:...> summary HIGH RELIABILITY
Всего HIGH/RELIABILITY: 17
=== По правилам ===
      9  java:S2259
      5  java:S2589
      3  java:S2201
...

sonar:...> details HIGH RELIABILITY 3
[печатается подробный отчёт с кодом — первые 3 issues на файл]

sonar:...> keys HIGH RELIABILITY
17 keys (HIGH/RELIABILITY)
  9ede9b09-...
  1a2b3c4d-...
  ...

sonar:...> mark 9ede9b09-... accept "Проверено, ложное срабатывание"
[mark] 9ede9b09-... -> accept (comment: Проверено, ложное срабатывание) ...
Батч 1/1 (1): OK
[mark] OK

sonar:...> mark accept HIGH RELIABILITY
project    = ...
issues     = 16
DRY RUN — ничего не изменено.
Чтобы применить: mark accept HIGH RELIABILITY --yes

sonar:...> mark accept HIGH RELIABILITY --yes
Применяю переход...
Батч 1/1 (16): OK
[console] applied to 16 issues in 1 batches
[console] snapshot rebuild started in background ('status' to monitor)

sonar:...> refresh issue 1a2b3c4d-...
[console] refreshing issue 1a2b3c4d-... ...
  removed = true
  reason  = status_changed
  snapshot issues = 341

sonar:...> status
snapshot:
  status   = READY
  issues   = 341
...

sonar:...> quit
[console] shutting down...
[main] shutdown complete
```

Соответствие REST:

| Реплика в консоли | REST-эквивалент |
|---|---|
| `status` | `GET /api/status` |
| `summary HIGH RELIABILITY` | `GET /api/summary?sev=HIGH&qual=RELIABILITY` |
| `details HIGH RELIABILITY 3` | `GET /api/details?…&snippets=3` |
| `keys HIGH RELIABILITY` | `GET /api/keys?…` |
| `mark <KEY> accept "…"` | `POST /api/mark?key=…&transition=accept&comment=…&dryRun=false` |
| `mark accept HIGH RELIABILITY` | `POST /api/mark?…&dryRun=true` |
| `mark accept HIGH RELIABILITY --yes` | `POST /api/mark?…&dryRun=false` |
| `refresh issue <KEY>` | `POST /api/refresh/issue?key=…` |
| `quit` | (останавливает JVM) |

---

## 10. Умные follow-up (что предложить после вывода)

Консоль не бот, сама подсказок не даёт — но по выводу видно, что делать дальше:

| Что вывелось | Естественное продолжение |
|---|---|
| `summary` — топ-файл `KafkaConsumer.java` | `perfile` → `details` |
| `perfile` — длинный файл, много issues | `details SEV QUAL N` — с кодом |
| `details` — «и ещё 35 замечаний» | `details SEV QUAL 35` или `snippet <KEY>` по конкретному |
| `keys` — список ключей | скопировать нужный → `snippet <KEY>` / `mark <KEY>` |
| `mark …` → `DRY RUN` | повторить с `--yes` |
| `mark …` → `snapshot rebuild started` | `status` → дождаться `READY` |
| `refresh issue …` → `removed = true, reason = status_changed` | issue закрыт — больше не появится в снимке |
| `cancelall …` → `failed: 2` + `failed keys: …` | `refresh issue <KEY>` / `snippet <KEY>` по каждому |
| `status` → `BUILDING (150/342)` | подождать, `status` снова |

---

## 11. Шаблоны команд (копировать как есть)

```
status
health
project <KEY>
project clear

session show
session set <path>
session reload
session save <path>

refresh
refresh wait
refresh issue <KEY>

summary
summary <SEV> <QUAL>
matrix <SEV> <QUAL>
perfile <SEV> <QUAL>
details <SEV> <QUAL> <N>
issues <SEV> <QUAL>
keys <SEV> <QUAL>
snippet <KEY>

mark <KEY> <transition> "<comment>"
mark <transition> <SEV> <QUAL>
mark <transition> <SEV> <QUAL> --yes

cancel <KEY>
cancelall <SEV> <QUAL>
cancelall <SEV> <QUAL> --yes

quit
```

Где:
- `<SEV>` = `MEDIUM` \| `HIGH` \| `LOW` \| `INFO` \| `ALL`
- `<QUAL>` = `MAINTAINABILITY` \| `RELIABILITY` \| `SECURITY` \| `ALL`
- `<transition>` = `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen`
- `<KEY>` = `9ede9b09-5875-4fbf-8a21-200346c8e415`
- `<comment>` — в кавычках, если содержит пробелы

---

## 12. Отличия консоли от чат-бота

| | Console | Chat-bot (REST) |
|---|---|---|
| Ввод | stdin процесса | сообщения в мессенджере/веб-виджете |
| Вывод | stdout, человекочитаемый | markdown/JSON в ответе |
| Project по умолчанию | `project <KEY>` (своя переменная) | `?project=` или `defaultProject` |
| Dry-run | без `--yes` | `?dryRun=true` |
| Доступ | локально | по сети, CORS `*` |
| Авторизация | не нужна | Bearer (если включён) |
| Асинхронные операции | команды `refresh …` в фоне видны через `status` | ответ `202` + опрос `/api/status` |

---

## 13. Ошибки и как их читать

| Вывод | Причина | Что делать |
|---|---|---|
| `[console] project не задан…` | пустой консольный project | `project <KEY>` или запуск с `--project=` |
| `snapshot: status = EMPTY` | снимок не собран | `refresh` или `refresh wait` |
| `[console] build already in progress` | уже идёт сборка | `status`, дождаться |
| `[mark] FAIL: reopen failed` | Sonar не дал переход | проверить статус в Sonar, `refresh issue <KEY>` |
| `Unknown command: '…'` | опечатка | `help` |
| `failed keys: …` | часть reopen не удалась | `snippet <KEY>` по каждому — возможно, issue уже закрыт |

---

## 14. Безопасность и «правила приличия» в консоли

- `mark <transition> …` и `cancelall …` **без `--yes`** — dry-run. Привыкайте сначала смотреть план, потом подтверждать.
- Одиночные `mark <KEY>` и `cancel <KEY>` применяются сразу — они точечные, но **всё равно проверяйте KEY** (скопированный из `keys` — самый безопасный способ).
- `session show` печатает cookie/XSRF **в открытом виде** в терминал. Не запускайте `session show` при записи экрана / в общем терминале.
- `quit` останавливает **и консоль, и REST** (процесс выходит). Если REST должен продолжать работать — используйте `Ctrl+C` на запущенном `--no-console` или запускайте демон без консоли.

---

> Полное описание REST-эндпоинтов — в `info.md`.
> Примеры curl-запросов — в `example.md`.
> Примеры чат-диалогов (бот) — в `CHAT-EXAMPLES.md` (или где вы их сохранили).