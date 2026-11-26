package ru.miacomsoft.sonar_gui.frame;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.Reloadable;
import ru.miacomsoft.sonar_gui.tabs.perfile.PerFilePanel;
import ru.miacomsoft.sonar_gui.tabs.report.ReportPanel;

import javax.swing.*;
import java.awt.*;

/**
 * Главное окно GUI-клиента.
 * Подключает обе вкладки: Per-File и Report.
 */
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

    private void reloadActiveTab() {
        setStatusText("Обновление…");
        Component c = tabs.getSelectedComponent();
        if (c instanceof Reloadable r) {
            r.reload();
        }
    }

    public void setStatusText(String text) {
        statusBarText.setText("  " + (text == null ? "" : text));
    }

    public GuiContext getContext()    { return ctx; }
    public TopBar     getTopBar()     { return topBar; }
    public JTabbedPane getTabs()      { return tabs; }
}