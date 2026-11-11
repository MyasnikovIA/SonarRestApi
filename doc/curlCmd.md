# Прямые запросы к SonarQube через `curl.exe` из `cmd.exe` (Windows 10)

`jq` не нужен. Все примеры — **чистый `cmd.exe`**, `curl.exe` уже есть в Windows 10 (`C:\Windows\System32\curl.exe`). Ответы SonarQube приходят в JSON — их можно читать глазами, либо (для цепочек запросов) извлекать отдельные поля через `findstr`.

---

## 0. Подготовка

### 0.1. Проверить `curl.exe`

Откройте **`cmd.exe`** (Win+R → `cmd`):

```cmd
curl --version
```

Ожидаемый вывод: `curl 8.x.x (Windows) ...`. Если «не является внутренней командой» — установите: <https://curl.se/windows/> → скачайте zip → распакуйте `bin\curl.exe` в `C:\Windows\System32\`.

### 0.2. Задать переменные окружения

```cmd
set SONAR=https://sonar.bars.group
set TOKEN=squ_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
set PROJ=MEDMIS-MISmobile.doctor_api_spec
```

**Правила `set` в cmd.exe:**
- Без пробелов вокруг `=`.
- Использование — `%VAR%` (например `%SONAR%`).
- Если в значении есть `&`, `|`, `<`, `>` — оборачивайте всё в двойные кавычки: `set "VAR=a&b"`.
- Переменные **живут только в текущей консоли**. При закрытии окна — пропадут.

### 0.3. Ключевые правила синтаксиса cmd.exe

| Что | Синтаксис |
|---|---|
| Переменные | `%SONAR%`, `%TOKEN%` |
| Перенос длинной строки | `^` в конце строки, **без пробелов после него** |
| Кавычки для аргументов с `=` | `--data-urlencode "components=%PROJ%"` |
| Перенаправить вывод в файл | `> "%TEMP%\out.json"` |
| Отбросить тело, оставить только HTTP-код | `-o NUL -w "%{http_code}"` |

---

## 1. Аутентификация

Один заголовок для всех запросов:

```cmd
-H "Authorization: Bearer %TOKEN%"
-H "Accept: application/json"
```

**Проверка токена:**

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/system/status"
```

Ожидаемый ответ (JSON одной строкой, читается глазами):

```json
{"id":"AV8xxx","version":"10.6.0.92116","status":"UP"}
```

Если токен невалиден — придёт `{"errors":[{"msg":"Authentication required"}]}`.

---

## 2. System — проверка живости

### 2.1. `GET /api/system/status`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/system/status"
```

```json
{"id":"AV8xxx","version":"10.6.0.92116","status":"UP"}
```

**Только HTTP-код** (200/401/403):

```cmd
curl -s -o NUL -w "%{http_code}" -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/system/status"
```

### 2.2. `GET /api/system/health`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/system/health"
```

```json
{"health":"GREEN","causes":[]}
```

---

## 3. Issues — поиск и данные issue

### 3.1. `GET /api/issues/search` — issues проекта

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "components=%PROJ%" ^
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" ^
  --data-urlencode "additionalFields=_all" ^
  --data-urlencode "ps=500" ^
  --data-urlencode "p=1"
```

Ответ (фрагмент, одной строкой в реальности):

```json
{
  "total": 1437, "p": 1, "ps": 500,
  "paging": { "pageIndex": 1, "pageSize": 500, "total": 1437 },
  "issues": [
    {
      "key": "30637c2f-1a4b-4d5e-8f90-abcdef123456",
      "rule": "php:S1192",
      "severity": "MINOR",
      "component": "MEDMIS-MISmobile.doctor_api_spec:src/Model/User.php",
      "line": 27,
      "status": "OPEN",
      "message": "Define a constant instead of duplicating this literal \"admin\" 3 times.",
      "effort": "5min",
      "type": "CODE_SMELL",
      "impacts": [{ "softwareQuality": "MAINTAINABILITY", "severity": "LOW" }]
    }
  ]
}
```

> **Как получить KEY issue** — найдите в ответе первую подстроку `"key":"..."`. Всё, что между кавычками — это ключ. Скопируйте его:

```cmd
set KEY=30637c2f-1a4b-4d5e-8f90-abcdef123456
```

