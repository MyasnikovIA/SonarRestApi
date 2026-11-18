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
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/config")) handleGetConfig(ex);
        else if (path.endsWith("/epics")) handleGetEpics(ex);
        else if (path.endsWith("/teams")) handleGetTeams(ex);
        else {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) sendError(ex, 405, "Use POST");
            else handlePost(ex);
        }
    }

    /**
     * ИСПРАВЛЕНО: display name текущего пользователя берётся из /rest/api/2/myself
     * (раньше показывался логин — не совпадало с отображением остальных пользователей).
     * Каждый список загружается в отдельном try-catch,
     * чтобы при ошибке одного из них остальные всё равно заполнились.
     */
    private void handleGetConfig(HttpExchange ex) throws Exception {
        JiraConfig cfg = JiraConfig.getInstance();
        SessionLoader.SessionInfo session = null;
        String sessionError = null;
        try {
            session = SessionLoader.load(Path.of(cfg.sessionFile()));
        } catch (Exception e) {
            sessionError = e.getMessage();
        }

        String defaultAssignee = session != null && session.username() != null ? session.username() : cfg.assignee();
        String userDisplayName = defaultAssignee;

        JSONObject resp = new JSONObject()
                .put("url", cfg.baseUrl())
                .put("sessionReady", session != null)
                .put("username", session != null ? session.username() : null)
                .put("userDisplayName", userDisplayName)
                .put("configProject", cfg.defaultProject())
                .put("configIssueType", cfg.issueType())
                .put("configEpic", cfg.epic())
                .put("configPriority", cfg.priority())
                .put("configSecurity", cfg.security())
                .put("configTeam", cfg.team())
                .put("configLabels", cfg.labels())
                .put("configComponents", cfg.components())
                .put("configAssignee", defaultAssignee);

        if (sessionError != null) resp.put("sessionError", sessionError);

        if (session == null) {
            sendJson(ex, 200, resp);
            return;
        }

        JiraClient jira = new JiraClient(cfg.baseUrl(), session.cookieHeader());
        JiraFieldService fieldService = new JiraFieldService(jira);
        JiraUserService userService = new JiraUserService(jira);
        JiraLabelService labelService = new JiraLabelService(jira);

        // NEW: реальное display name текущего пользователя (из /myself)
        try {
            JiraUserService.JiraUser me = userService.getMyself();
            if (me != null && me.displayName() != null && !me.displayName().isBlank()) {
                resp.put("userDisplayName", me.displayName());
            }
        } catch (Exception ignored) {
            // 401/недоступно — останется login из session.username() (приемлемо)
        }

        // Диагностика ошибок
        List<String> errors = new ArrayList<>();

        // --- Проекты ---
        try {
            JSONArray projectsArr = new JSONArray();
            for (JiraFieldService.AllowedValue av : fieldService.getProjects()) {
                projectsArr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            resp.put("projects", projectsArr);
            if (projectsArr.isEmpty()) errors.add("projects пуст");
        } catch (Exception e) {
            errors.add("projects: " + e.getMessage());
            resp.put("projects", new JSONArray());
        }

        // --- Типы задач ---
        try {
            JSONArray typesArr = new JSONArray();
            for (JiraFieldService.AllowedValue av : fieldService.getIssueTypes(cfg.defaultProject())) {
                typesArr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            resp.put("issueTypes", typesArr);
            if (typesArr.isEmpty()) errors.add("issueTypes пуст");
        } catch (Exception e) {
            errors.add("issueTypes: " + e.getMessage());
            resp.put("issueTypes", new JSONArray());
        }

        // --- Приоритеты ---
        try {
            JSONArray priorsArr = new JSONArray();
            for (JiraFieldService.AllowedValue av : fieldService.getAllPriorities()) {
                priorsArr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            resp.put("priorities", priorsArr);
            if (priorsArr.isEmpty()) errors.add("priorities пуст");
        } catch (Exception e) {
            errors.add("priorities: " + e.getMessage());
            resp.put("priorities", new JSONArray());
        }

        // --- Уровни безопасности ---
        try {
            JSONArray secArr = new JSONArray();
            String defaultSecurityId = "";
            for (JiraFieldService.AllowedValue av : fieldService.getSecurityLevels(cfg.defaultProject(), cfg.issueType())) {
                secArr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
                if (av.value().equalsIgnoreCase(cfg.security()) || av.value().equalsIgnoreCase("Основной")) {
                    defaultSecurityId = av.id();
                }
            }
            resp.put("securityLevels", secArr);
            resp.put("configSecurity", defaultSecurityId);
        } catch (Exception e) {
            errors.add("securityLevels: " + e.getMessage());
            resp.put("securityLevels", new JSONArray());
        }

        // --- Метки ---
        try {
            JSONArray labelsArr = new JSONArray();
            for (String label : labelService.getSuggestions(cfg.defaultProject(), "")) {
                labelsArr.put(label);
            }
            resp.put("availableLabels", labelsArr);
        } catch (Exception e) {
            errors.add("availableLabels: " + e.getMessage());
            resp.put("availableLabels", new JSONArray());
        }

        // --- Пользователи ---
        try {
            JSONArray usersArr = new JSONArray();
            List<JiraUserService.JiraUser> users = userService.getAssignableUsers(cfg.defaultProject(), "", 100);
            for (JiraUserService.JiraUser u : users) {
                usersArr.put(new JSONObject().put("name", u.name()).put("displayName", u.displayName()));
            }
            resp.put("assignees", usersArr);
            if (usersArr.isEmpty()) errors.add("assignees пуст");
        } catch (Exception e) {
            errors.add("assignees: " + e.getMessage());
            resp.put("assignees", new JSONArray());
        }

        if (!errors.isEmpty()) {
            resp.put("listError", "Ошибки загрузки: " + String.join("; ", errors)
                    + ". Проверьте cookie в session.txt.");
        }

        sendJson(ex, 200, resp);
    }

    private void handleGetEpics(HttpExchange ex) throws Exception {
        JiraConfig cfg = JiraConfig.getInstance();
        Map<String, String> q = parseQuery(ex);
        String project = param(q, "project", cfg.defaultProject());

        try {
            SessionLoader.SessionInfo session = SessionLoader.load(Path.of(cfg.sessionFile()));
            JiraClient jira = new JiraClient(cfg.baseUrl(), session.cookieHeader());
            JiraEpicService epicService = new JiraEpicService(jira);

            JSONArray arr = new JSONArray();
            for (JiraFieldService.AllowedValue av : epicService.getEpics(project)) {
                arr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            JSONObject resp = new JSONObject().put("epics", arr);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    private void handleGetTeams(HttpExchange ex) throws Exception {
        JiraConfig cfg = JiraConfig.getInstance();
        try {
            SessionLoader.SessionInfo session = SessionLoader.load(Path.of(cfg.sessionFile()));
            JiraClient jira = new JiraClient(cfg.baseUrl(), session.cookieHeader());
            JiraTeamService teamService = new JiraTeamService(jira);

            JSONArray arr = new JSONArray();
            for (JiraFieldService.AllowedValue av : teamService.getTeams()) {
                arr.put(new JSONObject().put("id", av.id()).put("name", av.value()));
            }
            JSONObject resp = new JSONObject().put("teams", arr);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
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

        String tagsParam = param(q, "tags", "");
        String excludedTagsParam = param(q, "excludedTags", "");
        String selectedRulesParam = param(q, "selectedRules", "");
        String filterText = param(q, "filterText", "");

        String overrideProject = param(q, "overrideProject", cfg.defaultProject());
        String overrideIssueType = param(q, "overrideIssueType", cfg.issueType());
        String overrideEpic = param(q, "overrideEpic", cfg.epic());
        String overridePriority = param(q, "overridePriority", cfg.priority());
        String overrideSecurity = param(q, "overrideSecurity", cfg.security());
        String overrideTeam = param(q, "overrideTeam", cfg.team());
        String overrideLabels = param(q, "overrideLabels", cfg.labels());
        String overrideComponents = param(q, "overrideComponents", cfg.components());
        String overrideAssignee = param(q, "overrideAssignee", cfg.assignee());

        // NEW: диагностика — видно, что реально приходит с клиента
        System.out.println("[CreateJiraIssue] POST: project=" + project
                + " sev=" + sev + " qual=" + qual
                + " statusFilter=" + statusFilter
                + " tags=[" + tagsParam + "]"
                + " excludedTags=[" + excludedTagsParam + "]"
                + " selectedRules=[" + selectedRulesParam + "]"
                + " filterText=[" + filterText + "]");

        if (overrideProject == null || overrideProject.isBlank()) {
            sendError(ex, 400, "Проект не выбран"); return;
        }

        SessionLoader.SessionInfo session;
        try {
            session = SessionLoader.load(Path.of(cfg.sessionFile()));
        } catch (Exception e) {
            sendError(ex, 500, cfg.sessionFile() + ": " + e.getMessage()); return;
        }


        JiraClient jira = new JiraClient(cfg.baseUrl(), session.cookieHeader());
        JiraFieldService fieldService = new JiraFieldService(jira);
        JiraTempoTeamService tempo = new JiraTempoTeamService(jira);
        JiraIssueService issueService = new JiraIssueService(jira, cfg.baseUrl(), fieldService, tempo);
        JiraUserService userService = new JiraUserService(jira);
        JiraIssueCommentService comment = new JiraIssueCommentService(jira);
        JiraIssueAttachmentService attach = new JiraIssueAttachmentService(jira, cfg.baseUrl(), comment);

        String author = "me".equalsIgnoreCase(overrideAssignee) ? session.username() : overrideAssignee;
        if (author == null || author.isBlank()) {
            try { author = userService.getMyself().name(); } catch (Exception ignored) {}
        }

        SonarIssuesApi api = newApi(project);
        List<Issue> issues = new ArrayList<>();
        String keysCsv = param(q, "keys", null);
        byte[] bodyBytes = ex.getRequestBody().readAllBytes();

        boolean hasExplicitKeys = false;

        // NEW: keys в теле POST обрабатываются батчами по 100 (было по одному fetch)
        if (bodyBytes.length > 0 && (keysCsv == null || keysCsv.isEmpty())) {
            System.out.println("[CreateJiraIssue] Body bytes: " + bodyBytes.length);
            try {
                JSONObject req = new JSONObject(new String(bodyBytes, StandardCharsets.UTF_8));
                if (req.has("keys")) {
                    hasExplicitKeys = true;
                    JSONArray arr = req.getJSONArray("keys");
                    List<String> keysList = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String k = arr.optString(i, "");
                        if (k != null && !k.isEmpty()) keysList.add(k);
                    }

                    final int BATCH = 100;
                    for (int i = 0; i < keysList.size(); i += BATCH) {
                        int end = Math.min(i + BATCH, keysList.size());
                        List<String> batch = keysList.subList(i, end);
                        try {
                            JSONObject raw = api.fetchIssuesByKeys(String.join(",", batch));
                            JSONArray issuesArr = raw.optJSONArray("issues");
                            if (issuesArr != null) {
                                for (int j = 0; j < issuesArr.length(); j++) {
                                    issues.add(Issue.from(issuesArr.getJSONObject(j), project));
                                }
                            }
                        } catch (Exception e) {
                            // fallback на индивидуальный fetch
                            for (String k : batch) {
                                try {
                                    Issue it = api.fetchIssue(k);
                                    if (it != null) issues.add(it);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    System.out.println("[CreateJiraIssue] Explicit keys loaded: "
                            + issues.size() + " из " + keysList.size() + " запрошенных");
                }
            } catch (Exception ignored) {}
        }
        if (issues.isEmpty() && keysCsv != null && !keysCsv.isEmpty()) {
            hasExplicitKeys = true;
            for (String raw : keysCsv.split(",")) {
                Issue it = api.fetchIssue(raw.trim());
                if (it != null) issues.add(it);
            }
        }

        if (!hasExplicitKeys && issues.isEmpty()) {
            if ("UNPROCESSED".equals(statusFilter)) issues = api.fetchAllByStatuses(SonarIssuesApi.ACTIVE_STATUSES, sev, qual);
            else if ("PROCESSED".equals(statusFilter)) {
                for (Issue it : api.fetchAllByStatuses(null, sev, qual)) {
                    if (!"OPEN".equals(it.status) && !"REOPENED".equals(it.status)) issues.add(it);
                }
            } else issues = api.fetchAllByStatuses(null, sev, qual);

            Set<String> selectedTags = new HashSet<>();
            if (!tagsParam.isEmpty()) for (String t : tagsParam.split(",")) if (!t.isEmpty()) selectedTags.add(t.trim());

            Set<String> excludedTags = new HashSet<>();
            if (!excludedTagsParam.isEmpty()) for (String t : excludedTagsParam.split(",")) if (!t.isEmpty()) excludedTags.add(t.trim());

            Set<String> selectedRules = new HashSet<>();
            if (!selectedRulesParam.isEmpty()) for (String r : selectedRulesParam.split(",")) if (!r.isEmpty()) selectedRules.add(r.trim());

            String qText = filterText == null ? "" : filterText.toLowerCase();

            List<Issue> filtered = new ArrayList<>();
            for (Issue it : issues) {
                if (shouldSkip(it, selectedTags, excludedTags, selectedRules, qText, statusFilter)) continue;
                filtered.add(it);
            }
            issues = filtered;
        }

        // NEW: диагностика после фильтрации
        System.out.println("[CreateJiraIssue] After filter: " + issues.size() + " issues");

        if (issues.isEmpty()) { sendError(ex, 400, "Нет issues для отчёта (проверьте фильтры)"); return; }

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
        } catch (Exception e) {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
            sendError(ex, 500, "Ошибка сборки ZIP: " + e.getMessage()); return;
        }

        String summary = (customSum != null && !customSum.isBlank()) ? customSum : project + ": Необходимо исправить ошибки, найденные SonarQube";
        String description = (customDesc != null && !customDesc.isBlank()) ? customDesc : "Необходимо проанализировать ошибки...\n\n*Всего issues:* " + issues.size();

        String issueTypeId = fieldService.findIssueTypeId(overrideProject, overrideIssueType);
        Map<String, Object> extras = buildExtraFields(fieldService, tempo, overrideProject, issueTypeId,
                overrideEpic, overridePriority, overrideSecurity, overrideTeam, overrideLabels, overrideComponents);

        JiraIssueService.CreatedIssue created;
        try {
            created = issueService.createIssueWithAutoFill(overrideProject, issueTypeId, summary, description, author, extras);
        } catch (Exception e) {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
            sendError(ex, 500, "Не удалось создать задачу: " + e.getMessage()); return;
        }

        String browseUrl = cfg.baseUrl() + "/browse/" + created.key();

        List<JiraIssueAttachmentService.AttachmentInfo> attachments = Collections.emptyList();
        try {
            attachments = attach.uploadFile(created.key(), zip);
        } catch (Exception e) {
            System.err.println("Ошибка загрузки вложения: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
        }

        if (!attachments.isEmpty()) {
            String text = "Отчёт SonarQube сформирован.\nФайлов в отчёте: " + issuesByFile.size() + "\nIssues: " + issues.size();
            try { attach.addCommentWithLinks(created.key(), text, attachments); } catch (Exception ignored) {}
        }
        System.out.println("[CreateJiraIssue] After filter: " + issues.size() + " issues");
        if (issues.size() >= 10000) {
            System.out.println("[CreateJiraIssue] WARN: Large batch (" + issues.size()
                    + ") — near SonarQube 10000-result limit");
        }
        JSONObject resp = new JSONObject()
                .put("success", true)
                .put("issueKey", created.key())
                .put("url", browseUrl)
                .put("issuesCount", issues.size())
                .put("attachmentsCount", attachments.size());

        sendJson(ex, 200, resp);
    }

    private boolean shouldSkip(Issue it, Set<String> selectedTags, Set<String> excludedTags,
                               Set<String> selectedRules, String qText, String statusFilter) {
        if (statusFilter != null && !statusFilter.isEmpty() && !"ALL".equals(statusFilter)) {
            boolean isProcessed = !"OPEN".equals(it.status) && !"REOPENED".equals(it.status);
            if ("PROCESSED".equals(statusFilter) && !isProcessed) return true;
            if ("UNPROCESSED".equals(statusFilter) && isProcessed) return true;
        }
        if (!selectedTags.isEmpty()) {
            List<String> itTags = it.tags != null ? it.tags : Collections.emptyList();
            boolean hasMatch = false;
            for (String t : selectedTags) {
                if (t.equals("__NO_TAGS__")) { if (itTags.isEmpty()) hasMatch = true; }
                else if (itTags.contains(t)) hasMatch = true;
                if (hasMatch) break;
            }
            if (!hasMatch) return true;
        }
        if (!selectedRules.isEmpty() && !selectedRules.contains(it.rule)) return true;
        if (!qText.isEmpty()) {
            boolean m = (it.key != null && it.key.toLowerCase().contains(qText)) ||
                    (it.message != null && it.message.toLowerCase().contains(qText)) ||
                    (it.component != null && it.component.toLowerCase().contains(qText));
            if (!m) return true;
        }
        if (!excludedTags.isEmpty()) {
            List<String> itTags = it.tags != null ? it.tags : Collections.emptyList();
            boolean hasExcluded = false;
            for (String t : excludedTags) {
                if (t.equals("__NO_TAGS__")) { if (itTags.isEmpty()) hasExcluded = true; }
                else if (itTags.contains(t)) hasExcluded = true;
                if (hasExcluded) break;
            }
            if (hasExcluded) return true;
        }
        return false;
    }

    private Map<String, Object> buildExtraFields(JiraFieldService fields, JiraTempoTeamService tempo,
                                                 String jiraProject, String issueTypeId,
                                                 String epic, String priority, String security,
                                                 String team, String labels, String components) {
        Map<String, Object> extras = new LinkedHashMap<>();

        if (epic != null && !epic.isBlank()) extras.put("customfield_10101", epic);

        if (priority != null && !priority.isBlank()) {
            String id = priority.matches("\\d+") ? priority : fields.findPriorityIdByName(jiraProject, issueTypeId, priority);
            if (id != null) extras.put("priority", new JSONObject().put("id", id));
        }

        if (security != null && !security.isBlank()) {
            String id = security.matches("\\d+") ? security : fields.findSecurityLevelIdByName(jiraProject, issueTypeId, security);
            if (id != null) extras.put("security", new JSONObject().put("id", id));
        }

        if (team != null && !team.isBlank()) {
            Integer teamId = tempo.findIdByName(team);
            if (teamId != null) extras.put("customfield_10202", teamId);
        }

        if (labels != null && !labels.isBlank()) {
            JSONArray arr = new JSONArray();
            for (String l : labels.split(",")) if (!l.trim().isEmpty()) arr.put(l.trim());
            if (arr.length() > 0) extras.put("labels", arr);
        }

        if (components != null && !components.isBlank()) {
            JSONArray arr = new JSONArray();
            for (String c : components.split(",")) {
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