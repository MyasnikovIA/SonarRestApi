import { esc, toast } from '../../../common.js';

export async function openBulkModal(issues, ctx, reloadCb) {
    if (!issues.length) { toast('Нет доступных issues для изменения', true); return; }

    let availableTags = [];
    try {
        const d = await ctx.api('/api/tags');
        availableTags = d.tags || [];
    } catch (e) {
        console.warn('Не удалось загрузить теги', e);
    }

    const currentTags = issues[0].tags || [];
    currentTags.forEach(t => {
        if (!availableTags.includes(t)) availableTags.push(t);
    });

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
                    <div style="max-height: 150px; overflow-y: auto; display: flex; flex-wrap: wrap; gap: 8px; padding: 8px; background: var(--bg3); border: 1px solid var(--border); border-radius: 6px;">
                        ${availableTags.map(t => `
                            <label style="display: flex; align-items: center; gap: 4px; font-size: 12px; cursor: pointer;">
                                <input type="checkbox" class="bulk-tag-cb" value="${esc(t)}" ${currentTags.includes(t) ? 'checked' : ''}>
                                ${esc(t)}
                            </label>
                        `).join('')}
                    </div>
                </div>
                <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                        <button id="bulk-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт">Сгенерировать промпт</button>
                    </div>
                    <div style="display:flex; gap:8px; align-items:center;">
                        <button id="bulk-cancel" class="btn">Отмена</button>
                        <button id="bulk-apply" class="btn btn-accent">Применить</button>
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

    overlay.querySelector('#bulk-apply').addEventListener('click', async () => {
        const status = overlay.querySelector('#bulk-status').value;
        const comment = overlay.querySelector('#bulk-comment').value;

        const checkedTags = Array.from(overlay.querySelectorAll('.bulk-tag-cb:checked')).map(cb => cb.value);
        const tagsStr = checkedTags.join(',');
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
                const keysStr = issues.map(it => it.key).join(',');
                await ctx.api('/api/set_tags?keys=' + encodeURIComponent(keysStr) + '&tags=' + encodeURIComponent(tagsStr), { method: 'POST' });
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
}