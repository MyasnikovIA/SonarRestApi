# SonarRestApi

Обёртка над SonarQube: **REST API**, **Web UI**, **интерактивная консоль** и **AI-обработка issues через Ollama**. Позволяет выгружать, фильтровать, помечать и автоматически разбирать замечания SonarQube — с сохранением тегов, комментариев и истории изменений.

## Что внутри

| Компонент | Где | Что делает |
|---|---|---|
| REST API | `src/main/java/ru/miacomsoft/sonar/server/handlers/` | ~27 эндпоинтов (сессия, снимок, отчёты, пометки, теги, промпты) |
| Web UI | `web/` → `www/` | 9 вкладок (Summary, Matrix, Per-File, Details, Issues, Accepted, Snippet, Session, Status) |
| Консоль | `Console.java` | Дубли REST-эндпоинтов в stdin/stdout + `auto` + `prompt` + `ollama` |
| AI-обработка | `AutoProcessor.java` + `prompts/auto-resolve.txt` | Цикл `промпт → Ollama → bulk_change` |
| Снимок проекта | `Snapshot.java` | Локальный кэш всех issues — избавляет от повторных запросов к Sonar |
| Шаблоны промптов | `prompts/*.txt` | Редактируются без рестарта сервера |

## Быстрый старт

```bash
# 1. Собрать (см. doc/deploy.md, если впервые)
mvn clean package

# 2. Запустить
java -jar target/SonarRestApi-1.0-SNAPSHOT.jar --project=MY_PROJECT_KEY

# 3. Открыть браузер
#    http://localhost:6060/

# 4. Заполнить session.txt (DevTools → Copy request headers)
# 5. Нажать "↻ Refresh snapshot" в шапке
```

Через 1–3 минуты снимок готов, все вкладки работают. Подробнее — [`doc/user_guide.md`](doc/user_guide.md).

## Документация

Вся документация — в каталоге [`doc/`](doc/). Ниже — навигация по ролям и задачам.

### 🚀 С чего начать

| Документ | Кому | Что внутри |
|---|---|---|
| [`doc/deploy.md`](doc/deploy.md) | Админ / первый запуск | Требования, сборка (fat JAR), запуск, systemd, параметры CLI, устранение неполадок |
| [`doc/user_guide.md`](doc/user_guide.md) | Все | Быстрый старт, работа с JAR, Web-интерфейс (вкладки, модалки), консоль (шпаргалка), REST (обзор), сценарии, обновление |
| [`doc/infoSession.md`](doc/infoSession.md) | Все, у кого протухла сессия | Как собрать `session.txt` из DevTools, 4 способа применить, что делать при `401 Unauthorized` |

### 📖 Полные справочники

| Документ | Для кого | Что внутри |
|---|---|---|
| [`doc/Info.md`](doc/Info.md) | Разработчик интеграции | Полный справочник REST API: 27 эндпоинтов, все поля JSON, примеры ответов, коды ошибок, безопасность |
| [`doc/console.md`](doc/console.md) | Power user / скрипты | Полное руководство по консоли: все команды (`summary`, `mark`, `prompt`, `ollama`, `auto`, `stop`), форматы вывода, статистика AI-обработки |

### 💬 Примеры и диалоги

Помогают понять «как это выглядит на практике» — что пишет пользователь и какой эндпоинт (или команда) под этим стоит.

| Документ | Для кого | Что внутри |
|---|---|---|
| [`doc/example.md`](doc/example.md) | Разработчик | Готовые URL и `curl` для всех эндпоинтов. Разделы: чтение (GET), изменение (POST/PUT), примеры с `jq`, общие параметры, полный список эндпоинтов |
| [`doc/infoRestApi.md`](doc/infoRestApi.md) | Автор чат-бота | «Каталог диалогов»: реплика пользователя → REST-эндпоинт → ответ в чат. 21 раздел, включая новые `/accepted`, `/changelog`, `/set_tags`, `/prompt/build` |
| [`doc/infoConsole.md`](doc/infoConsole.md) | Автор чат-бота | Тот же «каталог диалогов», но для **консоли**: 17 разделов, включая `prompt`, `ollama`, `auto`/`stop` |
| [`doc/сonsoleChat.md`](doc/сonsoleChat.md) | Начинающий пользователь консоли | Краткая шпаргалка по консоли: одна страница, все команды + примеры |

> ⚠️ **Примечание про `сonsoleChat.md`.** Имя файла начинается с **кириллической** буквы `с` (U+0441), а не с латинской `c`. При открытии ссылки в этом README всё работает, но при ручном наборе пути — легко ошибиться. Если переименуете в `consoleChat.md` (латиница) — обновите ссылку в этом README.

## Структура проекта

