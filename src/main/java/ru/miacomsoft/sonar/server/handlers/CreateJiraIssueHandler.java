package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.jira.*;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.exporters.HtmlPerFileExporter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CreateJiraIssueHandler extends BaseHandler {

    public CreateJiraIssueHandler(ServerState state) { super(state); }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        if (ex.getRequestURI().getPath().endsWith("/config")) {
            handleGetConfig(ex);
        } else {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) sendError(ex, 405, "Use POST");
            else handlePost(ex);
        }
    }

    private void handleGetConfig(HttpExchange ex) throws Exception {
        JiraConfig cfg = JiraConfig.getInstance();
        SessionLoader.SessionInfo session = null;
        String sessionError = null;
        try {
            session = SessionLoader.load(Path.of(cfg.sessionFile()));
        } catch (Exception e) {
            sessionError = e.getMessage();
        }

        String resolvedAssignee = cfg.assignee();
        if ("me".equalsIgnoreCase(resolvedAssignee) && session != null) {
            resolvedAssignee = session.username() != null ? session.username() : "me";
        }

        JSONObject resp = new JSONObject()
                .put("url", cfg.baseUrl())
                .put("sessionReady", session != null)
                .put("username", session != null ? session.username() : null)
                .put("project", cfg.defaultProject())
                .put("issueType", cfg.issueType())
                .put("priority", cfg.priority())
                .put("security", cfg.security())
                .put("team", cfg.team())
                .put("labels", cfg.labels())
                .put("components", cfg.components())
                .put("assignee", resolvedAssignee);

        if (sessionError != null) resp.put("sessionError", sessionError);
        sendJson(ex, 200, resp);
    }

    private void handlePost(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        JiraConfig cfg = JiraConfig.getInstance();
        String sev = param(q, "sev", "ALL");
        String qual = param(q, "qual", "ALL");
        String customSum = param(q, "summary", null);
        String customDesc = param(q, "description", null);
        String statusFilter = param(q, "statusFilter", "ALL");

        String jiraProject = cfg.defaultProject();
        String jiraType = cfg.issueType();

        if (jiraProject == null || jiraProject.isBlank()) {
            sendError(ex, 400, "jira.defaultProject не задан"); return;
        }

        SessionLoader.SessionInfo session;
        try {
            session = SessionLoader.load(Path.of(cfg.sessionFile()));
        } catch (Exception e) {
            sendError(ex, 500, cfg.sessionFile() + ": " + e.getMessage()); return;
        }

        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  СОЗДАНИЕ ЗАДАЧИ В JIRA                              ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println("[Jira] URL       = " + cfg.baseUrl());
        System.out.println("[Jira] Проект    = " + jiraProject);
        System.out.println("[Jira] Тип       = " + jiraType);
        System.out.println("[Jira] Sonar     = " + project + " (sev=" + sev + ", qual=" + qual + ", statusFilter=" + statusFilter + ")");

        JiraClient jira = new JiraClient(cfg.baseUrl(), session.cookieHeader());
        JiraFieldService fieldService = new JiraFieldService(jira);
        JiraTempoTeamService tempo = new JiraTempoTeamService(jira);
        JiraIssueService issueService = new JiraIssueService(jira, cfg.baseUrl(), fieldService, tempo);
        JiraUserService userService = new JiraUserService(jira);
        JiraIssueCommentService comment = new JiraIssueCommentService(jira);
        JiraIssueAttachmentService attach = new JiraIssueAttachmentService(jira, cfg.baseUrl(), comment);

        String author = "me".equalsIgnoreCase(cfg.assignee()) ? session.username() : cfg.assignee();
        if (author == null || author.isBlank()) {
            try { author = userService.getMyself().name(); } catch (Exception ignored) {}
        }

        // ---- Загрузка issues ----
        System.out.println("\n[1/6] Загрузка issues из SonarQube...");
        SonarIssuesApi api = newApi(project);
        List<Issue> issues = new ArrayList<>();
        String keysCsv = param(q, "keys", null);
        byte[] bodyBytes = ex.getRequestBody().readAllBytes();

        if (bodyBytes.length > 0 && (keysCsv == null || keysCsv.isEmpty())) {
            try {
                JSONObject req = new JSONObject(new String(bodyBytes, StandardCharsets.UTF_8));
                if (req.has("keys")) {
                    JSONArray arr = req.getJSONArray("keys");
                    for (int i = 0; i < arr.length(); i++) {
                        Issue it = api.fetchIssue(arr.getString(i));
                        if (it != null) issues.add(it);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (issues.isEmpty() && keysCsv != null && !keysCsv.isEmpty()) {
            for (String raw : keysCsv.split(",")) {
                Issue it = api.fetchIssue(raw.trim());
                if (it != null) issues.add(it);
            }
        }
        if (issues.isEmpty()) {
            if ("UNPROCESSED".equals(statusFilter)) issues = api.fetchAllByStatuses(SonarIssuesApi.ACTIVE_STATUSES, sev, qual);
            else if ("PROCESSED".equals(statusFilter)) {
                for (Issue it : api.fetchAllByStatuses(null, sev, qual)) {
                    if (!"OPEN".equals(it.status) && !"REOPENED".equals(it.status)) issues.add(it);
                }
            } else issues = api.fetchAllByStatuses(null, sev, qual);
        }

        if (issues.isEmpty()) {
            System.out.println("  ✗ Нет issues для отчёта");
            sendError(ex, 400, "Нет issues для отчёта"); return;
        }
        System.out.println("  ✓ Найдено issues: " + issues.size());

        // ---- Формирование ZIP ----
        System.out.println("\n[2/6] Формирование ZIP-архива...");
        Map<String, List<Issue>> issuesByFile = new TreeMap<>();
        for (Issue it : issues) issuesByFile.computeIfAbsent(it.component != null ? it.component : "(unknown)", k -> new ArrayList<>()).add(it);

        Map<String, List<String>> snippets = Collections.synchronizedMap(new HashMap<>());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, Runtime.getRuntime().availableProcessors()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (List<Issue> lst : issuesByFile.values())
                for (Issue it : lst)
                    futures.add(pool.submit(() -> {
                        try {
                            List<String> snip = fetchSnippetCached(project, it.key);
                            if (snip != null && !snip.isEmpty()) snippets.put(it.key, snip);
                        } catch (Exception ignored) {}
                    }));
            for (Future<?> f : futures) try { f.get(); } catch (Exception ignored) {}
        } finally { pool.shutdown(); }

        Path zip = Files.createTempFile("sonar_jira_", ".zip");
        try {
            HtmlPerFileExporter.writeZip(zip, issuesByFile, snippets, project, (state.getSession() != null ? state.getSession().getHost() : "sonar.bars.group"), false, null);
            System.out.println("  ✓ ZIP: " + Files.size(zip) + " байт, " + issuesByFile.size() + " файлов");
        } catch (Exception e) {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
            System.out.println("  ✗ Ошибка сборки ZIP: " + e.getMessage());
            sendError(ex, 500, "Ошибка сборки ZIP: " + e.getMessage()); return;
        }

        String summary = (customSum != null && !customSum.isBlank()) ? customSum : project + ": Необходимо исправить ошибки, найденные SonarQube";
        String description = (customDesc != null && !customDesc.isBlank()) ? customDesc : "Необходимо проанализировать ошибки...\n\n*Всего issues:* " + issues.size();

        // ---- Поиск обязательных полей ----
        System.out.println("\n[3/6] Определение типа задачи и обязательных полей...");
        String issueTypeId = jiraType;
        if (!jiraType.matches("\\d+")) {
            issueTypeId = fieldService.findIssueTypeId(jiraProject, jiraType);
        }
        System.out.println("  ✓ IssueTypeId = " + issueTypeId);

        // ---- Создание задачи ----
        System.out.println("\n[4/6] Создание задачи в Jira...");
        Map<String, Object> extras = buildExtraFields(cfg, tempo, fieldService, jiraProject, issueTypeId);
        JiraIssueService.CreatedIssue created;
        try {
            created = issueService.createIssueWithAutoFill(jiraProject, issueTypeId, summary, description, author, extras);
        } catch (Exception e) {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
            System.out.println("  ✗ Не удалось создать задачу: " + e.getMessage());
            sendError(ex, 500, "Не удалось создать задачу: " + e.getMessage()); return;
        }

        // ============================================================
        // ГЛАВНОЕ: ВЫВОД НОМЕРА ЗАДАЧИ В КОНСОЛЬ СЕРВЕРА
        // ============================================================
        String browseUrl = cfg.baseUrl() + "/browse/" + created.key();
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  ✓ ЗАДАЧА СОЗДАНА                                    ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf("║  НОМЕР: %-45s║%n", created.key());
        System.out.printf("║  ID:    %-45s║%n", created.id());
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.println("║  ССЫЛКА:");
        System.out.println("║  " + browseUrl);
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // ---- Вложение ----
        System.out.println("[5/6] Загрузка ZIP-архива в задачу...");
        List<JiraIssueAttachmentService.AttachmentInfo> attachments = Collections.emptyList();
        try {
            attachments = attach.uploadFile(created.key(), zip);
            System.out.println("  ✓ Вложений: " + attachments.size());
            for (JiraIssueAttachmentService.AttachmentInfo a : attachments) {
                System.out.println("    → " + a.filename() + " (" + a.url() + ")");
            }
        } catch (Exception e) {
            System.err.println("  ✗ Ошибка загрузки вложения: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
        }

        // ---- Комментарий ----
        System.out.println("\n[6/6] Добавление комментария...");
        if (!attachments.isEmpty()) {
            String text = "Отчёт SonarQube сформирован.\nФайлов в отчёте: " + issuesByFile.size() + "\nIssues: " + issues.size();
            try {
                attach.addCommentWithLinks(created.key(), text, attachments);
                System.out.println("  ✓ Комментарий добавлен");
            } catch (Exception e) {
                System.err.println("  ✗ Ошибка комментария: " + e.getMessage());
            }
        }

        System.out.println("\n═══════════════════════════════════════════════════════");
        System.out.println(" ГОТОВО. Задача: " + created.key() + " — " + browseUrl);
        System.out.println("═══════════════════════════════════════════════════════\n");

        JSONObject resp = new JSONObject()
                .put("success", true)
                .put("issueKey", created.key())
                .put("issueId", created.id())
                .put("url", browseUrl)
                .put("summary", summary)
                .put("assignee", author != null ? author : "")
                .put("issuesCount", issues.size())
                .put("filesCount", issuesByFile.size())
                .put("attachmentsCount", attachments.size());

        JSONArray att = new JSONArray();
        for (JiraIssueAttachmentService.AttachmentInfo a : attachments) {
            att.put(new JSONObject().put("id", a.id()).put("filename", a.filename()).put("url", a.url()));
        }
        resp.put("attachments", att);

        sendJson(ex, 200, resp);
    }

    private Map<String, Object> buildExtraFields(JiraConfig cfg, JiraTempoTeamService tempo, JiraFieldService fields, String jiraProject, String issueTypeId) {
        Map<String, Object> extras = new LinkedHashMap<>();
        if (cfg.priority() != null && !cfg.priority().isBlank()) {
            String id = fields.findPriorityIdByName(jiraProject, issueTypeId, cfg.priority());
            if (id != null) extras.put("priority", new JSONObject().put("id", id));
        }
        if (cfg.security() != null && !cfg.security().isBlank()) {
            String id = fields.findSecurityLevelIdByName(jiraProject, issueTypeId, cfg.security());
            if (id != null) extras.put("security", new JSONObject().put("id", id));
        }
        if (cfg.team() != null && !cfg.team().isBlank()) {
            Integer teamId = tempo.findIdByName(cfg.team());
            if (teamId != null) extras.put("customfield_10202", teamId);
        }
        if (cfg.labels() != null && !cfg.labels().isBlank()) {
            JSONArray arr = new JSONArray();
            for (String l : cfg.labels().split(",")) if (!l.trim().isEmpty()) arr.put(l.trim());
            if (arr.length() > 0) extras.put("labels", arr);
        }
        if (cfg.components() != null && !cfg.components().isBlank()) {
            JSONArray arr = new JSONArray();
            for (String c : cfg.components().split(",")) {
                if (c.trim().isEmpty()) continue;
                JSONObject o = new JSONObject();
                if (c.trim().matches("\\d+")) o.put("id", c.trim());
                else o.put("name", c.trim());
                arr.put(o);
            }
            if (arr.length() > 0) extras.put("components", arr);
        }
        return extras;
    }
}