import { esc } from '../common.js';

export const mark = {
    id: 'mark',
    title: 'Пометки (Mark / Cancel)',
    filters: ['sev', 'qual'],

    async mount(el, ctx) {
        // Форму строим один раз — при переключении вкладки не теряем введённые значения
        if (el.dataset.mounted === '1') return;

        el.innerHTML = `
            <div class="mark-grid">
                <label>transition
                    <select id="transition">
                        <option>accept</option>
                        <option>falsepositive</option>
                        <option>confirm</option>
                        <option>resolve</option>
                        <option>wontfix</option>
                        <option>reopen</option>
                    </select>
                </label>
                <label>key <small>(пусто — массово по фильтру)</small>
                    <input id="mark-key" type="text" placeholder="issue key">
                </label>
                <label>comment
                    <input id="mark-comment" type="text" placeholder="необязательно">
                </label>
            </div>
            <div class="actions">
                <button id="btn-mark-dry"  class="btn">Preview (dryRun)</button>
                <button id="btn-mark-apply" class="btn btn-danger">Apply</button>
                <button id="btn-cancel"    class="btn btn-danger">Cancel (reopen)</button>
            </div>
            <pre id="mark-result" class="output"></pre>`;

        const $ = (id) => el.querySelector('#' + id);

        async function doMark(dryRun) {
            const p = new URLSearchParams(ctx.qs());
            p.set('transition', $('transition').value);
            p.set('dryRun', dryRun);
            const key = $('mark-key').value.trim();
            const comment = $('mark-comment').value;
            if (key) p.set('key', key);
            if (comment) p.set('comment', comment);

            $('mark-result').textContent = 'sending…';
            try {
                const data = await ctx.api('/api/mark?' + p, { method: 'POST' });
                $('mark-result').textContent = JSON.stringify(data, null, 2);
                if (!dryRun) {
                    ctx.toast('Mark applied: ' + $('transition').value);
                    ctx.refreshStatus?.();
                }
            } catch (e) {
                $('mark-result').textContent = 'error: ' + e.message;
                ctx.toast(e.message, true);
            }
        }

        async function doCancel() {
            const key = $('mark-key').value.trim();
            if (!key) { ctx.toast('Введите key для cancel', true); return; }
            if (!confirm(`Reopen ${key}?`)) return;

            const p = new URLSearchParams(ctx.qs());
            p.set('key', key);
            p.set('dryRun', 'false');

            $('mark-result').textContent = 'sending…';
            try {
                const data = await ctx.api('/api/cancel?' + p, { method: 'POST' });
                $('mark-result').textContent = JSON.stringify(data, null, 2);
                ctx.toast('Reopen applied');
                ctx.refreshStatus?.();
            } catch (e) {
                $('mark-result').textContent = 'error: ' + e.message;
                ctx.toast(e.message, true);
            }
        }

        $('btn-mark-dry').addEventListener('click', () => doMark(true));
        $('btn-mark-apply').addEventListener('click', () => doMark(false));
        $('btn-cancel').addEventListener('click', doCancel);

        el.dataset.mounted = '1';
    },
};