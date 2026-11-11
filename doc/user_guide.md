# Инструкция по работе с проектом через JAR
# Sonar Rest API — руководство пользователя

> Предполагается, что JAR уже собран и лежит рядом с каталогами `www/` и `prompts/`.
> Если JAR ещё не собран — см. `deploy.md`.

---

## 1. Быстрый старт (3 шага до первого результата)

```bash
# 1. Запустить сервер (проект указывается сразу)
java -jar SonarRestApi-1.0-SNAPSHOT.jar --project=MEDMIS-MISmobile.doctor_api_spec

# 2. Открыть браузер
#    http://localhost:6060/

# 3. В шапке нажать "↻ Refresh snapshot"
```

Через 1–3 минуты (в зависимости от размера проекта) в шапке появится статус:

```
● ready · 3172 issues · 10:32:17
```

Это означает, что снимок проекта построен и все вкладки работают.

---

## 2. Запуск JAR

### 2.1. Минимальный запуск

```bash
java -jar SonarRestApi-1.0-SNAPSHOT.jar --project=MY_PROJECT_KEY
```

- HTTP-сервер слушает `0.0.0.0:6060`.
- Если рядом с JAR лежит `www/` — сервер отдаёт web-интерфейс.
- Если `session.txt` пуст или отсутствует — сервер стартует без сессии, покажет это в логах и в статусе шапки.

### 2.2. С параметрами

```bash
java -jar SonarRestApi-1.0-SNAPSHOT.jar \
    --host=127.0.0.1 \
    --port=8080 \
    --project=MY_PROJECT_KEY \
    --threads=8 \
    --batch=500 \
    --delay=500 \
    --session=/etc/sonar/session.txt \
    --ollama-config=/etc/sonar/ollama.properties
```

### 2.3. Указать пути к статике и промптам через JVM-свойства

```bash
java -Dsonar.www=/opt/sonar/www \
     -Dsonar.prompts=/opt/sonar/prompts \
     -jar SonarRestApi-1.0-SNAPSHOT.jar --project=MY_PROJECT_KEY
```

### 2.4. Только API, без интерактивной консоли

```bash
java -jar SonarRestApi-1.0-SNAPSHOT.jar --no-console --project=MY_PROJECT_KEY
```

Полезно для запуска через systemd / Docker / supervisor.

### 2.5. Не строить снимок при старте

```bash
java -jar SonarRestApi-1.0-SNAPSHOT.jar --project=MY_PROJECT_KEY --no-warmup
```

Снимок нужно будет собрать вручную (кнопка **↻ Refresh snapshot** в web или `refresh wait` в консоли).

### 2.6. Полная таблица параметров

| Параметр | По умолчанию | Назначение |
|---|---|---|
| `--host=` | `0.0.0.0` | Адрес привязки |
| `--port=` | `6060` | Порт HTTP |
| `--threads=` | `8` | Пул потоков |
| `--batch=` | `500` | Размер батча `bulk_change` |
| `--delay=` | `500` | Пауза между батчами (мс) |
| `--session=` | `session.txt` | Путь к файлу сессии |
| `--project=` | `$SONAR_PROJECT` | Ключ проекта по умолчанию |
| `--snippet-limit=` | `0` | Лимит сниппетов в снимке (`0` — все) |
| `--ollama-config=` | `ollama.properties` | Путь к настройкам Ollama |
| `--no-warmup` | — | Не строить снимок при старте |
| `--async-warmup` | — | Строить снимок в фоне при старте |
| `--no-snippets` | — | Не подгружать коды в снимок |
| `--no-console` | — | Отключить интерактивную консоль |

### 2.7. Что создаётся автоматически при первом запуске

Рядом с `session.txt` (по умолчанию — в текущем каталоге):

```
session.txt               ← шаблон-инструкция (заполнить вручную)
ollama.properties         ← пустой (заполняется через web или консоль)
prompts/
  ├── auto-resolve.txt          ← шаблон для AI-обработки
  ├── status-comment.txt        ← шаблон для одного issue
  ├── bulk-status-comment.txt   ← шаблон для группы
  └── analyze-issue.txt         ← шаблон «просто анализ»
```

Существующие файлы **никогда не перезаписываются** — правки сохраняются между перезапусками.

---

## 3. Первичная настройка сессии

