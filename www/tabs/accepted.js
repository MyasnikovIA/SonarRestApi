import { esc, toast, highlightCode, statusBadge, showTagEditor } from '../common.js';

function getReopenTransition(status) {
    if (status === 'CONFIRMED') return 'unconfirm';
    return 'reopen';
}

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

// ==================== PROGRESS HELPER ====================

async function runBulkWithProgress({ title, issues, perItemAction }) {
    const total = issues.length;
    if (total === 0) return { success: 0, failed: [], cancelled: false };

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 520px;">
            <div class="modal-header">
                <h3 style="margin:0;">${esc(title)}</h3>
            </div>
            <div class="modal-body">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; font-size:12px;">
                    <span>Прогресс: <b id="bulk-prog-counter">0 / ${total}</b></span>
                    <span id="bulk-prog-status" style="color:var(--text-dim);">выполняется…</span>
                </div>
                <div style="height:8px; background:var(--bg3); border-radius:4px; overflow:hidden; border:1px solid var(--border);">
                    <div id="bulk-prog-fill" style="height:100%; width:0%; background:var(--accent); transition: width 0.15s;"></div>
                </div>
                <div id="bulk-prog-current"
                     style="margin-top:10px; font-size:12px; color:var(--text-dim);
                            white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">—</div>
                <div id="bulk-prog-errors"
                     style="margin-top:8px; font-size:12px; color:var(--sev-HIGH);
                            max-height:120px; overflow-y:auto;"></div>
                <div style="margin-top:16px; display:flex; justify-content:flex-end; gap:8px;">
                    <button id="bulk-prog-cancel" class="btn btn-danger">Остановить</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const fill      = overlay.querySelector('#bulk-prog-fill');
    const counter   = overlay.querySelector('#bulk-prog-counter');
    const statusEl  = overlay.querySelector('#bulk-prog-status');
    const currentEl = overlay.querySelector('#bulk-prog-current');
    const errorsEl  = overlay.querySelector('#bulk-prog-errors');
    const cancelBtn = overlay.querySelector('#bulk-prog-cancel');

    let cancelled = false;
    let success   = 0;
    let lastDone  = 0;
    const failed  = [];
    const abortController = new AbortController();

    const updateProgress = (done) => {
        const pct = total ? Math.round((done / total) * 100) : 0;
        fill.style.width = pct + '%';
        counter.textContent = `${done} / ${total}`;
    };

    const onCancel = () => {
        cancelled = true;
        statusEl.textContent = 'Остановка…';
        cancelBtn.disabled = true;
        abortController.abort();
    };
    cancelBtn.addEventListener('click', onCancel);

    for (let i = 0; i < total; i++) {
        if (cancelled) break;
        const it = issues[i];
        currentEl.textContent = it.key + (it.component ? ' — ' + it.component : '');

        try {
            await perItemAction(it, i, abortController.signal);
            success++;
        } catch (e) {
            if (e && e.name === 'AbortError') {
                cancelled = true;
                break;
            }
            failed.push({ key: it.key, message: e.message });
            const div = document.createElement('div');
            div.textContent = `✗ ${it.key}: ${e.message}`;
            errorsEl.appendChild(div);
        }

        lastDone = i + 1;
        updateProgress(lastDone);
    }

    const finalDone = cancelled ? lastDone : total;
    updateProgress(finalDone);
    statusEl.textContent = cancelled
        ? `Остановлено: ${success} / ${total}`
        : `Готово: ${success} / ${total}`;
    currentEl.textContent = '—';

    cancelBtn.disabled = false;
    cancelBtn.classList.remove('btn-danger');
    cancelBtn.classList.add('btn-accent');
    cancelBtn.textContent = 'Закрыть';
    cancelBtn.removeEventListener('click', onCancel);
    cancelBtn.addEventListener('click', () => overlay.remove(), { once: true });

    return { success, failed, cancelled };
}

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
            let selectedKeys     = new Set();
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

                    // tag filter — AND: all selected tags must be present
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

                // ---- file checkbox state ----
                groups.forEach(([file, fileIssues]) => {
                    syncFileCheckbox(file, fileIssues);
                });

                // ---- master checkbox state ----
                syncMasterCheckbox(filtered);

                // ---- issue checkboxes ----
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

                // ---- file checkboxes ----
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

                // ---- row interactions ----
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

            // ---- master checkbox handler ----
            masterCb.addEventListener('change', e => {
                const filtered = applyFilters();
                if (e.target.checked) filtered.forEach(it => selectedKeys.add(it.key));
                else                  filtered.forEach(it => selectedKeys.delete(it.key));
                renderList();
                updateBulkBtn();
            });

            // ---- facet chips ----
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

