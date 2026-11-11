# Примеры работы с консольным чатом

Ниже — тот же «каталог диалогов», что и для REST, но для **интерактивной консоли** (`java -jar SonarRestApi.jar`). Пользователь вводит текстовые команды, получает человекочитаемый вывод. Каждая команда дублирует REST-эндпоинт — но без JSON и HTTP.

**Что нового:** разделы 9–11 (`prompt`, `ollama`, `auto`/`stop`). Остальное — как было.

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

Во время AI-обработки приглашение меняется:
```
sonar[auto]:MEDMIS-MISmobile.doctor_api_spec> _
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

## 9. Промпты из файлов — `prompt` 🆕

Рендерит `prompts/<name>.txt`, подставляя `[TAG]`-значения. Файл читается при каждом вызове — **правки применяются сразу**.

### 9.1 `prompt list` — что вообще есть в `prompts/`

**Пользователь вводит:**
```
prompt list
```

**Консоль отвечает:**
```
Доступные шаблоны в /opt/sonar-rest-api/prompts:
  analyze-issue
  auto-resolve
  bulk-status-comment
  status-comment
```

Если каталог пуст — увидите сообщение о том, какие файлы ожидаются (`BootstrapFiles` создаст дефолтные при старте).

### 9.2 `prompt status-comment <KEY> status=accept` — готовый промпт для одного issue

**Пользователь вводит:**
```
prompt status-comment 9ede9b09-5875-4fbf-8a21-200346c8e415 status=accept
```

**Консоль отвечает:**
```
===================================================
 Prompt: status-comment  (12480 chars)
===================================================
Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).
...
(полный текст из prompts/status-comment.txt с подставленными [TAG])
===================================================
```

Промпт можно скопировать и отдать в ChatGPT/Claude/Ollama. Если хочется в файл:
```
prompt status-comment 9ede9b09-... status=accept save=/tmp/prompt.txt
[prompt] сохранено в /tmp/prompt.txt (12480 chars)
```

### 9.3 `prompt bulk-status-comment keys=A,B,C status=accept` — промпт для группы

**Пользователь вводит:**
```
prompt bulk-status-comment keys=9ede9b09-...,1a2b3c4d-... status=accept
```

**Консоль отвечает:**
```
===================================================
 Prompt: bulk-status-comment  (8932 chars)
===================================================
...
```

### 9.4 `prompt bulk-status-comment sev=HIGH qual=RELIABILITY status=accept` — промпт по фильтру

**Пользователь вводит:**
```
prompt bulk-status-comment sev=HIGH qual=RELIABILITY status=accept
```

**Консоль отвечает:** тот же блок, только `[ISSUES_LIST]` заполняется issues из фильтра.

### 9.5 `prompt analyze-issue <KEY>` — анализ без фиксации статуса

**Пользователь вводит:**
```
prompt analyze-issue 9ede9b09-5875-4fbf-8a21-200346c8e415
```

**Консоль отвечает:** полный промпт из `prompts/analyze-issue.txt` (без `[STATUS]`/`[STATUS_LABEL]`).

### 9.6 `prompt auto-resolve <KEY> save=<path>` — промпт для авто-обработки

**Пользователь вводит:**
```
prompt auto-resolve 9ede9b09-5875-4fbf-8a21-200346c8e415 save=/tmp/auto.txt
```

**Консоль отвечает:**
```
[prompt] сохранено в /tmp/auto.txt (8493 chars)
```

Это **ровно тот промпт**, который уходит в Ollama при `auto … --yes`. Удобно посмотреть, что модель «увидит», до запуска обработки.

**Доступные `<name>`:** `status-comment` \| `bulk-status-comment` \| `analyze-issue` \| `auto-resolve`.
**Параметры:** `status=…`, `statusLabel="…"`, `keys=A,B,C`, `sev=ALL`, `qual=ALL`, `save=<path>`.

**Аналог в REST:** `GET /api/prompt/build?…` (для `prompt list` — `GET /api/prompt/list`).

---

## 10. Настройка LLM — `ollama` 🆕

Настройки хранятся в `ollama.properties` рядом с JAR — сохраняются между перезапусками.

### 10.1 `ollama show` — текущие настройки

**Пользователь вводит:**
```
ollama show
```

**Консоль отвечает:**
```
Ollama settings  (ollama.properties)
  url         = http://localhost:11434
  model       = llama3
  timeout     = 300s
  temperature = 0.2

