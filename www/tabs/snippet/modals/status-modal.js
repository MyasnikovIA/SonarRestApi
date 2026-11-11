import { esc } from '../../../common.js';
import { bindOllamaResolveButton, bindOllamaSettingsButton } from '../../../ollama.js';
import { getMappedStatus } from '../status.js';
import { parseOllamaResponse } from '../parse-response.js';
import { buildStatusCommentPrompt } from '../prompts.js';
import { bindStatusPromptButton } from '../helpers.js';
import {
    PRIORITY_TAGS,
    fetchAvailableTags,
    buildOrderedTags,
    renderTagCheckboxes,
    setIssueTags,
    applyParsedTags
} from '../tags.js';

export async function openStatusModal(issue, ctx, onDone) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 640px;">
            <div class="modal-header">
                <h3 style="margin:0;">Изменение статуса</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body">
                <div class="empty">Загрузка...</div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    try {
        // Свежие данные issue + доступные теги — параллельно
        const [d, availableTags] = await Promise.all([
            (async () => {
                const p = new URLSearchParams();
                if (ctx.state.project) p.set('project', ctx.state.project);
                p.set('issueKey', issue.key);
                return ctx.api('/api/snippet?' + p);
            })(),
            fetchAvailableTags(ctx)
        ]);

        const it = d.issue || issue;
        const currentStatusValue = getMappedStatus(it.status);

        const currentTags = it.tags || [];
        currentTags.forEach(t => { if (!availableTags.includes(t)) availableTags.push(t); });

        const initialTags = [...currentTags];
        const selectedTags = new Set(initialTags);
        let tagsQuery = '';

        function renderModalTagList() {
            const ordered = buildOrderedTags(availableTags, initialTags, PRIORITY_TAGS, tagsQuery);
            renderTagCheckboxes(overlay.querySelector('#modal-tags-list'), ordered, selectedTags);
        }

        const body = overlay.querySelector('.modal-body');
        body.innerHTML = `
            <p style="margin-top:0; color:var(--text-dim);">
                Выберите статус и оставьте комментарий для:<br>
                <b>${esc(it.key)}</b> — ${esc(it.message)}
            </p>

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

            <div style="margin-bottom:12px;">
                <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                <textarea id="modal-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
            </div>

            <div style="margin-bottom:12px;">
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
                 style="display:none; margin-bottom:12px; padding:8px;
                        background:var(--bg3); border:1px solid var(--accent);
                        border-radius:6px; font-size:12px;"></div>

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
                    const { removed } = applyParsedTags(selectedTags, parsed.tags, availableTags);
                    renderModalTagList();

                    const hint = overlay.querySelector('#modal-ai-tags-hint');
                    hint.style.display = 'block';
                    let msg = `🤖 AI предлагает теги: <b>${esc(parsed.tags.join(', '))}</b>`;
                    if (removed.length > 0) {
                        msg += `<br><span style="color:var(--warn);">Снято (взаимоисключающие): ${esc(removed.join(', '))}</span>`;
                    }
                    msg += `<br><span style="color:var(--text-dim);"> (отмечены в списке выше)</span>`;
                    hint.innerHTML = msg;
                }
            }
        });
        bindOllamaSettingsButton(overlay, ctx);

        overlay.querySelector('#modal-cancel').addEventListener('click', close);

        overlay.querySelector('#modal-apply').addEventListener('click', async () => {
            const status = overlay.querySelector('#modal-status').value;
            const comment = overlay.querySelector('#modal-comment').value;
            const tagsStr = [...selectedTags].join(',');
            const tagsChanged = tagsStr !== (it.tags || []).join(',');

            const btn = overlay.querySelector('#modal-apply');
            btn.disabled = true;
            btn.textContent = 'Применение...';

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
                if (onDone) onDone();
            } catch (e) {
                ctx.toast(e.message, true);
                btn.disabled = false;
                btn.textContent = 'Применить';
            }
        });

    } catch (e) {
        overlay.querySelector('.modal-body').innerHTML =
            `<div class="sev-HIGH">Ошибка: ${esc(e.message)}</div>`;
    }
}