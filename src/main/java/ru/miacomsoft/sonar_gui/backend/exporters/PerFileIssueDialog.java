package ru.miacomsoft.sonar_gui.backend.exporters;

import ru.miacomsoft.sonar_gui.backend.ollama.OllamaResponse;
import ru.miacomsoft.sonar_gui.common.GuiContext;
import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.AiResolveService;
import ru.miacomsoft.sonar_gui.common.services.IssueService;
import ru.miacomsoft.sonar_gui.common.services.MarkService;
import ru.miacomsoft.sonar_gui.common.services.SetTagsService;
import ru.miacomsoft.sonar_gui.common.services.SnippetService;
import ru.miacomsoft.sonar_gui.common.services.TagsService;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PerFileIssueDialog extends JDialog {

    private final GuiContext ctx;
    private final IssueService.IssueDto issue;
    private final Runnable onDone;
    private final AiResolveService aiService;

    private final JTextArea snippetArea = new JTextArea();
    private final JComboBox<String> statusCombo;
    private final JTextArea commentArea = new JTextArea(4, 40);
    private final JPanel tagsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    private final Set<String> selectedTags = new LinkedHashSet<>();
    private final List<String> availableTags = new ArrayList<>();
    private final JButton applyBtn;
    private final JButton aiBtn;

    public PerFileIssueDialog(GuiContext ctx, IssueService.IssueDto issue, Runnable onDone) {
        super((Frame) null, "Детали ошибки", true);
        this.ctx = ctx;
        this.issue = issue;
        this.onDone = onDone;
        this.aiService = new AiResolveService(ctx);

        selectedTags.addAll(issue.tags());

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(920, 740);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(GuiStyle.BG);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        // HEADER
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        JLabel msgLbl = new JLabel(UiHelpers.wrapHtml(issue.message()));
        msgLbl.setForeground(GuiStyle.TEXT);
        msgLbl.setFont(GuiStyle.FONT_TITLE);
        header.add(msgLbl);

        JLabel meta = new JLabel(UiHelpers.wrapHtml(
                issue.rule() + " · " + issue.component() + ":" + issue.line()
                        + " · " + issue.severity() + " · status=" + issue.status()));
        meta.setForeground(GuiStyle.TEXT_DIM);
        meta.setFont(GuiStyle.FONT_MONO);
        header.add(Box.createVerticalStrut(4));
        header.add(meta);

        root.add(header, BorderLayout.NORTH);

        // CENTER
        JPanel center = new JPanel(new BorderLayout(10, 10));
        center.setOpaque(false);

        snippetArea.setEditable(false);
        snippetArea.setFont(GuiStyle.FONT_MONO);
        snippetArea.setBackground(GuiStyle.BG2);
        snippetArea.setForeground(GuiStyle.TEXT);
        JScrollPane snippetScroll = new JScrollPane(snippetArea);
        snippetScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(GuiStyle.BORDER),
                "Код", 0, 0, GuiStyle.FONT_BOLD, GuiStyle.TEXT_DIM));
        snippetScroll.setPreferredSize(new Dimension(0, 240));
        center.add(snippetScroll, BorderLayout.NORTH);

        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.setOpaque(false);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusRow.setOpaque(false);
        statusRow.add(UiHelpers.dimLabel("Статус перехода:"));
        statusCombo = UiHelpers.darkCombo(new String[]{
                "accept", "falsepositive", "confirm", "resolve", "wontfix"
        }, mapStatus(issue.status()));
        statusCombo.setPreferredSize(new Dimension(200, 26));
        statusRow.add(statusCombo);

        aiBtn = UiHelpers.secondaryButton("🤖 Получить резолюцию ИИ");
        aiBtn.addActionListener(e -> runAiResolve());
        statusRow.add(aiBtn);
        fields.add(statusRow);

        fields.add(Box.createVerticalStrut(8));
        fields.add(UiHelpers.dimLabel("Комментарий:"));
        commentArea.setFont(GuiStyle.FONT_MONO);
        commentArea.setBackground(GuiStyle.BG2);
        commentArea.setForeground(GuiStyle.TEXT);
        commentArea.setLineWrap(true);
        commentArea.setWrapStyleWord(true);
        JScrollPane commentScroll = new JScrollPane(commentArea);
        commentScroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        commentScroll.setPreferredSize(new Dimension(0, 80));
        fields.add(commentScroll);

        fields.add(Box.createVerticalStrut(8));
        fields.add(UiHelpers.dimLabel("Теги:"));
        tagsPanel.setOpaque(false);
        JScrollPane tagsScroll = new JScrollPane(tagsPanel);
        tagsScroll.setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER));
        tagsScroll.setPreferredSize(new Dimension(0, 140));
        tagsScroll.getViewport().setBackground(GuiStyle.BG2);
        fields.add(tagsScroll);

        center.add(fields, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        // FOOTER
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);

        if (issue.isProcessed()) {
            JButton reopenBtn = UiHelpers.dangerButton("Reopen");
            reopenBtn.addActionListener(e -> doReopen());
            footer.add(reopenBtn);
        }

        JButton cancelBtn = UiHelpers.secondaryButton("Отмена");
        cancelBtn.addActionListener(e -> dispose());
        footer.add(cancelBtn);

        applyBtn = UiHelpers.primaryButton("Применить");
        applyBtn.addActionListener(e -> doApply());
        footer.add(applyBtn);

        root.add(footer, BorderLayout.SOUTH);

        loadSnippetAndTags();
    }

    private void loadSnippetAndTags() {
        new SwingWorker<Object[], Void>() {
            @Override protected Object[] doInBackground() throws Exception {
                SnippetService ss = new SnippetService(ctx);
                SnippetService.SnippetResult sr = ss.fetchSnippet(
                        ctx.getProject(), issue.key());
                List<String> tags = new TagsService(ctx).fetchTags(ctx.getProject());
                return new Object[]{sr, tags};
            }
            @Override protected void done() {
                try {
                    Object[] r = get();
                    SnippetService.SnippetResult sr = (SnippetService.SnippetResult) r[0];
                    @SuppressWarnings("unchecked")
                    List<String> tags = (List<String>) r[1];

                    snippetArea.setText(sr.snippet().isEmpty()
                            ? "(фрагмент кода недоступен)"
                            : String.join("\n", sr.snippet()));
                    snippetArea.setCaretPosition(0);

                    availableTags.clear();
                    availableTags.addAll(tags);
                    for (String t : issue.tags()) {
                        if (!availableTags.contains(t)) availableTags.add(t);
                    }
                    rebuildTagCheckboxes();
                } catch (Exception e) {
                    snippetArea.setText("(ошибка: " + e.getMessage() + ")");
                }
            }
        }.execute();
    }

    private void rebuildTagCheckboxes() {
        tagsPanel.removeAll();
        for (String t : availableTags) {
            JCheckBox cb = new JCheckBox(t);
            cb.setOpaque(false);
            cb.setForeground(GuiStyle.TEXT);
            cb.setFont(GuiStyle.FONT_BASE);
            cb.setSelected(selectedTags.contains(t));
            cb.addActionListener(e -> {
                if (cb.isSelected()) selectedTags.add(t);
                else selectedTags.remove(t);
            });
            tagsPanel.add(cb);
        }
        tagsPanel.revalidate();
        tagsPanel.repaint();
    }

    private void runAiResolve() {
        if (!ctx.ollamaConfig().isConfigured()) {
            UiHelpers.showError(this,
                    "Ollama не настроена. Меню «Файл → Настройки Ollama».");
            return;
        }

        String status = (String) statusCombo.getSelectedItem();
        String statusLabel = status == null ? "" : status;

        aiBtn.setEnabled(false);
        aiBtn.setText("⏳ ИИ работает…");

        new SwingWorker<AiResolveService.ResolveResult, Void>() {
            @Override protected AiResolveService.ResolveResult doInBackground() throws Exception {
                return aiService.ask("status-comment", issue.key(), status, statusLabel);
            }
            @Override protected void done() {
                aiBtn.setEnabled(true);
                aiBtn.setText("🤖 Получить резолюцию ИИ");
                try {
                    AiResolveService.ResolveResult r = get();
                    OllamaResponse p = r.parsed();
                    if (p.status != null && !p.status.isBlank())
                        statusCombo.setSelectedItem(p.status);
                    if (p.comment != null) commentArea.setText(p.comment);

                    if (p.tags != null && !p.tags.isEmpty()) {
                        for (String t : p.tags) {
                            if (!availableTags.contains(t)) availableTags.add(t);
                            selectedTags.add(t);
                        }
                        if (p.tags.contains("require-fix"))
                            selectedTags.remove("not-require-fix");
                        if (p.tags.contains("not-require-fix"))
                            selectedTags.remove("require-fix");
                        rebuildTagCheckboxes();
                    }
                    UiHelpers.showToast(PerFileIssueDialog.this,
                            "Резолюция ИИ получена", false);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    UiHelpers.showError(PerFileIssueDialog.this,
                            "Ошибка ИИ: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void doApply() {
        String status = (String) statusCombo.getSelectedItem();
        String comment = commentArea.getText();
        String[] tags = selectedTags.toArray(new String[0]);
        boolean tagsChanged = !sameTags(issue.tags(), selectedTags);

        applyBtn.setEnabled(false);
        applyBtn.setText("Применение…");

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                new MarkService(ctx).markOne(ctx.getProject(), issue.key(), status, comment);
                if (tagsChanged)
                    new SetTagsService(ctx).setTags(ctx.getProject(), issue.key(), tags);
                return null;
            }
            @Override protected void done() {
                applyBtn.setEnabled(true);
                applyBtn.setText("Применить");
                try {
                    get();
                    UiHelpers.showToast(PerFileIssueDialog.this, "Применено", false);
                    dispose();
                    if (onDone != null) onDone.run();
                } catch (Exception e) {
                    UiHelpers.showError(PerFileIssueDialog.this, "Ошибка: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void doReopen() {
        if (!UiHelpers.confirm(this, "Reopen этот issue?")) return;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                String transition = "CONFIRMED".equals(issue.status())
                        ? "unconfirm" : "reopen";
                new MarkService(ctx).markOne(ctx.getProject(), issue.key(), transition, "");
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    UiHelpers.showToast(PerFileIssueDialog.this, "Reopen OK", false);
                    dispose();
                    if (onDone != null) onDone.run();
                } catch (Exception e) {
                    UiHelpers.showError(PerFileIssueDialog.this,
                            "Ошибка reopen: " + e.getMessage());
                }
            }
        }.execute();
    }

    private static String mapStatus(String sonar) {
        if (sonar == null) return "accept";
        return switch (sonar.toUpperCase()) {
            case "ACCEPTED"       -> "accept";
            case "CONFIRMED"      -> "confirm";
            case "FALSE_POSITIVE" -> "falsepositive";
            case "FIXED", "RESOLVED" -> "resolve";
            case "WONTFIX"        -> "wontfix";
            default               -> "accept";
        };
    }

    private static boolean sameTags(List<String> a, Set<String> b) {
        if (a == null) a = List.of();
        if (a.size() != b.size()) return false;
        return a.stream().allMatch(b::contains);
    }
}