Usage:
  ollama show
  ollama set url <URL>
  ollama set model <MODEL>
  ollama set timeout <SECONDS>
  ollama set temperature <FLOAT>
  ollama test
  ollama reload
```

### 10.2 `ollama set url / model / timeout / temperature`

**Пользователь вводит:**
```
ollama set url http://smwrap.ru:3397
ollama set model qwen2.5-coder:14b
ollama set timeout 300
ollama set temperature 0.2
```

**Консоль отвечает (после каждой команды):**
```
[ollama] saved
```

### 10.3 `ollama test` — проверить соединение

**Пользователь вводит:**
```
ollama test
```

**Консоль отвечает (успех):**
```
Доступные модели:
  - qwen2.5-coder:14b
  - llama3:8b
[ollama] OK: http://smwrap.ru:3397
```

**Консоль отвечает (ошибка):**
```
[ollama] FAIL: Сеть/CORS: Connection refused
```

### 10.4 `ollama reload` — перечитать `ollama.properties` с диска

**Пользователь вводит:**
```
ollama reload
```

**Консоль отвечает:**
```
[ollama] reloaded from ollama.properties
```

Пригодится, если правили файл вручную.

**Аналог в REST / Web:** отдельного REST-эндпоинта нет. В web — кнопка **⚙ Ollama** в шапке.

---

## 11. Массовая AI-обработка — `auto` / `stop` 🆕

Полный цикл для каждого issue: `prompts/auto-resolve.txt` → Ollama → парсинг ответа → `bulk_change` + теги. В web — кнопка **«Добавить автоматически комментарий»**.

### 11.1 `auto [SEV] [QUAL]` — dry-run

**Пользователь вводит:**
```
auto HIGH SECURITY
```

**Консоль отвечает:**
```
project    = MEDMIS-MISmobile.doctor_api_spec
severity   = HIGH
quality    = SECURITY
issues     = 17  (OPEN / REOPENED)
ollama     = http://smwrap.ru:3397  (model: qwen2.5-coder:14b)
prompt     = /opt/sonar/prompts/auto-resolve.txt

DRY RUN — ничего не изменено.
Первые 5 issues:
  9ede9b09-...  HIGH  "NullPointerException" will be thrown...
  1a2b3c4d-...  HIGH  Boolean expressions should not be gratuitous.
  ...
  ... и ещё 12

Чтобы запустить обработку: auto HIGH SECURITY --yes
```

**Если Ollama не настроена:**
```
[auto] Ollama не настроена.
       Установите: ollama set url http://host:port
                   ollama set model llama3
```

**Если уже идёт другая auto:**
```
[auto] уже выполняется. Используйте 'stop' для остановки.
```

### 11.2 `auto [SEV] [QUAL] --yes` — запустить

**Пользователь вводит:**
```
auto HIGH SECURITY --yes
```

**Консоль отвечает:**
```

=== Запуск массовой обработки через Ollama ===

