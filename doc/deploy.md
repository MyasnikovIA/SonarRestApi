# Инструкция по развёртыванию проекта

# Sonar Rest API — инструкция по развёртыванию

## 1. Требования

| Компонент | Версия | Обязателен |
|---|---|---|
| JDK | 17+ | да |
| Maven | 3.8+ | да |
| SonarQube | 9.x / 10.x | да (для API) |
| Ollama | любая | опционально (для AI-обработки) |

Проверить Java и Maven:

```bash
java -version    # должно быть 17 или выше
mvn -version     # Maven 3.8+
```

Если `mvn` не установлен — установить:

- Windows: скачать `apache-maven-3.9.x-bin.zip` с https://maven.apache.org/download.cgi, распаковать, добавить `bin/` в `PATH`.
- Linux (Debian/Ubuntu): `sudo apt install maven`
- macOS: `brew install maven`

---

## 2. Структура проекта

```
SonarRestApi/
├── pom.xml
├── src/main/java/ru/miacomsoft/sonar/          # Java-исходники
│   ├── Main.java                               # точка входа
│   ├── server/                                 # HTTP-сервер, хэндлеры
│   └── ...
├── web/                                        # фронтенд (для сборки → www/)
│   ├── index.html
│   ├── app.js
│   ├── style.css
│   ├── common.js
│   ├── ollama.js
│   └── tabs/                                   # вкладки UI
├── prompts/                                    # шаблоны промптов для LLM
│   ├── auto-resolve.txt
│   ├── status-comment.txt
│   ├── bulk-status-comment.txt
│   └── analyze-issue.txt
├── session.txt                                 # сессия SonarQube (создаётся автоматически)
├── ollama.properties                           # настройки Ollama (создаётся автоматически)
└── DEPLOY.md                                   # этот файл
```

> В репозитории фронтенд может лежать в `web/`. При сборке его нужно положить в каталог `www/` **рядом с JAR** — сервер ищет статику автоматически (см. раздел 5).

---

## 3. Сборка

### 3.1. Стандартная сборка (fat JAR)

В `pom.xml` по умолчанию **не подключён** `maven-shade-plugin`, поэтому `mvn package` создаст обычный JAR без зависимостей. Чтобы получить единый исполняемый JAR, добавьте в `pom.xml` перед `</project>`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <version>3.5.1</version>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals><goal>shade</goal></goals>
                    <configuration>
                        <transformers>
                            <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                <mainClass>ru.miacomsoft.sonar.Main</mainClass>
                            </transformer>
                        </transformers>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Затем:

```bash
mvn clean package
```

Результат: `target/SonarRestApi-1.0-SNAPSHOT.jar` (единый JAR со всеми зависимостями).

### 3.2. Сборка без fat JAR

Если модифицировать `pom.xml` не хочется:

```bash
mvn clean package
mvn dependency:copy-dependencies -DoutputDirectory=target/lib
```

Запуск в этом случае:

```bash
java -cp "target/SonarRestApi-1.0-SNAPSHOT.jar:target/lib/*" ru.miacomsoft.sonar.Main
```

(для Windows разделитель `;` вместо `:`)

---

## 4. Запуск

### 4.1. Минимальный запуск (создастся всё, что нужно)

```bash
java -jar target/SonarRestApi-1.0-SNAPSHOT.jar --project=MY_PROJECT_KEY
```

При первом старте сервер:

1. Создаст `session.txt` с шаблоном-инструкцией (нужно заполнить вручную, см. раздел 5.1).
2. Создаст `prompts/*.txt` с дефолтными шаблонами (можно править).
3. Создаст `ollama.properties` (пустой — заполняется из web или консоли).
4. Запустит HTTP-сервер на `0.0.0.0:6060`.

Если сессия не заполнена — сервер стартует **без** снимка проекта, но `GET /api/session` будет отвечать, а web-интерфейс откроется.

### 4.2. Запуск с параметрами

```bash
java -jar target/SonarRestApi-1.0-SNAPSHOT.jar \
    --host=127.0.0.1 \
    --port=6060 \
    --threads=8 \
    --project=MEDMIS-MISmobile.doctor_api_spec \
    --batch=500 \
    --delay=500 \
    --session=/etc/sonar/session.txt \
    --ollama-config=/etc/sonar/ollama.properties
```

Полный список опций — в разделе 8.

### 4.3. Запуск с явным указанием `www/` и `prompts/`

Если статика лежит не рядом с JAR:

```bash
java -Dsonar.www=/opt/sonar/www \
     -Dsonar.prompts=/opt/sonar/prompts \
     -jar target/SonarRestApi-1.0-SNAPSHOT.jar --project=MY_PROJECT_KEY
```

### 4.4. Запуск без консоли (только REST API)

```bash
java -jar target/SonarRestApi-1.0-SNAPSHOT.jar --no-console --project=MY_PROJECT_KEY
```

