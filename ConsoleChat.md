# Инструкция по работе в интерактивной консоли

## Варианты имени файла

На выбор (в порядке предпочтения):

| Имя | Когда уместно |
|---|---|
| **`CONSOLE.md`** | ✅ рекомендуется — коротко, в верхнем регистре, рядом с `README.md` |
| `CONSOLE_GUIDE.md` | если есть отдельный `GUIDE.md` для REST — параллельно |
| `docs/console.md` | если вся документация в подпапке `docs/` |
| `README-console.md` | если хочется подчеркнуть «часть README» |
| `QUICKSTART-console.md` | если это именно быстрый старт, а полный справочник — в `console.md` |

Совет: если у вас уже есть подробный `console.md` — новый файл назовите **`CONSOLE.md`** и сделайте его **кратким справочником** (шпаргалкой), сославшись в шапке: *«Полное описание — в `console.md`»*.

---

# CONSOLE.md

Краткая инструкция по интерактивной консоли `SonarRestApi`.

## 1. Запуск

Консоль стартует **автоматически** вместе с REST-сервером.

```bash
# Минимум: REST на 0.0.0.0:6060 + консоль, session.txt из CWD
java -jar SonarRestApi.jar

# С проектом по умолчанию (иначе указывать ?project= / project в консоли)
java -jar SonarRestApi.jar --project=MEDMIS-MISmobile.doctor_api_spec

# Явный путь к session.txt
java -jar SonarRestApi.jar --session=/etc/sonar/session.txt \
                           --project=MEDMIS-MISmobile.doctor_api_spec

# Только REST, без консоли (демон)
java -jar SonarRestApi.jar --no-console
```

После старта видно:

```
===================================================
 Sonar REST API server started
 Bind:        0.0.0.0:6060
===================================================

===================================================
 Console ready. Type 'help' for commands.
===================================================
[Warmup] sync: собираю снимок для MEDMIS-MISmobile.doctor_api_spec ...
sonar:MEDMIS-MISmobile.doctor_api_spec> _
```

Приглашение `sonar:PROJECT>` показывает **консольный** project (независим от `defaultProject` HTTP-сервера).

---

## 2. Быстрый старт (типичный сценарий)

```
sonar:MY_PROJECT> status                                   # проверить сессию и снимок
sonar:MY_PROJECT> summary HIGH RELIABILITY                 # сколько issues
sonar:MY_PROJECT> details HIGH RELIABILITY 3               # посмотреть с кодом
sonar:MY_PROJECT> snippet <KEY>                            # один issue целиком
sonar:MY_PROJECT> mark <KEY> accept "Согласовано"          # пометить один
sonar:MY_PROJECT> mark accept MEDIUM MAINTAINABILITY --yes # пометить массово
sonar:MY_PROJECT> cancelall MEDIUM MAINTAINABILITY --yes   # откатить
sonar:MY_PROJECT> quit                                     # выход
```

---

## 3. Справочник команд

### 3.1 Служебные

| Команда | Что делает |
|---|---|
| `help`, `?`, `h` | справка по всем командам |
| `status` | сессия + снимок + прогресс сборки |
| `health` | краткая проверка (`sessionReady`, `snapshotStatus`) |
| `project [KEY \| clear]` | показать/переключить консольный project |
| `quit`, `exit`, `q` | выход (REST тоже останавливается) |

### 3.2 Сессия

| Команда | Что делает |
|---|---|
| `session show` | прочитать текущий `session.txt` |
| `session set <path>` | заменить `session.txt` из файла + reload + auto build |
| `session reload` | перечитать `session.txt` с диска |
| `session save <path>` | сохранить текущий raw в файл |

### 3.3 Снимок

| Команда | Что делает |
|---|---|
| `refresh` | пересобрать снимок в фоне (async) |
| `refresh wait` | пересобрать синхронно (блокирует консоль) |
| `refresh issue <KEY>` | обновить/удалить один issue в снимке |

### 3.4 Отчёты

Формат: `<команда> [SEV] [QUAL] [...]` — по умолчанию `sev=MEDIUM`, `qual=MAINTAINABILITY`.

| Команда | Что выводит |
|---|---|
| `summary [SEV] [QUAL]` | всего + facets (правила/файлы/severities) |
| `matrix [SEV] [QUAL]` | таблица file × rule |
| `perfile [SEV] [QUAL]` | issues по файлам |
| `details [SEV] [QUAL] [N]` | подробно, с кодом (N issues на файл, по умолчанию 5) |
| `issues [SEV] [QUAL]` | плоский список |
| `keys [SEV] [QUAL]` | только ключи |
| `snippet <KEY>` | один issue + фрагмент кода |

