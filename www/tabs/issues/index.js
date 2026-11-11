import { esc, statusBadge } from '../../common.js';
import { openIssueModal } from './modals/issue-modal.js';
import { openBulkModal } from './modals/bulk-modal.js';
import { showIssueContextMenu } from './context-menu.js';

export const issues = {
    id: 'issues',
    title: 'Все ошибки (Issues)',
    filters: ['sev', 'qual'],

    async mount(el, ctx) {
        el.innerHTML = '<div class="empty">loading…</div>';
        try {
            const d = await ctx.api('/api/issues?' + ctx.qs());
            const list = d.issues || [];
            if (!list.length) {
                el.innerHTML = '<div class="empty">Нет issues</div>';
                return;
            }

            const rawData = d;
            let filterText = '';
            let hideProcessed = false;
            const selectedKeys = new Set();

            el.innerHTML = `
                <div class="pf-header-sticky">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                        <div style="display:flex; align-items:center; gap:12px;">
                            <input type="checkbox" id="iss-master-checkbox" title="Выделить всё"
                                   style="width:18px; height:18px; cursor:pointer;">
                            <h3 style="margin:0;">
                                ${esc(d.project)} · ${esc(d.severity)}/${esc(d.quality)} · ${d.total} issues
                            </h3>
                        </div>
                        <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                            <label style="display:flex; align-items:center; gap:4px; font-size:12px; cursor:pointer;"
                                   title="Скрыть issues, у которых уже установлен статус">
                                <input type="checkbox" id="iss-hide-processed"> Скрыть обработанные
                            </label>
                            <input type="text" id="iss-filter"
                                   placeholder="Фильтр по ключу, файлу или сообщению..."
                                   style="padding:6px 10px; border-radius:6px; border:1px solid var(--border);
                                          background:var(--bg); color:var(--text); width:250px;">
                            <button id="iss-bulk-btn" class="btn btn-accent" style="display:none;">
                                Действия с выбранными (0)
                            </button>
                        </div>
                    </div>
                </div>
                <div id="iss-list"></div>
            `;

            const listEl          = el.querySelector('#iss-list');
            const filterInput     = el.querySelector('#iss-filter');
            const hideProcessedCb = el.querySelector('#iss-hide-processed');
            const bulkBtn         = el.querySelector('#iss-bulk-btn');
            const masterCb        = el.querySelector('#iss-master-checkbox');

            const applyFilters = () => {
                const query = filterText.toLowerCase();
                return rawData.issues.filter(it => {
                    const matchesText =
                        (it.key       || '').toLowerCase().includes(query) ||
                        (it.message   || '').toLowerCase().includes(query) ||
                        (it.rule      || '').toLowerCase().includes(query) ||
                        (it.ruleName  || '').toLowerCase().includes(query) ||
                        (it.component || '').toLowerCase().includes(query);
                    if (!matchesText) return false;
                    if (hideProcessed) {
                        const isMarked = it.status
                            && it.status !== 'OPEN'
                            && it.status !== 'REOPENED';
                        if (isMarked) return false;
                    }
                    return true;
                });
            };

            const updateBulkBtn = () => {
                bulkBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                bulkBtn.textContent = `Действия с выбранными (${selectedKeys.size})`;
            };

            const renderList = () => {
                const filtered = applyFilters();

                if (!filtered.length) {
                    listEl.innerHTML = '<div class="empty">Нет issues по фильтру</div>';
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
                                    </tr>
                                </thead>
                                <tbody>
                                    ${filtered.map(it => {
                                        const isMarked = it.status
                                            && it.status !== 'OPEN'
                                            && it.status !== 'REOPENED';
                                        return `
                                        <tr class="pf-issue-row" data-key="${esc(it.key)}"
                                            style="cursor:pointer; ${isMarked ? 'background: rgba(46, 160, 67, 0.08);' : ''}">
                                            <td style="padding-left:16px;">
                                                <input type="checkbox" class="iss-issue-checkbox"
                                                       data-key="${esc(it.key)}"
                                                       ${selectedKeys.has(it.key) ? 'checked' : ''}>
                                            </td>
                                            <td><code>${esc(it.key)}</code></td>
                                            <td class="sev-${esc(it.impactSeverity || it.severity || '')}">
                                                ${esc(it.impactSeverity || it.severity)}
                                            </td>
                                            <td>${esc(it.component)}:${it.line}</td>
                                            <td>${esc(it.rule)}</td>
                                            <td>${statusBadge(it.status)}${esc(it.message)}</td>
                                        </tr>
                                    `}).join('')}
                                </tbody>
                            </table>
                        </div>
                    </div>
                `;

                // ---- master checkbox state ----
                const allFilteredKeys = filtered.map(it => it.key);
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

                // ---- row checkboxes ----
                listEl.querySelectorAll('.iss-issue-checkbox').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        if (e.target.checked) selectedKeys.add(e.target.dataset.key);
                        else                  selectedKeys.delete(e.target.dataset.key);
                        updateBulkBtn();
                    });
                });

                // ---- row interactions ----
                listEl.querySelectorAll('.pf-issue-row').forEach(row => {
                    row.addEventListener('dblclick', (e) => {
                        if (e.target.type === 'checkbox') return;
                        const key = row.dataset.key;
                        const issue = rawData.issues.find(x => x.key === key);
                        openIssueModal(issue, ctx, () => {
                            document.querySelector('.tab[data-tab="issues"]').click();
                        });
                    });

                    row.addEventListener('contextmenu', (e) => {
                        e.preventDefault();
                        const key = row.dataset.key;
                        const issue = rawData.issues.find(x => x.key === key);

                        const targetIssues = selectedKeys.size > 0
                            ? rawData.issues.filter(it => selectedKeys.has(it.key))
                            : [issue];

                        showIssueContextMenu(
                            e.pageX, e.pageY,
                            issue, targetIssues, filtered, ctx,
                            () => {
                                // reloadCb
                                document.querySelector('.tab[data-tab="issues"]').click();
                            },
                            () => {
                                // onSelectAll (все видимые)
                                filtered.forEach(it => selectedKeys.add(it.key));
                                renderList();
                                updateBulkBtn();
                            },
                            () => {
                                // onDeselectAll
                                selectedKeys.clear();
                                renderList();
                                updateBulkBtn();
                            }
                        );
                    });
                });
            };

            filterInput.addEventListener('input', (e) => {
                filterText = e.target.value;
                renderList();
            });

            hideProcessedCb.addEventListener('change', (e) => {
                hideProcessed = e.target.checked;
                renderList();
            });

            masterCb.addEventListener('change', (e) => {
                const filtered = applyFilters();
                if (e.target.checked) filtered.forEach(it => selectedKeys.add(it.key));
                else                  filtered.forEach(it => selectedKeys.delete(it.key));
                renderList();
                updateBulkBtn();
            });

            bulkBtn.addEventListener('click', () => {
                const selectedIssues = rawData.issues.filter(i => selectedKeys.has(i.key));
                openBulkModal(selectedIssues, ctx, () => {
                    selectedKeys.clear();
                    updateBulkBtn();
                    document.querySelector('.tab[data-tab="issues"]').click();
                });
            });

            renderList();
        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};