**Сохранить ответ в файл** (если JSON слишком большой для консоли):

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "components=%PROJ%" ^
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" ^
  --data-urlencode "additionalFields=_all" ^
  --data-urlencode "ps=500" ^
  --data-urlencode "p=1" > "%TEMP%\issues.json"

notepad "%TEMP%\issues.json"
```

**Посчитать total** (только число из ответа, без парсинга):

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "components=%PROJ%" ^
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" ^
  --data-urlencode "ps=1" > "%TEMP%\total.json"

findstr /r /c:"\"total\":[0-9]*" "%TEMP%\total.json"
```

`findstr` напечатает всю строку — вы увидите `"total":1437,` в начале.

**Фильтры (примеры):**

```cmd
rem Только HIGH
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "components=%PROJ%" ^
  --data-urlencode "impactSeverities=HIGH" ^
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" ^
  --data-urlencode "additionalFields=_all" ^
  --data-urlencode "ps=500" ^
  --data-urlencode "p=1"

rem Только SECURITY
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "components=%PROJ%" ^
  --data-urlencode "impactSoftwareQualities=SECURITY" ^
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" ^
  --data-urlencode "additionalFields=_all" ^
  --data-urlencode "ps=500" ^
  --data-urlencode "p=1"

rem Только ACCEPTED
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "components=%PROJ%" ^
  --data-urlencode "issueStatuses=ACCEPTED" ^
  --data-urlencode "additionalFields=_all" ^
  --data-urlencode "ps=500" ^
  --data-urlencode "p=1"

rem Только OPEN/REOPENED
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "components=%PROJ%" ^
  --data-urlencode "issueStatuses=OPEN,REOPENED" ^
  --data-urlencode "additionalFields=_all" ^
  --data-urlencode "ps=500" ^
  --data-urlencode "p=1"
```

### 3.2. `GET /api/issues/search?issues=KEY` — один issue по ключу

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "issues=%KEY%" ^
  --data-urlencode "additionalFields=_all"
```

Возвращает `issues:[{...}]` с полными данными одного issue (тот же формат, что выше).

### 3.3. `GET /api/issues/changelog?issue=KEY`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/changelog" ^
  --data-urlencode "issue=%KEY%"
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
        { "key": "resolution", "oldValue": "", "newValue": "WONTFIX" }
      ]
    }
  ]
}
```

---

## 4. Rules — имя правила

### 4.1. `GET /api/rules/show?key=RULE`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/rules/show" ^
  --data-urlencode "key=php:S1192"
```

```json
{
  "rule": {
    "key": "php:S1192",
    "name": "String literals should not be duplicated",
    "lang": "php",
    "severity": "MINOR"
  }
}
```

> **Как получить RULE** — в ответе `/api/issues/search` найдите `"rule":"..."` в объекте issue. Это ключ правила.

---

## 5. Sources — фрагменты кода

### 5.1. `GET /api/sources/issue_snippets?issueKey=KEY`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/sources/issue_snippets" ^
  --data-urlencode "issueKey=%KEY%"
```

```json
{
  "MEDMIS-MISmobile.doctor_api_spec:src/Model/User.php": {
    "sources": [
      { "line": 25, "code": "    public function setRole($role) {" },
      { "line": 26, "code": "        $this-&gt;container['role'] = 'admin';" },
      { "line": 27, "code": "        $this-&gt;container['status'] = '&lt;mark class=\"sonar-issue-mark\"&gt;admin&lt;/mark&gt;';" }
    ]
  }
}
```

> `&gt;` / `&lt;` / `&amp;` — HTML-entities, при чтении заменяйте на `>` / `<` / `&`. Проблемная строка выделена `<mark class="sonar-issue-mark">…</mark>`.

**Сохранить сниппет в файл для просмотра:**

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/sources/issue_snippets" ^
  --data-urlencode "issueKey=%KEY%" > "%TEMP%\snippet.json"
notepad "%TEMP%\snippet.json"
```

### 5.2. Несуществующий ключ

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/sources/issue_snippets" ^
  --data-urlencode "issueKey=invalid-key"
```

Возвращает `{}`.

---

## 6. Tags

### 6.1. Все теги

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/tags" ^
  --data-urlencode "ps=100"
```

```json
{
  "tags": ["CWE", "code-duplication", "code-smell", "not-require-fix", "require-fix", "security"]
}
```

### 6.2. По префиксу

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/tags" ^
  --data-urlencode "ps=100" ^
  --data-urlencode "q=require"
```

