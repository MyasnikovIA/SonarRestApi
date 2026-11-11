# Прямые запросы к SonarQube через `curl` — все URL, которые реально вызывает проект

Все примеры — **напрямую к SonarQube** (не к прокси на `localhost:6060`). Именно эти URL формируются в `SonarClient` / `SonarIssuesApi` / `SonarBulkApi` / `SonarMeasuresApi` / `MetadataHandler` и других местах кода.

**Переменные для копипаста:**

```bash
SONAR=https://sonar.bars.group
TOKEN=squ_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
AUTH="Authorization: Bearer $TOKEN"
PROJ=MEDMIS-MISmobile.doctor_api_spec
KEY=30637c2f-1a4b-4d5e-8f90-abcdef123456      # ← реальный issue key
RULE=php:S1192                                 # ← реальный ключ правила
```

> **Как получить реальный `KEY`** — см. раздел 3.1 (`/api/issues/search`), поле `issues[0].key`.
> **Как получить реальный `RULE`** — см. раздел 4.1, поле `issues[0].rule`.

---

## Оглавление

1. Аутентификация
2. System — проверка живости
3. Issues — поиск и данные issue
4. Rules — имя правила
5. Sources — фрагменты кода
6. Tags — теги SonarQube
7. Hotspots — Security Hotspots
8. Bulk operations — изменение статусов / комментов / тегов
9. Measures — метрики проекта
10. Metadata — языки / ветки / PR / настройки
11. Projects — поиск проектов
12. Quality Gates — гейты качества

---

## 1. Аутентификация

Все запросы используют **одну** строку заголовка:

```bash
-H "Authorization: Bearer $TOKEN"
```

`Accept` и `Content-Type` — как браузер:

```bash
-H "Accept: application/json"
-H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8"   # для POST
```

**Проверка, что токен рабочий** (любой API-запрос, минимальный):

```bash
curl -s -H "$AUTH" -H "Accept: application/json" \
     "$SONAR/api/system/status" | jq
```

Если токен невалиден — `401 Unauthorized`, тело: `{"errors":[{"msg":"Authentication required"}]}`.

**Legacy cookie** (только если `sonar.auth=cookie`):

```bash
-H "Cookie: OAUTHSTATE=...; XSRF-TOKEN=...; JWT-SESSION=..."
-H "X-XSRF-TOKEN: <то же значение, что XSRF-TOKEN в cookie>"
```

---

## 2. System — проверка живости

Используется в `SystemApi.java` (пример из второго проекта) для preflight-проверки перед стартом работы.

### 2.1. `GET /api/system/status`

```bash
curl -s -H "$AUTH" "$SONAR/api/system/status" | jq
```

```json
{
  "id": "AV8xxx",
  "version": "10.6.0.92116",
  "status": "UP"
}
```

### 2.2. `GET /api/system/health`

```bash
curl -s -H "$AUTH" "$SONAR/api/system/health" | jq
```

```json
{
  "health": "GREEN",
  "causes": []
}
```

---

## 3. Issues — поиск и данные issue

Основной эндпоинт, вокруг которого крутится весь проект. Формируется в `SonarIssuesApi.buildQueryWithStatuses()`.

### 3.1. `GET /api/issues/search` — базовый поиск issues проекта

Именно этот запрос делает `SonarIssuesApi.fetchAllByStatuses()` при построении снимка (`Snapshot.build`) и при работе всех handlers.

**Полный набор параметров, которые генерирует прокси:**

```bash
curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
  --data-urlencode "components=$PROJ" \
  --data-urlencode "s=FILE_LINE" \
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" \
  --data-urlencode "additionalFields=_all" \
  --data-urlencode "timeZone=Asia/Novosibirsk" \
  --data-urlencode "ps=500" \
  --data-urlencode "p=1" | jq
```

**Примеры с фильтрами:**

```bash
# Только HIGH
curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
  --data-urlencode "components=$PROJ" \
  --data-urlencode "impactSeverities=HIGH" \
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" \
  --data-urlencode "additionalFields=_all" \
  --data-urlencode "ps=500" --data-urlencode "p=1" | jq
```

```bash
# Только SECURITY
curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
  --data-urlencode "components=$PROJ" \
  --data-urlencode "impactSoftwareQualities=SECURITY" \
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" \
  --data-urlencode "additionalFields=_all" \
  --data-urlencode "ps=500" --data-urlencode "p=1" | jq
```

```bash
# Только ACCEPTED (используется в AcceptedHandler)
curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
  --data-urlencode "components=$PROJ" \
  --data-urlencode "issueStatuses=ACCEPTED" \
  --data-urlencode "additionalFields=_all" \
  --data-urlencode "ps=500" --data-urlencode "p=1" | jq
```

