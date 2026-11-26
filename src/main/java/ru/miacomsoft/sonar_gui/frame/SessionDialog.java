package ru.miacomsoft.sonar_gui.frame;

import ru.miacomsoft.sonar_gui.backend.SessionManager;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Модальный диалог редактирования session.properties.
 *
 * Позволяет заполнить sonar.url / sonar.user / sonar.auth / sonar.token
 * (или sonar.cookie) и сохранить прямо в файл, без ручного редактирования.
 *
 * После сохранения вызывает state.update(raw) и refresh().
 */
public class SessionDialog extends JDialog {

    private final GuiContext ctx;
    private final Runnable onSaved;

    private final JTextField urlField   = UiHelpers.darkField("");
    private final JTextField userField  = UiHelpers.darkField("");
    private final JComboBox<String> authCombo = UiHelpers.darkCombo(
            new String[]{"bearer", "basic", "cookie"}, "bearer");
    private final JPasswordField tokenField = new JPasswordField();
    private final JTextArea cookieArea = new JTextArea(3, 40);
    private final JLabel tokenStatusLbl = UiHelpers.dimLabel("");
    private final JLabel cookieStatusLbl = UiHelpers.dimLabel("");
    private final JLabel statusLbl = UiHelpers.dimLabel("");

    public SessionDialog(GuiContext ctx, Runnable onSaved) {
        super((Frame) null, "Настройки SonarQube", true);
        this.ctx = ctx;
        this.onSaved = onSaved;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(680, 560);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        JLabel header = new JLabel("Подключение к SonarQube");
        header.setForeground(GuiStyle.ACCENT);
        header.setFont(GuiStyle.FONT_TITLE);
        root.add(header, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);

        // URL
        form.add(row("sonar.url", urlField));
        form.add(Box.createVerticalStrut(8));

        // user
        form.add(row("sonar.user", userField));
        form.add(Box.createVerticalStrut(8));

        // auth
        JPanel authRow = new JPanel(new BorderLayout(8, 0));
        authRow.setOpaque(false);
        authRow.add(UiHelpers.dimLabel("sonar.auth"), BorderLayout.WEST);
        authCombo.setPreferredSize(new Dimension(160, 26));
        authRow.add(authCombo, BorderLayout.CENTER);
        authRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        form.add(authRow);
        form.add(Box.createVerticalStrut(8));

        // token
        JPanel tokenRow = new JPanel(new BorderLayout(8, 0));
        tokenRow.setOpaque(false);
        tokenRow.add(UiHelpers.dimLabel("sonar.token"), BorderLayout.WEST);
        tokenField.setBackground(GuiStyle.BG);
        tokenField.setForeground(GuiStyle.TEXT);
        tokenField.setCaretColor(GuiStyle.TEXT);
        tokenField.setFont(GuiStyle.FONT_MONO);
        tokenField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GuiStyle.BORDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        tokenRow.add(tokenField, BorderLayout.CENTER);
        tokenRow.add(tokenStatusLbl, BorderLayout.EAST);
        tokenRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        form.add(tokenRow);
        form.add(Box.createVerticalStrut(8));

        // cookie
        JPanel cookieRow = new JPanel(new BorderLayout(8, 0));
        cookieRow.setOpaque(false);
        cookieRow.add(UiHelpers.dimLabel("sonar.cookie"), BorderLayout.WEST);
        cookieArea.setFont(GuiStyle.FONT_MONO);
        cookieArea.setBackground(GuiStyle.BG2);
        cookieArea.setForeground(GuiStyle.TEXT);
        cookieArea.setLineWrap(true);
        JScrollPane cookieScroll = new JScrollPane(cookieArea);
        cookieScroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        cookieScroll.setPreferredSize(new Dimension(0, 70));
        cookieRow.add(cookieScroll, BorderLayout.CENTER);
        cookieRow.add(cookieStatusLbl, BorderLayout.EAST);
        form.add(cookieRow);

        root.add(form, BorderLayout.CENTER);

        // Footer
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.add(statusLbl);

        JButton reloadBtn = UiHelpers.secondaryButton("↻ Перезагрузить с диска");
        reloadBtn.addActionListener(e -> loadFromFile());
        footer.add(reloadBtn);

        JButton cancelBtn = UiHelpers.secondaryButton("Отмена");
        cancelBtn.addActionListener(e -> dispose());
        footer.add(cancelBtn);

        JButton saveBtn = UiHelpers.primaryButton("💾 Сохранить");
        saveBtn.addActionListener(e -> saveToFile());
        footer.add(saveBtn);

        root.add(footer, BorderLayout.SOUTH);

        // Взаимоисключение token/cookie
        authCombo.addActionListener(e -> refreshAuthVisibility());
        refreshAuthVisibility();
        loadFromFile();
    }

