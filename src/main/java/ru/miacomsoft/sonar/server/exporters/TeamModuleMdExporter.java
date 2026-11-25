package ru.miacomsoft.sonar.server.exporters;

import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.TeamMapping;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TeamModuleMdExporter {

    public static int writeZip(Path zipPath, Map<String, List<Issue>> issuesByFile,
                               SonarIssuesApi api, String project, String host,
                               TeamMapping mapping,
                               OutputStream sseOut, long startMs) throws Exception {

        int count = 0;
        int totalFiles = issuesByFile.size();

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
                String file = entry.getKey();
                List<Issue> issues = entry.getValue();
                count++;

                // --- ЛОГИРОВАНИЕ ДЛЯ КОНСОЛИ ---
                if (sseOut == null) {
                    // Выводим прогресс каждые 50 файлов или если файлов мало
                    if (count % 50 == 0 || count == 1 || count == totalFiles || totalFiles <= 50) {
                        long elapsed = System.currentTimeMillis() - startMs;
                        double speed = (double) elapsed / count;
                        long etaMs = (long) (speed * (totalFiles - count));
                        System.out.printf("[team-zip]   ZIP: %d/%d файлов (%.1f%%), ETA: %d сек. Текущий: %s\n",
                                count, totalFiles, (count * 100.0) / totalFiles, etaMs / 1000, file);
                    }
                } else {
                    // --- ЛОГИРОВАНИЕ ДЛЯ WEB (SSE) ---
                    JSONObject p = new JSONObject();
                    p.put("stage", "team-module-gen");
                    p.put("message", "Генерация отчета: " + file);
                    p.put("current", count);
                    p.put("total", totalFiles);
                    p.put("elapsedMs", System.currentTimeMillis() - startMs);
                    String msg = "event: progress\ndata: " + p.toString() + "\n\n";
                    sseOut.write(msg.getBytes(StandardCharsets.UTF_8));
                    sseOut.flush();
                }

                // Получаем информацию о команде и модуле
                TeamMapping.FileInfo info = (mapping != null) ? mapping.findInfo(file) : null;
                String team = (info != null && info.hasTeam()) ? info.team : "Отдел не определен";
                String component = (info != null && info.hasComponent()) ? info.component : "Модуль не определен";

                // Санитайзим имена каталогов
                String safeTeam = sanitizePathPart(team);
                String safeComponent = sanitizePathPart(component);

                String fullSource = "";
                try {
                    fullSource = api.fetchFullSource(project + ":" + file);
                } catch (Exception e) {
                    System.err.println("[TeamModuleMdExporter] Не удалось получить код для " + file);
                }

                String mdContent = buildMarkdown(file, issues, fullSource, project, host, api);
                String fileName = makeFileName(file);

                // Путь в архиве: Отдел / Модуль / Файл.md
                String entryPath = safeTeam + "/" + safeComponent + "/" + fileName + ".md";

                ZipEntry ze = new ZipEntry(entryPath);
                zos.putNextEntry(ze);
                zos.write(mdContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return count;
    }

    private static String buildMarkdown(String file, List<Issue> issues, String fullSource,
                                        String project, String host, SonarIssuesApi api) {
        StringBuilder sb = new StringBuilder();
        String lang = getLanguageExtension(file);

        sb.append("# Файл: `").append(file).append("`\n\n");
        sb.append("## Найденные ошибки:\n\n");

        for (Issue it : issues) {
            String lineStr = it.line > 0 ? " (Строка: " + it.line + ")" : "";
            sb.append("### Ошибка: ").append(it.key).append(lineStr).append("\n\n");

            String link = "https://" + host + "/project/issues?id=" + urlEnc(project) + "&open=" + urlEnc(it.key);
            sb.append("Ссылка: ").append(link).append("\n\n");

            sb.append("Правило: ").append(it.rule);
            if (it.ruleName != null && !it.ruleName.isEmpty()) {
                sb.append(" — ").append(it.ruleName);
            }
            sb.append("\n\n");
            sb.append("Сообщение: ").append(it.message).append("\n\n");

            String lastComment = getLastComment(it);
            if (!"нет".equals(lastComment)) {
                String singleLineComment = lastComment.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();
                sb.append("Пользовательский комментарий: ").append(singleLineComment).append("\n\n");
            }

            try {
                List<String> snippet = api.fetchSnippet(it.key);
                if (snippet != null && !snippet.isEmpty()) {
                    sb.append("Фрагмент кода с ошибкой:\n");
                    sb.append("```").append(lang).append("\n");
                    for (String snippetLine : snippet) {
                        String highlightedLine = snippetLine
                                .replace("§HL_START§", ">>> ")
                                .replace("§HL_END§", " <<<");
                        sb.append(highlightedLine).append("\n");
                    }
                    sb.append("```\n\n");
                }
            } catch (Exception e) {
                System.err.println("[TeamModuleMdExporter] Не удалось загрузить фрагмент для " + it.key);
            }
            sb.append("---\n\n");
        }

        sb.append("## Исходный код файла:\n");
        sb.append("```").append(lang).append("\n");
        sb.append(fullSource).append("\n");
        sb.append("```\n");

        return sb.toString();
    }

    private static String sanitizePathPart(String s) {
        if (s == null || s.trim().isEmpty()) return "Без_названия";
        // Заменяем недопустимые в Windows/Linux символы
        String out = s.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return out;
    }

    private static String makeFileName(String originalFilePath) {
        String cleanPath = originalFilePath;
        if (cleanPath.contains(":")) {
            String[] parts = cleanPath.split(":");
            cleanPath = parts[parts.length - 1];
        }
        cleanPath = cleanPath.replaceAll("[\\\\/]", "_");
        cleanPath = cleanPath.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleanPath;
    }

    private static String getLastComment(Issue issue) {
        if (issue.comments == null || issue.comments.isEmpty()) return "нет";
        Issue.Comment latest = null;
        for (Issue.Comment c : issue.comments) {
            if (latest == null || String.valueOf(c.createdAt).compareTo(String.valueOf(latest.createdAt)) > 0) {
                latest = c;
            }
        }
        if (latest == null) return "нет";
        if (latest.markdown != null && !latest.markdown.isEmpty()) return latest.markdown;
        return latest.htmlText != null ? latest.htmlText : "нет";
    }

    private static String getLanguageExtension(String file) {
        String lower = file.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts")) return "typescript";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".html")) return "html";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".cpp") || lower.endsWith(".h")) return "cpp";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".frm")) return "pascal";
        return "text";
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}