```bash
# Только OPEN/REOPENED (используется в fetchOpen для auto-обработки)
curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
  --data-urlencode "components=$PROJ" \
  --data-urlencode "issueStatuses=OPEN,REOPENED" \
  --data-urlencode "additionalFields=_all" \
  --data-urlencode "ps=500" --data-urlencode "p=1" | jq
```

**Пагинация.** В ответе приходит `paging.total`; число страниц = `ceil(total / ps)`:

```bash
curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
  --data-urlencode "components=$PROJ" \
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" \
  --data-urlencode "additionalFields=_all" \
  --data-urlencode "ps=1" | jq '.paging'
```

```json
{ "pageIndex": 1, "pageSize": 1, "total": 1437 }
```

→ страниц `1437/500 = 3`.

### 3.2. `GET /api/issues/search?issues=KEY` — один issue по ключу

Используется в `SonarIssuesApi.fetchIssue()` (в том числе внутри `fetchSnippet`, `refreshIssue`, `SnippetHandler`, `PromptHandler`, `DetailsHandler`).

```bash
curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
  --data-urlencode "issues=$KEY" \
  --data-urlencode "additionalFields=_all" | jq
```

```json
{
  "total": 1,
  "p": 1,
  "ps": 100,
  "paging": { "pageIndex": 1, "pageSize": 100, "total": 1 },
  "issues": [
    {
      "key": "30637c2f-...",
      "rule": "php:S1192",
      "severity": "MINOR",
      "component": "MEDMIS-MISmobile.doctor_api_spec:src/Model/User.php",
      "project": "MEDMIS-MISmobile.doctor_api_spec",
      "line": 27,
      "hash": "...",
      "textRange": {
        "startLine": 27, "endLine": 27, "startOffset": 4, "endOffset": 15
      },
      "flows": [],
      "status": "OPEN",
      "message": "Define a constant instead of duplicating this literal \"admin\" 3 times.",
      "effort": "5min",
      "debt": "5min",
      "author": "",
      "tags": [],
      "creationDate": "2025-01-01T00:00:00+0000",
      "updateDate": "2026-11-01T00:00:00+0000",
      "type": "CODE_SMELL",
      "organization": "default-organization",
      "cleanCodeAttribute": "CONVENTIONAL",
      "cleanCodeAttributeCategory": "ADAPTABLE",
      "impacts": [
        { "softwareQuality": "MAINTAINABILITY", "severity": "LOW" }
      ],
      "issueStatus": "OPEN",
      "projectName": "..."
    }
  ],
  "components": [...],
  "facets": []
}
```

> Это источник `issues[0].key`, `issues[0].rule`, `issues[0].line` и т.д., которые проект потом использует.

### 3.3. `GET /api/issues/changelog?issue=KEY`

Используется в `ChangelogHandler` → `/api/changelog`. Даёт историю переходов, установок тегов, назначений.

```bash
curl -s -H "$AUTH" -G "$SONAR/api/issues/changelog" \
  --data-urlencode "issue=$KEY" | jq
```

```json
{
  "changelog": [
    {
      "user": "i.myasnikov",
      "userName": "Иван Мясников",
      "creationDate": "2026-11-10T09:00:00+0000",
      "diffs": [
        { "key": "status", "oldValue": "OPEN", "newValue": "ACCEPTED" },
        { "key": "resolution", "oldValue": "", "newValue": "WONTFIX" },
        { "key": "tags", "oldValue": "", "newValue": "not-require-fix" }
      ]
    }
  ]
}
```

---

## 4. Rules — имя правила

Используется в `SonarIssuesApi.fetchRuleName()`. Кешируется в `ServerState.ruleNameCache` (`ConcurrentHashMap`), поэтому реальный запрос идёт **только при первом обращении к правилу**.

### 4.1. `GET /api/rules/show?key=RULE`

```bash
curl -s -H "$AUTH" -G "$SONAR/api/rules/show" \
  --data-urlencode "key=$RULE" | jq '.rule | {key, name, lang, severity}'
```

```json
{
  "key": "php:S1192",
  "name": "String literals should not be duplicated",
  "lang": "php",
  "severity": "MINOR"
}
```

> `name` — это значение, которое потом попадает в `Issue.ruleName` и в `RULE_NAME` промпта.

### 4.2. Ошибки

```bash
curl -s -H "$AUTH" -G "$SONAR/api/rules/show" \
  --data-urlencode "key=php:UNKNOWN" | jq
```

