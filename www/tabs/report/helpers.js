export function detectLanguage(component) {
    const path = String(component || '').split(':')[0].toLowerCase();
    if (/\.d\.ts$/.test(path)) return { name: 'TypeScript', codeFence: 'typescript' };
    const m = path.match(/\.([a-z0-9]+)$/);
    if (!m) return { name: 'неизвестный', codeFence: 'text' };
    const map = {
        php: 'php', js: 'javascript', mjs: 'javascript', cjs: 'javascript',
        jsx: 'jsx', ts: 'typescript', tsx: 'tsx', vue: 'vue',
        html: 'html', css: 'css', scss: 'scss', less: 'less',
        java: 'java', kt: 'kotlin', kts: 'kotlin', groovy: 'groovy',
        scala: 'scala', sql: 'sql', py: 'python', go: 'go', rb: 'ruby',
        cs: 'csharp', cpp: 'cpp', cc: 'cpp', cxx: 'cpp', hpp: 'cpp',
        hxx: 'cpp', c: 'c', h: 'c', rs: 'rust', swift: 'swift',
        xml: 'xml', yaml: 'yaml', yml: 'yaml', json: 'json',
        sh: 'bash', bash: 'bash'
    };
    return { name: m[1].toUpperCase(), codeFence: map[m[1].toLowerCase()] || 'text' };
}

export function getLastComment(issue) {
    if (!issue.comments || issue.comments.length === 0) return 'нет';
    const sorted = [...issue.comments].sort((a, b) => {
        const da = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const db = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return db - da;
    });
    return sorted[0].markdown || sorted[0].htmlText || 'нет';
}

export function getIssueLink(issue, projectName, host = 'sonar.bars.group') {
    const sev = issue.impactSeverity || issue.severity || 'ALL';
    const status = issue.status || 'OPEN';
    return `https://${host}/project/issues?impactSeverities=${sev}&issueStatuses=${status}&open=${issue.key}&id=${projectName}`;
}

