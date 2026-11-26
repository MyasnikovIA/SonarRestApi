package ru.miacomsoft.sonar_gui.tabs.perfile;

import ru.miacomsoft.sonar_gui.common.services.IssueService;

/** Строка таблицы внутри карточки файла: DTO + флаг выбора. */
public class PerFileRowModel {

    public final IssueService.IssueDto issue;
    public boolean selected;

    public PerFileRowModel(IssueService.IssueDto issue) {
        this.issue = issue;
        this.selected = false;
    }
}