```json
{
  "errors": [{ "msg": "Rule with key 'php:UNKNOWN' does not exist" }]
}
```

---

## 5. Sources — фрагменты кода

Используется в `SonarIssuesApi.fetchSnippet()`. Кешируется в `ServerState.snippetCache` (`ConcurrentHashMap`), ключ — `issueKey`.

### 5.1. `GET /api/sources/issue_snippets?issueKey=KEY`

Возвращает **одну** или **несколько** строк вокруг issue. Ключ верхнего уровня — это имя файла (компонента); внутри — массив `sources[]` с `line` и `code` (HTML с `<mark>`).

```bash
curl -s -H "$AUTH" -G "$SONAR/api/sources/issue_snippets" \
  --data-urlencode "issueKey=$KEY" | jq
```

```json
{
  "MEDMIS-MISmobile.doctor_api_spec:src/Model/User.php": {
    "sources": [
      { "line": 25, "code": "    public function setRole($role) {" },
      { "line": 26, "code": "        $this-&gt;container['role'] = 'admin';" },
      { "line": 27, "code": "        $this-&gt;container['status'] = '&lt;mark class=\"sonar-issue-mark\"&gt;admin&lt;/mark&gt;';" },
      { "line": 28, "code": "    }" }
    ]
  }
}
```

> `SonarIssuesApi.fetchSnippet()` затем делает `cleanHtml()` — заменяет `<mark>` на `§HL_START§…§HL_END§`, декодирует `&gt;` / `&lt;` / `&amp;`, добавляет префикс `%4d | ` к каждой строке.

### 5.2. Тот же запрос для несуществующего ключа

```bash
curl -s -H "$AUTH" -G "$SONAR/api/sources/issue_snippets" \
  --data-urlencode "issueKey=invalid-key" | jq
```

Возвращается `{}` (пустой объект). `fetchSnippet()` трактует это как «кода нет», кеширует пустой список.

---

## 6. Tags — теги SonarQube

Используется в `SonarIssuesApi.fetchTags()`, `TagsHandler`, `PromptBuilder.buildAutoResolveParams` и т.д.

### 6.1. `GET /api/issues/tags` — список тегов (все или по префиксу)

```bash
# Все теги
curl -s -H "$AUTH" -G "$SONAR/api/issues/tags" \
  --data-urlencode "ps=100" | jq
```

```json
{
  "tags": [
    "CWE",
    "code-duplication",
    "code-smell",
    "convention",
    "not-require-fix",
    "require-fix",
    "security"
  ]
}
```

```bash
# Только по префиксу "require"
curl -s -H "$AUTH" -G "$SONAR/api/issues/tags" \
  --data-urlencode "ps=100" \
  --data-urlencode "q=require" | jq
```

```json
{ "tags": ["not-require-fix", "require-fix"] }
```

---

## 7. Hotspots — Security Hotspots

Используется в `SonarIssuesApi.fetchHotspots()`. Особенность: SonarQube принимает **только один** статус (`TO_REVIEW` **или** `REVIEWED`) за запрос, поэтому прокси делает **два** прохода и склеивает результаты. Фильтр по `vulnerabilityProbability` — на клиенте.

### 7.1. `GET /api/hotspots/search?project=KEY&status=TO_REVIEW`

```bash
curl -s -H "$AUTH" -G "$SONAR/api/hotspots/search" \
  --data-urlencode "project=$PROJ" \
  --data-urlencode "status=TO_REVIEW" \
  --data-urlencode "ps=500" \
  --data-urlencode "p=1" | jq
```

```json
{
  "paging": { "pageIndex": 1, "pageSize": 500, "total": 4 },
  "hotspots": [
    {
      "key": "abc-123",
      "component": "MEDMIS-MISmobile.doctor_api_spec:src/Config/security.yml",
      "project": "MEDMIS-MISmobile.doctor_api_spec",
      "securityCategory": "weak-cryptography",
      "vulnerabilityProbability": "HIGH",
      "status": "TO_REVIEW",
      "line": 12,
      "message": "Make sure this weak hash algorithm is not used in a sensitive context here.",
      "author": "",
      "creationDate": "2025-01-01T00:00:00+0000",
      "updateDate": "2026-11-01T00:00:00+0000",
      "textRange": { "startLine": 12, "endLine": 12, "startOffset": 4, "endOffset": 20 },
      "flows": [],
      "ruleKey": "php:S4790"
    }
  ]
}
```

### 7.2. `GET /api/hotspots/search?project=KEY&status=REVIEWED`

```bash
curl -s -H "$AUTH" -G "$SONAR/api/hotspots/search" \
  --data-urlencode "project=$PROJ" \
  --data-urlencode "status=REVIEWED" \
  --data-urlencode "ps=500" \
  --data-urlencode "p=1" | jq
```