    private JPanel row(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.add(UiHelpers.dimLabel(label), BorderLayout.WEST);
        if (field instanceof JTextField tf) {
            tf.setPreferredSize(new Dimension(0, 26));
        }
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        return p;
    }

    private void refreshAuthVisibility() {
        String auth = (String) authCombo.getSelectedItem();
        boolean isCookie = "cookie".equalsIgnoreCase(auth);
        tokenField.setEnabled(!isCookie);
        tokenField.setVisible(!isCookie);
        cookieArea.setEnabled(isCookie);
        cookieArea.setVisible(isCookie);
    }

    private void loadFromFile() {
        Path file = ctx.sessionFile();
        if (file == null || !Files.exists(file)) {
            statusLbl.setText("файл отсутствует — будет создан при сохранении");
            return;
        }
        try {
            Properties p = new Properties();
            try (var in = Files.newInputStream(file)) { p.load(in); }

            urlField.setText(p.getProperty("sonar.url", ""));
            userField.setText(p.getProperty("sonar.user", ""));
            String auth = p.getProperty("sonar.auth", "bearer");
            authCombo.setSelectedItem(
                    "basic".equalsIgnoreCase(auth) ? "basic" :
                            "cookie".equalsIgnoreCase(auth) ? "cookie" : "bearer");

            tokenField.setText("");
            tokenStatusLbl.setText(p.getProperty("sonar.token", "").isBlank()
                    ? "— не задан" : "✓ установлен");

            cookieArea.setText("");
            cookieStatusLbl.setText(p.getProperty("sonar.cookie", "").isBlank()
                    ? "— не задан" : "✓ установлен");

            statusLbl.setText("загружено из " + file.getFileName());
            refreshAuthVisibility();
        } catch (Exception e) {
            statusLbl.setText("ошибка чтения: " + e.getMessage());
        }
    }

    private void saveToFile() {
        Path file = ctx.sessionFile();
        if (file == null) {
            UiHelpers.showError(this, "Путь session.properties не задан");
            return;
        }

        String url   = urlField.getText().trim();
        String user  = userField.getText().trim();
        String auth  = (String) authCombo.getSelectedItem();
        String token = new String(tokenField.getPassword()).trim();
        String cookie = cookieArea.getText().trim();

        if (url.isEmpty()) {
            UiHelpers.showError(this, "Укажите sonar.url");
            urlField.requestFocus();
            return;
        }
        if (!url.matches("^https?://.*")) url = "https://" + url;

        if ("cookie".equalsIgnoreCase(auth) && cookie.isEmpty()) {
            UiHelpers.showError(this, "Для auth=cookie нужен sonar.cookie");
            cookieArea.requestFocus();
            return;
        }
        if (!"cookie".equalsIgnoreCase(auth) && token.isEmpty()) {
            String existing = "";
            try {
                if (Files.exists(file)) {
                    Properties p = new Properties();
                    try (var in = Files.newInputStream(file)) { p.load(in); }
                    existing = p.getProperty("sonar.token", "");
                }
            } catch (Exception ignored) {}
            if (existing.isBlank()) {
                UiHelpers.showError(this, "Введите sonar.token");
                tokenField.requestFocus();
                return;
            }
            token = existing;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# SonarQube connection\n");
        sb.append("sonar.url=").append(url).append('\n');
        sb.append("sonar.user=").append(user).append('\n');
        sb.append("sonar.auth=").append(auth).append('\n');
        if ("cookie".equalsIgnoreCase(auth)) {
            sb.append("sonar.cookie=").append(cookie.replace("\n", " ")).append('\n');
        } else {
            sb.append("sonar.token=").append(token).append('\n');
        }

        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, sb.toString());

            ctx.state().update(sb.toString());
            ctx.state().reload();
            statusLbl.setText("✓ сохранено");
            UiHelpers.showToast(this, "session.properties сохранён и применён", false);

            if (onSaved != null) onSaved.run();
            dispose();
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            UiHelpers.showError(this, "Ошибка сохранения: " + e.getMessage());
        }
    }
}