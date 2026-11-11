import { esc, highlightCode, showTagEditor } from '../../../common.js';
import { getReopenTransition } from '../helpers.js';

// ---- Человекочитаемые названия полей SonarQube ----
const FIELD_LABELS = {
    'status':     'Статус',
    'resolution': 'Резолюция',
    'assignee':   'Исполнитель',
    'tags':       'Теги',
    'severity':   'Критичность',
    'type':       'Тип',
    'file':       'Файл',
    'rule':       'Правило',
    'comment':    'Комментарий',
};

function fieldLabel(key) {
    return FIELD_LABELS[key] || key;
}

function formatDate(iso) {
    if (!iso) return '';
    try {
        return new Date(iso).toLocaleString('ru-RU');
    } catch {
        return iso;
    }
}

// ---- Рендер одного события таймлайна ----
function renderTimelineEvent(e) {
    const dateStr = formatDate(e.date);

    if (e.type === 'comment') {
        return `
            <div style="margin-bottom:10px; padding-bottom:10px; border-bottom:1px solid var(--border);">
                <div style="margin-bottom:4px; display:flex; align-items:center; gap:6px; flex-wrap:wrap;">
                    <span>💬</span>
                    <span style="color:var(--accent); font-weight:bold;">${esc(e.userName)}</span>
                    <span style="color:var(--text-dim);">${esc(dateStr)}</span>
                </div>
                <div style="background:var(--bg); border-left:3px solid var(--accent);
                            padding:6px 10px; border-radius:0 4px 4px 0;
                            white-space:pre-wrap;">${esc(e.text)}</div>
            </div>
        `;
    }

    const hasStatusChange = e.diffs.some(d => d.key === 'status');
    const icon = hasStatusChange ? '🔄' : '✏️';

    const diffsHtml = e.diffs.map(diff => {
        const label = fieldLabel(diff.key);
        const oldVal = diff.oldValue || '—';
        const newVal = diff.newValue || '—';
        return `
            <div style="margin:2px 0 2px 20px;">
                <span style="color:var(--text-dim);">${esc(label)}:</span>
                <span style="color:var(--text-dim); text-decoration:line-through;">${esc(oldVal)}</span>
                &rarr;
                <b style="color:var(--text);">${esc(newVal)}</b>
            </div>
        `;
    }).join('');

    return `
        <div style="margin-bottom:10px; padding-bottom:10px; border-bottom:1px solid var(--border);">
            <div style="margin-bottom:4px; display:flex; align-items:center; gap:6px; flex-wrap:wrap;">
                <span>${icon}</span>
                <span style="color:var(--accent); font-weight:bold;">${esc(e.userName)}</span>
                <span style="color:var(--text-dim);">${esc(dateStr)}</span>
            </div>
            ${diffsHtml}
        </div>
    `;
}