> `vulnerabilityProbability` (`HIGH | MEDIUM | LOW`) — это то, что в прокси становится `Issue.severity` и `Issue.impactSeverity`, а также `Impact("SECURITY", prob)`.

---

## 8. Bulk operations — изменение статусов / комментариев / тегов

Все — **POST** c `Content-Type: application/x-www-form-urlencoded`. Используются в `SonarBulkApi` (батчи по 500, `delayMs=500` между батчами, `REOPEN_DELAY_MS=120` для `reopen`).

### 8.1. `POST /api/issues/bulk_change` — массовая смена статуса + комментарий

Используется в `SonarBulkApi.change()` (батчами по 500). Также — одиночный issue через этот же эндпоинт, если transition ≠ `reopen`.

**Форма:**

```
issues=KEY1,KEY2,...
do_transition=accept|falsepositive|confirm|resolve|wontfix
comment=<текст>       ← опционально
```

```bash
curl -s -X POST -H "$AUTH" \
  --data-urlencode "issues=$KEY" \
  --data-urlencode "do_transition=accept" \
  --data-urlencode "comment=Техдолг на следующий спринт" \
  "$SONAR/api/issues/bulk_change" | jq
```

```json
{
  "total": 1,
  "success": 1,
  "ignored": 0,
  "failures": 0
}
```

**Массово (несколько ключей):**

```bash
curl -s -X POST -H "$AUTH" \
  --data-urlencode "issues=KEY1,KEY2,KEY3" \
  --data-urlencode "do_transition=confirm" \
  --data-urlencode "comment=Critical" \
  "$SONAR/api/issues/bulk_change" | jq
```

**Dry-run невозможен** — SonarQube применит изменения. Прокси делает dry-run на своей стороне (`dryRun=true` → не вызывает `bulk_change`).

### 8.2. `POST /api/issues/do_transition` — одиночный переход

Используется в `SonarBulkApi.doTransition()` — для `reopen` (через `bulk_change` `reopen` работает не на всех версиях SonarQube).

**Форма:**

```
issue=KEY
transition=reopen|confirm|resolve|accept|falsepositive|wontfix
```

```bash
curl -s -X POST -H "$AUTH" \
  --data-urlencode "issue=$KEY" \
  --data-urlencode "transition=reopen" \
  "$SONAR/api/issues/do_transition" | jq
```

```json
{
  "issue": {
    "key": "30637c2f-...",
    "status": "REOPENED"
  },
  "components": [...],
  "rules": [...]
}
```

### 8.3. `POST /api/issues/add_comment` — добавить комментарий

Используется в `SonarBulkApi.addComment()`. Сейчас в коде **не вызывается автоматически** (комментарий идёт в `bulk_change` через параметр `comment`), но метод доступен.

```bash
curl -s -X POST -H "$AUTH" \
  --data-urlencode "issue=$KEY" \
  --data-urlencode "text=Комментарий от прокси" \
  "$SONAR/api/issues/add_comment" | jq
```

```json
{
  "issue": { "key": "30637c2f-...", "comments": [ ... ] },
  "components": [...],
  "rules": [...]
}
```

### 8.4. `POST /api/issues/set_tags` — установить теги

Используется в `SonarBulkApi.setTags()` и `SetTagsHandler`. **SonarQube принимает только одну issue за вызов**, поэтому прокси вызывает этот URL в цикле.

**Форма:**

```
issue=KEY
tags=tag1,tag2,tag3      ← полный новый список (заменяет старый)
```

```bash
curl -s -X POST -H "$AUTH" \
  --data-urlencode "issue=$KEY" \
  --data-urlencode "tags=require-fix,code-smell" \
  "$SONAR/api/issues/set_tags" | jq
```

```json
{
  "issue": {
    "key": "30637c2f-...",
    "tags": ["require-fix", "code-smell"]
  }
}
```

```bash
# Очистить все теги
curl -s -X POST -H "$AUTH" \
  --data-urlencode "issue=$KEY" \
  --data-urlencode "tags=" \
  "$SONAR/api/issues/set_tags" | jq
```

> **Ограничение тегов:** `^[a-z0-9\+#\-\.]+$`. Пробелы, кириллица, `_` → `400 Bad Request`. Именно эту проверку делает `isValidTag()` на фронте.

---

## 9. Measures — метрики проекта

Используется в `SonarMeasuresApi.fetch()`. Дефолтный список метрик (`DEFAULT_METRIC_KEYS`) — это то, что запрашивает официальный UI.