export function escapeHtml(unsafe) {
    if (unsafe == null) return '';
    return String(unsafe)
         .replace(/&/g, "&amp;")
         .replace(/</g, "&lt;")
         .replace(/>/g, "&gt;")
         .replace(/"/g, "&quot;")
         .replace(/'/g, "&#039;");
}

// --- Форматтеры ---

export function formatJira(issue, snippet, projectName, host) {
    const link = getIssueLink(issue, projectName, host);
    let text = `h4. ${issue.key}\n`;
    text += `*Ссылка:* [${issue.key}|${link}]\n`;
    const ruleName = issue.ruleName ? ` — ${issue.ruleName}` : '';
    text += `*Правило:* ${issue.rule}${ruleName}\n`;
    text += `*Файл:* ${issue.component}:${issue.line}\n`;

    const tagsStr = (issue.tags && issue.tags.length > 0) ? issue.tags.join(', ') : 'нет';
    text += `*Теги:* ${tagsStr}\n`;
    text += `*Сообщение:* ${issue.message}\n`;
    text += `*Пользовательский комментарий:* ${getLastComment(issue)}\n`;

    if (snippet && snippet.length > 0) {
        const lang = detectLanguage(issue.component);
        const code = snippet.map(line =>
            line.replace(/§HL_START§/g, '>>> ').replace(/§HL_END§/g, ' <<<')
        ).join('\n');
        text += `{code:${lang.codeFence}}\n${code}\n{code}\n`;
    }
    return text;
}

export function formatMd(issue, snippet, projectName, host) {
    const link = getIssueLink(issue, projectName, host);
    let text = `### ${issue.key}\n\n`;
    text += `- **Ссылка:** [Открыть в SonarQube](${link})\n`;
    text += `- **Правило:** ${issue.rule}${issue.ruleName ? ' — ' + issue.ruleName : ''}\n`;
    text += `- **Файл:** \`${issue.component}:${issue.line}\`\n`;
    text += `- **Теги:** ${(issue.tags && issue.tags.length > 0) ? issue.tags.join(', ') : '—'}\n`;
    text += `- **Сообщение:** ${issue.message}\n`;
    text += `- **Пользовательский комментарий:** ${getLastComment(issue)}\n\n`;

    if (snippet && snippet.length > 0) {
        const lang = detectLanguage(issue.component);
        const code = snippet.map(line =>
            line.replace(/§HL_START§/g, '>>> ').replace(/§HL_END§/g, ' <<<')
        ).join('\n');
        text += `**Фрагмент кода:**\n\`\`\`${lang.codeFence}\n${code}\n\`\`\`\n`;
    }
    return text;
}

export function formatTxt(issue, snippet, projectName, host) {
    const link = getIssueLink(issue, projectName, host);
    let text = `Issue: ${issue.key}\n`;
    text += `Ссылка: ${link}\n`;
    text += `Правило: ${issue.rule}${issue.ruleName ? ' — ' + issue.ruleName : ''}\n`;
    text += `Файл: ${issue.component}:${issue.line}\n`;
    const tagsStr = (issue.tags && issue.tags.length > 0) ? issue.tags.join(', ') : 'нет';
    text += `Теги: ${tagsStr}\n`;
    text += `Сообщение: ${issue.message}\n`;
    text += `Пользовательский комментарий: ${getLastComment(issue)}\n`;

    if (snippet && snippet.length > 0) {
        const code = snippet.map(line =>
            line.replace(/§HL_START§/g, '>>> ').replace(/§HL_END§/g, ' <<<')
        ).join('\n');
        text += `\nКод:\n${code}\n`;
    }
    return text;
}

export function formatConfluence(issue, snippet, projectName, host) {
    const link = getIssueLink(issue, projectName, host);
    const ruleName = issue.ruleName ? ` — ${issue.ruleName}` : '';
    const tagsStr = (issue.tags && issue.tags.length > 0) ? issue.tags.join(', ') : 'нет';

    let html = `\n`;
    html += `<p><strong>Идентификатор ошибки:</strong>${escapeHtml(issue.key)}</p>\n`;
    html += `<p><strong>Ссылка:</strong> <a href="${escapeHtml(link)}">${escapeHtml(link)}</a></p>\n`;
    html += `<p><strong>Правило:</strong> ${escapeHtml(issue.rule)}${escapeHtml(ruleName)}</p>\n`;
    html += `<p><strong>Файл:</strong> ${escapeHtml(issue.component)}:${issue.line}</p>\n`;
    html += `<p><strong>Теги:</strong> ${escapeHtml(tagsStr)}</p>\n`;
    html += `<p><strong>Сообщение:</strong> ${escapeHtml(issue.message)}</p>\n`;
    html += `<p><strong>Пользовательский комментарий:</strong> ${escapeHtml(getLastComment(issue))}</p>\n`;

    if (snippet && snippet.length > 0) {
        // Заменяем маркеры на HTML-теги выделения
        const code = snippet.map(line => {
            return escapeHtml(line)
                .replace(/§HL_START§/g, '<mark class="sonar-issue-mark">')
                .replace(/§HL_END§/g, '</mark>');
        }).join('\n');

        html += `<details>\n<summary>Показать фрагмент кода</summary>\n<pre><code>${code}</code></pre>\n</details>\n`;
    }

    html += `<br/><hr/><br/>\n`;
    return html;
}

// Функция для отдельного файла с аккордеоном (используется в html-files)
export function formatHtmlFile(fileIssues, projectName, host, fileName) {
    const now = new Date().toLocaleString('ru-RU');
    let html = `<!DOCTYPE html>\n<html lang="ru">\n<head>\n<meta charset="UTF-8">\n<title>Отчет: ${escapeHtml(projectName)} - ${escapeHtml(fileName)}</title>\n`;
    html += `<style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; line-height: 1.5; color: #172b4d; max-width: 1000px; margin: 0 auto; padding: 20px; background: #fafbfc; }
        h1, h2 { color: #0052cc; margin-bottom: 8px; }
        h3 { color: #172b4d; margin-bottom: 8px; font-size: 16px; }
        p { margin: 6px 0; }
        a { color: #0052cc; text-decoration: none; }
        a:hover { text-decoration: underline; }
        .issue { border: 1px solid #dfe1e6; border-radius: 4px; background: #fff; padding: 16px; margin-bottom: 16px; }
        details { margin-top: 12px; border: 1px solid #dfe1e6; border-radius: 4px; background: #f4f5f7; }
        summary { cursor: pointer; padding: 8px 12px; font-weight: 600; color: #42526e; user-select: none; }
        summary:hover { background: #ebecf0; }
        pre { margin: 0; padding: 12px; background: #fff; overflow-x: auto; border-top: 1px solid #dfe1e6; border-radius: 0 0 4px 4px; font-family: monospace; font-size: 13px; white-space: pre-wrap; }

        /* Стиль для подсветки ошибки (как в модальном окне) */
        mark.sonar-issue-mark {
            background-color: rgba(248, 81, 73, 0.25);
            border-bottom: 2px solid #f85149;
            color: inherit;
            padding: 0 2px;
            border-radius: 2px;
        }
    </style>\n</head>\n<body>\n`;
    html += `<h1>Проект: ${escapeHtml(projectName)}</h1>\n`;
    html += `<h2>Файл: ${escapeHtml(fileName)}</h2>\n`;
    html += `<p><em>Сгенерировано: ${now} &middot; Всего issues: ${fileIssues.length}</em></p>\n<hr/>\n`;

    fileIssues.forEach(it => {
        const link = getIssueLink(it, projectName, host);
        const ruleName = it.ruleName ? ` — ${it.ruleName}` : '';
        const tagsStr = (it.tags && it.tags.length > 0) ? it.tags.join(', ') : 'нет';
        const lastComment = getLastComment(it);

        html += `<div class="issue">\n`;
        html += `<p><strong>Идентификатор ошибки:</strong>${escapeHtml(it.key)}  (Строка: ${it.line})</p>\n`;
        html += `<p><strong>Ссылка:</strong> <a href="${escapeHtml(link)}" target="_blank">${escapeHtml(link)}</a></p>\n`;
        html += `<p><strong>Правило:</strong> ${escapeHtml(it.rule)}${escapeHtml(ruleName)}</p>\n`;
        html += `<p><strong>Теги:</strong> ${escapeHtml(tagsStr)}</p>\n`;
        html += `<p><strong>Сообщение:</strong> ${escapeHtml(it.message)}</p>\n`;
        html += `<p><strong>Пользовательский комментарий:</strong> ${escapeHtml(lastComment)}</p>\n`;

        if (it._snippet && it._snippet.length > 0) {
            // Заменяем маркеры на HTML-теги выделения
            const code = it._snippet.map(line => {
                return escapeHtml(line)
                    .replace(/§HL_START§/g, '<mark class="sonar-issue-mark">')
                    .replace(/§HL_END§/g, '</mark>');
            }).join('\n');

            html += `<details>\n<summary>Показать фрагмент кода</summary>\n<pre><code>${code}</code></pre>\n</details>\n`;
        }
        html += `</div>\n`;
    });

    html += `</body>\n</html>`;
    return html;
}