export async function openAcceptedModal(issue, ctx, onDone) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 800px;">
            <div class="modal-header">
                <h3 style="margin:0;">Детали обработанного issue</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body" id="acc-modal-body">
                <div class="empty">Загрузка...</div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.addEventListener('click', e => { if (e.target === overlay) close(); });

    try {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        p.set('issueKey', issue.key);

        const [d, ch] = await Promise.all([
            ctx.api('/api/snippet?' + p),
            ctx.api('/api/changelog?' + p).catch(() => ({ changelog: [] }))
        ]);

        const it = d.issue || issue;
        const changelog = ch.changelog || [];
        const comments  = it.comments || [];
        const tags      = it.tags || [];

        // ---------- Активные теги ----------
        const tagsHtml = tags.length > 0
            ? `<div style="display:flex; flex-wrap:wrap; gap:6px;">
                ${tags.map(t => `
                    <span style="display:inline-block; padding:3px 10px; border-radius:10px;
                                 background:var(--bg3); border:1px solid var(--border);
                                 font-size:12px;">${esc(t)}</span>
                `).join('')}
               </div>`
            : `<div class="empty">Нет активных тегов</div>`;

        // ---------- Единый таймлайн ----------
        const events = [];

        // 1) Изменения полей из changelog
        changelog.forEach(entry => {
            events.push({
                type: 'change',
                user: entry.user,
                userName: entry.userName || entry.user || 'Пользователь',
                date: entry.creationDate,
                diffs: entry.diffs || []
            });
        });

        // 2) Комментарии из issue
        comments.forEach(c => {
            events.push({
                type: 'comment',
                user: c.login,
                userName: c.login || 'Пользователь',
                date: c.createdAt,
                text: c.markdown || c.htmlText || ''
            });
        });

        // Сортировка по убыванию даты
        events.sort((a, b) => {
            const da = a.date ? new Date(a.date).getTime() : 0;
            const db = b.date ? new Date(b.date).getTime() : 0;
            return db - da;
        });

        const timelineHtml = events.length > 0
            ? `<div style="background:var(--bg3); border:1px solid var(--border);
                           border-radius:6px; padding:12px; max-height:280px;
                           overflow-y:auto; font-size:12px;">
                ${events.map(renderTimelineEvent).join('')}
               </div>`
            : `<div class="empty">Нет истории изменений и комментариев</div>`;

        const body = overlay.querySelector('#acc-modal-body');
        body.innerHTML = `
            <div style="margin-bottom:12px;">
                <b style="font-size:16px;">${esc(it.message)}</b>
                <div style="color:var(--text-dim); margin-top:4px;">${esc(d.ruleName || it.rule)}</div>
            </div>

            <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:16px; font-size:12px; color:var(--text-dim);">
                <div><b>Файл:</b> ${esc(it.component)}</div>
                <div><b>Строка:</b> ${it.line}</div>
                <div><b>Severity:</b> <span class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</span></div>
                <div><b>Status:</b> ${esc(it.status)}${it.resolution ? ' / ' + esc(it.resolution) : ''}</div>
            </div>

            ${(d.snippet && d.snippet.length)
                ? `<pre class="code" style="max-height:300px; overflow:auto;">${highlightCode(d.snippet.join('\n'))}</pre>`
                : `<div class="empty">Код недоступен</div>`}

            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                    🏷️ Активные теги (${tags.length})
                </label>
                ${tagsHtml}
            </div>

            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                    📜 История изменений и комментарии (${events.length})
                </label>
                ${timelineHtml}
            </div>

            <div style="margin-top:20px; padding-top:16px; border-top:1px solid var(--border); display:flex; justify-content:flex-end; gap:8px;">
                <button id="acc-modal-tags" class="btn" style="margin-right:auto;">🏷️ Изменить теги</button>
                <button id="acc-modal-reopen" class="btn btn-danger">Снять пометку (Reopen)</button>
                <button id="acc-modal-close" class="btn">Закрыть</button>
            </div>
        `;

        overlay.querySelector('#acc-modal-close').addEventListener('click', close);
        overlay.querySelector('#acc-modal-tags').addEventListener('click', () => {
            showTagEditor([it], ctx, () => {
                close();
                if (onDone) onDone();
            });
        });

        overlay.querySelector('#acc-modal-reopen').addEventListener('click', async () => {
            if (!confirm('Снять пометку с этого issue?')) return;
            try {
                const pp = new URLSearchParams(ctx.qs());
                pp.set('key', it.key);
                pp.set('transition', getReopenTransition(it.status));
                pp.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + pp, { method: 'POST' });
                if (res.success === false) throw new Error(res.error || 'reopen failed');
                ctx.toast('Пометка снята');
                close();
                ctx.refreshStatus?.();
                if (onDone) onDone();
            } catch (e) {
                ctx.toast(e.message, true);
            }
        });
    } catch (e) {
        overlay.querySelector('#acc-modal-body').innerHTML =
            `<div class="sev-HIGH">${esc(e.message)}</div>`;
    }
}