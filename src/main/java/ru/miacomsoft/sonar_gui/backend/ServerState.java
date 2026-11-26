package ru.miacomsoft.sonar_gui.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Упрощённый ServerState для GUI-режима:
 *   - держит SessionManager + SonarClient;
 *   - отдаёт newApi(project) / newBulk();
 *   - умеет reload() / update(raw) / readRawFile();
 *   - НЕ содержит Snapshot-логики (GUI всегда запрашивает SonarQube напрямую).
 */
public class ServerState {

    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final long DEFAULT_DELAY_MS = 500;

    private final Path sessionFile;
    private volatile SessionManager session;
    private volatile SonarClient client;

    private final int batchSize;
    private final long delayMs;

    private final Map<String, String> ruleNameCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> snippetCache = new ConcurrentHashMap<>();

    public ServerState(Path sessionFile) {
        this(sessionFile, DEFAULT_BATCH_SIZE, DEFAULT_DELAY_MS);
    }

    public ServerState(Path sessionFile, int batchSize, long delayMs) {
        this.sessionFile = sessionFile;
        this.batchSize = batchSize;
        this.delayMs = delayMs;

        try {
            if (sessionFile != null && Files.exists(sessionFile)) {
                SessionManager sm = new SessionManager(sessionFile);
                this.session = sm;
                this.client = new SonarClient(sm);
                System.out.println("[ServerState] сессия загружена из " + sessionFile
                        + " (url=" + sm.getUrl()
                        + ", auth=" + sm.getAuthType()
                        + ", user=" + sm.getUser() + ")");
            } else {
                System.out.println("[ServerState] " + sessionFile
                        + " отсутствует — работа без сессии. "
                        + "Заполните session.properties через диалог Session.");
            }
        } catch (Exception e) {
            System.err.println("[ServerState] не удалось загрузить " + sessionFile
                    + ": " + e.getMessage());
        }
    }

    public Path    getSessionFile() { return sessionFile; }
    public int     getBatchSize()   { return batchSize; }
    public long    getDelayMs()     { return delayMs; }

    public boolean isSessionReady() { return client != null; }
    public SessionManager getSession() { return session; }

    public SonarClient getClient() {
        SonarClient c = client;
        if (c == null) {
            throw new SessionNotReadyException(
                    "Сессия SonarQube не инициализирована. "
                            + "Заполните session.properties (диалог Session).");
        }
        return c;
    }

    public SonarIssuesApi newApi(String projectKey) {
        return new SonarIssuesApi(getClient(), projectKey, ruleNameCache, snippetCache);
    }

    public SonarBulkApi newBulk() {
        return new SonarBulkApi(getClient(), batchSize, delayMs);
    }

    public synchronized String readRawFile() throws IOException {
        if (sessionFile == null || !Files.exists(sessionFile)) return "";
        return Files.readString(sessionFile, StandardCharsets.UTF_8);
    }

    public synchronized void update(String raw) throws IOException {
        SessionManager sm = new SessionManager(raw); // валидация
        Files.writeString(sessionFile, raw, StandardCharsets.UTF_8);
        this.session = sm;
        this.client = new SonarClient(sm);
    }

    public synchronized void reload() throws IOException {
        if (sessionFile == null || !Files.exists(sessionFile)) {
            this.session = null;
            this.client = null;
            return;
        }
        SessionManager sm = new SessionManager(sessionFile);
        this.session = sm;
        this.client = new SonarClient(sm);
    }
}