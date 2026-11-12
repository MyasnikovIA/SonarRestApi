import { detectLanguage } from './helpers.js';

const CONCURRENCY = 5;

function mdInline(s) {
    if (s == null) return '';
    return String(s).replace(/\\/g, '\\\\').replace(/([*_`|])/g, '\\$1');
}

function sanitize(s) {
    if (!s) return 'project';
    return String(s).replace(/[^A-Za-z0-9._-]/g, '_');
}

function codeFenceFor(content) {
    const matches = content.match(/`+/g);
    const maxLen = matches ? Math.max(...matches.map(s => s.length)) : 0;
    return '`'.repeat(Math.max(3, maxLen + 1));
}

export async function exportReportToMarkdown(issues, ctx, projectName) {
    const results = new Array(issues.length);

    // Параллельно (с ограничением) тянем snippet для каждого issue
    for (let start = 0; start < issues.length; start += CONCURRENCY) {
        const slice = issues.slice(start, start + CONCURRENCY);
        const fetched = await Promise.all(slice.map(async (it) => {
            if (it._snippet) return { issue: it, snippet: it._snippet };

            const p = new URLSearchParams();
            if (ctx.state.project) p.set('project', ctx.state.project);
            p.set('issueKey', it.key);
            try {
                const d = await ctx.api('/api/snippet?' + p);
                return { issue: d.issue || it, snippet: d.snippet || [] };
            } catch (e) {
                return { issue: it, snippet: [] };
            }
        }));
        fetched.forEach((d, i) => { results[start + i] = d; });
    }

    const lines = [];
    lines.push(`# Отчет по проекту: ${projectName}`);
    lines.push('');
    lines.push(`**Всего issues:** ${issues.length}`);
    lines.push(`**Сгенерировано:** ${new Date().toLocaleString('ru-RU')}`);
    lines.push('');
    lines.push('---');
    lines.push('');

    let num = 0;
    for (const { issue: it, snippet } of results) {
        num++;

        const ruleName = it.ruleName || '';
        const lastComment = (it.comments && it.comments.length > 0)
            ? [...it.comments].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))[0]
            : null;

        lines.push(`## ${num}. ${it.key}`);
        lines.push('');
        lines.push(`- **Проект:** ${mdInline(projectName)}`);
        lines.push(`- **Правило:** ${mdInline(it.rule)}${ruleName ? ' — ' + mdInline(ruleName) : ''}`);
        lines.push(`- **Файл:** \`${it.component}:${it.line}\``);
        lines.push(`- **Теги:** ${(it.tags && it.tags.length > 0) ? it.tags.map(t => '`' + t + '`').join(', ') : '—'}`);
        lines.push(`- **Сообщение:** ${mdInline(it.message)}`);

        if (lastComment) {
            lines.push(`- **Последний комментарий:** ${mdInline(lastComment.markdown || lastComment.htmlText || '')}`);
        } else {
            lines.push(`- **Последний комментарий:** —`);
        }
        lines.push('');

        if (snippet && snippet.length > 0) {
            const lang = detectLanguage(it.component).codeFence;
            const body = snippet.map(l => l.replace(/§HL_START§/g, '>>> ').replace(/§HL_END§/g, ' <<<')).join('\n');
            const fence = codeFenceFor(body);
            lines.push(`**Фрагмент кода:**`);
            lines.push('');
            lines.push(`${fence}${lang}`);
            lines.push(body);
            lines.push(`${fence}`);
            lines.push('');
        }
        lines.push('---');
        lines.push('');
    }

    const md = lines.join('\n');
    const filename = `report_${sanitize(projectName)}_${Date.now()}.md`;
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);

    return md;
}