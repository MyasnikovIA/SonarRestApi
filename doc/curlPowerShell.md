# Прямые запросы к SonarQube через `curl.exe` из **PowerShell** (Windows 10)

В PowerShell 5.1 (стандартный на Win10) команда `curl` — это **алиас** для `Invoke-WebRequest`, а не `curl`. Поэтому в примерах всегда используется **`curl.exe`** — реальный бинарник из `C:\Windows\System32\curl.exe`. Ответы SonarQube — JSON, разбираются встроенным **`ConvertFrom-Json`** (ничего доустанавливать не надо).

---

## 0. Подготовка

### 0.1. Проверить `curl.exe`

Откройте **Windows PowerShell** (Win+R → `powershell`):

```powershell
curl.exe --version
```

Ожидаемо: `curl 8.x.x (Windows) ...`. Если не работает — установите <https://curl.se/windows/>, распакуйте `bin\curl.exe` в `C:\Windows\System32\`.

### 0.2. Задать переменные

```powershell
$SONAR = "https://sonar.bars.group"
$TOKEN = "squ_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
$PROJ  = "MEDMIS-MISmobile.doctor_api_spec"
$AUTH  = "Authorization: Bearer $TOKEN"
```

- `$VAR` — с `$` впереди, без `%`.
- Переменные **живут в текущей сессии** PowerShell. При закрытии окна — пропадут.
- Чтобы сохранить между сессиями — `$env:SONAR = "..."` (тогда `$env:SONAR` доступен и в cmd).

### 0.3. Ключевые правила PowerShell

| Что | Синтаксис |
|---|---|
| Переменная | `$SONAR` |
| Внутри двойных кавычек | `"$SONAR/api/system/status"` (раскрывается) |
| Внутри одинарных кавычек | `'$SONAR/api/system/status'` (НЕ раскрывается) |
| Перенос длинной строки | backtick `` ` `` в конце строки, **без пробелов после него** |
| Парсинг JSON | `\| ConvertFrom-Json` |
| Поле объекта | `$obj.field` или `$obj["field"]` |
| Все элементы массива | `$obj.arr \| ForEach-Object { $_ }` |
| Плоский список одного поля | `$obj.arr \| ForEach-Object { $_.key }` |
| Первые N | `$obj.arr \| Select-Object -First N` |
| Фильтр | `$obj.arr \| Where-Object { $_.status -eq "OPEN" }` |
| Длина массива | `@($obj.arr).Count` (обязательно `@()`) |
| Отбросить тело, показать HTTP-код | `curl.exe -s -o NUL -w "%{http_code}"` |
| Отправить в файл | `\| Out-File -Encoding utf8 file.json` |

**Самые частые грабли:**

1. `curl` (без `.exe`) в PowerShell 5.1 — **это не curl**. Всегда `curl.exe`.
2. Внутри `"..."` знак `` ` `` (backtick) — это escape-символ. Для буквального backtick — двойной `` `` ``.
3. Двойные кавычки внутри `"..."` — экранировать через `` `" `` **или** использовать внешние одинарные кавычки `'...'`.
4. `$obj.arr.Count` при одном элементе вернёт `$null`. Всегда `@($obj.arr).Count`.

---

## 1. Аутентификация

Заголовок один и тот же для всех запросов:

```powershell
$AUTH = "Authorization: Bearer $TOKEN"
```

**Проверка токена:**

```powershell
curl.exe -s -H $AUTH "$SONAR/api/system/status"
```

Ответ — JSON:

```json
{"id":"AV8xxx","version":"10.6.0.92116","status":"UP"}
```

**Разбор через ConvertFrom-Json:**

```powershell
$r = curl.exe -s -H $AUTH "$SONAR/api/system/status" | ConvertFrom-Json
$r.status     # UP
$r.version    # 10.6.0.92116
```

Если токен невалиден — тело `{"errors":[{"msg":"Authentication required"}]}`, HTTP-код 401.

---

## 2. System — проверка живости

### 2.1. `GET /api/system/status`

```powershell
curl.exe -s -H $AUTH "$SONAR/api/system/status" | ConvertFrom-Json |
    Format-List id, version, status
```

```text
id      : AV8xxx
version : 10.6.0.92116
status  : UP
```

**Только HTTP-код** (для быстрой проверки):

```powershell
curl.exe -s -o NUL -w "%{http_code}" -H $AUTH "$SONAR/api/system/status"
# 200
```

### 2.2. `GET /api/system/health`

```powershell
curl.exe -s -H $AUTH "$SONAR/api/system/health" | ConvertFrom-Json |
    Format-List health, causes
```

```text
health : GREEN
causes : {}
```

---

## 3. Issues — поиск и данные

### 3.1. `GET /api/issues/search` — issues проекта

```powershell
$resp = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "components=$PROJ" `
    --data-urlencode "issueStatuses=CONFIRMED,OPEN" `
    --data-urlencode "additionalFields=_all" `
    --data-urlencode "ps=500" `
    --data-urlencode "p=1" | ConvertFrom-Json