Без `session.txt` сервер не сможет обращаться к SonarQube. Заполнить его можно тремя способами.

### 3.1. Через web-интерфейс (самый простой)

1. Открыть `http://localhost:6060/`.
2. Перейти на вкладку **Сессия (Session)**.
3. Вставить заголовки (см. ниже) в textarea.
4. Нажать **Save session (PUT /api/session)**.

### 3.2. Через консоль

```
> session set /path/to/session.txt
```

Файл `session.txt` должен содержать строки:

```
Host: sonar.example.com
Cookie: XSRF-TOKEN=...; JWT-SESSION=...
X-XSRF-TOKEN: ...
```

### 3.3. Через REST API

```bash
curl -X PUT "http://localhost:6060/api/session?project=MY_PROJECT_KEY" \
     -H "Content-Type: text/plain" \
     --data-binary @session.txt
```

### 3.4. Как получить заголовки

1. Открыть SonarQube в браузере, авторизоваться.
2. DevTools → **Network** → выбрать любой запрос к `/api/*`.
3. ПКМ → **Copy** → **Copy request headers**.
4. Вставить в `session.txt` только `Host`, `Cookie`, `X-XSRF-TOKEN`.

### 3.5. Проверить, что сессия подхватилась

```bash
curl http://localhost:6060/api/health
```

Ожидается `"sessionReady": true`.

---

## 4. Построение снимка проекта

Снимок — это **локальная копия всех issues проекта** (со сниппетами и `ruleName`). Все вкладки работают по снимку, а не по SonarQube напрямую — это в разы быстрее и снижает нагрузку на сервер.

### 4.1. Способы запустить сборку

| Способ | Команда / действие | Блокирует? |
|---|---|---|
| Web | Кнопка **↻ Refresh snapshot** в шапке | Нет (async) |
| Консоль (async) | `refresh` | Нет |
| Консоль (sync) | `refresh wait` | Да |
| REST API (async) | `POST /api/refresh?project=KEY` | Нет |
| REST API (sync) | `POST /api/refresh?project=KEY&wait=true` | Да |
| При старте | параметр `--async-warmup` или `--no-warmup` | — |

### 4.2. Наблюдать за прогрессом

**В шапке web** — индикатор прогресс-бара.

**В консоли:**

```
> status
snapshot:
  status   = BUILDING
  phase    = fetching_snippets (1500/3172)
```

**Через API:**

```bash
curl "http://localhost:6060/api/status" | jq .snapshot
```

Когда `status = READY`, все вкладки будут работать мгновенно.

---

## 5. Web-интерфейс

Открыть `http://<host>:6060/`.

### 5.1. Шапка (topbar)

| Элемент | Назначение |
|---|---|
| `project` (input) | Ключ проекта. Меняется на лету |
| Метрики проекта | Quality Gate, Σ issues, Security, Reliability, Maintainability, Accepted, Coverage, Duplicates |
| `● ready · N issues · HH:MM` | Индикатор состояния снимка |
| **↻ Refresh snapshot** | Пересобрать снимок (async) |
| **⚙ Ollama** | Настройки LLM |
| Прогресс-бар | Показывается во время сборки снимка |

### 5.2. Фильтры (под шапкой)

Скрываются/показываются в зависимости от вкладки. Основные:

- **sev** — критичность (`HIGH / MEDIUM / LOW / INFO / ALL`)
- **qual** — качество (`MAINTAINABILITY / RELIABILITY / SECURITY / SECURITY_HOTSPOT / ALL`)
- **snippets** — сколько сниппетов показывать (для **Details**)
- **issueKey** — ключ issue с автодополнением (для **Snippet**)

При изменении любого фильтра нажмите **Загрузить** или переключите вкладку — данные перерисуются.

### 5.3. Вкладки

| Вкладка | Что показывает | Действия |
|---|---|---|
| **Сводка (Summary)** | Общая статистика + facets по правилам / файлам / severity | только просмотр |
| **Матрица (Matrix)** | Файл × правило с числами | только просмотр |
| **Отчет по файлам (Per-File)** | Список issues, сгруппированный по файлам | Выделить → Действия; ПКМ → контекстное меню |
| **Отчет с кодом (Details)** | То же + сниппеты | Те же + двойной клик → детали |
| **Все ошибки (Issues)** | Плоский список | Те же действия |
| **Обработанные (Accepted)** | Уже обработанные issues + фильтр по тегам + **Экспорт MD** | Reopen, теги, история, комментарии |
| **Код ошибки (Snippet)** | Один issue по `issueKey` + история + комментарии | Установить статус, теги |
| **Сессия (Session)** | Управление `session.txt` | Save / Load |
| **Статус (Status)** | Сырой JSON `/api/status` | только просмотр |