```json
{ "tags": ["not-require-fix", "require-fix"] }
```

---

## 7. Hotspots — Security Hotspots

### 7.1. `TO_REVIEW`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/hotspots/search" ^
  --data-urlencode "project=%PROJ%" ^
  --data-urlencode "status=TO_REVIEW" ^
  --data-urlencode "ps=500" ^
  --data-urlencode "p=1"
```

```json
{
  "paging": { "pageIndex": 1, "pageSize": 500, "total": 4 },
  "hotspots": [
    {
      "key": "abc-123",
      "component": "MEDMIS-MISmobile.doctor_api_spec:src/Config/security.yml",
      "vulnerabilityProbability": "HIGH",
      "status": "TO_REVIEW",
      "line": 12,
      "message": "Make sure this weak hash algorithm is not used here.",
      "ruleKey": "php:S4790"
    }
  ]
}
```

### 7.2. `REVIEWED`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/hotspots/search" ^
  --data-urlencode "project=%PROJ%" ^
  --data-urlencode "status=REVIEWED" ^
  --data-urlencode "ps=500" ^
  --data-urlencode "p=1"
```

---

## 8. Bulk operations

Все — `POST`, `Content-Type: application/x-www-form-urlencoded`.

### 8.1. `POST /api/issues/bulk_change` — статус + комментарий

```cmd
curl -s -X POST -H "Authorization: Bearer %TOKEN%" ^
  --data-urlencode "issues=%KEY%" ^
  --data-urlencode "do_transition=accept" ^
  --data-urlencode "comment=Техдолг на следующий спринт" ^
  "%SONAR%/api/issues/bulk_change"
```

```json
{ "total": 1, "success": 1, "ignored": 0, "failures": 0 }
```

**Массово:**

```cmd
curl -s -X POST -H "Authorization: Bearer %TOKEN%" ^
  --data-urlencode "issues=KEY1,KEY2,KEY3" ^
  --data-urlencode "do_transition=confirm" ^
  --data-urlencode "comment=Critical" ^
  "%SONAR%/api/issues/bulk_change"
```

> ⚠ SonarQube **применит** изменения. Dry-run недоступен.

**Допустимые `do_transition`:** `accept`, `falsepositive`, `confirm`, `resolve`, `wontfix`.

### 8.2. `POST /api/issues/do_transition` — одиночный переход (включая reopen)

```cmd
curl -s -X POST -H "Authorization: Bearer %TOKEN%" ^
  --data-urlencode "issue=%KEY%" ^
  --data-urlencode "transition=reopen" ^
  "%SONAR%/api/issues/do_transition"
```

```json
{ "issue": { "key": "30637c2f-...", "status": "REOPENED" } }
```

### 8.3. `POST /api/issues/add_comment`

```cmd
curl -s -X POST -H "Authorization: Bearer %TOKEN%" ^
  --data-urlencode "issue=%KEY%" ^
  --data-urlencode "text=Комментарий из cmd.exe" ^
  "%SONAR%/api/issues/add_comment"
```

### 8.4. `POST /api/issues/set_tags`

```cmd
curl -s -X POST -H "Authorization: Bearer %TOKEN%" ^
  --data-urlencode "issue=%KEY%" ^
  --data-urlencode "tags=require-fix,code-smell" ^
  "%SONAR%/api/issues/set_tags"
```

```json
{ "issue": { "key": "30637c2f-...", "tags": ["require-fix", "code-smell"] } }
```

**Очистить теги:**

```cmd
curl -s -X POST -H "Authorization: Bearer %TOKEN%" ^
  --data-urlencode "issue=%KEY%" ^
  --data-urlencode "tags=" ^
  "%SONAR%/api/issues/set_tags"
```

> **Ограничение тегов:** `^[a-z0-9\+#\-\.]+$`. Пробелы, кириллица, `_` → `400 Bad Request`.

---

## 9. Measures — метрики проекта

### 9.1. Полный набор метрик

```cmd
set METRICS=alert_status,violations,code_smells,bugs,vulnerabilities,coverage,duplicated_lines_density,ncloc,accepted_issues

curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/measures/component" ^
  --data-urlencode "additionalFields=period,metrics" ^
  --data-urlencode "component=%PROJ%" ^
  --data-urlencode "metricKeys=%METRICS%"
```