$resp.paging | Format-List
```

```text
pageIndex : 1
pageSize  : 500
total     : 1437
```

**Первые 3 issue:**

```powershell
$resp.issues | Select-Object -First 3 |
    Format-Table key, rule, status, line, severity, component -AutoSize
```

```text
key                                  rule       status line severity component
---                                  ----       ------ ---- -------- ---------
30637c2f-1a4b-...                    php:S1192  OPEN     27  MINOR   src/Model/User.php
41738d3f-2b5c-...                    php:S3776  OPEN     55  MAJOR   src/Model/AddDirsItems.php
...
```

**Фильтры:**

```powershell
# Только HIGH
$resp = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "components=$PROJ" `
    --data-urlencode "impactSeverities=HIGH" `
    --data-urlencode "issueStatuses=CONFIRMED,OPEN" `
    --data-urlencode "additionalFields=_all" `
    --data-urlencode "ps=500" `
    --data-urlencode "p=1" | ConvertFrom-Json
$resp.paging.total

# Только SECURITY
$resp = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "components=$PROJ" `
    --data-urlencode "impactSoftwareQualities=SECURITY" `
    --data-urlencode "issueStatuses=CONFIRMED,OPEN" `
    --data-urlencode "additionalFields=_all" `
    --data-urlencode "ps=500" `
    --data-urlencode "p=1" | ConvertFrom-Json

# Только ACCEPTED
$resp = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "components=$PROJ" `
    --data-urlencode "issueStatuses=ACCEPTED" `
    --data-urlencode "additionalFields=_all" `
    --data-urlencode "ps=500" `
    --data-urlencode "p=1" | ConvertFrom-Json

# Только OPEN/REOPENED
$resp = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "components=$PROJ" `
    --data-urlencode "issueStatuses=OPEN,REOPENED" `
    --data-urlencode "additionalFields=_all" `
    --data-urlencode "ps=500" `
    --data-urlencode "p=1" | ConvertFrom-Json
```

**Сохранить ответ в файл:**

```powershell
curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "components=$PROJ" `
    --data-urlencode "issueStatuses=CONFIRMED,OPEN" `
    --data-urlencode "additionalFields=_all" `
    --data-urlencode "ps=500" `
    --data-urlencode "p=1" | Out-File -Encoding utf8 "$env:TEMP\issues.json"

notepad "$env:TEMP\issues.json"
```

### 3.2. `GET /api/issues/search?issues=KEY` — один issue

**Получить KEY первого issue:**

```powershell
$resp = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "components=$PROJ" `
    --data-urlencode "issueStatuses=CONFIRMED,OPEN" `
    --data-urlencode "additionalFields=_all" `
    --data-urlencode "ps=1" | ConvertFrom-Json

$KEY = $resp.issues[0].key
Write-Host "KEY = $KEY"
```

**Запросить этот issue:**

```powershell
$one = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "issues=$KEY" `
    --data-urlencode "additionalFields=_all" | ConvertFrom-Json

$one.issues[0] | Format-List key, rule, status, line, severity, message, effort, type
```

```text
key      : 30637c2f-1a4b-4d5e-8f90-abcdef123456
rule     : php:S1192
status   : OPEN
line     : 27
severity : MINOR
message  : Define a constant instead of duplicating this literal "admin" 3 times.
effort   : 5min
type     : CODE_SMELL
```

**Все ключи проекта (плоский список):**

```powershell
$all = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "components=$PROJ" `
    --data-urlencode "issueStatuses=CONFIRMED,OPEN" `
    --data-urlencode "additionalFields=_all" `
    --data-urlencode "ps=500" | ConvertFrom-Json

$all.issues | ForEach-Object { $_.key }
```

**Сохранить ключи в файл:**

```powershell
$all.issues | ForEach-Object { $_.key } |
    Out-File -Encoding utf8 "$env:TEMP\keys.txt"
```

### 3.3. `GET /api/issues/changelog?issue=KEY`

```powershell
$ch = curl.exe -s -H $AUTH -G "$SONAR/api/issues/changelog" `
    --data-urlencode "issue=$KEY" | ConvertFrom-Json

$ch.changelog | ForEach-Object {
    "$($_.user)  $($_.creationDate)"
    $_.diffs | ForEach-Object { "    $($_.key): '$($_.oldValue)' -> '$($_.newValue)'" }
}
```

```text
i.myasnikov  2026-11-10T09:00:00+0000
    status: 'OPEN' -> 'ACCEPTED'
    resolution: '' -> 'WONTFIX'
```

**Плоский список комментариев** (если нужны):

```powershell
$ch.changelog | ForEach-Object { $_.diffs } | Format-Table key, oldValue, newValue -AutoSize
```

---

## 4. Rules — имя правила

### 4.1. `GET /api/rules/show?key=RULE`

```powershell
$r = curl.exe -s -H $AUTH -G "$SONAR/api/rules/show" `
    --data-urlencode "key=php:S1192" | ConvertFrom-Json

$r.rule | Format-List key, name, lang, severity
```

