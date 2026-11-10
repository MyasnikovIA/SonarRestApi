import { esc } from '../common.js';

function facetTable(entries, label) {
    if (!entries.length) return `<div class="empty">—</div>`;
    return `<table><thead><tr><th>${label}</th><th class="num">count</th></tr></thead><tbody>${
        entries.map(([k, v]) =>
            `<tr><td>${esc(k)}</td><td class="num">${v}</td></tr>`).join('')
    }</tbody></table>`;
}

export const summary = {
    id: 'summary',
    title: 'Сводка (Summary)',
    filters: ['sev', 'qual'],

    async mount(el, ctx) {
        el.innerHTML = '<div class="empty">loading…</div>';
        try {
            const d = await ctx.api('/api/summary?' + ctx.qs());
            const rules = Object.entries(d.byRules || {}).sort((a, b) => b[1] - a[1]);
            const files = Object.entries(d.byFiles || {}).sort((a, b) => b[1] - a[1]);
            const sevs  = Object.entries(d.bySeverities || {});
            el.innerHTML = `
                <div class="card">
                    <h3>${esc(d.project)} · ${esc(d.severity)} / ${esc(d.quality)}</h3>
                    <div>Всего issues: <b>${d.total}</b>
                        <span class="tag">source: ${esc(d.source)}</span>
                        ${d.snapshotBuiltAt
                            ? `<span class="tag">built: ${esc(d.snapshotBuiltAt)}</span>`
                            : ''}
                    </div>
                </div>
                <div class="card"><h4>По правилам</h4>${facetTable(rules, 'rule')}</div>
                <div class="card"><h4>По файлам</h4>${facetTable(files, 'file')}</div>
                <div class="card"><h4>По severity</h4>${facetTable(sevs, 'severity')}</div>`;
        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};