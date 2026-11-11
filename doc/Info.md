# Полный справочник REST API `http://127.0.0.1:6060`

## 0. Общие сведения

**Base URL:** `http://127.0.0.1:6060`

**Формат:** JSON, `Content-Type: application/json; charset=utf-8`.
**Кодировка запросов:** `application/x-www-form-urlencoded` (для POST-параметров) или query string.
**CORS:** `Access-Control-Allow-Origin: *` — запросы из браузера разрешены.
**Аутентификация:** если сервер запущен с `-Dsonar.api.token=SECRET`, добавляйте заголовок:
```
Authorization: Bearer SECRET
```
или `X-Api-Token: SECRET`. Без токена (по умолчанию) авторизация не требуется — **сервер открыт для всех, кто достучится до порта**.

**Коды ответов:**

| Код | Значение |
|---|---|
| `200` | OK — данные получены / изменение применено |
| `202` | Accepted — операция поставлена в очередь (async refresh) |
| `400` | Bad Request — неверные параметры |
| `401` | Unauthorized — нет/неверный Bearer-токен (если он включён) |
| `404` | Not Found — issue не найден |
| `405` | Method Not Allowed — неподдерживаемый HTTP-метод |
| `500` | Internal Error — ошибка на сервере / Sonar |
| `503` | Service Unavailable — сессия не загружена или снимок не построен |

**Общие параметры** (для всех data-эндпоинтов):

| Параметр | Значения | По умолчанию | Описание |
|---|---|---|---|
| `project` | `MEDMIS-MISmobile.kafka_api_spec` и т.п. | из `--project` / `SONAR_PROJECT` | ключ проекта в SonarQube |
| `sev` | `MEDIUM` \| `HIGH` \| `LOW` \| `INFO` \| `ALL` | `MEDIUM` | impact severity |
| `qual` | `MAINTAINABILITY` \| `RELIABILITY` \| `SECURITY` \| `SECURITY_HOTSPOT` \| `ALL` | `MAINTAINABILITY` | software quality |

> `SECURITY_HOTSPOT` — служебное значение для запроса `/api/issues` через hotspot-API Sonar. В `/api/accepted` и `/api/summary` использовать `ALL`/`SECURITY`.

**Источник данных в ответе** (поле `source`):
- `snapshot` — данные из построенного снимка (быстро, может быть устаревшим, если не было `refresh`);
- `live` — прямой запрос в SonarQube (медленно, но актуально; используется, если снимок не построен для этого проекта).

**Поля issue в JSON** (используются во всех data-эндпоинтах):

| Поле | Тип | Описание |
|---|---|---|
| `key` | string | ключ issue |
| `rule` / `ruleName` | string | ключ и человекочитаемое имя правила |
| `severity` | string | старая шкала (`CRITICAL`/`MAJOR`/`MINOR`/`INFO`) |
| `impactSeverity` | string | новая шкала (`HIGH`/`MEDIUM`/`LOW`/`INFO`) — приоритет, если заполнено |
| `component` | string | путь к файлу (без префикса проекта) |
| `line` | int | номер строки |
| `message` | string | текст issue |
| `status` | string | `OPEN`, `REOPENED`, `CONFIRMED`, `ACCEPTED`, `RESOLVED` |
| `resolution` | string | `FIXED`, `WONTFIX`, `FALSE_POSITIVE` |
| `issueStatus` | string | фактический статус с учётом hotspot-специфики (`TO_REVIEW`, `REVIEWED`) |
| `type` | string | `CODE_SMELL`, `BUG`, `VULNERABILITY`, `SECURITY_HOTSPOT` |
| `author` | string | автор последнего изменения |
| `creationDate` / `updateDate` | ISO-8601 | даты создания / обновления |
| `tags` | string[] | активные теги issue |
| `transitions` | string[] | доступные переходы |
| `impacts` | object[] | `[{softwareQuality, severity}]` |
| **`comments`** | object[] | 🆕 история комментариев: `[{key, login, htmlText, markdown, createdAt}]` |

---

## 1. Диагностика / служебные

### 1.1 `GET /api/health` — проверка живости

**Описание:** быстрый health-check. Не требует сессии, не требует токена. 🆕 теперь отдаёт прогресс сборки снимка — удобно для прогресс-бара.
**Параметры:** нет.

```bash
curl http://127.0.0.1:6060/api/health
```

**Ответ 200:**
```json
{
  "status": "ok",
  "sessionReady": true,
  "snapshotStatus": "BUILDING",
  "snapshotReady": true,
  "snapshotPhase": "resolving_rules",
  "snapshotProgress": 150,
  "snapshotProgressTotal": 342,
  "snapshotProject": "MEDMIS-MISmobile.kafka_api_spec",
  "snapshotIssuesCount": 342,
  "snapshotBuiltAt": "2025-11-07T12:34:56Z",
  "defaultProject": "MEDMIS-MISmobile.kafka_api_spec",
  "timestamp": "2025-11-07T13:00:00Z"
}
```

**Новые поля (для прогресса):**
- `snapshotPhase`: `starting` / `fetching_issues` / `resolving_rules` / `fetching_snippets` / `done` / `failed`;
- `snapshotProgress` / `snapshotProgressTotal` — текущий/общий шаг (например, 150/342).

---

### 1.2 `GET /api/status` — детальный статус

**Описание:** состояние сессии, снимка, прогресс сборки, настройки прогрева. Полный аналог `/api/health`, но с подробностями.
**Параметры:** нет.

```bash
curl http://127.0.0.1:6060/api/status
```

**Ответ 200:**
```json
{
  "session": {
    "ready": true,
    "path": "session.txt",
    "host": "sonar.example.com",
    "hasCookie": true,
    "hasXsrf": true
  },
  "snapshot": {
    "status": "BUILDING",
    "phase": "resolving_rules",
    "progress": 150,
    "progressTotal": 342,
    "startedAt": "2025-11-07T12:59:30Z",
    "projectKey": "MEDMIS-MISmobile.kafka_api_spec",
    "builtAt": "2025-11-07T12:50:00Z",
    "issuesCount": 342,
    "snippetsCount": 342,
    "host": "sonar.example.com"
  },
  "defaultProject": "MEDMIS-MISmobile.kafka_api_spec",
  "warmupSnippets": true,
  "warmupSnippetLimit": 0
}
```

**Полезные поля:**
- `snapshot.status`: `EMPTY` / `BUILDING` / `READY` / `FAILED`;
- `snapshot.phase`: `starting` / `fetching_issues` / `resolving_rules` / `fetching_snippets` / `done` / `failed`;
- `snapshot.progress / progressTotal` — числовой прогресс (например, 150/342 правил).

---

## 2. Сессия (cookie + XSRF)