```text
key      : php:S1192
name     : String literals should not be duplicated
lang     : php
severity : MINOR
```

**Получить RULE из KEY автоматически:**

```powershell
$one = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
    --data-urlencode "issues=$KEY" `
    --data-urlencode "additionalFields=_all" | ConvertFrom-Json
$RULE = $one.issues[0].rule

$r = curl.exe -s -H $AUTH -G "$SONAR/api/rules/show" `
    --data-urlencode "key=$RULE" | ConvertFrom-Json
Write-Host "Rule: $RULE  ->  $($r.rule.name)"
```

**Ошибка (правило не существует):**

```powershell
$r = curl.exe -s -H $AUTH -G "$SONAR/api/rules/show" `
    --data-urlencode "key=php:UNKNOWN" | ConvertFrom-Json
$r.errors[0].msg
# Rule with key 'php:UNKNOWN' does not exist
```

---

## 5. Sources — фрагменты кода

### 5.1. `GET /api/sources/issue_snippets?issueKey=KEY`

```powershell
$sn = curl.exe -s -H $AUTH -G "$SONAR/api/sources/issue_snippets" `
    --data-urlencode "issueKey=$KEY" | ConvertFrom-Json

# Первое свойство = имя компонента (файл)
$file = ($sn.PSObject.Properties | Select-Object -First 1).Name
Write-Host "FILE = $file"

# Строки кода с номерами
$sn.$file.sources | ForEach-Object { "{0,5} | {1}" -f $_.line, $_.code }
```

```text
   25 |     public function setRole($role) {
   26 |         $this->container['role'] = 'admin';
   27 |         $this->container['status'] = '<mark class="sonar-issue-mark">admin</mark>';
   28 |     }
```

> `&gt;` / `&lt;` / `&amp;` — HTML-entities, PowerShell их не декодирует автоматически. Если нужно — `[System.Web.HttpUtility]::HtmlDecode($code)` (требует `Add-Type -AssemblyName System.Web`).

**Только номера строк:**

```powershell
$sn.$file.sources | ForEach-Object { $_.line }
```

**Сохранить сниппет в файл:**

```powershell
$sn.$file.sources | ForEach-Object { "{0,5} | {1}" -f $_.line, $_.code } |
    Out-File -Encoding utf8 "$env:TEMP\snippet.txt"
notepad "$env:TEMP\snippet.txt"
```

### 5.2. Несуществующий ключ

```powershell
$sn = curl.exe -s -H $AUTH -G "$SONAR/api/sources/issue_snippets" `
    --data-urlencode "issueKey=invalid-key" | ConvertFrom-Json
# $sn = пустой объект @{ }
```

---

## 6. Tags

### 6.1. Все теги

```powershell
$t = curl.exe -s -H $AUTH -G "$SONAR/api/issues/tags" `
    --data-urlencode "ps=100" | ConvertFrom-Json

$t.tags
"count = $(@($t.tags).Count)"
```

```text
CWE
code-duplication
code-smell
not-require-fix
require-fix
security
count = 6
```

### 6.2. По префиксу

```powershell
$t = curl.exe -s -H $AUTH -G "$SONAR/api/issues/tags" `
    --data-urlencode "ps=100" `
    --data-urlencode "q=require" | ConvertFrom-Json
$t.tags
```

```text
not-require-fix
require-fix
```

---

## 7. Hotspots — Security Hotspots

### 7.1. `TO_REVIEW`

```powershell
$hs = curl.exe -s -H $AUTH -G "$SONAR/api/hotspots/search" `
    --data-urlencode "project=$PROJ" `
    --data-urlencode "status=TO_REVIEW" `
    --data-urlencode "ps=500" `
    --data-urlencode "p=1" | ConvertFrom-Json

"total = $($hs.paging.total)"
$hs.hotspots | Format-Table key, vulnerabilityProbability, line, component -AutoSize
```

```text
total = 4

key       vulnerabilityProbability line component
---       ------------------------ ---- ---------
abc-123   HIGH                       12  src/Config/security.yml
def-456   MEDIUM                      7  src/Http/Client.php
```

### 7.2. `REVIEWED`

```powershell
$hr = curl.exe -s -H $AUTH -G "$SONAR/api/hotspots/search" `
    --data-urlencode "project=$PROJ" `
    --data-urlencode "status=REVIEWED" `
    --data-urlencode "ps=500" `
    --data-urlencode "p=1" | ConvertFrom-Json
"total = $($hr.paging.total)"
```

---

## 8. Bulk operations

Все — `POST`, `Content-Type: application/x-www-form-urlencoded`.

### 8.1. `POST /api/issues/bulk_change` — статус + комментарий

