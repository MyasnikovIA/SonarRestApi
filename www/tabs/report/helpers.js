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