### 2.1 `GET /api/session` — прочитать текущую сессию

**Описание:** возвращает содержимое `session.txt` и разобранные поля. **Внимание:** отдаёт cookie и XSRF в открытом виде.

```bash
curl http://127.0.0.1:6060/api/session
```

**Ответ 200:**
```json
{
  "path": "session.txt",
  "exists": true,
  "raw": "Host: sonar.example.com\nCookie: ...\nX-XSRF-TOKEN: ...",
  "host": "sonar.example.com",
  "xsrf": "abc...xyz",
  "hasCookie": true
}
```

Если файла нет — `"exists": false, "raw": ""`, остальные поля пустые/`false`.

---

### 2.2 `PUT /api/session` — заменить `session.txt` (и перезагрузить сессию)

**Описание:** тело запроса — **сырой текст** дампа (как из DevTools). Сервер валидирует, пишет на диск, перезагружает `SonarClient`. По умолчанию триггерит фоновый пересбор снимка.
**Параметры:**
- `project` (query, опционально) — проект для пересбора; иначе используется `defaultProject`;
- `build` (query, `true|false`, опц., по умолчанию `true`) — запускать ли фоновый пересбор.

```bash
curl -X PUT 'http://127.0.0.1:6060/api/session?project=MY_PROJECT&build=true' \
     --data-binary @session.txt
```

**Ответ 200:**
```json
{
  "saved": true,
  "path": "session.txt",
  "host": "sonar.example.com",
  "xsrf": "abc...",
  "hasCookie": true,
  "buildStarted": true,
  "buildProject": "MY_PROJECT",
  "note": "Пересбор снимка идёт в фоне, прогресс: GET /api/status"
}
```

**Ошибки:**
- `400` — невалидный формат (нет `Host`/`Cookie`);
- `500` — не удалось записать файл.

**Особенность:** файл пишется только после успешной валидации — битый дамп не убьёт рабочую сессию.

---

### 2.3 `POST /api/session/reload` — перечитать `session.txt` с диска

**Описание:** если файл правили вручную (не через API).
**Параметры:** те же, что у `PUT /api/session` (`project`, `build`).

```bash
curl -X POST 'http://127.0.0.1:6060/api/session/reload?build=true'
```

**Ответ 200:**
```json
{
  "reloaded": true,
  "path": "session.txt",
  "ready": true,
  "host": "sonar.example.com",
  "xsrf": "...",
  "hasCookie": true,
  "buildStarted": true,
  "buildProject": "MY_PROJECT",
  "note": "Пересбор снимка идёт в фоне, прогресс: GET /api/status"
}
```

---

## 3. Снимок проекта (refresh)

Снимок — это «фотография» всех issues проекта с `ruleName` и сниппетами. Data-эндпоинты (раздел 4) читают из снимка, если он построен для запрошенного `project`; иначе идут live в Sonar.

### 3.1 `POST /api/refresh` — полный пересбор снимка

**Описание:** заново скачивает все `OPEN/CONFIRMED` issues, названия правил, сниппеты.
**Параметры:**
- `project` (query, опц.) — проект (иначе `defaultProject`);
- `wait` (`true|false`, опц., по умолчанию `false`):
  - `false` → **202** сразу, сборка в фоне;
  - `true` → **200** после завершения (запрос блокируется, может быть долгим).

```bash
# Асинхронный запуск (возвращается сразу)
curl -X POST 'http://127.0.0.1:6060/api/refresh?project=MY_PROJECT'
```

**Ответ 202 (async):**
```json
{
  "status": "building",
  "project": "MY_PROJECT",
  "message": "Следите за прогрессом через GET /api/status"
}
```

```bash
# Синхронно (ждём завершения)
curl -X POST 'http://127.0.0.1:6060/api/refresh?project=MY_PROJECT&wait=true'
```

**Ответ 200 (sync):**
```json
{
  "status": "ready",
  "project": "MY_PROJECT",
  "builtAt": "2025-11-07T13:10:00Z",
  "issuesCount": 342,
  "snippetsCount": 342
}
```

Если сборка уже идёт — оба варианта вернут `202` с `"status": "building"` и текущим прогрессом.

---

### 3.2 `POST /api/refresh/issue` — точечный refresh одного issue

**Описание:** если кто-то поправил код/перевёл issue в Sonar, можно обновить только один ключ (не пересобирая всё). Поведение:
- если issue всё ещё `OPEN/CONFIRMED` — обновляется в снимке (правило + сниппет);
- если статус стал `RESOLVED/CLOSED/...` — **удаляется** из снимка;
- если issue не найден в Sonar — **удаляется** из снимка.

**Параметры:**
- `project` (query, опц.);
- `key` (query, **обязательно**) — issue key. Также принимается `issueKey`.

```bash
curl -X POST 'http://127.0.0.1:6060/api/refresh/issue?project=MY_PROJECT&key=AYxYz123'
```

**Ответ 200 (обновлён):**
```json
{
  "project": "MY_PROJECT",
  "key": "AYxYz123",
  "removed": false,
  "issue": { "key": "AYxYz123", "status": "OPEN", "...": "..." },
  "snapshotUpdated": true,
  "snapshotIssuesCount": 342
}
```

**Ответ 200 (удалён из снимка):**
```json
{
  "project": "MY_PROJECT",
  "key": "AYxYz123",
  "removed": true,
  "reason": "status_changed",
  "issue": { "key": "AYxYz123", "status": "RESOLVED", "...": "..." },
  "snapshotUpdated": true,
  "snapshotIssuesCount": 341
}
```

`reason` бывает `not_found` (в Sonar уже нет) и `status_changed` (закрыт).

**Ошибки:**
- `400` — нет `key`;
- `503` — снимок не построен / сессия не загружена.

---

## 4. Данные (read-only)

Все запросы из этого раздела принимают общие параметры `project`, `sev`, `qual` (см. §0) и возвращают `source: snapshot|live`.

### 4.1 `GET /api/summary` — сводка

**Описание:** общее количество + счётчики по правилам/файлам/серьёзностям. Соответствует CLI-команде `summary`.

```bash
curl 'http://127.0.0.1:6060/api/summary?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY'
```

**Ответ 200:**
```json
{
  "project": "MY_PROJECT",
  "severity": "MEDIUM",
  "quality": "MAINTAINABILITY",
  "total": 87,
  "byRules": {
    "java:S2259": 9,
    "java:S2589": 5,
    "java:S2201": 3
  },
  "byFiles": {
    "src/main/java/ru/miacomsoft/KafkaConsumer.java": 4,
    "src/main/java/ru/miacomsoft/Producer.java": 3
  },
  "bySeverities": {
    "MEDIUM": 87
  },
  "source": "snapshot",
  "snapshotBuiltAt": "2025-11-07T12:34:56Z"
}
```

