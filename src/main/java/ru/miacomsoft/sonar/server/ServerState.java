package ru.miacomsoft.sonar.server;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SessionParser;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarClient;
import ru.miacomsoft.sonar.SonarIssuesApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Централизованное изменяемое состояние сервера:
 *  - сессия (Session + SonarClient)
 *  - Snapshot (текущий снимок проекта)
 *  - статус снимка (EMPTY/BUILDING/READY/FAILED) + прогресс
 *  - общие кэши (ruleName, snippet) — переживают пересоздание SonarIssuesApi
 *  - project по умолчанию (env / --project)
 *
 * Все переходы состояния защищены buildLock; снимок — volatile.
 */
public class ServerState {

    public enum SnapshotStatus { EMPTY, BUILDING, READY, FAILED }

    private final Path sessionFile;
    private final String defaultProject;
    private final int batchSize;
    private final long delayMs;
    private final boolean warmupSnippets;
    private final int warmupSnippetLimit;

    private volatile SessionParser.Session session;
    private volatile SonarClient client;

    private volatile Snapshot snapshot;
    private volatile SnapshotStatus snapshotStatus = SnapshotStatus.EMPTY;
    private volatile String snapshotError;
    private volatile Instant snapshotStartedAt;
    private volatile String snapshotPhase;
    private volatile int snapshotProgress;
    private volatile int snapshotProgressTotal;

    private final Object buildLock = new Object();

    private final Map<String, String> ruleNameCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> snippetCache = new ConcurrentHashMap<>();

    public ServerState(Path sessionFile, String defaultProject, int batchSize, long delayMs,
                       boolean warmupSnippets, int warmupSnippetLimit) {
        this.sessionFile = sessionFile;
        this.defaultProject = defaultProject != null ? defaultProject : "";
        this.batchSize = batchSize;
        this.delayMs = delayMs;
        this.warmupSnippets = warmupSnippets;
        this.warmupSnippetLimit = warmupSnippetLimit;

        try {
            if (Files.exists(sessionFile)) {
                SessionParser.Session s = SessionParser.parseFile(sessionFile.toString());
                this.session = s;
                this.client = new SonarClient(s);
                System.out.println("[ServerState] сессия загружена из " + sessionFile
                        + " (host=" + s.host + ")");
            } else {
                System.out.println("[ServerState] " + sessionFile
                        + " отсутствует — сервер стартует без сессии. "
                        + "Загрузите её через PUT /api/session");
            }
        } catch (Exception e) {
            System.err.println("[ServerState] не удалось загрузить " + sessionFile
                    + ": " + e.getMessage() + ". Сервер стартует без сессии.");
        }
    }

    // ================= getters =================

    public Path getSessionFile()             { return sessionFile; }
    public String getDefaultProject()        { return defaultProject; }
    public int getBatchSize()                { return batchSize; }
    public long getDelayMs()                 { return delayMs; }
    public boolean isWarmupSnippets()        { return warmupSnippets; }
    public int getWarmupSnippetLimit()       { return warmupSnippetLimit; }

    public boolean isSessionReady()          { return client != null; }
    public SessionParser.Session getSession(){ return session; }

    public Snapshot getSnapshot()            { return snapshot; }
    public SnapshotStatus getSnapshotStatus(){ return snapshotStatus; }
    public String getSnapshotError()         { return snapshotError; }
    public Instant getSnapshotStartedAt()    { return snapshotStartedAt; }
    public String getSnapshotPhase()         { return snapshotPhase; }
    public int getSnapshotProgress()         { return snapshotProgress; }
    public int getSnapshotProgressTotal()    { return snapshotProgressTotal; }

    public boolean isSnapshotBuilding()      { return snapshotStatus == SnapshotStatus.BUILDING; }
    public boolean isSnapshotReady()         { return snapshot != null; }

    public SonarClient getClient() {
        SonarClient c = client;
        if (c == null) {
            throw new SessionNotReadyException(
                    "Сессия не инициализирована. Загрузите её через PUT /api/session " +
                            "(сырой текст дампа в теле запроса).");
        }
        return c;
    }

    public SonarIssuesApi newApi(String projectKey) {
        return new SonarIssuesApi(getClient(), projectKey, ruleNameCache, snippetCache);
    }

    public SonarBulkApi newBulk() {
        return new SonarBulkApi(getClient(), batchSize, delayMs);
    }

    // ================= session =================

    public synchronized String readRawFile() throws IOException {
        if (!Files.exists(sessionFile)) return "";
        return Files.readString(sessionFile, StandardCharsets.UTF_8);
    }

    public synchronized void update(String raw) throws IOException {
        SessionParser.Session parsed = SessionParser.parse(raw); // валидация
        Files.writeString(sessionFile, raw, StandardCharsets.UTF_8);
        this.session = parsed;
        this.client = new SonarClient(parsed);
    }

