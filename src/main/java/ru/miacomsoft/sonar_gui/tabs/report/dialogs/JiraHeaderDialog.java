package ru.miacomsoft.sonar_gui.tabs.report.dialogs;

import ru.miacomsoft.sonar_gui.backend.jira.JiraClient;
import ru.miacomsoft.sonar_gui.backend.jira.SessionLoader;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Диалог для ввода заголовков Jira, если сессия не работает.
 */
public class JiraHeaderDialog extends JDialog {

    private final GuiContext ctx;
    private final JTextArea headersArea = new JTextArea(15, 80);
    private final JLabel statusLbl = UiHelpers.dimLabel("");
    private boolean isSavedAndValid = false;

    public JiraHeaderDialog(GuiContext ctx) {
        super((Frame) null, "Требуется авторизация в Jira", true);
        this.ctx = ctx;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JLabel header = new JLabel(UiHelpers.wrapHtml(
                "<html>Сессия Jira недействительна.<br>" +
                        "Пожалуйста, откройте Jira в браузере, нажмите F12 (DevTools) → Network,<br>" +
                        "обновите страницу, выберите любой запрос к Jira, нажмите правой кнопкой мыши<br>" +
                        "и выберите <b>Copy request headers</b>. Вставьте скопированный текст ниже.</html>"));
        header.setForeground(GuiStyle.ACCENT);
        header.setFont(GuiStyle.FONT_TITLE);
        root.add(header, BorderLayout.NORTH);

        headersArea.setFont(GuiStyle.FONT_MONO);
        headersArea.setBackground(GuiStyle.BG2);
        headersArea.setForeground(GuiStyle.TEXT);
        headersArea.setLineWrap(true);
        JScrollPane scroll = new JScrollPane(headersArea);
        scroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        root.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.add(statusLbl);

        JButton cancelBtn = UiHelpers.secondaryButton("Отмена");
        cancelBtn.addActionListener(e -> dispose());
        footer.add(cancelBtn);

        JButton saveBtn = UiHelpers.primaryButton("Проверить и сохранить");
        saveBtn.addActionListener(e -> validateAndSave());
        footer.add(saveBtn);

        root.add(footer, BorderLayout.SOUTH);

        // Заполняем текущим содержимым
        try {
            Path file = Path.of("session.txt");
            if (Files.exists(file)) {
                headersArea.setText(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void validateAndSave() {
        String raw = headersArea.getText();
        if (raw == null || raw.isBlank()) {
            statusLbl.setText("Вставьте заголовки");
            statusLbl.setForeground(GuiStyle.ERR);
            return;
        }

        statusLbl.setText("Проверка...");
        statusLbl.setForeground(GuiStyle.TEXT_DIM);

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                Path file = Path.of("session.txt").toAbsolutePath();
                Files.writeString(file, raw, StandardCharsets.UTF_8);

                // Пытаемся загрузить сессию
                SessionLoader.SessionInfo info = SessionLoader.loadDefault();

                // Пытаемся достучаться до Jira
                JiraClient client = new JiraClient(info.baseUrl(), info.cookieHeader());
                client.get("/rest/api/2/myself"); // Если ошибка - выбросит исключение
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    isSavedAndValid = true;
                    UiHelpers.showToast(JiraHeaderDialog.this, "Успешно авторизован!", false);
                    dispose();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    statusLbl.setText("Ошибка: " + cause.getMessage());
                    statusLbl.setForeground(GuiStyle.ERR);

                    int res = JOptionPane.showConfirmDialog(JiraHeaderDialog.this,
                            "Не удалось авторизоваться.\n" + cause.getMessage() + "\n\nПопробовать другие заголовки?",
                            "Ошибка авторизации", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);

                    if (res != JOptionPane.YES_OPTION) {
                        dispose();
                    }
                }
            }
        }.execute();
    }

    public boolean isSavedAndValid() {
        return isSavedAndValid;
    }
}