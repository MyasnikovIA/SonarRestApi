import {
    esc, toast, highlightCode, showTagEditor
} from '../../common.js';
import { openStatusModal } from './modals/status-modal.js';
import { getReopenTransition } from './status.js';

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
    try { return new Date(iso).toLocaleString('ru-RU'); }
    catch { return iso; }
}

// ---- Последний комментарий ----
function getLastComment(comments) {
    if (!comments || !comments.length) return null;
    const sorted = [...comments].sort((a, b) => {
        const da = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const db = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return db - da;
    });
    return sorted[0];
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

export const snippet = {
    id: 'snippet',
    title: 'Код ошибки (Snippet)',
    filters: ['sev', 'qual', 'issueKey'],

    async mount(el, ctx) {
        const key = ctx.state.issueKey;
        if (!key) {
            el.innerHTML = '<div class="empty">Введите issueKey в фильтре сверху</div>';
            return;
        }
        el.innerHTML = '<div class="empty">loading…</div>';
        try {
            const p = new URLSearchParams();
            if (ctx.state.project) p.set('project', ctx.state.project);
            p.set('issueKey', key);

            // Параллельно: данные issue (tags, comments, snippet) + changelog
            const [d, ch] = await Promise.all([
                ctx.api('/api/snippet?' + p),
                ctx.api('/api/changelog?' + p).catch(() => ({ changelog: [] }))
            ]);

            const it = d.issue;
            if (!it) {
                el.innerHTML = '<div class="empty">Issue не найден</div>';
                return;
            }

            const isMarked  = it.status !== 'OPEN' && it.status !== 'REOPENED';
            const tags      = it.tags || [];
            const comments  = it.comments || [];
            const changelog = ch.changelog || [];
            const lastCmt   = getLastComment(comments);

            // ---------- Active tags + last comment ----------
            const tagsHtml = tags.length > 0
                ? `<div style="display:flex; flex-wrap:wrap; gap:6px;">
                    ${tags.map(t => `
                        <span style="display:inline-block; padding:3px 10px; border-radius:10px;
                                     background:var(--bg3); border:1px solid var(--border);
                                     font-size:12px;">${esc(t)}</span>
                    `).join('')}
                   </div>`
                : `<div class="empty">Нет активных тегов</div>`;

            const lastCommentHtml = lastCmt
                ? `
                    <div style="margin-top:12px; padding-top:12px; border-top:1px dashed var(--border);">
                        <div style="font-size:12px; color:var(--text-dim); margin-bottom:4px;">
                            💬 Последний комментарий:
                        </div>
                        <div style="background:var(--bg); border-left:3px solid var(--accent);
                                    padding:8px 10px; border-radius:0 4px 4px 0;
                                    font-size:12px;">
                            <div style="margin-bottom:4px;">
                                <span style="color:var(--accent); font-weight:bold;">${esc(lastCmt.login || 'Пользователь')}</span>
                                <span style="color:var(--text-dim);"> (${esc(formatDate(lastCmt.createdAt))})</span>
                            </div>
                            <div style="white-space:pre-wrap;">${esc(lastCmt.markdown || lastCmt.htmlText || '')}</div>
                        </div>
                    </div>
                `
                : '';

            // ---------- Unified timeline ----------
            const events = [];

            changelog.forEach(entry => {
                events.push({
                    type: 'change',
                    user: entry.user,
                    userName: entry.userName || entry.user || 'Пользователь',
                    date: entry.creationDate,
                    diffs: entry.diffs || []
                });
            });

            comments.forEach(c => {
                events.push({
                    type: 'comment',
                    user: c.login,
                    userName: c.login || 'Пользователь',
                    date: c.createdAt,
                    text: c.markdown || c.htmlText || ''
                });
            });

            events.sort((a, b) => {
                const da = a.date ? new Date(a.date).getTime() : 0;
                const db = b.date ? new Date(b.date).getTime() : 0;
                return db - da;
            });

            const timelineHtml = events.length > 0
                ? `<div style="background:var(--bg3); border:1px solid var(--border);
                               border-radius:6px; padding:12px; max-height:320px;
                               overflow-y:auto; font-size:12px;">
                    ${events.map(renderTimelineEvent).join('')}
                   </div>`
                : `<div class="empty">Нет истории изменений и комментариев</div>`;

            el.innerHTML = `
                <div class="card">
                    <div style="display:flex; gap:10px; margin-bottom:16px;">
                        ${!isMarked
                            ? `<button id="sn-btn-mark" class="btn btn-accent">Установить статус</button>`
                            : `<button id="sn-btn-cancel" class="btn btn-danger">Отменить комментарий</button>`}
                        <button id="sn-btn-tags" class="btn">🏷️ Изменить теги</button>
                    </div>
                    <h3>${esc(it.key)}</h3>
                    <div style="margin:8px 0"><b>${esc(it.message)}</b></div>
                    ${d.ruleName ? `<div style="color:var(--text-dim)">${esc(d.ruleName)}</div>` : ''}
                    <div style="margin:8px 0;color:var(--text-dim);font-size:12px">
                        <code>${esc(it.rule)}</code> · ${esc(it.component)} · L${it.line} ·
                        <span class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</span> ·
                        status: <span style="color:${isMarked ? 'var(--ok)' : 'var(--text)'}; font-weight:bold;">${esc(it.status)}</span>
                    </div>

                    ${(d.snippet && d.snippet.length)
                        ? `<pre class="code">${highlightCode(d.snippet.join('\n'))}</pre>`
                        : `<div class="empty">(фрагмент кода недоступен)</div>`}

                    <div style="color:var(--text-dim);font-size:11px;margin-top:8px;">
                        source: ${esc(d.source)}
                    </div>
                </div>

                <div class="card">
                    <h4 style="margin-top:0;">🏷️ Активные теги (${tags.length})</h4>
                    ${tagsHtml}
                    ${lastCommentHtml}
                </div>

                <div class="card">
                    <h4 style="margin-top:0;">📜 История изменений и комментарии (${events.length})</h4>
                    ${timelineHtml}
                </div>
            `;

            el.querySelector('#sn-btn-tags').addEventListener('click', () => {
                showTagEditor([it], ctx, () => snippet.mount(el, ctx));
            });

            if (!isMarked) {
                el.querySelector('#sn-btn-mark').addEventListener('click', () => {
                    openStatusModal(it, ctx, () => snippet.mount(el, ctx));
                });
            } else {
                el.querySelector('#sn-btn-cancel').addEventListener('click', async () => {
                    if (!confirm(`Снять пометку (reopen) с issue ${it.key}?`)) return;

                    const btn = el.querySelector('#sn-btn-cancel');
                    btn.disabled = true;
                    btn.textContent = 'Отмена...';

                    try {
                        const p = new URLSearchParams(ctx.qs());
                        p.set('key', it.key);
                        p.set('transition', getReopenTransition(it.status));
                        p.set('dryRun', 'false');
                        await ctx.api('/api/mark?' + p, { method: 'POST' });
                        toast('Пометка снята');
                        ctx.refreshStatus?.();
                        snippet.mount(el, ctx);
                    } catch (e) {
                        toast(e.message, true);
                        btn.disabled = false;
                        btn.textContent = 'Отменить комментарий';
                    }
                });
            }

        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};