[auto] промпт: /opt/sonar/prompts/auto-resolve.txt
[auto] запущено в фоне. Для остановки введите 'stop'.
```

Приглашение сразу меняется:
```
sonar[auto]:MEDMIS-MISmobile.doctor_api_spec> _
```

**Прогресс идёт в stderr** (одна строка на этап, `stderr` не конфликтует со stdin):
```
[1/17] 9ede9b09-... — подготовка...
[1/17] 9ede9b09-... — Ollama (qwen2.5-coder:14b)...
[1/17] 9ede9b09-... — применяю accept...
[1/17] 9ede9b09-... → accept [require-fix,error-handling]  OK  (12.4s)
[2/17] 1a2b3c4d-... — подготовка...
[2/17] 1a2b3c4d-... → FAIL: Не удалось извлечь статус из ответа LLM (8.1s)
[3/17] ...
```

- `OK` — зелёный (ANSI).
- `FAIL` — красный.

**Во время обработки доступны:** `status`, `project`, `session`, `stop`, любые отчёты (`summary`/`details`/…). Заблокированы: `quit` — нужно сначала `stop`.

### 11.3 `auto key <KEY>` — dry-run одного issue

**Пользователь вводит:**
```
auto key 9ede9b09-5875-4fbf-8a21-200346c8e415
```

**Консоль отвечает:**
```

key        = 9ede9b09-...
status     = OPEN
component  = src/main/java/ru/miacomsoft/KafkaConsumer.java:42
rule       = java:S2259
message    = "NullPointerException" will be thrown ...
ollama     = http://smwrap.ru:3397  (model: qwen2.5-coder:14b)
prompt     = /opt/sonar/prompts/auto-resolve.txt

DRY RUN — добавьте --yes, чтобы применить.
```

### 11.4 `auto key <KEY> --yes` — обработать один issue

**Пользователь вводит:**
```
auto key 9ede9b09-5875-4fbf-8a21-200346c8e415 --yes
```

**Консоль отвечает (синхронно):**
```
[auto] запрос к Ollama...
[auto] статус     = accept
[auto] теги       = [not-require-fix, code-smell]
[auto] комментарий:
Критичность: завышена — ... (текст от LLM)
[auto] OK
```

Снимок сразу обновляется через `refresh issue`.

### 11.5 `stop` — graceful-остановка

**Пользователь вводит:**
```
stop
```

**Консоль отвечает:**
```
[auto] запрошена остановка (текущий issue будет доработан, затем цикл завершится).
```

Текущий issue дорабатывается (или падает по ошибке), после чего цикл завершается. Уже обработанные issues остаются обработанными.

### 11.6 Итоговая статистика после завершения

**Ввод:** никакого — печатается автоматически после завершения auto (или после `stop`):

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

После успеха (не прерванного `stop`/401/сетью) снимок пересобирается в фоне.

### 11.7 Аварийные остановки

**Если истекла сессия SonarQube (HTTP 401):**
```
##################################################
#  ⚠  СЕССИЯ SONARQUBE ИСТЕКЛА (HTTP 401)        #
##################################################
Обработано 15 из 17 issues, дальше — стоп.

Что делать:
  1. Откройте SonarQube в браузере (sonar.example.com)
  2. DevTools → Network → любой API-запрос
  3. Скопируйте заголовки Cookie и X-XSRF-TOKEN в session.txt
  4. session reload
  5. auto HIGH SECURITY --yes — продолжит с того же места
     (обработанные уже не OPEN → fetchOpen их не вернёт)
