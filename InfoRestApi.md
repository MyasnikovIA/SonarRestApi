# Примеры работы с чатом

Ниже — «каталог диалогов»: что пользователь пишет в чат и какой эндпоинт под этим стоит. Подходит как для реального бота (Telegram/Slack/веб-виджет), так и для промптов к AI-ассистенту, который проксирует REST API.

---

## Как это устроено

```
[Пользователь]  ──сообщение──▶  [Чат-бот / AI]  ──HTTP──▶  http://www.smwrap.ru:6060/api/...
                                       ▲
                                       └── JSON-ответ  ──▶  структурированный ответ в чат
```

Все эндпоинты возвращают JSON (`Content-Type: application/json`), CORS открыт (`*`) — можно звать прямо из веб-виджета. `sev`/`qual` регистронезависимы; `project` можно опустить, если задан `--project` при старте сервера.

---

## 1. Сводка — `/api/summary`

**Пользователь:**
> Сколько MEDIUM/MAINTAINABILITY issues в проекте MEDMIS-MISmobile.doctor_api_spec? Покажи топ правил и файлов.

**Бот вызывает:**
```
GET /api/summary?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY
```

**Ответ в чат:**
> 📊 **MEDMIS-MISmobile.doctor_api_spec** — MEDIUM / MAINTAINABILITY
> Всего issues: **87**
>
> **Топ правил:**
> - `java:S2259` — 9
> - `java:S2589` — 5
> - `java:S2201` — 3
>
> **Топ файлов:**
> - `src/main/java/ru/miacomsoft/KafkaConsumer.java` — 4
> - `src/main/java/ru/miacomsoft/Producer.java` — 3
>
> *source: snapshot, builtAt: 2025-11-07T12:34:56Z*

**Варианты вопросов → тот же эндпоинт:**
- «Сводка по HIGH/RELIABILITY»
- «Сколько всего issues во всём проекте?» → `sev=ALL&qual=ALL`
- «Дай общую картину по проекту»

---

## 2. Матрица file × rule — `/api/matrix`

**Пользователь:**
> Покажи, где какие правила сконцентрированы — матрица файл × правило по всему проекту.

**Бот вызывает:**
```
GET /api/matrix?project=MEDMIS-MISmobile.doctor_api_spec&sev=ALL&qual=ALL
```

**Ответ в чат (в виде таблицы/markdown):**

> 🔲 **Матрица file × rule** — всего 342 issues
>
> | Файл | S2259 | S2589 | S2201 | Σ |
> |---|---:|---:|---:|---:|
> | `KafkaConsumer.java` | 4 | 2 | 0 | 6 |
> | `Producer.java` | 1 | 0 | 2 | 3 |
> | `Controller.java` | 0 | 1 | 1 | 2 |
>
> Видно, что `KafkaConsumer.java` — самый «горячий» файл.

**Подсказка бота:** «Скажи *"детали по KafkaConsumer"* — покажу конкретные строки с кодом».

---

## 3. Замечания по файлам — `/api/perfile`

**Пользователь:**
> Покажи замечания по файлам — только MEDIUM/RELIABILITY.

**Бот вызывает:**
```
GET /api/perfile?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=RELIABILITY
```

**Ответ в чат:**

> 📁 **Замечания по файлам** — MEDIUM / RELIABILITY, 2 файла, 17 issues
>
> **`src/main/java/ru/miacomsoft/KafkaConsumer.java`** — 9:
> - L42 `java:S2259` — *"NullPointerException" will be thrown...*
> - L78 `java:S2589` — *Boolean expressions should not be gratuitous...*
> - L120 `java:S2201` — *Return values should not be ignored...*
> - …
>
> **`src/main/java/ru/miacomsoft/Producer.java`** — 8:
> - L15 `java:S2589` — …
> - …

**Естественное продолжение:** «с кодом вокруг строки покажи» → `/api/details` или `/api/snippet`.

---

## 4. Подробно с кодом — `/api/details`

**Пользователь:**
> Дай подробный отчёт по MEDIUM/MAINTAINABILITY с фрагментами кода — по 10 issue на файл.

**Бот вызывает:**
```
GET /api/details?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY&snippets=10
```

**Ответ в чат (фрагмент):**

