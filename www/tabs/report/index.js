import { esc, toast, highlightCode } from '../../common.js';
import { showExportModal } from './export-modal.js';

// Специальный ключ для ошибок без тегов
const NO_TAGS_KEY = '__NO_TAGS__';
const NO_TAGS_LABEL = '(Без тегов)';

export const report = {
    id: 'report',
    title: 'Отчет (Jira/MD/TXT)',
    filters: [],

    async mount(el, ctx) {
        if (!ctx.state.project) {
            el.innerHTML = '<div class="empty">Выберите проект в верхней панели</div>';
            return;
        }

        el.innerHTML = '<div class="empty">Загрузка данных...</div>';

        try {
            const [openRes, accRes] = await Promise.all([
                ctx.api(`/api/issues?project=${ctx.state.project}&sev=ALL&qual=ALL`),
                ctx.api(`/api/accepted?project=${ctx.state.project}&sev=ALL&qual=ALL`)
            ]);

            const openIssues = (openRes.issues || []).map(i => ({ ...i, isProcessed: false }));
            const accIssues = (accRes.issues || []).map(i => ({ ...i, isProcessed: true }));
            const allIssues = [...openIssues, ...accIssues];

            if (!allIssues.length) {
                el.innerHTML = '<div class="empty">Нет замечаний</div>';
                return;
            }

            const allTagsSet = new Set();
            const allRulesSet = new Set();
            allIssues.forEach(it => {
                (it.tags || []).forEach(t => allTagsSet.add(t));
                if (it.rule) allRulesSet.add(it.rule);
            });

            // Формируем список тегов: require-fix, not-require-fix, __NO_TAGS__, потом остальные по алфавиту
            const baseTags = Array.from(allTagsSet)
                .filter(t => t !== 'require-fix' && t !== 'not-require-fix')
                .sort((a, b) => a.localeCompare(b));

            const sortedTags = ['require-fix', 'not-require-fix', NO_TAGS_KEY, ...baseTags];

            const sortedRules = Array.from(allRulesSet).sort();

            let filterText = '';
            let statusFilter = 'ALL';

            let selectedTags = new Set();
            let excludedTags = new Set();

            let selectedRules = new Set();

            let tagQuery = '';
            let excludeTagQuery = '';
            let ruleQuery = '';

            let sortField = 'severity';
            let sortAsc = false;

            const selectedKeys = new Set();

            el.innerHTML = `
                <div class="pf-header-sticky" style="padding-bottom: 10px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                        <div style="display:flex; align-items:center; gap:12px;">
                            <input type="checkbox" id="rep-master-cb" style="width:18px; height:18px; cursor:pointer;" title="Выделить всё">
                            <h3 style="margin:0;" id="rep-header-title">${esc(ctx.state.project)} · Всего: ${allIssues.length}</h3>
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

                        <!-- Multi-select Включить Теги -->
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

                        <!-- Multi-select ИСКЛЮЧИТЬ Теги -->
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

                        <!-- Multi-select Правила -->
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

            const listEl = el.querySelector('#rep-list');
            const statusSel = el.querySelector('#rep-status');
            const textInput = el.querySelector('#rep-text');
            const masterCb = el.querySelector('#rep-master-cb');
            const exportBtn = el.querySelector('#rep-export-btn');

            // Элементы управления ВКЛЮЧЕННЫМИ тегами
            const tagDetails = el.querySelector('#rep-tag-details');
            const tagSearch = el.querySelector('#rep-tag-search');
            const tagListEl = el.querySelector('#rep-tag-list');
            const tagClear = el.querySelector('#rep-tag-clear');
            const tagClose = el.querySelector('#rep-tag-close');
            const tagCount = el.querySelector('#rep-tag-count');

            // Элементы управления ИСКЛЮЧЕННЫМИ тегами
            const excludeTagDetails = el.querySelector('#rep-exclude-tag-details');
            const excludeTagSearch = el.querySelector('#rep-exclude-tag-search');
            const excludeTagListEl = el.querySelector('#rep-exclude-tag-list');
            const excludeTagClear = el.querySelector('#rep-exclude-tag-clear');
            const excludeTagClose = el.querySelector('#rep-exclude-tag-close');
            const excludeTagCount = el.querySelector('#rep-exclude-tag-count');

            // Элементы управления правилами
            const ruleDetails = el.querySelector('#rep-rule-details');
            const ruleSearch = el.querySelector('#rep-rule-search');
            const ruleListEl = el.querySelector('#rep-rule-list');
            const ruleClear = el.querySelector('#rep-rule-clear');
            const ruleClose = el.querySelector('#rep-rule-close');
            const ruleCount = el.querySelector('#rep-rule-count');

            // Универсальная функция рендера списка тегов
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
                        renderList(); // Обновляем основной список сразу
                    });
                });
            };

            const renderRuleList = () => {
                const q = ruleQuery.toLowerCase();
                const visible = sortedRules.filter(r => !q || r.toLowerCase().includes(q));
                if (!visible.length) {
                    ruleListEl.innerHTML = `<div style="color:var(--text-dim); font-size:12px; padding:4px;">Нет правил</div>`;
                    return;
                }
                ruleListEl.innerHTML = visible.map(r => `
                    <label style="display:flex; align-items:center; gap:6px; font-size:12px;
                                  cursor:pointer; padding:4px 6px; border-radius:4px;
                                  background:${selectedRules.has(r) ? 'var(--bg3)' : 'transparent'};">
                        <input type="checkbox" class="rep-rule-cb" value="${esc(r)}" ${selectedRules.has(r) ? 'checked' : ''}>
                        <span style="font-family:var(--mono);">${esc(r)}</span>
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

            const updateTagCount = () => { tagCount.textContent = selectedTags.size; };
            const updateExcludeTagCount = () => { excludeTagCount.textContent = excludedTags.size; };
            const updateRuleCount = () => { ruleCount.textContent = selectedRules.size; };

            // Инициализация списков
            renderTagListTo(tagListEl, tagQuery, selectedTags, updateTagCount);
            updateTagCount();

            renderTagListTo(excludeTagListEl, excludeTagQuery, excludedTags, updateExcludeTagCount);
            updateExcludeTagCount();

            renderRuleList();
            updateRuleCount();

            // Обработчики поиска
            tagSearch.addEventListener('input', (e) => { tagQuery = e.target.value; renderTagListTo(tagListEl, tagQuery, selectedTags, updateTagCount); });
            excludeTagSearch.addEventListener('input', (e) => { excludeTagQuery = e.target.value; renderTagListTo(excludeTagListEl, excludeTagQuery, excludedTags, updateExcludeTagCount); });
            ruleSearch.addEventListener('input', (e) => { ruleQuery = e.target.value; renderRuleList(); });

            // Обработчики сброса и закрытия (с preventDefault)
            tagClear.addEventListener('click', (e) => {
                e.preventDefault(); e.stopPropagation();
                selectedTags.clear(); updateTagCount();
                renderTagListTo(tagListEl, tagQuery, selectedTags, updateTagCount);
                renderList();
            });
            tagClose.addEventListener('click', (e) => {
                e.preventDefault(); e.stopPropagation();
                tagDetails.removeAttribute('open');
            });

            excludeTagClear.addEventListener('click', (e) => {
                e.preventDefault(); e.stopPropagation();
                excludedTags.clear(); updateExcludeTagCount();
                renderTagListTo(excludeTagListEl, excludeTagQuery, excludedTags, updateExcludeTagCount);
                renderList();
            });
            excludeTagClose.addEventListener('click', (e) => {
                e.preventDefault(); e.stopPropagation();
                excludeTagDetails.removeAttribute('open');
            });

            ruleClear.addEventListener('click', (e) => {
                e.preventDefault(); e.stopPropagation();
                selectedRules.clear(); updateRuleCount(); renderRuleList(); renderList();
            });
            ruleClose.addEventListener('click', (e) => {
                e.preventDefault(); e.stopPropagation();
                ruleDetails.removeAttribute('open');
            });

            // Контекстное меню
            const ctxMenu = document.createElement('div');
            ctxMenu.className = 'ctx-menu';
            ctxMenu.style.display = 'none';
            ctxMenu.innerHTML = `
                <div class="ctx-item" id="ctx-jira">📋 Выгрузить Jira</div>
                <div class="ctx-item" id="ctx-md">📄 Выгрузить Markdown</div>
                <div class="ctx-item" id="ctx-txt">📝 Выгрузить TXT</div>
                <div class="ctx-item" id="ctx-confluence">📋 Выгрузить Confluence (HTML)</div>
                <div class="ctx-item" style="border-top:1px solid var(--border); margin-top:4px; padding-top:8px;" id="ctx-html-files">📦 Выгрузить HTML (по файлам)</div>
            `;
            document.body.appendChild(ctxMenu);
            document.addEventListener('click', () => { ctxMenu.style.display = 'none'; }, { once: true });

            const applyFilters = () => {
                const q = filterText.toLowerCase();

                // Шаг 1: Применяем основные фильтры (включая выбранные теги)
                let filtered = allIssues.filter(it => {
                    if (statusFilter === 'PROCESSED' && !it.isProcessed) return false;
                    if (statusFilter === 'UNPROCESSED' && it.isProcessed) return false;

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

                // Шаг 2: Применяем исключение тегов к результату
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

                        return !hasExcluded; // Оставляем только те, у которых нет исключаемых тегов
                    });
                }

                return filtered;
            };

            const applySort = (issues) => {
                return [...issues].sort((a, b) => {
                    let valA, valB;
                    if (sortField === 'severity') {
                        const order = { 'BLOCKER': 5, 'CRITICAL': 4, 'HIGH': 3, 'MAJOR': 2, 'MEDIUM': 1, 'MINOR': 0, 'LOW': -1, 'INFO': -2 };
                        valA = order[a.impactSeverity || a.severity] || 0;
                        valB = order[b.impactSeverity || b.severity] || 0;
                    } else if (sortField === 'line') {
                        valA = a.line || 0;
                        valB = b.line || 0;
                    } else {
                        valA = String(a[sortField] || '').toLowerCase();
                        valB = String(b[sortField] || '').toLowerCase();
                    }

                    if (valA < valB) return sortAsc ? -1 : 1;
                    if (valA > valB) return sortAsc ? 1 : -1;
                    return 0;
                });
            };

            const updateExportBtn = () => {
                exportBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                exportBtn.textContent = `Действия с выбранными (${selectedKeys.size})`;
            };

            const syncMasterCheckbox = (filtered) => {
                const visibleKeys = filtered.map(it => it.key);
                const allVisible = visibleKeys.length > 0 && visibleKeys.every(k => selectedKeys.has(k));
                masterCb.checked = allVisible;
                masterCb.indeterminate = !allVisible && visibleKeys.some(k => selectedKeys.has(k));
            };

            const syncFileCheckbox = (fileName, fileIssues) => {
                const cb = listEl.querySelector(`.rep-file-cb[data-file="${fileName.replace(/"/g, '\\"')}"]`);
                if (!cb) return;
                const allChecked = fileIssues.length > 0 && fileIssues.every(it => selectedKeys.has(it.key));
                const someChecked = fileIssues.some(it => selectedKeys.has(it.key));
                cb.checked = allChecked;
                cb.indeterminate = someChecked && !allChecked;
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

                // Группировка по файлам
                const groups = new Map();
                filtered.forEach(it => {
                    const file = it.component || '(unknown)';
                    if (!groups.has(file)) groups.set(file, []);
                    groups.get(file).push(it);
                });

                // Сортировка файлов по алфавиту
                const sortedFiles = [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));

                let html = '';

                sortedFiles.forEach(([fileName, fileIssues]) => {
                    // Сортируем ошибки внутри файла согласно выбранному полю
                    const sortedIssues = applySort(fileIssues);

                    html += `
                        <div class="card" style="margin-bottom: 16px; padding: 0; overflow: hidden;">
                            <div class="pf-file-header" style="padding: 12px 16px; background: var(--bg3); border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center;">
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <input type="checkbox" class="rep-file-cb" data-file="${esc(fileName)}" style="width:16px; height:16px; cursor:pointer;">
                                    <h4 style="margin: 0; text-transform: none;">📁 ${esc(fileName)} <span style="font-weight: normal; color: var(--text-dim); font-size: 13px;">— ${fileIssues.length}</span></h4>
                                </div>
                            </div>

                            <div style="overflow-x: auto;">
                                <table>
                                    <thead>
                                        <tr>
                                            <th style="width:30px; padding-left:16px;"></th>
                                            <th class="rep-sort" data-field="rule" style="cursor:pointer;">Правило ${sortField === 'rule' ? (sortAsc ? '▲' : '▼') : ''}</th>
                                            <th class="rep-sort" data-field="severity" style="cursor:pointer;">Severity ${sortField === 'severity' ? (sortAsc ? '▲' : '▼') : ''}</th>
                                            <th class="rep-sort" data-field="message" style="cursor:pointer;">Сообщение ${sortField === 'message' ? (sortAsc ? '▲' : '▼') : ''}</th>
                                            <th>Теги</th>
                                            <th>Код</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        ${sortedIssues.map(it => {
                                            // Отрисовка тегов. Если тегов нет, показываем серый бейдж
                                            const tagsHtml = (it.tags && it.tags.length > 0)
                                                ? it.tags.map(t => `<span class="tag" style="margin:1px;">${esc(t)}</span>`).join('')
                                                : `<span class="tag" style="margin:1px; opacity:0.6; font-style:italic;">${NO_TAGS_LABEL}</span>`;

                                            const isChecked = selectedKeys.has(it.key) ? 'checked' : '';
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

                // Привязываем события к сгенерированному HTML
                listEl.querySelectorAll('.rep-sort').forEach(th => {
                    th.addEventListener('click', () => {
                        const field = th.dataset.field;
                        if (sortField === field) sortAsc = !sortAsc;
                        else { sortField = field; sortAsc = true; }
                        renderList();
                    });
                });

                // Чекбоксы конкретных ошибок
                listEl.querySelectorAll('.rep-cb').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        if (e.target.checked) selectedKeys.add(e.target.dataset.key);
                        else selectedKeys.delete(e.target.dataset.key);

                        const file = cb.closest('tr').dataset.file;
                        const fileIssues = groups.get(file);
                        syncFileCheckbox(file, fileIssues);

                        updateExportBtn();
                        syncMasterCheckbox(filtered);
                    });
                });

                // Чекбоксы групп файлов
                listEl.querySelectorAll('.rep-file-cb').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        const file = e.target.dataset.file;
                        const fileIssues = groups.get(file);
                        const isChecked = e.target.checked;

                        fileIssues.forEach(it => {
                            if (isChecked) selectedKeys.add(it.key);
                            else selectedKeys.delete(it.key);
                        });

                        // Обновляем состояние чекбоксов в этом файле
                        const card = cb.closest('.card');
                        card.querySelectorAll('.rep-cb').forEach(rowCb => {
                            rowCb.checked = isChecked;
                        });

                        updateExportBtn();
                        syncMasterCheckbox(filtered);
                    });
                });

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
                                    const d = await ctx.api('/api/snippet?' + p);
                                    const snippet = d.snippet || [];
                                    const issue = allIssues.find(i => i.key === key);
                                    if (issue) issue._snippet = snippet;

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

                listEl.querySelectorAll('.rep-row').forEach(tr => {
                    // Клик по строке переключает чекбокс
                    tr.addEventListener('click', (e) => {
                        if (e.target.type === 'checkbox' || e.target.closest('.rep-btn-toggle')) return;

                        const cb = tr.querySelector('.rep-cb');
                        cb.checked = !cb.checked;
                        cb.dispatchEvent(new Event('change'));
                    });

                    tr.addEventListener('contextmenu', (e) => {
                        e.preventDefault();
                        const key = tr.dataset.key;
                        if (selectedKeys.size === 0) {
                            selectedKeys.add(key);
                            // Обновляем чекбокс без перерисовки всей таблицы
                            const cb = tr.querySelector('.rep-cb');
                            if (cb) cb.checked = true;

                            const file = tr.dataset.file;
                            const fileIssues = groups.get(file);
                            syncFileCheckbox(file, fileIssues);

                            updateExportBtn();
                            syncMasterCheckbox(filtered);
                        }

                        ctxMenu.style.display = 'block';
                        ctxMenu.style.left = e.pageX + 'px';
                        ctxMenu.style.top = e.pageY + 'px';
                    });
                });
            };

            const startExport = async (format) => {
                const selectedIssues = allIssues.filter(it => selectedKeys.has(it.key));
                if (!selectedIssues.length) { toast('Ничего не выбрано', true); return; }
                await showExportModal(selectedIssues, format, ctx, ctx.state.project);
            };

            exportBtn.addEventListener('click', () => {
                if (selectedKeys.size === 0) return;
                const rect = exportBtn.getBoundingClientRect();
                ctxMenu.style.display = 'block';
                ctxMenu.style.left = (rect.left + window.scrollX) + 'px';
                ctxMenu.style.top = (rect.bottom + window.scrollY) + 'px';
            });

            ctxMenu.querySelector('#ctx-jira').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('jira'); });
            ctxMenu.querySelector('#ctx-md').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('md'); });
            ctxMenu.querySelector('#ctx-txt').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('txt'); });
            ctxMenu.querySelector('#ctx-confluence').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('confluence'); });
            ctxMenu.querySelector('#ctx-html-files').addEventListener('click', () => { ctxMenu.style.display = 'none'; startExport('html-files'); });

            masterCb.addEventListener('change', (e) => {
                let filtered = applyFilters();
                if (e.target.checked) filtered.forEach(it => selectedKeys.add(it.key));
                else filtered.forEach(it => selectedKeys.delete(it.key));
                renderList();
                updateExportBtn();
            });

            statusSel.addEventListener('change', e => {
                statusFilter = e.target.value;
                renderList();
            });

            textInput.addEventListener('input', e => {
                filterText = e.target.value;
                renderList();
            });

            renderList();

        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};