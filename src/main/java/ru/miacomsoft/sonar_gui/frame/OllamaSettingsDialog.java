package ru.miacomsoft.sonar_gui.frame;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaClient;
import ru.miacomsoft.sonar_gui.backend.ollama.OllamaConfig;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;

import javax.swing.*;
import java.awt.*;

/**
 * Настройка Ollama: url, model, timeout, temperature.
 *
 * Кнопка «Проверить» → GET /api/tags на Ollama-сервере,
 * показывает список доступных моделей и позволяет выбрать одну.
 */
public class OllamaSettingsDialog extends JDialog {

    private final GuiContext ctx;
    private final Runnable onSaved;

    private final JTextField urlField   = UiHelpers.darkField("");
    private final JComboBox<String> modelCombo = new JComboBox<>();
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(300, 10, 3600, 10));
    private final JSpinner tempSpinner = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.05));
    private final JLabel statusLbl = UiHelpers.dimLabel("");
    private final JButton testBtn;

    public OllamaSettingsDialog(GuiContext ctx, Runnable onSaved) {
        super((Frame) null, "Настройки Ollama", true);
        this.ctx = ctx;
        this.onSaved = onSaved;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(620, 380);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        JLabel header = new JLabel("Настройки Ollama (LLM)");
        header.setForeground(GuiStyle.ACCENT);
        header.setFont(GuiStyle.FONT_TITLE);
        root.add(header, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);

        form.add(row("url", urlField));
        form.add(Box.createVerticalStrut(8));

        JPanel modelRow = new JPanel(new BorderLayout(8, 0));
        modelRow.setOpaque(false);
        modelRow.add(UiHelpers.dimLabel("model"), BorderLayout.WEST);
        modelCombo.setEditable(true);
        modelCombo.setBackground(GuiStyle.BG);
        modelCombo.setForeground(GuiStyle.TEXT);
        modelCombo.setFont(GuiStyle.FONT_MONO);
        modelRow.add(modelCombo, BorderLayout.CENTER);

        testBtn = UiHelpers.secondaryButton("Проверить");
        testBtn.addActionListener(e -> testAndLoadModels());
        modelRow.add(testBtn, BorderLayout.EAST);
        modelRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        form.add(modelRow);

        form.add(Box.createVerticalStrut(8));
        form.add(row("timeout (сек)", timeoutSpinner));
        form.add(Box.createVerticalStrut(8));
        form.add(row("temperature", tempSpinner));

        root.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.add(statusLbl);

        JButton cancelBtn = UiHelpers.secondaryButton("Отмена");
        cancelBtn.addActionListener(e -> dispose());
        footer.add(cancelBtn);

        JButton saveBtn = UiHelpers.primaryButton("💾 Сохранить");
        saveBtn.addActionListener(e -> save());
        footer.add(saveBtn);

        root.add(footer, BorderLayout.SOUTH);

        loadFromConfig();
    }

    private JPanel row(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.add(UiHelpers.dimLabel(label), BorderLayout.WEST);
        field.setPreferredSize(new Dimension(0, 26));
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        return p;
    }

    private void loadFromConfig() {
        OllamaConfig cfg = ctx.ollamaConfig();
        urlField.setText(cfg.getUrl() == null ? "" : cfg.getUrl());
        modelCombo.removeAllItems();
        if (cfg.getModel() != null && !cfg.getModel().isBlank()) {
            modelCombo.addItem(cfg.getModel());
            modelCombo.setSelectedItem(cfg.getModel());
        }
        timeoutSpinner.setValue(cfg.getTimeoutSeconds());
        tempSpinner.setValue(cfg.getTemperature());
    }

    private void testAndLoadModels() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            statusLbl.setText("укажите url");
            statusLbl.setForeground(GuiStyle.ERR);
            return;
        }
        statusLbl.setText("проверка…");
        statusLbl.setForeground(GuiStyle.TEXT_DIM);
        testBtn.setEnabled(false);

        new SwingWorker<JSONArray, Void>() {
            @Override protected JSONArray doInBackground() throws Exception {
                // Временный конфиг — чтобы не портить основной
                OllamaConfig tmp = new OllamaConfig(ctx.ollamaConfig().getConfigFile());
                tmp.setUrl(url);
                OllamaClient client = new OllamaClient(tmp);
                return client.listModels();
            }
            @Override protected void done() {
                testBtn.setEnabled(true);
                try {
                    JSONArray models = get();
                    String prev = (String) modelCombo.getSelectedItem();
                    modelCombo.removeAllItems();
                    for (int i = 0; i < models.length(); i++) {
                        JSONObject m = models.getJSONObject(i);
                        String name = m.optString("name", m.optString("model", ""));
                        if (!name.isBlank()) modelCombo.addItem(name);
                    }
                    if (prev != null && !prev.isBlank()) modelCombo.setSelectedItem(prev);
                    else if (modelCombo.getItemCount() > 0) modelCombo.setSelectedIndex(0);

                    statusLbl.setText("моделей: " + models.length());
                    statusLbl.setForeground(GuiStyle.OK);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    statusLbl.setText("ошибка: " + cause.getMessage());
                    statusLbl.setForeground(GuiStyle.ERR);
                }
            }
        }.execute();
    }

    private void save() {
        OllamaConfig cfg = ctx.ollamaConfig();
        cfg.setUrl(urlField.getText().trim());
        Object m = modelCombo.getSelectedItem();
        cfg.setModel(m == null ? "" : m.toString().trim());
        cfg.setTimeoutSeconds((Integer) timeoutSpinner.getValue());
        cfg.setTemperature(((Number) tempSpinner.getValue()).doubleValue());
        cfg.save();

        UiHelpers.showToast(this, "Настройки Ollama сохранены", false);
        if (onSaved != null) onSaved.run();
        dispose();
    }
}