### 5.4. Работа с issue (Per-File / Details / Issues / Accepted)

**Двойной клик по строке** — открыть модалку «Детали».

**ПКМ по строке** — контекстное меню:

```
📝 Сгенерировать промпт
Посмотреть детали
🏷️ Редактировать теги
────────────
Выделить все
Снять выделение
────────────
Добавить комментарий (N)
Добавить автоматически комментарий (N)   ← AI через Ollama
Отменить комментарий (Reopen) (N)
```

**Выделение:** чекбокс в шапке таблицы выделяет всё видимое; кнопка **Действия с выбранными (N)** открывает массовую модалку.

**Скрыть обработанные** — галка в шапке вкладки. Полезно, когда осталось несколько issues для разбора.

### 5.5. Модалка «Детали ошибки»

Что внутри:

- **Сообщение** issue + имя правила
- **Файл / Строка / Severity / Статус**
- **Фрагмент кода** с подсветкой `>>> <<<`
- **📜 История изменений и комментарии (N)** — единый таймлайн (свежие сверху):
  - 🔄 изменение статуса
  - ✏️ изменение любого поля (tags, resolution…)
  - 💬 комментарий
- **🏷️ Теги** — с фильтром
- **Статус перехода** — `accept / falsepositive / confirm / resolve / wontfix`
- **Новый комментарий**
- Кнопки:
  - **Сгенерировать промпт** — копирует готовый промпт в буфер
  - **Получить резолюцию ИИ** — отправляет промпт в Ollama, ответ заполняет поля
  - **Отменить комментарий (Reopen)** — если issue уже обработан
  - **Применить** — сохранить статус + комментарий + теги
  - **⚙ Ollama** — настройки модели

### 5.6. Модалка «Массовое действие»

Открывается:
- по кнопке **Действия с выбранными**
- из контекстного меню → **Добавить комментарий**

Внутри:

- **Статус перехода** — один для всей группы
- **Комментарий** — общий
- **Теги** — с фильтром
- **Сгенерировать промпт** — по всей группе (bulk)
- **Получить резолюцию ИИ** — один запрос к Ollama, ответ применяется ко всем
- **Применить** — последовательно для каждой issue

### 5.7. Модалка «Автоматическая обработка (AI)»

Открывается из контекстного меню → **Добавить автоматически комментарий**.

Полуавтоматический процесс:

1. Нажать **▶ Запустить**.
2. Модалка показывает текущий issue и шлёт запрос в Ollama.
3. После ответа — статус, комментарий и AI-теги заполнены автоматически.
4. Проверить → **✅ Подтвердить и далее** (или **⏭ Пропустить**).
5. Переход к следующему.

**Режимы:**

- **Полуавтоматический** (по умолчанию) — ждёт подтверждения.
- **Автоматический** — снимается галка, применяет через 1 сек без подтверждения.

Кнопка **⏹ Остановить** прерывает запрос к Ollama.

### 5.8. Экспорт отчёта (только Accepted)

Кнопка **📥 Экспорт MD** в шапке вкладки.

Экспортирует **текущий отфильтрованный список** в markdown-файл:

