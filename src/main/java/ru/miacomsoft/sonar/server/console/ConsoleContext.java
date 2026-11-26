package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BootstrapFiles;
import ru.miacomsoft.sonar.server.OllamaConfig;
import ru.miacomsoft.sonar.server.PromptTemplate;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Общее изменяемое состояние консоли и хелперы, которые переиспользуются
 * несколькими группами команд.
 *
 * Вынесено из {@code Console}, чтобы:
 *   - уменьшить размер основного класса;
 *   - дать всем подкомандам единый доступ к state/project/flags;
 *   - сосредоточить в одном месте persistence (console.properties)
 *     и общие операции над снимком/issues.
 */
public class ConsoleContext {

    private final ServerState state;
    private final OllamaConfig ollamaConfig;
    private final PromptTemplate template;
    private final Path consoleStateFile;

    private volatile String  project;
    private volatile boolean running           = true;
    private volatile boolean autoRunning       = false;
    private volatile boolean autoStopRequested = false;

    public ConsoleContext(ServerState state, OllamaConfig ollamaConfig, Path consoleStateFile) {
        this.state            = state;
        this.ollamaConfig     = ollamaConfig;
        this.consoleStateFile = consoleStateFile;
        this.template         = new PromptTemplate(BootstrapFiles.resolvePromptsDir());

        String cliProject = state.getDefaultProject();
        String saved      = loadSavedProject();
        if (cliProject != null && !cliProject.isEmpty()) {
            this.project = cliProject;
        } else if (saved != null && !saved.isEmpty()) {
            this.project = saved;
            System.out.println("[console] восстановлен project из " + consoleStateFile + ": " + saved);
        } else {
            this.project = "";
        }
    }

    // ==================== accessors ====================

    public ServerState   state()           { return state; }
    public OllamaConfig  ollamaConfig()    { return ollamaConfig; }
    public PromptTemplate template()       { return template; }
    public Path          consoleStateFile(){ return consoleStateFile; }

    public String getProject() { return project; }

    /** Устанавливает и сохраняет в console.properties. */
    public void setProject(String value) {
        this.project = (value == null) ? "" : value;
        saveProject(this.project);
    }

    /** Сбрасывает project и сохраняет пустое значение. */
    public void clearProject() {
        this.project = "";
        saveProject("");
    }

    public boolean isRunning()             { return running; }
    public void    setRunning(boolean v)   { this.running = v; }

    public boolean isAutoRunning()             { return autoRunning; }
    public void    setAutoRunning(boolean v)   { this.autoRunning = v; }

    public boolean isAutoStopRequested()             { return autoStopRequested; }
    public void    setAutoStopRequested(boolean v)   { this.autoStopRequested = v; }

    // ==================== persistence ====================

    private String loadSavedProject() {
        if (consoleStateFile == null || !Files.exists(consoleStateFile)) return "";
        try (InputStream in = Files.newInputStream(consoleStateFile)) {
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("project", "").trim();
        } catch (IOException e) {
            System.err.println("[console] не удалось прочитать " + consoleStateFile
                    + ": " + e.getMessage());
            return "";
        }
    }

    private void saveProject(String value) {
        if (consoleStateFile == null) return;
        try {
            Properties p = new Properties();
            p.setProperty("project", value == null ? "" : value);
            Path parent = consoleStateFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(consoleStateFile)) {
                p.store(out, "Console state (last selected project)");
            }
        } catch (IOException e) {
            System.err.println("[console] не удалось сохранить " + consoleStateFile
                    + ": " + e.getMessage());
        }
    }

    // ==================== shared helpers ====================

    public boolean requireProject() {
        if (project == null || project.isEmpty()) {
            System.out.println("[console] project не задан. Используйте 'project <KEY>' "
                    + "или задайте --project / SONAR_PROJECT при старте.");
            return false;
        }
        return true;
    }

    public Snapshot snapshotFor(String projectKey) {
        Snapshot s = state.getSnapshot();
        return (s != null && s.projectKey.equals(projectKey)) ? s : null;
    }

    /**
     * ИЗМЕНЕНО: раньше без снимка вызывался {@code api.fetchAll(sev, qual)},
     * который внутри использует ACTIVE_STATUSES (OPEN,CONFIRMED,REOPENED)
     * — то есть БЕЗ ACCEPTED. Из-за этого команды qual-summary / summary /
     * details / perfile / matrix / issues не учитывали принятые замечания
     * (и другие resolved-статусы). Теперь явно запрашиваем ALL_STATUSES
     * (OPEN,CONFIRMED,REOPENED,ACCEPTED,FALSE_POSITIVE,RESOLVED,CLOSED,WONTFIX).
     * sanitizeStatuses() в SonarIssuesApi сам смапит legacy → MQR и отбросит
     * неподдерживаемые сервером статусы.
     *
     * Если снимок уже построен — по-прежнему используем его (снимок содержит
     * ровно те статусы, с которыми он собирался через Snapshot.build()).
     */
    public List<Issue> fetchIssues(String project, String sev, String qual) throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) return s.filter(sev, qual);
        return state.newApi(project)
                .fetchAllByStatuses(SonarIssuesApi.ALL_STATUSES, sev, qual);
    }

    /**
     * ИЗМЕНЕНО: аналогично fetchIssues() — issuesByFile(sev, qual) использует
     * ACTIVE_STATUSES. Заменено на issuesByFileByStatuses(ALL_STATUSES, …),
     * чтобы ACCEPTED попадал в отчёты по файлам.
     */
    public Map<String, List<Issue>> fetchIssuesByFile(String project, String sev, String qual)
            throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) return s.filterByFile(sev, qual);
        return state.newApi(project)
                .issuesByFileByStatuses(SonarIssuesApi.ALL_STATUSES, sev, qual);
    }

    public List<String> fetchSnippetCached(String project, String key) throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) {
            List<String> cached = s.snippet(key);
            if (!cached.isEmpty()) return cached;
        }
        return state.newApi(project).fetchSnippet(key);
    }

    public static String[] sevQual(String[] a, int from) {
        String sev  = a.length > from     ? a[from].toUpperCase(Locale.ROOT)         : "MEDIUM";
        String qual = a.length > from + 1 ? a[from + 1].toUpperCase(Locale.ROOT)     : "MAINTAINABILITY";
        return new String[]{sev, qual};
    }

    public static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}