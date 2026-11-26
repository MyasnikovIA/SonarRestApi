package ru.miacomsoft.sonar_gui.common;

import ru.miacomsoft.sonar_gui.client.SonarApiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Общий контекст GUI: единственный клиент к Sonar REST API,
 * текущие project/sev/qual/hideProcessed и подписчики на их изменения.
 * Аналог state + ctx из web/app.js.
 */
public class GuiContext {

    private final SonarApiClient api;

    private String project        = "";
    private String severity       = "MEDIUM";
    private String quality        = "MAINTAINABILITY";
    private boolean hideProcessed = true;

    private final List<Consumer<GuiContext>> projectListeners  = new ArrayList<>();
    private final List<Consumer<GuiContext>> filtersListeners  = new ArrayList<>();

    public GuiContext(String baseUrl) {
        this.api = new SonarApiClient(baseUrl);
    }

    // ============ getters ============

    public SonarApiClient api()      { return api; }
    public String  getProject()      { return project; }
    public String  getSeverity()     { return severity; }
    public String  getQuality()      { return quality; }
    public boolean isHideProcessed() { return hideProcessed; }

    public boolean hasProject() {
        return project != null && !project.isBlank();
    }

    // ============ setters ============

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

    public void onProjectChanged(Consumer<GuiContext> l)  { if (l != null) projectListeners.add(l); }
    public void onFiltersChanged(Consumer<GuiContext> l)  { if (l != null) filtersListeners.add(l); }

    private void fire(List<Consumer<GuiContext>> list) {
        for (Consumer<GuiContext> l : list) {
            try { l.accept(this); } catch (Exception ignored) { /* UI callback */ }
        }
    }

    // ============ query helpers ============

    /** Базовый набор параметров: project + sev + qual + hideProcessed. */
    public String commonQuery() {
        StringBuilder sb = new StringBuilder();
        if (hasProject()) {
            sb.append("project=").append(SonarApiClient.enc(project));
        }
        if (sb.length() > 0) sb.append('&');
        sb.append("sev=").append(SonarApiClient.enc(severity));
        sb.append("&qual=").append(SonarApiClient.enc(quality));
        sb.append("&hideProcessed=").append(hideProcessed);
        return sb.toString();
    }
}