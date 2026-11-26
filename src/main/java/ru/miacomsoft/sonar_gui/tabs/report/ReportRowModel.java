package ru.miacomsoft.sonar_gui.tabs.report;

import ru.miacomsoft.sonar_gui.common.services.IssueService;

/** Строка таблицы внутри карточки Report. */
public class ReportRowModel {

    public final IssueService.IssueDto issue;
    public boolean selected;

    public ReportRowModel(IssueService.IssueDto issue) {
        this.issue = issue;
        this.selected = false;
    }
}