// ==================== КОНТЕКСТНОЕ МЕНЮ ====================

function showContextMenu(x, y, clickedIssue, targetIssues, ctx, reloadCb) {
    document.querySelector('.ctx-menu')?.remove();

    const menu = document.createElement('div');
    menu.className = 'ctx-menu';
    menu.style.left = x + 'px';
    menu.style.top = y + 'px';

    menu.innerHTML = `
        <div class="ctx-item" id="ctx-add-comment">📝 Добавить комментарий</div>
        <div class="ctx-item" id="ctx-edit-tags">🏷️ Редактировать теги</div>
        <div class="ctx-item" id="ctx-change-status">🔄 Изменить статус</div>
        <div class="ctx-item" id="ctx-changelog">📜 История изменений</div>
        <div class="ctx-item" style="border-top:1px solid var(--border); margin-top:4px; padding-top:8px;" id="ctx-reopen">🔓 Снять пометку (Reopen)</div>
        <div class="ctx-item" id="ctx-reopened">❌ Переоткрытое замечание</div>
    `;

    document.body.appendChild(menu);

    const closeMenu = () => menu.remove();
    document.addEventListener('click', closeMenu, { once: true });

    menu.querySelector('#ctx-add-comment').addEventListener('click', () => {
        openBulkModal(targetIssues, ctx, reloadCb);
    });

    menu.querySelector('#ctx-edit-tags').addEventListener('click', () => {
        showTagEditor(targetIssues, ctx, reloadCb);
    });

    menu.querySelector('#ctx-change-status').addEventListener('click', () => {
        showChangeStatusModal(targetIssues, ctx, reloadCb);
    });

    menu.querySelector('#ctx-changelog').addEventListener('click', () => {
        showChangelogModal(targetIssues, ctx);
    });

    menu.querySelector('#ctx-reopen').addEventListener('click', async () => {
        if (!confirm(`Снять пометку (reopen) у ${targetIssues.length} issues?`)) return;

        await runBulkWithProgress({
            title: 'Снятие пометок (Reopen)',
            issues: targetIssues,
            perItemAction: async (it, _i, signal) => {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', getReopenTransition(it.status));
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST', signal });
                if (res.success === false) throw new Error(res.error || 'Не удалось сбросить');
            }
        });

        ctx.refreshStatus?.();
        reloadCb();
    });

    menu.querySelector('#ctx-reopened').addEventListener('click', async () => {
        if (!confirm(`Установить статус "Переоткрытое замечание" у ${targetIssues.length} issues?`)) return;

        await runBulkWithProgress({
            title: 'Переоткрытие замечаний',
            issues: targetIssues,
            perItemAction: async (it, _i, signal) => {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', 'reopen');
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST', signal });
                if (res.success === false) throw new Error(res.error || 'Не удалось открыть');
            }
        });

        ctx.refreshStatus?.();
        reloadCb();
    });
}

