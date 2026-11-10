export const status = {
    id: 'status',
    title: 'Статус (Status)',
    filters: [],

    async mount(el, ctx) {
        el.innerHTML = '<div class="empty">loading…</div>';
        try {
            const d = await ctx.api('/api/status');
            el.innerHTML = `<pre class="output">${ctx.esc(JSON.stringify(d, null, 2))}</pre>`;
        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">${ctx.esc(e.message)}</div></div>`;
        }
    },
};