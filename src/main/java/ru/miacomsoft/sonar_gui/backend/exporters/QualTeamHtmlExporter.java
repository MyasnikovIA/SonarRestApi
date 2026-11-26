package ru.miacomsoft.sonar_gui.backend.exporters;

import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.TeamMapping;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public class QualTeamHtmlExporter {

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String message);
    }

    // Сопоставление Quality с именами каталогов на русском
    private static String mapQualityToDir(String qual) {
        if (qual == null) return "Прочее";
        return switch (qual.toUpperCase()) {
            case "SECURITY" -> "Безопасность";
            case "RELIABILITY" -> "Надежность";
            case "MAINTAINABILITY" -> "Сопровождаемость";
            case "SECURITY_HOTSPOT" -> "Уязвимости";
            default -> "Прочее";
        };
    }

    // Вспомогательный метод для определения "худшего" impact'а для файла
    private static Issue.Impact getHighestImpact(List<Issue> issues) {
        if (issues == null || issues.isEmpty()) return null;

        Issue.Impact best = null;
        int maxScore = -1;

        for (Issue it : issues) {
            if (it.impacts != null && !it.impacts.isEmpty()) {
                for (Issue.Impact im : it.impacts) {
                    int score = getSeverityScore(im.severity) * 10 + getQualityScore(im.softwareQuality);
                    if (score > maxScore) {
                        maxScore = score;
                        best = im;
                    }
                }
            } else if (it.severity != null) {
                // Fallback для старых версий
                int score = getSeverityScore(it.severity) * 10;
                if (score > maxScore) {
                    maxScore = score;
                    best = new Issue.Impact("MAINTAINABILITY", it.severity);
                }
            }
        }
        return best;
    }

    private static int getSeverityScore(String sev) {
        if (sev == null) return 0;
        return switch (sev.toUpperCase()) {
            case "BLOCKER" -> 5;
            case "HIGH", "CRITICAL" -> 4;
            case "MEDIUM", "MAJOR" -> 3;
            case "LOW", "MINOR" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private static int getQualityScore(String qual) {
        if (qual == null) return 0;
        return switch (qual.toUpperCase()) {
            case "SECURITY" -> 4;
            case "SECURITY_HOTSPOT" -> 3;
            case "RELIABILITY" -> 2;
            case "MAINTAINABILITY" -> 1;
            default -> 0;
        };
    }

    public static int writeDirectory(Path rootDir,
                                     List<Issue> issues,
                                     Map<String, List<String>> issueSnippets,
                                     Map<String, String> fullSources,
                                     String projectName, String host,
                                     TeamMapping mapping,
                                     ProgressCallback progress,
                                     BooleanSupplier isCancelled) throws IOException, InterruptedException {

        Files.createDirectories(rootDir);

        // Группируем все замечания по файлам (TreeMap для стабильной сортировки)
        Map<String, List<Issue>> issuesByFile = issues.stream()
                .collect(Collectors.groupingBy(
                        it -> it.component != null ? it.component : "(unknown)",
                        TreeMap::new,
                        Collectors.toList()
                ));

        int total = issuesByFile.size();
        AtomicInteger current = new AtomicInteger(0);

        // Создаем пул из 4 потоков
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
            if (isCancelled.getAsBoolean()) {
                break;
            }

            final String file = entry.getKey();
            final List<Issue> fileIssues = entry.getValue();

            futures.add(executor.submit(() -> {
                if (isCancelled.getAsBoolean()) return;

                try {
                    // 1. Определяем самый критичный impact для этого файла
                    Issue.Impact bestImpact = getHighestImpact(fileIssues);
                    String qual = (bestImpact != null && bestImpact.softwareQuality != null) ? bestImpact.softwareQuality : "MAINTAINABILITY";
                    String sev = (bestImpact != null && bestImpact.severity != null) ? bestImpact.severity : "INFO";

                    // 2. Формируем имена каталогов
                    String qualDir = sanitizePathPart(mapQualityToDir(qual));
                    String sevDir = sanitizePathPart(sev.toUpperCase());

                    TeamMapping.FileInfo info = (mapping != null) ? mapping.findInfo(file) : null;
                    String team = (info != null && info.hasTeam()) ? info.team : "Без_владельца";
                    String module = (info != null && info.hasComponent()) ? info.component : "Без_модуля";

                    String teamDir = sanitizePathPart(team);
                    String moduleDir = sanitizePathPart(module);

                    // 3. Создаем путь: Каталог / QUAL / SEV / Владелец / Модуль
                    Path targetDir = rootDir
                            .resolve(qualDir)
                            .resolve(sevDir)
                            .resolve(teamDir)
                            .resolve(moduleDir);

                    Files.createDirectories(targetDir);

                    // 4. Генерируем HTML
                    String fullSource = fullSources.getOrDefault(file, "");
                    String html = formatHtmlFile(
                            fileIssues, issueSnippets, projectName, host, file,
                            fullSource, team, module, qual, sev
                    );

                    // 5. Сохраняем файл
                    String safeFileName = sanitizeFileName(file) + ".html";
                    Path targetFile = targetDir.resolve(safeFileName);

                    // Ограничение длины пути для Windows
                    if (targetFile.toString().length() > 250) {
                        String hash = Integer.toHexString(file.hashCode());
                        safeFileName = hash + "_" + safeFileName;
                        if (safeFileName.length() > 100) {
                            safeFileName = safeFileName.substring(safeFileName.length() - 100);
                        }
                        targetFile = targetDir.resolve(safeFileName);
                    }

                    Files.writeString(targetFile, html, StandardCharsets.UTF_8);

                } catch (Exception e) {
                    System.err.println("[QualTeamHtmlExporter] Ошибка при обработке файла " + file + ": " + e.getMessage());
                } finally {
                    int done = current.incrementAndGet();
                    if (progress != null && (done % 10 == 0 || done == total)) {
                        progress.onProgress(done, total, "Сгенерировано: " + done + "/" + total);
                    }
                }
            }));
        }

        executor.shutdown();
        // Ожидаем завершения всех потоков (максимум 2 часа)
        executor.awaitTermination(2, TimeUnit.HOURS);

        if (isCancelled.getAsBoolean()) {
            throw new InterruptedException("cancelled");
        }

        return current.get();
    }

    private static String formatHtmlFile(List<Issue> issues,
                                         Map<String, List<String>> issueSnippets,
                                         String projectName, String host,
                                         String fileName, String fullSource,
                                         String team, String module,
                                         String qual, String sev) {
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());
        StringBuilder html = new StringBuilder(8192);

        html.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Отчет: ").append(escapeHtml(fileName)).append("</title>\n");
        html.append("<style>\n");
        html.append("body{font-family:system-ui,sans-serif;max-width:1200px;margin:0 auto;padding:20px;background:#fafbfc;color:#172b4d;}\n");
        html.append("h1,h2{color:#0052cc;}\n");
        html.append(".meta-table{border-collapse:collapse;width:100%;margin-bottom:20px;background:#fff;border:1px solid #dfe1e6;border-radius:4px;}\n");
        html.append(".meta-table th,.meta-table td{text-align:left;padding:8px 12px;border-bottom:1px solid #dfe1e6;}\n");
        html.append(".meta-table th{background:#f4f5f7;width:200px;font-weight:600;}\n");
        html.append(".issue{border:1px solid #dfe1e6;border-radius:4px;background:#fff;padding:16px;margin-bottom:16px;}\n");
        html.append("details{margin-top:12px;border:1px solid #dfe1e6;border-radius:4px;background:#f4f5f7;}\n");
        html.append("summary{cursor:pointer;padding:8px 12px;font-weight:600;}\n");
        html.append("pre{margin:0;padding:12px;background:#fff;overflow-x:auto;font-family:monospace;font-size:13px;white-space:pre-wrap;}\n");
        html.append("mark.sonar-issue-mark{background-color:rgba(248,81,73,0.25);border-bottom:2px solid #f85149;}\n");
        html.append("</style>\n</head>\n<body>\n");

        html.append("<h1>Проект: ").append(escapeHtml(projectName)).append("</h1>\n");

        // Блок мета-информации
        html.append("<table class=\"meta-table\">\n");
        html.append("  <tr><th>Файл</th><td>").append(escapeHtml(fileName)).append("</td></tr>\n");
        html.append("  <tr><th>Владелец (Отдел)</th><td>").append(escapeHtml(team)).append("</td></tr>\n");
        html.append("  <tr><th>Модуль</th><td>").append(escapeHtml(module)).append("</td></tr>\n");
        html.append("  <tr><th>Качество (QUAL)</th><td>").append(escapeHtml(qual)).append("</td></tr>\n");
        html.append("  <tr><th>Уровень (SEV)</th><td>").append(escapeHtml(sev)).append("</td></tr>\n");
        html.append("  <tr><th>Всего ошибок</th><td>").append(issues.size()).append("</td></tr>\n");
        html.append("  <tr><th>Сгенерировано</th><td>").append(now).append("</td></tr>\n");
        html.append("</table>\n\n<hr/>\n");

        for (Issue it : issues) {
            String link = "https://" + host + "/project/issues?id=" + urlEnc(projectName)
                    + "&open=" + urlEnc(it.key);
            String fullComponentKey = projectName + ":" + it.component;
            String sourceLink = "https://" + host + "/code?id=" + urlEnc(projectName)
                    + "&selected=" + urlEnc(fullComponentKey);
            String ruleName = (it.ruleName != null && !it.ruleName.isEmpty())
                    ? " — " + it.ruleName : "";
            String tagsStr = (it.tags == null || it.tags.isEmpty())
                    ? "нет" : String.join(", ", it.tags);
            String lastComment = getLastComment(it);

            html.append("<div class=\"issue\">\n");
            html.append("<p><strong>Идентификатор ошибки:</strong> ").append(escapeHtml(it.key));
            if (it.line > 0) html.append(" (Строка: ").append(it.line).append(")");
            html.append("</p>\n");
            html.append("<p><strong>Ссылка:</strong> <a href=\"").append(escapeHtml(link))
                    .append("\" target=\"_blank\">").append(escapeHtml(link)).append("</a></p>\n");
            html.append("<p><strong>Ссылка на исходник:</strong> <a href=\"")
                    .append(escapeHtml(sourceLink)).append("\" target=\"_blank\">")
                    .append(escapeHtml(fullComponentKey)).append("</a></p>\n");
            html.append("<p><strong>Правило:</strong> ").append(escapeHtml(it.rule))
                    .append(escapeHtml(ruleName)).append("</p>\n");
            html.append("<p><strong>Теги:</strong> ").append(escapeHtml(tagsStr)).append("</p>\n");
            html.append("<p><strong>Сообщение:</strong> ").append(escapeHtml(it.message)).append("</p>\n");
            html.append("<p><strong>Комментарий:</strong> ").append(escapeHtml(lastComment)).append("</p>\n");

            List<String> snippet = (issueSnippets != null) ? issueSnippets.get(it.key) : null;
            if (snippet != null && !snippet.isEmpty()) {
                html.append("<details>\n<summary>Показать фрагмент кода</summary>\n<pre><code>");
                for (int i = 0; i < snippet.size(); i++) {
                    if (i > 0) html.append('\n');
                    html.append(renderSnippetLine(snippet.get(i)));
                }
                html.append("</code></pre>\n</details>\n");
            }
            html.append("</div>\n");
        }

        if (fullSource != null && !fullSource.isEmpty()) {
            html.append("<div class=\"issue\" style=\"background:#f4f5f7;\">\n");
            html.append("<details>\n<summary>Показать полный исходный код файла</summary>\n<pre><code>");
            html.append(escapeHtml(fullSource));
            html.append("</code></pre>\n</details>\n");
            html.append("</div>\n");
        }

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static String renderSnippetLine(String line) {
        if (line == null) return "";
        String result = escapeHtml(line)
                .replace("§HL_START§", "<mark class=\"sonar-issue-mark\">")
                .replace("§HL_END§", "</mark>");
        return result.replace("<mark class=\"sonar-issue-mark\"></mark>", "");
    }

    private static String getLastComment(Issue issue) {
        if (issue.comments == null || issue.comments.isEmpty()) return "нет";
        Issue.Comment latest = null;
        for (Issue.Comment c : issue.comments) {
            if (latest == null || String.valueOf(c.createdAt)
                    .compareTo(String.valueOf(latest.createdAt)) > 0) {
                latest = c;
            }
        }
        if (latest == null) return "нет";
        if (latest.markdown != null && !latest.markdown.isEmpty()) return latest.markdown;
        return latest.htmlText != null ? latest.htmlText : "нет";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static String sanitizePathPart(String s) {
        if (s == null || s.trim().isEmpty()) return "Без_названия";
        // Заменяем недопустимые символы и убираем лишние пробелы
        return s.trim().replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }

    private static String sanitizeFileName(String path) {
        if (path.contains(":")) {
            String[] parts = path.split(":");
            path = parts[parts.length - 1];
        }
        path = path.replaceAll("[\\\\/]", "_");
        path = path.replaceAll("[^A-Za-z0-9._-]", "_");
        return path.isEmpty() ? "unknown" : path;
    }
}