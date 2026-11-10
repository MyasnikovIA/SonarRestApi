import { esc, highlightCode, showTagEditor } from '../../../common.js';
import { getReopenTransition } from '../helpers.js';

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
        const comments = it.comments || [];

        const commentsHtml = comments.length > 0 ? `
            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">Комментарии:</label>
                <div style="background:var(--bg3); border:1px solid var(--border); border-radius:6px; padding:12px; max-height:150px; overflow-y:auto; font-size:12px;">
                    ${comments.map(c => `
                        <div style="margin-bottom:8px; padding-bottom:8px; border-bottom:1px solid var(--border);">
                            <div style="color:var(--accent); font-weight:bold; margin-bottom:4px;">${esc(c.login || 'Пользователь')} (${new Date(c.createdAt).toLocaleString()}):</div>
                            <div style="white-space:pre-wrap;">${esc(c.markdown || c.htmlText || '')}</div>
                        </div>
                    `).join('')}
                </div>
            </div>
        ` : '';

        const changelogHtml = changelog.length > 0 ? `
            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">История изменений:</label>
                <div style="background:var(--bg3); border:1px solid var(--border); border-radius:6px; padding:12px; max-height:150px; overflow-y:auto; font-size:12px;">
                    ${changelog.map(c => `
                        <div style="margin-bottom:8px; padding-bottom:8px; border-bottom:1px solid var(--border);">
                            <div style="color:var(--accent); font-weight:bold; margin-bottom:4px;">${esc(c.userName || c.user)} (${new Date(c.creationDate).toLocaleString()}):</div>
                            <div>
                                ${c.diffs.map(diff => `<div><span style="color:var(--text-dim)">${esc(diff.key)}:</span> ${esc(diff.oldValue || '—')} &rarr; <b>${esc(diff.newValue)}</b></div>`).join('')}
                            </div>
                        </div>
                    `).join('')}
                </div>
            </div>
        ` : '';

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

            ${changelogHtml}
            ${commentsHtml}

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