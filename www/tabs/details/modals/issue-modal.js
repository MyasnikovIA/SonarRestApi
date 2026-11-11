import { esc, toast, highlightCode } from '../../../common.js';
import { bindOllamaResolveButton, bindOllamaSettingsButton } from '../../../ollama.js';
import { getMappedStatus, getReopenTransition } from '../status.js';
import { parseOllamaResponse } from '../parse-response.js';
import { buildStatusCommentPrompt } from '../prompts.js';
import {
    PRIORITY_TAGS,
    fetchAvailableTags,
    buildOrderedTags,
    renderTagCheckboxes,
    setIssueTags
} from '../tags.js';

export async function openIssueModal(issue, ctx, reloadCb) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 800px;">
            <div class="modal-header">
                <h3 style="margin:0;">Детали ошибки</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body" id="modal-body">
                <div class="empty">Загрузка...</div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    try {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        p.set('issueKey', issue.key);
        const d = await ctx.api('/api/snippet?' + p);
        const it = d.issue || issue;

        const availableTags = await fetchAvailableTags(ctx);

        const currentStatusValue = getMappedStatus(it.status);
        const canReopen = it.status && it.status !== 'OPEN' && it.status !== 'REOPENED';

        const currentTags = it.tags || [];
        currentTags.forEach(t => { if (!availableTags.includes(t)) availableTags.push(t); });

        const initialTags = [...currentTags];
        const selectedTags = new Set(initialTags);
        let tagsQuery = '';

        function renderModalTagList() {
            const ordered = buildOrderedTags(availableTags, initialTags, PRIORITY_TAGS, tagsQuery);
            renderTagCheckboxes(overlay.querySelector('#modal-tags-list'), ordered, selectedTags);
        }

        const comments = it.comments || d.comments || [];
        const commentsHtml = comments.length > 0 ? `
            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">История комментариев:</label>
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

        const body = overlay.querySelector('#modal-body');
        body.innerHTML = `
            <div style="margin-bottom:12px;">
                <b style="font-size:16px;">${esc(it.message)}</b>
                <div style="color:var(--text-dim); margin-top:4px;">${esc(d.ruleName || it.rule)}</div>
            </div>

            <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:16px; font-size:12px; color:var(--text-dim);">
                <div><b>Файл:</b> ${esc(it.component)}</div>
                <div><b>Строка:</b> ${it.line}</div>
                <div><b>Severity:</b> <span class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</span></div>
                <div><b>Текущий статус:</b> ${esc(it.status)}</div>
            </div>

            ${(d.snippet && d.snippet.length)
                ? `<pre class="code" style="max-height:300px; overflow:auto;">${highlightCode(d.snippet.join('\n'))}</pre>`
                : `<div class="empty">Код недоступен</div>`}

            ${commentsHtml}

            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px;">Теги</label>
                <input type="text" id="modal-tags-filter" placeholder="Фильтр по тегам..."
                       style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                              border-radius:6px; border:1px solid var(--border);
                              background:var(--bg); color:var(--text);">
                <div id="modal-tags-list"
                     style="max-height:200px; overflow-y:auto; display:flex; flex-wrap:wrap;
                            gap:6px; padding:8px; background:var(--bg3);
                            border:1px solid var(--border); border-radius:6px;"></div>
            </div>

            <div id="modal-ai-tags-hint"
                 style="display:none; margin-top:12px; padding:8px;
                        background:var(--bg3); border:1px solid var(--accent);
                        border-radius:6px; font-size:12px;"></div>

            <div style="margin-top:20px; padding-top:16px; border-top:1px solid var(--border);">
                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Статус перехода</label>
                    <select id="modal-status" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                        <option value="accept" ${currentStatusValue === 'accept' ? 'selected' : ''}>Принять (Это ошибка, но исправлена будет позднее)</option>
                        <option value="falsepositive" ${currentStatusValue === 'falsepositive' ? 'selected' : ''}>Ложно (Анализ содержит ошибки)</option>
                        <option value="confirm" ${currentStatusValue === 'confirm' ? 'selected' : ''}>Подтвердить</option>
                        <option value="resolve" ${currentStatusValue === 'resolve' ? 'selected' : ''}>Исправлено</option>
                        <option value="wontfix" ${currentStatusValue === 'wontfix' ? 'selected' : ''}>Не исправлять</option>
                    </select>
                </div>
                <div>
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Новый комментарий (старые сохраняются в истории SonarQube)</label>
                    <textarea id="modal-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите новый комментарий..."></textarea>
                </div>
                <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                        <button id="modal-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM">Сгенерировать промпт</button>
                        <button id="modal-btn-ai-resolve" type="button" class="btn" title="Отправить промпт в Ollama">Получить резолюцию ИИ</button>
                        ${canReopen ? `<button id="modal-btn-reopen" class="btn btn-danger">Отменить комментарий (Reopen)</button>` : ''}
                    </div>
                    <div style="display:flex; gap:8px; align-items:center;">
                        <button id="modal-btn-cancel" class="btn">Отмена</button>
                        <button id="modal-btn-apply" class="btn btn-accent">Применить</button>
                        <button id="modal-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
                    </div>
                </div>
            </div>
        `;

        renderModalTagList();

        overlay.querySelector('#modal-tags-filter').addEventListener('input', (e) => {
            tagsQuery = e.target.value;
            renderModalTagList();
        });

        overlay.querySelector('#modal-tags-list').addEventListener('change', (e) => {
            if (e.target.classList.contains('tag-cb')) {
                if (e.target.checked) selectedTags.add(e.target.value);
                else                  selectedTags.delete(e.target.value);
            }
        });

        // Кнопка "Сгенерировать промпт" — из helpers, теперь вызывает API
        // (ruleName и snippet больше не передаём — сервер достаёт сам)
        bindStatusPromptButton(overlay, it, d.ruleName, d.snippet, ctx);

        bindOllamaResolveButton(overlay, {
            ctx,
            buildPrompt: (status, statusLabel) =>
                buildStatusCommentPrompt(ctx, it.key, status, statusLabel),
            commentSelector: '#modal-comment',
            onComplete: (fullResponse) => {
                const parsed = parseOllamaResponse(fullResponse);

                const statusSelect = overlay.querySelector('#modal-status');
                if (statusSelect && parsed.status) statusSelect.value = parsed.status;

                const commentTa = overlay.querySelector('#modal-comment');
                if (commentTa) commentTa.value = parsed.comment;

                if (parsed.tags && parsed.tags.length > 0) {
                    parsed.tags.forEach(tag => {
                        if (!availableTags.includes(tag)) availableTags.push(tag);
                        selectedTags.add(tag);
                    });
                    renderModalTagList();

                    const hint = overlay.querySelector('#modal-ai-tags-hint');
                    hint.style.display = 'block';
                    hint.innerHTML = `🤖 AI предлагает теги: <b>${esc(parsed.tags.join(', '))}</b>
                        <span style="color:var(--text-dim);"> (отмечены в списке выше)</span>`;
                }
            }
        });
        bindOllamaSettingsButton(overlay, ctx);

        overlay.querySelector('#modal-btn-apply').addEventListener('click', async () => {
            const status = overlay.querySelector('#modal-status').value;
            const comment = overlay.querySelector('#modal-comment').value;
            const tagsStr = [...selectedTags].join(',');
            const tagsChanged = tagsStr !== (it.tags || []).join(',');

            try {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', status);
                p.set('comment', comment);
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                if (res.success === false) throw new Error(res.error || 'Ошибка применения');

                if (tagsChanged) {
                    try {
                        const result = await setIssueTags(ctx, it.key, [...selectedTags]);
                        if (result.errors && result.errors.length > 0) {
                            ctx.toast('Теги не установлены: ' + result.errors[0].message, true);
                        }
                    } catch (e) {
                        console.warn('Не удалось применить теги:', e);
                        ctx.toast('Статус применён, но теги не установлены: ' + e.message, true);
                    }
                }

                ctx.toast('Успешно применено');
                close();
                ctx.refreshStatus?.();
                reloadCb();
            } catch (e) {
                ctx.toast(e.message, true);
            }
        });

        if (canReopen) {
            overlay.querySelector('#modal-btn-reopen').addEventListener('click', async () => {
                if (!confirm('Отменить комментарий (reopen)? Старые комментарии останутся в истории.')) return;
                try {
                    const p = new URLSearchParams(ctx.qs());
                    p.set('key', it.key);
                    p.set('transition', getReopenTransition(it.status));
                    p.set('dryRun', 'false');
                    const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                    if (res.success === false) throw new Error(res.error || 'reopen failed');
                    ctx.toast('Статус сброшен');
                    close();
                    ctx.refreshStatus?.();
                    reloadCb();
                } catch (e) {
                    ctx.toast(e.message, true);
                }
            });
        }

        overlay.querySelector('#modal-btn-cancel').addEventListener('click', close);

    } catch (e) {
        overlay.querySelector('#modal-body').innerHTML = `<div class="sev-HIGH">${esc(e.message)}</div>`;
    }
}