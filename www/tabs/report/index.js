import { esc, toast, highlightCode } from '../../common.js';
import { showExportModal } from './export-modal.js';
import { openReportIssueModal } from './modals/issue-modal.js';

const NO_TAGS_KEY = '__NO_TAGS__';
const NO_TAGS_LABEL = '(Без тегов)';

export const report = {
    id: 'report',
    title: 'Отчет (Jira/MD/TXT)',
    filters: ['sev', 'qual'],

    async mount(el, ctx) {
        if (!ctx.state.project) {
            el.innerHTML = '<div class="empty">Выберите проект в верхней панели</div>';
            return;
        }

        el.innerHTML = '<div class="empty">Загрузка данных...</div>';

        try {
            // Запрашиваем ВСЕ статусы (hideProcessed=false),
            // иначе total показывает только активные.
            const pBase = new URLSearchParams();
            if (ctx.state.project) pBase.set('project', ctx.state.project.trim());
            pBase.set('sev', ctx.state.sev);
            pBase.set('qual', ctx.state.qual);
            pBase.set('p', ctx.state.page || 1);
            pBase.set('ps', 50);
            pBase.set('hideProcessed', 'false');

            const d = await ctx.api('/api/issues?' + pBase.toString());
            if (ctx.updatePagination) ctx.updatePagination(d.total);

            if (!d.issues || d.issues.length === 0) {
                el.innerHTML = '<div class="empty">Нет замечаний по фильтру</div>';
                return;
            }

            let allRules = [];
            try {
                const pFacet = new URLSearchParams();
                if (ctx.state.project) pFacet.set('project', ctx.state.project.trim());
                pFacet.set('sev', ctx.state.sev);
                pFacet.set('qual', ctx.state.qual);
                pFacet.set('hideProcessed', 'false');

                const rulesRes = await ctx.api('/api/facets?' + pFacet.toString() + '&facet=rules');
                allRules = Object.entries(rulesRes.values || {})
                    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
            } catch (e) {
                console.warn('Не удалось загрузить фасеты правил', e);
            }

            const allTagsSet = new Set();
            d.issues.forEach(it => (it.tags || []).forEach(t => allTagsSet.add(t)));

            const baseTags = Array.from(allTagsSet)
                .filter(t => t !== 'require-fix' && t !== 'not-require-fix')
                .sort((a, b) => a.localeCompare(b));

            const sortedTags = ['require-fix', 'not-require-fix', NO_TAGS_KEY, ...baseTags];

            let filterText = '';
            let statusFilter = 'ALL';

            let selectedTags = new Set();
            let excludedTags = new Set();
            let selectedRules = new Set();

            let tagQuery = '';
            let excludeTagQuery = '';
            let ruleQuery = '';

            let selectedKeys = new Set();
            let isAllSelected = false;

            el.innerHTML = `
                <div class="pf-header-sticky" style="padding-bottom: 10px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                        <div style="display:flex; align-items:center; gap:12px;">
                            <input type="checkbox" id="rep-master-cb" style="width:18px; height:18px; cursor:pointer;" title="Выделить всё">
                            <h3 style="margin:0;" id="rep-header-title">${esc(ctx.state.project)} · Всего: ${d.total}</h3>
                        </div>
                        <button id="rep-export-btn" class="btn btn-accent" style="display:none;" type="button">Действия с выбранными (0)</button>
                    </div>
                    <div style="display:flex; gap:12px; align-items:center; flex-wrap:wrap; margin-top:12px;">
                        <label style="display:flex; align-items:center; gap:4px; font-size:12px;">
                            Статус:
                            <select id="rep-status" class="btn" style="padding:4px 8px;">
                                <option value="ALL">Все</option>
                                <option value="UNPROCESSED">Не обработанные</option>
                                <option value="PROCESSED">Обработанные</option>
                            </select>
                        </label>

                        <details id="rep-tag-details" style="position:relative;">
                            <summary class="btn" style="list-style:none; cursor:pointer; user-select:none; display:inline-flex; gap:4px; align-items:center;">
                                🏷️ Теги (<span id="rep-tag-count">0</span>)
                            </summary>
                            <div style="position:absolute; z-index:100; top:100%; left:0; margin-top:4px;
                                        background:var(--bg); border:1px solid var(--border);
                                        border-radius:6px; padding:8px; min-width:260px;
                                        max-height:340px; box-shadow:0 4px 12px rgba(0,0,0,0.15);">
                                <input type="text" id="rep-tag-search" placeholder="Поиск по тегам..."
                                       style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                                              border-radius:6px; border:1px solid var(--border);
                                              background:var(--bg3); color:var(--text);">
                                <div id="rep-tag-list" style="max-height:220px; overflow-y:auto; display:flex; flex-direction:column; gap:2px; margin-bottom:8px;"></div>
                                <div style="display:flex; justify-content:space-between; gap:8px;">
                                    <button id="rep-tag-clear" class="btn" style="flex:1;" type="button">Сбросить</button>
                                    <button id="rep-tag-close" class="btn" style="flex:1;" type="button">Готово</button>
                                </div>
                            </div>
                        </details>

                        <details id="rep-exclude-tag-details" style="position:relative;">
                            <summary class="btn" style="list-style:none; cursor:pointer; user-select:none; display:inline-flex; gap:4px; align-items:center;">
                                🚫 Исключить теги (<span id="rep-exclude-tag-count">0</span>)
                            </summary>
                            <div style="position:absolute; z-index:100; top:100%; left:0; margin-top:4px;
                                        background:var(--bg); border:1px solid var(--border);
                                        border-radius:6px; padding:8px; min-width:260px;
                                        max-height:340px; box-shadow:0 4px 12px rgba(0,0,0,0.15);">
                                <input type="text" id="rep-exclude-tag-search" placeholder="Поиск по тегам..."
                                       style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                                              border-radius:6px; border:1px solid var(--border);
                                              background:var(--bg3); color:var(--text);">
                                <div id="rep-exclude-tag-list" style="max-height:220px; overflow-y:auto; display:flex; flex-direction:column; gap:2px; margin-bottom:8px;"></div>
                                <div style="display:flex; justify-content:space-between; gap:8px;">
                                    <button id="rep-exclude-tag-clear" class="btn" style="flex:1;" type="button">Сбросить</button>
                                    <button id="rep-exclude-tag-close" class="btn" style="flex:1;" type="button">Готово</button>
                                </div>
                            </div>
                        </details>

                        <details id="rep-rule-details" style="position:relative;">
                            <summary class="btn" style="list-style:none; cursor:pointer; user-select:none; display:inline-flex; gap:4px; align-items:center;">
                                📏 Правила (<span id="rep-rule-count">0</span>)
                            </summary>
                            <div style="position:absolute; z-index:100; top:100%; left:0; margin-top:4px;
                                        background:var(--bg); border:1px solid var(--border);
                                        border-radius:6px; padding:8px; min-width:300px;
                                        max-height:340px; box-shadow:0 4px 12px rgba(0,0,0,0.15);">
                                <input type="text" id="rep-rule-search" placeholder="Поиск по правилам..."
                                       style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                                              border-radius:6px; border:1px solid var(--border);
                                              background:var(--bg3); color:var(--text);">
                                <div id="rep-rule-list" style="max-height:220px; overflow-y:auto; display:flex; flex-direction:column; gap:2px; margin-bottom:8px;"></div>
                                <div style="display:flex; justify-content:space-between; gap:8px;">
                                    <button id="rep-rule-clear" class="btn" style="flex:1;" type="button">Сбросить</button>
                                    <button id="rep-rule-close" class="btn" style="flex:1;" type="button">Готово</button>
                                </div>
                            </div>
                        </details>

                        <input type="text" id="rep-text" placeholder="Фильтр по ключу, файлу или сообщению..." class="btn" style="padding:6px 10px; width:250px;">
                    </div>
                </div>
                <div id="rep-list"></div>
            `;

            const listEl     = el.querySelector('#rep-list');
            const statusSel  = el.querySelector('#rep-status');
            const textInput  = el.querySelector('#rep-text');
            const masterCb   = el.querySelector('#rep-master-cb');
            const exportBtn  = el.querySelector('#rep-export-btn');

            const tagDetails        = el.querySelector('#rep-tag-details');
            const tagSearch         = el.querySelector('#rep-tag-search');
            const tagListEl         = el.querySelector('#rep-tag-list');
            const tagClear          = el.querySelector('#rep-tag-clear');
            const tagClose          = el.querySelector('#rep-tag-close');
            const tagCount          = el.querySelector('#rep-tag-count');

            const excludeTagDetails = el.querySelector('#rep-exclude-tag-details');
            const excludeTagSearch  = el.querySelector('#rep-exclude-tag-search');
            const excludeTagListEl  = el.querySelector('#rep-exclude-tag-list');
            const excludeTagClear   = el.querySelector('#rep-exclude-tag-clear');
            const excludeTagClose   = el.querySelector('#rep-exclude-tag-close');
            const excludeTagCount   = el.querySelector('#rep-exclude-tag-count');

            const ruleDetails       = el.querySelector('#rep-rule-details');
            const ruleSearch        = el.querySelector('#rep-rule-search');
            const ruleListEl        = el.querySelector('#rep-rule-list');
            const ruleClear         = el.querySelector('#rep-rule-clear');
            const ruleClose         = el.querySelector('#rep-rule-close');
            const ruleCount         = el.querySelector('#rep-rule-count');

            const renderTagListTo = (container, query, selectedSet, updateCountFn) => {
                const q = query.toLowerCase();
                const visible = sortedTags.filter(t => {
                    const label = t === NO_TAGS_KEY ? NO_TAGS_LABEL : t;
                    return !q || label.toLowerCase().includes(q);
                });

                if (!visible.length) {
                    container.innerHTML = `<div style="color:var(--text-dim); font-size:12px; padding:4px;">Нет тегов</div>`;
                    return;
                }

                container.innerHTML = visible.map(t => {
                    const label = t === NO_TAGS_KEY ? NO_TAGS_LABEL : t;
                    return `
                    <label style="display:flex; align-items:center; gap:6px; font-size:12px;
                                  cursor:pointer; padding:4px 6px; border-radius:4px;
                                  background:${selectedSet.has(t) ? 'var(--bg3)' : 'transparent'};">
                        <input type="checkbox" class="tag-cb" value="${esc(t)}" ${selectedSet.has(t) ? 'checked' : ''}>
                        <span>${esc(label)}</span>
                    </label>
                `}).join('');

                container.querySelectorAll('.tag-cb').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        if (e.target.checked) selectedSet.add(e.target.value);
                        else selectedSet.delete(e.target.value);
                        updateCountFn();
                        renderList();
                    });
                });
            };

            const renderRuleList = () => {
                const q = ruleQuery.toLowerCase();
                const visible = allRules.filter(([r]) => !q || r.toLowerCase().includes(q));
                if (!visible.length) {
                    ruleListEl.innerHTML = `<div style="color:var(--text-dim); font-size:12px; padding:4px;">Нет правил</div>`;
                    return;
                }
                ruleListEl.innerHTML = visible.map(([r, count]) => `
                    <label style="display:flex; align-items:center; gap:6px; font-size:12px;
                                  cursor:pointer; padding:4px 6px; border-radius:4px;
                                  background:${selectedRules.has(r) ? 'var(--bg3)' : 'transparent'};">
                        <input type="checkbox" class="rep-rule-cb" value="${esc(r)}" ${selectedRules.has(r) ? 'checked' : ''}>
                        <span style="font-family:var(--mono);">${esc(r)}</span>
                        <span style="color:var(--text-dim); margin-left:auto;">${count}</span>
                    </label>
                `).join('');
                ruleListEl.querySelectorAll('.rep-rule-cb').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        if (e.target.checked) selectedRules.add(e.target.value);
                        else selectedRules.delete(e.target.value);
                        updateRuleCount();
                        renderList();
                    });
                });
            };

            const updateTagCount        = () => { tagCount.textContent = selectedTags.size; };
            const updateExcludeTagCount = () => { excludeTagCount.textContent = excludedTags.size; };
            const updateRuleCount       = () => { ruleCount.textContent = selectedRules.size; };

            renderTagListTo(tagListEl, tagQuery, selectedTags, updateTagCount);
            updateTagCount();

            renderTagListTo(excludeTagListEl, excludeTagQuery, excludedTags, updateExcludeTagCount);
            updateExcludeTagCount();

            renderRuleList();
            updateRuleCount();

            tagSearch.addEventListener('input', (e) => { tagQuery = e.target.value; renderTagListTo(tagListEl, tagQuery, selectedTags, updateTagCount); });
            excludeTagSearch.addEventListener('input', (e) => { excludeTagQuery = e.target.value; renderTagListTo(excludeTagListEl, excludeTagQuery, excludedTags, updateExcludeTagCount); });
            ruleSearch.addEventListener('input', (e) => { ruleQuery = e.target.value; renderRuleList(); });

            tagClear.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); selectedTags.clear(); updateTagCount(); renderTagListTo(tagListEl, tagQuery, selectedTags, updateTagCount); renderList(); });
            tagClose.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); tagDetails.removeAttribute('open'); });

            excludeTagClear.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); excludedTags.clear(); updateExcludeTagCount(); renderTagListTo(excludeTagListEl, excludeTagQuery, excludedTags, updateExcludeTagCount); renderList(); });
            excludeTagClose.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); excludeTagDetails.removeAttribute('open'); });

            ruleClear.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); selectedRules.clear(); updateRuleCount(); renderRuleList(); renderList(); });
            ruleClose.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); ruleDetails.removeAttribute('open'); });

            const ctxMenu = document.createElement('div');
            ctxMenu.className = 'ctx-menu';
            ctxMenu.style.display = 'none';
            ctxMenu.innerHTML = `
                <div class="ctx-item" id="ctx-jira">📋 Выгрузить Jira</div>
                <div class="ctx-item" id="ctx-md">📄 Выгрузить Markdown</div>
                <div class="ctx-item" id="ctx-txt">📝 Выгрузить TXT</div>
                <div class="ctx-item" id="ctx-confluence">📋 Выгрузить Confluence (HTML)</div>
                <div class="ctx-item" style="border-top:1px solid var(--border); margin-top:4px; padding-top:8px;" id="ctx-html-files">📦 Выгрузить HTML (по файлам)</div>
                <div class="ctx-item" id="ctx-html-files-teams">📦 Выгрузить HTML (с командами)</div>
            `;
            document.body.appendChild(ctxMenu);
            document.addEventListener('click', () => { ctxMenu.style.display = 'none'; }, { once: true });

            const applyFilters = () => {
                if (!d.issues) return [];
                const q = filterText.toLowerCase();
                let filtered = d.issues.filter(it => {
                    // isProcessed: OPEN/REOPENED = не обработано
                    const isProcessed = it.status && it.status !== 'OPEN' && it.status !== 'REOPENED';
                    if (statusFilter === 'PROCESSED' && !isProcessed) return false;
                    if (statusFilter === 'UNPROCESSED' && isProcessed) return false;

                    if (selectedTags.size > 0) {
                        const itTags = it.tags || [];
                        let hasMatch = false;
                        for (const t of selectedTags) {
                            if (t === NO_TAGS_KEY) {
                                if (itTags.length === 0) hasMatch = true;
                            } else {
                                if (itTags.includes(t)) hasMatch = true;
                            }
                            if (hasMatch) break;
                        }
                        if (!hasMatch) return false;
                    }

                    if (selectedRules.size > 0) {
                        if (!selectedRules.has(it.rule)) return false;
                    }

                    if (!q) return true;
                    return (it.key || '').toLowerCase().includes(q)
                        || (it.message || '').toLowerCase().includes(q)
                        || (it.component || '').toLowerCase().includes(q);
                });

                if (excludedTags.size > 0) {
                    filtered = filtered.filter(it => {
                        const itTags = it.tags || [];
                        let hasExcluded = false;
                        for (const t of excludedTags) {
                            if (t === NO_TAGS_KEY) {
                                if (itTags.length === 0) hasExcluded = true;
                            } else {
                                if (itTags.includes(t)) hasExcluded = true;
                            }
                            if (hasExcluded) break;
                        }
                        return !hasExcluded;
                    });
                }

                return filtered;
            };

            const updateExportBtn = () => {
                if (isAllSelected) {
                    exportBtn.style.display = 'inline-block';
                    exportBtn.textContent = `Действия со всеми (${d.total})`;
                } else {
                    exportBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                    exportBtn.textContent = `Действия с выбранными (${selectedKeys.size})`;
                }
            };

            const syncMasterCheckbox = (filtered) => {
                if (isAllSelected) {
                    masterCb.checked = true;
                    masterCb.indeterminate = false;
                } else {
                    const visibleKeys = filtered.map(it => it.key);
                    const allVisible = visibleKeys.length > 0 && visibleKeys.every(k => selectedKeys.has(k));
                    masterCb.checked = allVisible;
                    masterCb.indeterminate = !allVisible && visibleKeys.some(k => selectedKeys.has(k));
                }
            };

            const renderList = () => {
                let filtered = applyFilters();

                if (!filtered.length) {
                    listEl.innerHTML = '<div class="empty">Нет данных по фильтру</div>';
                    masterCb.checked = false;
                    updateExportBtn();
                    return;
                }

                syncMasterCheckbox(filtered);

                const groups = new Map();
                filtered.forEach(it => {
                    const file = it.component || '(unknown)';
                    if (!groups.has(file)) groups.set(file, []);
                    groups.get(file).push(it);
                });

                const sortedFiles = [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));

                let html = '';

                sortedFiles.forEach(([fileName, fileIssues]) => {
                    html += `
                        <div class="card" style="margin-bottom: 16px; padding: 0; overflow: hidden;">
                            <div class="pf-file-header" style="padding: 12px 16px; background: var(--bg3); border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center;">
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <h4 style="margin: 0; text-transform: none;">📁 ${esc(fileName)} <span style="font-weight: normal; color: var(--text-dim); font-size: 13px;">— ${fileIssues.length}</span></h4>
                                </div>
                            </div>

                            <div style="overflow-x: auto;">
                                <table>
                                    <thead>
                                        <tr>
                                            <th style="width:30px; padding-left:16px;"></th>
                                            <th>Правило</th>
                                            <th>Severity</th>
                                            <th>Сообщение</th>
                                            <th>Теги</th>
                                            <th>Код</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        ${fileIssues.map(it => {
                                            const tagsHtml = (it.tags && it.tags.length > 0)
                                                ? it.tags.map(t => `<span class="tag" style="margin:1px;">${esc(t)}</span>`).join('')
                                                : `<span class="tag" style="margin:1px; opacity:0.6; font-style:italic;">${NO_TAGS_LABEL}</span>`;

                                            const isChecked = selectedKeys.has(it.key) || isAllSelected ? 'checked' : '';
                                            return `
                                                <tr class="rep-row" data-key="${esc(it.key)}" data-file="${esc(fileName)}" style="cursor:pointer;">
                                                    <td style="padding-left:16px;"><input type="checkbox" class="rep-cb" data-key="${esc(it.key)}" ${isChecked}></td>
                                                    <td><code>${esc(it.rule)}</code></td>
                                                    <td class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</td>
                                                    <td>${esc(it.message)}</td>
                                                    <td>${tagsHtml}</td>
                                                    <td><button class="btn rep-btn-toggle" data-key="${esc(it.key)}" style="padding:2px 8px; font-size:11px;" type="button">Показать</button></td>
                                                </tr>
                                                <tr id="snippet-row-${it.key}" style="display:none; background:var(--bg3);">
                                                    <td colspan="6" style="padding:12px 16px;">
                                                        <div class="empty" id="snippet-content-${it.key}">Загрузка...</div>
                                                    </td>
                                                </tr>
                                            `;
                                        }).join('')}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    `;
                });

                listEl.innerHTML = html;

                // --- Чекбоксы строк ---
                listEl.querySelectorAll('.rep-cb').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        if (isAllSelected) {
                            isAllSelected = false;
                            filtered.forEach(it => {
                                if (it.key !== e.target.dataset.key) selectedKeys.add(it.key);
                            });
                        } else {
                            if (e.target.checked) selectedKeys.add(e.target.dataset.key);
                            else selectedKeys.delete(e.target.dataset.key);
                        }
                        updateExportBtn();
                        syncMasterCheckbox(filtered);
                    });
                });

                // --- Кнопка "Показать/Скрыть" сниппета ---
                listEl.querySelectorAll('.rep-btn-toggle').forEach(btn => {
                    btn.addEventListener('click', async (e) => {
                        e.stopPropagation();
                        const key = btn.dataset.key;
                        const row = listEl.querySelector(`#snippet-row-${key}`);
                        const content = listEl.querySelector(`#snippet-content-${key}`);

                        if (row.style.display === 'none') {
                            row.style.display = 'table-row';
                            btn.textContent = 'Скрыть';
                            if (!content.dataset.loaded) {
                                try {
                                    const p = new URLSearchParams({ project: ctx.state.project, issueKey: key });
                                    const res = await ctx.api('/api/snippet?' + p);
                                    const snippet = res.snippet || [];
                                    if (snippet.length > 0) {
                                        content.className = 'code';
                                        content.innerHTML = highlightCode(snippet.join('\n'));
                                    } else {
                                        content.className = 'empty';
                                        content.textContent = 'Код недоступен';
                                    }
                                } catch (err) {
                                    content.className = 'empty';
                                    content.textContent = 'Ошибка загрузки: ' + err.message;
                                }
                                content.dataset.loaded = '1';
                            }
                        } else {
                            row.style.display = 'none';
                            btn.textContent = 'Показать';
                        }
                    });
                });

                // --- Клик / двойной клик / правый клик по строке ---
                listEl.querySelectorAll('.rep-row').forEach(tr => {
                    // Одиночный клик — переключает чекбокс, но с debounce 250мс,
                    // чтобы dblclick (открытие модалки) не привёл к двойному переключению.
                    let clickTimeout = null;

                    tr.addEventListener('click', (e) => {
                        if (e.target.type === 'checkbox' || e.target.closest('.rep-btn-toggle')) return;
                        if (clickTimeout) return; // второй клик из серии dblclick — игнорируем

                        clickTimeout = setTimeout(() => {
                            clickTimeout = null;
                            const cb = tr.querySelector('.rep-cb');
                            cb.checked = !cb.checked;
                            cb.dispatchEvent(new Event('change'));
                        }, 250);
                    });

                    // Двойной клик — открывает модалку
                    tr.addEventListener('dblclick', (e) => {
                        if (e.target.type === 'checkbox' || e.target.closest('.rep-btn-toggle')) return;
                        if (clickTimeout) { clearTimeout(clickTimeout); clickTimeout = null; }

                        const key = tr.dataset.key;
                        const issue = rawDataSafe(d).issues.find(x => x.key === key);
                        if (!issue) return;

                        openReportIssueModal(issue, ctx, () => {
                            // Пере-рендер вкладки «Отчет» после сохранения
                            document.querySelector('.tab[data-tab="report"]')?.click();
                        });
                    });

                    // Правый клик — контекстное меню экспорта
                    tr.addEventListener('contextmenu', (e) => {
                        e.preventDefault();
                        if (selectedKeys.size === 0 && !isAllSelected) {
                            selectedKeys.add(tr.dataset.key);
                            const cb = tr.querySelector('.rep-cb');
                            if (cb) cb.checked = true;
                            updateExportBtn();
                            syncMasterCheckbox(filtered);
                        }

                        ctxMenu.style.display = 'block';
                        ctxMenu.style.left = e.pageX + 'px';
                        ctxMenu.style.top = e.pageY + 'px';
                    });
                });
            };

            // Вспомогательная обёртка: d - результат /api/issues, у него есть .issues
            const rawDataSafe = (obj) => obj || { issues: [] };

            const startExport = async (format) => {
                let keys = null;

                if (!isAllSelected) {
                    const filtered = applyFilters();
                    const list = [];
                    filtered.forEach(it => {
                        if (selectedKeys.has(it.key)) {
                            list.push(it.key);
                        }
                    });
                    if (list.length === 0) {
                        toast('Ничего не выбрано', true);
                        return;
                    }
                    keys = list;
                }

                const filters = {
                    sev: ctx.state.sev,
                    qual: ctx.state.qual,
                    tags: Array.from(selectedTags),
                    excludedTags: Array.from(excludedTags),
                    rules: Array.from(selectedRules),
                    filterText: filterText,
                    statusFilter: statusFilter
                };

                await showExportModal(keys, format, ctx, ctx.state.project, filters);
            };

            exportBtn.addEventListener('click', () => {
                if (selectedKeys.size === 0 && !isAllSelected) return;
                const rect = exportBtn.getBoundingClientRect();
                ctxMenu.style.display = 'block';
                ctxMenu.style.left = (rect.left + window.scrollX) + 'px';
                ctxMenu.style.top  = (rect.bottom + window.scrollY) + 'px';
            });

            ctxMenu.querySelector('#ctx-jira').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('jira'); });
            ctxMenu.querySelector('#ctx-md').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('md'); });
            ctxMenu.querySelector('#ctx-txt').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('txt'); });
            ctxMenu.querySelector('#ctx-confluence').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('confluence'); });
            ctxMenu.querySelector('#ctx-html-files').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('html-files'); });
            ctxMenu.querySelector('#ctx-html-files-teams').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('html-files-teams'); });

            masterCb.addEventListener('change', (e) => {
                if (e.target.checked) {
                    isAllSelected = true;
                    selectedKeys.clear();
                } else {
                    isAllSelected = false;
                    selectedKeys.clear();
                }
                renderList();
                updateExportBtn();
            });

            statusSel.addEventListener('change', e => { statusFilter = e.target.value; renderList(); });
            textInput.addEventListener('input', e => { filterText = e.target.value; renderList(); });

            renderList();

        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};