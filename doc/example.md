# Обновлённый `example.md`

Актуализирован с учётом нового функционала (промпты, сессия, refresh, mark/cancel/set_tags, changelog, measures). Все POST-эндпоинты даны и в виде URL, и в виде `curl`. Read-only GET — просто список URL, как в исходной версии.

```markdown
# Примеры запросов к REST API SonarRestApi

База: `http://127.0.0.1:6060`

Во всех запросах используется `project=MEDMIS-MISmobile.doctor_api_spec`. Если сервер запущен с `--project=KEY`, параметр `project` в URL можно не передавать.

---

## 1. Чтение (GET)

### 1.1 Сводка / матрица / отчёты

```
http://127.0.0.1:6060/api/summary?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY
http://127.0.0.1:6060/api/matrix?project=MEDMIS-MISmobile.doctor_api_spec&sev=ALL&qual=ALL
http://127.0.0.1:6060/api/perfile?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=RELIABILITY
http://127.0.0.1:6060/api/details?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY&snippets=10
http://127.0.0.1:6060/api/issues?project=MEDMIS-MISmobile.doctor_api_spec&sev=HIGH&qual=ALL
http://127.0.0.1:6060/api/keys?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY
```

### 1.2 Один issue, changelog, snippet

```
http://www.127.0.0.1:6060/api/snippet?project=MEDMIS-MISmobile.doctor_api_spec&issueKey=9ede9b09-5875-4fbf-8a21-200346c8e415
http://www.127.0.0.1:6060/api/changelog?project=MEDMIS-MISmobile.doctor_api_spec&issueKey=9ede9b09-5875-4fbf-8a21-200346c8e415
```

### 1.3 Состояние сервера

```
http://127.0.0.1:6060/api/health
http://127.0.0.1:6060/api/status
```

### 1.4 Обработанные / открытые / теги

```
http://127.0.0.1:6060/api/accepted?project=MEDMIS-MISmobile.doctor_api_spec&sev=ALL&qual=ALL&facets=true
http://127.0.0.1:6060/api/open_issues?project=MEDMIS-MISmobile.doctor_api_spec&sev=ALL&qual=ALL&snippets=true
http://127.0.0.1:6060/api/tags
```

### 1.5 Метрики и метаданные

```
http://127.0.0.1:6060/api/measures?project=MEDMIS-MISmobile.doctor_api_spec
http://127.0.0.1:6060/api/metadata?project=MEDMIS-MISmobile.doctor_api_spec
```

### 1.6 Промпты (только чтение)

```
http://127.0.0.1:6060/api/prompt/list
http://127.0.0.1:6060/api/prompt/build?project=MEDMIS-MISmobile.doctor_api_spec&name=status-comment&issueKey=9ede9b09-5875-4fbf-8a21-200346c8e415&status=accept
```

**Параметры `prompt/build`:**

| Параметр | Назначение |
|---|---|
| `name` | `status-comment` \| `bulk-status-comment` \| `analyze-issue` \| `auto-resolve` |
| `issueKey` | один issue (взаимоисключающий с `keys`/`sev`+`qual`) |
| `keys=A,B,C` | группа по ключам |
| `sev=ALL&qual=ALL` | фильтр для bulk |
| `status` | `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` |
| `statusLabel` | человекочитаемое описание |

Примеры:

```
http://127.0.0.1:6060/api/prompt/build?project=MEDMIS-MISmobile.doctor_api_spec&name=bulk-status-comment&keys=9ede9b09-5875-4fbf-8a21-200346c8e415,1a2b3c4d-5e6f-7890-abcd-ef1234567890&status=accept
http://127.0.0.1:6060/api/prompt/build?project=MEDMIS-MISmobile.doctor_api_spec&name=analyze-issue&issueKey=9ede9b09-5875-4fbf-8a21-200346c8e415
http://127.0.0.1:6060/api/prompt/build?project=MEDMIS-MISmobile.doctor_api_spec&name=auto-resolve&issueKey=9ede9b09-5875-4fbf-8a21-200346c8e415
```

### 1.7 Сессия (только чтение)

```
http://127.0.0.1:6060/api/session
```

---

## 2. Изменение (POST)

> Все POST-эндпоинты принимают `project` (если не задан `--project=`), а по умолчанию — **dry-run**. Для применения нужно явно указать `dryRun=false`. 

### 2.1 Сессия

**Сохранить новую сессию и запустить пересбор:**

```bash
curl -X PUT "http://127.0.0.1:6060/api/session?project=MEDMIS-MISmobile.doctor_api_spec&build=true" \
     -H "Content-Type: text/plain" \
     --data-binary @session.txt
```

**Перечитать `session.txt` с диска:**

```bash
curl -X POST "http://127.0.0.1:6060/api/session/reload?project=MEDMIS-MISmobile.doctor_api_spec&build=true"
```

### 2.2 Пересборка снимка

**Async (возвращает 202 сразу):**

```bash
curl -X POST "http://127.0.0.1:6060/api/refresh?project=MEDMIS-MISmobile.doctor_api_spec"
```

**Sync (ждёт завершения):**

