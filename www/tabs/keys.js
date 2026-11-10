import { esc } from '../common.js';

export const keys = {
    id: 'keys',
    title: 'Keys',
    filters: ['sev', 'qual'],

    async mount(el, ctx) {
        el.innerHTML = '<div class="empty">loading…</div>';
        try {
            const d = await ctx.api('/api/keys?' + ctx.qs());
            const list = d.keys || [];
            if (!list.length) { el.innerHTML = '<div class="empty">Нет keys</div>'; return; }

            el.innerHTML = `
                <div class="card">
                    <h3>${esc(d.project)} · ${esc(d.severity)}/${esc(d.quality)} · ${d.total} keys</h3>
                    <pre class="code">${esc(list.join('\n'))}</pre>
                </div>`;
        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};