```

**Если Ollama / сеть недоступны (3 подряд ошибки):**
```
##################################################
#  ⚠  OLLAMA / СЕТЬ НЕДОСТУПНЫ                      #
##################################################
Причина: 3 подряд сетевых ошибок: Connection refused
Проверьте: ollama test
```

**Аналог в REST:** отдельного REST-эндпоинта нет. Из REST можно собрать вручную (см. `example.md` §6.12).

---

## 12. Сквозной диалог (несколько команд подряд)

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

sonar:...> prompt status-comment 9ede9b09-... status=accept
===================================================
 Prompt: status-comment  (12480 chars)
===================================================
Ты — опытный разработчик и эксперт по SonarQube.
... (полный текст из prompts/status-comment.txt)
===================================================

sonar:...> ollama show
Ollama settings  (ollama.properties)
  url         = http://smwrap.ru:3397
  model       = qwen2.5-coder:14b
  ...

sonar:...> auto HIGH SECURITY
project    = ...
issues     = 17
DRY RUN — ничего не изменено.
Чтобы запустить обработку: auto HIGH SECURITY --yes

sonar:...> auto HIGH SECURITY --yes
=== Запуск массовой обработки через Ollama ===
[auto] промпт: /opt/sonar/prompts/auto-resolve.txt
[auto] запущено в фоне. Для остановки введите 'stop'.
sonar[auto]:MEDMIS-MISmobile.doctor_api_spec>

[1/17] 9ede9b09-... — подготовка...
[1/17] 9ede9b09-... — Ollama (qwen2.5-coder:14b)...
[1/17] 9ede9b09-... — применяю accept...
[1/17] 9ede9b09-... → accept [require-fix,error-handling]  OK  (12.4s)
[2/17] 1a2b3c4d-... — подготовка...
...

=== Результат ===
Всего:         17
Успешно:       17
Ошибок:        0
Время:         203.1 сек
По статусам:   {accept=12, falsepositive=3, confirm=2}
По фикс-тегам: {require-fix=14, not-require-fix=3}
[auto] Обновляю снимок в фоне (status — прогресс)

sonar:...> status
snapshot:
  status   = BUILDING
  phase    = fetching_snippets (150/342)
...

sonar:...> mark <KEY> accept "Проверено, ложное срабатывание"
[mark] 9ede9b09-... -> accept (comment: Проверено, ложное срабатывание) ...
Батч 1/1 (1): OK
[mark] OK

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
| `prompt list` | `GET /api/prompt/list` |
| `prompt status-comment <KEY> …` | `GET /api/prompt/build?name=status-comment&issueKey=…` |
| `prompt bulk-status-comment keys=…` | `GET /api/prompt/build?name=bulk-status-comment&keys=…` |
| `prompt analyze-issue <KEY>` | `GET /api/prompt/build?name=analyze-issue&issueKey=…` |
| `prompt auto-resolve <KEY>` | `GET /api/prompt/build?name=auto-resolve&issueKey=…` |
| `ollama show / set / test / reload` | (нет REST) — только консоль / web `⚙ Ollama` |
| `auto …` / `auto key …` | (нет REST) — только консоль / web «Добавить автоматически комментарий» |
| `stop` | (нет REST) — только консоль / web «Остановить» |
| `mark <KEY> accept "…"` | `POST /api/mark?key=…&transition=accept&comment=…&dryRun=false` |
| `mark accept HIGH RELIABILITY` | `POST /api/mark?…&dryRun=true` |
| `mark accept HIGH RELIABILITY --yes` | `POST /api/mark?…&dryRun=false` |
| `refresh issue <KEY>` | `POST /api/refresh/issue?key=…` |
| `quit` | (останавливает JVM) |

---

## 13. Умные follow-up (что предложить после вывода)

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
| `prompt list` — есть `auto-resolve` | `auto-resolve <KEY> save=/tmp/p.txt` → посмотреть, что уйдёт в LLM |
| `prompt status-comment …` — готовый текст | скопировать → ChatGPT/Claude/Ollama; или `save=/tmp/p.txt` |
| `[auto] Ollama не настроена` | `ollama set url …` → `ollama set model …` → `ollama test` |
| `auto <SEV> <QUAL>` → `DRY RUN` | `auto <SEV> <QUAL> --yes` |
| `auto …` → `sonar[auto]:…` | следить за stderr, `stop` — если передумали |
| `auto …` → `SESSION EXPIRED` | `session set new.txt` → `session reload` → `auto SEV QUAL --yes` (продолжит) |
| `auto …` → `OLLAMA НЕДОСТУПНЫ` | `ollama test` → проверить сеть/модель → `auto SEV QUAL --yes` |
| `auto …` → `Failed keys: …` | `auto key <KEY> --yes` по каждому (точечная повторная обработка) |

---

## 14. Шаблоны команд (копировать как есть)

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

prompt list
prompt status-comment <KEY> status=accept
prompt status-comment <KEY> status=accept save=<path>
prompt bulk-status-comment keys=<KEY1>,<KEY2> status=accept
prompt bulk-status-comment sev=<SEV> qual=<QUAL> status=accept
prompt analyze-issue <KEY>
prompt auto-resolve <KEY>
prompt auto-resolve <KEY> save=<path>

ollama show
ollama set url <URL>
ollama set model <MODEL>
ollama set timeout <SECONDS>
ollama set temperature <FLOAT>
ollama test
ollama reload

auto <SEV> <QUAL>
auto <SEV> <QUAL> --yes
auto key <KEY>
auto key <KEY> --yes
stop

quit
```