```bash
curl -X POST "http://127.0.0.1:6060/api/refresh?project=MEDMIS-MISmobile.doctor_api_spec&wait=true"
```

**Точечный refresh одного issue:**

```bash
curl -X POST "http://127.0.0.1:6060/api/refresh/issue?project=MEDMIS-MISmobile.doctor_api_spec&key=9ede9b09-5875-4fbf-8a21-200346c8e415"
```

### 2.3 Пометить ОДИН issue

**Dry-run (по умолчанию) — только план, ничего не меняется:**

```bash
curl -X POST "http://127.0.0.1:6060/api/mark?project=MEDMIS-MISmobile.doctor_api_spec&key=9ede9b09-5875-4fbf-8a21-200346c8e415&transition=accept&comment=Согласовано"
```

**Реально применить:**

```bash
curl -X POST "http://127.0.0.1:6060/api/mark?project=MEDMIS-MISmobile.doctor_api_spec&key=9ede9b09-5875-4fbf-8a21-200346c8e415&transition=accept&comment=Согласовано&dryRun=false"
```

**Доступные `transition`:** `accept` | `falsepositive` | `confirm` | `resolve` | `wontfix` | `reopen`.

### 2.4 Пометить МАССОВО по фильтру

**Dry-run:**

```bash
curl -X POST "http://127.0.0.1:6060/api/mark?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY&transition=accept"
```

**Применить:**

```bash
curl -X POST "http://127.0.0.1:6060/api/mark?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY&transition=accept&dryRun=false"
```

### 2.5 Снять пометку (reopen)

**Один issue:**

```bash
curl -X POST "http://127.0.0.1:6060/api/cancel?project=MEDMIS-MISmobile.doctor_api_spec&key=9ede9b09-5875-4fbf-8a21-200346c8e415&dryRun=false"
```

**Массово по фильтру:**

```bash
curl -X POST "http://127.0.0.1:6060/api/cancel?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY&dryRun=false"
```

### 2.6 Установить теги

```bash
curl -X POST "http://127.0.0.1:6060/api/set_tags?project=MEDMIS-MISmobile.doctor_api_spec&keys=9ede9b09-5875-4fbf-8a21-200346c8e415&tags=require-fix,error-handling"
```

**Параметры:**

- `keys` — CSV-список ключей (`A,B,C`)
- `issue` — alias для одиночного ключа (для совместимости с SonarQube `/api/issues/set_tags`, который принимает только `issue`)
- `tags` — CSV-список тегов (пустая строка = снять все теги)

Для установки тегов сразу нескольким issues — либо через `/api/set_tags` с `keys=A,B,C` (сервер сам разберёт), либо сделать N последовательных запросов с `keys=KEY&issue=KEY`.

---

## 3. Полные примеры с curl + jq

### 3.1 Проверить сервер

```bash
curl -s http://127.0.0.1:6060/api/health | jq
```

### 3.2 Список ключей по HIGH/SECURITY

```bash
curl -s "http://127.0.0.1:6060/api/keys?project=MEDMIS-MISmobile.doctor_api_spec&sev=HIGH&qual=SECURITY" \
  | jq -r '.keys[]'
```

### 3.3 Получить промпт и отправить в Ollama

```bash
# 1. Собрать промпт
curl -s "http://127.0.0.1:6060/api/prompt/build?project=MEDMIS-MISmobile.doctor_api_spec&name=status-comment&issueKey=9ede9b09-5875-4fbf-8a21-200346c8e415&status=accept" \
  | jq -r .prompt > /tmp/prompt.txt

# 2. Отправить в Ollama (напрямую, без SonarRestApi)
curl -s http://smwrap.ru:3397/api/generate \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"qwen2.5-coder:14b\",\"prompt\":$(jq -Rs . < /tmp/prompt.txt),\"stream\":false}" \
  | jq -r .response > /tmp/response.txt

# 3. Прочитать ответ модели
cat /tmp/response.txt
```

### 3.4 Применить статус по ответу модели (вручную)

```bash
curl -X POST "http://127.0.0.1:6060/api/mark?project=MEDMIS-MISmobile.doctor_api_spec&key=9ede9b09-5875-4fbf-8a21-200346c8e415&transition=accept&comment=$(cat /tmp/response.txt | jq -sRr @uri)&dryRun=false"
```

(параметр `comment` нужно URL-кодировать — `jq -sRr @uri` делает это.)

### 3.5 Массовый dry-run + применение

```bash
# Посмотреть, что будет обработано
curl -s -X POST "http://127.0.0.1:6060/api/mark?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY&transition=accept" | jq
# → { "issuesCount": 87, "dryRun": true, "applied": false, "batches": 1 }

# Применить
curl -s -X POST "http://127.0.0.1:6060/api/mark?project=MEDMIS-MISmobile.doctor_api_spec&sev=MEDIUM&qual=MAINTAINABILITY&transition=accept&dryRun=false" | jq
# → { "applied": true, "responses": [...] }
```

### 3.6 Список обработанных + facets

