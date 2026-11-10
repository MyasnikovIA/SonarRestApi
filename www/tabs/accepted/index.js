import { esc, statusBadge } from '../../common.js';
import { acceptedBadge, runBulkWithProgress } from './helpers.js';
import { showContextMenu } from './context-menu.js';
import { openAcceptedModal } from './modals/accepted-modal.js';

function renderFacetChips(kind, values) {
    const entries = Object.entries(values).filter(([, n]) => n > 0);
    if (!entries.length) return '';
    return entries.map(([val, n]) => `
        <span class="facet-chip" data-kind="${esc(kind)}" data-val="${esc(val)}"
              style="cursor:pointer; padding:2px 8px; border-radius:10px;
                     background:var(--bg3); border:1px solid var(--border);
                     font-size:11px; user-select:none;">
            ${esc(val)} <b>${n}</b>
        </span>
    `).join('');
}

export const accepted = {
    id: 'accepted',
    title: 'Обработанные (Accepted)',
    filters: [],

    async mount(el, ctx) {
        if (!ctx.state.project) {
            el.innerHTML = '<div class="empty">Сначала задайте project в шапке</div>';
            return;
        }

        el.innerHTML = '<div class="empty">loading…</div>';
        try {
            const p = new URLSearchParams({ project: ctx.state.project, facets: 'true' });
            const d = await ctx.api('/api/accepted?' + p);

            const issues       = d.issues || [];
            const facets       = d.facets || {};
            const severities   = facets.severities || {};
            const types        = facets.types || {};
            const resolutions  = facets.resolutions || {};

            let filterText       = '';
            let severityFilter   = '';
            let typeFilter       = '';
            let resolutionFilter = '';
            let selectedKeys     = new Set();

            el.innerHTML = `
                <div class="pf-header-sticky">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                        <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
                            <h3 style="margin:0;">${esc(d.project)} · Обработанные: ${d.total}</h3>
                            ${renderFacetChips('sev', severities)}
                            ${renderFacetChips('type', types)}
                            ${renderFacetChips('res', resolutions)}
                        </div>
                        <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                            <input type="text" id="acc-filter" placeholder="Фильтр по ключу, файлу или сообщению..."
                                   style="padding:6px 10px; border-radius:6px; border:1px solid var(--border); background:var(--bg); color:var(--text); width:250px;">
                            <button id="acc-bulk-reopen" class="btn btn-danger" style="display:none;">Reopen выбранные (0)</button>
                        </div>
                    </div>
                </div>
                <div id="acc-list"></div>
            `;

            const listEl      = el.querySelector('#acc-list');
            const filterInput = el.querySelector('#acc-filter');
            const bulkBtn     = el.querySelector('#acc-bulk-reopen');

            const applyFilters = () => {
                const q = filterText.toLowerCase();
                return issues.filter(it => {
                    if (severityFilter && (it.impactSeverity || it.severity) !== severityFilter) return false;
                    if (typeFilter && it.type !== typeFilter) return false;
                    if (resolutionFilter && it.resolution !== resolutionFilter) return false;
                    if (!q) return true;
                    return (it.key || '').toLowerCase().includes(q)
                        || (it.message || '').toLowerCase().includes(q)
                        || (it.rule || '').toLowerCase().includes(q)
                        || (it.component || '').toLowerCase().includes(q);
                });
            };

            const renderList = () => {
                const filtered = applyFilters();
                if (!filtered.length) {
                    listEl.innerHTML = '<div class="empty">Нет обработанных issues по фильтру</div>';
                    updateBulkBtn();
                    return;
                }

                listEl.innerHTML = `
                    <div class="card" style="padding:0; overflow:hidden;">
                        <div style="overflow-x:auto">
                            <table>
                                <thead>
                                    <tr>
                                        <th style="width:30px; padding-left:16px;">
                                            <input type="checkbox" id="acc-master-checkbox" title="Выделить всё" style="cursor:pointer;">
                                        </th>
                                        <th>key</th>
                                        <th>sev</th>
                                        <th>component:line</th>
                                        <th>rule</th>
                                        <th>message</th>
                                        <th>status</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    ${filtered.map(it => `
                                        <tr class="pf-issue-row" data-key="${esc(it.key)}" style="cursor:pointer;">
                                            <td style="padding-left:16px;">
                                                <input type="checkbox" class="acc-issue-checkbox" data-key="${esc(it.key)}" ${selectedKeys.has(it.key) ? 'checked' : ''}>
                                            </td>
                                            <td><code>${esc(it.key)}</code></td>
                                            <td class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</td>
                                            <td>${esc(it.component)}:${it.line}</td>
                                            <td>${esc(it.rule)}</td>
                                            <td>${esc(it.message)}</td>
                                            <td>${acceptedBadge(it)}</td>
                                        </tr>
                                    `).join('')}
                                </tbody>
                            </table>
                        </div>
                    </div>
                `;

                const currentMasterCb = listEl.querySelector('#acc-master-checkbox');
                const allFilteredKeys = filtered.map(it => it.key);
                if (allFilteredKeys.length > 0 && allFilteredKeys.every(k => selectedKeys.has(k))) {
                    currentMasterCb.checked = true;
                    currentMasterCb.indeterminate = false;
                } else if (allFilteredKeys.some(k => selectedKeys.has(k))) {
                    currentMasterCb.checked = false;
                    currentMasterCb.indeterminate = true;
                } else {
                    currentMasterCb.checked = false;
                    currentMasterCb.indeterminate = false;
                }

                currentMasterCb.addEventListener('change', e => {
                    const isChecked = e.target.checked;
                    if (isChecked) {
                        filtered.forEach(it => selectedKeys.add(it.key));
                    } else {
                        filtered.forEach(it => selectedKeys.delete(it.key));
                    }
                    renderList();
                    updateBulkBtn();
                });

                listEl.querySelectorAll('.acc-issue-checkbox').forEach(cb => {
                    cb.addEventListener('change', e => {
                        if (e.target.checked) selectedKeys.add(e.target.dataset.key);
                        else selectedKeys.delete(e.target.dataset.key);
                        updateBulkBtn();
                    });
                });

                listEl.querySelectorAll('.pf-issue-row').forEach(row => {
                    row.addEventListener('dblclick', e => {
                        if (e.target.type === 'checkbox') return;
                        const it = filtered.find(x => x.key === row.dataset.key);
                        if (it) openAcceptedModal(it, ctx, () => accepted.mount(el, ctx));
                    });

                    row.addEventListener('contextmenu', e => {
                        e.preventDefault();
                        const key = row.dataset.key;
                        const issue = filtered.find(x => x.key === key);

                        let targetIssues = [];
                        if (selectedKeys.size > 0) {
                            targetIssues = issues.filter(it => selectedKeys.has(it.key));
                        } else {
                            targetIssues = [issue];
                        }

                        showContextMenu(e.pageX, e.pageY, issue, targetIssues, ctx, () => {
                            accepted.mount(el, ctx);
                        });
                    });
                });

                updateBulkBtn();
            };

            const updateBulkBtn = () => {
                bulkBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                bulkBtn.textContent = `Reopen выбранные (${selectedKeys.size})`;
            };

            el.querySelectorAll('.facet-chip').forEach(chip => {
                chip.addEventListener('click', () => {
                    const kind = chip.dataset.kind;
                    const val = chip.dataset.val;
                    const isActive = chip.classList.contains('active');

                    el.querySelectorAll(`.facet-chip[data-kind="${kind}"]`).forEach(c => c.classList.remove('active'));

                    if (isActive) {
                        if (kind === 'sev')  severityFilter = '';
                        if (kind === 'type') typeFilter = '';
                        if (kind === 'res')  resolutionFilter = '';
                    } else {
                        chip.classList.add('active');
                        if (kind === 'sev')  severityFilter = val;
                        if (kind === 'type') typeFilter = val;
                        if (kind === 'res')  resolutionFilter = val;
                    }
                    renderList();
                });
            });

            filterInput.addEventListener('input', e => {
                filterText = e.target.value;
                renderList();
            });

            // Кнопка массового Reopen — с прогресс-модалкой
            bulkBtn.addEventListener('click', async () => {
                const keys = [...selectedKeys];
                if (!keys.length) return;
                if (!confirm(`Снять пометку (reopen) у ${keys.length} issues?`)) return;

                const targetIssues = issues.filter(it => selectedKeys.has(it.key));

                await runBulkWithProgress({
                    title: 'Снятие пометок (Reopen)',
                    issues: targetIssues,
                    perItemAction: async (it, _i, signal) => {
                        const pp = new URLSearchParams(ctx.qs());
                        pp.set('key', it.key);
                        pp.set('transition', 'reopen');
                        pp.set('dryRun', 'false');
                        const res = await ctx.api('/api/mark?' + pp, { method: 'POST', signal });
                        if (res.success === false) throw new Error(res.error || 'Ошибка');
                    }
                });

                selectedKeys.clear();
                ctx.refreshStatus?.();
                accepted.mount(el, ctx);
            });

            renderList();
        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};