---

### 4.2 `GET /api/matrix` — матрица file × rule

**Описание:** сводная таблица «файл на правило», удобна для понимания, где концентрация каких проблем.

```bash
curl 'http://127.0.0.1:6060/api/matrix?project=MY_PROJECT&sev=ALL&qual=ALL'
```

**Ответ 200:**
```json
{
  "project": "MY_PROJECT",
  "severity": "ALL",
  "quality": "ALL",
  "total": 342,
  "rules": ["java:S2259", "java:S2589", "java:S2201"],
  "rows": [
    {
      "file": "src/main/java/ru/miacomsoft/KafkaConsumer.java",
      "counts": { "java:S2259": 4, "java:S2589": 2, "java:S2201": 0 },
      "total": 6
    },
    {
      "file": "src/main/java/ru/miacomsoft/Producer.java",
      "counts": { "java:S2259": 1, "java:S2589": 0, "java:S2201": 2 },
      "total": 3
    }
  ],
  "source": "snapshot"
}
```

---

### 4.3 `GET /api/perfile` — замечания по файлам

**Описание:** список файлов, у каждого — массив issues. Соответствует CLI-команде `perfile`.

```bash
curl 'http://127.0.0.1:6060/api/perfile?project=MY_PROJECT&sev=HIGH&qual=RELIABILITY'
```

**Ответ 200:**
```json
{
  "project": "MY_PROJECT",
  "severity": "HIGH",
  "quality": "RELIABILITY",
  "filesCount": 2,
  "issuesCount": 17,
  "files": [
    {
      "file": "src/main/java/ru/miacomsoft/KafkaConsumer.java",
      "count": 9,
      "issues": [
        { "key": "AYxYz123", "rule": "java:S2259", "message": "...", "line": 42, "status": "OPEN" }
      ]
    }
  ],
  "source": "snapshot"
}
```

---

### 4.4 `GET /api/details` — подробно с фрагментами кода

**Описание:** как `perfile`, но добавляет `ruleName` и `snippet` (исходный код вокруг issue). По умолчанию по 5 issues на файл.
**Параметры:** общие + `snippets` (int, по умолчанию `5`, `0` = без сниппетов, `-1` не принимается — станет `0`).

```bash
curl 'http://127.0.0.1:6060/api/details?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY&snippets=10'
```

**Ответ 200 (сокращённо):**
```json
{
  "project": "MY_PROJECT",
  "filesCount": 2,
  "issuesCount": 87,
  "maxSnippetsPerFile": 10,
  "files": [
    {
      "file": "src/main/java/ru/miacomsoft/KafkaConsumer.java",
      "count": 45,
      "returned": 10,
      "hasMore": true,
      "remaining": 35,
      "issues": [
        {
          "key": "AYxYz123",
          "rule": "java:S2259",
          "ruleName": "Null pointers should not be dereferenced",
          "message": "\"NullPointerException\" will be thrown...",
          "line": 42,
          "snippet": [
            "  40 | public void handle(Message m) {",
            "  41 |     if (m.getPayload() != null) {",
            "  42 |         process(m.getPayload().get());   // <- NPE",
            "  43 |     }",
            "  44 | }"
          ]
        }
      ]
    }
  ],
  "source": "snapshot"
}
```

Полезно для code-review прямо из REST-клиента.

---

### 4.5 `GET /api/issues` — плоский список issues

**Описание:** все issues под фильтр, полное JSON-представление. Соответствует CLI-команде `issues`/`list`.
**Параметры:** общие. Особое значение `qual=SECURITY_HOTSPOT` — переключает на hotspot-API Sonar (возвращает issues со статусами `TO_REVIEW`/`REVIEWED`).

```bash
curl 'http://127.0.0.1:6060/api/issues?project=MY_PROJECT&sev=HIGH&qual=ALL' \
     -o issues.json

# Hotspots (специальный QUAL)
curl 'http://127.0.0.1:6060/api/issues?project=MY_PROJECT&sev=ALL&qual=SECURITY_HOTSPOT'
```

**Ответ 200 (сокращённо):**
```json
{
  "project": "MY_PROJECT",
  "severity": "HIGH",
  "quality": "ALL",
  "total": 17,
  "issues": [
    {
      "key": "AYxYz123",
      "rule": "java:S2259",
      "ruleName": "Null pointers should not be dereferenced",
      "severity": "CRITICAL",
      "impactSeverity": "HIGH",
      "component": "src/main/java/ru/miacomsoft/KafkaConsumer.java",
      "line": 42,
      "message": "\"NullPointerException\" will be thrown...",
      "status": "OPEN",
      "type": "BUG",
      "author": "user@example.com",
      "creationDate": "2025-10-01T10:00:00Z",
      "updateDate": "2025-10-15T15:30:00Z",
      "flowsCount": 1,
      "tags": ["cwe"],
      "transitions": ["accept", "falsepositive", "confirm", "resolve", "wontfix"],
      "impacts": [
        { "softwareQuality": "RELIABILITY", "severity": "HIGH" }
      ],
      "comments": [
        {
          "key": "comment-uuid",
          "login": "ivanov",
          "htmlText": "<b>Переоткрыл</b>, воспроизводится",
          "markdown": "**Переоткрыл**, воспроизводится",
          "createdAt": "2025-10-15T15:28:00Z"
        }
      ]
    }
  ],
  "source": "snapshot"
}
```

🆕 Массив `comments` содержит все комментарии к issue — используется web-вкладкой «Детали ошибки» и вкладкой «Snippet» для отображения истории.

---

### 4.6 `GET /api/keys` — только ключи

**Описание:** лёгкий ответ (только `key`), если нужно быстро получить список для последующих `mark`/`cancel`. Соответствует CLI-команде `keys`.

```bash
curl 'http://127.0.0.1:6060/api/keys?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY'
```

**Ответ 200:**
```json
{
  "project": "MY_PROJECT",
  "severity": "MEDIUM",
  "quality": "MAINTAINABILITY",
  "total": 87,
  "keys": ["AYxYz1", "AYxYz2", "AYxYz3"],
  "source": "snapshot"
}
```

---

### 4.7 `GET /api/snippet` — один issue + код

**Описание:** детальная карточка одного issue: поля, `ruleName`, сниппет, **теги и комментарии**. Сначала ищет в снимке, потом live.
**Параметры:** общие + `issueKey` (**обязательно**).

```bash
curl 'http://127.0.0.1:6060/api/snippet?project=MY_PROJECT&issueKey=AYxYz123'
```