### 9.1. `GET /api/measures/component` — метрики

**Полный список метрик, который генерирует `DEFAULT_METRIC_KEYS`** (передаётся через `--data-urlencode`):

```bash
curl -s -H "$AUTH" -G "$SONAR/api/measures/component" \
  --data-urlencode "additionalFields=period,metrics" \
  --data-urlencode "component=$PROJ" \
  --data-urlencode "metricKeys=alert_status,quality_gate_details,new_violations,accepted_issues,new_accepted_issues,high_impact_accepted_issues,software_quality_maintainability_issues,software_quality_reliability_issues,software_quality_security_issues,bugs,new_bugs,reliability_rating,software_quality_reliability_rating,new_reliability_rating,new_software_quality_reliability_rating,vulnerabilities,new_vulnerabilities,security_rating,software_quality_security_rating,new_security_rating,new_software_quality_security_rating,security_hotspots,new_security_hotspots,security_hotspots_reviewed,new_security_hotspots_reviewed,security_review_rating,new_security_review_rating,code_smells,new_code_smells,sqale_rating,software_quality_maintainability_rating,new_maintainability_rating,new_software_quality_maintainability_rating,sqale_index,new_technical_debt,coverage,new_coverage,lines_to_cover,new_lines_to_cover,tests,duplicated_lines_density,new_duplicated_lines_density,duplicated_blocks,ncloc,ncloc_language_distribution,projects,lines,new_lines,violations" \
  | jq
```

```json
{
  "component": {
    "key": "MEDMIS-MISmobile.doctor_api_spec",
    "name": "MISmobile.doctor API",
    "qualifier": "TRK",
    "measures": [
      { "metric": "alert_status", "value": "OK", "period": {...} },
      { "metric": "violations", "value": "1437" },
      { "metric": "bugs", "value": "12" },
      { "metric": "code_smells", "value": "1422" },
      { "metric": "vulnerabilities", "value": "3" },
      { "metric": "security_hotspots", "value": "4" },
      { "metric": "coverage", "value": "12.3" },
      { "metric": "duplicated_lines_density", "value": "1.7" },
      { "metric": "ncloc", "value": "42000" },
      { "metric": "ncloc_language_distribution", "value": "php=42000" },
      { "metric": "software_quality_maintainability_issues", "value": "1422" },
      { "metric": "software_quality_reliability_issues", "value": "12" },
      { "metric": "software_quality_security_issues", "value": "3" },
      { "metric": "accepted_issues", "value": "187" },
      { "metric": "new_violations", "period": { "value": "0" } }
    ],
    "periods": [
      { "index": 1, "mode": "PREVIOUS_VERSION", "date": "2026-10-01T00:00:00+0000" }
    ]
  },
  "metrics": [ ... ]
}
```

**Только покрытие и дубликаты:**

```bash
curl -s -H "$AUTH" -G "$SONAR/api/measures/component" \
  --data-urlencode "additionalFields=period,metrics" \
  --data-urlencode "component=$PROJ" \
  --data-urlencode "metricKeys=coverage,duplicated_lines_density" | jq
```

---

## 10. Metadata — языки / ветки / PR / настройки

Все 4 запроса параллельно вызывает `MetadataHandler.handleRequest()` (каждый обёрнут в `try/catch`, при ошибке возвращается пустой `[]` / `{}`).

### 10.1. `GET /api/languages/list`

```bash
curl -s -H "$AUTH" "$SONAR/api/languages/list" | jq
```

```json
{
  "languages": [
    { "key": "php", "name": "PHP" },
    { "key": "js",  "name": "JavaScript" },
    { "key": "css", "name": "CSS" }
  ]
}
```

### 10.2. `GET /api/project_branches/list?project=KEY`

```bash
curl -s -H "$AUTH" -G "$SONAR/api/project_branches/list" \
  --data-urlencode "project=$PROJ" | jq
```

```json
{
  "branches": [
    {
      "name": "master",
      "isMain": true,
      "type": "BRANCH",
      "status": { "qualityGateStatus": "OK" },
      "analysisDate": "2026-11-10T00:00:00+0000"
    }
  ]
}
```

### 10.3. `GET /api/project_pull_requests/list?project=KEY`

```bash
curl -s -H "$AUTH" -G "$SONAR/api/project_pull_requests/list" \
  --data-urlencode "project=$PROJ" | jq
```

```json
{
  "pullRequests": [
    {
      "key": "42",
      "title": "Fix#123: user model refactor",
      "branch": "feature/x",
      "base": "master",
      "status": { "qualityGateStatus": "OK" },
      "analysisDate": "2026-11-09T00:00:00+0000"
    }
  ]
}
```