```powershell
$r = curl.exe -s -X POST -H $AUTH `
    --data-urlencode "issues=$KEY" `
    --data-urlencode "do_transition=accept" `
    --data-urlencode "comment=Техдолг на следующий спринт" `
    "$SONAR/api/issues/bulk_change" | ConvertFrom-Json

$r | Format-List total, success, ignored, failures
```

```text
total    : 1
success  : 1
ignored  : 0
failures : 0
```

**Массово (несколько ключей):**

```powershell
$keys = "KEY1,KEY2,KEY3"
$r = curl.exe -s -X POST -H $AUTH `
    --data-urlencode "issues=$keys" `
    --data-urlencode "do_transition=confirm" `
    --data-urlencode "comment=Critical" `
    "$SONAR/api/issues/bulk_change" | ConvertFrom-Json
$r
```

> ⚠ SonarQube **применит** изменения. Dry-run на этом URL невозможен. Перед запуском проверьте доступные переходы в `/api/issues/search?issues=KEY` — массив `transitions[]`.

**Допустимые `do_transition`:** `accept`, `falsepositive`, `confirm`, `resolve`, `wontfix`.

### 8.2. `POST /api/issues/do_transition` — одиночный переход (включая reopen)

```powershell
$r = curl.exe -s -X POST -H $AUTH `
    --data-urlencode "issue=$KEY" `
    --data-urlencode "transition=reopen" `
    "$SONAR/api/issues/do_transition" | ConvertFrom-Json

$r.issue.status
# REOPENED
```

### 8.3. `POST /api/issues/add_comment`

```powershell
$r = curl.exe -s -X POST -H $AUTH `
    --data-urlencode "issue=$KEY" `
    --data-urlencode "text=Комментарий из PowerShell" `
    "$SONAR/api/issues/add_comment" | ConvertFrom-Json

@($r.issue.comments).Count
# сколько комментариев всего
```

### 8.4. `POST /api/issues/set_tags`

```powershell
$r = curl.exe -s -X POST -H $AUTH `
    --data-urlencode "issue=$KEY" `
    --data-urlencode "tags=require-fix,code-smell" `
    "$SONAR/api/issues/set_tags" | ConvertFrom-Json

$r.issue.tags
```

```text
require-fix
code-smell
```

**Очистить теги:**

```powershell
$r = curl.exe -s -X POST -H $AUTH `
    --data-urlencode "issue=$KEY" `
    --data-urlencode "tags=" `
    "$SONAR/api/issues/set_tags" | ConvertFrom-Json
```

> **Ограничение тегов:** `^[a-z0-9\+#\-\.]+$`. Пробелы, кириллица, `_` → `400 Bad Request`.

---

## 9. Measures — метрики проекта

### 9.1. Полный набор метрик

```powershell
$m = curl.exe -s -H $AUTH -G "$SONAR/api/measures/component" `
    --data-urlencode "additionalFields=period,metrics" `
    --data-urlencode "component=$PROJ" `
    --data-urlencode "metricKeys=alert_status,violations,code_smells,bugs,vulnerabilities,coverage,duplicated_lines_density,ncloc,accepted_issues,new_violations" `
    | ConvertFrom-Json

$m.component.measures | Format-Table metric, value -AutoSize
```

```text
metric                    value
------                    -----
alert_status              OK
new_violations            0
accepted_issues           187
bugs                      12
vulnerabilities           3
code_smells               1422
coverage                  12.3
duplicated_lines_density  1.7
ncloc                     42000
violations                1437
```

**В `[hashtable]` для удобного доступа:**

```powershell
$metrics = @{}
$m.component.measures | ForEach-Object { $metrics[$_.metric] = $_.value }

$metrics['coverage']        # 12.3
$metrics['violations']      # 1437
$metrics['alert_status']    # OK
```

### 9.2. Только покрытие и дубликаты

```powershell
$m = curl.exe -s -H $AUTH -G "$SONAR/api/measures/component" `
    --data-urlencode "additionalFields=period,metrics" `
    --data-urlencode "component=$PROJ" `
    --data-urlencode "metricKeys=coverage,duplicated_lines_density" | ConvertFrom-Json
$m.component.measures
```

---

## 10. Metadata

### 10.1. `GET /api/languages/list`

```powershell
$l = curl.exe -s -H $AUTH "$SONAR/api/languages/list" | ConvertFrom-Json
$l.languages | Format-Table key, name -AutoSize
```

### 10.2. `GET /api/project_branches/list?project=KEY`

```powershell
$b = curl.exe -s -H $AUTH -G "$SONAR/api/project_branches/list" `
    --data-urlencode "project=$PROJ" | ConvertFrom-Json
$b.branches | Format-Table name, isMain, type, analysisDate -AutoSize
```

### 10.3. `GET /api/project_pull_requests/list?project=KEY`