```bash
curl -s "http://127.0.0.1:6060/api/accepted?project=MEDMIS-MISmobile.doctor_api_spec&sev=ALL&qual=ALL&facets=true" \
  | jq '{total, facets: .facets.severities, issues: (.issues | length)}'
```

### 3.7 Обновить один issue после ручной правки

```bash
curl -s -X POST "http://127.0.0.1:6060/api/refresh/issue?project=MEDMIS-MISmobile.doctor_api_spec&key=9ede9b09-5875-4fbf-8a21-200346c8e415" \
  | jq '{removed, reason, snapshotIssuesCount}'
```

---

## 4. Параметры, общие для всех эндпоинтов

| Параметр | Значения | По умолчанию |
|---|---|---|
| `project` | ключ проекта | `--project` / `SONAR_PROJECT` |
| `sev` | `MEDIUM` \| `HIGH` \| `LOW` \| `INFO` \| `ALL` | `MEDIUM` (или `ALL` в `accepted`/`open_issues`) |
| `qual` | `MAINTAINABILITY` \| `RELIABILITY` \| `SECURITY` \| `SECURITY_HOTSPOT` \| `ALL` | `MAINTAINABILITY` |
| `dryRun` | `true` \| `false` | `true` (для `mark`/`cancel`) |
| `wait` | `true` \| `false` | `false` (для `refresh`) |
| `facets` | `true` \| `false` | `true` (для `accepted`) |
| `snippets` | число ≥ 0 | `5` (для `details`) |

---

## 5. Полный список эндпоинтов

### Чтение (GET)

| Эндпоинт | Назначение |
|---|---|
| `/api/health` | краткая проверка (для мониторинга) |
| `/api/status` | полное состояние сервера |
| `/api/session` | прочитать `session.txt` |
| `/api/summary` | сводка + facets |
| `/api/matrix` | матрица файл × правило |
| `/api/perfile` | issues по файлам |
| `/api/details` | подробно с кодом |
| `/api/issues` | плоский список |
| `/api/keys` | только ключи |
| `/api/snippet` | один issue + код |
| `/api/changelog` | история изменений issue |
| `/api/accepted` | обработанные issues + facets |
| `/api/open_issues` | только OPEN / REOPENED |
| `/api/tags` | все теги SonarQube |
| `/api/measures` | метрики проекта |
| `/api/metadata` | языки / ветки / PR / settings |
| `/api/prompt/list` | доступные шаблоны в `prompts/` |
| `/api/prompt/build` | готовый промпт (рендерит `prompts/<name>.txt`) |

### Изменение (POST / PUT)

| Эндпоинт | Назначение |
|---|---|
| `POST /api/refresh` | пересобрать снимок (async / sync) |
| `POST /api/refresh/issue` | точечный refresh одного issue |
| `PUT  /api/session` | сохранить новую сессию + reload + auto build |
| `POST /api/session/reload` | перечитать `session.txt` с диска |
| `POST /api/mark` | смена статуса (один или массово) |
| `POST /api/cancel` | снять пометку (reopen, один или массово) |
| `POST /api/set_tags` | установить теги |

---

## 6. Чего в REST API нет (только в консоли / web)

Следующие операции **не имеют** отдельного REST-эндпоинта — они доступны только через интерактивную консоль (см. `CONSOLE.md`) или web-интерфейс:

- **Настройка Ollama** (`ollama set url/model/test`) — хранится в `ollama.properties`, настраивается через web (кнопка **⚙ Ollama**) или консоль.
- **Массовая AI-обработка** (`auto … --yes`) — цикл промпт→Ollama→mark. Из REST можно собрать вручную: `/api/prompt/build` + собственный вызов Ollama + `/api/mark` (см. пример 3.3).
- **Экспорт отчёта в Markdown** — генерируется на клиенте (web), файл `accepted_*.md` скачивается браузером.

Если нужен REST-эндпоинт для одного из этих сценариев — можно добавить handler в `ApiServer` (см. `DEPLOY.md`, раздел «Архитектура»).
```

---

## Что изменилось

| Блок | Было | Стало |
|---|---|---|
| Формат | плоский список 7 URL | структурированный markdown с 6 разделами |
| Покрытие эндпоинтов | только `/summary`, `/matrix`, `/perfile`, `/details`, `/issues`, `/keys`, `/snippet` | + `/health`, `/status`, `/session`, `/changelog`, `/accepted`, `/open_issues`, `/tags`, `/measures`, `/metadata`, `/prompt/list`, `/prompt/build` |
| POST-эндпоинты | ❌ отсутствовали | ✅ `/mark`, `/cancel`, `/set_tags`, `/refresh`, `/refresh/issue`, `/session`, `/session/reload` — с `curl` и `dryRun=false` |
| Примеры `jq` | ❌ | ✅ 7 готовых сценариев |
| Общие параметры | ❌ | ✅ отдельная таблица (`project`, `sev`, `qual`, `dryRun`, `wait`, `facets`, `snippets`) |
| Список «чего нет в REST» | ❌ | ✅ явно указано: `ollama`, `auto`, экспорт MD |
| Ссылки на другие документы | ❌ | ✅ `CONSOLE.md`, `DEPLOY.md` |