Где:
- `<SEV>` = `MEDIUM` \| `HIGH` \| `LOW` \| `INFO` \| `ALL`
- `<QUAL>` = `MAINTAINABILITY` \| `RELIABILITY` \| `SECURITY` \| `ALL`
- `<transition>` = `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen`
- `<name>` (для `prompt`) = `status-comment` \| `bulk-status-comment` \| `analyze-issue` \| `auto-resolve`
- `<status>` (для `prompt`) = `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix`
- `<KEY>` = `9ede9b09-5875-4fbf-8a21-200346c8e415`
- `<comment>` — в кавычках, если содержит пробелы

---

## 15. Отличия консоли от чат-бота

| | Console | Chat-bot (REST) | Web |
|---|---|---|---|
| Ввод | stdin процесса | сообщения в мессенджере | клики в браузере |
| Вывод | stdout, человекочитаемый | markdown/JSON в ответе | интерактивные вкладки |
| Project по умолчанию | `project <KEY>` (своя переменная) | `?project=` или `defaultProject` | поле в шапке |
| Dry-run | без `--yes` | `?dryRun=true` | кнопка «Применить» |
| Доступ | локально | по сети, CORS `*` | по сети, CORS `*` |
| Авторизация | не нужна | Bearer (если включён) | не нужна |
| Асинхронные операции | команды `refresh …` / `auto …` в фоне видны через `status` / stderr | ответ `202` + опрос `/api/status` | прогресс-бар в шапке / модалке |
| Промпты | `prompt list` / `prompt <name> …` | `GET /api/prompt/build`, `/api/prompt/list` | кнопка «Сгенерировать промпт» |
| AI-обработка | `auto … --yes` + `stop` | (нет REST) — вручную через `prompt/build` + Ollama + `mark` | «Добавить автоматически комментарий» |
| Настройка Ollama | `ollama set …` | (нет REST) | ⚙ Ollama |

---

## 16. Ошибки и как их читать

| Вывод | Причина | Что делать |
|---|---|---|
| `[console] project не задан…` | пустой консольный project | `project <KEY>` или запуск с `--project=` |
| `snapshot: status = EMPTY` | снимок не собран | `refresh` или `refresh wait` |
| `[console] build already in progress` | уже идёт сборка | `status`, дождаться |
| `[mark] FAIL: reopen failed` | Sonar не дал переход | проверить статус в Sonar, `refresh issue <KEY>` |
| `Unknown command: '…'` | опечатка | `help` |
| `failed keys: …` | часть reopen не удалась | `snippet <KEY>` по каждому — возможно, issue уже закрыт |
| `[prompt] шаблон не найден: <name>` | нет `prompts/<name>.txt` | `prompt list` → посмотреть доступные; создать файл или проверить `-Dsonar.prompts` |
| `[prompt] промпт пуст — проверьте файл …` | файл существует, но пустой | заполнить файл (см. `Defaults.java` — там дефолтный контент) |
| `[auto] Ollama не настроена` | пустой `url` или `model` | `ollama set url …` + `ollama set model …` |
| `[ollama] FAIL: Сеть/CORS: …` | Ollama недоступна | проверить `url`, что Ollama запущена; `OLLAMA_ORIGINS='*'` |
| `[auto] уже выполняется` | ещё одна `auto` в фоне | дождаться или `stop` |
| `[auto] FAIL: Не удалось извлечь статус…` | LLM вернул ответ без `Статус:` | проверить `prompts/auto-resolve.txt`, `prompt auto-resolve <KEY>` — посмотреть, что уходит |
| `[auto] остановка запрошена пользователем` | сработал `stop` | это нормально; снимок будет обновлён |
| `[auto] ABORT: …` | сеть или 401 | см. блоки «СЕССИЯ ИСТЕКЛА» / «OLLAMA НЕДОСТУПНЫ» в §11.7 |

