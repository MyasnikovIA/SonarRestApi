import { esc, toast, highlightCode, statusBadge } from '../common.js';

function getReopenTransition(status) {
    if (status === 'CONFIRMED') return 'unconfirm';
    return 'reopen';
}

/**
 * Расшифровка статуса для обработанных issues.
 * Для ACCEPTED SonarQube отдаёт status="RESOLVED" + resolution="WONTFIX"/"FALSE_POSITIVE"/"FIXED".
 * Рендерим бейдж по resolution — это точнее отражает суть.
 */
function acceptedBadge(it) {
    const res = (it.resolution || '').toUpperCase();
    if (res === 'WONTFIX') {
        return '<span class="status-tag" data-status="WONTFIX" title="RESOLVED / WONTFIX">✗ не исправлять</span>';
    }
    if (res === 'FALSE_POSITIVE') {
        return '<span class="status-tag" data-status="FALSE_POSITIVE" title="RESOLVED / FALSE_POSITIVE">✕ ложно</span>';
    }
    if (res === 'FIXED') {
        return '<span class="status-tag" data-status="FIXED" title="RESOLVED / FIXED">✔ исправлено</span>';
    }
    return statusBadge(it.issueStatus || it.status);
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
                                        <th style="width:30px; padding-left:16px;"></th>
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
                });

                updateBulkBtn();
            };

            const updateBulkBtn = () => {
                bulkBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                bulkBtn.textContent = `Reopen выбранные (${selectedKeys.size})`;
            };

            // ===== фильтры-чипы =====

            el.querySelectorAll('.facet-chip').forEach(chip => {
                chip.addEventListener('click', () => {
                    const kind = chip.dataset.kind;
                    const val = chip.dataset.val;
                    const isActive = chip.classList.contains('active');

                    // снять выделение со всех чипов этого типа
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

            bulkBtn.addEventListener('click', async () => {
                const keys = [...selectedKeys];
                if (!keys.length) return;
                if (!confirm(`Снять пометку (reopen) у ${keys.length} issues?`)) return;

                let success = 0;
                for (const key of keys) {
                    try {
                        const pp = new URLSearchParams(ctx.qs());
                        pp.set('key', key);
                        pp.set('transition', 'reopen');
                        pp.set('dryRun', 'false');
                        const res = await ctx.api('/api/mark?' + pp, { method: 'POST' });
                        if (res.success === false) throw new Error(res.error || 'Ошибка');
                        success++;
                    } catch (e) {
                        console.error('Reopen failed for ' + key, e);
                    }
                }
                ctx.toast(`Снято с ${success} из ${keys.length}`);
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

// ==================== MODAL ====================

async function openAcceptedModal(issue, ctx, onDone) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 800px;">
            <div class="modal-header">
                <h3 style="margin:0;">Детали обработанного issue</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body" id="acc-modal-body">
                <div class="empty">Загрузка...</div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.addEventListener('click', e => { if (e.target === overlay) close(); });

    try {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        p.set('issueKey', issue.key);
        const d = await ctx.api('/api/snippet?' + p);
        const it = d.issue || issue;

        const body = overlay.querySelector('#acc-modal-body');
        body.innerHTML = `
            <div style="margin-bottom:12px;">
                <b style="font-size:16px;">${esc(it.message)}</b>
                <div style="color:var(--text-dim); margin-top:4px;">${esc(d.ruleName || it.rule)}</div>
            </div>

            <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:16px; font-size:12px; color:var(--text-dim);">
                <div><b>Файл:</b> ${esc(it.component)}</div>
                <div><b>Строка:</b> ${it.line}</div>
                <div><b>Severity:</b> <span class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</span></div>
                <div><b>Status:</b> ${esc(it.status)}${it.resolution ? ' / ' + esc(it.resolution) : ''}</div>
            </div>

            ${(d.snippet && d.snippet.length)
                ? `<pre class="code" style="max-height:400px; overflow:auto;">${highlightCode(d.snippet.join('\n'))}</pre>`
                : `<div class="empty">Код недоступен</div>`}

            <div style="margin-top:20px; padding-top:16px; border-top:1px solid var(--border); display:flex; justify-content:flex-end; gap:8px;">
                <button id="acc-modal-reopen" class="btn btn-danger">Снять пометку (Reopen)</button>
                <button id="acc-modal-close" class="btn">Закрыть</button>
            </div>
        `;

        overlay.querySelector('#acc-modal-close').addEventListener('click', close);
        overlay.querySelector('#acc-modal-reopen').addEventListener('click', async () => {
            if (!confirm('Снять пометку с этого issue?')) return;
            try {
                const pp = new URLSearchParams(ctx.qs());
                pp.set('key', it.key);
                pp.set('transition', getReopenTransition(it.status));
                pp.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + pp, { method: 'POST' });
                if (res.success === false) throw new Error(res.error || 'reopen failed');
                ctx.toast('Пометка снята');
                close();
                ctx.refreshStatus?.();
                if (onDone) onDone();
            } catch (e) {
                ctx.toast(e.message, true);
            }
        });
    } catch (e) {
        overlay.querySelector('#acc-modal-body').innerHTML =
            `<div class="sev-HIGH">${esc(e.message)}</div>`;
    }
}