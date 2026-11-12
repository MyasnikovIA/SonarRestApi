import { esc } from '../../common.js';
import { formatJira, formatMd, formatTxt, formatConfluence } from './helpers.js';

export async function showExportModal(issues, formatType, ctx, projectName) {
    if (!issues.length) return;

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.style.zIndex = '2000';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 520px;">
            <div class="modal-header">
                <h3 style="margin:0;">Генерация отчета (${formatType === 'confluence' ? 'Confluence' : formatType.toUpperCase()})</h3>
            </div>
            <div class="modal-body">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; font-size:12px;">
                    <span>Прогресс: <b id="exp-counter">0 / ${issues.length}</b></span>
                    <span id="exp-status" style="color:var(--text-dim);">подготовка...</span>
                </div>
                <div style="height:8px; background:var(--bg3); border-radius:4px; overflow:hidden; border:1px solid var(--border);">
                    <div id="exp-fill" style="height:100%; width:0%; background:var(--accent); transition: width 0.15s;"></div>
                </div>
                <div id="exp-current" style="margin-top:10px; font-size:12px; color:var(--text-dim); white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">—</div>
                <div style="margin-top:16px; display:flex; justify-content:flex-end; gap:8px;">
                    <button id="exp-cancel" class="btn btn-danger">Остановить</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const counterEl = overlay.querySelector('#exp-counter');
    const statusEl = overlay.querySelector('#exp-status');
    const fillEl = overlay.querySelector('#exp-fill');
    const currentEl = overlay.querySelector('#exp-current');
    const cancelBtn = overlay.querySelector('#exp-cancel');

    let cancelled = false;
    const controller = new AbortController();

    cancelBtn.addEventListener('click', () => {
        cancelled = true;
        controller.abort();
        statusEl.textContent = 'Остановка...';
    });

    const blocks = [];
    const total = issues.length;
    const now = new Date().toLocaleString('ru-RU');

    let host = 'sonar.bars.group';
    try {
        const status = await ctx.api('/api/status');
        if (status.session && status.session.host) {
            host = status.session.host;
        }
    } catch (e) {
        console.warn('Не удалось получить host для ссылок', e);
    }

    // Заголовки
    if (formatType === 'jira') {
        blocks.push(`h3. Проект: ${projectName}\n`);
        blocks.push(`*Всего issues:* ${total}\n*Сгенерировано:* ${now}\n\n`);
    } else if (formatType === 'md') {
        blocks.push(`# Отчет по проекту: ${projectName}\n`);
        blocks.push(`**Сгенерировано:** ${now}\n**Всего issues:** ${total}\n\n---\n`);
    } else if (formatType === 'txt') {
        blocks.push(`=== Отчет по проекту: ${projectName} ===\n`);
        blocks.push(`Сгенерировано: ${now}\nВсего issues: ${total}\n\n`);
    } else if (formatType === 'confluence') {
        blocks.push(`<h2>Проект: ${projectName}</h2>\n`);
        blocks.push(`<p><em>Сгенерировано: ${now} &middot; Всего issues: ${total}</em></p>\n<hr/>\n`);
    }

    for (let i = 0; i < total; i++) {
        if (cancelled) break;
        const it = issues[i];

        counterEl.textContent = `${i + 1} / ${total}`;
        currentEl.textContent = it.key + ' — ' + it.component;
        statusEl.textContent = 'загрузка кода...';
        fillEl.style.width = `${Math.round(((i) / total) * 100)}%`;

        let snippet = [];
        if (!it._snippet) {
            try {
                const p = new URLSearchParams({ project: ctx.state.project, issueKey: it.key });
                const d = await ctx.api('/api/snippet?' + p, { signal: controller.signal });
                snippet = d.snippet || [];
                it._snippet = snippet;
                if (d.issue && d.issue.comments) {
                    it.comments = d.issue.comments;
                }
            } catch (e) {
                if (e.name === 'AbortError') break;
                console.warn('Failed to fetch snippet for ' + it.key, e);
            }
        } else {
            snippet = it._snippet;
        }

        if (formatType === 'jira')       blocks.push(formatJira(it, snippet, projectName, host));
        if (formatType === 'md')         blocks.push(formatMd(it, snippet, projectName, host));
        if (formatType === 'txt')        blocks.push(formatTxt(it, snippet, projectName, host));
        if (formatType === 'confluence') blocks.push(formatConfluence(it, snippet, projectName, host));

        if (formatType === 'md')  blocks.push('\n---\n');
        if (formatType === 'txt') blocks.push('\n----------------------------------------\n');
    }

    counterEl.textContent = `${cancelled ? 'Остановлено на ' + (blocks.length) : total} / ${total}`;
    statusEl.textContent = cancelled ? 'отменено' : 'формирование файла...';
    fillEl.style.width = `${cancelled ? 100 : 100}%`;

    if (blocks.length > 1) {
        const text = blocks.join('\n');
        const ext = formatType === 'jira' ? 'txt' : (formatType === 'confluence' ? 'html' : formatType);
        const mime = formatType === 'md' ? 'text/markdown' : (formatType === 'confluence' ? 'text/html' : 'text/plain');

        const filename = `report_${projectName.replace(/[^A-Za-z0-9._-]/g, '_')}_${Date.now()}.${ext}`;
        const blob = new Blob([text], { type: `${mime};charset=utf-8` });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    statusEl.textContent = cancelled ? 'Отменено пользователем' : 'Готово!';
    cancelBtn.textContent = 'Закрыть';
    cancelBtn.classList.remove('btn-danger');
    cancelBtn.classList.add('btn-accent');
    cancelBtn.replaceWith(cancelBtn.cloneNode(true));
    overlay.querySelector('#exp-cancel').addEventListener('click', () => overlay.remove());
}