```json
{
  "component": {
    "key": "MEDMIS-MISmobile.doctor_api_spec",
    "measures": [
      { "metric": "alert_status", "value": "OK" },
      { "metric": "violations", "value": "1437" },
      { "metric": "bugs", "value": "12" },
      { "metric": "code_smells", "value": "1422" },
      { "metric": "vulnerabilities", "value": "3" },
      { "metric": "coverage", "value": "12.3" },
      { "metric": "duplicated_lines_density", "value": "1.7" },
      { "metric": "ncloc", "value": "42000" },
      { "metric": "accepted_issues", "value": "187" }
    ]
  }
}
```

**Только покрытие и дубликаты:**

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/measures/component" ^
  --data-urlencode "component=%PROJ%" ^
  --data-urlencode "metricKeys=coverage,duplicated_lines_density"
```

---

## 10. Metadata

### 10.1. `GET /api/languages/list`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/languages/list"
```

```json
{ "languages": [ { "key": "php", "name": "PHP" }, { "key": "js", "name": "JavaScript" } ] }
```

### 10.2. `GET /api/project_branches/list?project=KEY`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/project_branches/list" ^
  --data-urlencode "project=%PROJ%"
```

### 10.3. `GET /api/project_pull_requests/list?project=KEY`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/project_pull_requests/list" ^
  --data-urlencode "project=%PROJ%"
```

### 10.4. `GET /api/settings/values?keys=...`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/settings/values" ^
  --data-urlencode "keys=sonar.announcement.displayMessage,sonar.announcement.message"
```

---

## 11. Projects

### 11.1. `GET /api/components/search?qualifiers=TRK`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/components/search" ^
  --data-urlencode "qualifiers=TRK" ^
  --data-urlencode "p=1" ^
  --data-urlencode "ps=100"
```

```json
{
  "paging": { "pageIndex": 1, "pageSize": 100, "total": 338 },
  "components": [ { "key": "MEDMIS-MISmobile.doctor_api_spec", "name": "MISmobile.doctor API", "qualifier": "TRK" } ]
}
```

**С поиском:**

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/components/search" ^
  --data-urlencode "qualifiers=TRK" ^
  --data-urlencode "q=MEDMIS" ^
  --data-urlencode "ps=100"
```

### 11.2. `GET /api/components/search_projects`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/components/search_projects" ^
  --data-urlencode "p=1" ^
  --data-urlencode "ps=50" ^
  --data-urlencode "f=analysisDate,leakPeriodDate"
```

**С фильтром:**

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/components/search_projects" ^
  --data-urlencode "filter=qualifier = \"TRK\"" ^
  --data-urlencode "p=1" ^
  --data-urlencode "ps=500"
```

> `\"TRK\"` внутри `"..."` — экранированные кавычки для cmd.exe. Работает.

### 11.3. `GET /api/components/show?component=KEY`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/components/show" ^
  --data-urlencode "component=%PROJ%"
```

```json
{
  "component": {
    "key": "MEDMIS-MISmobile.doctor_api_spec",
    "name": "MISmobile.doctor API",
    "qualifier": "TRK",
    "visibility": "private",
    "analysisDate": "2026-11-10T00:00:00+0000"
  }
}
```

### 11.4. `GET /api/projects/search` (требует admin/Browse)

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/projects/search" ^
  --data-urlencode "p=1" ^
  --data-urlencode "ps=100"
```

Если нет прав — `403 Forbidden`. Используйте `/api/components/search` вместо него.

---

## 12. Quality Gates

### 12.1. `GET /api/qualitygates/list`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/qualitygates/list"
```

### 12.2. `GET /api/qualitygates/project_status?projectKey=KEY`

```cmd
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/qualitygates/project_status" ^
  --data-urlencode "projectKey=%PROJ%"