### 10.4. `GET /api/settings/values?keys=...`

Запрос конкретных настроек (используется, чтобы показать объявление из SonarQube в UI):

```bash
curl -s -H "$AUTH" -G "$SONAR/api/settings/values" \
  --data-urlencode "keys=sonar.announcement.displayMessage,sonar.announcement.message" | jq
```

```json
{
  "settings": [
    {
      "key": "sonar.announcement.message",
      "value": "Плановые работы в субботу",
      "inherited": false
    }
  ]
}
```

---

## 11. Projects — поиск проектов

Используется во втором проекте (`ProjectsApi`, `ProjectSearchApi`) и в тестах `Main.java`.

### 11.1. `GET /api/components/search?qualifiers=TRK` — проекты, на которые есть Browse

Безопасная замена `/api/projects/search` (не требует админ-прав).

```bash
curl -s -H "$AUTH" -G "$SONAR/api/components/search" \
  --data-urlencode "qualifiers=TRK" \
  --data-urlencode "p=1" \
  --data-urlencode "ps=100" | jq
```

```json
{
  "paging": { "pageIndex": 1, "pageSize": 100, "total": 338 },
  "components": [
    { "key": "MEDMIS-MISmobile.doctor_api_spec", "name": "MISmobile.doctor API", "qualifier": "TRK" }
  ]
}
```

С поиском:

```bash
curl -s -H "$AUTH" -G "$SONAR/api/components/search" \
  --data-urlencode "qualifiers=TRK" \
  --data-urlencode "q=MEDMIS" \
  --data-urlencode "ps=100" | jq
```

### 11.2. `GET /api/components/search_projects` — расширенный поиск с facets

Используется в `ProjectSearchApi.search()`.

```bash
curl -s -H "$AUTH" -G "$SONAR/api/components/search_projects" \
  --data-urlencode "p=1" \
  --data-urlencode "ps=50" \
  --data-urlencode "facets=software_quality_reliability_rating,software_quality_security_rating,software_quality_maintainability_rating,security_review_rating,coverage,duplicated_lines_density,ncloc,alert_status,languages,tags,qualifier" \
  --data-urlencode "f=analysisDate,leakPeriodDate" | jq
```

С фильтром (DSL-синтаксис):

```bash
curl -s -H "$AUTH" -G "$SONAR/api/components/search_projects" \
  --data-urlencode 'filter=query = "MEDMIS-MISmobile"' \
  --data-urlencode "p=1" --data-urlencode "ps=50" | jq
```

С точным фильтром по TRK:

```bash
curl -s -H "$AUTH" -G "$SONAR/api/components/search_projects" \
  --data-urlencode 'filter=qualifier = "TRK"' \
  --data-urlencode "p=1" --data-urlencode "ps=500" | jq
```

### 11.3. `GET /api/components/show?component=KEY` — точный lookup одного проекта

```bash
curl -s -H "$AUTH" -G "$SONAR/api/components/show" \
  --data-urlencode "component=$PROJ" | jq
```

```json
{
  "component": {
    "key": "MEDMIS-MISmobile.doctor_api_spec",
    "name": "MISmobile.doctor API",
    "qualifier": "TRK",
    "visibility": "private",
    "analysisDate": "2026-11-10T00:00:00+0000",
    "leakPeriodDate": "2026-10-01T00:00:00+0000"
  }
}
```

### 11.4. `GET /api/projects/search` — старый эндпоинт (требует прав)

```bash
curl -s -H "$AUTH" -G "$SONAR/api/projects/search" \
  --data-urlencode "p=1" \
  --data-urlencode "ps=100" | jq
```

Если у пользователя нет admin/Browse — вернёт `403 Forbidden`. `ProjectsApi.searchComponents()` — предпочтительная замена.

---

## 12. Quality Gates — гейты качества

Используется в `QualityGatesApi` (второй проект, пример).

### 12.1. `GET /api/qualitygates/list` — все гейты

```bash
curl -s -H "$AUTH" "$SONAR/api/qualitygates/list" | jq
```

```json
{
  "qualitygates": [
    { "id": "1", "name": "Sonar way", "isDefault": true, "isBuiltIn": true, "actions": {...} }
  ],
  "actions": { "create": true }
}
```

### 12.2. `GET /api/qualitygates/project_status?projectKey=KEY` — статус для проекта

```bash
curl -s -H "$AUTH" -G "$SONAR/api/qualitygates/project_status" \
  --data-urlencode "projectKey=$PROJ" | jq
```

