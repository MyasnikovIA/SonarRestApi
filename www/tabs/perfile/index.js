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
            const files = d.files || [];
            if (!files.length) { el.innerHTML = '<div class="empty">Нет данных</div>'; return; }

            let rawData = d;
            let filterText = '';
            let hideProcessed = false;
            let ruleFilter = '';
            let selectedKeys = new Set();

            // ========== Список правил для фильтра ==========
            let allRules = [];
            let totalRules = 0;

            const collectRules = () => {
                const ruleCounts = new Map();
                rawData.files.forEach(f => {
                    (f.issues || []).forEach(it => {
                        if (it.rule) {
                            ruleCounts.set(it.rule, (ruleCounts.get(it.rule) || 0) + 1);
                        }
                    });
                });
                allRules = [...ruleCounts.entries()]
                    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
                totalRules = allRules.length;
            };
            collectRules();

            el.innerHTML = `
                <div class="pf-header-sticky">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                        <div style="display:flex; align-items:center; gap:12px;">
                            <input type="checkbox" id="pf-master-checkbox" title="Выделить всё" style="width:18px; height:18px; cursor:pointer;">
                            <h3 id="pf-header-title" style="margin:0;">${esc(d.project)} · ${esc(d.severity)}/${esc(d.quality)} · ${d.issuesCount} issues · ${d.filesCount} файлов · ${totalRules} правил</h3>
                        </div>
                        <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                            <label style="display:flex; align-items:center; gap:6px; font-size:12px;">
                                <span style="color:var(--text-dim);">Правило:</span>
                                <select id="pf-rule-filter"
                                        style="padding:6px 10px; border-radius:6px;
                                               border:1px solid var(--border);
                                               background:var(--bg); color:var(--text);
                                               max-width:300px; font-family:var(--mono);
                                               font-size:12px;">
                                    <option value="">— все правила (${totalRules}) —</option>
                                </select>
                            </label>
                            <label style="display:flex; align-items:center; gap:4px; font-size:12px; cursor:pointer;">
                                <input type="checkbox" id="pf-hide-processed"> Скрыть обработанные
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
            const headerTitle = el.querySelector('#pf-header-title');

            // Заполняем select уникальными правилами
            allRules.forEach(([rule, count]) => {
                const opt = document.createElement('option');
                opt.value = rule;
                opt.textContent = `${rule} (${count})`;
                ruleSelect.appendChild(opt);
            });

            const rebuildRulesSelect = () => {
                const prev = ruleFilter;
                collectRules();

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
                    ruleFilter = '';
                    ruleSelect.value = '';
                }

                const totalIssues = rawData.files.reduce((s, f) => s + f.issues.length, 0);
                const totalFiles = rawData.files.filter(f => f.issues.length > 0).length;
                headerTitle.textContent =
                    `${d.project} · ${d.severity}/${d.quality} · ${totalIssues} issues · ${totalFiles} файлов · ${totalRules} правил`;
            };

            // ========== Фильтры ==========
            const applyFilters = () => {
                const query = filterText.toLowerCase();
                return rawData.files.map(f => {
                    const matchedIssues = f.issues.filter(it => {
                        if (ruleFilter && it.rule !== ruleFilter) return false;

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
                bulkBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                bulkBtn.textContent = `Действия с выбранными (${selectedKeys.size})`;
                const visibleIssues = applyFilters().flatMap(f => f.issues);
                const allVisibleChecked = visibleIssues.length > 0 && visibleIssues.every(it => selectedKeys.has(it.key));
                masterCb.checked = allVisibleChecked;
            };

            const renderList = () => {
                const filtered = applyFilters();
                if (filtered.length === 0) {
                    listEl.innerHTML = '<div class="empty">Ничего не найдено по фильтру</div>';
                    syncCheckboxes(filtered);
                    updateBulkBtn(); // <-- ОБЯЗАТЕЛЬНО обновляем кнопку
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
                                    <td style="padding-left:16px;"><input type="checkbox" class="pf-issue-checkbox" data-key="${esc(it.key)}" ${selectedKeys.has(it.key) ? 'checked' : ''}></td>
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
                        if (e.target.checked) selectedKeys.add(e.target.dataset.key);
                        else                  selectedKeys.delete(e.target.dataset.key);
                        updateBulkBtn();
                        syncFileCheckbox(cb.closest('.pf-file-card').dataset.file, filtered);
                    });
                });

                listEl.querySelectorAll('.pf-file-checkbox').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        const file = cb.dataset.file;
                        const fileIssues = filtered.find(x => x.file === file).issues;
                        const isChecked = e.target.checked;
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
                        openIssueModal(issue, ctx, () => {
                            document.querySelector('.tab[data-tab="perfile"]').click();
                        });
                    });
                });

                listEl.querySelectorAll('.pf-issue-row').forEach(row => {
                    row.addEventListener('contextmenu', (e) => {
                        e.preventDefault();
                        const key = row.dataset.key;
                        const issue = rawData.files.flatMap(x => x.issues).find(x => x.key === key);

                        let targetIssues = [];
                        if (selectedKeys.size > 0) {
                            targetIssues = rawData.files.flatMap(f => f.issues).filter(it => selectedKeys.has(it.key));
                        } else {
                            targetIssues = [issue];
                        }

                        // === ИСПРАВЛЕНИЕ ===
                        // Раньше здесь было:
                        //   () => document.querySelector('.tab[data-tab="perfile"]').click()
                        // что вызывало перемонтирование вкладки и повторную загрузку
                        // данных из устаревшего snapshot'а. Теперь — локальный рендер
                        // по уже мутированному rawData (bulk-modal обновляет it.status).
                        const localReloadCb = () => {
                            selectedKeys.clear();
                            updateBulkBtn();
                            rebuildRulesSelect();
                            renderList();
                        };

                        showIssueContextMenu(
                            e.pageX, e.pageY,
                            issue, targetIssues, filtered, ctx,
                            localReloadCb,
                            () => {
                                selectedKeys.clear();
                                filtered.forEach(f => f.issues.forEach(it => selectedKeys.add(it.key)));
                                renderList();
                            },
                            () => {
                                selectedKeys.clear();
                                renderList();
                            }
                        );
                    });
                });

                updateBulkBtn(); // <-- ОБЯЗАТЕЛЬНО обновляем кнопку
            };

            const syncCheckboxes = (filtered) => {
                listEl.querySelectorAll('.pf-issue-checkbox').forEach(cb => {
                    cb.checked = selectedKeys.has(cb.dataset.key);
                });

                filtered.forEach(f => {
                    syncFileCheckbox(f.file, filtered);
                });

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
            };

            const syncFileCheckbox = (fileName) => {
                const fileCard = listEl.querySelector(`.pf-file-card[data-file="${fileName}"]`);
                if (!fileCard) return;
                const fileCb = fileCard.querySelector('.pf-file-checkbox');
                const rows = fileCard.querySelectorAll('.pf-issue-checkbox');
                if (!rows.length) return;

                const allChecked = Array.from(rows).every(r => selectedKeys.has(r.dataset.key));
                const someChecked = Array.from(rows).some(r => selectedKeys.has(r.dataset.key));

                fileCb.checked = allChecked;
                fileCb.indeterminate = someChecked && !allChecked;
            };

            // ========== Обработчики ==========

            // При смене любого фильтра очищаем выделение, чтобы избежать "невидимых" выбранных issues
            filterInput.addEventListener('input', (e) => {
                filterText = e.target.value;
                selectedKeys.clear(); // <-- очищаем
                renderList();
            });

            hideProcessedCb.addEventListener('change', (e) => {
                hideProcessed = e.target.checked;
                selectedKeys.clear(); // <-- очищаем
                renderList();
            });

            ruleSelect.addEventListener('change', (e) => {
                ruleFilter = e.target.value;
                selectedKeys.clear(); // <-- очищаем
                renderList();
            });

            masterCb.addEventListener('change', (e) => {
                const filtered = applyFilters();
                const keys = filtered.flatMap(f => f.issues.map(it => it.key));
                if (e.target.checked) {
                    keys.forEach(k => selectedKeys.add(k));
                } else {
                    keys.forEach(k => selectedKeys.delete(k));
                }
                renderList();
                updateBulkBtn();
            });

            bulkBtn.addEventListener('click', () => {
                const issues = rawData.files.flatMap(x => x.issues).filter(i => selectedKeys.has(i.key));
                openBulkModal(issues, ctx, () => {
                    selectedKeys.clear();
                    updateBulkBtn();
                    rebuildRulesSelect();
                    renderList();
                });
            });

            renderList();
        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};