```powershell
$pr = curl.exe -s -H $AUTH -G "$SONAR/api/project_pull_requests/list" `
    --data-urlencode "project=$PROJ" | ConvertFrom-Json
$pr.pullRequests | Format-Table key, title, branch -AutoSize
```

### 10.4. `GET /api/settings/values?keys=...`

```powershell
$s = curl.exe -s -H $AUTH -G "$SONAR/api/settings/values" `
    --data-urlencode "keys=sonar.announcement.displayMessage,sonar.announcement.message" `
    | ConvertFrom-Json
$s.settings | Format-Table key, value -AutoSize
```

---

## 11. Projects

### 11.1. `GET /api/components/search?qualifiers=TRK`

```powershell
$p = curl.exe -s -H $AUTH -G "$SONAR/api/components/search" `
    --data-urlencode "qualifiers=TRK" `
    --data-urlencode "p=1" `
    --data-urlencode "ps=100" | ConvertFrom-Json

"total = $($p.paging.total)"
$p.components | Format-Table key, name -AutoSize
```

**С поиском:**

```powershell
$p = curl.exe -s -H $AUTH -G "$SONAR/api/components/search" `
    --data-urlencode "qualifiers=TRK" `
    --data-urlencode "q=MEDMIS" `
    --data-urlencode "ps=100" | ConvertFrom-Json
$p.components | Format-Table key, name -AutoSize
```

### 11.2. `GET /api/components/search_projects`

```powershell
$p = curl.exe -s -H $AUTH -G "$SONAR/api/components/search_projects" `
    --data-urlencode "p=1" `
    --data-urlencode "ps=50" `
    --data-urlencode "f=analysisDate,leakPeriodDate" | ConvertFrom-Json
"total = $($p.paging.total)"
```

**С фильтром (внимание на экранирование кавычек в PowerShell):**

```powershell
# Вариант 1: фильтр в отдельной переменной (рекомендуется)
$filter = 'qualifier = "TRK"'
$p = curl.exe -s -H $AUTH -G "$SONAR/api/components/search_projects" `
    --data-urlencode "filter=$filter" `
    --data-urlencode "p=1" `
    --data-urlencode "ps=500" | ConvertFrom-Json

# Вариант 2: экранирование через backtick
$p = curl.exe -s -H $AUTH -G "$SONAR/api/components/search_projects" `
    --data-urlencode "filter=qualifier = `"TRK`"" `
    --data-urlencode "p=1" `
    --data-urlencode "ps=500" | ConvertFrom-Json

# Вариант 3: внешние одинарные кавычки — внутри двойные без escape
$p = curl.exe -s -H $AUTH -G "$SONAR/api/components/search_projects" `
    --data-urlencode 'filter=qualifier = "TRK"' `
    --data-urlencode "p=1" `
    --data-urlencode "ps=500" | ConvertFrom-Json
```

### 11.3. `GET /api/components/show?component=KEY`

```powershell
$c = curl.exe -s -H $AUTH -G "$SONAR/api/components/show" `
    --data-urlencode "component=$PROJ" | ConvertFrom-Json
$c.component | Format-List key, name, qualifier, visibility, analysisDate
```

```text
key          : MEDMIS-MISmobile.doctor_api_spec
name         : MISmobile.doctor API
qualifier    : TRK
visibility   : private
analysisDate : 2026-11-10T00:00:00+0000
```

### 11.4. `GET /api/projects/search` (требует admin/Browse)

```powershell
$code = curl.exe -s -o NUL -w "%{http_code}" -H $AUTH "$SONAR/api/projects/search?p=1&ps=1"
if ($code -eq "200") {
    $r = curl.exe -s -H $AUTH -G "$SONAR/api/projects/search" `
        --data-urlencode "p=1" `
        --data-urlencode "ps=100" | ConvertFrom-Json
    $r.paging.total
} else {
    Write-Host "HTTP $code — нет прав, используйте /api/components/search" -ForegroundColor Yellow
}
```

---

## 12. Quality Gates

### 12.1. `GET /api/qualitygates/list`

```powershell
$qg = curl.exe -s -H $AUTH "$SONAR/api/qualitygates/list" | ConvertFrom-Json
$qg.qualitygates | Format-Table id, name, isDefault, isBuiltIn -AutoSize
```

### 12.2. `GET /api/qualitygates/project_status?projectKey=KEY`

```powershell
$qs = curl.exe -s -H $AUTH -G "$SONAR/api/qualitygates/project_status" `
    --data-urlencode "projectKey=$PROJ" | ConvertFrom-Json

$qs.projectStatus.status
$qs.projectStatus.conditions | Format-Table metricKey, status, actualValue, errorThreshold -AutoSize
```

```text
OK

metricKey           status actualValue errorThreshold
---------           ------ ----------- --------------
new_coverage        OK     85.4        80
new_duplicated_...  OK     1.2         3
```

---

## 13. Полный smoke-test (PowerShell-скрипт)

Сохраните как **`smoke.ps1`** и запустите:

```powershell
# smoke.ps1
$SONAR = "https://sonar.bars.group"
$TOKEN = "squ_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
$PROJ  = "MEDMIS-MISmobile.doctor_api_spec"
$AUTH  = "Authorization: Bearer $TOKEN"

