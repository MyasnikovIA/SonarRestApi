import { esc, toast, showTagEditor } from '../../../common.js';
import { bindOllamaResolveButton, bindOllamaSettingsButton } from '../../../ollama.js';
import { buildBulkStatusCommentPrompt } from '../prompts.js';
import { copyToClipboard } from '../helpers.js';

export function openBulkModal(issues, ctx, reloadCb) {
    if (!issues.length) { toast('Нет доступных issues для изменения', true); return; }
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
                <div style="margin-bottom:16px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                    <textarea id="bulk-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
                </div>
                <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                        <button id="bulk-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM для всей группы ошибок">Сгенерировать промпт</button>
                        <button id="bulk-btn-ai-resolve" type="button" class="btn" title="Отправить промпт в Ollama и вставить ответ в поле «Комментарий»">Получить резолюцию ИИ</button>
                        <button id="bulk-btn-tags" type="button" class="btn">🏷️ Изменить теги</button>
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

    overlay.querySelector('#bulk-btn-tags').addEventListener('click', () => {
        showTagEditor(issues, ctx, () => {
            close();
            reloadCb();
        });
    });

    overlay.querySelector('#bulk-btn-gen-prompt').addEventListener('click', async () => {
        const status = overlay.querySelector('#bulk-status').value;
        const statusLabel = overlay.querySelector('#bulk-status').selectedOptions[0].textContent.trim();

        const btn = overlay.querySelector('#bulk-btn-gen-prompt');
        const originalText = btn.textContent;
        btn.textContent = 'Подготовка...';
        btn.disabled = true;

        try {
            const prompt = await buildBulkStatusCommentPrompt(issues, status, statusLabel, ctx);
            const ok = await copyToClipboard(prompt);

            if (ok) {
                toast('Промпт для группы скопирован в буфер обмена');
            } else {
                const ta = overlay.querySelector('#bulk-comment');
                if (ta) {
                    ta.value = prompt;
                    ta.focus();
                    ta.select();
                }
                toast('Промпт вставлен в поле комментария', true);
            }
        } catch (e) {
            toast(e.message, true);
        } finally {
            btn.textContent = originalText;
            btn.disabled = false;
        }
    });

    bindOllamaResolveButton(overlay, {
        ctx,
        buildPrompt: (status, statusLabel) => buildBulkStatusCommentPrompt(issues, status, statusLabel, ctx),
        commentSelector: '#bulk-comment',
        btnSelector: '#bulk-btn-ai-resolve'
    });

    bindOllamaSettingsButton(overlay, ctx, '#bulk-btn-ollama-settings');

    overlay.querySelector('#bulk-apply').addEventListener('click', async () => {
        const status = overlay.querySelector('#bulk-status').value;
        const comment = overlay.querySelector('#bulk-comment').value;

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

        ctx.toast(`Применено к ${success} из ${issues.length}`);
        close();
        ctx.refreshStatus?.();
        reloadCb();
    });
}