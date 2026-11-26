package ru.miacomsoft.sonar_gui.tabs.report;

/** Типы экспорта, поддерживаемые /api/export-stream. */
public enum ReportExportType {
    JIRA              ("jira",              "Jira (TXT)",         "txt"),
    MARKDOWN          ("md",                "Markdown",           "md"),
    TXT               ("txt",               "TXT",                "txt"),
    CONFLUENCE        ("confluence",        "Confluence (HTML)",  "html"),
    HTML_FILES        ("html-files",        "HTML по файлам (ZIP)", "zip"),
    HTML_FILES_TEAMS  ("html-files-teams",  "HTML + команды (ZIP)", "zip"),
    LLM_MD_ZIP        ("llm-md-zip",        "LLM MD (ZIP)",        "zip"),
    TEAM_MODULE_ZIP   ("team-module-zip",   "Отчёт по командам (ZIP)", "zip");

    public final String code;
    public final String title;
    public final String ext;

    ReportExportType(String code, String title, String ext) {
        this.code = code;
        this.title = title;
        this.ext = ext;
    }

    @Override public String toString() { return title; }
}