package ru.miacomsoft.sonar_gui.tabs.report;

/** Типы экспорта, поддерживаемые /api/export-stream. */
public enum ReportExportType {
    JIRA              ("jira",              "1) Jira (TXT)",         "txt"),
    MARKDOWN          ("md",                "2) Markdown",           "md"),
    TXT               ("txt",               "3) TXT",                "txt"),
    CONFLUENCE        ("confluence",        "4) Confluence (HTML)",  "html"),
    HTML_FILES        ("html-files",        "5) HTML по файлам (Папка)", "zip"),
    HTML_FILES_TEAMS  ("html-files-teams",  "6) HTML + команды (Папка)", "zip"),
    TEAM_HTML         ("team-html",         "7) Team HTML (Папка Отдел/Модуль)", "html"),
    QUAL_SUMMARY      ("qual-summary",      "8) Сводка по Quality/Severity", "txt"),
    LLM_MD_ZIP        ("llm-md-zip",        "9) LLM MD (Папка)",     "zip"),
    TEAM_MODULE_ZIP   ("team-module-zip",   "10) Отчёт по командам (Папка)", "zip"),
    QUAL_TEAM_HTML    ("qual-team-html",    "11) Отчет по командам HTML (Папка)", "html");

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