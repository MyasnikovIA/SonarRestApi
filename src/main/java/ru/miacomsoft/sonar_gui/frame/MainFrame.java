package ru.miacomsoft.sonar_gui.frame;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.Reloadable;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.tabs.perfile.PerFilePanel;
import ru.miacomsoft.sonar_gui.tabs.report.ReportPanel;

import javax.swing.*;
import java.awt.*;

public class MainFrame extends JFrame {

    private final GuiContext ctx;
    private final TopBar topBar;
    private final JTabbedPane tabs;
    private final JLabel statusBarText;

    private final PerFilePanel perFilePanel;
    private final ReportPanel  reportPanel;

    public MainFrame(GuiContext ctx) {
        super("Sonar GUI — Web Interface Clone");
        this.ctx = ctx;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 900);
        setMinimumSize(new Dimension(1000, 600));
        setLocationRelativeTo(null);

        getContentPane().setBackground(GuiStyle.BG);
        setLayout(new BorderLayout());

        setJMenuBar(buildMenuBar());

        topBar = new TopBar(ctx);
        topBar.setOnRefresh(c -> reloadActiveTab());
        add(topBar, BorderLayout.NORTH);

        tabs = new JTabbedPane();
        tabs.setBackground(GuiStyle.BG2);
        tabs.setForeground(GuiStyle.TEXT);
        tabs.setFont(GuiStyle.FONT_BOLD);

        perFilePanel = new PerFilePanel(ctx);
        reportPanel  = new ReportPanel(ctx);

        tabs.addTab("  Отчет по файлам (Per-File)  ", perFilePanel);
        tabs.addTab("  Отчет (Jira/MD/TXT)  ",       reportPanel);

        add(tabs, BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(GuiStyle.BG2);
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, GuiStyle.BORDER));
        statusBar.setPreferredSize(new Dimension(0, 26));

        statusBarText = new JLabel("  Готов к работе");
        statusBarText.setForeground(GuiStyle.TEXT_DIM);
        statusBarText.setFont(GuiStyle.FONT_BASE);
        statusBar.add(statusBarText, BorderLayout.WEST);

        add(statusBar, BorderLayout.SOUTH);

        ctx.onFiltersChanged(c -> setStatusText(
                "sev=" + c.getSeverity() + " · qual=" + c.getQuality()
                        + " · hideProcessed=" + c.isHideProcessed()));
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("Файл");
        file.add(menuItem("Настройки…", this::openSettings));
        file.addSeparator();
        file.add(menuItem("Перезагрузить сессию", this::reloadSession));
        file.addSeparator();
        file.add(menuItem("Выход", () -> System.exit(0)));
        bar.add(file);

        JMenu view = new JMenu("Вид");
        view.add(menuItem("Обновить активную вкладку", this::reloadActiveTab));
        bar.add(view);

        JMenu help = new JMenu("Справка");
        help.add(menuItem("О программе", this::showAbout));
        bar.add(help);

        return bar;
    }

    private JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    /**
     * Единое окно настроек: SonarQube / Jira / CSV / Ollama / Промпты.
     * После сохранения перезагружает активную вкладку.
     */
    private void openSettings() {
        new SettingsDialog(ctx, () -> {
            setStatusText("Настройки применены");
            reloadActiveTab();
        }).setVisible(true);
    }

    private void reloadSession() {
        try {
            ctx.state().reload();
            ctx.ollamaConfig().reload();
            ru.miacomsoft.sonar_gui.backend.jira.JiraConfig.getInstance().reload();
            UiHelpers.showToast(this, "Конфигурация перезагружена", false);
            reloadActiveTab();
        } catch (Exception e) {
            UiHelpers.showError(this, "Ошибка перезагрузки: " + e.getMessage());
        }
    }

    private void reloadActiveTab() {
        setStatusText("Обновление…");
        Component c = tabs.getSelectedComponent();
        if (c instanceof Reloadable r) r.reload();
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "Sonar GUI 1.0\nАвтономный клиент SonarQube.\n"
                        + "session: " + ctx.sessionFile() + "\n"
                        + "prompts: " + ctx.promptsDir(),
                "О программе", JOptionPane.INFORMATION_MESSAGE);
    }

    public void setStatusText(String text) {
        statusBarText.setText("  " + (text == null ? "" : text));
    }

    public GuiContext getContext()    { return ctx; }
    public TopBar     getTopBar()     { return topBar; }
    public JTabbedPane getTabs()      { return tabs; }
}