---

## 17. Безопасность и «правила приличия» в консоли

- `mark <transition> …`, `cancelall …`, `auto …` **без `--yes`** — dry-run. Привыкайте сначала смотреть план, потом подтверждать.
- Одиночные `mark <KEY>`, `cancel <KEY>`, `auto key <KEY> --yes` применяются сразу — они точечные, но **всё равно проверяйте KEY** (скопированный из `keys` — самый безопасный способ).
- `session show` печатает cookie/XSRF **в открытом виде** в терминал. Не запускайте `session show` при записи экрана / в общем терминале.
- `prompt …` печатает промпт, включая **фрагмент кода из исходников**. Если код под NDA — используйте `save=/tmp/p.txt` и работайте с файлом.
- `auto …` шлёт содержимое кода во внешний сервис Ollama. Если Ollama на внешнем хосте — код покидает ваш периметр. Убедитесь, что это допустимо.
- `quit` останавливает **и консоль, и REST** (процесс выходит). Если REST должен продолжать работать — используйте `Ctrl+C` на запущенном `--no-console` или запускайте демон без консоли.
- `quit` **при активной `auto` отклоняется** — сначала `stop`. Это защита: kill процесса оставит SonarQube в промежуточном состоянии (часть issues обработана, часть нет).

---

> Полное описание REST-эндпоинтов — в `Info.md`.
> Примеры curl-запросов — в `example.md`.
> Полное руководство по консоли — в `console.md`.
> Краткая шпаргалка по консоли — в `CONSOLE.md`.
```

---

## Что изменено относительно исходного `InfoConsole.md`

| Раздел | Изменение |
|---|---|
| Шапка | Добавлена ссылка на новые разделы (`prompt`, `ollama`, `auto`/`stop`) |
| «Как это устроено» | Добавлено приглашение `sonar[auto]:PROJECT>` — виден режим auto |
| **§9 `prompt`** 🆕 | Полностью новый раздел: `list`, `status-comment`, `bulk-status-comment`, `analyze-issue`, `auto-resolve`, `save=<path>` |
| **§10 `ollama`** 🆕 | Полностью новый раздел: `show`, `set`, `test`, `reload` |
| **§11 `auto`/`stop`** 🆕 | Полностью новый раздел: dry-run, боевой запуск, `key <KEY>`, `stop`, итоговая статистика, аварийные остановки |
| **§12 «Сквозной диалог»** (быв. §9) | Расширен: показан `prompt`, `ollama`, `auto … --yes`, прогресс в stderr, итоговая статистика. Таблица соответствия REST — дополнена новыми строками |
| **§13 «Умные follow-up»** (быв. §10) | Добавлены строки про `prompt`, `ollama`, `auto`, `stop`, ошибки auto (session expired / сеть / failed keys) |
| **§14 «Шаблоны команд»** (быв. §11) | Добавлены блоки `prompt …`, `ollama …`, `auto …`, `stop` |
| **§15 «Отличия консоли от чат-бота»** (быв. §12) | Добавлен столбец **Web**; добавлены строки про `prompt`, `auto`, `ollama` |
| **§16 «Ошибки»** (быв. §13) | Расширено: `[prompt] шаблон не найден`, `[auto] Ollama не настроена`, `[auto] ABORT`, `[auto] FAIL: Не удалось извлечь статус` и др. |
| **§17 «Безопасность»** (быв. §14) | Добавлены: `prompt …` печатает код в терминал (NDA), `auto` шлёт код в Ollama (внешний сервис), `quit` при активной `auto` отклоняется |
| Финальные ссылки | Добавлены `console.md` и `CONSOLE.md` |