**Ответ 200:**
```json
{
  "project": "MY_PROJECT",
  "issue": {
    "key": "AYxYz123",
    "rule": "java:S2259",
    "ruleName": "Null pointers should not be dereferenced",
    "impactSeverity": "HIGH",
    "component": "src/main/java/ru/miacomsoft/KafkaConsumer.java",
    "line": 42,
    "message": "\"NullPointerException\" will be thrown...",
    "status": "OPEN",
    "tags": ["require-fix", "error-handling"],
    "comments": [
      {
        "key": "...", "login": "ivanov",
        "markdown": "Воспроизводится на staging",
        "createdAt": "2025-10-15T15:28:00Z"
      }
    ]
  },
  "ruleName": "Null pointers should not be dereferenced",
  "snippet": [
    "  40 | public void handle(Message m) {",
    "  41 |     if (m.getPayload() != null) {",
    "  42 |         process(m.getPayload().get());   // <- NPE",
    "  43 |     }",
    "  44 | }"
  ],
  "source": "snapshot"
}
```

**Ошибки:**
- `400` — нет `issueKey`;
- `404` — issue не найден в Sonar.

---

### 4.8 🆕 `GET /api/changelog` — история изменений issue

**Описание:** журнал изменений одного issue (смена статуса, resolution, тегов, assignee и т.п.). Используется web-вкладками для отображения хронологии изменений и комментариев.
**Параметры:** общие + `issueKey` (**обязательно**).

```bash
curl 'http://127.0.0.1:6060/api/changelog?project=MY_PROJECT&issueKey=AYxYz123'
```

**Ответ 200:**
```json
{
  "changelog": [
    {
      "user": "ivanov",
      "userName": "Иван Иванов",
      "creationDate": "2025-10-15T15:30:00Z",
      "diffs": [
        { "key": "status", "oldValue": "CONFIRMED", "newValue": "REOPENED" }
      ]
    },
    {
      "user": "petrov",
      "userName": "Пётр Петров",
      "creationDate": "2025-10-15T15:28:00Z",
      "diffs": [
        { "key": "status", "oldValue": "OPEN", "newValue": "CONFIRMED" }
      ]
    }
  ],
  "source": "snapshot"
}
```

**Возможные `key` в diffs:** `status`, `resolution`, `assignee`, `tags`, `severity`, `type`, `file`, `rule`, `comment`.

**Ошибки:**
- `400` — нет `issueKey`.

---

### 4.9 🆕 `GET /api/accepted` — уже обработанные issues + facets

**Описание:** все issues со статусом `ACCEPTED` (обработанные). Возвращает полный список + локально посчитанные facets (`severities`, `types`, `resolutions`, `impactSoftwareQualities`, `impactSeverities`). Используется вкладкой «Обработанные (Accepted)».

**Параметры:**
- общие (`project`, `sev`, `qual`);
- `facets` (`true|false`, по умолчанию `true`) — включать ли facets в ответ.

```bash
curl 'http://127.0.0.1:6060/api/accepted?project=MY_PROJECT&sev=ALL&qual=ALL&facets=true'
```

**Ответ 200:**
```json
{
  "project": "MY_PROJECT",
  "severity": "ALL",
  "quality": "ALL",
  "total": 95,
  "issues": [
    {
      "key": "AYxYz123",
      "rule": "php:S131",
      "ruleName": "Switch statements should have a default clause",
      "severity": "MAJOR",
      "impactSeverity": "HIGH",
      "component": "src/Api/AuthApi.php",
      "line": 192,
      "message": "Add a \"case default\" clause...",
      "status": "ACCEPTED",
      "resolution": "WONTFIX",
      "type": "CODE_SMELL",
      "tags": ["error-handling", "require-fix"],
      "impacts": [{ "softwareQuality": "MAINTAINABILITY", "severity": "HIGH" }]
    }
  ],
  "facets": {
    "severities":              { "CRITICAL": 52, "MAJOR": 35, "MINOR": 8 },
    "types":                   { "CODE_SMELL": 95 },
    "resolutions":             { "WONTFIX": 95 },
    "impactSoftwareQualities": { "MAINTAINABILITY": 95 },
    "impactSeverities":        { "HIGH": 52, "MEDIUM": 35, "LOW": 8 }
  },
  "source": "snapshot"
}
```

Facets считаются локально по уже загруженному списку — без дополнительных запросов к SonarQube.

---

### 4.10 🆕 `GET /api/open_issues` — только OPEN/REOPENED с опциональным кодом

**Описание:** только **необработанные** issues (`OPEN` или `REOPENED`). Возвращает расширенный JSON: `ruleName` и (по умолчанию) `snippet`. Удобно для клиента (HermesAgent), чтобы не делать отдельный `/api/snippet` на каждый issue.

**Параметры:**
- общие;
- `snippets` (`true|false`, по умолчанию `true`) — включать ли `snippet` в каждый issue.

```bash
curl 'http://127.0.0.1:6060/api/open_issues?project=MY_PROJECT&sev=ALL&qual=ALL&snippets=true'
```

**Ответ 200 (сокращённо):**
```json
{
  "project": "MY_PROJECT",
  "severity": "ALL",
  "quality": "ALL",
  "statuses": ["OPEN", "REOPENED"],
  "snippetsIncluded": true,
  "total": 342,
  "issues": [
    {
      "key": "AYxYz123",
      "rule": "java:S2259",
      "ruleName": "Null pointers should not be dereferenced",
      "impactSeverity": "HIGH",
      "component": "src/main/java/ru/miacomsoft/KafkaConsumer.java",
      "line": 42,
      "message": "\"NullPointerException\" will be thrown...",
      "status": "OPEN",
      "tags": ["cwe"],
      "snippet": [
        "  40 | public void handle(Message m) {",
        "  41 |     if (m.getPayload() != null) {",
        "  42 |         process(m.getPayload().get());   // <- NPE",
        "  43 |     }",
        "  44 | }"
      ]
    }
  ],
  "source": "snapshot"
}
```

Если `snippet` получить не удалось — `"snippet": []`, `"snippetError": "..."`.

**Отличие от `/api/issues`:**
- `/api/issues` возвращает **все** статусы (включая `ACCEPTED`, `RESOLVED`);
- `/api/open_issues` — только `OPEN`/`REOPENED`, плюс автоматически добавляет `ruleName` и `snippet`.

---

### 4.11 🆕 `GET /api/tags` — все доступные теги

**Описание:** список тегов SonarQube (глобальный, не привязан к проекту). Используется web-редактором тегов и AI-обработкой (для валидации тегов, которые предлагает LLM).

**Параметры:**
- `q` (опц.) — подстрока для поиска по тегам;
- `project` (опц., нужен только для конструктора API — на результат не влияет).

```bash
curl 'http://127.0.0.1:6060/api/tags'
curl 'http://127.0.0.1:6060/api/tags?q=require'
```

