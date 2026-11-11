import { esc, toast } from '../../../common.js';
import { bindOllamaResolveButton, bindOllamaSettingsButton } from '../../../ollama.js';
import { parseOllamaResponse } from '../parse-response.js';
import { buildBulkStatusCommentPrompt } from '../prompts.js';
import {
    PRIORITY_TAGS,
    fetchAvailableTags,
    buildOrderedTags,
    renderTagCheckboxes,
    setIssueTags,
    applyParsedTags
} from '../tags.js';

export function openBulkModal(issues, ctx, reloadCb) {
    if (!issues.length) { toast('Нет доступных issues для изменения', true); return; }

    (async () => {
        const availableTags = await fetchAvailableTags(ctx);

        const currentTags = issues[0].tags || [];
        currentTags.forEach(t => { if (!availableTags.includes(t)) availableTags.push(t); });

        const initialTags = [...currentTags];
        const selectedTags = new Set(initialTags);
        let tagsQuery = '';

        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        overlay.innerHTML = `
            <div class="modal-content" style="max-width: 600px;">
                <div class="modal-header">
                    <h3 style="margin:0;">Массовое действие</h3>
                    <button class="modal-close">&times;</button>
                </div>
                <div class="modal-body">
                    <p>Выбрано ошибок: <b>${issues.length}</b></p>
                    <div style="margin-bottom:12px;">
                        <label style="display:block; font-size:12px; margin-bottom:4px;">Статус перехода</label>
                        <select id="bulk-status" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                            <option value="accept">Принять (Это ошибка, но исправлена будет позднее)</option>
                            <option value="falsepositive">Ложно (Анализ содержит ошибки)</option>
                            <option value="confirm">Подтвердить</option>
                            <option value="resolve">Исправлено</option>
                            <option value="wontfix">Не исправлять</option>
                        </select>
                    </div>
                    <div style="margin-bottom:12px;">
                        <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                        <textarea id="bulk-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
                    </div>
                    <div style="margin-bottom:16px;">
                        <label style="display:block; font-size:12px; margin-bottom:4px;">Теги</label>
                        <input type="text" id="bulk-tags-filter" placeholder="Фильтр по тегам..."
                               style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                                      border-radius:6px; border:1px solid var(--border);
                                      background:var(--bg); color:var(--text);">
                        <div id="bulk-tags-list"
                             style="max-height: 150px; overflow-y: auto; display: flex; flex-wrap: wrap;
                                    gap: 6px; padding: 8px; background: var(--bg3);
                                    border: 1px solid var(--border); border-radius: 6px;"></div>
                    </div>
                    <div id="bulk-ai-tags-hint" style="display:none; margin-bottom:12px; padding:8px; background:var(--bg3); border:1px solid var(--accent); border-radius:6px; font-size:12px;"></div>
                    <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                        <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                            <button id="bulk-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM">Сгенерировать промпт</button>
                            <button id="bulk-btn-ai-resolve" type="button" class="btn" title="Получить резолюцию от Ollama">Получить резолюцию ИИ</button>
                        </div>
                        <div style="display:flex; gap:8px; align-items:center;">
                            <button id="bulk-cancel" class="btn">Отмена</button>
                            <button id="bulk-apply" class="btn btn-accent">Применить</button>
                            <button id="bulk-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
                        </div>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(overlay);

        const close = () => overlay.remove();
        overlay.querySelector('.modal-close').addEventListener('click', close);
        overlay.querySelector('#bulk-cancel').addEventListener('click', close);
        overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

        function renderBulkTagList() {
            const ordered = buildOrderedTags(availableTags, initialTags, PRIORITY_TAGS, tagsQuery);
            renderTagCheckboxes(overlay.querySelector('#bulk-tags-list'), ordered, selectedTags);
        }

        renderBulkTagList();

        overlay.querySelector('#bulk-tags-filter').addEventListener('input', (e) => {
            tagsQuery = e.target.value;
            renderBulkTagList();
        });

        overlay.querySelector('#bulk-tags-list').addEventListener('change', (e) => {
            if (e.target.classList.contains('tag-cb')) {
                if (e.target.checked) selectedTags.add(e.target.value);
                else                  selectedTags.delete(e.target.value);
            }
        });

        overlay.querySelector('#bulk-btn-gen-prompt').addEventListener('click', async () => {
            const status = overlay.querySelector('#bulk-status').value;
            const statusLabel = overlay.querySelector('#bulk-status').selectedOptions[0].textContent.trim();

            let prompt;
            try {
                prompt = await buildBulkStatusCommentPrompt(ctx, issues, status, statusLabel);
            } catch (e) {
                toast('Не удалось построить промпт: ' + e.message, true);
                return;
            }

            try {
                await navigator.clipboard.writeText(prompt);
                toast('Промпт скопирован в буфер обмена');
            } catch (e) {
                const ta = overlay.querySelector('#bulk-comment');
                ta.value = prompt; ta.focus(); ta.select();
                toast('Промпт вставлен в поле комментария', true);
            }
        });

        bindOllamaResolveButton(overlay, {
            ctx,
            buildPrompt: (status, statusLabel) =>
                buildBulkStatusCommentPrompt(ctx, issues, status, statusLabel),
            commentSelector: '#bulk-comment',
            btnSelector: '#bulk-btn-ai-resolve',
            onComplete: (fullResponse) => {
                const parsed = parseOllamaResponse(fullResponse);

                const statusSel = overlay.querySelector('#bulk-status');
                if (statusSel && parsed.status) statusSel.value = parsed.status;

                const commentTa = overlay.querySelector('#bulk-comment');
                if (commentTa) commentTa.value = parsed.comment;

                if (parsed.tags && parsed.tags.length > 0) {
                    const { removed } = applyParsedTags(selectedTags, parsed.tags, availableTags);
                    renderBulkTagList();

                    const hint = overlay.querySelector('#bulk-ai-tags-hint');
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
        bindOllamaSettingsButton(overlay, ctx, '#bulk-btn-ollama-settings');

        overlay.querySelector('#bulk-apply').addEventListener('click', async () => {
            const status = overlay.querySelector('#bulk-status').value;
            const comment = overlay.querySelector('#bulk-comment').value;
            const tagsStr = [...selectedTags].join(',');
            const tagsChanged = tagsStr !== (issues[0].tags || []).join(',');

            const btn = overlay.querySelector('#bulk-apply');
            btn.disabled = true;
            btn.textContent = 'Применение...';

            let success = 0;
            for (const it of issues) {
                try {
                    const p = new URLSearchParams(ctx.qs());
                    p.set('key', it.key);
                    p.set('transition', status);
                    p.set('comment', comment);
                    p.set('dryRun', 'false');
                    const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                    if (res.success === false) throw new Error(res.error || 'Ошибка');
                    success++;
                } catch (e) {
                    console.error('Failed for ' + it.key, e);
                }
                btn.textContent = `Применение ${success}/${issues.length}...`;
            }

            if (tagsChanged) {
                try {
                    const keys = issues.map(it => it.key);
                    const result = await setIssueTags(ctx, keys, [...selectedTags]);
                    if (result.errors && result.errors.length > 0) {
                        const failed = result.errors.length;
                        const ok = result.success;
                        ctx.toast(`Теги: ${ok}/${result.total} успешно, ${failed} с ошибкой`, true);
                    }
                } catch (e) {
                    console.error('Ошибка применения тегов', e);
                    ctx.toast('Статусы применены, но теги не обновились: ' + e.message, true);
                }
            }

            ctx.toast(`Применено к ${success} из ${issues.length}`);
            close();
            ctx.refreshStatus?.();
            if (reloadCb) reloadCb();
        });
    })();
}