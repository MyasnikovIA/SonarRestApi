package ru.miacomsoft.sonar_gui.frame;

import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.HealthService;
import ru.miacomsoft.sonar_gui.common.services.ProjectsService;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Верхняя панель GUI-клиента (аналог topbar из web/index.html):
 *   - поле project (с автодополнением из /api/projects);
 *   - выпадающие списки sev / qual;
 *   - индикатор состояния (health-polling каждые 5 сек);
 *   - кнопка "Обновить", эмитящая событие onRefresh.
 */
public class TopBar extends JPanel {

    private final GuiContext ctx;
    private final HealthService healthService;
    private final ProjectsService projectsService;

    private final JComboBox<String> projectCombo;
    private final JComboBox<String> sevCombo;
    private final JComboBox<String> qualCombo;
    private final JLabel statusDot;
    private final JLabel statusText;
    private final JButton refreshBtn;

    private Consumer<GuiContext> onRefresh;
    private Timer healthTimer;

    public TopBar(GuiContext ctx) {
        this.ctx = ctx;
        this.healthService = new HealthService(ctx.api());
        this.projectsService = new ProjectsService(ctx.api());

        setLayout(new BorderLayout(12, 0));
        setBackground(GuiStyle.BG2);
        setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));

        // ---------- Brand + project ----------
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);

        JLabel brand = new JLabel("◈ Sonar Rest API GUI");
        brand.setForeground(GuiStyle.ACCENT);
        brand.setFont(GuiStyle.FONT_TITLE);
        left.add(brand);

        left.add(Box.createHorizontalStrut(20));

        JLabel projectLbl = UiHelpers.dimLabel("project:");
        left.add(projectLbl);

        projectCombo = new JComboBox<>();
        projectCombo.setEditable(true);
        projectCombo.setPreferredSize(new Dimension(320, 26));
        projectCombo.setBackground(GuiStyle.BG);
        projectCombo.setForeground(GuiStyle.TEXT);
        projectCombo.setFont(GuiStyle.FONT_MONO);
        left.add(projectCombo);

        JButton loadProjects = UiHelpers.secondaryButton("↻");
        loadProjects.setToolTipText("Загрузить список проектов");
        loadProjects.addActionListener(e -> loadProjectsAsync());
        left.add(loadProjects);

        add(left, BorderLayout.WEST);

        // ---------- sev / qual ----------
        JPanel center = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        center.setOpaque(false);

        center.add(UiHelpers.dimLabel("sev:"));
        sevCombo = UiHelpers.darkCombo(
                new String[]{"MEDIUM", "HIGH", "LOW", "INFO", "BLOCKER", "ALL"},
                ctx.getSeverity());
        sevCombo.setPreferredSize(new Dimension(120, 26));
        center.add(sevCombo);

        center.add(UiHelpers.dimLabel("qual:"));
        qualCombo = UiHelpers.darkCombo(
                new String[]{"MAINTAINABILITY", "RELIABILITY", "SECURITY",
                        "SECURITY_HOTSPOT", "ALL"},
                ctx.getQuality());
        qualCombo.setPreferredSize(new Dimension(180, 26));
        center.add(qualCombo);

        add(center, BorderLayout.CENTER);

        // ---------- status + refresh ----------
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        statusDot = new JLabel("●");
        statusDot.setForeground(GuiStyle.TEXT_DIM);
        statusDot.setFont(GuiStyle.FONT_BOLD);
        right.add(statusDot);

        statusText = new JLabel("Подключение…");
        statusText.setForeground(GuiStyle.TEXT_DIM);
        statusText.setFont(GuiStyle.FONT_BASE);
        right.add(statusText);

        refreshBtn = UiHelpers.primaryButton("↻ Обновить");
        refreshBtn.addActionListener(e -> {
            if (onRefresh != null) onRefresh.accept(ctx);
        });
        right.add(refreshBtn);

        add(right, BorderLayout.EAST);

        // ---------- события ----------
        projectCombo.addActionListener(e -> {
            Object v = projectCombo.getEditor().getItem();
            String s = v == null ? "" : v.toString().trim();
            ctx.setProject(s);
            loadProjectsAsyncIfNeeded();
        });

        sevCombo.addActionListener(e -> {
            Object v = sevCombo.getSelectedItem();
            ctx.setSeverity(v == null ? "MEDIUM" : v.toString());
        });

        qualCombo.addActionListener(e -> {
            Object v = qualCombo.getSelectedItem();
            ctx.setQuality(v == null ? "MAINTAINABILITY" : v.toString());
        });

        // ---------- health polling ----------
        healthTimer = new Timer(5000, e -> checkHealthAsync());
        healthTimer.start();
        checkHealthAsync();

        // ---------- project listener ----------
        ctx.onProjectChanged(c -> SwingUtilities.invokeLater(() -> {
            Object v = projectCombo.getEditor().getItem();
            String current = v == null ? "" : v.toString().trim();
            if (!current.equals(c.getProject())) {
                projectCombo.getEditor().setItem(c.getProject());
            }
        }));
    }

    public void setOnRefresh(Consumer<GuiContext> callback) {
        this.onRefresh = callback;
    }

    // ============ HEALTH ============

    private void checkHealthAsync() {
        new SwingWorker<JSONObject, Void>() {
            @Override
            protected JSONObject doInBackground() throws Exception {
                return healthService.fetchHealth();
            }

            @Override
            protected void done() {
                try {
                    JSONObject h = get();
                    boolean ready = h.optBoolean("sessionReady", false);
                    if (ready) {
                        statusDot.setForeground(GuiStyle.OK);
                        statusText.setText("Готов к работе");
                    } else {
                        statusDot.setForeground(GuiStyle.ERR);
                        statusText.setText("session not ready");
                    }
                } catch (Exception ex) {
                    statusDot.setForeground(GuiStyle.ERR);
                    statusText.setText("API unreachable");
                }
            }
        }.execute();
    }

    // ============ PROJECTS ============

    private void loadProjectsAsync() {
        new SwingWorker<List<ProjectsService.ProjectItem>, Void>() {
            @Override
            protected List<ProjectsService.ProjectItem> doInBackground() throws Exception {
                return projectsService.fetchProjects();
            }

            @Override
            protected void done() {
                try {
                    List<ProjectsService.ProjectItem> list = get();
                    String current = ctx.getProject();

                    projectCombo.removeAllItems();
                    for (ProjectsService.ProjectItem p : list) {
                        projectCombo.addItem(p.key());
                    }
                    // восстановить текущий выбор, если он есть в списке
                    if (current != null && !current.isBlank()) {
                        projectCombo.getEditor().setItem(current);
                    } else if (projectCombo.getItemCount() > 0) {
                        String first = projectCombo.getItemAt(0);
                        projectCombo.setSelectedItem(first);
                        ctx.setProject(first);
                    }
                } catch (Exception ex) {
                    UiHelpers.showToast(TopBar.this,
                            "Не удалось загрузить проекты: " + ex.getMessage(), true);
                }
            }
        }.execute();
    }

    /** Ленивая подгрузка проектов — только если список ещё пуст. */
    private void loadProjectsAsyncIfNeeded() {
        if (projectCombo.getItemCount() == 0) {
            loadProjectsAsync();
        }
    }
}