**Ответ 200:**
```json
{
  "tags": [
    "accessibility", "android", "api-design", "architecture",
    "code-smell", "convention", "cwe", "duplicate", "error-handling",
    "generated-code", "not-require-fix", "require-fix", "..."
  ]
}
```

---

### 4.12 🆕 `GET /api/measures` — метрики проекта

**Описание:** агрегированные метрики проекта (Quality Gate, violations, размер, покрытие, рейтинги). Используется верхней панелью web-интерфейса.

**Параметры:**
- `project` (**обязательно**);
- `metricKeys` (опц.) — CSV для переопределения набора метрик; по умолчанию — полный набор (см. `SonarMeasuresApi.DEFAULT_METRIC_KEYS`).

```bash
curl 'http://127.0.0.1:6060/api/measures?project=MY_PROJECT'
```

**Ответ 200 (сокращённо):**
```json
{
  "project": "MY_PROJECT",
  "alertStatus": "ERROR",
  "violations": 342,
  "bugs": 17,
  "vulnerabilities": 3,
  "codeSmells": 322,
  "securityHotspots": 5,
  "maintainabilityIssues": 322,
  "reliabilityIssues": 17,
  "securityIssues": 3,
  "acceptedIssues": 95,
  "highImpactAcceptedIssues": 52,
  "newViolations": 8,
  "lines": 45230,
  "ncloc": 32100,
  "linesToCover": 24000,
  "coverage": 68.4,
  "duplicatedLinesDensity": 3.2,
  "maintainabilityRating": 2,
  "reliabilityRating": 3,
  "securityRating": 1,
  "securityReviewRating": 1,
  "sqaleRating": 2,
  "nclocByLanguage": { "java": 20000, "php": 12100 },
  "raw": { "alert_status": "ERROR", "...": "..." },
  "source": "live"
}
```

**Полезные поля:**
- `alertStatus` — `OK` / `ERROR` / `WARN`;
- `violations` — legacy-счётчик (сумма `bugs+vulnerabilities+codeSmells`);
- `*Rating` — 1=A … 5=E, 0 = не оценено;
- `nclocByLanguage` — распределение `ncloc` по языкам;
- `raw` — сырой набор метрик (полный, для отладки).

---

### 4.13 🆕 `GET /api/metadata` — метаданные проекта

**Описание:** справочная информация о проекте: языки, ветки, PR, настройки `sonar.announcement.*`. Каждый источник обёрнут в `try/catch` — если один недоступен, остальные всё равно возвращаются.

**Параметры:**
- `project` (**обязательно**);
- `languages` (`true|false`, по умолчанию `true`);
- `branches` (`true|false`, по умолчанию `true`);
- `pullRequests` (`true|false`, по умолчанию `true`);
- `settings` (`true|false`, по умолчанию `true`).

```bash
curl 'http://127.0.0.1:6060/api/metadata?project=MY_PROJECT'
```

**Ответ 200 (сокращённо):**
```json
{
  "project": "MY_PROJECT",
  "languages": [
    { "key": "java", "name": "Java" },
    { "key": "php",  "name": "PHP" }
  ],
  "branches": [
    { "name": "main", "isMain": true, "type": "BRANCH" }
  ],
  "pullRequests": [],
  "settings": {
    "settings": [
      { "key": "sonar.announcement.message", "value": "..." }
    ]
  },
  "source": "live"
}
```

---

### 4.14 🆕 `GET /api/prompt/list` — список доступных шаблонов промптов

**Описание:** диагностический эндпоинт. Показывает, какой каталог промптов видит сервер и какие `.txt` в нём лежат. Помогает отличить «маршрут не работает» от «файла нет».

**Параметры:** нет.

```bash
curl 'http://127.0.0.1:6060/api/prompt/list'
```

**Ответ 200:**
```json
{
  "dir": "/opt/sonar-rest-api/prompts",
  "dirExists": true,
  "prompts": ["analyze-issue", "auto-resolve", "bulk-status-comment", "status-comment"],
  "count": 4
}
```

---

### 4.15 🆕 `GET /api/prompt/build` — готовый промпт из шаблона

**Описание:** читает `prompts/<name>.txt`, подставляет `[TAG]`-значения из параметров и данных SonarQube, возвращает готовый текст. Внутри делает все нужные подзапросы к SonarQube (`rules/show`, `issue_snippets`, `tags`, ...). Используется web-кнопкой «Сгенерировать промпт».

**Параметры:**

| Параметр | Обязателен | Описание |
|---|---|---|
| `name` | **да** | имя файла без `.txt`: `status-comment` \| `bulk-status-comment` \| `analyze-issue` \| `auto-resolve` |
| `project` | да (если не задан `--project`) | ключ проекта |
| `issueKey` | нет (если не `keys`/`sev`+`qual`) | один issue |
| `keys` | нет | CSV-список ключей (для bulk) |
| `sev`, `qual` | нет | фильтр (для bulk) |
| `status` | нет | для `status-comment` / `bulk-status-comment`: `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` (по умолчанию `accept`) |
| `statusLabel` | нет | человекочитаемое описание статуса (по умолчанию — из маппинга) |

```bash
# Один issue
curl 'http://127.0.0.1:6060/api/prompt/build?project=MY_PROJECT&name=status-comment&issueKey=AYxYz123&status=accept'
```

**Ответ 200 (сокращённо):**
```json
{
  "promptName": "status-comment",
  "promptLength": 12480,
  "prompt": "Ты — опытный разработчик и эксперт по SonarQube.\n\nЗАДАЧА:\n1. Подтверди или скорректируй...",
  "tagsAvailable": ["cwe", "phpunit", "require-fix", "not-require-fix", "..."],
  "params": {
    "PROJECT": "MY_PROJECT",
    "STATUS": "accept",
    "STATUS_LABEL": "Принять (Это ошибка, но исправлена будет позднее)",
    "RULE_NAME_OR_KEY": "Null pointers should not be dereferenced",
    "MESSAGE": "\"NullPointerException\" will be thrown...",
    "LOCATION": "src/main/java/ru/miacomsoft/KafkaConsumer.java:42",
    "SEVERITY": "HIGH",
    "LANGUAGE": "Java",
    "LANGUAGE_FENCE": "java",
    "CODE": "  40 | public void handle(Message m) {\n  ...",
    "CODE_RAW": "  40 | public void handle(Message m) {\n  ...",
    "TAGS_LIST": "cwe, phpunit, require-fix, not-require-fix, ...",
    "ISSUES_COUNT": "1",
    "ISSUES_LIST": "1. AYxYz123 | java:S2259 | src/.../KafkaConsumer.java:42 | ..."
  },
  "source": "snapshot"
}
```

**Примеры:**