function Section($n, $title) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor DarkGray
    Write-Host " $n. $title" -ForegroundColor Cyan
    Write-Host ("=" * 60) -ForegroundColor DarkGray
}

function Try-Run($block) {
    try { & $block } catch { Write-Host "  ERROR: $_" -ForegroundColor Red }
}

# -------- 1. System --------
Section 1 "System status"
Try-Run {
    $r = curl.exe -s -H $AUTH "$SONAR/api/system/status" | ConvertFrom-Json
    "$($r.status)  (v$($r.version))"
}

# -------- 2. Issues --------
Section 2 "First issue key"
$script:KEY = $null
Try-Run {
    $resp = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
        --data-urlencode "components=$PROJ" `
        --data-urlencode "issueStatuses=OPEN,CONFIRMED" `
        --data-urlencode "additionalFields=_all" `
        --data-urlencode "ps=1" | ConvertFrom-Json
    $script:KEY = $resp.issues[0].key
    "KEY = $script:KEY"
}

Section 3 "Issue details"
Try-Run {
    if (-not $script:KEY) { throw "KEY не получен в шаге 2" }
    $one = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
        --data-urlencode "issues=$($script:KEY)" `
        --data-urlencode "additionalFields=_all" | ConvertFrom-Json
    $one.issues[0] | Format-List key, rule, status, line, severity, message
}

# -------- 4. Rule --------
Section 4 "Rule name"
Try-Run {
    $one = curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" `
        --data-urlencode "issues=$($script:KEY)" `
        --data-urlencode "additionalFields=_all" | ConvertFrom-Json
    $rule = $one.issues[0].rule
    $r = curl.exe -s -H $AUTH -G "$SONAR/api/rules/show" `
        --data-urlencode "key=$rule" | ConvertFrom-Json
    "$rule  ->  $($r.rule.name)"
}

# -------- 5. Snippet --------
Section 5 "Snippet"
Try-Run {
    $sn = curl.exe -s -H $AUTH -G "$SONAR/api/sources/issue_snippets" `
        --data-urlencode "issueKey=$($script:KEY)" | ConvertFrom-Json
    $file = ($sn.PSObject.Properties | Select-Object -First 1).Name
    "FILE = $file"
    $sn.$file.sources | ForEach-Object { "{0,5} | {1}" -f $_.line, $_.code }
}

# -------- 6. Changelog --------
Section 6 "Changelog"
Try-Run {
    $ch = curl.exe -s -H $AUTH -G "$SONAR/api/issues/changelog" `
        --data-urlencode "issue=$($script:KEY)" | ConvertFrom-Json
    "events = $(@($ch.changelog).Count)"
}

# -------- 7. Tags --------
Section 7 "Tags"
Try-Run {
    $t = curl.exe -s -H $AUTH -G "$SONAR/api/issues/tags" `
        --data-urlencode "ps=100" | ConvertFrom-Json
    "count = $(@($t.tags).Count)"
    $t.tags -join ", "
}

# -------- 8. Hotspots --------
Section 8 "Hotspots TO_REVIEW"
Try-Run {
    $hs = curl.exe -s -H $AUTH -G "$SONAR/api/hotspots/search" `
        --data-urlencode "project=$PROJ" `
        --data-urlencode "status=TO_REVIEW" `
        --data-urlencode "ps=500" `
        --data-urlencode "p=1" | ConvertFrom-Json
    "total = $($hs.paging.total)"
}

# -------- 9. Measures --------
Section 9 "Measures"
Try-Run {
    $m = curl.exe -s -H $AUTH -G "$SONAR/api/measures/component" `
        --data-urlencode "component=$PROJ" `
        --data-urlencode "metricKeys=alert_status,violations,code_smells,bugs,vulnerabilities,coverage,duplicated_lines_density" `
        | ConvertFrom-Json
    $m.component.measures | Format-Table metric, value -AutoSize
}

# -------- 10. Languages --------
Section 10 "Languages"
Try-Run {
    $l = curl.exe -s -H $AUTH "$SONAR/api/languages/list" | ConvertFrom-Json
    "count = $(@($l.languages).Count)"
    $l.languages | ForEach-Object { $_.key }
}

# -------- 11. Branches --------
Section 11 "Branches"
Try-Run {
    $b = curl.exe -s -H $AUTH -G "$SONAR/api/project_branches/list" `
        --data-urlencode "project=$PROJ" | ConvertFrom-Json
    $b.branches | Format-Table name, isMain, type -AutoSize
}

# -------- 12. Project info --------
Section 12 "Project info"
Try-Run {
    $c = curl.exe -s -H $AUTH -G "$SONAR/api/components/show" `
        --data-urlencode "component=$PROJ" | ConvertFrom-Json
    $c.component | Format-List key, name, qualifier
}

