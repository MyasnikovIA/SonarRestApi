import {
    esc, toast, highlightCode, showTagEditor
} from '../../common.js';
import { openStatusModal } from './modals/status-modal.js';
import { getReopenTransition } from './status.js';

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

            // Параллельно: данные issue (включая tags и comments) + changelog
            const [d, ch] = await Promise.all([
                ctx.api('/api/snippet?' + p),
                ctx.api('/api/changelog?' + p).catch(() => ({ changelog: [] }))
            ]);

            const it = d.issue;
            if (!it) {
                el.innerHTML = '<div class="empty">Issue не найден</div>';
                return;
            }

            const isMarked = it.status !== 'OPEN' && it.status !== 'REOPENED';
            const tags       = it.tags || [];
            const comments   = it.comments || [];
            const changelog  = ch.changelog || [];

            // ---------- Tags ----------
            const tagsHtml = tags.length > 0
                ? `<div style="display:flex; flex-wrap:wrap; gap:6px;">
                    ${tags.map(t => `
                        <span style="display:inline-block; padding:3px 10px; border-radius:10px;
                                     background:var(--bg3); border:1px solid var(--border);
                                     font-size:12px;">${esc(t)}</span>
                    `).join('')}
                   </div>`
                : `<div class="empty">Нет активных тегов</div>`;

            // ---------- Changelog ----------
            const changelogHtml = changelog.length > 0
                ? `<div style="background:var(--bg3); border:1px solid var(--border);
                               border-radius:6px; padding:12px; max-height:260px;
                               overflow-y:auto; font-size:12px;">
                    ${changelog.map(c => `
                        <div style="margin-bottom:8px; padding-bottom:8px;
                                    border-bottom:1px solid var(--border);">
                            <div style="color:var(--accent); font-weight:bold; margin-bottom:4px;">
                                ${esc(c.userName || c.user || 'Пользователь')}
                                <span style="color:var(--text-dim); font-weight:normal;">
                                    (${new Date(c.creationDate).toLocaleString()})
                                </span>
                            </div>
                            <div>
                                ${(c.diffs || []).map(diff => `
                                    <div>
                                        <span style="color:var(--text-dim)">${esc(diff.key)}:</span>
                                        ${esc(diff.oldValue || '—')} &rarr; <b>${esc(diff.newValue || '')}</b>
                                    </div>
                                `).join('')}
                            </div>
                        </div>
                    `).join('')}
                   </div>`
                : `<div class="empty">Нет истории изменений</div>`;

            // ---------- Comments ----------
            const commentsHtml = comments.length > 0
                ? `<div style="background:var(--bg3); border:1px solid var(--border);
                               border-radius:6px; padding:12px; max-height:260px;
                               overflow-y:auto; font-size:12px;">
                    ${comments.map(c => `
                        <div style="margin-bottom:8px; padding-bottom:8px;
                                    border-bottom:1px solid var(--border);">
                            <div style="color:var(--accent); font-weight:bold; margin-bottom:4px;">
                                ${esc(c.login || 'Пользователь')}
                                <span style="color:var(--text-dim); font-weight:normal;">
                                    (${new Date(c.createdAt).toLocaleString()})
                                </span>
                            </div>
                            <div style="white-space:pre-wrap;">${esc(c.markdown || c.htmlText || '')}</div>
                        </div>
                    `).join('')}
                   </div>`
                : `<div class="empty">Нет комментариев</div>`;

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
                </div>

                <div class="card">
                    <h4 style="margin-top:0;">📜 История изменений (${changelog.length})</h4>
                    ${changelogHtml}
                </div>

                <div class="card">
                    <h4 style="margin-top:0;">💬 Комментарии (${comments.length})</h4>
                    ${commentsHtml}
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