```bash
# Bulk-промпт по группе ключей
curl 'http://127.0.0.1:6060/api/prompt/build?project=MY_PROJECT&name=bulk-status-comment&keys=AYxYz1,AYxYz2&status=accept'

# Промпт по фильтру (все HIGH/RELIABILITY)
curl 'http://127.0.0.1:6060/api/prompt/build?project=MY_PROJECT&name=bulk-status-comment&sev=HIGH&qual=RELIABILITY&status=accept'

# Промпт для авто-обработки (LLM сам выберет статус)
curl 'http://127.0.0.1:6060/api/prompt/build?project=MY_PROJECT&name=auto-resolve&issueKey=AYxYz123'

# Промпт для анализа (без статуса)
curl 'http://127.0.0.1:6060/api/prompt/build?project=MY_PROJECT&name=analyze-issue&issueKey=AYxYz123'
```

**Ошибки:**
- `400` — не передан `name`;
- `404` — файл `prompts/<name>.txt` не найден (в теле ответа — список доступных промптов и абсолютный путь к каталогу).

---

## 5. Изменения статусов (mark / cancel) и тегов (set_tags)

**Важно:**
- Все `POST /api/mark` и `POST /api/cancel` по умолчанию работают **в режиме dry-run** — считают батчи, ничего не меняют. Чтобы применить, добавьте `dryRun=false`.
- Комментарий прикрепляется к переходу (через `bulk_change`), а не отдельным `add_comment` — в UI Sonar он виден как комментарий к изменению статуса.
- Поддерживаемые `transition`: `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen`.
- Для `transition=reopen` handler автоматически переключается на per-issue `do_transition` (bulk_change с `reopen` не везде работает).

### 5.1 `POST /api/mark` — одиночный issue (с комментарием)

**Описание:** пометить **один** issue с указанным статусом и комментарием.
**Параметры:**
- `project` — опц.;
- `key` (**обязательно** при одиночном режиме) — issue key;
- `transition` (**обязательно**) — `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen`;
- `comment` — опц.;
- `dryRun` — опц., по умолчанию `true`.

```bash
# 1) dry-run (ничего не меняет)
curl -X POST 'http://127.0.0.1:6060/api/mark?project=MY_PROJECT&key=AYxYz123&transition=accept&comment=Согласовано'

# 2) применить
curl -X POST 'http://127.0.0.1:6060/api/mark?project=MY_PROJECT&key=AYxYz123&transition=accept&comment=Согласовано&dryRun=false'

# 3) пометить как ложное срабатывание
curl -X POST 'http://127.0.0.1:6060/api/mark?project=MY_PROJECT&key=AYxYz456&transition=falsepositive&comment=Не%20является%20дефектом&dryRun=false'

# 4) reopen через mark (эквивалент /api/cancel?key=)
curl -X POST 'http://127.0.0.1:6060/api/mark?project=MY_PROJECT&key=AYxYz123&transition=reopen&dryRun=false'
```

**Ответ 200 (dry-run):**
```json
{
  "project": "MY_PROJECT",
  "key": "AYxYz123",
  "transition": "accept",
  "comment": "Согласовано",
  "dryRun": true,
  "applied": false
}
```

**Ответ 200 (applied):**
```json
{
  "project": "MY_PROJECT",
  "key": "AYxYz123",
  "transition": "accept",
  "comment": "Согласовано",
  "dryRun": false,
  "applied": true,
  "success": true
}
```

При ошибке `success: false` и `error: "..."`, статус ответа остаётся 200 (детали в теле).

---

### 5.2 `POST /api/mark` — массово по фильтру

**Описание:** пометить **все** issues, попадающие под `sev`/`qual`, батчами по 500 с паузой 500 мс.
**Параметры:**
- `project` — опц.;
- `sev`, `qual` — фильтр;
- `transition` (**обязательно**);
- `comment` — опц.;
- `dryRun` — опц., по умолчанию `true`.

```bash
# dry-run: сколько issues/батчей
curl -X POST 'http://127.0.0.1:6060/api/mark?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY&transition=accept&comment=Массово'

# применить
curl -X POST 'http://127.0.0.1:6060/api/mark?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY&transition=accept&comment=Массово&dryRun=false'
```

**Ответ 200 (dry-run):**
```json
{
  "project": "MY_PROJECT",
  "severity": "MEDIUM",
  "quality": "MAINTAINABILITY",
  "transition": "accept",
  "comment": "Массово",
  "issuesCount": 87,
  "dryRun": true,
  "batches": 1,
  "applied": false
}
```

**Ответ 200 (applied, обычный transition):**
```json
{
  "project": "MY_PROJECT",
  "severity": "MEDIUM",
  "quality": "MAINTAINABILITY",
  "transition": "accept",
  "issuesCount": 87,
  "dryRun": false,
  "batches": 1,
  "applied": true,
  "responses": ["{...sonar response...}"]
}
```

**Ответ 200 (applied, transition=reopen):**
```json
{
  "project": "MY_PROJECT",
  "severity": "MEDIUM",
  "quality": "MAINTAINABILITY",
  "transition": "reopen",
  "issuesCount": 87,
  "dryRun": false,
  "batches": 1,
  "applied": true,
  "succeeded": 85,
  "failed": 2,
  "failedKeys": ["AYxYz1", "AYxYz2"]
}
```

---

### 5.3 `POST /api/cancel` — снять пометку с одного issue (reopen)

**Описание:** семантический алиас для reopen. Использует per-issue `do_transition("reopen")` с паузой 120 мс (bulk_change с `reopen` не на всех версиях SonarQube работает).
**Параметры:**
- `project` — опц.;
- `key` (**обязательно**) — issue key;
- `dryRun` — опц., по умолчанию `true`.

```bash
curl -X POST 'http://127.0.0.1:6060/api/cancel?project=MY_PROJECT&key=AYxYz123&dryRun=false'
```

**Ответ 200:**
```json
{
  "project": "MY_PROJECT",
  "key": "AYxYz123",
  "dryRun": false,
  "applied": true,
  "success": true
}
```

---

### 5.4 `POST /api/cancel` — снять пометки массово по фильтру

**Описание:** reopen всех issues под `sev`/`qual`, per-issue.
**Параметры:** `project`, `sev`, `qual`, `dryRun`.

```bash
curl -X POST 'http://127.0.0.1:6060/api/cancel?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY&dryRun=false'
```

**Ответ 200:**
```json
{
  "project": "MY_PROJECT",
  "severity": "MEDIUM",
  "quality": "MAINTAINABILITY",
  "issuesCount": 87,
  "dryRun": false,
  "applied": true,
  "succeeded": 85,
  "failed": 2,
  "failedKeys": ["AYxYz1", "AYxYz2"]
}
```

---

### 5.5 🆕 `POST /api/set_tags` — установить теги на один/несколько issues