```

```json
{
  "projectStatus": {
    "status": "OK",
    "conditions": [
      { "status": "OK", "metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "85.4" }
    ]
  }
}
```

---

## 13. Как извлечь KEY в batch-файле (для цепочек)

В интерактивном `cmd.exe` проще скопировать `KEY` из вывода `/api/issues/search` вручную. В **batch-файле** можно автоматизировать через `findstr`:

```bat
@echo off
setlocal enabledelayedexpansion

set SONAR=https://sonar.bars.group
set TOKEN=squ_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
set PROJ=MEDMIS-MISmobile.doctor_api_spec

rem 1. Сохраняем ответ в файл
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "components=%PROJ%" ^
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" ^
  --data-urlencode "additionalFields=_all" ^
  --data-urlencode "ps=1" > "%TEMP%\issue.json"

rem 2. Ищем первую подстроку "key":"..." (это ключ issue)
rem    findstr /r ищет строку с "key":"<hex>-..." — ключи SonarQube содержат дефисы
for /f "tokens=2 delims=:" %%a in ('findstr /r /c:"\"key\":\"[0-9a-f][0-9a-f]*-" "%TEMP%\issue.json"') do (
    if not defined KEY set "KEY=%%a"
)

rem 3. Убираем всё после второй кавычки и сами кавычки
if defined KEY (
    for /f "tokens=1 delims=," %%b in ("!KEY!") do set "KEY=%%b"
    set "KEY=!KEY:"=!"
)

echo KEY=!KEY!

rem Дальше можно использовать !KEY! в следующих запросах
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/changelog" ^
  --data-urlencode "issue=!KEY!"

endlocal
```

> `enabledelayedexpansion` нужен, чтобы переменная, установленная внутри `for`, была видна сразу (через `!KEY!`, а не `%KEY%`). В **интерактивном** cmd по умолчанию это выключено — там используйте `%KEY%` (но тогда логика с `for` внутри `for` потребует `setlocal enabledelayedexpansion` или запуск через `cmd /v:on`).

---

## 14. Полный smoke-test (batch-файл)

Сохраните как **`smoke.bat`**, отредактируйте `TOKEN`/`PROJ`, запустите двойным кликом:

```bat
@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

set SONAR=https://sonar.bars.group
set TOKEN=squ_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
set PROJ=MEDMIS-MISmobile.doctor_api_spec

echo ============================================================
echo 1. System status
echo ============================================================
curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/system/status"
echo.
echo.

echo ============================================================
echo 2. Issues — total + first 3 keys
echo ============================================================
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" ^
  --data-urlencode "components=%PROJ%" ^
  --data-urlencode "issueStatuses=CONFIRMED,OPEN" ^
  --data-urlencode "additionalFields=_all" ^
  --data-urlencode "ps=3" > "%TEMP%\issues.json"
type "%TEMP%\issues.json"
echo.
echo.

echo ============================================================
echo 3. Tags
echo ============================================================
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/tags" --data-urlencode "ps=100"
echo.
echo.

echo ============================================================
echo 4. Hotspots TO_REVIEW
echo ============================================================
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/hotspots/search" ^
  --data-urlencode "project=%PROJ%" ^
  --data-urlencode "status=TO_REVIEW" ^
  --data-urlencode "ps=500" --data-urlencode "p=1"
echo.
echo.

echo ============================================================
echo 5. Measures
echo ============================================================
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/measures/component" ^
  --data-urlencode "component=%PROJ%" ^
  --data-urlencode "metricKeys=alert_status,violations,code_smells,bugs,vulnerabilities,coverage,duplicated_lines_density,ncloc"
echo.
echo.

echo ============================================================
echo 6. Languages
echo ============================================================
curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/languages/list"
echo.
echo.

echo ============================================================
echo 7. Branches
echo ============================================================
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/project_branches/list" --data-urlencode "project=%PROJ%"
echo.
echo.

echo ============================================================
echo 8. Project info
echo ============================================================
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/components/show" --data-urlencode "component=%PROJ%"
echo.
echo.

echo ============================================================
echo 9. Quality Gate
echo ============================================================
curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/qualitygates/project_status" --data-urlencode "projectKey=%PROJ%"
echo.
echo.

endlocal
pause
```

> `chcp 65001 >nul` — переключает консоль в UTF-8, чтобы русские символы в JSON отображались корректно. Без этого в логах будет «кракозябра».

---

## 15. Шпаргалка: все URL

| Метод | URL | Одна строка |
|---|---|---|
| GET | `/api/system/status` | `curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/system/status"` |
| GET | `/api/system/health` | `curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/system/health"` |
| GET | `/api/issues/search` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/search" --data-urlencode "components=%PROJ%" --data-urlencode "additionalFields=_all"` |
| GET | `/api/issues/changelog` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/changelog" --data-urlencode "issue=%KEY%"` |
| GET | `/api/rules/show` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/rules/show" --data-urlencode "key=%RULE%"` |
| GET | `/api/sources/issue_snippets` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/sources/issue_snippets" --data-urlencode "issueKey=%KEY%"` |
| GET | `/api/issues/tags` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/issues/tags" --data-urlencode "ps=100"` |
| GET | `/api/hotspots/search` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/hotspots/search" --data-urlencode "project=%PROJ%" --data-urlencode "status=TO_REVIEW"` |
| POST | `/api/issues/bulk_change` | `curl -s -X POST -H "Authorization: Bearer %TOKEN%" --data-urlencode "issues=%KEY%" --data-urlencode "do_transition=accept" "%SONAR%/api/issues/bulk_change"` |
| POST | `/api/issues/do_transition` | `curl -s -X POST -H "Authorization: Bearer %TOKEN%" --data-urlencode "issue=%KEY%" --data-urlencode "transition=reopen" "%SONAR%/api/issues/do_transition"` |
| POST | `/api/issues/add_comment` | `curl -s -X POST -H "Authorization: Bearer %TOKEN%" --data-urlencode "issue=%KEY%" --data-urlencode "text=..." "%SONAR%/api/issues/add_comment"` |
| POST | `/api/issues/set_tags` | `curl -s -X POST -H "Authorization: Bearer %TOKEN%" --data-urlencode "issue=%KEY%" --data-urlencode "tags=a,b" "%SONAR%/api/issues/set_tags"` |
| GET | `/api/measures/component` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/measures/component" --data-urlencode "component=%PROJ%" --data-urlencode "metricKeys=..."` |
| GET | `/api/languages/list` | `curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/languages/list"` |
| GET | `/api/project_branches/list` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/project_branches/list" --data-urlencode "project=%PROJ%"` |
| GET | `/api/project_pull_requests/list` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/project_pull_requests/list" --data-urlencode "project=%PROJ%"` |
| GET | `/api/settings/values` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/settings/values" --data-urlencode "keys=..."` |
| GET | `/api/components/search` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/components/search" --data-urlencode "qualifiers=TRK"` |
| GET | `/api/components/search_projects` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/components/search_projects" --data-urlencode "p=1" --data-urlencode "ps=50"` |
| GET | `/api/components/show` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/components/show" --data-urlencode "component=%PROJ%"` |
| GET | `/api/projects/search` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/projects/search" --data-urlencode "p=1" --data-urlencode "ps=100"` |
| GET | `/api/qualitygates/list` | `curl -s -H "Authorization: Bearer %TOKEN%" "%SONAR%/api/qualitygates/list"` |
| GET | `/api/qualitygates/project_status` | `curl -s -H "Authorization: Bearer %TOKEN%" -G "%SONAR%/api/qualitygates/project_status" --data-urlencode "projectKey=%PROJ%"` |

---

## 16. Типичные ошибки в cmd.exe

| Симптом | Причина | Решение |
|---|---|---|
| `curl: (6) Could not resolve host` | `%SONAR%` не задан | `echo %SONAR%` — должно вывести URL |
| `curl: (3) URL using bad/illegal format` | Неэкранированный `&` в URL | Только `--data-urlencode` + `-G` |
| `'curl' is not recognized` | Не установлен | Скачайте <https://curl.se/windows/>, положите в `C:\Windows\System32\` |
| `401 Unauthorized` | Токен невалиден / истёк | Новый токен: `<SONAR>/account/security` |
| `403 Forbidden` | Нет прав (например `/api/projects/search`) | Используйте `/api/components/search` |
| `400 Bad Request` при `bulk_change` | Недопустимый `do_transition` для текущего статуса | Сначала `GET /api/issues/search?issues=KEY`, посмотрите `transitions[]` |
| `^` съедает следующую строку | Пробел после `^` | Уберите **все** пробелы после `^` |
| `!VAR!` не раскрывается | `enabledelayedexpansion` не включён | `setlocal enabledelayedexpansion` в batch; или `cmd /v:on` |
| Кракозябры в выводе с русскими | Консоль не в UTF-8 | `chcp 65001` (один раз за сессию) |
| Переменная обрезается на `&` | `set` без кавычек | `set "VAR=значение с &"` (кавычки вокруг всего) |
| `findstr` не находит строку | JSON пришёл в одну строку без переносов | Это норма — `findstr` возвращает всю строку целиком; читайте её глазами |