# -------- 13. Quality Gate --------
Section 13 "Quality Gate"
Try-Run {
    $qs = curl.exe -s -H $AUTH -G "$SONAR/api/qualitygates/project_status" `
        --data-urlencode "projectKey=$PROJ" | ConvertFrom-Json
    $qs.projectStatus.status
}
```

Запуск:

```powershell
powershell -ExecutionPolicy Bypass -File .\smoke.ps1
```

Или из текущей консоли (если политика позволяет):

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\smoke.ps1
```

---

## 14. Полезные шаблоны PowerShell

### 14.1. Плоский список полей

```powershell
$resp.issues | ForEach-Object { "{0,-40} {1,-12} {2}" -f $_.key, $_.rule, $_.message }
```

### 14.2. Группировка и подсчёт

```powershell
$resp.issues | Group-Object rule |
    Sort-Object Count -Descending |
    Format-Table Count, Name -AutoSize
```

### 14.3. Фильтр + проекция

```powershell
$resp.issues |
    Where-Object { $_.impactSeverity -eq "HIGH" } |
    Select-Object key, rule, component, line |
    Format-Table -AutoSize
```

### 14.4. Экспорт в CSV

```powershell
$resp.issues |
    Select-Object key, rule, status, line, severity, component, message |
    Export-Csv -NoTypeInformation -Encoding utf8 "$env:TEMP\issues.csv"
```

### 14.5. Экспорт в JSON (обратно в файл)

```powershell
$resp | ConvertTo-Json -Depth 20 | Out-File -Encoding utf8 "$env:TEMP\issues-pretty.json"
```

### 14.6. HTTP-код и тело отдельно

```powershell
$body = curl.exe -s -w "`n%{http_code}" -H $AUTH "$SONAR/api/system/status"
$lines = $body -split "`n"
$json  = $lines[0..($lines.Count-2)] -join "`n"
$code  = $lines[-1]
"HTTP $code"
$json | ConvertFrom-Json
```

### 14.7. Проверка авторизации (быстрый чек)

```powershell
$code = curl.exe -s -o NUL -w "%{http_code}" -H $AUTH "$SONAR/api/system/status"
switch ($code) {
    "200" { Write-Host "OK" -ForegroundColor Green }
    "401" { Write-Host "Токен невалиден" -ForegroundColor Red }
    "403" { Write-Host "Нет прав" -ForegroundColor Yellow }
    default { Write-Host "HTTP $code" -ForegroundColor Yellow }
}
```

---

## 15. Шпаргалка: все URL

| Метод | URL | Одна строка PowerShell |
|---|---|---|
| GET | `/api/system/status` | `curl.exe -s -H $AUTH "$SONAR/api/system/status" \| ConvertFrom-Json` |
| GET | `/api/system/health` | `curl.exe -s -H $AUTH "$SONAR/api/system/health" \| ConvertFrom-Json` |
| GET | `/api/issues/search` | `curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" --data-urlencode "components=$PROJ" --data-urlencode "additionalFields=_all" \| ConvertFrom-Json` |
| GET | `/api/issues/search?issues=KEY` | `curl.exe -s -H $AUTH -G "$SONAR/api/issues/search" --data-urlencode "issues=$KEY" --data-urlencode "additionalFields=_all" \| ConvertFrom-Json` |
| GET | `/api/issues/changelog` | `curl.exe -s -H $AUTH -G "$SONAR/api/issues/changelog" --data-urlencode "issue=$KEY" \| ConvertFrom-Json` |
| GET | `/api/rules/show` | `curl.exe -s -H $AUTH -G "$SONAR/api/rules/show" --data-urlencode "key=$RULE" \| ConvertFrom-Json` |
| GET | `/api/sources/issue_snippets` | `curl.exe -s -H $AUTH -G "$SONAR/api/sources/issue_snippets" --data-urlencode "issueKey=$KEY" \| ConvertFrom-Json` |
| GET | `/api/issues/tags` | `curl.exe -s -H $AUTH -G "$SONAR/api/issues/tags" --data-urlencode "ps=100" \| ConvertFrom-Json` |
| GET | `/api/hotspots/search` | `curl.exe -s -H $AUTH -G "$SONAR/api/hotspots/search" --data-urlencode "project=$PROJ" --data-urlencode "status=TO_REVIEW" \| ConvertFrom-Json` |
| POST | `/api/issues/bulk_change` | `curl.exe -s -X POST -H $AUTH --data-urlencode "issues=$KEY" --data-urlencode "do_transition=accept" "$SONAR/api/issues/bulk_change" \| ConvertFrom-Json` |
| POST | `/api/issues/do_transition` | `curl.exe -s -X POST -H $AUTH --data-urlencode "issue=$KEY" --data-urlencode "transition=reopen" "$SONAR/api/issues/do_transition" \| ConvertFrom-Json` |
| POST | `/api/issues/add_comment` | `curl.exe -s -X POST -H $AUTH --data-urlencode "issue=$KEY" --data-urlencode "text=..." "$SONAR/api/issues/add_comment" \| ConvertFrom-Json` |
| POST | `/api/issues/set_tags` | `curl.exe -s -X POST -H $AUTH --data-urlencode "issue=$KEY" --data-urlencode "tags=a,b" "$SONAR/api/issues/set_tags" \| ConvertFrom-Json` |
| GET | `/api/measures/component` | `curl.exe -s -H $AUTH -G "$SONAR/api/measures/component" --data-urlencode "component=$PROJ" --data-urlencode "metricKeys=..." \| ConvertFrom-Json` |
| GET | `/api/languages/list` | `curl.exe -s -H $AUTH "$SONAR/api/languages/list" \| ConvertFrom-Json` |
| GET | `/api/project_branches/list` | `curl.exe -s -H $AUTH -G "$SONAR/api/project_branches/list" --data-urlencode "project=$PROJ" \| ConvertFrom-Json` |
| GET | `/api/project_pull_requests/list` | `curl.exe -s -H $AUTH -G "$SONAR/api/project_pull_requests/list" --data-urlencode "project=$PROJ" \| ConvertFrom-Json` |
| GET | `/api/settings/values` | `curl.exe -s -H $AUTH -G "$SONAR/api/settings/values" --data-urlencode "keys=..." \| ConvertFrom-Json` |
| GET | `/api/components/search` | `curl.exe -s -H $AUTH -G "$SONAR/api/components/search" --data-urlencode "qualifiers=TRK" \| ConvertFrom-Json` |
| GET | `/api/components/search_projects` | `curl.exe -s -H $AUTH -G "$SONAR/api/components/search_projects" --data-urlencode "p=1" --data-urlencode "ps=50" \| ConvertFrom-Json` |
| GET | `/api/components/show` | `curl.exe -s -H $AUTH -G "$SONAR/api/components/show" --data-urlencode "component=$PROJ" \| ConvertFrom-Json` |
| GET | `/api/projects/search` | `curl.exe -s -H $AUTH -G "$SONAR/api/projects/search" --data-urlencode "p=1" --data-urlencode "ps=100" \| ConvertFrom-Json` |
| GET | `/api/qualitygates/list` | `curl.exe -s -H $AUTH "$SONAR/api/qualitygates/list" \| ConvertFrom-Json` |
| GET | `/api/qualitygates/project_status` | `curl.exe -s -H $AUTH -G "$SONAR/api/qualitygates/project_status" --data-urlencode "projectKey=$PROJ" \| ConvertFrom-Json` |

---

## 16. Типичные ошибки в PowerShell

| Симптом | Причина | Решение |
|---|---|---|
| `curl: (6) Could not resolve host` | `$SONAR` пуст | `Write-Host $SONAR` — проверьте значение |
| `curl: (3) URL using bad/illegal format` | В URL неэкранированный `&` | Только `--data-urlencode` + `-G` |
| `'curl' is not recognized` | В PowerShell 5.1 `curl` = `Invoke-WebRequest`, у которого нет опций curl | Всегда **`curl.exe`** (с `.exe`) |
| `ConvertFrom-Json : Invalid JSON primitive` | Пришёл не JSON (например, HTML-ошибка от прокси) | Сначала `curl.exe -s ...` без pipe — посмотрите, что вернулось |
| `$r.issues.Count = $null` | Один элемент — не массив | `@($r.issues).Count` |
| `Cannot index into a null array` | `.issues` пуст | `if ($r.issues) { ... }` |
| Backtick `` ` `` не работает как продолжение строки | Есть пробел после `` ` `` | Уберите **все** пробелы после `` ` `` |
| Кавычки внутри `"..."` ломают аргумент | PowerShell парсит их | Внешние одинарные: `'filter=query = "X"'` или backtick-escape `` `" `` |
| `401 Unauthorized` | Токен невалиден / истёк | Сгенерируйте новый на `<SONAR>/account/security` |
| `403 Forbidden` | Нет прав (например `/api/projects/search`) | Используйте `/api/components/search` |
| `400 Bad Request` при `bulk_change` | Недопустимый `do_transition` для текущего статуса | `GET /api/issues/search?issues=KEY`, посмотрите `transitions[]` |
| `$metrics['coverage']` пусто | Поле не пришло в ответе (например, метрики нет) | Проверьте `$m.component.measures \| Format-Table` |
| `Script is not digitally signed` при запуске `.ps1` | Execution Policy | `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` |
| `Out-File` пишет в UTF-16 | По умолчанию `-Encoding` в PS 5.1 = Unicode | Всегда `-Encoding utf8` |
| Не отображается кириллица в консоли | Кодировка консоли | `chcp 65001` или `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` |