    public synchronized void reload() throws IOException {
        if (!Files.exists(sessionFile)) {
            this.session = null;
            this.client = null;
            return;
        }
        SessionParser.Session parsed;
        try {
            parsed = SessionParser.parseFile(sessionFile.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Ошибка парсинга сессии в " + sessionFile + ": " + e.getMessage(), e);
        }
        this.session = parsed;
        this.client = new SonarClient(parsed);
    }

    // ================= snapshot =================

    private void onProgress(String phase, int cur, int tot) {
        this.snapshotPhase = phase;
        this.snapshotProgress = cur;
        this.snapshotProgressTotal = tot;
    }

    /** Синхронный пересбор снимка. Бросает исключение, если не удалось. */
    public Snapshot buildSnapshotSync(String projectKey) throws Exception {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey не задан");
        }
        synchronized (buildLock) {
            snapshotStatus = SnapshotStatus.BUILDING;
            snapshotError = null;
            snapshotStartedAt = Instant.now();
            snapshotPhase = "starting";
            snapshotProgress = 0;
            snapshotProgressTotal = 0;

            // полный refresh — сбрасываем кэш сниппетов (могли устареть).
            // Кэш правил оставляем: правила почти не меняются.
            snippetCache.clear();

            try {
                SonarIssuesApi api = newApi(projectKey);
                Snapshot s = Snapshot.build(
                        api, projectKey,
                        session != null ? session.host : "",
                        warmupSnippets, warmupSnippetLimit,
                        this::onProgress);
                this.snapshot = s;
                snapshotStatus = SnapshotStatus.READY;
                snapshotPhase = "done";
                return s;
            } catch (Exception e) {
                snapshotError = e.getClass().getSimpleName() + ": " + e.getMessage();
                snapshotStatus = SnapshotStatus.FAILED;
                snapshotPhase = "failed";
                throw e;
            }
        }
    }

    /** Асинхронный пересбор. Возвращает false, если уже строится. */
    public boolean buildSnapshotAsync(String projectKey) {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey не задан");
        }
        if (isSnapshotBuilding()) {
            return false;
        }
        Thread t = new Thread(() -> {
            try {
                buildSnapshotSync(projectKey);
            } catch (Exception e) {
                System.err.println("[Snapshot] async build failed: " + e.getMessage());
            }
        }, "snapshot-builder");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * Точечное обновление одного issue.
     * Возвращает:
     *  - Issue — если найден и всё ещё OPEN/CONFIRMED, обновлён в снимке;
     *  - null — если issue больше не OPEN/CONFIRMED или не найден (удалён из снимка).
     * Метаданные о предыдущем/новом статусе — во втором аргументе через RefreshResult.
     */
    public RefreshResult refreshIssue(String projectKey, String issueKey) throws Exception {
        if (issueKey == null || issueKey.isEmpty()) {
            throw new IllegalArgumentException("issueKey не задан");
        }
        SonarIssuesApi api = newApi(projectKey);
        api.invalidateIssue(issueKey);

        Issue fresh = api.fetchIssue(issueKey);
        Snapshot current = snapshot;

        // Issue не найден в Sonar
        if (fresh == null) {
            if (current != null && current.projectKey.equals(projectKey)) {
                this.snapshot = current.withoutIssue(issueKey);
            }
            return RefreshResult.removed(issueKey, "not_found", null);
        }

        String st = fresh.status == null ? "" : fresh.status;
        boolean stillOpen = "OPEN".equals(st) || "CONFIRMED".equals(st)
                || "REOPENED".equals(st);

        // Issue больше не OPEN → удаляем из снимка
        if (!stillOpen) {
            if (current != null && current.projectKey.equals(projectKey)) {
                this.snapshot = current.withoutIssue(issueKey);
            }
            return RefreshResult.removed(issueKey, "status_changed", fresh);
        }

        // Обновляем: ruleName + snippet
        try { fresh.ruleName = api.fetchRuleName(fresh.rule); }
        catch (Exception ignored) { fresh.ruleName = ""; }
        List<String> snippet;
        try { snippet = api.fetchSnippet(issueKey); }
        catch (Exception e) { snippet = null; }

        if (current != null && current.projectKey.equals(projectKey)) {
            this.snapshot = current.withIssueAndSnippet(fresh, snippet);
        }
        return RefreshResult.updated(issueKey, fresh);
    }

    /** Результат точечного обновления. */
    public static final class RefreshResult {
        public final String key;
        public final boolean removed;
        public final String reason;   // для removed: not_found | status_changed | null
        public final Issue issue;     // для removed: последний известный; для updated: актуальный

        private RefreshResult(String key, boolean removed, String reason, Issue issue) {
            this.key = key;
            this.removed = removed;
            this.reason = reason;
            this.issue = issue;
        }

        static RefreshResult removed(String key, String reason, Issue lastKnown) {
            return new RefreshResult(key, true, reason, lastKnown);
        }

        static RefreshResult updated(String key, Issue issue) {
            return new RefreshResult(key, false, null, issue);
        }
    }
}