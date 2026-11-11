// ==================== Markdown EXPORT ====================
// Без сторонних библиотек: сборка строки + Blob + <a download>.

const CONCURRENCY = 5;   // сколько snippet'ов тянем параллельно

// ---------- helpers ----------

function mdInline(s) {
    if (s == null) return '';
    return String(s)
        .replace(/\\/g, '\\\\')
        .replace(/([*_`|])/g, '\\$1');
}

function formatDate(iso) {
    if (!iso) return '';
    try { return new Date(iso).toLocaleString('ru-RU'); }
    catch { return iso; }
}

function sanitize(s) {
    if (!s) return 'project';
    return String(s).replace(/[^A-Za-z0-9._-]/g, '_');
}

function detectLanguage(component) {
    const m = String(component || '').match(/\.([a-z0-9]+)$/i);
    if (!m) return '';
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
    return map[m[1].toLowerCase()] || m[1].toLowerCase();
}

// longest run of backticks in content — чтобы сформировать fence,
// который не сломается, если в коде есть ```
function codeFenceFor(content) {
    const matches = content.match(/`+/g);
    const maxLen = matches ? Math.max(...matches.map(s => s.length)) : 0;
    return '`'.repeat(Math.max(3, maxLen + 1));
}

function renderCodeBlock(snippet, language) {
    if (!snippet || !snippet.length) {
        return '_(фрагмент кода недоступен)_';
    }
    // §HL_START§ / §HL_END§ → ">>> " / " <<<"
    const cleaned = snippet.map(line =>
        line.replace(/§HL_START§/g, '>>> ').replace(/§HL_END§/g, ' <<<')
    );
    const body = cleaned.join('\n');
    const fence = codeFenceFor(body);
    return `${fence}${language || ''}\n${body}\n${fence}`;
}

function lastComment(comments) {
    if (!comments || !comments.length) return null;
    const sorted = [...comments].sort((a, b) => {
        const da = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const db = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return db - da;
    });
    return sorted[0];
}

// ---------- главная функция ----------

/**
 * @param {Array}  issues    — уже отфильтрованные issues
 * @param {object} ctx       — контекст приложения (ctx.api, ctx.state.project)
 * @param {object} meta      — { project, totalAll, filterSummary }
 * @returns {Promise<string>} — сгенерированный markdown (и он же скачивается)
 */
export async function exportAcceptedToMarkdown(issues, ctx, meta = {}) {
    // 1) Параллельно (с ограничением) тянем snippet + issue по каждому ключу.
    //    /api/snippet отдаёт issue + ruleName + snippet + comments одним запросом.
    const results = new Array(issues.length);

    for (let start = 0; start < issues.length; start += CONCURRENCY) {
        const slice = issues.slice(start, start + CONCURRENCY);
        const fetched = await Promise.all(slice.map(async (it) => {
            const p = new URLSearchParams();
            if (ctx.state.project) p.set('project', ctx.state.project);
            p.set('issueKey', it.key);
            try {
                return await ctx.api('/api/snippet?' + p);
            } catch (e) {
                return { issue: it, snippet: [], ruleName: it.ruleName || '' };
            }
        }));
        fetched.forEach((d, i) => { results[start + i] = d; });
    }

    // 2) Группируем по файлам (name → [{ issue, snippetData }])
    const groups = new Map();
    issues.forEach((it, idx) => {
        const file = it.component || '(unknown)';
        if (!groups.has(file)) groups.set(file, []);
        groups.get(file).push({ issue: it, snippetData: results[idx] });
    });

    const sortedFiles = [...groups.entries()]
        .sort((a, b) => a[0].localeCompare(b[0]));

    sortedFiles.forEach(([, list]) =>
        list.sort((a, b) => (a.issue.line || 0) - (b.issue.line || 0))
    );

    // 3) Собираем MD
    const lines = [];

    lines.push(`# Обработанные issues — ${meta.project || ''}`);
    lines.push('');
    if (meta.filterSummary) {
        lines.push(`**Фильтр:** ${meta.filterSummary}`);
    }
    if (typeof meta.totalAll === 'number') {
        lines.push(`**Всего issues (до фильтра):** ${meta.totalAll}`);
    }
    lines.push(`**В отчёте (после фильтра):** ${issues.length}`);
    lines.push(`**Файлов в отчёте:** ${sortedFiles.length}`);
    lines.push(`**Сгенерировано:** ${new Date().toLocaleString('ru-RU')}`);
    lines.push('');
    lines.push('---');
    lines.push('');

    let issueNum = 0;

    for (const [file, list] of sortedFiles) {
        lines.push(`## 📁 Файл: \`${file}\``);
        lines.push(`**Замечаний в файле:** ${list.length}`);
        lines.push('');

        for (const { issue: it, snippetData } of list) {
            issueNum++;

            const fresh     = snippetData && snippetData.issue ? snippetData.issue : it;
            const snippet   = (snippetData && snippetData.snippet) || [];
            const ruleName  = (snippetData && snippetData.ruleName) || it.ruleName || '';
            const comments  = fresh.comments || it.comments || [];
            const tags      = fresh.tags || it.tags || [];
            const last      = lastComment(comments);
            const sev       = it.impactSeverity || it.severity || '';
            const ruleKey   = it.rule || '';
            const type      = it.type || '';
            const effort    = it.effort || '';
            const author    = it.author || '';
            const created   = it.creationDate || '';
            const updated   = it.updateDate || '';
            const status    = it.status || '';
            const resolution= it.resolution || '';

            lines.push(`### Issue #${issueNum} — \`${it.key}\``);
            lines.push('');

            // ---- 1) Описание ошибки ----
            lines.push(`- **Сообщение:** ${mdInline(it.message || '')}`);

            // ---- 2) Имя правила Sonar ----
            if (ruleName)  lines.push(`- **Правило:** ${mdInline(ruleName)}`);
            if (ruleKey)   lines.push(`- **Ключ правила:** \`${ruleKey}\``);

            // ---- 3) Остальные данные ----
            lines.push(`- **Файл:** \`${it.component}\``);
            lines.push(`- **Строка:** ${it.line}`);
            if (sev)         lines.push(`- **Критичность:** ${sev}`);
            if (type)        lines.push(`- **Тип:** ${type}`);
            lines.push(`- **Статус:** ${status}${resolution ? ' / ' + resolution : ''}`);
            if (effort)      lines.push(`- **Трудозатраты:** ${mdInline(effort)}`);
            if (author)      lines.push(`- **Автор:** ${mdInline(author)}`);
            if (created)     lines.push(`- **Создано:** ${formatDate(created)}`);
            if (updated)     lines.push(`- **Обновлено:** ${formatDate(updated)}`);

            // ---- 2) Активные теги (последние) ----
            if (tags.length) {
                lines.push(`- **Активные теги:** ${tags.map(t => '`' + t + '`').join(', ')}`);
            } else {
                lines.push(`- **Активные теги:** —`);
            }

            lines.push('');

            // ---- 5) Последний пользовательский комментарий ----
            lines.push('**Последний комментарий:**');
            if (last) {
                const text = last.markdown || last.htmlText || '';
                lines.push(`> **${mdInline(last.login || 'Пользователь')}** (${formatDate(last.createdAt)}):`);
                String(text).split(/\r?\n/).forEach(tl => lines.push(`> ${mdInline(tl)}`));
            } else {
                lines.push('_Комментариев нет_');
            }
            lines.push('');

            // ---- 6) Фрагмент кода ----
            lines.push('**Фрагмент кода:**');
            lines.push('');
            lines.push(renderCodeBlock(snippet, detectLanguage(it.component)));
            lines.push('');

            // ---- Разделитель между ошибками ----
            lines.push('---');
            lines.push('');
        }

        // ---- Разделитель между файлами (жирнее) ----
        lines.push('');
        lines.push('<div style="page-break-after: always;"></div>');
        lines.push('');
        lines.push('---');
        lines.push('');
    }

    const md = lines.join('\n');

    // 4) Скачивание
    const filename = `accepted_${sanitize(meta.project)}_${Date.now()}.md`;
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);

    return md;
}