> 📄 **`src/main/java/ru/miacomsoft/KafkaConsumer.java`** — 45 замечаний, показано 10
>
> **#1, строка 42** — `java:S2259`
> > "NullPointerException" will be thrown when invoking method "get()".
> > Правило: *Null pointers should not be dereferenced*
> > Серьёзность: MEDIUM
> ```java
>   40 | public void handle(Message m) {
>   41 |     if (m.getPayload() != null) {
>   42 |         process(m.getPayload().get());   // <- NPE
>   43 |     }
>   44 | }
> ```
>
> **#2, строка 78** — `java:S2589` …
>
> ℹ️ *Ещё 35 замечаний в этом файле. Скажи «дальше по KafkaConsumer» — покажу следующую порцию.*

**Умное поведение бота:** `hasMore: true, remaining: 35` в JSON → бот предлагает «дальше», а не обрывает.

---

## 5. Плоский список — `/api/issues`

**Пользователь:**
> Дай список всех HIGH issues по проекту.

**Бот вызывает:**
```
GET /api/issues?project=MEDMIS-MISmobile.doctor_api_spec&sev=HIGH&qual=ALL
```

**Ответ в чат:**

> ⚠️ **17 HIGH issues** (все quality)
>
> | Key | Sev | Файл:строка | Rule | Сообщение |
> |---|---|---|---|---|
> | `9ede9b09-…` | HIGH | `KafkaConsumer.java:42` | `java:S2259` | "NullPointerException" will be thrown… |
> | `1a2b3c4d-…` | HIGH | `Producer.java:78` | `java:S2589` | Boolean expressions should not be gratuitous… |
> | … | | | | |
>
> Дальше можно: *«пометь всё как accept»* / *«покажи код по 9ede9b09»* / *«сохрани в CSV»*.

**Почему это удобно в чате:** из этого списка пользователь естественно переходит к `mark`, `snippet` или фильтрации по конкретному `rule`/`component`.

---

## 6. Только ключи — `/api/keys`

**Пользователь:**
> Дай ключи MEDIUM/MAINTAINABILITY — хочу потом пометить.

**Бот вызывает:**
```
GET /api/keys?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY
```

**Ответ в чат:**

> 🔑 **87 ключей** — MEDIUM / MAINTAINABILITY
>
> ```
> 9ede9b09-5875-4fbf-8a21-200346c8e415
> 1a2b3c4d-5e6f-7890-abcd-ef1234567890
> … (ещё 85)
> ```
>
> Что сделать?
> - *«пометь все accept»* → бот вызовет `POST /api/mark?…&transition=accept&dryRun=true` (сначала dry-run!)
> - *«покажи код по первому»* → `POST /api/snippet?issueKey=…`
> - *«скачать полный список»* → отдаст файл `.txt`

**Ключевой сценарий:** `keys` → dry-run → `--yes` в следующий ход. Это безопасный диалог.

---

## 7. Один issue со сниппетом — `/api/snippet`

**Пользователь:**
> Покажи код под issue 9ede9b09-5875-4fbf-8a21-200346c8e415.

**Бот вызывает:**
```
GET /api/snippet?project=MEDMIS-MISmobile.doctor_api_spec&issueKey=9ede9b09-5875-4fbf-8a21-200346c8e415
```

**Ответ в чат:**

> 🐛 **`9ede9b09-5875-4fbf-8a21-200346c8e415`**
> > "NullPointerException" will be thrown when invoking method "get()".
>
> **Правило:** Null pointers should not be dereferenced (`java:S2259`)
> **Файл:** `src/main/java/ru/miacomsoft/KafkaConsumer.java` **·** L42
> **Статус:** OPEN **·** **Severity:** MEDIUM
>
> ```java
>   40 | public void handle(Message m) {
>   41 |     if (m.getPayload() != null) {
>   42 |         process(m.getPayload().get());   // <- NPE
>   43 |     }
>   44 | }
> ```
>
> Действия: *«пометь как accept с комментарием…»* / *«это ложное срабатывание»* / *«refresh этот issue»*.

---

## 8. Сквозной диалог (несколько шагов подряд)

Так выглядит реальный сеанс в чате:

```
👤 Сколько MEDIUM/MAINTAINABILITY в проекте?
🤖 Всего 87. Топ-файл — KafkaConsumer.java (4). Топ-правило — java:S2259 (9).

👤 Покажи по нему код.
🤖 [details по KafkaConsumer.java] — 45 issues, показываю 10. Первое — L42:
    if (m.getPayload() != null) { process(m.getPayload().get()); }
    java:S2259, MEDIUM. Ещё 35 — сказать «дальше»?

👤 Дай ключи всех 87, хочу пометить как accept.
🤖 [keys] 87 ключей. Запускаю dry-run для accept MEDIUM/MAINTAINABILITY…

👤 Да, применяй.
🤖 Применяю… Батч 1/1 (87): OK. Стартую фоновый пересбор снимка.
    Через ~30с напиши «status», будет READY.
```

