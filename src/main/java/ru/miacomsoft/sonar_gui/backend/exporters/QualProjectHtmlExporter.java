package ru.miacomsoft.sonar_gui.backend.exporters;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.ProductMapping;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;
import ru.miacomsoft.sonar_gui.backend.jira.*;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.tabs.report.ReportExportService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class QualProjectHtmlExporter {

    public static void writeDirectory(Path rootDir, List<String> projects, GuiContext ctx,
                                      ReportExportService.BaseFilter filter,
                                      ReportExportService.ProgressListener listener) throws Exception {
        Files.createDirectories(rootDir);
        String host = getHost(ctx);
        ProductMapping pm = ProductMapping.load();

        int totalProjects = projects.size();
        int currentProject = 0;

        for (String project : projects) {
            currentProject++;
            if (listener != null) {
                listener.onProgress(currentProject, totalProjects, "fetching", "Загрузка данных проекта: " + project);
            }
            System.out.println("[QualProjectHtmlExporter] Загрузка проекта " + project + " (" + currentProject + "/" + totalProjects + ")");

            SonarIssuesApi api = ctx.state().newApi(project);
            List<Issue> issues = api.fetchAllByParts(SonarIssuesApi.ALL_STATUSES, filter.severity, filter.quality,
                    (cur, tot, msg) -> {
                        if (listener != null) listener.onProgress(cur, tot, "fetching", project + ": " + msg);
                    });

            // Дедупликация по ключу (на случай мульти-импактов)
            Map<String, Issue> uniqueIssues = new LinkedHashMap<>();
            for (Issue it : issues) uniqueIssues.put(it.key, it);

            // Группировка: QUAL -> SEV -> Файл -> Issues
            Map<String, Map<String, Map<String, List<Issue>>>> structured = new TreeMap<>();

            for (Issue it : uniqueIssues.values()) {
                if (it.tags != null && it.tags.contains("not-require-fix")) continue;

                if (it.impacts != null && !it.impacts.isEmpty()) {
                    for (Issue.Impact im : it.impacts) {
                        String qual = im.softwareQuality != null ? im.softwareQuality.toUpperCase() : "MAINTAINABILITY";
                        String sev = im.severity != null ? im.severity.toUpperCase() : "INFO";
                        structured.computeIfAbsent(qual, k -> new TreeMap<>())
                                .computeIfAbsent(sev, k -> new TreeMap<>())
                                .computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
                    }
                } else if (it.severity != null) {
                    String qual = "MAINTAINABILITY";
                    String sev = it.severity.toUpperCase();
                    structured.computeIfAbsent(qual, k -> new TreeMap<>())
                            .computeIfAbsent(sev, k -> new TreeMap<>())
                            .computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
                }
            }

            String productName = "Без имени продукта";
            String teamOwner = "PFM134"; // По умолчанию из задачи
            ProductMapping.ProductInfo info = pm.findInfo(project);
            if (info != null) {
                if (info.product != null && !info.product.isBlank()) productName = info.product.trim();
                if (info.teamOwner != null && !info.teamOwner.isBlank()) teamOwner = info.teamOwner.trim();
            }

            Path productDir = rootDir.resolve(productName);
            Path projectDir = productDir.resolve(project);

            for (var qualEntry : structured.entrySet()) {
                String qual = qualEntry.getKey();
                String qualDirName = mapQualityToDir(qual);

                for (var sevEntry : qualEntry.getValue().entrySet()) {
                    String sev = sevEntry.getKey();
                    Map<String, List<Issue>> byFile = sevEntry.getValue();

                    Path targetDir = projectDir.resolve(qualDirName).resolve(sev);
                    Files.createDirectories(targetDir);

                    // Загрузка сниппетов
                    Map<String, List<String>> snippets = new HashMap<>();
                    for (List<Issue> lst : byFile.values()) {
                        for (Issue it : lst) {
                            try {
                                List<String> s = api.fetchSnippet(it.key);
                                if (s != null && !s.isEmpty()) snippets.put(it.key, s);
                            } catch (Exception ignored) {}
                        }
                    }

                    // Запись HTML-файлов (используем стандартный экспортер)
                    HtmlPerFileExporter.writeDirectory(
                            targetDir, byFile, snippets, project, host, false, null, null
                    );

                    // Создание задачи в Jira, если нужно
                    if (filter.createJiraTasks) {
                        int issueCount = byFile.values().stream().mapToInt(List::size).sum();
                        createJiraTask(project, qual, sev, issueCount, teamOwner);
                    }
                }
            }
        }

        System.out.println("[QualProjectHtmlExporter] Глубокая выгрузка завершена.");
    }

    private static String mapQualityToDir(String qual) {
        if (qual == null) return "Прочее (UNKNOWN)";
        return switch (qual.toUpperCase()) {
            case "SECURITY" -> "Безопасность (SECURITY)";
            case "RELIABILITY" -> "Надежность (RELIABILITY)";
            case "MAINTAINABILITY" -> "Сопровождаемость (MAINTAINABILITY)";
            case "SECURITY_HOTSPOT" -> "Уязвимости (SECURITY_HOTSPOT)";
            default -> qual.toUpperCase() + " (" + qual.toUpperCase() + ")";
        };
    }

    private static void createJiraTask(String project, String qual, String sev, int issueCount, String teamOwner) {
        try {
            SessionLoader.SessionInfo session = SessionLoader.loadDefault();
            JiraClient jiraClient = new JiraClient(session.baseUrl(), session.cookieHeader());
            JiraUserService userService = new JiraUserService(jiraClient);
            JiraUserService.JiraUser me = userService.getMyself();

            JiraConfig jcfg = JiraConfig.getInstance();
            JiraFieldService fieldService = new JiraFieldService(jiraClient);

            JSONObject fields = new JSONObject();
            fields.put("project", new JSONObject().put("key", jcfg.defaultProject()));
            fields.put("issuetype", new JSONObject().put("id", jcfg.issueType()));

            String qualRus = mapQualityToDir(qual);
            String summary = "Исправление ошибок уровня " + sev + " в проекте " + project;
            fields.put("summary", summary);

            StringBuilder desc = new StringBuilder();
            desc.append("Автоматически созданная задача при экспорте SonarQube.\n");
            desc.append("Проект: ").append(project).append("\n");
            desc.append("Качество (QUAL): ").append(qualRus).append("\n");
            desc.append("Уровень (SEV): ").append(sev).append("\n");
            desc.append("Количество ошибок: ").append(issueCount).append("\n");
            desc.append("Команда: ").append(teamOwner).append("\n");
            fields.put("description", desc.toString());

            if (me.name() != null && !me.name().isBlank()) {
                fields.put("assignee", new JSONObject().put("name", me.name()));
            }

            String epicKey = jcfg.getEpic(qual, sev);
            if (epicKey == null || epicKey.isBlank()) {
                epicKey = jcfg.defaultEpic();
            }

            Map<String, JiraFieldService.FieldMeta> meta = fieldService.getCreatemeta(jcfg.defaultProject(), jcfg.issueType());
            String epicFieldId = null;
            for (JiraFieldService.FieldMeta fm : meta.values()) {
                if ("Epic Link".equalsIgnoreCase(fm.name())) {
                    epicFieldId = fm.id();
                    break;
                }
            }

            if (epicFieldId != null && !epicKey.isBlank()) {
                fields.put(epicFieldId, epicKey);
            }

            JiraIssueService issueService = new JiraIssueService(jiraClient, session.baseUrl(), fieldService, null);
            issueService.create(fields);
            System.out.println("[QualProjectHtmlExporter] Создана задача Jira: " + summary);

        } catch (Exception e) {
            System.err.println("[QualProjectHtmlExporter] Ошибка создания задачи в Jira: " + e.getMessage());
        }
    }

    private static String getHost(GuiContext ctx) {
        try {
            String url = ctx.state().getSession().getUrl();
            java.net.URI u = java.net.URI.create(url);
            String h = u.getHost();
            int p = u.getPort();
            if (h == null || h.isBlank()) return "sonar";
            return (p > 0 && p != 80 && p != 443) ? h + ":" + p : h;
        } catch (Exception e) { return "sonar"; }
    }
}