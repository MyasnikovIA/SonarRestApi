import {
    esc, toast, highlightCode,
    detectLanguage, cleanSnippet, buildPromptFromApi
} from '../common.js';
import { bindOllamaResolveButton, bindOllamaSettingsButton } from '../ollama.js';

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

            const d = await ctx.api('/api/snippet?' + p);
            const it = d.issue;
            if (!it) {
                el.innerHTML = '<div class="empty">Issue не найден</div>';
                return;
            }

            const isMarked = it.status !== 'OPEN' && it.status !== 'REOPENED';

            el.innerHTML = `
                <div class="card">
                    <div style="display:flex; gap:10px; border-top:1px solid var(--border); padding-top:16px;">
                        ${!isMarked
                            ? `<button id="sn-btn-mark" class="btn btn-accent">Установить статус</button>`
                            : `<button id="sn-btn-cancel" class="btn btn-danger">Отменить комментарий</button>`}
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

                    <div style="color:var(--text-dim);font-size:11px;margin-top:8px; margin-bottom: 16px;">source: ${esc(d.source)}</div>
                </div>`;

            if (!isMarked) {
                el.querySelector('#sn-btn-mark').addEventListener('click', () => {
                    openStatusModal(it, d.ruleName, d.snippet, ctx, () => this.mount(el, ctx));
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
                        p.set('dryRun', 'false');
                        await ctx.api('/api/cancel?' + p, { method: 'POST' });
                        toast('Пометка снята');
                        ctx.refreshStatus?.();
                        this.mount(el, ctx);
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

// ==================== ПРОМПТ ДЛЯ AI (server-side build) ====================

async function buildStatusCommentPrompt(ctx, issueKey, status, statusLabel) {
    const params = {
        name: 'status-comment',
        issueKey,
        status
    };
    if (statusLabel) params.statusLabel = statusLabel;
    return buildPromptFromApi(ctx, params);
}

async function copyToClipboard(text) {
    if (navigator.clipboard && window.isSecureContext) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch (e) { /* fallback ниже */ }
    }
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-1000px';
    ta.style.left = '-1000px';
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    return ok;
}

function flashButton(btn, text, ms) {
    if (!btn) return;
    const original = btn.dataset.originalText || btn.textContent;
    btn.dataset.originalText = original;
    btn.textContent = text;
    btn.disabled = true;
    setTimeout(function () {
        btn.textContent = original;
        btn.disabled = false;
    }, ms || 1500);
}

function bindStatusPromptButton(overlay, issue, ruleName, snippet, ctx) {
    const btn = overlay.querySelector('#modal-btn-gen-prompt');
    if (!btn) return;
    btn.addEventListener('click', async function () {
        const sel = overlay.querySelector('#modal-status');
        if (!sel) return;
        const status = sel.value;
        const statusLabel = sel.selectedOptions[0]
            ? sel.selectedOptions[0].textContent.trim()
            : status;

        let prompt;
        try {
            prompt = await buildStatusCommentPrompt(ctx, issue.key, status, statusLabel);
        } catch (e) {
            toast('Не удалось построить промпт: ' + e.message, true);
            return;
        }
        const ok = await copyToClipboard(prompt);

        if (ok) {
            flashButton(btn, 'Промпт скопирован!');
            toast('Промпт для статуса "' + status + '" скопирован в буфер обмена');
        } else {
            const ta = overlay.querySelector('#modal-comment');
            if (ta && !ta.value) {
                ta.value = prompt;
                ta.focus();
                ta.select();
            }
            flashButton(btn, 'Не удалось скопировать', 2000);
            toast('Промпт вставлен в поле комментария (статус "' + status + '")', true);
        }
    });
}

// ==================== МОДАЛЬНОЕ ОКНО СМЕНЫ СТАТУСА ====================

function openStatusModal(issue, ruleName, snippet, ctx, onDone) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 600px;">
            <div class="modal-header">
                <h3 style="margin:0;">Изменение статуса</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body">
                <p style="margin-top:0; color:var(--text-dim);">Выберите статус и оставьте комментарий для:<br><b>${esc(issue.key)}</b></p>

                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Статус перехода</label>
                    <select id="modal-status" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                        <option value="accept">Принять (Это ошибка, но исправлена будет позднее)</option>
                        <option value="falsepositive">Ложно (Анализ содержит ошибки)</option>
                        <option value="confirm">Подтвердить</option>
                        <option value="resolve">Исправлено</option>
                        <option value="wontfix">Не исправлять</option>
                    </select>
                </div>

                <div style="margin-bottom:16px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                    <textarea id="modal-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
                </div>

                <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                        <button id="modal-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM">Сгенерировать промпт</button>
                        <button id="modal-btn-ai-resolve" type="button" class="btn" title="Отправить промпт в Ollama">Получить резолюцию ИИ</button>
                    </div>
                    <div style="display:flex; gap:8px; align-items:center;">
                        <button id="modal-cancel" class="btn">Отмена</button>
                        <button id="modal-apply" class="btn btn-accent">Применить</button>
                        <button id="modal-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
                    </div>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.querySelector('#modal-cancel').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    bindStatusPromptButton(overlay, issue, ruleName, snippet, ctx);

    bindOllamaResolveButton(overlay, {
        ctx,
        buildPrompt: (status, statusLabel) =>
            buildStatusCommentPrompt(ctx, issue.key, status, statusLabel),
        commentSelector: '#modal-comment'
    });
    bindOllamaSettingsButton(overlay, ctx);

    overlay.querySelector('#modal-apply').addEventListener('click', async () => {
        const status = overlay.querySelector('#modal-status').value;
        const comment = overlay.querySelector('#modal-comment').value;

        const btn = overlay.querySelector('#modal-apply');
        btn.disabled = true;
        btn.textContent = 'Применение...';

        try {
            const p = new URLSearchParams(ctx.qs());
            p.set('key', issue.key);
            p.set('transition', status);
            p.set('comment', comment);
            p.set('dryRun', 'false');
            await ctx.api('/api/mark?' + p, { method: 'POST' });

            toast('Статус успешно изменен');
            close();
            ctx.refreshStatus?.();
            if (onDone) onDone();
        } catch (e) {
            toast(e.message, true);
            btn.disabled = false;
            btn.textContent = 'Применить';
        }
    });
}