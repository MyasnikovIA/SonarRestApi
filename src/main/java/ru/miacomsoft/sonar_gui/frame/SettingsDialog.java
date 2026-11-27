package ru.miacomsoft.sonar_gui.frame;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.backend.jira.JiraConfig;
import ru.miacomsoft.sonar_gui.backend.jira.SessionLoader;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaClient;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaConfig;
import ru.miacomsoft.sonar_gui.common.DarkTitleBarUtil;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class SettingsDialog extends JDialog {

    private final GuiContext ctx;
    private final Runnable onSaved;

    // ========== SonarQube ==========
    private final JTextField sqUrlField = UiHelpers.darkField("");
    private final JTextField sqUserField = UiHelpers.darkField("");
    private final JComboBox<String> sqAuthCombo = UiHelpers.darkCombo(
            new String[]{"bearer", "basic", "cookie"}, "bearer");
    private final JTextField sqTokenField = UiHelpers.darkField("");
    private final JTextArea sqCookieArea = new JTextArea(3, 40);
    private final JLabel sqTokenStatusLbl = UiHelpers.dimLabel("");
    private final JLabel sqCookieStatusLbl = UiHelpers.dimLabel("");

    // ========== Jira ==========
    private final JTextArea jiraSessionArea = new JTextArea(20, 100);
    private final JTextField jiraUrlField       = UiHelpers.darkField("");
    private final JTextField jiraIssueTypeField = UiHelpers.darkField("");
    private final JTextField jiraProjectField   = UiHelpers.darkField("");
    private final JTextField jiraTeamField      = UiHelpers.darkField("");
    private final JTextField jiraAssigneeField  = UiHelpers.darkField("");
    private final JTextField jiraDefaultEpicField = UiHelpers.darkField(""); // НОВОЕ ПОЛЕ
    private final JLabel     jiraStatusLbl      = UiHelpers.dimLabel("не загружено");
    private final JLabel     jiraSessionFileLbl = UiHelpers.dimLabel("session.txt");

    // ========== Jira Epics ==========
    private final Map<String, JTextField> epicFields = new LinkedHashMap<>();
    private static final String[] QUALS = {"SECURITY", "RELIABILITY", "MAINTAINABILITY", "SECURITY_HOTSPOT"};
    private static final String[] SEVS  = {"BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"};

    // ========== CSV (Mapping) ==========
    private final JLabel teamCsvStatusLbl = UiHelpers.dimLabel("team_mapping.csv не загружен");
    private final JLabel productCsvStatusLbl = UiHelpers.dimLabel("product_mapping.csv не загружен");

    // ========== Ollama ==========
    private final JTextField olUrlField = UiHelpers.darkField("");
    private final JComboBox<String> olModelCombo = new JComboBox<>();
    private final JSpinner olTimeoutSpinner = new JSpinner(new SpinnerNumberModel(300, 10, 3600, 10));
    private final JSpinner olTempSpinner = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.05));
    private final JLabel olStatusLbl = UiHelpers.dimLabel("");
    private final JButton olTestBtn;

    // ========== Prompts ==========
    private final DefaultListModel<String> promptsListModel = new DefaultListModel<>();
    private final JList<String> promptsList = new JList<>(promptsListModel);
    private final JTextArea promptEditor = new JTextArea(20, 80);
    private final JLabel promptStatusLbl = UiHelpers.dimLabel("");

    public SettingsDialog(GuiContext ctx, Runnable onSaved) {
        super((Frame) null, "Настройки", true);
        this.ctx = ctx;
        this.onSaved = onSaved;
        this.olTestBtn = UiHelpers.secondaryButton("Проверить");

        for (String q : QUALS) {
            for (String s : SEVS) {
                epicFields.put(q + "." + s, UiHelpers.darkField(""));
            }
        }

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1050, 920);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(GuiStyle.BG);
        setContentPane(root);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(GuiStyle.BG2);
        tabs.setForeground(GuiStyle.TEXT);
        tabs.setFont(GuiStyle.FONT_BOLD);

        tabs.addTab("  SonarQube  ",      buildSonarQubePanel());
        tabs.addTab("  Jira  ",           buildJiraPanel());
        tabs.addTab("  Привязка (CSV)  ", buildCsvPanel());
        tabs.addTab("  Ollama  ",         buildOllamaPanel());
        tabs.addTab("  Промпты  ",        buildPromptsPanel());

        root.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.setBackground(GuiStyle.BG2);

        JButton saveBtn = UiHelpers.primaryButton("💾 Сохранить всё и применить");
        saveBtn.addActionListener(e -> saveAll());
        footer.add(saveBtn);

        JButton cancelBtn = UiHelpers.secondaryButton("Отмена");
        cancelBtn.addActionListener(e -> dispose());
        footer.add(cancelBtn);

        root.add(footer, BorderLayout.SOUTH);

        loadAll();
        DarkTitleBarUtil.apply(this);
    }

    // ================================================================
    // 1. SonarQube
    // ================================================================
    private JPanel buildSonarQubePanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(GuiStyle.BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        p.add(row("sonar.url", sqUrlField));
        p.add(Box.createVerticalStrut(8));
        p.add(row("sonar.user", sqUserField));
        p.add(Box.createVerticalStrut(8));

        JPanel authRow = new JPanel(new BorderLayout(8, 0));
        authRow.setOpaque(false);
        authRow.add(UiHelpers.dimLabel("sonar.auth"), BorderLayout.WEST);
        sqAuthCombo.setPreferredSize(new Dimension(160, 26));
        authRow.add(sqAuthCombo, BorderLayout.CENTER);
        authRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        p.add(authRow);
        p.add(Box.createVerticalStrut(8));

        JPanel tokenRow = new JPanel(new BorderLayout(8, 0));
        tokenRow.setOpaque(false);
        tokenRow.add(UiHelpers.dimLabel("sonar.token"), BorderLayout.WEST);
        sqTokenField.setBackground(GuiStyle.BG);
        sqTokenField.setForeground(GuiStyle.TEXT);
        sqTokenField.setCaretColor(GuiStyle.TEXT);
        sqTokenField.setFont(GuiStyle.FONT_MONO);
        sqTokenField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GuiStyle.BORDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        tokenRow.add(sqTokenField, BorderLayout.CENTER);
        tokenRow.add(sqTokenStatusLbl, BorderLayout.EAST);
        tokenRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        p.add(tokenRow);
        p.add(Box.createVerticalStrut(8));

        JPanel cookieRow = new JPanel(new BorderLayout(8, 0));
        cookieRow.setOpaque(false);
        cookieRow.add(UiHelpers.dimLabel("sonar.cookie"), BorderLayout.WEST);
        sqCookieArea.setFont(GuiStyle.FONT_MONO);
        sqCookieArea.setBackground(GuiStyle.BG2);
        sqCookieArea.setForeground(GuiStyle.TEXT);
        sqCookieArea.setLineWrap(true);
        JScrollPane cookieScroll = new JScrollPane(sqCookieArea);
        cookieScroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        cookieScroll.setPreferredSize(new Dimension(0, 70));
        cookieRow.add(cookieScroll, BorderLayout.CENTER);
        cookieRow.add(sqCookieStatusLbl, BorderLayout.EAST);
        p.add(cookieRow);

        sqAuthCombo.addActionListener(e -> {
            String auth = (String) sqAuthCombo.getSelectedItem();
            boolean isCookie = "cookie".equalsIgnoreCase(auth);
            sqTokenField.setEnabled(!isCookie);
            sqTokenField.setVisible(!isCookie);
            sqCookieArea.setEnabled(isCookie);
            sqCookieArea.setVisible(isCookie);
        });

        return p;
    }

    // ================================================================
    // 2. Jira (включая Эпики)
    // ================================================================
    private JPanel buildJiraPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(GuiStyle.BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel desc = new JLabel(UiHelpers.wrapHtml(
                "<html>Вставьте ниже текст запроса из браузера целиком " +
                        "(DevTools → Network → правый клик по запросу → <b>Copy request headers</b>).<br>" +
                        "Содержимое сохраняется в <b>session.txt</b>.</html>"));
        desc.setForeground(GuiStyle.TEXT);
        p.add(desc, BorderLayout.NORTH);

        jiraSessionArea.setFont(GuiStyle.FONT_MONO);
        jiraSessionArea.setBackground(GuiStyle.BG2);
        jiraSessionArea.setForeground(GuiStyle.TEXT);
        jiraSessionArea.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(jiraSessionArea);
        scroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        scroll.setPreferredSize(new Dimension(0, 120));
        p.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setOpaque(false);

        bottom.add(row("jira.url", jiraUrlField));
        bottom.add(Box.createVerticalStrut(6));
        bottom.add(row("jira.issueType", jiraIssueTypeField));
        bottom.add(Box.createVerticalStrut(6));
        bottom.add(row("jira.defaultProject", jiraProjectField));
        bottom.add(Box.createVerticalStrut(6));
        bottom.add(row("jira.team", jiraTeamField));
        bottom.add(Box.createVerticalStrut(6));
        bottom.add(row("jira.assignee", jiraAssigneeField));
        bottom.add(Box.createVerticalStrut(6));
        // ДОБАВЛЕНО ПОЛЕ ЭПИКА ПО УМОЛЧАНИЮ
        bottom.add(row("jira.epic.default (Эпик по умолчанию)", jiraDefaultEpicField));
        bottom.add(Box.createVerticalStrut(10));

        JPanel epicsGrid = new JPanel(new GridLayout(0, 2, 8, 4));
        epicsGrid.setOpaque(false);
        epicsGrid.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(GuiStyle.BORDER),
                "Эпики (QUAL:SEV)", 0, 0, GuiStyle.FONT_BOLD, GuiStyle.TEXT_DIM));

        for (String q : QUALS) {
            for (String s : SEVS) {
                String key = q + "." + s;
                epicsGrid.add(UiHelpers.dimLabel(mapQual(q) + ", " + s));
                epicsGrid.add(epicFields.get(key));
            }
        }

        JScrollPane epicsScroll = new JScrollPane(epicsGrid);
        epicsScroll.setBorder(BorderFactory.createEmptyBorder());
        epicsScroll.setPreferredSize(new Dimension(0, 220));
        epicsScroll.getViewport().setOpaque(false);
        bottom.add(epicsScroll);
        bottom.add(Box.createVerticalStrut(10));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btns.setOpaque(false);

        JButton loadFileBtn = UiHelpers.secondaryButton("📂 Загрузить session.txt");
        loadFileBtn.addActionListener(e -> loadJiraSessionFromFile());
        btns.add(loadFileBtn);

        JButton checkBtn = UiHelpers.secondaryButton("🔍 Проверить");
        checkBtn.addActionListener(e -> updateJiraStatus());
        btns.add(checkBtn);

        JButton saveNowBtn = UiHelpers.primaryButton("💾 Сохранить session.txt");
        saveNowBtn.addActionListener(e -> saveJiraSessionToFile());
        btns.add(saveNowBtn);

        btns.add(jiraStatusLbl);
        btns.add(jiraSessionFileLbl);
        bottom.add(btns);

        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    private String mapQual(String q) {
        return switch (q) {
            case "SECURITY" -> "Безопасность";
            case "RELIABILITY" -> "Надежность";
            case "MAINTAINABILITY" -> "Сопровождаемость";
            case "SECURITY_HOTSPOT" -> "Уязвимости";
            default -> q;
        };
    }

    private void loadJiraSessionFromFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите session.txt");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String content = Files.readString(
                        chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8);
                jiraSessionArea.setText(content);
                jiraSessionArea.setCaretPosition(0);
                updateJiraStatus();
            } catch (Exception e) {
                UiHelpers.showError(this, "Ошибка чтения: " + e.getMessage());
            }
        }
    }

    private void saveJiraSessionToFile() {
        try {
            Path target = Path.of("session.txt").toAbsolutePath();
            Files.writeString(target, jiraSessionArea.getText(), StandardCharsets.UTF_8);
            jiraStatusLbl.setText("✓ session.txt сохранён");
            jiraStatusLbl.setForeground(GuiStyle.OK);
            updateJiraStatus();
        } catch (Exception e) {
            UiHelpers.showError(this, "Ошибка сохранения session.txt: " + e.getMessage());
        }
    }

    private void updateJiraStatus() {
        try {
            Path target = Path.of("session.txt").toAbsolutePath();
            Files.writeString(target, jiraSessionArea.getText(), StandardCharsets.UTF_8);
            SessionLoader.SessionInfo info = SessionLoader.loadDefault();
            String u = info.username() == null ? "?" : info.username();
            String b = info.baseUrl() == null ? "?" : info.baseUrl();
            jiraStatusLbl.setText("✓ " + u + " @ " + b);
            jiraStatusLbl.setForeground(GuiStyle.OK);
        } catch (Exception e) {
            jiraStatusLbl.setText("⚠ " + e.getMessage());
            jiraStatusLbl.setForeground(GuiStyle.WARN);
        }
    }

    // ================================================================
    // 3. CSV (Team & Product Mapping)
    // ================================================================
    private JPanel buildCsvPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(GuiStyle.BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel descTeam = new JLabel(UiHelpers.wrapHtml(
                "<html><b>team_mapping.csv</b> — используется для привязки файлов к командам и модулям.</html>"));
        descTeam.setForeground(GuiStyle.TEXT);
        p.add(descTeam);
        p.add(Box.createVerticalStrut(8));

        JPanel rowTeam = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        rowTeam.setOpaque(false);
        JButton loadTeamBtn = UiHelpers.primaryButton("📂 Загрузить team_mapping.csv");
        loadTeamBtn.addActionListener(e -> loadTeamMappingCsv());
        rowTeam.add(loadTeamBtn);
        rowTeam.add(teamCsvStatusLbl);
        p.add(rowTeam);

        p.add(Box.createVerticalStrut(24));

        JLabel descProduct = new JLabel(UiHelpers.wrapHtml(
                "<html><b>product_mapping.csv</b> — связывает проекты SonarQube (Sonar_ID) с продуктами и командами-владельцами.<br>" +
                        "Используется для отчетов по продуктам.</html>"));
        descProduct.setForeground(GuiStyle.TEXT);
        p.add(descProduct);
        p.add(Box.createVerticalStrut(8));

        JPanel rowProduct = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        rowProduct.setOpaque(false);
        JButton loadProductBtn = UiHelpers.primaryButton("📂 Загрузить product_mapping.csv");
        loadProductBtn.addActionListener(e -> loadProductMappingCsv());
        rowProduct.add(loadProductBtn);
        rowProduct.add(productCsvStatusLbl);
        p.add(rowProduct);

        return p;
    }

    private void loadTeamMappingCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите team_mapping.csv");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Path target = Path.of("team_mapping.csv").toAbsolutePath();
                Files.copy(chooser.getSelectedFile().toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                teamCsvStatusLbl.setText("✓ Загружен");
                teamCsvStatusLbl.setForeground(GuiStyle.OK);
            } catch (Exception e) {
                UiHelpers.showError(this, "Ошибка загрузки team_mapping.csv: " + e.getMessage());
            }
        }
    }

    private void loadProductMappingCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите product_mapping.csv");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Path target = Path.of("product_mapping.csv").toAbsolutePath();
                Files.copy(chooser.getSelectedFile().toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                productCsvStatusLbl.setText("✓ Загружен");
                productCsvStatusLbl.setForeground(GuiStyle.OK);
            } catch (Exception e) {
                UiHelpers.showError(this, "Ошибка загрузки product_mapping.csv: " + e.getMessage());
            }
        }
    }

    // ================================================================
    // 4. Ollama
    // ================================================================
    private JPanel buildOllamaPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(GuiStyle.BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        p.add(row("url", olUrlField));
        p.add(Box.createVerticalStrut(8));

        JPanel modelRow = new JPanel(new BorderLayout(8, 0));
        modelRow.setOpaque(false);
        modelRow.add(UiHelpers.dimLabel("model"), BorderLayout.WEST);
        olModelCombo.setEditable(true);
        olModelCombo.setBackground(GuiStyle.BG);
        olModelCombo.setForeground(GuiStyle.TEXT);
        olModelCombo.setFont(GuiStyle.FONT_MONO);
        modelRow.add(olModelCombo, BorderLayout.CENTER);

        olTestBtn.addActionListener(e -> testAndLoadOllamaModels());
        modelRow.add(olTestBtn, BorderLayout.EAST);
        modelRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        p.add(modelRow);

        p.add(Box.createVerticalStrut(8));
        p.add(row("timeout (сек)", olTimeoutSpinner));
        p.add(Box.createVerticalStrut(8));
        p.add(row("temperature", olTempSpinner));

        p.add(Box.createVerticalStrut(16));
        p.add(olStatusLbl);

        return p;
    }

    private void testAndLoadOllamaModels() {
        String url = olUrlField.getText().trim();
        if (url.isEmpty()) {
            olStatusLbl.setText("укажите url");
            olStatusLbl.setForeground(GuiStyle.ERR);
            return;
        }
        olStatusLbl.setText("проверка…");
        olStatusLbl.setForeground(GuiStyle.TEXT_DIM);
        olTestBtn.setEnabled(false);

        new SwingWorker<JSONArray, Void>() {
            @Override protected JSONArray doInBackground() throws Exception {
                OllamaConfig tmp = new OllamaConfig(ctx.ollamaConfig().getConfigFile());
                tmp.setUrl(url);
                OllamaClient client = new OllamaClient(tmp);
                return client.listModels();
            }
            @Override protected void done() {
                olTestBtn.setEnabled(true);
                try {
                    JSONArray models = get();
                    String prev = (String) olModelCombo.getSelectedItem();
                    olModelCombo.removeAllItems();
                    for (int i = 0; i < models.length(); i++) {
                        JSONObject m = models.getJSONObject(i);
                        String name = m.optString("name", m.optString("model", ""));
                        if (!name.isBlank()) olModelCombo.addItem(name);
                    }
                    if (prev != null && !prev.isBlank()) olModelCombo.setSelectedItem(prev);
                    else if (olModelCombo.getItemCount() > 0) olModelCombo.setSelectedIndex(0);

                    olStatusLbl.setText("моделей: " + models.length());
                    olStatusLbl.setForeground(GuiStyle.OK);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    olStatusLbl.setText("ошибка: " + cause.getMessage());
                    olStatusLbl.setForeground(GuiStyle.ERR);
                }
            }
        }.execute();
    }

    // ================================================================
    // 5. Промпты
    // ================================================================
    private JPanel buildPromptsPanel() {
        JPanel p = new JPanel(new BorderLayout(10, 10));
        p.setBackground(GuiStyle.BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel leftPanel = new JPanel(new BorderLayout(0, 8));
        leftPanel.setOpaque(false);
        leftPanel.setPreferredSize(new Dimension(220, 0));
        leftPanel.add(UiHelpers.dimLabel("Доступные промпты:"), BorderLayout.NORTH);

        promptsList.setBackground(GuiStyle.BG2);
        promptsList.setForeground(GuiStyle.TEXT);
        promptsList.setFont(GuiStyle.FONT_MONO);
        JScrollPane listScroll = new JScrollPane(promptsList);
        listScroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        leftPanel.add(listScroll, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new GridLayout(2, 1, 0, 4));
        btnPanel.setOpaque(false);

        JButton createBtn = UiHelpers.secondaryButton("➕ Создать");
        createBtn.addActionListener(e -> createNewPrompt());
        btnPanel.add(createBtn);

        JButton deleteBtn = UiHelpers.dangerButton("🗑 Удалить");
        deleteBtn.addActionListener(e -> deleteCurrentPrompt());
        btnPanel.add(deleteBtn);

        leftPanel.add(btnPanel, BorderLayout.SOUTH);
        p.add(leftPanel, BorderLayout.WEST);

        JPanel rightPanel = new JPanel(new BorderLayout(0, 8));
        rightPanel.setOpaque(false);

        JPanel editorHeader = new JPanel(new BorderLayout());
        editorHeader.setOpaque(false);
        editorHeader.add(UiHelpers.dimLabel("Редактор шаблона:"), BorderLayout.WEST);
        editorHeader.add(promptStatusLbl, BorderLayout.EAST);
        rightPanel.add(editorHeader, BorderLayout.NORTH);

        promptEditor.setFont(GuiStyle.FONT_MONO);
        promptEditor.setBackground(GuiStyle.BG2);
        promptEditor.setForeground(GuiStyle.TEXT);
        promptEditor.setLineWrap(true);
        promptEditor.setWrapStyleWord(true);
        JScrollPane editorScroll = new JScrollPane(promptEditor);
        editorScroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        rightPanel.add(editorScroll, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bottomPanel.setOpaque(false);
        JButton savePromptBtn = UiHelpers.primaryButton("💾 Сохранить текущий промпт");
        savePromptBtn.addActionListener(e -> {
            saveCurrentPrompt();
            UiHelpers.showToast(this, "Промпт сохранён", false);
        });
        bottomPanel.add(savePromptBtn);
        rightPanel.add(bottomPanel, BorderLayout.SOUTH);

        p.add(rightPanel, BorderLayout.CENTER);

        promptsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadSelectedPrompt();
        });

        return p;
    }

    private void loadSelectedPrompt() {
        String selected = promptsList.getSelectedValue();
        if (selected == null) {
            promptEditor.setText("");
            promptStatusLbl.setText("");
            return;
        }
        try {
            Path file = ctx.promptsDir().resolve(selected);
            if (Files.exists(file)) {
                promptEditor.setText(Files.readString(file, StandardCharsets.UTF_8));
                promptEditor.setCaretPosition(0);
                promptStatusLbl.setText(selected);
            }
        } catch (Exception e) {
            UiHelpers.showError(this, "Ошибка чтения: " + e.getMessage());
        }
    }

    private void createNewPrompt() {
        String name = JOptionPane.showInputDialog(this,
                "Введите имя нового промпта (без .txt):",
                "Создание промпта",
                JOptionPane.PLAIN_MESSAGE);

        if (name != null && !name.trim().isEmpty()) {
            String fileName = name.trim() + ".txt";
            Path file = ctx.promptsDir().resolve(fileName);
            try {
                if (!Files.exists(file)) {
                    Files.writeString(file, "Текст промпта по умолчанию...", StandardCharsets.UTF_8);
                    refreshPromptsList();
                    promptsList.setSelectedValue(fileName, true);
                } else {
                    UiHelpers.showError(this, "Файл уже существует");
                }
            } catch (Exception e) {
                UiHelpers.showError(this, "Ошибка создания: " + e.getMessage());
            }
        }
    }

    private void deleteCurrentPrompt() {
        String selected = promptsList.getSelectedValue();
        if (selected == null) return;

        int confirm = JOptionPane.showConfirmDialog(this,
                "Удалить промпт " + selected + "?",
                "Подтверждение",
                JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            try {
                Path file = ctx.promptsDir().resolve(selected);
                Files.deleteIfExists(file);
                refreshPromptsList();
                promptEditor.setText("");
                promptStatusLbl.setText("");
            } catch (Exception e) {
                UiHelpers.showError(this, "Ошибка удаления: " + e.getMessage());
            }
        }
    }

    // ================================================================
    // Общие утилиты
    // ================================================================
    private JPanel row(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.add(UiHelpers.dimLabel(label), BorderLayout.WEST);
        field.setPreferredSize(new Dimension(0, 26));
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        return p;
    }

    private void loadAll() {
        loadSonar();
        loadJiraSession();
        loadJiraConfig();
        loadCsvStatus();
        loadOllama();
        refreshPromptsList();
    }

    private void loadSonar() {
        Path file = ctx.sessionFile();
        if (file != null && Files.exists(file)) {
            try {
                Properties p = new Properties();
                try (var in = Files.newInputStream(file)) { p.load(in); }
                sqUrlField.setText(p.getProperty("sonar.url", ""));
                sqUserField.setText(p.getProperty("sonar.user", ""));
                String auth = p.getProperty("sonar.auth", "bearer");
                sqAuthCombo.setSelectedItem(auth);

                String token = p.getProperty("sonar.token", "");
                sqTokenField.setText(token);
                sqTokenStatusLbl.setText(token.isBlank() ? "— не задан" : "✓ установлен");

                String cookie = p.getProperty("sonar.cookie", "");
                sqCookieArea.setText(cookie);
                sqCookieStatusLbl.setText(cookie.isBlank() ? "— не задан" : "✓ установлен");

            } catch (Exception ignored) {}
        }
    }

    private void loadJiraSession() {
        Path sTxt = Path.of("session.txt").toAbsolutePath();
        jiraSessionFileLbl.setText(sTxt.getFileName().toString());
        if (Files.exists(sTxt)) {
            try {
                jiraSessionArea.setText(Files.readString(sTxt, StandardCharsets.UTF_8));
                jiraSessionArea.setCaretPosition(0);
                updateJiraStatus();
            } catch (Exception ignored) {}
        }
    }

    private void loadJiraConfig() {
        JiraConfig jcfg = JiraConfig.getInstance();
        jiraUrlField.setText(jcfg.baseUrl());
        jiraIssueTypeField.setText(jcfg.issueType());
        jiraProjectField.setText(jcfg.defaultProject());
        jiraTeamField.setText(jcfg.team());
        jiraAssigneeField.setText(jcfg.assignee());
        jiraDefaultEpicField.setText(jcfg.defaultEpic()); // Загрузка

        for (Map.Entry<String, JTextField> entry : epicFields.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            entry.getValue().setText(jcfg.getEpic(parts[0], parts[1]));
        }
    }

    private void loadCsvStatus() {
        if (Files.exists(Path.of("team_mapping.csv"))) {
            teamCsvStatusLbl.setText("✓ Файл найден");
            teamCsvStatusLbl.setForeground(GuiStyle.OK);
        }
        if (Files.exists(Path.of("product_mapping.csv"))) {
            productCsvStatusLbl.setText("✓ Файл найден");
            productCsvStatusLbl.setForeground(GuiStyle.OK);
        }
    }

    private void loadOllama() {
        OllamaConfig cfg = ctx.ollamaConfig();
        olUrlField.setText(cfg.getUrl());
        olTimeoutSpinner.setValue(cfg.getTimeoutSeconds());
        olTempSpinner.setValue(cfg.getTemperature());
        if (cfg.getModel() != null && !cfg.getModel().isBlank()) {
            olModelCombo.removeAllItems();
            olModelCombo.addItem(cfg.getModel());
            olModelCombo.setSelectedItem(cfg.getModel());
        }
    }

    private void saveAll() {
        if (!saveUnifiedConfig()) return;
        saveJiraSessionToFile();
        saveCurrentPrompt();

        UiHelpers.showToast(this, "Настройки сохранены", false);
        if (onSaved != null) onSaved.run();
        dispose();
    }

    private boolean saveUnifiedConfig() {
        String url = sqUrlField.getText().trim();
        String user = sqUserField.getText().trim();
        String auth = (String) sqAuthCombo.getSelectedItem();
        String token = sqTokenField.getText().trim();
        String cookie = sqCookieArea.getText().trim();

        if (url.isEmpty()) {
            UiHelpers.showError(this, "Укажите sonar.url");
            return false;
        }
        if (!url.matches("^https?://.*")) url = "https://" + url;

        StringBuilder sb = new StringBuilder();
        sb.append("# ========================================================\n");
        sb.append("# app.properties — единый файл настроек приложения\n");
        sb.append("# ========================================================\n\n");

        // --- SonarQube ---
        sb.append("# --- SonarQube ---\n");
        sb.append("sonar.url=").append(url).append('\n');
        sb.append("sonar.user=").append(user).append('\n');
        sb.append("sonar.auth=").append(auth).append('\n');
        if ("cookie".equalsIgnoreCase(auth)) {
            sb.append("sonar.cookie=").append(cookie.replace("\n", " ")).append('\n');
        } else {
            sb.append("sonar.token=").append(token).append('\n');
        }
        sb.append('\n');

        // --- Ollama ---
        sb.append("# --- Ollama (LLM) ---\n");
        sb.append("ollama.url=").append(olUrlField.getText().trim()).append('\n');
        Object m = olModelCombo.getSelectedItem();
        sb.append("ollama.model=").append(m == null ? "" : m.toString().trim()).append('\n');
        sb.append("ollama.timeout=").append(olTimeoutSpinner.getValue()).append('\n');
        sb.append("ollama.temperature=").append(((Number) olTempSpinner.getValue()).doubleValue()).append('\n');
        sb.append('\n');

        // --- Jira ---
        sb.append("# --- Jira ---\n");
        sb.append("jira.url=").append(jiraUrlField.getText().trim()).append('\n');
        sb.append("jira.session=session.txt\n");
        sb.append("jira.issueType=").append(jiraIssueTypeField.getText().trim()).append('\n');
        sb.append("jira.defaultProject=").append(jiraProjectField.getText().trim()).append('\n');
        sb.append("jira.team=").append(jiraTeamField.getText().trim()).append('\n');
        sb.append("jira.assignee=").append(jiraAssigneeField.getText().trim()).append('\n');
        sb.append("jira.epic.default=").append(jiraDefaultEpicField.getText().trim()).append('\n'); // СОХРАНЕНИЕ
        sb.append('\n');

        // --- Эпики ---
        sb.append("# --- Jira Epics (QUAL:SEV) ---\n");
        for (Map.Entry<String, JTextField> entry : epicFields.entrySet()) {
            sb.append("jira.epic.").append(entry.getKey().toLowerCase())
                    .append("=").append(entry.getValue().getText().trim()).append('\n');
        }

        try {
            Path file = ctx.sessionFile(); // Файл app.properties
            if (file != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

                ctx.state().update(sb.toString());
                ctx.state().reload();
                ctx.ollamaConfig().reload();
                JiraConfig.getInstance().reload();
            }
            return true;
        } catch (Exception e) {
            UiHelpers.showError(this, "Ошибка сохранения настроек: " + e.getMessage());
            return false;
        }
    }

    private void refreshPromptsList() {
        promptsListModel.clear();
        Path dir = ctx.promptsDir();
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".txt"))
                        .sorted()
                        .forEach(promptsListModel::addElement);
            } catch (Exception ignored) {}
        }
    }

    private void saveCurrentPrompt() {
        String selected = promptsList.getSelectedValue();
        if (selected == null || selected.isEmpty()) return;
        try {
            Path file = ctx.promptsDir().resolve(selected);
            Files.writeString(file, promptEditor.getText(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            UiHelpers.showError(this, "Ошибка сохранения промпта: " + e.getMessage());
        }
    }
}