**Описание:** устанавливает теги на issues через `issues/set_tags`. Позволяет работать как с одним issue (`issue=KEY`), так и с группой (`keys=A,B,C`) — в зависимости от того, что удобнее клиенту.

**Параметры:**

| Параметр | Обязателен | Описание |
|---|---|---|
| `project` | да (если не задан `--project`) | ключ проекта |
| `keys` | **да** (или `issue`) | CSV-список ключей или один ключ |
| `issue` | нет | alias для одиночного ключа — для совместимости с SonarQube API (`/api/issues/set_tags` принимает только `issue`) |
| `tags` | да (может быть пустым) | CSV-список тегов. Пустая строка = снять все теги |

> Если передан только `issue` (без `keys`) — работает как единичная установка.
> Если передан `keys=A,B,C` — handler разбирает CSV и делает N последовательных запросов к SonarQube (set_tags принимает только `issue`).

```bash
# Один issue (удобный alias)
curl -X POST 'http://127.0.0.1:6060/api/set_tags?project=MY_PROJECT&issue=AYxYz123&tags=require-fix,error-handling'

# Явно
curl -X POST 'http://127.0.0.1:6060/api/set_tags?project=MY_PROJECT&keys=AYxYz123&issue=AYxYz123&tags=require-fix,error-handling'

# Группа (последовательные запросы)
curl -X POST 'http://127.0.0.1:6060/api/set_tags?project=MY_PROJECT&keys=AYxYz1,AYxYz2,AYxYz3&tags=require-fix'

# Снять все теги
curl -X POST 'http://127.0.0.1:6060/api/set_tags?project=MY_PROJECT&issue=AYxYz123&tags='
```

**Ответ 200:**
```json
{
  "success": true,
  "count": 1,
  "tags": "require-fix,error-handling"
}
```

**Ошибки:**
- `400` — не передан ни `keys`, ни `issue`.

> Теги валидируются SonarQube: `^[a-z0-9\+\#\-\.]+$`. Невалидные теги вызовут `500` (от Sonar) — клиент должен их отфильтровать до отправки.

---

## 6. Готовые сценарии

### 6.1 Первичная настройка

```bash
# 1) залить сессию (cookie/XSRF из браузера)
curl -X PUT 'http://127.0.0.1:6060/api/session?project=MEDMIS-MISmobile.kafka_api_spec' \
     --data-binary @session.txt

# 2) дождаться окончания сборки снимка
while :; do
  S=$(curl -s http://127.0.0.1:6060/api/status | jq -r .snapshot.status)
  echo "snapshot: $S"
  [ "$S" = "READY" ] && break
  sleep 2
done

# 3) проверить
curl -s http://127.0.0.1:6060/api/health | jq .
```

### 6.2 Кто-то поправил один баг — обновить только его

```bash
curl -X POST 'http://127.0.0.1:6060/api/refresh/issue?project=MY_PROJECT&key=AYxYz123'
```

Если `"removed": true` — issue закрыт, он исчез из снимка.

### 6.3 Посмотреть все HIGH/RELIABILITY с кодом

```bash
curl -s 'http://127.0.0.1:6060/api/details?project=MY_PROJECT&sev=HIGH&qual=RELIABILITY&snippets=5' \
  | jq .
```

### 6.4 Массовый accept MEDIUM/MAINTAINABILITY (с комментарием)

```bash
# 1) dry-run — сколько затронуто
curl -X POST 'http://127.0.0.1:6060/api/mark?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY&transition=accept&comment=Спринт-42' | jq .

# 2) применить
curl -X POST 'http://127.0.0.1:6060/api/mark?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY&transition=accept&comment=Спринт-42&dryRun=false' | jq .
```

### 6.5 Откатить неудачный accept (reopen всех по фильтру)

```bash
curl -X POST 'http://127.0.0.1:6060/api/cancel?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY&dryRun=true'  # сначала dry-run
curl -X POST 'http://127.0.0.1:6060/api/cancel?project=MY_PROJECT&sev=MEDIUM&qual=MAINTAINABILITY&dryRun=false' # применить
```

### 6.6 Пересобрать снимок после правок

```bash
curl -X POST 'http://127.0.0.1:6060/api/refresh?project=MY_PROJECT&wait=true' | jq .
```

### 6.7 Сменить cookie (перелогин в браузере)

```bash
curl -X PUT 'http://127.0.0.1:6060/api/session?project=MY_PROJECT' \
     --data-binary @new_session.txt
# дальше снимок пересоберётся в фоне; следить через GET /api/status
```

### 6.8 Мониторинг прогресса

```bash
watch -n 2 'curl -s http://127.0.0.1:6060/api/status | jq ".snapshot"'
```

### 6.9 🆕 Посмотреть все обработанные issues + facets

```bash
curl -s 'http://127.0.0.1:6060/api/accepted?project=MY_PROJECT&sev=ALL&qual=ALL&facets=true' \
  | jq '{total, facets: .facets.severities, issues: (.issues | length)}'
```

### 6.10 🆕 История изменений одного issue

```bash
curl -s 'http://127.0.0.1:6060/api/changelog?project=MY_PROJECT&issueKey=AYxYz123' \
  | jq '.changelog[] | {userName, creationDate, diffs}'
```

### 6.11 🆕 Установить теги на группу issues

```bash
curl -X POST 'http://127.0.0.1:6060/api/set_tags?project=MY_PROJECT&keys=AYxYz1,AYxYz2,AYxYz3&tags=require-fix,error-handling'
```

### 6.12 🆕 Сгенерировать промпт для одного issue (для LLM)

```bash
# Собрать промпт
curl -s 'http://127.0.0.1:6060/api/prompt/build?project=MY_PROJECT&name=status-comment&issueKey=AYxYz123&status=accept' \
  | jq -r .prompt > /tmp/prompt.txt

# Отправить в Ollama напрямую
curl -s http://localhost:11434/api/generate \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"qwen2.5-coder:14b\",\"prompt\":$(jq -Rs . < /tmp/prompt.txt),\"stream\":false}" \
  | jq -r .response > /tmp/response.txt

# Прочитать ответ модели
cat /tmp/response.txt
```

### 6.13 🆕 Посмотреть метрики проекта

```bash
curl -s 'http://127.0.0.1:6060/api/measures?project=MY_PROJECT' \
  | jq '{alertStatus, violations, coverage, maintainabilityRating, reliabilityRating, securityRating}'
```

### 6.14 🆕 Посмотреть доступные теги

```bash
curl -s 'http://127.0.0.1:6060/api/tags' | jq '.tags | length'
curl -s 'http://127.0.0.1:6060/api/tags?q=require' | jq .
```

---

## 7. Сводная таблица эндпоинтов