```markdown
# Обработанные issues — MEDMIS-MISmobile.doctor_api_spec

**Фильтр:** severity=HIGH | tags=require-fix | text="Auth"
**Всего issues (до фильтра):** 95
**В отчёте (после фильтра):** 12
**Файлов в отчёте:** 3
**Сгенерировано:** 15.01.2024, 14:32:11

---

## 📁 Файл: `src/Api/AuthApi.php`
**Замечаний в файле:** 5

### Issue #1 — `9319f3fb-...`

- **Сообщение:** Add a "case default" clause to this "switch" statement.
- **Правило:** Switch statements should have a default clause
- **Ключ правила:** `php:S131`
- **Файл:** `src/Api/AuthApi.php`
- **Строка:** 192
- **Критичность:** HIGH
- **Статус:** WONTFIX
- **Активные теги:** `error-handling`, `require-fix`

**Последний комментарий:**
> **ivanov** (15.01.2024, 14:28:00):
> Сгенерированный код, дублирование — стилистика.

**Фрагмент кода:**

```php
 190 |     switch ($statusCode) {
 192 | >>>     case 401: <<<
 ...
```

---
```

Имя файла: `accepted_MEDMIS-MISmobile.doctor_api_spec_1736998800000.md`.

---

## 6. Интерактивная консоль

Доступна при запуске **без** `--no-console`. Приглашение выглядит так:

```
sonar:MEDMIS-MISmobile.doctor_api_spec>
```

Во время AI-обработки появляется маркер `[auto]`:

```
sonar[auto]:MEDMIS-MISmobile.doctor_api_spec>
```

### 6.1. Шпаргалка по командам

```
help                    полная справка

status                  состояние сервера (сессия / снимок / auto)
health                  краткая проверка
project [KEY | clear]   показать/задать консольный project

session show            прочитать session.txt
session set <path>      заменить session.txt из файла
session reload          перечитать session.txt с диска
session save <path>     сохранить текущий raw в файл

refresh                 пересобрать снимок в фоне
refresh wait            пересобрать синхронно (ждём)
refresh issue <KEY>     обновить один issue в снимке

summary [SEV] [QUAL]              сводка + facets
matrix [SEV] [QUAL]               матрица file × rule
perfile [SEV] [QUAL]              issues по файлам
details [SEV] [QUAL] [snippets=N] подробный отчёт с кодом
issues [SEV] [QUAL]               плоский список
keys [SEV] [QUAL]                 только ключи
snippet <KEY>                     один issue + код

mark <KEY> <transition> [comment...]      один issue
mark <transition> [SEV] [QUAL] [--yes]    массово (--yes = применить)
cancel <KEY>                              снять пометку (reopen)
cancelall [SEV] [QUAL] [--yes]            снять массово

prompt list
prompt status-comment <KEY> status=accept
prompt bulk-status-comment keys=A,B,C status=accept
prompt analyze-issue <KEY>
prompt auto-resolve <KEY> save=/tmp/prompt.txt

ollama show
ollama set url http://host:11434
ollama set model qwen2.5-coder:14b
ollama set timeout 300
ollama set temperature 0.2
ollama test
ollama reload

auto [SEV] [QUAL]           dry-run массовой AI-обработки
auto [SEV] [QUAL] --yes     запустить обработку в фоне
auto key <KEY> [--yes]      обработать один issue
stop                        graceful-остановка auto

quit                    выйти из консоли (сервер продолжит)
```

### 6.2. Примеры: что и как вводить

**Получить сводку по HIGH / SECURITY:**

```
> summary HIGH SECURITY
```

**Показать все issues по файлам:**

```
> perfile ALL ALL
```

**Найти ключ по коду:**

```
> snippet 9319f3fb-fb6e-42a4-b7b4-4c421396c1c8
```

**Снять пометки (reopen) у всех MEDIUM / MAINTAINABILITY:**

```
> cancelall MEDIUM MAINTAINABILITY            # dry-run
> cancelall MEDIUM MAINTAINABILITY --yes      # применить
```

**Dry-run AI-обработки:**

```
> auto ALL ALL
```

Покажет: сколько issues, примеры первых 5, что будет обработано.

**Запустить массовую AI-обработку:**

```
> auto ALL ALL --yes
```

Прогресс идёт в stderr (по строкам `[N/M] KEY → статус OK/FAIL`). Во время процесса доступны `stop` и `status`.

**Продолжить после session expired:**

1. Обновить `session.txt` из браузера.
2. `session reload`
3. `auto <SEV> <QUAL> --yes` — продолжит с того же места (уже обработанные issues не подхватятся, т.к. они больше не OPEN).

---

## 7. REST API

Все эндпоинты доступны с любого HTTP-клиента (`curl`, Postman, ваш frontend).

### 7.1. Снимок и статус

| Метод | URL | Назначение |
|---|---|---|
| `GET`  | `/api/health` | Краткая проверка (для мониторинга) |
| `GET`  | `/api/status` | Полное состояние сервера |
| `POST` | `/api/refresh?project=KEY` | Пересобрать снимок (async) |
| `POST` | `/api/refresh?project=KEY&wait=true` | Пересобрать снимок (sync) |
| `POST` | `/api/refresh/issue?project=KEY&key=ISSUE_KEY` | Точечное обновление issue |

### 7.2. Сессия

| Метод | URL | Назначение |
|---|---|---|
| `GET`  | `/api/session` | Прочитать `session.txt` |
| `PUT`  | `/api/session?project=KEY` | Записать новый raw + перезагрузить + auto build |
| `POST` | `/api/session/reload?project=KEY` | Перечитать с диска |

### 7.3. Отчёты (только чтение)

| URL | Назначение |
|---|---|
| `/api/summary?project=KEY&sev=&qual=` | Сводка + facets |
| `/api/matrix?project=KEY&sev=&qual=` | Матрица файл × правило |
| `/api/perfile?project=KEY&sev=&qual=` | Issues по файлам |
| `/api/details?project=KEY&sev=&qual=&snippets=N` | Подробно с кодом |
| `/api/issues?project=KEY&sev=&qual=` | Плоский список |
| `/api/keys?project=KEY&sev=&qual=` | Только ключи |
| `/api/accepted?project=KEY&sev=&qual=&facets=true` | Уже обработанные |
| `/api/open_issues?project=KEY&sev=&qual=&snippets=true` | Только OPEN / REOPENED |
| `/api/snippet?project=KEY&issueKey=KEY` | Один issue + код |
| `/api/changelog?project=KEY&issueKey=KEY` | История изменений |
| `/api/measures?project=KEY` | Метрики проекта |
| `/api/metadata?project=KEY` | Языки / ветки / PR / settings |
| `/api/tags` | Все теги SonarQube |

### 7.4. Изменение (мутации)

| Метод | URL | Назначение |
|---|---|---|
| `POST` | `/api/mark?project=KEY&key=ISSUE_KEY&transition=accept&comment=...&dryRun=false` | Сменить статус одного issue |
| `POST` | `/api/mark?project=KEY&sev=ALL&qual=ALL&transition=accept&dryRun=false` | Сменить статус массово |
| `POST` | `/api/cancel?project=KEY&key=ISSUE_KEY&dryRun=false` | Reopen одного |
| `POST` | `/api/set_tags?project=KEY&keys=A,B,C&tags=tag1,tag2` | Установить теги |

> `dryRun=true` (по умолчанию) — только показать план, ничего не менять. Для реального применения **обязательно** `dryRun=false`.

### 7.5. Промпты

| URL | Назначение |
|---|---|
| `/api/prompt/list` | Список доступных шаблонов в `prompts/` |
| `/api/prompt/build?name=NAME&project=KEY&issueKey=KEY` | Готовый промпт для одного issue |
| `/api/prompt/build?name=NAME&project=KEY&keys=A,B,C` | Для группы |
| `/api/prompt/build?name=NAME&project=KEY&sev=ALL&qual=ALL` | По фильтру |

Доступные `name`:
- `status-comment`
- `bulk-status-comment`
- `analyze-issue`
- `auto-resolve`

### 7.6. Примеры curl

**Проверить сервер:**

```bash
curl -s http://localhost:6060/api/health | jq
```

**Список ключей по HIGH/SECURITY:**

```bash
curl -s "http://localhost:6060/api/keys?project=MY_PROJECT_KEY&sev=HIGH&qual=SECURITY" | jq -r '.keys[]'
```

**Dry-run reopen всех MEDIUM/MAINTAINABILITY:**

```bash
curl -s -X POST "http://localhost:6060/api/mark?project=MY_PROJECT_KEY&sev=MEDIUM&qual=MAINTAINABILITY&transition=reopen&dryRun=true"
```

**Реально снять пометки (осторожно!):**

```bash
curl -s -X POST "http://localhost:6060/api/mark?project=MY_PROJECT_KEY&sev=MEDIUM&qual=MAINTAINABILITY&transition=reopen&dryRun=false"
```

**Получить промпт для одного issue:**

```bash
curl -s "http://localhost:6060/api/prompt/build?project=MY_PROJECT_KEY&issueKey=9319f3fb-...&name=status-comment&status=accept" \
  | jq -r .prompt > /tmp/prompt.txt
```

**Экспорт в Markdown — только через web** (REST не отдаёт готовый .md; MD генерируется на клиенте из `/api/accepted` + `/api/snippet`).

---

## 8. Типовые сценарии

### 8.1. «Хочу быстро посмотреть все issues проекта»

1. Запустить JAR с `--project=MY_KEY`.
2. Дождаться `● ready · N issues`.
3. Вкладка **Отчет по файлам (Per-File)** — issues по файлам.
4. Вкладка **Матрица** — где больше всего проблем.

### 8.2. «Хочу разобрать один issue вручную»

1. Вкладка **Код ошибки (Snippet)**.
2. Ввести `issueKey` в фильтре (с автодополнением) → Enter.
3. Модалка покажет код, историю, комментарии.
4. Кнопка **Установить статус** → выбрать статус + написать комментарий → **Применить**.

### 8.3. «Хочу применить один статус ко всем issues по правилу»

Пока фильтр по правилу в SonarQube API не выделен, работаем через Per-File:

1. Вкладка **Отчет по файлам (Per-File)**.
2. Отфильтровать по правилу (поле «Фильтр»).
3. Выделить нужные issues (можно галкой «Выделить всё» в шапке).
4. **Действия с выбранными (N)** → массовая модалка.

### 8.4. «Хочу прогнать AI-обработку по всем HIGH/SECURITY»

Из консоли:

```
> auto HIGH SECURITY              # dry-run — посмотреть, сколько issues
> auto HIGH SECURITY --yes        # запустить обработку
```

Или через web:

1. Вкладка **Все ошибки (Issues)**.
2. Фильтры: `sev=HIGH`, `qual=SECURITY` → **Загрузить**.
3. ПКМ на любой строке → **Добавить автоматически комментарий**.
4. В модалке **▶ Запустить**.

### 8.5. «Хочу выгрузить отчёт для review»

1. Вкладка **Обработанные (Accepted)**.
2. Применить фильтры: severity / type / resolution / теги / текстовый поиск.
3. Кнопка **📥 Экспорт MD**.
4. Файл `accepted_MY_PROJECT_<timestamp>.md` скачается в загрузки.

### 8.6. «Хочу отредактировать теги у одной issue»

1. Вкладка Snippet / Issues / Per-File.
2. ПКМ → **🏷️ Редактировать теги**.
3. Отметить нужные → **Сохранить**.

Теги в SonarQube общие для всех — обработанные и необработанные issue используют один и тот же список.

### 8.7. «Хочу снять пометки (reopen) со всех issues по фильтру»

Через консоль:

```
> cancelall MEDIUM MAINTAINABILITY           # dry-run
> cancelall MEDIUM MAINTAINABILITY --yes     # применить
```

Через API:

```bash
curl -X POST "http://localhost:6060/api/cancel?project=MY_KEY&sev=MEDIUM&qual=MAINTAINABILITY&dryRun=false"
```

Через web:

1. Вкладка **Обработанные (Accepted)**.
2. Выделить галками нужные issue.
3. **Reopen выбранные (N)**.

---

## 9. Настройка Ollama

### 9.1. Запустить Ollama

```bash
OLLAMA_HOST=0.0.0.0 OLLAMA_ORIGINS='*' ollama serve
```

- `OLLAMA_HOST=0.0.0.0` — принимать запросы не только с localhost.
- `OLLAMA_ORIGINS='*'` — разрешить CORS (иначе браузер заблокирует запросы).

### 9.2. Скачать модель

```bash
ollama pull qwen2.5-coder:14b
# или
ollama pull llama3
```

### 9.3. Настроить в приложении

**Web:** кнопка **⚙ Ollama** в шапке → URL → **Обновить список** → выбрать модель → **Сохранить**.

**Консоль:**

```
> ollama set url http://localhost:11434
> ollama set model qwen2.5-coder:14b
> ollama test
```

### 9.4. Проверить соединение

```bash
ollama test     # в консоли
```

Или через web — кнопка **⚙ Ollama** → **Проверить доступ**.

### 9.5. Как работают шаблоны промптов

Все промпты берутся из каталога `prompts/` **при каждом вызове** — можно править `.txt` без рестарта сервера.

- `prompts/auto-resolve.txt` — используется в auto-обработке и в консольной `auto`.
- `prompts/status-comment.txt` — используется при разборе одного issue.
- `prompts/bulk-status-comment.txt` — при массовой обработке группы.
- `prompts/analyze-issue.txt` — при «Сгенерировать промпт» из контекстного меню.

Правки в этих файлах мгновенно влияют на результат: LLM получает именно то, что вы написали.

---

## 10. Частые проблемы и что делать

| Симптом | Причина | Решение |
|---|---|---|
| `sessionReady: false` | Не заполнен `session.txt` | Заполнить + `session reload` |
| `snapshotStatus: FAILED` | Сессия истекла / 401 | Обновить cookie, `refresh wait` |
| В web пусто, статус «snapshot empty» | Снимок не построен | Кнопка **↻ Refresh snapshot** |
| **400 Bad Request** на `/api/mark` | `transition=` пустой | Проверить, что LLM вернул статус — обновите `parse-response.js` (см. последние фиксы) |
| **400 Bad Request** на `/api/set_tags` | Не передан `project` | Обновить `common.js` / `showTagEditor` |
| `Network/CORS` при обращении к Ollama | Нет `OLLAMA_ORIGINS` | Перезапустить Ollama с `OLLAMA_ORIGINS='*'` |
| `Ollama не настроена` | Не указан url/model | **⚙ Ollama** → сохранить |
| Список тегов обрезается | CSS z-index | Применить фикс `.pf-header-sticky { z-index: 1000 }` |
| Пустой `prompt` в ответе `/api/prompt/build` | Файл `prompts/NAME.txt` не найден или пуст | Проверить `prompts/`, `/api/prompt/list` |
| Слишком долгая сборка снимка | Много issues + много сниппетов | `--no-snippets` или `--snippet-limit=500` |

### Логи

- **stdout**: `[ApiServer]`, `[Snapshot]`, `[ServerState]`, `[Bootstrap]`, `[console]`.
- **stderr**: прогресс auto-обработки, ошибки, `[auto] ...`.

Полезные команды:

```bash
# смотреть всё в реальном времени
java -jar ... 2>&1 | tee sonar.log

# только ошибки
java -jar ... 2>&1 | grep -E "FAIL|ERROR|Exception"
```

---

## 11. Что делать после обновления JAR

Обновления безопасны — **ваши данные не тронуты**:

| Файл / каталог | Заменяется? |
|---|---|
| `session.txt` | ❌ нет |
| `ollama.properties` | ❌ нет |
| `prompts/*.txt` | ❌ нет (только недостающие создаются) |
| `www/` (статика) | ❌ нет (обновляется вручную `cp -r web/* www/`) |
| Собственно `.jar` | ✅ да, заменяется целиком |

Порядок обновления:

```bash
# 1. Остановить сервер
Ctrl+C (или systemctl stop sonar-rest-api)

# 2. Заменить JAR
mv SonarRestApi-1.0-SNAPSHOT.jar SonarRestApi-1.0-SNAPSHOT.jar.bak
cp /new/build/SonarRestApi-1.0-SNAPSHOT.jar .

# 3. Обновить статику (по желанию)
cp -r /new/build/web/* www/

# 4. Запустить
java -jar SonarRestApi-1.0-SNAPSHOT.jar --project=MY_KEY

# 5. Пересобрать снимок (могли измениться форматы)
> refresh wait
```

---

## 12. Краткая справка (карточка на стену)

```
ЗАПУСК:
  java -jar app.jar --project=MY_KEY
  открыть http://localhost:6060/

СЕССИЯ:
  вкладка Session → вставить заголовки → Save

СНИМОК:
  кнопка ↻ Refresh snapshot

WEB:
  Per-File       — список по файлам
  Details        — с кодом
  Issues         — плоский
  Accepted       — обработанные + Экспорт MD
  Snippet        — один issue по key

КОНСОЛЬ:
  refresh wait                    пересобрать снимок
  summary HIGH SECURITY           сводка
  auto HIGH SECURITY --yes        AI-обработка
  stop                            остановить auto
  cancelall MEDIUM ALL --yes      reopen массово
  help                            всё остальное

REST:
  GET  /api/health
  POST /api/refresh?project=K
  POST /api/mark?project=K&key=KEY&transition=accept&dryRun=false
  GET  /api/keys?project=K&sev=ALL&qual=ALL
```

---

## См. также

- `deploy.md` — установка, systemd, структура проекта, Maven.
- `README.md` — общее описание проекта.
- `prompts/*.txt` — редактируемые шаблоны промптов для LLM.
