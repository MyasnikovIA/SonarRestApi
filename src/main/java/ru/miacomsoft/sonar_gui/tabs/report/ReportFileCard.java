package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.common.GuiStyle;
import ru.miacomsoft.sonar_gui.common.UiHelpers;
import ru.miacomsoft.sonar_gui.common.services.IssueService;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** Карточка файла во вкладке Report. */
public class ReportFileCard extends JPanel {

    public interface Delegate {
        void onSelectionChanged();
        void onIssueDoubleClick(IssueService.IssueDto issue);
        void onIssueContextMenu(MouseEvent e, IssueService.IssueDto issue,
                                List<IssueService.IssueDto> selected);
    }

    private final String fileName;
    private final List<ReportRowModel> rows = new ArrayList<>();
    private final Delegate delegate;

    private final JCheckBox fileCheckbox;
    private final JTable table;
    private final ReportTableModel tableModel;

    public ReportFileCard(String fileName, List<IssueService.IssueDto> issues, Delegate delegate) {
        this.fileName = fileName;
        this.delegate = delegate;
        for (IssueService.IssueDto dto : issues) rows.add(new ReportRowModel(dto));

        setLayout(new BorderLayout());
        setBackground(GuiStyle.BG2);
        setBorder(BorderFactory.createLineBorder(GuiStyle.BORDER, 1));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        header.setBackground(GuiStyle.BG3);

        fileCheckbox = new JCheckBox();
        fileCheckbox.setOpaque(false);
        fileCheckbox.addActionListener(e -> {
            setAllSelected(fileCheckbox.isSelected());
            if (delegate != null) delegate.onSelectionChanged();
        });
        header.add(fileCheckbox);

        JLabel lbl = new JLabel("📁 " + fileName + " — " + rows.size());
        lbl.setForeground(GuiStyle.TEXT);
        lbl.setFont(GuiStyle.FONT_BOLD);
        header.add(lbl);

        add(header, BorderLayout.NORTH);

        tableModel = new ReportTableModel(rows);
        table = new JTable(tableModel);
        table.setBackground(GuiStyle.BG2);
        table.setForeground(GuiStyle.TEXT);
        table.setFont(GuiStyle.FONT_MONO);
        table.setGridColor(GuiStyle.BORDER);
        table.setRowHeight(24);
        table.getTableHeader().setBackground(GuiStyle.BG3);
        table.getTableHeader().setForeground(GuiStyle.TEXT_DIM);
        table.getTableHeader().setFont(GuiStyle.FONT_BOLD);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        int[] widths = {30, 220, 90, 200};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.getColumnModel().getColumn(i).setMaxWidth(widths[i]);
        }

        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v,
                                                                     boolean sel, boolean focus, int row, int col) {
                JCheckBox cb = new JCheckBox();
                cb.setOpaque(true);
                cb.setBackground(sel ? GuiStyle.ACCENT2 : GuiStyle.BG2);
                cb.setSelected(v != null && (Boolean) v);
                return cb;
            }
        });

        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v,
                                                                     boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                if (v != null) c.setForeground(GuiStyle.severityColor(v.toString()));
                setBackground(sel ? GuiStyle.ACCENT2 : GuiStyle.BG2);
                return c;
            }
        });

        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v,
                                                                     boolean sel, boolean focus, int row, int col) {
                String msg = v == null ? "" : v.toString();
                IssueService.IssueDto dto = rows.get(row).issue;
                String prefix = "";
                if (dto.status() != null && !dto.status().isEmpty()
                        && !dto.status().equals("OPEN")
                        && !dto.status().equals("REOPENED")) {
                    prefix = "<span style='color:#3fb950;font-weight:bold;'>["
                            + UiHelpers.escHtml(dto.status()) + "]</span> ";
                }
                setText("<html>" + prefix + UiHelpers.escHtml(msg) + "</html>");
                setBackground(sel ? GuiStyle.ACCENT2 : GuiStyle.BG2);
                setForeground(GuiStyle.TEXT);
                return this;
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0) return;
                if (e.getClickCount() == 1 && col == 0) {
                    ReportRowModel r = rows.get(row);
                    r.selected = !r.selected;
                    tableModel.fireTableCellUpdated(row, 0);
                    syncHeaderCheckbox();
                    if (delegate != null) delegate.onSelectionChanged();
                    return;
                }
                if (e.getClickCount() == 2) {
                    if (delegate != null) delegate.onIssueDoubleClick(rows.get(row).issue);
                }
            }
            @Override public void mousePressed(MouseEvent e) { handlePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { handlePopup(e); }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(GuiStyle.BG2);
        add(scroll, BorderLayout.CENTER);
    }

    private void handlePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) return;
        table.setRowSelectionInterval(row, row);
        List<IssueService.IssueDto> selected = getSelectedIssues();
        if (selected.isEmpty()) selected = List.of(rows.get(row).issue);
        if (delegate != null) delegate.onIssueContextMenu(e, rows.get(row).issue, selected);
    }

    public List<IssueService.IssueDto> getSelectedIssues() {
        List<IssueService.IssueDto> out = new ArrayList<>();
        for (ReportRowModel r : rows) if (r.selected) out.add(r.issue);
        return out;
    }

    public List<IssueService.IssueDto> getAllIssues() {
        List<IssueService.IssueDto> out = new ArrayList<>();
        for (ReportRowModel r : rows) out.add(r.issue);
        return out;
    }

    public void setAllSelected(boolean sel) {
        for (ReportRowModel r : rows) r.selected = sel;
        tableModel.fireTableDataChanged();
        fileCheckbox.setSelected(sel);
    }

    public void syncHeaderCheckbox() {
        boolean all = !rows.isEmpty();
        for (ReportRowModel r : rows) if (!r.selected) { all = false; break; }
        fileCheckbox.setSelected(all);
    }

    public String getFileName() { return fileName; }

    public boolean matchesFilter(String text, java.util.Set<String> tags,
                                 java.util.Set<String> excludedTags,
                                 java.util.Set<String> rules,
                                 String statusFilter) {
        String q = text == null ? "" : text.trim().toLowerCase();
        for (ReportRowModel r : rows) {
            IssueService.IssueDto it = r.issue;
            boolean isProcessed = it.isProcessed();
            if ("PROCESSED".equals(statusFilter) && !isProcessed) continue;
            if ("UNPROCESSED".equals(statusFilter) && isProcessed) continue;

            if (!tags.isEmpty()) {
                boolean hasMatch = false;
                List<String> itTags = it.tags();
                for (String t : tags) {
                    if (t.equals("__NO_TAGS__")) {
                        if (itTags.isEmpty()) hasMatch = true;
                    } else if (itTags.contains(t)) hasMatch = true;
                    if (hasMatch) break;
                }
                if (!hasMatch) continue;
            }

            if (!rules.isEmpty() && !rules.contains(it.rule())) continue;

            if (!q.isEmpty()) {
                boolean m = (it.key() != null && it.key().toLowerCase().contains(q))
                        || (it.message() != null && it.message().toLowerCase().contains(q))
                        || (it.component() != null && it.component().toLowerCase().contains(q));
                if (!m) continue;
            }

            if (!excludedTags.isEmpty()) {
                List<String> itTags = it.tags();
                boolean hasExcluded = false;
                for (String t : excludedTags) {
                    if (t.equals("__NO_TAGS__")) {
                        if (itTags.isEmpty()) hasExcluded = true;
                    } else if (itTags.contains(t)) hasExcluded = true;
                    if (hasExcluded) break;
                }
                if (hasExcluded) continue;
            }

            return true; // хотя бы одна строка прошла
        }
        return false;
    }

    private static class ReportTableModel extends AbstractTableModel {
        private static final String[] COLS = {"", "rule", "sev", "message"};
        private final List<ReportRowModel> rows;

        ReportTableModel(List<ReportRowModel> rows) { this.rows = rows; }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override public Object getValueAt(int row, int col) {
            ReportRowModel r = rows.get(row);
            return switch (col) {
                case 0 -> r.selected;
                case 1 -> r.issue.rule();
                case 2 -> r.issue.severity();
                case 3 -> r.issue.message();
                default -> "";
            };
        }

        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c == 0) { rows.get(r).selected = (Boolean) v; fireTableCellUpdated(r, c); }
        }
        @Override public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }
    }
}