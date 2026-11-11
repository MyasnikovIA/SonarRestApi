# Примеры работы с чатом

Ниже — «каталог диалогов»: что пользователь пишет в чат и какой эндпоинт под этим стоит. Подходит как для реального бота (Telegram/Slack/веб-виджет), так и для промптов к AI-ассистенту, который проксирует REST API.

**Что нового:** разделы 8–16 — `/accepted`, `/open_issues`, `/changelog`, `/tags`, `/set_tags`, `/measures`, `/metadata`, `/prompt/list`, `/prompt/build`. В существующих `/issues` и `/snippet` появились поля `comments`, `tags`, `impacts` (используются ботом для отображения истории и группировки).

---

## Как это устроено

```
[Пользователь]  ──сообщение──▶  [Чат-бот / AI]  ──HTTP──▶  http://127.0.0.1:6060/api/...
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

**Что нового в JSON:** каждый issue теперь содержит `comments: [...]` (полная история комментариев), `impacts: [...]` и `tags: [...]` — бот может показывать историю прямо в списке или подсвечивать теги.

**Особое значение:** `qual=SECURITY_HOTSPOT` — переключает на hotspot-API Sonar, возвращает `TO_REVIEW`/`REVIEWED` issues (поля `status`/`issueStatus`).

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
> **🏷️ Теги:** `error-handling`, `require-fix`
>
> ```java
>   40 | public void handle(Message m) {
>   41 |     if (m.getPayload() != null) {
>   42 |         process(m.getPayload().get());   // <- NPE
>   43 |     }
>   44 | }
> ```
>
> 💬 **Комментарии (2):**
> - **ivanov** (2025-10-15 15:28) — *Переоткрыл, воспроизводится на staging*
> - **petrov** (2025-10-14 09:10) — *Согласовано с командой*
>
> Действия: *«пометь как accept с комментарием…»* / *«это ложное срабатывание»* / *«refresh этот issue»* / *«покажи историю изменений»*.

**Что нового:** ответ теперь включает `issue.tags`, `issue.comments` (полный список) — бот может показать их без отдельного вызова `/api/changelog` (если нужны только комментарии).

---

## 8. Обработанные issues — `/api/accepted` 🆕

**Пользователь:**
> Покажи всё, что уже обработано — с разбивкой по критичности, типам и резолюциям.

**Бот вызывает:**
```
GET /api/accepted?project=MEDMIS-MISmobile.doctor_api_spec&sev=ALL&qual=ALL&facets=true
```

**Ответ в чат:**

> ✅ **Обработанные** — MEDMIS-MISmobile.doctor_api_spec
> Всего: **95**
>
> **По критичности:** CRITICAL — 52, MAJOR — 35, MINOR — 8
> **По типу:** CODE_SMELL — 95
> **По резолюции:** WONTFIX — 95
>
> Топ-файл — `src/Api/AuthApi.php` (14 issues)
>
> *Facets считаются локально — 1 запрос, без дополнительных обращений к SonarQube.*

**Варианты вопросов → тот же эндпоинт:**
- «Сколько всего принято / отложено / ложно?» → `facets=true` даёт `resolutions`
- «Что приняли как wontfix по MEDIUM/MAINTAINABILITY?» → добавить `sev=MEDIUM&qual=MAINTAINABILITY`
- «Покажи все обработанные» → `sev=ALL&qual=ALL`

**Что нового в JSON:** `facets` содержит `severities`, `types`, `resolutions`, `impactSoftwareQualities`, `impactSeverities` — бот может предложить facet-фильтры прямо в чате.

---

## 9. Только необработанные — `/api/open_issues` 🆕

**Пользователь:**
> Дай все необработанные issues с кодом — чтобы можно было сразу показать пользователю.

**Бот вызывает:**
```
GET /api/open_issues?project=MEDMIS-MISmobile.doctor_api_spec&sev=ALL&qual=ALL&snippets=true
```

**Ответ в чат:**

> 🔴 **OPEN / REOPENED** — 342 issues
> *(сниппеты включены — у каждого issue есть кусок кода)*
>
> **#1** `9ede9b09-…` · HIGH · `KafkaConsumer.java:42` · `java:S2259`
> ```java
>   42 | process(m.getPayload().get());   // <- NPE
> ```
>
> **#2** `1a2b3c4d-…` · HIGH · `Producer.java:78` · `java:S2589`
> …

**Отличие от `/api/issues`:**
- Возвращает только `OPEN` и `REOPENED` (без `ACCEPTED`/`RESOLVED`);
- Автоматически подтягивает `ruleName` и `snippet` для каждого issue;
- Оптимизирован для агента, который будет эти issues обрабатывать (не нужно N запросов `/api/snippet`).

**Вариант:** `snippets=false` — только список без кода (быстрее для больших проектов).

---

## 10. История изменений issue — `/api/changelog` 🆕

**Пользователь:**
> Покажи историю изменений issue 9ede9b09-….

**Бот вызывает:**
```
GET /api/changelog?project=MEDMIS-MISmobile.doctor_api_spec&issueKey=9ede9b09-5875-4fbf-8a21-200346c8e415
```

**Ответ в чат:**

> 📜 **История изменений** — `9ede9b09-…`
>
> **15.01.2024 14:32** — 🔄 ivanov
> - Статус: CONFIRMED → **REOPENED**
>
> **15.01.2024 14:28** — 🔄 petrov
> - Статус: OPEN → **CONFIRMED**
>
> **15.01.2024 14:20** — ✏️ ivanov
> - Теги: code-smell → **error-handling, require-fix**

**Комбинирование с `/api/snippet`:** бот может сделать оба запроса параллельно и показать единый таймлайн (изменения + комментарии) — именно так это сделано в web-вкладке «Snippet».

**Типы `diffs.key`:** `status`, `resolution`, `assignee`, `tags`, `severity`, `type`, `file`, `rule`, `comment`.

---

## 11. Все теги — `/api/tags` 🆕

**Пользователь:**
> Какие теги вообще есть в SonarQube? Хочу понять, что можно ставить.

**Бот вызывает:**
```
GET /api/tags
GET /api/tags?q=require          # поиск по подстроке
```

**Ответ в чат:**

> 🏷️ **Доступные теги (92)**
>
> `accessibility`, `android`, `api-design`, `architecture`, `code-smell`, `cwe`, `error-handling`, `generated-code`, `not-require-fix`, `phpunit`, `require-fix`, `security`, … *(ещё 80)*
>
> По запросу `require`:
> - `require-fix`
> - `not-require-fix`

**Применение:** бот использует этот список для валидации тегов, которые предлагает LLM, и для подсветки «правильных» тегов при редактировании.

---

## 12. Установить теги — `/api/set_tags` 🆕

**Пользователь:**
> Поставь теги `require-fix` и `error-handling` на issue 9ede9b09-….

**Бот вызывает:**
```
POST /api/set_tags?project=MEDMIS-MISmobile.doctor_api_spec&issue=9ede9b09-5875-4fbf-8a21-200346c8e415&tags=require-fix,error-handling
```

**Ответ в чат:**

> ✅ Теги обновлены у **1** issue
>
> `9ede9b09-…` → `require-fix`, `error-handling`

**Массовая установка (несколько issues):**
```
POST /api/set_tags?…&keys=A,B,C&tags=require-fix,error-handling
```
Ответ: `{ "success": true, "count": 3, "tags": "require-fix,error-handling" }`.
Внутри — последовательные запросы к SonarQube (API `issues/set_tags` принимает только один `issue`).

**Снять все теги:**
```
POST /api/set_tags?…&issue=KEY&tags=
```

**Важно:** теги валидируются SonarQube (`^[a-z0-9\+\#\-\.]+$`). Невалидные (с пробелами, кириллицей) — вызовут 500 от Sonar; бот должен фильтровать до отправки (в web-редакторе это делает `isValidTag()`).

---

## 13. Метрики проекта — `/api/measures` 🆕

**Пользователь:**
> Какие метрики у проекта? Quality Gate, покрытие, дубликаты, рейтинги.

**Бот вызывает:**
```
GET /api/measures?project=MEDMIS-MISmobile.doctor_api_spec
```

**Ответ в чат:**

> 📊 **Метрики проекта** — MEDMIS-MISmobile.doctor_api_spec
>
> **Quality Gate:** ❌ ERROR
>
> | Категория | Issues | Рейтинг |
> |---|---:|---|
> | 🛡 Безопасность | 3 | **A** |
> | 🐞 Надёжность | 17 | **C** |
> | 🔧 Сопровождаемость | 322 | **B** |
>
> **Всего violations:** 342 (bugs: 17, vulnerabilities: 3, code smells: 322)
> **Accepted:** 95 (из них high impact: 52)
> **Coverage:** 68.4%
> **Duplicated lines:** 3.2%
> **NCLOC:** 32 100 (Java 20 000, PHP 12 100)

**Варианты вопросов → тот же эндпоинт:**
- «Какие рейтинги?» → `maintainabilityRating`, `reliabilityRating`, `securityRating`
- «Сколько строк кода?» → `ncloc`, `nclocByLanguage`
- «Покрытие?» → `coverage`
- «Проходит ли Quality Gate?» → `alertStatus` (OK / ERROR / WARN)

---

## 14. Метаданные проекта — `/api/metadata` 🆕

**Пользователь:**
> Какие языки в проекте? Есть ли ветки/PR?

**Бот вызывает:**
```
GET /api/metadata?project=MEDMIS-MISmobile.doctor_api_spec
```

**Ответ в чат:**

> ℹ️ **Метаданные** — MEDMIS-MISmobile.doctor_api_spec
>
> **Языки:** Java, PHP
> **Ветки:**
> - `main` *(главная, BRANCH)*
> - `develop` *(BRANCH)*
> **Pull requests:** нет
> **Объявления:** *(настройки `sonar.announcement.*` не заданы)*

**Что внутри:** 4 независимых источника — `/api/languages/list`, `/api/project_branches/list`, `/api/project_pull_requests/list`, `/api/settings/values`. Каждый можно отключить параметрами `languages=false`, `branches=false`, `pullRequests=false`, `settings=false`.

---

## 15. Список шаблонов промптов — `/api/prompt/list` 🆕

**Пользователь:**
> Какие вообще есть промпты? Что можно отдать в LLM?

**Бот вызывает:**
```
GET /api/prompt/list
```

**Ответ в чат:**

> 📝 **Доступные шаблоны промптов**
> Каталог: `/opt/sonar-rest-api/prompts/`
>
> - `status-comment` — разбор одного issue с заданным статусом
> - `bulk-status-comment` — разбор группы
> - `analyze-issue` — общий анализ без фиксации статуса
> - `auto-resolve` — LLM сам выбирает статус (используется в `auto`)

**Зачем:** диагностика. Если бот не находит шаблон, он видит список доступных и понимает, что не так.

---

## 16. Готовый промпт для LLM — `/api/prompt/build` 🆕

**Пользователь:**
> Собери промпт, чтобы LLM разобрала issue 9ede9b09-… как `accept`.

**Бот вызывает:**
```
GET /api/prompt/build?project=MEDMIS-MISmobile.doctor_api_spec&name=status-comment&issueKey=9ede9b09-5875-4fbf-8a21-200346c8e415&status=accept
```

**Ответ в чат:**

> 📝 **Промпт (status-comment, 12480 chars)**
>
> ```
> Ты — опытный разработчик и эксперт по SonarQube.
> ЗАДАЧА:
> 1. Подтверди или скорректируй выбранный статус: "accept" — Принять (Это ошибка...)
> ...
> ДОСТУПНЫЕ ТЕГИ (используй ТОЛЬКО из этого списка): cwe, phpunit, require-fix, not-require-fix, ...
> ...
> Код:
> ```java
>   40 | public void handle(Message m) { ... }
> ```
> ФОРМАТ ОТВЕТА (СТРОГО):
> 1. Строка 1: "Статус: <accept|falsepositive|...>"
> 2. Строка 2: "Критичность: ..."
> 3. Строка 3: "Теги: <require-fix|not-require-fix>, ..."
> ```
>
> *Готово для отправки в Ollama / ChatGPT / Claude. Просто скопировать.*

**Параметры:**
- `name` — `status-comment` \| `bulk-status-comment` \| `analyze-issue` \| `auto-resolve`;
- `issueKey` — один issue;
- `keys=A,B,C` — группа;
- `sev=ALL&qual=ALL` — фильтр;
- `status=accept` — для `status-comment`/`bulk-status-comment`.

**Пример с группой:**
```
GET /api/prompt/build?…&name=bulk-status-comment&keys=A,B,C&status=accept
```
→ в ответе `[ISSUES_LIST]` уже заполнен списком A, B, C.

**Ошибки:**
- `400` — не передан `name`;
- `404` — файл `prompts/<name>.txt` не найден (в теле ответа — список доступных).

---

## 17. Сквозной диалог (несколько шагов подряд)

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

👤 Что уже обработано?
🤖 [accepted] Всего 95. По критичности: CRITICAL 52, MAJOR 35, MINOR 8.
По резолюциям: WONTFIX 95. Топ-файл — AuthApi.php (14).

👤 Покажи историю по issue 9ede9b09-…
🤖 [changelog] 3 изменения: status CONFIRMED→REOPENED (ivanov),
status OPEN→CONFIRMED (petrov), tags code-smell→error-handling,require-fix (ivanov).

👤 Собери промпт для LLM по issue 1a2b3c4d-…, хочу falsepositive.
🤖 [prompt/build] Готово, 12480 символов — скинуть в чат или в файл?

👤 Какие у проекта метрики?
🤖 [measures] Quality Gate — ERROR. Coverage 68.4%, Duplicates 3.2%,
NCLOC 32 100. Топ-категория по рейтингу — Security (A), Reliability (C).
```

Каждый шаг — ровно один REST-вызов:

| Реплика | Endpoint |
|---|---|
| «сколько MEDIUM…» | `GET /api/summary` |
| «покажи по нему код» | `GET /api/details` (или `perfile` → `snippet`) |
| «дай ключи» | `GET /api/keys` |
| «применяй accept» | `POST /api/mark?…&dryRun=true` → затем `dryRun=false` |
| «status» | `GET /api/status` |
| «что уже обработано» | `GET /api/accepted?facets=true` |
| «покажи историю» | `GET /api/changelog` |
| «собери промпт» | `GET /api/prompt/build` |
| «какие метрики» | `GET /api/measures` |

---

## 18. Умные follow-up (бот может предложить сам)

Когда бот получает JSON, он видит подсказки:

| Поле в ответе | Куда ведёт |
|---|---|
| `hasMore: true, remaining: N` | «показать ещё N?» → `details` с `snippets=` дальше |
| `failedKeys: [...]` | «N не удалось пометить — показать?» → `snippet` по каждому |
| `source: "live"` + `snapshotReady: false` | «снимок не построен, данные медленные — пересобрать?» → `POST /api/refresh` |
| `snapshot.phase: "BUILDING"` | «сборка идёт (150/342), повторить через 10с?» → `status` |
| `reason: "status_changed"` | «issue уже закрыт в Sonar, я убрал его из снимка» |
| `issue.transitions: ["accept", "falsepositive", …]` | «доступные действия для этой задачи: accept / falsepositive / …» |
| 🆕 `facets.severities` | «есть CRITICAL 52, MAJOR 35 — фильтровать?» → `accepted?sev=CRITICAL` |
| 🆕 `facets.resolutions` | «WONTFIX 95 — может, часть пересмотреть?» |
| 🆕 `issue.tags` в `/snippet` | «🏷️ Теги: error-handling, require-fix — редактировать?» → `set_tags` |
| 🆕 `issue.comments` в `/snippet` | «💬 2 комментария — показать историю?» → `/changelog` |
| 🆕 `changelog.diffs` | «🔄 статус менялся 2 раза — показать полный таймлайн?» |
| 🆕 `prompt/build` (большой prompt) | «12480 символов — скинуть в чат или сохранить в файл?» |
| 🆕 `prompt/list` → нет нужного имени | «шаблона `xxx` нет — доступны: auto-resolve, status-comment, …» |
| 🆕 `accepted` + `facets` | «можно отфильтровать по type/resolution — показать?» |

Это делает диалог естественным: пользователь не помнит эндпоинтов — он просто уточняет.

---

## 19. Шаблоны промптов (копировать как есть)

```
Сводка по {PROJECT} {SEV}/{QUAL}
Матрица file×rule по {PROJECT}
Замечания по файлам {PROJECT} {SEV}/{QUAL}
Подробно с кодом {PROJECT} {SEV}/{QUAL}, {N} на файл
Список issues {PROJECT} {SEV}/{QUAL}
Ключи {PROJECT} {SEV}/{QUAL}
Код по issue {KEY} в {PROJECT}

Что уже обработано в {PROJECT}?             ← /accepted
Что осталось необработанным?                 ← /open_issues
История изменений по {KEY}                   ← /changelog
Какие есть теги?                             ← /tags
Поставь теги {TAGS} на {KEY}                 ← /set_tags
Какие метрики у {PROJECT}?                   ← /measures
Какие языки / ветки в {PROJECT}?             ← /metadata

Какие шаблоны промптов доступны?             ← /prompt/list
Собери промпт {NAME} для {KEY} со статусом {STATUS}  ← /prompt/build

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
- `{TAGS}` = `require-fix,error-handling`
- `{NAME}` = `status-comment` \| `bulk-status-comment` \| `analyze-issue` \| `auto-resolve`
- `{STATUS}` = `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix`

---

## 20. Ошибки и как их обрабатывать в чате

| HTTP | Что значит | Сообщение в чат |
|---|---|---|
| `400` | неверные параметры | «Не понял фильтр, `sev` должен быть MEDIUM/HIGH/LOW/INFO/ALL» |
| `404` | issue не найден | «Issue `…` в Sonar не найден — возможно, уже закрыт» |
| `404` (prompt) | 🆕 нет файла `prompts/<name>.txt` | «Шаблон `xxx` не найден. Доступны: auto-resolve, status-comment, …» |
| `503` | сессия не загружена | «Сессия не загружена — пришли cookie/XSRF из браузера» |
| `500` (set_tags) | 🆕 невалидный тег в Sonar | «Sonar отклонил тег (допустимы только `a-z0-9+#-._`). Уточни теги.» |
| `500` | Sonar вернул ошибку | «Sonar ответил ошибкой: {текст}. Проверить сессию?» |

Все ошибки приходят в едином виде:
```json
{ "error": "...", "status": 503 }
```
Бот может перевести это в человеческий текст, не разбирая каждый эндпоинт отдельно.

**Особый случай** — `404` от `/api/prompt/build`: тело содержит **список доступных промптов и абсолютный путь к каталогу**:
```json
{ "error": "Промпт не найден: xxx. Каталог: /opt/sonar/prompts. Доступные: auto-resolve, status-comment, ..." }
```
Бот может показать это как есть — пользователь сразу поймёт, что не так.

---

## 21. Безопасность (напоминание для бота)

- `POST /api/mark` и `POST /api/cancel` **по умолчанию** `dryRun=true` → случайно ничего не сломается.
- `POST /api/set_tags` 🆕 — **применяется сразу** (без dry-run), т.к. это идемпотентная установка тегов. Бот должен показать план («поставить теги X, Y на issue Z?») до отправки.
- Бот **обязан** показать dry-run-план и попросить явное подтверждение перед `dryRun=false`.
- Если сервер запущен с `-Dsonar.api.token=…` — добавь `Authorization: Bearer …` (и не забудь, что CORS `Allow-Headers` нужно расширить — см. `Access-Control-Allow-Headers` в `BaseHandler`).
- `/api/session` отдаёт cookie/XSRF в открытом виде — **никогда** не показывай его содержимое в публичном чате.
- 🆕 `/api/prompt/build` включает **исходный код из файлов** — если код под NDA, не отображай промпт в чате; вместо этого возвращай пользователю только длину (`promptLength`) или сохраняй в файл.

