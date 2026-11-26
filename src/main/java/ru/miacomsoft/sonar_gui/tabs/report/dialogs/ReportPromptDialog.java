package ru.miacomsoft.sonar_gui.tabs.report.dialogs;

import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.IssueService;
import ru.miacomsoft.sonar_gui.common.services.PromptService;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

public class ReportPromptDialog extends JDialog {

    private final JTextArea promptArea = new JTextArea(20, 80);
    private final JLabel statusLbl;

    public ReportPromptDialog(GuiContext ctx, IssueService.IssueDto issue) {
        super((Frame) null, "Промпт для LLM (Report)", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(900, 620);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JLabel header = new JLabel(UiHelpers.wrapHtml(
                "Промпт для " + issue.key() + " — " + issue.message()));
        header.setForeground(GuiStyle.ACCENT);
        header.setFont(GuiStyle.FONT_TITLE);
        root.add(header, BorderLayout.NORTH);

        promptArea.setEditable(false);
        promptArea.setFont(GuiStyle.FONT_MONO);
        promptArea.setBackground(GuiStyle.BG2);
        promptArea.setForeground(GuiStyle.TEXT);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(promptArea);
        scroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        root.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        statusLbl = UiHelpers.dimLabel("Загрузка промпта…");
        footer.add(statusLbl);

        JButton copyBtn = UiHelpers.primaryButton("📋 Копировать");
        copyBtn.addActionListener(e -> {
            String text = promptArea.getText();
            if (text != null && !text.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(text), null);
                UiHelpers.showToast(this, "Промпт скопирован", false);
            }
        });
        footer.add(copyBtn);

        JButton closeBtn = UiHelpers.secondaryButton("Закрыть");
        closeBtn.addActionListener(e -> dispose());
        footer.add(closeBtn);

        root.add(footer, BorderLayout.SOUTH);

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return new PromptService(ctx).buildForIssue(
                        ctx.getProject(), "status-comment",
                        issue.key(), "accept", null);
            }
            @Override protected void done() {
                try {
                    String prompt = get();
                    promptArea.setText(prompt == null ? "" : prompt);
                    promptArea.setCaretPosition(0);
                    statusLbl.setText("Готово (" + (prompt == null ? 0 : prompt.length()) + " символов)");
                } catch (Exception e) {
                    statusLbl.setText("Ошибка: " + e.getMessage());
                }
            }
        }.execute();
    }
}