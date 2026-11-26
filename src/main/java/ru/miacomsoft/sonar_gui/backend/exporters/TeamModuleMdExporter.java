package ru.miacomsoft.sonar_gui.backend.exporters;

import ru.miacomsoft.sonar_gui.backend.Issue;
import ru.miacomsoft.sonar_gui.backend.SonarIssuesApi;
import ru.miacomsoft.sonar_gui.backend.TeamMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ZIP с MD-отчётами, разложенными по командам/модулям (из team_mapping.csv).
 *
 * Реализация минимальна: MD-файл по каждому SonarQube-файлу с префиксом
 * (команда)[модуль]_ в имени. Если team_mapping.csv нет или строки нет —
 * файл кладётся в корень ZIP.
 */
public class TeamModuleMdExporter {

    public static int writeZip(Path zipPath, Map<String, List<Issue>> issuesByFile,
                               SonarIssuesApi api, String project, String host,
                               TeamMapping mapping) throws Exception {
        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, List<Issue>> entry : issuesByFile.entrySet()) {
                String file = entry.getKey();
                List<Issue> issues = entry.getValue();

                String fullSource = "";
                try { fullSource = api.fetchFullSource(project + ":" + file); }
                catch (Exception ignored) {}

                String md = buildMd(file, issues, fullSource, project, host);

                String safeName = makeFileName(file);
                String prefix = "";
                if (mapping != null) {
                    TeamMapping.FileInfo info = mapping.findInfo(file);
                    if (info != null && !info.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        if (info.hasTeam()) sb.append("(").append(info.team).append(")");
                        if (info.hasComponent()) sb.append("[").append(info.component).append("]");
                        if (sb.length() > 0) prefix = sb.append("_").toString();
                    }
                }
                zos.putNextEntry(new ZipEntry(prefix + safeName + ".md"));
                zos.write(md.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }

    private static String buildMd(String file, List<Issue> issues, String fullSource,
                                  String project, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(file).append("\n\n");
        sb.append("Всего issues: ").append(issues.size()).append("\n\n");

        for (Issue it : issues) {
            sb.append("## ").append(it.key);
            if (it.line > 0) sb.append(" · L").append(it.line);
            sb.append("\n\n");
            sb.append("- **Правило:** ").append(it.rule);
            if (it.ruleName != null && !it.ruleName.isEmpty())
                sb.append(" — ").append(it.ruleName);
            sb.append("\n");
            sb.append("- **Severity:** ").append(
                    it.impactSeverity != null && !it.impactSeverity.isEmpty()
                            ? it.impactSeverity : it.severity).append("\n");
            sb.append("- **Status:** ").append(it.status).append("\n");
            sb.append("- **Сообщение:** ").append(it.message).append("\n\n");
        }

        if (fullSource != null && !fullSource.isEmpty()) {
            sb.append("## Полный листинг\n\n```\n").append(fullSource).append("\n```\n");
        }
        return sb.toString();
    }

    private static String makeFileName(String path) {
        String clean = path;
        if (clean.contains(":")) clean = clean.substring(clean.indexOf(':') + 1);
        return clean.replaceAll("[\\\\/]", "_").replaceAll("[^A-Za-z0-9._-]", "_");
    }
}