```json
{
  "projectStatus": {
    "status": "OK",
    "conditions": [
      {
        "status": "OK",
        "metricKey": "new_coverage",
        "comparator": "LT",
        "errorThreshold": "80",
        "actualValue": "85.4"
      }
    ],
    "period": { "mode": "PREVIOUS_VERSION", "date": "2026-10-01T00:00:00+0000" }
  }
}
```

---

## Полный smoke-test напрямую в SonarQube

```bash
#!/usr/bin/env bash
set -e
SONAR=https://sonar.bars.group
TOKEN=squ_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
AUTH="Authorization: Bearer $TOKEN"
PROJ=MEDMIS-MISmobile.doctor_api_spec

echo "=== 1. System status ==="
curl -s -H "$AUTH" "$SONAR/api/system/status" | jq -r '.status, .version'

echo "=== 2. Issues — первый ключ проекта ==="
KEY=$(curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
    --data-urlencode "components=$PROJ" \
    --data-urlencode "issueStatuses=OPEN,CONFIRMED" \
    --data-urlencode "additionalFields=_all" \
    --data-urlencode "ps=1" | jq -r '.issues[0].key')
echo "KEY=$KEY"

echo "=== 3. Один issue ==="
curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
    --data-urlencode "issues=$KEY" \
    --data-urlencode "additionalFields=_all" | jq '.issues[0] | {key, rule, status, line}'

echo "=== 4. Rule name ==="
RULE=$(curl -s -H "$AUTH" -G "$SONAR/api/issues/search" \
    --data-urlencode "issues=$KEY" \
    --data-urlencode "additionalFields=_all" | jq -r '.issues[0].rule')
curl -s -H "$AUTH" -G "$SONAR/api/rules/show" \
    --data-urlencode "key=$RULE" | jq '.rule.name'

echo "=== 5. Snippet ==="
curl -s -H "$AUTH" -G "$SONAR/api/sources/issue_snippets" \
    --data-urlencode "issueKey=$KEY" | jq 'keys[0]'

echo "=== 6. Changelog ==="
curl -s -H "$AUTH" -G "$SONAR/api/issues/changelog" \
    --data-urlencode "issue=$KEY" | jq '.changelog | length'

echo "=== 7. Tags ==="
curl -s -H "$AUTH" -G "$SONAR/api/issues/tags" \
    --data-urlencode "ps=100" | jq '.tags | length'

echo "=== 8. Hotspots ==="
curl -s -H "$AUTH" -G "$SONAR/api/hotspots/search" \
    --data-urlencode "project=$PROJ" \
    --data-urlencode "status=TO_REVIEW" \
    --data-urlencode "ps=500" --data-urlencode "p=1" | jq '.paging.total'

echo "=== 9. Measures ==="
curl -s -H "$AUTH" -G "$SONAR/api/measures/component" \
    --data-urlencode "component=$PROJ" \
    --data-urlencode "metricKeys=alert_status,violations,code_smells,bugs,vulnerabilities,coverage,duplicated_lines_density" | jq \
    '.component.measures | map({(.metric): .value}) | add'

echo "=== 10. Languages ==="
curl -s -H "$AUTH" "$SONAR/api/languages/list" | jq '.languages | length'

echo "=== 11. Project branches ==="
curl -s -H "$AUTH" -G "$SONAR/api/project_branches/list" \
    --data-urlencode "project=$PROJ" | jq '.branches | length'

echo "=== 12. Project lookup ==="
curl -s -H "$AUTH" -G "$SONAR/api/components/show" \
    --data-urlencode "component=$PROJ" | jq '.component | {key, name, qualifier}'

echo "=== 13. Quality Gate ==="
curl -s -H "$AUTH" -G "$SONAR/api/qualitygates/project_status" \
    --data-urlencode "projectKey=$PROJ" | jq '.projectStatus.status'
```

---

## Соответствие URL и мест в коде

