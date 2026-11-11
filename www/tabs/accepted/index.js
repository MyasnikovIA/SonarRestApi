import { esc, toast } from '../../common.js';
import { acceptedBadge, runBulkWithProgress } from './helpers.js';
import { showContextMenu } from './context-menu.js';
import { openAcceptedModal } from './modals/accepted-modal.js';
import { exportAcceptedToMarkdown } from './export-md.js';

// ==================== FACET CHIPS ====================

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

// ==================== TAB ====================

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

            // ---------- collect all unique tags ----------
            const tagCounts = new Map();
            issues.forEach(it => {
                (it.tags || []).forEach(t => {
                    tagCounts.set(t, (tagCounts.get(t) || 0) + 1);
                });
            });
            const allTags = [...tagCounts.entries()]
                .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));

            // ---------- filter state ----------
            let filterText       = '';
            let severityFilter   = '';
            let typeFilter       = '';
            let resolutionFilter = '';
            const selectedKeys   = new Set();
            const selectedTags   = new Set();
            let tagSearchQuery   = '';

            el.innerHTML = `
                <div class="pf-header-sticky">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                        <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
                            <input type="checkbox" id="acc-master-checkbox" title="Выделить всё"
                                   style="width:18px; height:18px; cursor:pointer;">
                            <h3 style="margin:0;">${esc(d.project)} · Обработанные: ${d.total}</h3>
                            ${renderFacetChips('sev', severities)}
                            ${renderFacetChips('type', types)}
                            ${renderFacetChips('res', resolutions)}
                        </div>
                        <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                            <details id="acc-tags-details" style="position:relative;">
                                <summary id="acc-tags-btn" class="btn"
                                         style="cursor:pointer; list-style:none; user-select:none;">
                                    🏷️ Теги (<span id="acc-tags-count">0</span>)
                                </summary>
                                <div style="position:absolute; z-index:100; right:0; top:100%; margin-top:4px;
                                            background:var(--bg); border:1px solid var(--border);
                                            border-radius:6px; padding:8px; min-width:280px;
                                            max-height:340px; box-shadow:0 4px 12px rgba(0,0,0,0.15);">
                                    <input type="text" id="acc-tags-search" placeholder="Поиск по тегам..."
                                           style="width:100%; box-sizing:border-box; padding:6px 10px;
                                                  margin-bottom:8px; border-radius:6px;
                                                  border:1px solid var(--border);
                                                  background:var(--bg3); color:var(--text);">
                                    <div id="acc-tags-list"
                                         style="max-height:220px; overflow-y:auto; display:flex;
                                                flex-direction:column; gap:2px; margin-bottom:8px;"></div>
                                    <div style="display:flex; justify-content:space-between; gap:8px;">
                                        <button id="acc-tags-clear" class="btn" style="flex:1;">Сбросить</button>
                                        <button id="acc-tags-close" class="btn" style="flex:1;">Готово</button>
                                    </div>
                                </div>
                            </details>
                            <input type="text" id="acc-filter"
                                   placeholder="Фильтр по ключу, файлу или сообщению..."
                                   style="padding:6px 10px; border-radius:6px; border:1px solid var(--border);
                                          background:var(--bg); color:var(--text); width:250px;">
                            <button id="acc-export-md" class="btn" title="Выгрузить отфильтрованный список в Markdown">
                                📥 Экспорт MD
                            </button>
                            <button id="acc-bulk-reopen" class="btn btn-danger" style="display:none;">
                                Reopen выбранные (0)
                            </button>
                        </div>
                    </div>
                </div>
                <div id="acc-list"></div>
            `;

            const listEl       = el.querySelector('#acc-list');
            const filterInput  = el.querySelector('#acc-filter');
            const exportBtn    = el.querySelector('#acc-export-md');
            const bulkBtn      = el.querySelector('#acc-bulk-reopen');
            const masterCb     = el.querySelector('#acc-master-checkbox');
            const tagsDetails  = el.querySelector('#acc-tags-details');
            const tagsCountEl  = el.querySelector('#acc-tags-count');
            const tagsSearchEl = el.querySelector('#acc-tags-search');
            const tagsListEl   = el.querySelector('#acc-tags-list');
            const tagsClearBtn = el.querySelector('#acc-tags-clear');
            const tagsCloseBtn = el.querySelector('#acc-tags-close');

            // ==================== TAG LIST ====================

            const updateTagsCount = () => {
                tagsCountEl.textContent = selectedTags.size;
            };

            const renderTagList = () => {
                const q = tagSearchQuery.trim().toLowerCase();
                const visible = allTags.filter(([t]) => !q || t.toLowerCase().includes(q));

                if (!visible.length) {
                    tagsListEl.innerHTML = `<div style="color:var(--text-dim); font-size:12px; padding:4px;">
                        ${q ? 'Нет тегов по фильтру' : 'Нет тегов'}</div>`;
                    return;
                }

                tagsListEl.innerHTML = visible.map(([t, n]) => `
                    <label style="display:flex; align-items:center; gap:6px; font-size:12px;
                                  cursor:pointer; padding:4px 6px; border-radius:4px;
                                  background:${selectedTags.has(t) ? 'var(--bg3)' : 'transparent'};">
                        <input type="checkbox" class="acc-tag-cb" value="${esc(t)}"
                               ${selectedTags.has(t) ? 'checked' : ''}>
                        <span style="flex:1;">${esc(t)}</span>
                        <span style="color:var(--text-dim);">${n}</span>
                    </label>
                `).join('');

                tagsListEl.querySelectorAll('.acc-tag-cb').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        if (e.target.checked) selectedTags.add(e.target.value);
                        else                  selectedTags.delete(e.target.value);
                        updateTagsCount();
                        renderTagList();
                        renderList();
                    });
                });
            };

            renderTagList();
            updateTagsCount();

            tagsSearchEl.addEventListener('input', (e) => {
                tagSearchQuery = e.target.value;
                renderTagList();
            });

            tagsClearBtn.addEventListener('click', () => {
                selectedTags.clear();
                updateTagsCount();
                renderTagList();
                renderList();
            });

            tagsCloseBtn.addEventListener('click', () => {
                tagsDetails.removeAttribute('open');
            });

            // ==================== FILTER + GROUPING ====================

            const applyFilters = () => {
                const q = filterText.toLowerCase();
                return issues.filter(it => {
                    if (severityFilter && (it.impactSeverity || it.severity) !== severityFilter) return false;
                    if (typeFilter && it.type !== typeFilter) return false;
                    if (resolutionFilter && it.resolution !== resolutionFilter) return false;

                    if (selectedTags.size > 0) {
                        const itTags = new Set(it.tags || []);
                        for (const t of selectedTags) {
                            if (!itTags.has(t)) return false;
                        }
                    }

                    if (!q) return true;
                    return (it.key || '').toLowerCase().includes(q)
                        || (it.message || '').toLowerCase().includes(q)
                        || (it.rule || '').toLowerCase().includes(q)
                        || (it.component || '').toLowerCase().includes(q);
                });
            };

            const groupByFile = (filtered) => {
                const grouped = new Map();
                filtered.forEach(it => {
                    const file = it.component || '(unknown)';
                    if (!grouped.has(file)) grouped.set(file, []);
                    grouped.get(file).push(it);
                });
                grouped.forEach(list => list.sort((a, b) => (a.line || 0) - (b.line || 0)));
                return [...grouped.entries()].sort((a, b) => a[0].localeCompare(b[0]));
            };

            // ==================== RENDER ====================

            const syncFileCheckbox = (fileName, fileIssues) => {
                const fileCard = [...listEl.querySelectorAll('.pf-file-card')]
                    .find(c => c.dataset.file === fileName);
                if (!fileCard) return;
                const fileCb = fileCard.querySelector('.acc-file-checkbox');
                if (!fileCb) return;

                const allChecked  = fileIssues.length > 0 && fileIssues.every(it => selectedKeys.has(it.key));
                const someChecked = fileIssues.some(it => selectedKeys.has(it.key));

                fileCb.checked = allChecked;
                fileCb.indeterminate = someChecked && !allChecked;
            };

            const syncMasterCheckbox = (filtered) => {
                const visibleKeys = filtered.map(it => it.key);
                if (visibleKeys.length > 0 && visibleKeys.every(k => selectedKeys.has(k))) {
                    masterCb.checked = true;
                    masterCb.indeterminate = false;
                } else if (visibleKeys.some(k => selectedKeys.has(k))) {
                    masterCb.checked = false;
                    masterCb.indeterminate = true;
                } else {
                    masterCb.checked = false;
                    masterCb.indeterminate = false;
                }
            };

            const updateBulkBtn = () => {
                bulkBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                bulkBtn.textContent = `Reopen выбранные (${selectedKeys.size})`;
            };

            const renderList = () => {
                const filtered = applyFilters();

                if (!filtered.length) {
                    listEl.innerHTML = '<div class="empty">Нет обработанных issues по фильтру</div>';
                    syncMasterCheckbox(filtered);
                    updateBulkBtn();
                    return;
                }

                const groups = groupByFile(filtered);

                listEl.innerHTML = groups.map(([file, fileIssues]) => `
                    <div class="card pf-file-card" data-file="${esc(file)}" style="padding: 0; margin-bottom: 12px;">
                        <div class="pf-file-header"
                             style="padding: 12px 16px; background: var(--bg3);
                                    border-bottom: 1px solid var(--border);
                                    display:flex; justify-content:space-between;
                                    align-items:center; user-select:none;">
                            <div style="display:flex; align-items:center; gap:8px;">
                                <input type="checkbox" class="acc-file-checkbox"
                                       data-file="${esc(file)}"
                                       style="width:16px; height:16px; cursor:pointer;">
                                <h4 style="margin:0; text-transform:none;">
                                    ${esc(file)} — ${fileIssues.length}
                                </h4>
                            </div>
                        </div>
                        <table>
                            <thead>
                                <tr>
                                    <th style="width:30px; padding-left:16px;"></th>
                                    <th>key</th>
                                    <th>sev</th>
                                    <th>L</th>
                                    <th>rule</th>
                                    <th>message</th>
                                    <th>tags</th>
                                    <th>status</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${fileIssues.map(it => {
                                    const tagsHtml = (it.tags || []).length > 0
                                        ? (it.tags || []).map(t =>
                                            `<span style="display:inline-block; padding:1px 6px;
                                                          border-radius:8px; background:var(--bg3);
                                                          border:1px solid var(--border);
                                                          font-size:11px; margin:1px;">${esc(t)}</span>`
                                          ).join('')
                                        : '<span style="color:var(--text-dim);">—</span>';

                                    return `
                                    <tr class="pf-issue-row" data-key="${esc(it.key)}" style="cursor:pointer;">
                                        <td style="padding-left:16px;">
                                            <input type="checkbox" class="acc-issue-checkbox"
                                                   data-key="${esc(it.key)}"
                                                   ${selectedKeys.has(it.key) ? 'checked' : ''}>
                                        </td>
                                        <td><code>${esc(it.key)}</code></td>
                                        <td class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</td>
                                        <td class="num">${it.line}</td>
                                        <td>${esc(it.rule)}</td>
                                        <td>${esc(it.message)}</td>
                                        <td>${tagsHtml}</td>
                                        <td>${acceptedBadge(it)}</td>
                                    </tr>
                                `}).join('')}
                            </tbody>
                        </table>
                    </div>
                `).join('');

                groups.forEach(([file, fileIssues]) => {
                    syncFileCheckbox(file, fileIssues);
                });

                syncMasterCheckbox(filtered);

                listEl.querySelectorAll('.acc-issue-checkbox').forEach(cb => {
                    cb.addEventListener('change', e => {
                        if (e.target.checked) selectedKeys.add(e.target.dataset.key);
                        else                  selectedKeys.delete(e.target.dataset.key);
                        updateBulkBtn();
                        const fileCard = cb.closest('.pf-file-card');
                        if (fileCard) {
                            const file = fileCard.dataset.file;
                            const fileIssues = filtered.filter(it => (it.component || '(unknown)') === file);
                            syncFileCheckbox(file, fileIssues);
                        }
                        syncMasterCheckbox(filtered);
                    });
                });

                listEl.querySelectorAll('.acc-file-checkbox').forEach(cb => {
                    cb.addEventListener('change', e => {
                        const file = cb.dataset.file;
                        const fileIssues = filtered.filter(it => (it.component || '(unknown)') === file);
                        if (e.target.checked) fileIssues.forEach(it => selectedKeys.add(it.key));
                        else                  fileIssues.forEach(it => selectedKeys.delete(it.key));
                        const rows = cb.closest('.pf-file-card').querySelectorAll('.acc-issue-checkbox');
                        rows.forEach(rowCb => rowCb.checked = e.target.checked);
                        updateBulkBtn();
                        syncMasterCheckbox(filtered);
                    });
                });

                listEl.querySelectorAll('.pf-issue-row').forEach(row => {
                    row.addEventListener('dblclick', e => {
                        if (e.target.type === 'checkbox') return;
                        const it = issues.find(x => x.key === row.dataset.key);
                        if (it) openAcceptedModal(it, ctx, () => accepted.mount(el, ctx));
                    });

                    row.addEventListener('contextmenu', e => {
                        e.preventDefault();
                        const key = row.dataset.key;
                        const issue = issues.find(x => x.key === key);

                        const targetIssues = selectedKeys.size > 0
                            ? issues.filter(it => selectedKeys.has(it.key))
                            : [issue];

                        showContextMenu(e.pageX, e.pageY, issue, targetIssues, ctx, () => {
                            accepted.mount(el, ctx);
                        });
                    });
                });

                updateBulkBtn();
            };

            masterCb.addEventListener('change', e => {
                const filtered = applyFilters();
                if (e.target.checked) filtered.forEach(it => selectedKeys.add(it.key));
                else                  filtered.forEach(it => selectedKeys.delete(it.key));
                renderList();
                updateBulkBtn();
            });

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

            // ==================== EXPORT MD ====================

            exportBtn.addEventListener('click', async () => {
                const filtered = applyFilters();
                if (!filtered.length) {
                    toast('Нет данных для выгрузки (после фильтров)', true);
                    return;
                }

                // Собираем строку фильтра для шапки отчёта
                const parts = [];
                if (severityFilter)   parts.push(`severity=${severityFilter}`);
                if (typeFilter)       parts.push(`type=${typeFilter}`);
                if (resolutionFilter) parts.push(`resolution=${resolutionFilter}`);
                if (selectedTags.size > 0) {
                    parts.push(`tags=${[...selectedTags].join(',')}`);
                }
                if (filterText) parts.push(`text="${filterText}"`);

                const orig = exportBtn.textContent;
                exportBtn.disabled = true;
                exportBtn.textContent = '⏳ Готовлю...';

                try {
                    await exportAcceptedToMarkdown(filtered, ctx, {
                        project: d.project,
                        totalAll: issues.length,
                        filterSummary: parts.length ? parts.join(' | ') : 'нет (весь список)'
                    });
                    toast(`Экспортировано ${filtered.length} issues в MD`);
                } catch (e) {
                    console.error('[accepted] export failed:', e);
                    toast('Ошибка экспорта: ' + e.message, true);
                } finally {
                    exportBtn.disabled = false;
                    exportBtn.textContent = orig;
                }
            });

            // ==================== BULK REOPEN ====================

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