### 3.5 Пометки

| Команда | Что делает |
|---|---|
| `mark <KEY> <transition> [comment...]` | пометить **один** issue |
| `mark <transition> [SEV] [QUAL] [--yes]` | пометить **все** по фильтру |
| `cancel <KEY>` | снять пометку с одного (reopen) |
| `cancelall [SEV] [QUAL] [--yes]` | снять пометки по фильтру |

---

## 4. Форматы параметров

| Параметр | Допустимые значения |
|---|---|
| `SEV` | `MEDIUM` \| `HIGH` \| `LOW` \| `INFO` \| `ALL` |
| `QUAL` | `MAINTAINABILITY` \| `RELIABILITY` \| `SECURITY` \| `ALL` |
| `transition` | `accept` \| `falsepositive` \| `confirm` \| `resolve` \| `wontfix` \| `reopen` |

Регистр не важен (`high` = `HIGH`).

---

## 5. Dry-run и подтверждение массовых операций

- Команды `mark <transition> …` и `cancelall …` **без `--yes`** работают в режиме **dry-run** — печатают план, ничего не меняют.
- С `--yes` — применяют.
- Одиночные `mark <KEY>` и `cancel <KEY>` применяются **сразу** (это точечное действие, ошибиться сложно).

Пример:

```
sonar:MY_PROJECT> mark accept MEDIUM MAINTAINABILITY
project    = MY_PROJECT
severity   = MEDIUM
quality    = MAINTAINABILITY
transition = accept
issues     = 87
batches    = 1 (размер 500)

DRY RUN — ничего не изменено.
Чтобы применить: mark accept MEDIUM MAINTAINABILITY --yes
```

---

## 6. Типичные сценарии

### Сменить сессию (перелогин в браузере)
```
sonar:MY_PROJECT> session set new_session.txt
[console] session updated from new_session.txt
          host = sonar.example.com
[console] snapshot rebuild started in background
```
Следить за прогрессом — `status`.

### Обновить один issue после правки кода
```
sonar:MY_PROJECT> refresh issue 9ede9b09-5875-4fbf-8a21-200346c8e415
[console] refreshing issue ... ...
  removed = false   (или true + reason=status_changed|not_found)
  issue   = 9ede9b09-... [MEDIUM/OPEN] src/main/.../Consumer.java:42
```

### Пометить всё HIGH/RELIABILITY как «принято», с комментарием
```
sonar:MY_PROJECT> mark accept HIGH RELIABILITY --yes
```
Комментарий для массовой операции через `--yes` не передаётся — для комментария используйте одиночный `mark <KEY>`.

### Откатить неудачный массовый accept
```
sonar:MY_PROJECT> cancelall HIGH RELIABILITY           # dry-run: сколько будет
sonar:MY_PROJECT> cancelall HIGH RELIABILITY --yes     # применить
```

### Посмотреть код под конкретным issue
```
sonar:MY_PROJECT> snippet 1a2b3c4d-5e6f-7890-abcd-ef1234567890
```
Печатает шапку (message/rule/severity) + фрагмент кода вокруг строки issue.

### Работать с другим проектом, не трогая REST
```
sonar:MY_PROJECT> project OTHER_PROJECT
[console] console project = OTHER_PROJECT
sonar:OTHER_PROJECT> refresh wait
```
HTTP-`defaultProject` при этом **не меняется** — REST-клиенты продолжают работать с прежним.

---

## 7. Отличия консоли от REST

| | REST API | Console |
|---|---|---|
| Формат вывода | JSON | человекочитаемый |
| Project по умолчанию | `--project` / `SONAR_PROJECT` | команда `project <KEY>` (своя переменная) |
| Dry-run | `?dryRun=false` — применить | `--yes` — применить |
| Доступ | по сети | только stdin процесса |
| Авторизация | Bearer (если включён) | не нужна |
| Автообновление после `mark`/`cancel` | нет | да, `refresh issue` для одиночных операций |

---

## 8. Завершение

```
sonar:MY_PROJECT> quit
[console] shutting down...
[main] shutdown complete
```

`quit`/`exit`/`q` останавливает **и консоль, и REST** — процесс выходит. Если нужен только отключённый ввод, но работающий REST — используйте `Ctrl+C` (сработает shutdown hook) или запускайте с `--no-console`.

---

> Полное описание с примерами вывода — в `console.md`.
> Справочник по HTTP-эндпоинтам — в `Info.md`.
> Примеры curl-запросов — в `example.md`.