| # | Метод | Путь | Назначение |
|---|---|---|---|
| 1 | GET | `/api/health` | health-check (+ прогресс снимка) |
| 2 | GET | `/api/status` | сессия + снимок + прогресс |
| 3 | GET | `/api/session` | прочитать `session.txt` |
| 4 | PUT | `/api/session` | записать `session.txt` + перезагрузить |
| 5 | POST | `/api/session/reload` | перечитать `session.txt` с диска |
| 6 | POST | `/api/refresh` | полный пересбор снимка |
| 7 | POST | `/api/refresh/issue` | обновить/удалить один issue в снимке |
| 8 | GET | `/api/summary` | сводка по правилам/файлам |
| 9 | GET | `/api/matrix` | матрица file × rule |
| 10 | GET | `/api/perfile` | issues по файлам |
| 11 | GET | `/api/details` | issues + сниппеты кода |
| 12 | GET | `/api/issues` | плоский список issues |
| 13 | GET | `/api/keys` | только ключи issues |
| 14 | GET | `/api/snippet` | один issue + сниппет + теги + комментарии |
| 15 | GET | 🆕 `/api/changelog` | история изменений одного issue |
| 16 | GET | 🆕 `/api/accepted` | обработанные issues + facets |
| 17 | GET | 🆕 `/api/open_issues` | только OPEN/REOPENED, с кодом |
| 18 | GET | 🆕 `/api/tags` | все доступные теги |
| 19 | GET | 🆕 `/api/measures` | метрики проекта |
| 20 | GET | 🆕 `/api/metadata` | языки / ветки / PR / settings |
| 21 | GET | 🆕 `/api/prompt/list` | список шаблонов промптов |
| 22 | GET | 🆕 `/api/prompt/build` | готовый промпт из шаблона |
| 23 | POST | `/api/mark` (`?key=`) | пометить один issue + комментарий |
| 24 | POST | `/api/mark` (`?sev=&qual=`) | массовая пометка по фильтру |
| 25 | POST | `/api/cancel` (`?key=`) | снять пометку с одного issue |
| 26 | POST | `/api/cancel` (`?sev=&qual=`) | массовый reopen по фильтру |
| 27 | POST | 🆕 `/api/set_tags` | установить теги (один/несколько) |

---

## 8. Чего нет в REST API (только консоль / web)

Следующие операции **не имеют** отдельного REST-эндпоинта — доступны только через интерактивную консоль (см. `CONSOLE.md`) или web-интерфейс:

- **Настройка Ollama** — хранится в `ollama.properties`, настраивается через web (кнопка **⚙ Ollama**) или команду `ollama set` в консоли.
- **Массовая AI-обработка** (аналог «Добавить автоматически комментарий») — цикл промпт→Ollama→mark. Из REST можно собрать вручную: `/api/prompt/build` + вызов Ollama + `/api/mark` (см. пример 6.12).
- **Экспорт отчёта в Markdown** — генерируется на клиенте (web, кнопка **📥 Экспорт MD** на вкладке «Обработанные»).

---

## 9. Безопасность — обратите внимание

Сервер на `127.0.0.1:6060` (или на внешнем IP) **открыт для всех, кто достучится до порта**. Если на старте не передан `-Dsonar.api.token=SECRET`:

- **любой** может прочитать `/api/session` и получить вашу cookie/XSRF от SonarQube;
- **любой** может вызвать `/api/mark?dryRun=false` и поменять статусы issues;
- **любой** может вызвать `/api/set_tags` и перезаписать теги;
- **любой** может подменить сессию через `PUT /api/session` и направить её на свой хост (`Host: attacker.example.com`) — это SSRF-вектор;
- **любой** может выгрузить историю изменений/комментарии issue через `/api/changelog` и `/api/snippet`.

**Настоятельно рекомендуется:**

1. Перезапустить сервер с `-Dsonar.api.token=SECRET` и передавать `Authorization: Bearer SECRET`.
2. Поставить перед ним reverse-proxy (nginx/Traefik) с TLS и HTTP Basic / OAuth2.
3. Ограничить доступ по IP (firewall) на уровне `smwrap.ru`.
4. По возможности использовать `--host=127.0.0.1` и пробрасывать порт через SSH-туннель.

Пример запуска с токеном (если вы контролируете запуск):

```bash
java -jar SonarRestApi.jar \
     -Dsonar.api.bind=0.0.0.0 \
     -Dsonar.api.port=6060 \
     -Dsonar.api.token=СекретныйТокен123 \
     --session=session.txt \
     --project=MEDMIS-MISmobile.kafka_api_spec
```

И тогда все запросы из §1–§6 должны содержать:

```bash
-H 'Authorization: Bearer СекретныйТокен123'
```
```

---

## Что изменено относительно исходного `Info.md`

| Раздел | Изменение |
|---|---|
| **§0** | Обновлена таблица полей issue — добавлены `impactSeverity`, `issueStatus`, `impacts`, **`comments`**; `qual` расширен значением `SECURITY_HOTSPOT` |
| **§1.1 `/api/health`** | Добавлены новые поля `snapshotPhase`, `snapshotProgress`, `snapshotProgressTotal` |
| **§4.5 `/api/issues`** | Добавлен пример с `comments`, `impacts`; упомянут спец. `qual=SECURITY_HOTSPOT` |
| **§4.7 `/api/snippet`** | В ответ добавлены `tags` и `comments` |
| **§4.8** 🆕 | Новый эндпоинт `/api/changelog` |
| **§4.9** 🆕 | Новый эндпоинт `/api/accepted` (с facets) |
| **§4.10** 🆕 | Новый эндпоинт `/api/open_issues` (только OPEN/REOPENED + snippet) |
| **§4.11** 🆕 | Новый эндпоинт `/api/tags` |
| **§4.12** 🆕 | Новый эндпоинт `/api/measures` (метрики проекта) |
| **§4.13** 🆕 | Новый эндпоинт `/api/metadata` (языки/ветки/PR/settings) |
| **§4.14** 🆕 | Новый эндпоинт `/api/prompt/list` |
| **§4.15** 🆕 | Новый эндпоинт `/api/prompt/build` (рендер `prompts/*.txt`) |
| **§5** | Добавлен подраздел **`§5.5 /api/set_tags`** 🆕 |
| **§6** | Добавлены сценарии 6.9–6.14 (`accepted`, `changelog`, `set_tags`, `prompt/build`, `measures`, `tags`) |
| **§7** | Сводная таблица расширена с 18 до **27 эндпоинтов** |
| **§8** 🆕 | Явно указано, чего **нет** в REST (`ollama`, `auto`, экспорт MD) |
| **§9** | Раздел про безопасность дополнен: `set_tags`, `changelog`, `snippet` как ещё одни векторы злоупотребления |