Каждый шаг — ровно один REST-вызов:

| Реплика | Endpoint |
|---|---|
| «сколько MEDIUM…» | `GET /api/summary` |
| «покажи по нему код» | `GET /api/details` (или `perfile` → `snippet`) |
| «дай ключи» | `GET /api/keys` |
| «применяй accept» | `POST /api/mark?…&dryRun=true` → затем `dryRun=false` |
| «status» | `GET /api/status` |

---

## 9. Умные follow-up (бот может предложить сам)

Когда бот получает JSON, он видит подсказки:

| Поле в ответе | Куда ведёт |
|---|---|
| `hasMore: true, remaining: N` | «показать ещё N?» → `details` с `snippets=` дальше |
| `failedKeys: [...]` | «N не удалось пометить — показать?» → `snippet` по каждому |
| `source: "live"` + `snapshotReady: false` | «снимок не построен, данные медленные — пересобрать?» → `POST /api/refresh` |
| `snapshot.phase: "BUILDING"` | «сборка идёт (150/342), повторить через 10с?» → `status` |
| `reason: "status_changed"` | «issue уже закрыт в Sonar, я убрал его из снимка» |
| `issue.transitions: ["accept", "falsepositive", …]` | «доступные действия для этой задачи: accept / falsepositive / …» |

Это делает диалог естественным: пользователь не помнит эндпоинтов — он просто уточняет.

---

## 10. Шаблоны промптов (копировать как есть)

```
Сводка по {PROJECT} {SEV}/{QUAL}
Матрица file×rule по {PROJECT}
Замечания по файлам {PROJECT} {SEV}/{QUAL}
Подробно с кодом {PROJECT} {SEV}/{QUAL}, {N} на файл
Список issues {PROJECT} {SEV}/{QUAL}
Ключи {PROJECT} {SEV}/{QUAL}
Код по issue {KEY} в {PROJECT}

Пометь {KEY} как {transition} с комментарием "{text}"
Пометь всё {transition} в {PROJECT} {SEV}/{QUAL}   ← dry-run
Применяю   ← подтверждение dry-run

Откатить всё в {PROJECT} {SEV}/{QUAL}   ← cancelall dry-run
Да, откатить   ← --yes
```

Где:
- `{PROJECT}` = `MEDMIS-MISmobile.doctor_api_spec`
- `{SEV}` = `MEDIUM` \| `HIGH` \| `LOW` \| `INFO` \| `ALL`
- `{QUAL}` = `MAINTAINABILITY` \| `RELIABILITY` \| `SECURITY` \| `ALL`
- `{transition}` = `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen`
- `{KEY}` = `9ede9b09-5875-4fbf-8a21-200346c8e415`

---

## 11. Ошибки и как их обрабатывать в чате

| HTTP | Что значит | Сообщение в чат |
|---|---|---|
| `400` | неверные параметры | «Не понял фильтр, `sev` должен быть MEDIUM/HIGH/LOW/INFO/ALL» |
| `404` | issue не найден | «Issue `…` в Sonar не найден — возможно, уже закрыт» |
| `503` | сессия не загружена | «Сессия не загружена — пришли cookie/XSRF из браузера» |
| `500` | Sonar вернул ошибку | «Sonar ответил ошибкой: {текст}. Проверить сессию?» |

Все ошибки приходят в едином виде:
```json
{ "error": "...", "status": 503 }
```
Бот может перевести это в человеческий текст, не разбирая каждый эндпоинт отдельно.

---

## 12. Безопасность (напоминание для бота)

- `POST /api/mark` и `POST /api/cancel` **по умолчанию** `dryRun=true` → случайно ничего не сломается.
- Бот **обязан** показать dry-run-план и попросить явное подтверждение перед `dryRun=false`.
- Если сервер запущен с `-Dsonar.api.token=…` — добавь `Authorization: Bearer …` (и не забудь, что CORS `Allow-Headers` нужно расширить — см. `Access-Control-Allow-Headers` в `BaseHandler`).
- `/api/session` отдаёт cookie/XSRF в открытом виде — **никогда** не показывай его содержимое в публичном чате.