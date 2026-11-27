package ru.miacomsoft.sonar_gui.common;

import ru.miacomsoft.sonar_gui.backend.ServerState;
import ru.miacomsoft.sonar_gui.backend.PromptTemplate;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaConfig;
import ru.miacomsoft.sonar_gui.client.SonarApiClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Общий контекст GUI.
 *
 * Держит:
 *   - ServerState (SessionManager + SonarClient) — прямой доступ к SonarQube;
 *   - OllamaConfig — настройки LLM;
 *   - PromptTemplate — каталог prompts/*.txt;
 *   - project / sev / qual / hideProcessed — состояние верхней панели.
 */
public class GuiContext {

    private final ServerState state;
    private final OllamaConfig ollamaConfig;
    private final PromptTemplate template;
    private final Path settingsFile;
    private final Path promptsDir;

    private String project        = "";
    private String severity       = "MEDIUM";
    private String quality        = "MAINTAINABILITY";
    private boolean hideProcessed = true;

    private final List<Consumer<GuiContext>> projectListeners = new ArrayList<>();
    private final List<Consumer<GuiContext>> filtersListeners = new ArrayList<>();

    /** @deprecated временно — до переработки ReportExportService в части 9. */
    @Deprecated
    private volatile SonarApiClient legacyApi;

    public GuiContext(ServerState state, OllamaConfig ollamaConfig,
                      PromptTemplate template,
                      Path settingsFile, Path promptsDir) {
        this.state = state;
        this.ollamaConfig = ollamaConfig;
        this.template = template;
        this.settingsFile = settingsFile;
        this.promptsDir = promptsDir;
    }

    // ============ backend access ============

    public ServerState    state()        { return state; }
    public OllamaConfig   ollamaConfig() { return ollamaConfig; }
    public PromptTemplate template()     { return template; }

    /**
     * Возвращает путь к единому файлу настроек (app.properties).
     * Оставлено имя sessionFile() для обратной совместимости.
     */
    public Path           sessionFile()  { return settingsFile; }
    public Path           promptsDir()   { return promptsDir; }

    public boolean isSessionReady() {
        return state != null && state.isSessionReady();
    }

    /** @deprecated использовать state().newApi(project) / state().newBulk(). */
    @Deprecated
    public SonarApiClient api() {
        if (legacyApi == null) {
            synchronized (this) {
                if (legacyApi == null) {
                    legacyApi = new SonarApiClient("http://localhost:6060");
                }
            }
        }
        return legacyApi;
    }

    // ============ state ============

    public String  getProject()      { return project; }
    public String  getSeverity()     { return severity; }
    public String  getQuality()      { return quality; }
    public boolean isHideProcessed() { return hideProcessed; }

    public boolean hasProject() {
        return project != null && !project.isBlank();
    }

    public void setProject(String p) {
        String v = (p == null) ? "" : p.trim();
        if (v.equals(project)) return;
        project = v;
        fire(projectListeners);
    }

    public void setSeverity(String s) {
        String v = (s == null || s.isBlank()) ? "MEDIUM" : s.trim();
        if (v.equals(severity)) return;
        severity = v;
        fire(filtersListeners);
    }

    public void setQuality(String q) {
        String v = (q == null || q.isBlank()) ? "MAINTAINABILITY" : q.trim();
        if (v.equals(quality)) return;
        quality = v;
        fire(filtersListeners);
    }

    public void setHideProcessed(boolean h) {
        if (hideProcessed == h) return;
        hideProcessed = h;
        fire(filtersListeners);
    }

    // ============ listeners ============

    public void onProjectChanged(Consumer<GuiContext> l) { if (l != null) projectListeners.add(l); }
    public void onFiltersChanged(Consumer<GuiContext> l) { if (l != null) filtersListeners.add(l); }

    private void fire(List<Consumer<GuiContext>> list) {
        for (Consumer<GuiContext> l : list) {
            try { l.accept(this); } catch (Exception ignored) { /* UI */ }
        }
    }

    // ============ helpers ============

    public String commonQuery() {
        StringBuilder sb = new StringBuilder();
        if (hasProject()) sb.append("project=").append(urlEnc(project));
        if (sb.length() > 0) sb.append('&');
        sb.append("sev=").append(urlEnc(severity));
        sb.append("&qual=").append(urlEnc(quality));
        sb.append("&hideProcessed=").append(hideProcessed);
        return sb.toString();
    }

    private static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return s == null ? "" : s; }
    }
}