| URL SonarQube | Где вызывается |
|---|---|
| `GET /api/system/status` | `SystemApi.getStatus()` (пример второго проекта) |
| `GET /api/system/health` | `SystemApi.getHealth()` |
| `GET /api/issues/search` | `SonarIssuesApi.count/fetchAll/fetchOpen/fetchAccepted/facet/issuesByFile/fetchIssue` |
| `GET /api/issues/changelog` | `SonarIssuesApi.fetchChangelog` → `ChangelogHandler` |
| `GET /api/rules/show` | `SonarIssuesApi.fetchRuleName` |
| `GET /api/sources/issue_snippets` | `SonarIssuesApi.fetchSnippet` |
| `GET /api/issues/tags` | `SonarIssuesApi.fetchTags` → `TagsHandler`, `BuildPromptHandler`, `PromptBuilder` |
| `GET /api/hotspots/search` | `SonarIssuesApi.fetchHotspots` |
| `POST /api/issues/bulk_change` | `SonarBulkApi.change` → `MarkHandler`, `AutoProcessor`, `Console.cmdMarkBulk` |
| `POST /api/issues/do_transition` | `SonarBulkApi.doTransition` → `SonarBulkApi.reopenIssues` → `CancelHandler`, `MarkHandler` (reopen), `AutoProcessor` |
| `POST /api/issues/add_comment` | `SonarBulkApi.addComment` (метод есть, но сейчас не вызывается автоматически) |
| `POST /api/issues/set_tags` | `SonarBulkApi.setTags` → `SetTagsHandler`, `AutoProcessor` |
| `GET /api/measures/component` | `SonarMeasuresApi.fetch` → `MeasuresHandler` |
| `GET /api/languages/list` | `MetadataHandler.fetchLanguages` |
| `GET /api/project_branches/list` | `MetadataHandler.fetchBranches` |
| `GET /api/project_pull_requests/list` | `MetadataHandler.fetchPullRequests` |
| `GET /api/settings/values` | `MetadataHandler.fetchSettings` |
| `GET /api/components/search` | `ProjectsApi.searchComponents` |
| `GET /api/components/search_projects` | `ProjectSearchApi.search` |
| `GET /api/components/show` | `ProjectSearchApi.showComponent` |
| `GET /api/projects/search` | `ProjectsApi.searchProjects` |
| `GET /api/qualitygates/list` | `QualityGatesApi.listQualityGates` |
| `GET /api/qualitygates/project_status` | `QualityGatesApi.getProjectQualityGateStatus` |

## Замечания по пагинации и лимитам

| Эндпоинт | `ps` (макс.) | Как обрабатывает прокси |
|---|---|---|
| `/api/issues/search` | **500** | `fetchAllByStatuses` — цикл `p=1..N` до `out.size() == total` |
| `/api/hotspots/search` | **500** | `fetchHotspots` — 2 прохода (`TO_REVIEW` + `REVIEWED`), `safety: page > 100 break` |
| `/api/issues/tags` | **100** | `fetchTags` — один запрос `ps=100` |
| `/api/measures/component` | — | все метрики за один запрос |
| `/api/components/search` | **500** | `searchComponents` — один запрос (без пагинации) |
| `/api/components/search_projects` | **500** | `searchAll` — цикл `p=1..totalPages` |
| `/api/issues/bulk_change` | **500** | `SonarBulkApi.change` — батчи по 500 + `delayMs=500` |
| `/api/issues/do_transition` | 1 | `reopenIssues` — по одному + `REOPEN_DELAY_MS=120` |
| `/api/issues/set_tags` | 1 | `setTags` — по одному + `delayMs=50` |

## Коды ответов SonarQube

| Код | Что значит |
|---|---|
| `200` | Успех |
| `400` | Неверные параметры (например, `issueStatuses=OPEN,REOPENED` для старых версий, где `REOPENED` нет) |
| `401` | Токен невалиден / истёк → `SonarClient.SessionExpiredException` |
| `403` | Токен валиден, но нет прав (например, `Administer System` для `/api/projects/search`) |
| `404` | Issue/rule/component не найден |

## Ловушки SonarQube API

1. **`REOPENED`** в `issueStatuses` — в SonarQube 9.x/10.x такого статуса **нет**, переоткрытый issue = `OPEN`. `fetchOpen()` пробует `"OPEN,REOPENED"`, при ошибке — откатывается на `"OPEN"`.
2. **`SECURITY_HOTSPOT`** — это **не** `qual`, а отдельный API (`/api/hotspots/search`). `qual` в `/api/issues/search` принимает `MAINTAINABILITY | RELIABILITY | SECURITY`.
3. **`vulnerabilityProbability`** у Hotspots — это `severity` (`HIGH | MEDIUM | LOW`), фильтр на **клиенте** (серверной фильтрации нет).
4. **`add_comment`** принимает `text`, а `bulk_change` — `comment` (прикрепляется к переходу, не отдельным комментарием).
5. **`reopen`** через `bulk_change` работает не везде — используйте `do_transition` per-issue.
6. **`set_tags`** принимает **только одну** issue за вызов; массовость — на клиенте.
7. **`additionalFields=_all`** обязателен, иначе `impacts[]` не придёт (только legacy `severity`).
8. **`timeZone=Asia/Novosibirsk`** — жёстко прописан в `SonarIssuesApi.buildQueryWithStatuses()`; влияет на `creationDate`/`updateDate` в ответе.