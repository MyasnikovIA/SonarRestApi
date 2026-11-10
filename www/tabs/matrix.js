import { esc } from '../common.js';

export const matrix = {
    id: 'matrix',
    title: 'Матрица (Matrix)',
    filters: ['sev', 'qual'],

    async mount(el, ctx) {
        el.innerHTML = '<div class="empty">loading…</div>';
        try {
            const d = await ctx.api('/api/matrix?' + ctx.qs());
            const rules = d.rules || [];
            if (!rules.length) { el.innerHTML = '<div class="empty">Нет данных</div>'; return; }

            const head = `<tr><th>file</th>${
                rules.map(r => `<th class="num">${esc(r.replace(/^php:/, ''))}</th>`).join('')
            }<th class="num">Σ</th></tr>`;

            const rows = (d.rows || []).map(row => {
                const cells = rules.map(r =>
                    `<td class="num">${row.counts[r] || ''}</td>`).join('');
                return `<tr><td>${esc(row.file)}</td>${cells}<td class="num"><b>${row.total}</b></td></tr>`;
            }).join('');

            el.innerHTML = `
                <div class="card">
                    <h3>Matrix · ${esc(d.project)} · ${esc(d.severity)}/${esc(d.quality)} · ${d.total} issues</h3>
                    <div style="overflow-x:auto"><table>${head}${rows}</table></div>
                </div>`;
        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};