```
SonarRestApi_git/
├── pom.xml
├── README.md                       ← этот файл
├── doc/                            ← вся документация
│   ├── deploy.md
│   ├── user_guide.md
│   ├── infoSession.md
│   ├── Info.md
│   ├── console.md
│   ├── example.md
│   ├── infoRestApi.md
│   ├── infoConsole.md
│   └── сonsoleChat.md
├── src/main/java/ru/miacomsoft/sonar/
│   ├── Main.java                   ← точка входа
│   ├── Issue.java, Measures.java, Reporter.java, …
│   └── server/
│       ├── ApiServer.java          ← регистрация эндпоинтов
│       ├── Console.java            ← интерактивная консоль
│       ├── AutoProcessor.java      ← AI-обработка через Ollama
│       ├── Snapshot.java           ← локальный кэш issues
│       ├── ServerState.java        ← состояние сервера
│       ├── handlers/               ← ~20 REST-хэндлеров
│       └── ollama/                 ← клиент к LLM
├── web/                            ← фронтенд (при сборке → www/)
│   ├── index.html
│   ├── app.js, common.js, ollama.js
│   └── tabs/                       ← 9 вкладок UI
├── prompts/                        ← шаблоны промптов (редактируются)
│   ├── auto-resolve.txt
│   ├── status-comment.txt
│   ├── bulk-status-comment.txt
│   └── analyze-issue.txt
├── session.txt                     ← сессия Sonar (создаётся автоматически)
└── ollama.properties               ← настройки LLM (создаётся автоматически)
```

## Ключевые концепции

- **Снимок (`Snapshot`).** Локальная копия всех issues проекта с `ruleName` и сниппетами. Все data-эндпоинты читают из снимка — это в разы быстрее прямых запросов к SonarQube. Обновляется кнопкой **↻ Refresh snapshot**, `POST /api/refresh` или командой `refresh`.
- **Промпты как файлы.** Все тексты для LLM лежат в `prompts/*.txt`. Редактируются на лету — правки применяются сразу (файл читается при каждом вызове).
- **AI-обработка.** Массовая пометка issues через Ollama: для каждого берётся `auto-resolve.txt`, отправляется в LLM, ответ парсится и применяется через `bulk_change`. Есть в консоли (`auto … --yes`) и в Web (кнопка «Добавить автоматически комментарий»).
- **Dry-run по умолчанию.** Все `POST /api/mark` и `POST /api/cancel` без `dryRun=false` работают в режиме «показать план». В консоли то же самое — без `--yes`. Случайно ничего не сломается.

## Требования

- **JDK 17+**, **Maven 3.8+**
- **SonarQube** 9.x / 10.x (для API)
- **Ollama** — опционально, только для AI-функционала

Подробнее — [`doc/deploy.md`](doc/deploy.md).

## Поддержка

- **Что-то не работает при старте** → [`doc/deploy.md`](doc/deploy.md), раздел «Устранение неполадок»
- **Протухла сессия / `401 Unauthorized`** → [`doc/infoSession.md`](doc/infoSession.md)
- **Не понимаю, что возвращает эндпоинт** → [`doc/Info.md`](doc/Info.md)
- **Хочу быстро попробовать REST** → [`doc/example.md`](doc/example.md)
- **Что-то не получается в консоли** → [`doc/console.md`](doc/console.md) или [`doc/infoConsole.md`](doc/infoConsole.md)
- **Настраиваю чат-бота поверх API** → [`doc/infoRestApi.md`](doc/infoRestApi.md)
```

---

## Что в README и почему так

| Блок | Назначение |
|---|---|
| **Что внутри** | 30-секундное понимание: REST + Web + Console + AI + Snapshot + Prompts — с указанием, где что в репозитории |
| **Быстрый старт** | 5 строк — от `mvn package` до работающего UI |
| **Документация → С чего начать** | Для тех, кто первый раз: установка, работа, сессия |
| **Документация → Полные справочники** | Для тех, кто пишет интеграцию или активно использует консоль |
| **Документация → Примеры и диалоги** | Для тех, кто «не знает, с чего начать вопрос» — 4 файла с готовыми сценариями под разные аудитории |
| **Структура проекта** | Один взгляд — где что лежит. `doc/` показан с именами файлов (совпадают со ссылками) |
| **Ключевые концепции** | 4 пункта — чтобы не читать 200 КБ документации прежде, чем понять, как устроено |
| **Требования** | Минимум для старта + ссылка на deploy.md |
| **Поддержка** | 7 типовых вопросов → 7 точных ссылок в `doc/` |

## Отдельно — про имя `сonsoleChat.md`

Файл в каталоге `doc/` называется **`сonsoleChat.md`** (с **кириллической** `с`). README ссылается на него корректно (путь совпадает с реальным именем на диске), поэтому в браузере и в GitHub ссылка работает.

Но если кто-то наберёт путь вручную или скопирует без проверки — получит 404. Рекомендую **переименовать** файл в `consoleChat.md` (вся латиница) и заменить ссылку в README:

```diff
- [`doc/сonsoleChat.md`](doc/сonsoleChat.md)
+ [`doc/consoleChat.md`](doc/consoleChat.md)
```

Проверить, что действительно кириллица, можно так:

```bash
# В PowerShell
Get-ChildItem doc\*.md | Where-Object { $_.Name -match '[^\x00-\x7F]' }
# или просто посмотреть первые байты имени:
[System.Text.Encoding]::UTF8.GetBytes('сonsoleChat.md')[0..2]
# 'с' кириллическая = 209 129 (UTF-8), латинская 'c' = 99
```