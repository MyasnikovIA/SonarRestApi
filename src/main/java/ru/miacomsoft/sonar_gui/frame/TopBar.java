package ru.miacomsoft.sonar_gui.frame;

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
 * Верхняя панель GUI-клиента.
 *
 * Работает напрямую с ServerState:
 *   - health проверяется через HealthService → SonarClient /api/system/ping;
 *   - список проектов грузится через ProjectsService → SonarClient /api/components/search.
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
        this.healthService = new HealthService(ctx);
        this.projectsService = new ProjectsService(ctx);

        setLayout(new BorderLayout(12, 0));
        setBackground(GuiStyle.BG2);
        setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);

        JLabel brand = new JLabel("◈ Sonar GUI");
        brand.setForeground(GuiStyle.ACCENT);
        brand.setFont(GuiStyle.FONT_TITLE);
        left.add(brand);
        left.add(Box.createHorizontalStrut(20));

        left.add(UiHelpers.dimLabel("project:"));
        projectCombo = new JComboBox<>();
        projectCombo.setEditable(true);
        projectCombo.setPreferredSize(new Dimension(340, 26));
        projectCombo.setBackground(GuiStyle.BG);
        projectCombo.setForeground(GuiStyle.TEXT);
        projectCombo.setFont(GuiStyle.FONT_MONO);
        left.add(projectCombo);

        JButton loadProjects = UiHelpers.secondaryButton("↻");
        loadProjects.setToolTipText("Загрузить список проектов из SonarQube");
        loadProjects.addActionListener(e -> loadProjectsAsync());
        left.add(loadProjects);

        add(left, BorderLayout.WEST);

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

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        statusDot = new JLabel("●");
        statusDot.setForeground(GuiStyle.TEXT_DIM);
        statusDot.setFont(GuiStyle.FONT_BOLD);
        right.add(statusDot);

        statusText = new JLabel("Инициализация…");
        statusText.setForeground(GuiStyle.TEXT_DIM);
        statusText.setFont(GuiStyle.FONT_BASE);
        right.add(statusText);

        refreshBtn = UiHelpers.primaryButton("↻ Обновить");
        refreshBtn.addActionListener(e -> {
            if (onRefresh != null) onRefresh.accept(ctx);
        });
        right.add(refreshBtn);

        add(right, BorderLayout.EAST);

        // события
        projectCombo.addActionListener(e -> {
            Object v = projectCombo.getEditor().getItem();
            String s = v == null ? "" : v.toString().trim();
            ctx.setProject(s);
        });
        sevCombo.addActionListener(e -> {
            Object v = sevCombo.getSelectedItem();
            ctx.setSeverity(v == null ? "MEDIUM" : v.toString());
        });
        qualCombo.addActionListener(e -> {
            Object v = qualCombo.getSelectedItem();
            ctx.setQuality(v == null ? "MAINTAINABILITY" : v.toString());
        });

        // health
        healthTimer = new Timer(5000, e -> checkHealthAsync());
        healthTimer.start();
        checkHealthAsync();

        // при смене проекта — синхронизируем поле
        ctx.onProjectChanged(c -> SwingUtilities.invokeLater(() -> {
            Object cur = projectCombo.getEditor().getItem();
            String s = cur == null ? "" : cur.toString().trim();
            if (!s.equals(c.getProject())) {
                projectCombo.getEditor().setItem(c.getProject());
            }
        }));
    }

    public void setOnRefresh(Consumer<GuiContext> callback) {
        this.onRefresh = callback;
    }

    // ============ HEALTH ============

    private void checkHealthAsync() {
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                try { return healthService.isReady(); }
                catch (Exception e) { return false; }
            }
            @Override protected void done() {
                try {
                    boolean ready = get();
                    if (ready) {
                        statusDot.setForeground(GuiStyle.OK);
                        statusText.setText("Готов к работе");
                    } else if (!ctx.isSessionReady()) {
                        statusDot.setForeground(GuiStyle.ERR);
                        statusText.setText("session.properties не заполнен");
                    } else {
                        statusDot.setForeground(GuiStyle.ERR);
                        statusText.setText("SonarQube недоступен");
                    }
                } catch (Exception ex) {
                    statusDot.setForeground(GuiStyle.ERR);
                    statusText.setText("Ошибка проверки");
                }
            }
        }.execute();
    }

    // ============ PROJECTS ============

    private void loadProjectsAsync() {
        new SwingWorker<List<ProjectsService.ProjectItem>, Void>() {
            @Override protected List<ProjectsService.ProjectItem> doInBackground() throws Exception {
                return projectsService.fetchProjects();
            }
            @Override protected void done() {
                try {
                    List<ProjectsService.ProjectItem> list = get();
                    String current = ctx.getProject();
                    projectCombo.removeAllItems();
                    for (ProjectsService.ProjectItem p : list) projectCombo.addItem(p.key());
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
}