package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.exporters.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ExportStreamHandler extends BaseHandler {

    public ExportStreamHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String format = param(q, "format", "html");
        String sev = param(q, "sev", "ALL");
        String qual = param(q, "qual", "ALL");
        String rule = param(q, "rule", null);
        boolean hideProcessed = paramBool(q, "hideProcessed", false);

        String tagsParam = param(q, "tags", "");
        String excludedTagsParam = param(q, "excludedTags", "");
        String selectedRulesParam = param(q, "selectedRules", "");
        String filterText = param(q, "filterText", "");
        String statusFilter = param(q, "statusFilter", "ALL");

        List<String> keys = null;
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            byte[] body = ex.getRequestBody().readAllBytes();
            if (body.length > 0) {
                try {
                    JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    if (req.has("keys")) {
                        JSONArray arr = req.getJSONArray("keys");
                        keys = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) keys.add(arr.getString(i));
                    }
                } catch (Exception e) {
                    System.err.println("[ExportStream] Ошибка парсинга POST тела: " + e.getMessage());
                }
            }
        }

        System.out.println("[ExportStream] Запрос на экспорт: project=" + project +
                ", format=" + format + ", sev=" + sev + ", qual=" + qual +
                ", rule=" + (rule == null ? "нет" : rule) +
                ", hideProcessed=" + hideProcessed +
                ", keys=" + (keys == null ? "нет" : keys.size()));

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        try {
            if (keys != null && !keys.isEmpty()) {
                runExportByKeys(os, project, format, keys, sev, qual);
            } else {
                runExport(os, project, format, sev, qual, rule, hideProcessed,
                        tagsParam, excludedTagsParam, selectedRulesParam, filterText, statusFilter);
            }
        } catch (Exception e) {
            System.err.println("[ExportStream] ОШИБКА: " + e.getMessage());
            e.printStackTrace();
            try {
                sendEvent(os, "error", new JSONObject().put("message", e.getMessage()));
            } catch (Exception ignored) {}
        } finally {
            try { os.close(); } catch (IOException ignored) {}
        }
    }

    private void sendEvent(OutputStream os, String event, JSONObject data) {
        try {
            String msg = "event: " + event + "\ndata: " + data.toString() + "\n\n";
            os.write(msg.getBytes(StandardCharsets.UTF_8));
            os.flush();
            if (!"complete".equals(event)) {
                System.out.println("[ExportStream] Событие: " + event + " -> " + data.toString());
            }
        } catch (Exception e) {
            System.err.println("[ExportStream] Ошибка отправки события: " + e.getMessage());
        }
    }

    // =========================================================
    // Выгрузка по ключам (переданным с клиента)
    // =========================================================
    private void runExportByKeys(OutputStream os, String project, String format, List<String> keys, String sev, String qual) throws Exception {
        SonarIssuesApi api = state.newApi(project);
        String host = state.getSession() != null ? state.getSession().getHost() : "sonar.bars.group";

        int total = keys.size();
        sendEvent(os, "progress", new JSONObject()
                .put("stage", "start")
                .put("total", total)
                .put("current", 0)
                .put("message", "Начинаем сбор данных по " + total + " ключам..."));

        StringBuilder reportContent = new StringBuilder();
        int loadedCount = 0;

        if ("jira".equals(format)) reportContent.append("h3. Проект: ").append(project).append("\n\n");
        else if ("md".equals(format)) reportContent.append("# Отчет по проекту: ").append(project).append("\n\n");
        else if ("txt".equals(format)) reportContent.append("=== Отчет по проекту: ").append(project).append(" ===\n\n");
        else if ("html".equals(format) || "html-files".equals(format) || "html-files-teams".equals(format)) {
            reportContent.append("<!DOCTYPE html><html><head><meta charset='utf-8'></head><body>\n");
            reportContent.append("<h1>Проект: ").append(project).append("</h1>\n");
        } else if ("confluence".equals(format)) reportContent.append("<h2>Проект: ").append(project).append("</h2>\n");

        int batchSize = 100;
        for (int i = 0; i < keys.size(); i += batchSize) {
            List<String> batch = keys.subList(i, Math.min(i + batchSize, keys.size()));
            String keysCsv = String.join(",", batch);

            try {
                JSONObject raw = api.fetchIssuesByKeys(keysCsv);
                JSONArray arr = raw.optJSONArray("issues");
                if (arr != null) {
                    for (int j = 0; j < arr.length(); j++) {
                        Issue it = Issue.from(arr.getJSONObject(j), project);
                        if ("jira".equals(format)) reportContent.append(JiraExporter.exportSingle(it, project, host));
                        else if ("md".equals(format)) reportContent.append(MdExporter.exportSingle(it, project, host));
                        else if ("txt".equals(format)) reportContent.append(TxtExporter.exportSingle(it, project, host));
                        else if ("html".equals(format) || "html-files".equals(format) || "html-files-teams".equals(format)) {
                            reportContent.append(HtmlExporter.exportSingle(it, project, host));
                        } else if ("confluence".equals(format)) reportContent.append(ConfluenceExporter.exportSingle(it, project, host));
                        loadedCount++;
                    }
                }
            } catch (Exception e) {
                System.err.println("[ExportStream] Ошибка при запросе пачки ключей: " + e.getMessage());
            }

            sendEvent(os, "progress", new JSONObject()
                    .put("stage", "fetching")
                    .put("total", total)
                    .put("current", loadedCount)
                    .put("message", "Загружено " + loadedCount + " из " + total));
        }

        if ("html".equals(format) || "html-files".equals(format) || "html-files-teams".equals(format)) {
            reportContent.append("</body></html>");
        }

        String ext = "html";
        if ("jira".equals(format)) ext = "txt";
        if ("md".equals(format)) ext = "md";
        if ("txt".equals(format)) ext = "txt";

        Path tempFile = Files.createTempFile("sonar_report_", "." + ext);
        Files.writeString(tempFile, reportContent.toString(), StandardCharsets.UTF_8);
        System.out.println("[ExportStream] Файл сформирован: " + tempFile.toAbsolutePath());

        sendEvent(os, "complete", new JSONObject()
                .put("file", tempFile.toAbsolutePath().toString())
                .put("total", loadedCount)
                .put("message", "Файл успешно сформирован"));
    }

    // =========================================================
    // Старый режим выгрузки (по фильтрам)
    // =========================================================
    private void runExport(OutputStream os, String project, String format, String sev, String qual, String rule, boolean hideProcessed,
                           String tagsParam, String excludedTagsParam, String selectedRulesParam, String filterText, String statusFilter) throws Exception {
        SonarIssuesApi api = state.newApi(project);
        String host = state.getSession() != null ? state.getSession().getHost() : "sonar.bars.group";

        String statuses = hideProcessed ? SonarIssuesApi.ACTIVE_STATUSES : null;

        // Логируем фильтры
        System.out.println("[ExportStream] Фильтры: sev=" + sev + ", qual=" + qual +
                ", rule=" + (rule == null ? "нет" : rule) +
                ", hideProcessed=" + hideProcessed +
                ", tags=" + tagsParam +
                ", excludedTags=" + excludedTagsParam +
                ", selectedRules=" + selectedRulesParam +
                ", filterText=" + filterText +
                ", statusFilter=" + statusFilter);

        sendEvent(os, "progress", new JSONObject()
                .put("stage", "start")
                .put("message", "Подсчет общего количества замечаний...")
                .put("current", 0)
                .put("total", 0));

        // Получаем общее количество с учетом фильтров
        JSONObject firstRaw = api.fetchPageRaw(statuses, sev, qual, rule, 1, 1);
        int total = firstRaw.optInt("total", 0);

        if (total == 0) {
            sendEvent(os, "error", new JSONObject().put("message", "Нет замечаний для выгрузки"));
            return;
        }

        System.out.println("[ExportStream] Всего замечаний: " + total);

        sendEvent(os, "progress", new JSONObject()
                .put("stage", "start")
                .put("total", total)
                .put("current", 0)
                .put("message", "Начинаем сбор данных (всего " + total + " замечаний)..."));

        StringBuilder reportContent = new StringBuilder();
        int loadedCount = 0;

        if ("jira".equals(format)) reportContent.append("h3. Проект: ").append(project).append("\n\n");
        else if ("md".equals(format)) reportContent.append("# Отчет по проекту: ").append(project).append("\n\n");
        else if ("txt".equals(format)) reportContent.append("=== Отчет по проекту: ").append(project).append(" ===\n\n");
        else if ("html".equals(format) || "html-files".equals(format) || "html-files-teams".equals(format)) {
            reportContent.append("<!DOCTYPE html><html><head><meta charset='utf-8'></head><body>\n");
            reportContent.append("<h1>Проект: ").append(project).append("</h1>\n");
        } else if ("confluence".equals(format)) reportContent.append("<h2>Проект: ").append(project).append("</h2>\n");

        Set<String> selectedTags = new HashSet<>();
        if (!tagsParam.isEmpty()) {
            for (String t : tagsParam.split(",")) if (!t.isEmpty()) selectedTags.add(t.trim());
        }

        Set<String> excludedTags = new HashSet<>();
        if (!excludedTagsParam.isEmpty()) {
            for (String t : excludedTagsParam.split(",")) if (!t.isEmpty()) excludedTags.add(t.trim());
        }

        Set<String> selectedRules = new HashSet<>();
        if (!selectedRulesParam.isEmpty()) {
            for (String r : selectedRulesParam.split(",")) if (!r.isEmpty()) selectedRules.add(r.trim());
        }

        String qText = filterText == null ? "" : filterText.toLowerCase();

        Set<String> rulesToProcess = new HashSet<>();
        if (rule != null && !rule.isEmpty()) {
            rulesToProcess.add(rule);
        } else {
            try {
                Map<String, Integer> facetRules = api.facetWithStatuses(statuses, sev, qual, null, "rules");
                for (String r : facetRules.keySet()) {
                    if (selectedRules.isEmpty() || selectedRules.contains(r)) {
                        rulesToProcess.add(r);
                    }
                }
                System.out.println("[ExportStream] Найдено правил: " + rulesToProcess.size());
            } catch (Exception e) {
                System.err.println("[ExportStream] Не удалось получить фасеты правил: " + e.getMessage());
                rulesToProcess.add(null);
            }
        }

        List<String> projectFiles = null;

        for (String currentRule : rulesToProcess) {
            System.out.println("[ExportStream] Обработка правила: " + (currentRule == null ? "все" : currentRule));

            int pageSize = 500;
            int page = 1;
            int ruleTotal = -1;
            int ruleLoaded = 0;
            boolean needFileSplit = false;

            while (true) {
                try {
                    JSONObject raw = api.fetchPageRaw(statuses, sev, qual, currentRule, page, pageSize);

                    if (ruleTotal < 0) {
                        ruleTotal = raw.optInt("total", 0);
                        System.out.println("[ExportStream] Правило " + currentRule + ": всего " + ruleTotal + " замечаний");
                    }

                    JSONArray arr = raw.optJSONArray("issues");
                    if (arr == null || arr.length() == 0) break;

                    for (int i = 0; i < arr.length(); i++) {
                        Issue it = Issue.from(arr.getJSONObject(i), project);

                        if (statusFilter != null && !statusFilter.isEmpty() && !"ALL".equals(statusFilter)) {
                            boolean isProcessed = !"OPEN".equals(it.status) && !"REOPENED".equals(it.status);
                            if ("PROCESSED".equals(statusFilter) && !isProcessed) continue;
                            if ("UNPROCESSED".equals(statusFilter) && isProcessed) continue;
                        }

                        if (!selectedTags.isEmpty()) {
                            List<String> itTags = it.tags != null ? it.tags : Collections.emptyList();
                            boolean hasMatch = false;
                            for (String t : selectedTags) {
                                if (t.equals("__NO_TAGS__")) {
                                    if (itTags.isEmpty()) hasMatch = true;
                                } else if (itTags.contains(t)) {
                                    hasMatch = true;
                                }
                                if (hasMatch) break;
                            }
                            if (!hasMatch) continue;
                        }

                        if (!selectedRules.isEmpty()) {
                            if (!selectedRules.contains(it.rule)) continue;
                        }

                        if (!qText.isEmpty()) {
                            boolean matchesText =
                                    (it.key != null && it.key.toLowerCase().contains(qText)) ||
                                            (it.message != null && it.message.toLowerCase().contains(qText)) ||
                                            (it.component != null && it.component.toLowerCase().contains(qText));
                            if (!matchesText) continue;
                        }

                        if (!excludedTags.isEmpty()) {
                            List<String> itTags = it.tags != null ? it.tags : Collections.emptyList();
                            boolean hasExcluded = false;
                            for (String t : excludedTags) {
                                if (t.equals("__NO_TAGS__")) {
                                    if (itTags.isEmpty()) hasExcluded = true;
                                } else if (itTags.contains(t)) {
                                    hasExcluded = true;
                                }
                                if (hasExcluded) break;
                            }
                            if (hasExcluded) continue;
                        }

                        if ("jira".equals(format)) reportContent.append(JiraExporter.exportSingle(it, project, host));
                        else if ("md".equals(format)) reportContent.append(MdExporter.exportSingle(it, project, host));
                        else if ("txt".equals(format)) reportContent.append(TxtExporter.exportSingle(it, project, host));
                        else if ("html".equals(format) || "html-files".equals(format) || "html-files-teams".equals(format)) {
                            reportContent.append(HtmlExporter.exportSingle(it, project, host));
                        } else if ("confluence".equals(format)) reportContent.append(ConfluenceExporter.exportSingle(it, project, host));

                        loadedCount++;
                        ruleLoaded++;
                    }

                    sendEvent(os, "progress", new JSONObject()
                            .put("stage", "fetching")
                            .put("total", total)
                            .put("current", loadedCount)
                            .put("message", "Загружено " + loadedCount + " из " + total + " (правило: " + (currentRule == null ? "все" : currentRule) + ")"));

                    page++;

                    if (ruleTotal > 0 && ruleLoaded >= ruleTotal) break;

                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("10000")) {
                        System.out.println("[ExportStream] Правило " + currentRule + " содержит >10000. Разбиваем по файлам...");
                        needFileSplit = true;
                        break;
                    } else {
                        System.err.println("[ExportStream] Ошибка на странице " + page + ": " + e.getMessage());
                        sendEvent(os, "error", new JSONObject().put("message", e.getMessage()));
                        return;
                    }
                }
            }

            if (needFileSplit) {
                if (projectFiles == null) {
                    try {
                        projectFiles = api.fetchProjectFilesList();
                        System.out.println("[ExportStream] Получен список файлов: " + projectFiles.size());
                    } catch (Exception e) {
                        System.err.println("[ExportStream] Не удалось получить список файлов: " + e.getMessage());
                        sendEvent(os, "error", new JSONObject().put("message", "Не удалось получить список файлов для разбивки"));
                        return;
                    }
                }

                for (String file : projectFiles) {
                    int filePage = 1;
                    while (true) {
                        try {
                            JSONObject raw = api.fetchPageRawForComponent(statuses, sev, qual, currentRule, file, filePage, pageSize);
                            JSONArray arr = raw.optJSONArray("issues");
                            if (arr == null || arr.length() == 0) break;

                            for (int i = 0; i < arr.length(); i++) {
                                Issue it = Issue.from(arr.getJSONObject(i), project);
                                if ("jira".equals(format)) reportContent.append(JiraExporter.exportSingle(it, project, host));
                                else if ("md".equals(format)) reportContent.append(MdExporter.exportSingle(it, project, host));
                                else if ("txt".equals(format)) reportContent.append(TxtExporter.exportSingle(it, project, host));
                                else if ("html".equals(format)) reportContent.append(HtmlExporter.exportSingle(it, project, host));
                                loadedCount++;
                            }
                            filePage++;

                            sendEvent(os, "progress", new JSONObject()
                                    .put("stage", "fetching")
                                    .put("total", total)
                                    .put("current", loadedCount)
                                    .put("message", "Загружено " + loadedCount + " из " + total + " (правило: " + currentRule + ", файл: " + file + ")"));

                        } catch (Exception e) {
                            System.err.println("[ExportStream] Ошибка при запросе файла " + file + ": " + e.getMessage());
                            break;
                        }
                    }
                }
            }
        }

        if ("html".equals(format) || "html-files".equals(format) || "html-files-teams".equals(format)) {
            reportContent.append("</body></html>");
        }

        String ext = "html";
        if ("jira".equals(format)) ext = "txt";
        if ("md".equals(format)) ext = "md";
        if ("txt".equals(format)) ext = "txt";

        Path tempFile = Files.createTempFile("sonar_report_", "." + ext);
        Files.writeString(tempFile, reportContent.toString(), StandardCharsets.UTF_8);
        System.out.println("[ExportStream] Файл сформирован: " + tempFile.toAbsolutePath());

        sendEvent(os, "complete", new JSONObject()
                .put("file", tempFile.toAbsolutePath().toString())
                .put("total", loadedCount)
                .put("message", "Файл успешно сформирован"));
    }
}