Полезно для запуска из systemd / Docker.

---

## 5. Раскладка файлов на сервере

Рекомендуемая структура для production:

```
/opt/sonar-rest-api/
├── SonarRestApi-1.0-SNAPSHOT.jar
├── www/                            # ← сюда положить содержимое web/ из репозитория
│   ├── index.html
│   ├── app.js
│   ├── style.css
│   ├── common.js
│   ├── ollama.js
│   └── tabs/
├── prompts/
│   ├── auto-resolve.txt
│   ├── status-comment.txt
│   ├── bulk-status-comment.txt
│   └── analyze-issue.txt
├── session.txt
└── ollama.properties
```

**Скопировать `web/` в `www/`** перед первым запуском:

```bash
cp -r web /opt/sonar-rest-api/www
```

Сервер найдёт `www/` автоматически (ищет рядом с JAR, затем в родительском каталоге, затем в `./www`).

---

## 6. Первичная настройка

### 6.1. Заполнить `session.txt`

1. Открыть SonarQube в браузере, авторизоваться.
2. Открыть DevTools → вкладка **Network**.
3. Выполнить **любой** запрос к `/api/*` (например, кликнуть на любой проект).
4. ПКМ по запросу → **Copy** → **Copy request headers**.
5. Вставить в `session.txt` только нужные заголовки:

```
Host: sonar.example.com
Cookie: XSRF-TOKEN=...; JWT-SESSION=...
X-XSRF-TOKEN: ...
```

### 6.2. Перезагрузить сессию

**Через web:** вкладка **Сессия (Session)** → вставить текст → **Save session (PUT /api/session)**.

**Через консоль:**

```
> session reload
> refresh wait
```

**Через API:**

```bash
curl -X POST "http://localhost:6060/api/session/reload?project=MY_PROJECT_KEY"
```

### 6.3. Построить снимок проекта

**Через web:** кнопка **↻ Refresh snapshot** в шапке.

**Через консоль:**

```
> refresh wait          # синхронно
> refresh               # в фоне (async), прогресс через `status`
```

**Через API:**

```bash
curl -X POST "http://localhost:6060/api/refresh?project=MY_PROJECT_KEY&wait=true"
```

---

## 7. Доступ к web-интерфейсу

Открыть в браузере:

```
http://<host>:6060/
```

В шапке указать `project` (если не задан при запуске) и нажать **↻ Refresh snapshot**.

### Вкладки

| Вкладка | Назначение |
|---|---|
| Сводка (Summary) | Общая статистика + facets |
| Матрица (Matrix) | Файл × правило |
| Отчёт по файлам (Per-File) | Список issues, сгруппированный по файлам |
| Отчет с кодом (Details) | То же + сниппеты кода |
| Все ошибки (Issues) | Плоский список |
| **Обработанные (Accepted)** | Уже обработанные issues + экспорт в MD |
| Код ошибки (Snippet) | Детали одного issue по `issueKey` |
| Сессия (Session) | Управление `session.txt` |
| Статус (Status) | JSON-состояние сервера |

---

## 8. Настройка Ollama (опционально)

Нужно только для AI-обработки (`auto` в консоли, кнопки «Получить резолюцию ИИ» и «Добавить автоматически комментарий» в web).

### 8.1. Установить и запустить Ollama

```bash
# Linux
curl -fsSL https://ollama.com/install.sh | sh

# Запуск с поддержкой CORS (для доступа из браузера)
OLLAMA_HOST=0.0.0.0 OLLAMA_ORIGINS='*' ollama serve
```

### 8.2. Скачать модель

```bash
ollama pull qwen2.5-coder:14b
# или
ollama pull llama3
```

### 8.3. Настроить в приложении

**Через web:** кнопка **⚙ Ollama** в шапке → URL + модель → **Сохранить**.

**Через консоль:**

```
> ollama set url http://localhost:11434
> ollama set model qwen2.5-coder:14b
> ollama test
```

**Через API:** настройки хранятся в `ollama.properties`, при необходимости можно заполнить файл вручную:

```properties
url=http://localhost:11434
model=qwen2.5-coder:14b
timeout=300
temperature=0.2
```

---

## 9. Параметры командной строки

| Параметр | По умолчанию | Описание |
|---|---|---|
| `--host=` | `0.0.0.0` | Адрес привязки |
| `--port=` | `6060` | Порт |
| `--threads=` | `8` | Пул HTTP-хендлеров |
| `--batch=` | `500` | Размер батча для `bulk_change` |
| `--delay=` | `500` | Пауза между батчами (мс) |
| `--session=` | `session.txt` | Файл сессии |
| `--project=` | из `SONAR_PROJECT` | Ключ проекта по умолчанию |
| `--snippet-limit=` | `0` (все) | Лимит сниппетов в снимке |
| `--ollama-config=` | `ollama.properties` | Файл настроек Ollama |
| `--no-warmup` | выкл | Не строить снимок при старте |
| `--async-warmup` | выкл | Строить снимок в фоне |
| `--no-snippets` | выкл | Не тянуть коды в снимок |
| `--no-console` | выкл | Отключить интерактивную консоль |

