import { esc, statusBadge } from '../../common.js';
import { openIssueModal } from './modals/issue-modal.js';
import { openBulkModal } from './modals/bulk-modal.js';
import { showIssueContextMenu } from './context-menu.js';

export const perfile = {
    id: 'perfile',
    title: 'Отчет по файлам (Per-File)',
    filters: ['sev', 'qual'],

    async mount(el, ctx) {
        el.innerHTML = '<div class="empty">loading…</div>';
        try {
            const d = await ctx.api('/api/perfile?' + ctx.qs());
            if (ctx.updatePagination) ctx.updatePagination(d.total);

            let rawData = d;
            let filterText = '';
            let hideProcessed = ctx.state.hideProcessed;
            let selectedKeys = new Set();
            let isAllSelected = false;

            let allRules = [];
            let totalRules = 0;

            const loadRulesFacet = async () => {
                try {
                    const p = new URLSearchParams();
                    if (ctx.state.project) p.set('project', ctx.state.project.trim());
                    p.set('sev', ctx.state.sev);
                    p.set('qual', ctx.state.qual);
                    p.set('facet', 'rules');
                    p.set('hideProcessed', hideProcessed);

                    const res = await ctx.api('/api/facets?' + p.toString());
                    const rulesObj = res.values || {};
                    allRules = Object.entries(rulesObj)
                        .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
                    totalRules = allRules.length;
                } catch (e) {
                    console.warn('Не удалось загрузить статистику по правилам:', e);
                    allRules = [];
                    totalRules = 0;
                }
            };

            await loadRulesFacet();

            el.innerHTML = `
                <div class="pf-header-sticky">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                        <div style="display:flex; align-items:center; gap:12px;">
                            <input type="checkbox" id="pf-master-checkbox" title="Выделить всё" style="width:18px; height:18px; cursor:pointer;">
                            <h3 id="pf-header-title" style="margin:0;">${esc(d.project)} · ${esc(d.severity)}/${esc(d.quality)} · Всего: ${d.total}</h3>
                        </div>
                        <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                            <label style="display:flex; align-items:center; gap:6px; font-size:12px;">
                                <span style="color:var(--text-dim);">Правило:</span>
                                <select id="pf-rule-filter" style="padding:6px 10px; border-radius:6px; border:1px solid var(--border); background:var(--bg); color:var(--text); max-width:300px; font-family:var(--mono); font-size:12px;">
                                    <option value="">— все правила (${totalRules}) —</option>
                                </select>
                            </label>
                            <label style="display:flex; align-items:center; gap:4px; font-size:12px; cursor:pointer;">
                                <input type="checkbox" id="pf-hide-processed" ${hideProcessed ? 'checked' : ''}> Скрыть обработанные
                            </label>
                            <input type="text" id="pf-filter" placeholder="Фильтр по файлу или сообщению..." style="padding:6px 10px; border-radius:6px; border:1px solid var(--border); background:var(--bg); color:var(--text); width:250px;">
                            <button id="pf-bulk-btn" class="btn btn-accent" style="display:none;">Действия с выбранными (0)</button>
                        </div>
                    </div>
                </div>
                <div id="pf-list" class="pf-list-container"></div>
            `;

            const listEl = el.querySelector('#pf-list');
            const filterInput = el.querySelector('#pf-filter');
            const hideProcessedCb = el.querySelector('#pf-hide-processed');
            const bulkBtn = el.querySelector('#pf-bulk-btn');
            const masterCb = el.querySelector('#pf-master-checkbox');
            const ruleSelect = el.querySelector('#pf-rule-filter');

            const rebuildRulesSelect = () => {
                const prev = ctx.state.rule || '';
                ruleSelect.innerHTML = '';
                const allOpt = document.createElement('option');
                allOpt.value = '';
                allOpt.textContent = `— все правила (${totalRules}) —`;
                ruleSelect.appendChild(allOpt);

                allRules.forEach(([rule, count]) => {
                    const opt = document.createElement('option');
                    opt.value = rule;
                    opt.textContent = `${rule} (${count})`;
                    ruleSelect.appendChild(opt);
                });

                if (prev && allRules.some(([r]) => r === prev)) {
                    ruleSelect.value = prev;
                } else {
                    ctx.state.rule = '';
                    localStorage.setItem('rule', '');
                    ruleSelect.value = '';
                }
            };

            rebuildRulesSelect();

            const refreshData = async () => {
                await loadRulesFacet();
                rebuildRulesSelect();

                try {
                    const queryParams = new URLSearchParams(ctx.qs());
                    const res = await ctx.api('/api/perfile?' + queryParams.toString());
                    rawData = res;
                    ctx.updatePagination(res.total);
                } catch (e) {
                    console.warn('Ошибка обновления данных:', e);
                }
                isAllSelected = false;
                selectedKeys.clear();
                renderList();
            };

            const applyFilters = () => {
                const query = filterText.toLowerCase();
                return rawData.files.map(f => {
                    const matchedIssues = f.issues.filter(it => {
                        const matchesText =
                            (it.message || '').toLowerCase().includes(query) ||
                            (it.rule || '').toLowerCase().includes(query) ||
                            (it.ruleName || '').toLowerCase().includes(query);
                        if (!matchesText) return false;

                        if (hideProcessed) {
                            const isMarked = it.status && it.status !== 'OPEN' && it.status !== 'REOPENED';
                            if (isMarked) return false;
                        }
                        return true;
                    });
                    return { ...f, issues: matchedIssues };
                }).filter(f => f.issues.length > 0);
            };

            const updateBulkBtn = () => {
                if (isAllSelected) {
                    bulkBtn.style.display = 'inline-block';
                    bulkBtn.textContent = `Действия со всеми (${rawData.total})`;
                } else {
                    bulkBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                    bulkBtn.textContent = `Действия с выбранными (${selectedKeys.size})`;
                }
            };

            // ФУНКЦИЯ ДЛЯ ЗАГРУЗКИ ВСЕХ ЭЛЕМЕНТОВ (нужна для контекстного меню)
            const fetchAllIssuesForAction = async () => {
                let issues = [];
                const pageSize = 500;
                let page = 1;
                let total = rawData.total;

                while (issues.length < total) {
                    const pPage = new URLSearchParams(ctx.qs());
                    pPage.set('ps', pageSize);
                    pPage.set('p', page);
                    const res = await ctx.api('/api/perfile?' + pPage.toString());
                    if (!res.files || res.files.length === 0) break;

                    res.files.forEach(f => {
                        f.issues.forEach(it => issues.push(it));
                    });

                    page++;
                    if (issues.length >= total || page > 100) break;
                }
                return issues;
            };

            const renderList = () => {
                const filtered = applyFilters();
                if (filtered.length === 0) {
                    listEl.innerHTML = '<div class="empty">Ничего не найдено по фильтру</div>';
                    syncCheckboxes(filtered);
                    updateBulkBtn();
                    return;
                }

                listEl.innerHTML = filtered.map(f => `
                    <div class="card pf-file-card" data-file="${esc(f.file)}" style="padding: 0;">
                        <div class="pf-file-header" style="padding: 12px 16px; background: var(--bg3); border-bottom: 1px solid var(--border); display:flex; justify-content:space-between; align-items:center; user-select:none;">
                            <div style="display:flex; align-items:center; gap:8px;">
                                <input type="checkbox" class="pf-file-checkbox" data-file="${esc(f.file)}" style="width:16px; height:16px; cursor:pointer;">
                                <h4 style="margin:0; text-transform:none;">${esc(f.file)} — ${f.issues.length}</h4>
                            </div>
                        </div>
                        <table><thead><tr>
                            <th style="width:30px; padding-left:16px;"></th>
                            <th>L</th><th>rule</th><th>sev</th><th>message</th>
                        </tr></thead><tbody>
                            ${f.issues.map(it => {
                                const isMarked = it.status && it.status !== 'OPEN' && it.status !== 'REOPENED';
                                return `
                                <tr class="pf-issue-row" data-key="${esc(it.key)}" data-file="${esc(f.file)}" style="cursor:pointer; ${isMarked ? 'background: rgba(46, 160, 67, 0.08);' : ''}">
                                    <td style="padding-left:16px;"><input type="checkbox" class="pf-issue-checkbox" data-key="${esc(it.key)}" ${(isAllSelected || selectedKeys.has(it.key)) ? 'checked' : ''}></td>
                                    <td class="num">${it.line}</td>
                                    <td>${esc(it.rule)}</td>
                                    <td class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</td>
                                    <td>${statusBadge(it.status)}${esc(it.message)}</td>
                                </tr>
                            `}).join('')}
                        </tbody></table>
                    </div>
                `).join('');

                syncCheckboxes(filtered);

                listEl.querySelectorAll('.pf-issue-checkbox').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        if (isAllSelected) {
                            isAllSelected = false;
                            filtered.forEach(f => f.issues.forEach(it => {
                                if (it.key !== e.target.dataset.key) selectedKeys.add(it.key);
                            }));
                        } else {
                            if (e.target.checked) selectedKeys.add(e.target.dataset.key);
                            else                  selectedKeys.delete(e.target.dataset.key);
                        }
                        updateBulkBtn();
                        syncFileCheckbox(cb.closest('.pf-file-card').dataset.file, filtered);
                    });
                });

                listEl.querySelectorAll('.pf-file-checkbox').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        const file = cb.dataset.file;
                        const fileIssues = filtered.find(x => x.file === file).issues;
                        const isChecked = e.target.checked;
                        if (isChecked) isAllSelected = false;

                        fileIssues.forEach(it => {
                            if (isChecked) selectedKeys.add(it.key);
                            else           selectedKeys.delete(it.key);
                        });
                        const rows = cb.closest('.pf-file-card').querySelectorAll('.pf-issue-checkbox');
                        rows.forEach(rowCb => rowCb.checked = isChecked);
                        updateBulkBtn();
                    });
                });

                listEl.querySelectorAll('.pf-issue-row').forEach(row => {
                    row.addEventListener('dblclick', (e) => {
                        if (e.target.type === 'checkbox') return;
                        const key = row.dataset.key;
                        const issue = rawData.files.flatMap(x => x.issues).find(x => x.key === key);
                        openIssueModal(issue, ctx, () => refreshData());
                    });

                    row.addEventListener('contextmenu', (e) => {
                        e.preventDefault();
                        const key = row.dataset.key;
                        const issue = rawData.files.flatMap(x => x.issues).find(x => x.key === key);

                        let targetIssues = [];
                        if (isAllSelected) {
                            targetIssues = filtered.flatMap(f => f.issues);
                        } else if (selectedKeys.size > 0) {
                            targetIssues = rawData.files.flatMap(f => f.issues).filter(it => selectedKeys.has(it.key));
                        } else {
                            targetIssues = [issue];
                        }

                        const localReloadCb = () => {
                            selectedKeys.clear();
                            isAllSelected = false;
                            updateBulkBtn();
                            refreshData();
                        };

                        showIssueContextMenu(
                            e.pageX, e.pageY,
                            issue, targetIssues, filtered, ctx,
                            localReloadCb,
                            () => {
                                selectedKeys.clear();
                                isAllSelected = true;
                                updateBulkBtn();
                                renderList();
                            },
                            () => {
                                selectedKeys.clear();
                                isAllSelected = false;
                                renderList();
                                updateBulkBtn();
                            },
                            isAllSelected,
                            rawData.total
                        );
                    });
                });

                updateBulkBtn();
            };

            const syncCheckboxes = (filtered) => {
                listEl.querySelectorAll('.pf-issue-checkbox').forEach(cb => {
                    cb.checked = isAllSelected || selectedKeys.has(cb.dataset.key);
                });

                filtered.forEach(f => {
                    syncFileCheckbox(f.file, filtered);
                });

                if (isAllSelected) {
                    masterCb.checked = true;
                    masterCb.indeterminate = false;
                } else {
                    const allFilteredKeys = filtered.flatMap(f => f.issues.map(it => it.key));
                    if (allFilteredKeys.length > 0 && allFilteredKeys.every(k => selectedKeys.has(k))) {
                        masterCb.checked = true;
                        masterCb.indeterminate = false;
                    } else if (allFilteredKeys.some(k => selectedKeys.has(k))) {
                        masterCb.checked = false;
                        masterCb.indeterminate = true;
                    } else {
                        masterCb.checked = false;
                        masterCb.indeterminate = false;
                    }
                }
            };

            const syncFileCheckbox = (fileName) => {
                const fileCard = listEl.querySelector(`.pf-file-card[data-file="${fileName}"]`);
                if (!fileCard) return;
                const fileCb = fileCard.querySelector('.pf-file-checkbox');
                const rows = fileCard.querySelectorAll('.pf-issue-checkbox');
                if (!rows.length) return;

                const allChecked = Array.from(rows).every(r => isAllSelected || selectedKeys.has(r.dataset.key));
                const someChecked = Array.from(rows).some(r => isAllSelected || selectedKeys.has(r.dataset.key));

                fileCb.checked = allChecked;
                fileCb.indeterminate = someChecked && !allChecked;
            };

            filterInput.addEventListener('input', (e) => {
                filterText = e.target.value;
                isAllSelected = false;
                selectedKeys.clear();
                renderList();
            });

            hideProcessedCb.addEventListener('change', async (e) => {
                hideProcessed = e.target.checked;
                ctx.state.hideProcessed = hideProcessed;
                localStorage.setItem('hideProcessed', hideProcessed);
                isAllSelected = false;
                selectedKeys.clear();
                await refreshData();
            });

            ruleSelect.addEventListener('change', async (e) => {
                ctx.state.rule = e.target.value;
                localStorage.setItem('rule', ctx.state.rule);
                ctx.state.page = 1;
                isAllSelected = false;
                selectedKeys.clear();
                await refreshData();
            });

            masterCb.addEventListener('change', (e) => {
                if (e.target.checked) {
                    isAllSelected = true;
                    selectedKeys.clear();
                } else {
                    isAllSelected = false;
                    selectedKeys.clear();
                }
                renderList();
                updateBulkBtn();
            });

            // ОБНОВЛЕННЫЙ ОБРАБОТЧИК КНОПКИ (И для контекстного меню, и для кнопки сверху)
            const handleBulkAction = async (issuesToProcess) => {
                if (issuesToProcess.length === 0) {
                    ctx.toast('Нет выбранных замечаний', true);
                    return;
                }

                openBulkModal(issuesToProcess, ctx, () => {
                    isAllSelected = false;
                    selectedKeys.clear();
                    updateBulkBtn();
                    refreshData();
                });
            };

            bulkBtn.addEventListener('click', async () => {
                if (isAllSelected) {
                    bulkBtn.textContent = 'Загрузка...';
                    bulkBtn.disabled = true;
                    try {
                        const allIssues = await fetchAllIssuesForAction();
                        handleBulkAction(allIssues);
                    } catch (err) {
                        ctx.toast('Ошибка загрузки всех замечаний: ' + err.message, true);
                    } finally {
                        bulkBtn.disabled = false;
                        updateBulkBtn();
                    }
                } else {
                    const issues = rawData.files.flatMap(x => x.issues).filter(i => selectedKeys.has(i.key));
                    handleBulkAction(issues);
                }
            });

            renderList();
        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};