// ==================== МОДАЛЬНЫЕ ОКНА ====================

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

        const [d, ch] = await Promise.all([
            ctx.api('/api/snippet?' + p),
            ctx.api('/api/changelog?' + p).catch(() => ({ changelog: [] }))
        ]);

        const it = d.issue || issue;
        const changelog = ch.changelog || [];
        const comments = it.comments || [];

        const commentsHtml = comments.length > 0 ? `
            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">Комментарии:</label>
                <div style="background:var(--bg3); border:1px solid var(--border); border-radius:6px; padding:12px; max-height:150px; overflow-y:auto; font-size:12px;">
                    ${comments.map(c => `
                        <div style="margin-bottom:8px; padding-bottom:8px; border-bottom:1px solid var(--border);">
                            <div style="color:var(--accent); font-weight:bold; margin-bottom:4px;">${esc(c.login || 'Пользователь')} (${new Date(c.createdAt).toLocaleString()}):</div>
                            <div style="white-space:pre-wrap;">${esc(c.markdown || c.htmlText || '')}</div>
                        </div>
                    `).join('')}
                </div>
            </div>
        ` : '';

        const changelogHtml = changelog.length > 0 ? `
            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">История изменений:</label>
                <div style="background:var(--bg3); border:1px solid var(--border); border-radius:6px; padding:12px; max-height:150px; overflow-y:auto; font-size:12px;">
                    ${changelog.map(c => `
                        <div style="margin-bottom:8px; padding-bottom:8px; border-bottom:1px solid var(--border);">
                            <div style="color:var(--accent); font-weight:bold; margin-bottom:4px;">${esc(c.userName || c.user)} (${new Date(c.creationDate).toLocaleString()}):</div>
                            <div>
                                ${c.diffs.map(diff => `<div><span style="color:var(--text-dim)">${esc(diff.key)}:</span> ${esc(diff.oldValue || '—')} &rarr; <b>${esc(diff.newValue)}</b></div>`).join('')}
                            </div>
                        </div>
                    `).join('')}
                </div>
            </div>
        ` : '';

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
                ? `<pre class="code" style="max-height:300px; overflow:auto;">${highlightCode(d.snippet.join('\n'))}</pre>`
                : `<div class="empty">Код недоступен</div>`}

            ${changelogHtml}
            ${commentsHtml}

            <div style="margin-top:20px; padding-top:16px; border-top:1px solid var(--border); display:flex; justify-content:flex-end; gap:8px;">
                <button id="acc-modal-tags" class="btn" style="margin-right:auto;">🏷️ Изменить теги</button>
                <button id="acc-modal-reopen" class="btn btn-danger">Снять пометку (Reopen)</button>
                <button id="acc-modal-close" class="btn">Закрыть</button>
            </div>
        `;

        overlay.querySelector('#acc-modal-close').addEventListener('click', close);
        overlay.querySelector('#acc-modal-tags').addEventListener('click', () => {
            showTagEditor([it], ctx, () => {
                close();
                if (onDone) onDone();
            });
        });

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

// ==================== МОДАЛКА ИЗМЕНЕНИЯ СТАТУСА ====================

function showChangeStatusModal(issues, ctx, reloadCb) {
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

// ==================== МОДАЛКА ИСТОРИИ ИЗМЕНЕНИЙ ====================

function showChangelogModal(issues, ctx) {
    if (!issues.length) return;
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 600px;">
            <div class="modal-header">
                <h3 style="margin:0;">История изменений</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body" id="changelog-body">
                <div class="empty">Загрузка...</div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    Promise.all(issues.map(it =>
        ctx.api('/api/changelog?issueKey=' + encodeURIComponent(it.key))
           .catch(() => ({ changelog: [] }))
    )).then(results => {
        let allLogs = [];
        results.forEach((r, idx) => {
            const it = issues[idx];
            const logs = r.changelog || [];
            logs.forEach(c => {
                allLogs.push({ ...c, issueKey: it.key });
            });
        });

        allLogs.sort((a, b) => new Date(b.creationDate) - new Date(a.creationDate));

        const body = overlay.querySelector('#changelog-body');
        if (!allLogs.length) {
            body.innerHTML = '<div class="empty">Нет истории изменений</div>';
            return;
        }

        body.innerHTML = `
            <div style="background:var(--bg3); border:1px solid var(--border); border-radius:6px; padding:12px; max-height:500px; overflow-y:auto; font-size:12px;">
                ${allLogs.map(c => `
                    <div style="margin-bottom:12px; padding-bottom:12px; border-bottom:1px solid var(--border);">
                        <div style="color:var(--accent); font-weight:bold; margin-bottom:4px;">
                            ${esc(c.userName || c.user)} <span style="color:var(--text-dim); font-weight:normal;">(${new Date(c.creationDate).toLocaleString()})</span>
                            <span style="color:var(--text-dim); font-weight:normal; margin-left:8px;">[${esc(c.issueKey)}]</span>
                        </div>
                        <div>
                            ${c.diffs.map(diff => `<div><span style="color:var(--text-dim)">${esc(diff.key)}:</span> ${esc(diff.oldValue || '—')} &rarr; <b>${esc(diff.newValue)}</b></div>`).join('')}
                        </div>
                    </div>
                `).join('')}
            </div>
        `;
    }).catch(e => {
        overlay.querySelector('#changelog-body').innerHTML = `<div class="sev-HIGH">Ошибка: ${esc(e.message)}</div>`;
    });
}

// ==================== МОДАЛЬНОЕ ОКНО ДОБАВЛЕНИЯ КОММЕНТАРИЯ ====================

async function openBulkModal(issues, ctx, reloadCb) {
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