Позиционные аргументы (для обратной совместимости):

```
java -jar app.jar <sessionFile> <project>
```

Переменные окружения:

- `SONAR_PROJECT` — ключ проекта по умолчанию
- `-Dsonar.www=` — путь к статике
- `-Dsonar.prompts=` — путь к промптам

---

## 10. Запуск как systemd-сервис (Linux)

Создать `/etc/systemd/system/sonar-rest-api.service`:

```ini
[Unit]
Description=Sonar Rest API
After=network.target

[Service]
Type=simple
User=sonar
WorkingDirectory=/opt/sonar-rest-api
ExecStart=/usr/bin/java \
    -Dsonar.www=/opt/sonar-rest-api/www \
    -Dsonar.prompts=/opt/sonar-rest-api/prompts \
    -jar /opt/sonar-rest-api/SonarRestApi-1.0-SNAPSHOT.jar \
    --no-console --project=MY_PROJECT_KEY
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now sonar-rest-api
sudo journalctl -u sonar-rest-api -f
```

---

## 11. Проверка развёртывания

### 11.1. Health-check

```bash
curl http://localhost:6060/api/health
```

Ожидаемый ответ:

```json
{
  "status": "ok",
  "sessionReady": true,
  "snapshotStatus": "READY",
  "snapshotReady": true,
  "snapshotIssuesCount": 3172,
  "defaultProject": "MY_PROJECT_KEY",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

### 11.2. Список эндпоинтов (быстрая проверка)

```bash
curl http://localhost:6060/api/status
curl http://localhost:6060/api/measures?project=MY_PROJECT_KEY
curl http://localhost:6060/api/keys?project=MY_PROJECT_KEY&sev=ALL&qual=ALL | head -c 200
```

### 11.3. Web-интерфейс

Открыть `http://localhost:6060/` — должна открыться страница с шапкой «Sonar Rest API».

---

## 12. Консольные команды (краткая шпаргалка)

После запуска без `--no-console` доступна интерактивная консоль.

```
help                    # полная справка
status                  # состояние
project MY_KEY          # задать проект
refresh                 # async пересборка снимка
refresh wait            # sync пересборка снимка
issues ALL ALL          # список всех issues
snippet <KEY>           # один issue
auto ALL ALL --yes      # массовая AI-обработка через Ollama
stop                    # graceful-остановка auto
ollama show             # настройки Ollama
ollama test             # проверка соединения
session reload          # перечитать session.txt
quit                    # выйти (сервер продолжит работу)
```

---

## 13. Устранение неполадок

| Симптом | Причина | Решение |
|---|---|---|
| `sessionReady: false` | Не заполнен `session.txt` | Заполнить и `session reload` |
| `snapshotStatus: FAILED` | Истекла сессия или нет прав | Обновить `session.txt`, `refresh wait` |
| `HTTP 401` при запросах | Сессия протухла | Скопировать свежие cookie из DevTools |
| `HTTP 400` при вызове `/api/*` | Не передан `project` | Добавить `?project=KEY` в URL |
| Web-интерфейс отдаёт 404 | Нет `www/` рядом с JAR | `cp -r web www` в каталог JAR |
| Пустой список промптов | Нет `prompts/` | Создать каталог, либо `BootstrapFiles` создаст сам |
| `Ollama не настроена` в web | Не указан URL или модель | Кнопка **⚙ Ollama** |
| `Network/CORS` при обращении к Ollama | Ollama не принимает браузерные запросы | `OLLAMA_HOST=0.0.0.0 OLLAMA_ORIGINS='*' ollama serve` |
| Maven не скачивает зависимости | Нет доступа к Maven Central | Настроить `~/.m2/settings.xml` с mirror |

### Логи

- **stdout** — все действия сервера (`[ApiServer]`, `[Snapshot]`, `[ServerState]`, `[Bootstrap]`).
- **stderr** — прогресс auto-обработки и ошибки.
- При `--no-console` логи уходят в journald (systemd) или `docker logs` (Docker).

---

## 14. Обновление

```bash
git pull
mvn clean package
sudo systemctl restart sonar-rest-api
```

**Не перезаписывать:**

- `session.txt` (ваши cookie)
- `ollama.properties` (настройки LLM)
- `prompts/*.txt` (ваши промпты) — `BootstrapFiles` создаёт только отсутствующие, существующие **никогда** не перезаписывает

Статика (`www/`) обновляется через `cp -r web/* www/` — по желанию.

