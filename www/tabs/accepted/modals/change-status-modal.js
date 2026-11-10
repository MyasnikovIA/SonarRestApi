export function showChangeStatusModal(issues, ctx, reloadCb) {
    if (!issues.length) return;
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 400px;">
            <div class="modal-header">
                <h3 style="margin:0;">Изменить статус</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body">
                <p>Выбрано ошибок: <b>${issues.length}</b></p>
                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Новый статус</label>
                    <select id="status-select" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                        <option value="accept">Принять (Это ошибка, но исправлена будет позднее)</option>
                        <option value="falsepositive">Ложно (Анализ содержит ошибки)</option>
                        <option value="confirm">Подтвердить</option>
                        <option value="resolve">Исправлено</option>
                        <option value="wontfix">Не исправлять</option>
                    </select>
                </div>
                <div style="display:flex; justify-content:flex-end; gap:8px;">
                    <button id="status-cancel" class="btn">Отмена</button>
                    <button id="status-apply" class="btn btn-accent">Применить</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.querySelector('#status-cancel').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    overlay.querySelector('#status-apply').addEventListener('click', async () => {
        const status = overlay.querySelector('#status-select').value;
        const btn = overlay.querySelector('#status-apply');
        btn.disabled = true;
        btn.textContent = 'Применение...';

        let success = 0;
        for (const it of issues) {
            try {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', status);
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                if (res.success === false) throw new Error(res.error || 'Ошибка');
                success++;
            } catch (e) {
                console.error('Failed for ' + it.key, e);
            }
        }

        ctx.toast(`Применено к ${success} из ${issues.length}`);
        close();
        ctx.refreshStatus?.();
        if (reloadCb) reloadCb();
    });
}