// ============================================================
// Модал статистики по проектам.
//
// Поток:
//   1. Загрузить список проектов через /api/projects
//   2. Загрузить доступные теги через /api/tags
//   3. Загрузить правила через /api/facets?project=<top-bar>&facet=rules
//   4. Показать список проектов с чекбоксами + многострочный фильтр
//   5. Пользователь выбирает проекты и жмёт «Сформировать отчёт»
//   6. POST /api/stats/projects-stream — SSE-поток с прогрессом
//   7. По событию complete — скачать HTML через /api/export-download
// ============================================================

import { esc, toast } from '../../common.js';

// ---------- Константы ----------

const NO_TAGS_KEY   = '__NO_TAGS__';
const NO_TAGS_LABEL = '(Без тегов)';

const STATUS_OPTIONS = [
    { value: 'OPEN',           label: 'OPEN — открыто' },
    { value: 'CONFIRMED',      label: 'CONFIRMED — подтверждено' },
    { value: 'REOPENED',       label: 'REOPENED — переоткрыто' },
    { value: 'ACCEPTED',       label: 'ACCEPTED — принято' },
    { value: 'FALSE_POSITIVE', label: 'FALSE_POSITIVE — ложно' },
    { value: 'WONTFIX',        label: 'WONTFIX — не исправлять' },
    { value: 'FIXED',          label: 'FIXED — исправлено' },
    { value: 'CLOSED',         label: 'CLOSED — закрыто' },
    { value: 'RESOLVED',       label: 'RESOLVED — решено' }
];

const DEFAULT_SELECTED_STATUSES = ['OPEN', 'CONFIRMED', 'REOPENED'];

// ---------- Вспомогательные функции: фильтр проектов ----------

function parseProjectTokens(raw) {
    if (!raw) return [];
    return String(raw)
        .split(/[\s,;]+/)
        .map(t => t.trim().toLowerCase())
        .filter(t => t.length > 0);
}

function projectMatchesAnyToken(project, tokens) {
    if (!tokens || tokens.length === 0) return true;
    const key  = String(project.key  || '').toLowerCase();
    const name = String(project.name || '').toLowerCase();
    return tokens.some(tok => key.includes(tok) || name.includes(tok));
}

// ---------- Вспомогательные функции: скачивание ----------

function timestampNow() {
    const n = new Date();
    const pad = x => String(x).padStart(2, '0');
    return `${n.getFullYear()}${pad(n.getMonth() + 1)}${pad(n.getDate())}`
         + `_${pad(n.getHours())}${pad(n.getMinutes())}${pad(n.getSeconds())}`;
}

async function triggerDownload(filePath, downloadName, fallbackName) {
    let name = downloadName;
    if (!name) {
        const safe = String(fallbackName || 'stats_projects')
            .replace(/[^A-Za-z0-9._-]/g, '_') || 'stats_projects';
        name = `${safe}_${timestampNow()}.html`;
        console.warn('[stats] downloadName не пришёл — сгенерирован локально:', name);
    }

    const qp = new URLSearchParams();
    qp.set('file', filePath);
    qp.set('name', name);
    const url = '/api/export-download?' + qp.toString();

    try {
        const resp = await fetch(url, { method: 'GET' });
        if (!resp.ok) {
            const txt = await resp.text();
            throw new Error(`HTTP ${resp.status}: ${txt.slice(0, 200)}`);
        }
        const blob = await resp.blob();
        const blobUrl = URL.createObjectURL(blob);

        let filename = name;
        const cd = resp.headers.get('Content-Disposition');
        if (cd) {
            const mStar = cd.match(/filename\*=UTF-8''([^;]+)/i);
            if (mStar) {
                try { filename = decodeURIComponent(mStar[1]); } catch (_) {}
            }
        }

        const a = document.createElement('a');
        a.href = blobUrl;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(blobUrl), 5000);
    } catch (e) {
        console.error('[stats] Ошибка скачивания:', e);
        toast('Ошибка скачивания: ' + e.message, true);
    }
}

// ---------- Модал ----------

export async function showStatsModal(ctx) {
    // ===== 1. Загрузка проектов =====
    let projects = [];
    try {
        const d = await ctx.api('/api/projects');
        projects = (d.projects || [])
            .slice()
            .sort((a, b) => String(a.key).localeCompare(String(b.key)));
    } catch (e) {
        toast('Не удалось загрузить список проектов: ' + e.message, true);
        return;
    }
    if (projects.length === 0) {
        toast('Список проектов пуст', true);
        return;
    }

    // ===== 2. Загрузка тегов =====
    let allTags = [];
    try {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        const res = await ctx.api('/api/tags?' + p.toString());
        allTags = (res.tags || []).filter(t => typeof t === 'string' && t.length > 0);
    } catch (e) {
        console.warn('[stats] не удалось загрузить теги:', e);
    }

    // Сортируем по правилу родительской вкладки:
    // require-fix, not-require-fix, __NO_TAGS__, остальные по алфавиту
    const baseTags = allTags
        .filter(t => t !== 'require-fix' && t !== 'not-require-fix')
        .sort((a, b) => a.localeCompare(b));
    const sortedTags = ['require-fix', 'not-require-fix', NO_TAGS_KEY, ...baseTags];

    // ===== 3. Загрузка правил (из проекта верхней панели) =====
    let allRules = [];
    let rulesHint = '';
    if (ctx.state.project) {
        try {
            const p = new URLSearchParams();
            p.set('project', ctx.state.project);
            p.set('facet', 'rules');
            p.set('sev', 'ALL');
            p.set('qual', 'ALL');
            p.set('hideProcessed', 'false');
            const res = await ctx.api('/api/facets?' + p.toString());
            allRules = Object.entries(res.values || {})
                .filter(([, c]) => Number(c) > 0)
                .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
                .map(([rule, count]) => ({ rule, count: Number(count) }));
            rulesHint = `Правила для проекта в верхней панели: <code>${esc(ctx.state.project)}</code>`;
        } catch (e) {
            console.warn('[stats] не удалось загрузить правила:', e);
            rulesHint = 'Не удалось загрузить правила — можно не использовать этот фильтр';
        }
    } else {
        rulesHint = 'Выберите проект в верхней панели, чтобы загрузить список правил';
    }

    // ===== 4. Построение модала =====
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.style.zIndex = '2000';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 1000px;">
            <div class="modal-header">
                <h3 style="margin:0;">Статистика по проектам</h3>
                <button class="modal-close" type="button">&times;</button>
            </div>
            <div class="modal-body">

                <!-- ФИЛЬТР: многострочный ввод -->
                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; color:var(--text-dim); margin-bottom:4px;">
                        Поиск проектов — можно несколько через запятую, пробел или каждый с новой строки:
                    </label>
                    <textarea id="stats-search"
                              rows="2"
                              placeholder="Например:&#10;MEDMIS-MISmobile.max-service, MEDMIS-tMIS&#10;или каждый проект с новой строки"
                              style="width:100%; box-sizing:border-box; padding:8px 12px; margin-bottom:8px;
                                     border-radius:6px; border:1px solid var(--border);
                                     background:var(--bg); color:var(--text); font-size:13px;
                                     resize:vertical; min-height:56px; font-family:var(--mono);"></textarea>
                    <div id="stats-search-hint"
                         style="font-size:11px; color:var(--text-dim);"></div>
                </div>

                <!-- Фильтры: Теги / Исключить / Правила / Статусы / Ветка -->
                <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;
                            margin-bottom:12px; padding:10px; background:var(--bg3);
                            border:1px solid var(--border); border-radius:6px;">

                    <!-- Включённые теги -->
                    <details id="stats-tag-details" style="position:relative;">
                        <summary class="btn" style="list-style:none; cursor:pointer;
                                    user-select:none; display:inline-flex; gap:4px; align-items:center;">
                            🏷️ Теги (<span id="stats-tag-count">0</span>)
                        </summary>
                        <div style="position:absolute; z-index:200; top:100%; left:0; margin-top:4px;
                                    background:var(--bg); border:1px solid var(--border);
                                    border-radius:6px; padding:8px; min-width:260px;
                                    max-height:340px; box-shadow:0 4px 12px rgba(0,0,0,0.15);">
                            <input type="text" id="stats-tag-search" placeholder="Поиск по тегам..."
                                   style="width:100%; box-sizing:border-box; padding:6px 10px;
                                          margin-bottom:8px; border-radius:6px;
                                          border:1px solid var(--border);
                                          background:var(--bg3); color:var(--text);">
                            <div id="stats-tag-list"
                                 style="max-height:220px; overflow-y:auto; display:flex;
                                        flex-direction:column; gap:2px; margin-bottom:8px;"></div>
                            <div style="display:flex; justify-content:space-between; gap:8px;">
                                <button id="stats-tag-clear" class="btn" style="flex:1;" type="button">Сбросить</button>
                                <button id="stats-tag-close" class="btn" style="flex:1;" type="button">Готово</button>
                            </div>
                        </div>
                    </details>

                    <!-- Исключённые теги -->
                    <details id="stats-exclude-tag-details" style="position:relative;">
                        <summary class="btn" style="list-style:none; cursor:pointer;
                                    user-select:none; display:inline-flex; gap:4px; align-items:center;">
                            🚫 Исключить теги (<span id="stats-exclude-tag-count">0</span>)
                        </summary>
                        <div style="position:absolute; z-index:200; top:100%; left:0; margin-top:4px;
                                    background:var(--bg); border:1px solid var(--border);
                                    border-radius:6px; padding:8px; min-width:260px;
                                    max-height:340px; box-shadow:0 4px 12px rgba(0,0,0,0.15);">
                            <input type="text" id="stats-exclude-tag-search" placeholder="Поиск по тегам..."
                                   style="width:100%; box-sizing:border-box; padding:6px 10px;
                                          margin-bottom:8px; border-radius:6px;
                                          border:1px solid var(--border);
                                          background:var(--bg3); color:var(--text);">
                            <div id="stats-exclude-tag-list"
                                 style="max-height:220px; overflow-y:auto; display:flex;
                                        flex-direction:column; gap:2px; margin-bottom:8px;"></div>
                            <div style="display:flex; justify-content:space-between; gap:8px;">
                                <button id="stats-exclude-tag-clear" class="btn" style="flex:1;" type="button">Сбросить</button>
                                <button id="stats-exclude-tag-close" class="btn" style="flex:1;" type="button">Готово</button>
                            </div>
                        </div>
                    </details>

                    <!-- Правила -->
                    <details id="stats-rule-details" style="position:relative;">
                        <summary class="btn" style="list-style:none; cursor:pointer;
                                    user-select:none; display:inline-flex; gap:4px; align-items:center;"
                                 title="${esc(rulesHint)}">
                            📏 Правила (<span id="stats-rule-count">0</span>)
                        </summary>
                        <div style="position:absolute; z-index:200; top:100%; left:0; margin-top:4px;
                                    background:var(--bg); border:1px solid var(--border);
                                    border-radius:6px; padding:8px; min-width:340px;
                                    max-height:380px; box-shadow:0 4px 12px rgba(0,0,0,0.15);">
                            <div style="font-size:11px; color:var(--text-dim);
                                        margin-bottom:6px; padding:2px 4px;">
                                ${rulesHint}
                            </div>
                            <input type="text" id="stats-rule-search" placeholder="Поиск по правилам..."
                                   style="width:100%; box-sizing:border-box; padding:6px 10px;
                                          margin-bottom:8px; border-radius:6px;
                                          border:1px solid var(--border);
                                          background:var(--bg3); color:var(--text);">
                            <div id="stats-rule-list"
                                 style="max-height:240px; overflow-y:auto; display:flex;
                                        flex-direction:column; gap:2px; margin-bottom:8px;"></div>
                            <div style="display:flex; justify-content:space-between; gap:8px;">
                                <button id="stats-rule-clear" class="btn" style="flex:1;" type="button">Сбросить</button>
                                <button id="stats-rule-close" class="btn" style="flex:1;" type="button">Готово</button>
                            </div>
                        </div>
                    </details>

                    <!-- Статусы -->
                    <details id="stats-status-details" style="position:relative;">
                        <summary class="btn" style="list-style:none; cursor:pointer;
                                    user-select:none; display:inline-flex; gap:4px; align-items:center;">
                            ✅ Статусы (<span id="stats-status-count">0</span>)
                        </summary>
                        <div style="position:absolute; z-index:200; top:100%; left:0; margin-top:4px;
                                    background:var(--bg); border:1px solid var(--border);
                                    border-radius:6px; padding:8px; min-width:280px;
                                    max-height:380px; box-shadow:0 4px 12px rgba(0,0,0,0.15);">
                            <div id="stats-status-list"
                                 style="max-height:280px; overflow-y:auto; display:flex;
                                        flex-direction:column; gap:2px; margin-bottom:8px;"></div>
                            <div style="display:flex; justify-content:space-between; gap:8px;">
                                <button id="stats-status-clear" class="btn" style="flex:1;" type="button">Сбросить</button>
                                <button id="stats-status-close" class="btn" style="flex:1;" type="button">Готово</button>
                            </div>
                        </div>
                    </details>

                    <!-- Ветка -->
                    <label style="display:flex; align-items:center; gap:6px; font-size:12px;">
                        <span style="color:var(--text-dim);">Ветка:</span>
                        <input type="text" id="stats-branch" placeholder="master"
                               style="padding:6px 10px; width:140px;
                                      background:var(--bg); color:var(--text);
                                      border:1px solid var(--border); border-radius:6px;
                                      font-size:12px; font-family:var(--mono);">
                    </label>
                </div>

                <!-- Список проектов -->
                <div style="margin-bottom:12px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
                        <label style="font-size:12px; color:var(--text-dim);">
                            Проекты (<span id="stats-project-count">0</span>
                            из <span id="stats-project-total">${projects.length}</span>)
                        </label>
                        <div style="display:flex; gap:6px;">
                            <button id="stats-select-visible" class="btn"
                                    style="font-size:11px; padding:3px 8px;" type="button">
                                Выбрать видимые
                            </button>
                            <button id="stats-clear-selection" class="btn"
                                    style="font-size:11px; padding:3px 8px;" type="button">
                                Сбросить выбор
                            </button>
                        </div>
                    </div>
                    <div id="stats-project-list"
                         style="max-height:280px; overflow-y:auto;
                                border:1px solid var(--border); border-radius:6px;
                                background:var(--bg3); padding:6px;"></div>
                </div>

                <!-- Прогресс -->
                <div id="stats-progress-container" style="display:none; margin-top:16px;">
                    <div style="display:flex; justify-content:space-between; font-size:12px; margin-bottom:4px;">
                        <span id="stats-progress-text" style="color:var(--text-dim);">Инициализация…</span>
                        <span id="stats-progress-counter"
                              style="font-family:var(--mono); font-weight:bold;">0 / 0</span>
                    </div>
                    <div style="height:8px; background:var(--bg3); border-radius:4px; overflow:hidden;
                                border:1px solid var(--border);">
                        <div id="stats-progress-fill"
                             style="height:100%; width:0%; background:var(--accent); transition:width 0.2s;"></div>
                    </div>
                    <div id="stats-progress-elapsed"
                         style="font-size:11px; color:var(--text-dim); text-align:right; margin-top:4px;"></div>
                </div>

                <div id="stats-result" style="display:none; margin-top:16px;"></div>

                <div id="stats-actions"
                     style="display:flex; justify-content:flex-end; gap:8px; margin-top:16px;">
                    <button id="stats-cancel-btn" class="btn" type="button">Отмена</button>
                    <button id="stats-start-btn" class="btn btn-accent" type="button">📊 Сформировать отчёт</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const $ = (id) => overlay.querySelector('#' + id);

    // ===== 5. Состояние =====
    const selectedProjects    = new Set();
    const selectedTags        = new Set();
    const excludedTags        = new Set();
    const selectedRules       = new Set();
    const selectedStatuses    = new Set(DEFAULT_SELECTED_STATUSES);

    let tagQuery = '';
    let excludeTagQuery = '';
    let ruleQuery = '';

    let abortController = null;
    let elapsedTimer    = null;
    let startMs         = 0;

    // ===== 6. Фильтр проектов =====
    const searchInput   = $('stats-search');
    const projectListEl = $('stats-project-list');
    const hintEl        = $('stats-search-hint');

    function getVisibleProjects() {
        const tokens = parseProjectTokens(searchInput.value);
        return projects.filter(p => projectMatchesAnyToken(p, tokens));
    }

    function updateHint() {
        const tokens = parseProjectTokens(searchInput.value);
        if (tokens.length === 0) {
            hintEl.textContent = '';
        } else if (tokens.length === 1) {
            hintEl.textContent = `Фильтр: 1 токен — «${tokens[0]}»`;
        } else {
            hintEl.textContent =
                `Фильтр: ${tokens.length} токенов — ${tokens.map(t => `«${t}»`).join(', ')}`;
        }
    }

    function renderProjectList() {
        const visible = getVisibleProjects();
        $('stats-project-count').textContent = visible.length;

        if (visible.length === 0) {
            projectListEl.innerHTML =
                '<div style="text-align:center;color:var(--text-dim);' +
                'font-style:italic;padding:20px;font-size:13px;">' +
                'Нет проектов по фильтру</div>';
            updateHint();
            return;
        }

        projectListEl.innerHTML = visible.map(p => {
            const checked = selectedProjects.has(p.key);
            return `
                <label style="display:flex; align-items:center; gap:8px; padding:4px 6px;
                              border-radius:4px; cursor:pointer; font-size:12px;
                              background:${checked ? 'var(--bg)' : 'transparent'};">
                    <input type="checkbox" class="stats-project-cb"
                           data-key="${esc(p.key)}"
                           ${checked ? 'checked' : ''}>
                    <span style="font-family:var(--mono); color:var(--accent);">${esc(p.key)}</span>
                    <span style="color:var(--text-dim);">— ${esc(p.name || '')}</span>
                </label>
            `;
        }).join('');

        projectListEl.querySelectorAll('.stats-project-cb').forEach(cb => {
            cb.addEventListener('change', (e) => {
                const key = e.target.dataset.key;
                if (e.target.checked) selectedProjects.add(key);
                else                  selectedProjects.delete(key);
                renderProjectList();
            });
        });

        updateHint();
    }

    searchInput.addEventListener('input', renderProjectList);

    $('stats-select-visible').addEventListener('click', () => {
        getVisibleProjects().forEach(p => selectedProjects.add(p.key));
        renderProjectList();
    });

    $('stats-clear-selection').addEventListener('click', () => {
        selectedProjects.clear();
        renderProjectList();
    });

    renderProjectList();

    // ===== 7. Рендер списка тегов (общий для include/exclude) =====
    function renderTagList(container, query, selectedSet, updateCountFn) {
        const q = String(query || '').toLowerCase();
        const visible = sortedTags.filter(t => {
            const label = t === NO_TAGS_KEY ? NO_TAGS_LABEL : t;
            return !q || label.toLowerCase().includes(q);
        });

        if (!visible.length) {
            container.innerHTML =
                `<div style="color:var(--text-dim); font-size:12px; padding:4px;">Нет тегов</div>`;
            return;
        }

        container.innerHTML = visible.map(t => {
            const label = t === NO_TAGS_KEY ? NO_TAGS_LABEL : t;
            return `
                <label style="display:flex; align-items:center; gap:6px; font-size:12px;
                              cursor:pointer; padding:4px 6px; border-radius:4px;
                              background:${selectedSet.has(t) ? 'var(--bg3)' : 'transparent'};">
                    <input type="checkbox" class="stats-tag-cb"
                           data-scope="${container.id}"
                           value="${esc(t)}" ${selectedSet.has(t) ? 'checked' : ''}>
                    <span>${esc(label)}</span>
                </label>
            `;
        }).join('');

        container.querySelectorAll('.stats-tag-cb').forEach(cb => {
            cb.addEventListener('change', (e) => {
                if (e.target.checked) selectedSet.add(e.target.value);
                else                  selectedSet.delete(e.target.value);
                updateCountFn();
                renderTagList(container, query, selectedSet, updateCountFn);
            });
        });
    }

    // ===== 8. Рендер списка правил =====
    function renderRuleList() {
        const container = $('stats-rule-list');
        const q = ruleQuery.toLowerCase();

        if (!allRules.length) {
            container.innerHTML = `
                <div style="color:var(--text-dim); font-size:12px; padding:8px; text-align:center;
                            font-style:italic;">${esc(rulesHint)}</div>
            `;
            return;
        }

        const visible = allRules.filter(({ rule }) =>
            !q || rule.toLowerCase().includes(q));

        if (!visible.length) {
            container.innerHTML =
                `<div style="color:var(--text-dim); font-size:12px; padding:4px;">Нет правил по фильтру</div>`;
            return;
        }

        container.innerHTML = visible.map(({ rule, count }) => `
            <label style="display:flex; align-items:center; gap:6px; font-size:12px;
                          cursor:pointer; padding:4px 6px; border-radius:4px;
                          background:${selectedRules.has(rule) ? 'var(--bg3)' : 'transparent'};">
                <input type="checkbox" class="stats-rule-cb"
                       value="${esc(rule)}" ${selectedRules.has(rule) ? 'checked' : ''}>
                <span style="font-family:var(--mono);">${esc(rule)}</span>
                <span style="color:var(--text-dim); margin-left:auto;">${count}</span>
            </label>
        `).join('');

        container.querySelectorAll('.stats-rule-cb').forEach(cb => {
            cb.addEventListener('change', (e) => {
                if (e.target.checked) selectedRules.add(e.target.value);
                else                  selectedRules.delete(e.target.value);
                updateRuleCount();
                renderRuleList();
            });
        });
    }

    // ===== 9. Рендер списка статусов =====
    function renderStatusList() {
        const container = $('stats-status-list');

        container.innerHTML = STATUS_OPTIONS.map(o => `
            <label style="display:flex; align-items:center; gap:6px; font-size:12px;
                          cursor:pointer; padding:4px 6px; border-radius:4px;
                          background:${selectedStatuses.has(o.value) ? 'var(--bg3)' : 'transparent'};">
                <input type="checkbox" class="stats-status-cb"
                       value="${esc(o.value)}" ${selectedStatuses.has(o.value) ? 'checked' : ''}>
                <span style="font-family:var(--mono);">${esc(o.label)}</span>
            </label>
        `).join('');

        container.querySelectorAll('.stats-status-cb').forEach(cb => {
            cb.addEventListener('change', (e) => {
                if (e.target.checked) selectedStatuses.add(e.target.value);
                else                  selectedStatuses.delete(e.target.value);
                updateStatusCount();
                renderStatusList();
            });
        });
    }

    // ===== 10. Счётчики =====
    const tagCount        = $('stats-tag-count');
    const excludeTagCount = $('stats-exclude-tag-count');
    const ruleCount       = $('stats-rule-count');
    const statusCount     = $('stats-status-count');

    const updateTagCount        = () => { tagCount.textContent = selectedTags.size; };
    const updateExcludeTagCount = () => { excludeTagCount.textContent = excludedTags.size; };
    const updateRuleCount       = () => { ruleCount.textContent = selectedRules.size; };
    const updateStatusCount     = () => { statusCount.textContent = selectedStatuses.size; };

    // ===== 11. Первичный рендер =====
    renderTagList($('stats-tag-list'),         tagQuery,        selectedTags,    updateTagCount);
    updateTagCount();

    renderTagList($('stats-exclude-tag-list'), excludeTagQuery, excludedTags,    updateExcludeTagCount);
    updateExcludeTagCount();

    renderRuleList();
    updateRuleCount();

    renderStatusList();
    updateStatusCount();

    // ===== 12. Обработчики поиска и очистки =====
    $('stats-tag-search').addEventListener('input', (e) => {
        tagQuery = e.target.value;
        renderTagList($('stats-tag-list'), tagQuery, selectedTags, updateTagCount);
    });
    $('stats-exclude-tag-search').addEventListener('input', (e) => {
        excludeTagQuery = e.target.value;
        renderTagList($('stats-exclude-tag-list'), excludeTagQuery, excludedTags, updateExcludeTagCount);
    });
    $('stats-rule-search').addEventListener('input', (e) => {
        ruleQuery = e.target.value;
        renderRuleList();
    });

    $('stats-tag-clear').addEventListener('click', (e) => {
        e.preventDefault(); e.stopPropagation();
        selectedTags.clear();
        updateTagCount();
        renderTagList($('stats-tag-list'), tagQuery, selectedTags, updateTagCount);
    });
    $('stats-tag-close').addEventListener('click', (e) => {
        e.preventDefault(); e.stopPropagation();
        $('stats-tag-details').removeAttribute('open');
    });

    $('stats-exclude-tag-clear').addEventListener('click', (e) => {
        e.preventDefault(); e.stopPropagation();
        excludedTags.clear();
        updateExcludeTagCount();
        renderTagList($('stats-exclude-tag-list'), excludeTagQuery, excludedTags, updateExcludeTagCount);
    });
    $('stats-exclude-tag-close').addEventListener('click', (e) => {
        e.preventDefault(); e.stopPropagation();
        $('stats-exclude-tag-details').removeAttribute('open');
    });

    $('stats-rule-clear').addEventListener('click', (e) => {
        e.preventDefault(); e.stopPropagation();
        selectedRules.clear();
        updateRuleCount();
        renderRuleList();
    });
    $('stats-rule-close').addEventListener('click', (e) => {
        e.preventDefault(); e.stopPropagation();
        $('stats-rule-details').removeAttribute('open');
    });

    $('stats-status-clear').addEventListener('click', (e) => {
        e.preventDefault(); e.stopPropagation();
        selectedStatuses.clear();
        updateStatusCount();
        renderStatusList();
    });
    $('stats-status-close').addEventListener('click', (e) => {
        e.preventDefault(); e.stopPropagation();
        $('stats-status-details').removeAttribute('open');
    });

    // ===== 13. Закрытие =====
    const stopTimer = () => {
        if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
    };

    const close = () => {
        stopTimer();
        if (abortController) {
            try { abortController.abort(); } catch (_) {}
            abortController = null;
        }
        overlay.remove();
    };

    overlay.querySelector('.modal-close').addEventListener('click', close);
    $('stats-cancel-btn').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    // ===== 14. Submit → SSE → download =====
    $('stats-start-btn').addEventListener('click', async () => {
        if (selectedProjects.size === 0) {
            toast('Выберите хотя бы один проект', true);
            return;
        }
        if (selectedStatuses.size === 0) {
            toast('Выберите хотя бы один статус', true);
            return;
        }

        const projectsArr = Array.from(selectedProjects);

        const body = {
            projects:     projectsArr,
            includedTags: Array.from(selectedTags),
            excludedTags: Array.from(excludedTags),
            rules:        Array.from(selectedRules),
            statuses:     Array.from(selectedStatuses).join(','),
            branch:       ($('stats-branch').value || '').trim()
        };

        $('stats-start-btn').disabled = true;
        $('stats-start-btn').textContent = 'Формирование…';
        $('stats-actions').style.display = 'none';
        $('stats-progress-container').style.display = 'block';
        $('stats-result').style.display = 'none';

        const progressText    = $('stats-progress-text');
        const progressCounter = $('stats-progress-counter');
        const progressFill    = $('stats-progress-fill');
        const progressElapsed = $('stats-progress-elapsed');

        startMs = performance.now();
        progressText.textContent = 'Инициализация…';
        progressCounter.textContent = '0 / 0';
        progressFill.style.width = '0%';

        elapsedTimer = setInterval(() => {
            const s = ((performance.now() - startMs) / 1000).toFixed(1);
            progressElapsed.textContent = `Прошло: ${s} с`;
        }, 200);

        abortController = new AbortController();

        try {
            const resp = await fetch('/api/stats/projects-stream', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
                signal: abortController.signal
            });
            if (!resp.ok) {
                throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);
            }

            const reader  = resp.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '';

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });

                let nl;
                while ((nl = buffer.indexOf('\n\n')) !== -1) {
                    const chunk = buffer.slice(0, nl);
                    buffer = buffer.slice(nl + 2);
                    if (!chunk.trim()) continue;

                    let event = 'message';
                    let data  = '';
                    for (const line of chunk.split('\n')) {
                        if (line.startsWith('event: ')) event = line.substring(7).trim();
                        else if (line.startsWith('data: ')) data += line.substring(6).trim();
                    }
                    if (!data) continue;

                    let d;
                    try { d = JSON.parse(data); } catch (_) { continue; }

                    if (event === 'progress') {
                        if (d.message) progressText.textContent = d.message;

                        if (d.total > 0) {
                            const cur = Math.min(d.current || 0, d.total);
                            progressCounter.textContent = `${cur} / ${d.total}`;
                            progressFill.style.width =
                                Math.round((cur / d.total) * 100) + '%';
                        } else if (d.projectTotal > 0) {
                            progressCounter.textContent =
                                `${d.projectIndex || 0} / ${d.projectTotal}`;
                            progressFill.style.width =
                                Math.round(((d.projectIndex || 0) / d.projectTotal) * 100) + '%';
                        }

                        if (typeof d.elapsedMs === 'number' && d.elapsedMs > 0) {
                            progressElapsed.textContent =
                                `Прошло: ${(d.elapsedMs / 1000).toFixed(1)} с`;
                        }
                    } else if (event === 'complete') {
                        stopTimer();
                        progressText.textContent = 'Готово — скачивание…';
                        progressFill.style.width = '100%';

                        const elapsedMs = performance.now() - startMs;
                        progressElapsed.textContent =
                            `Готово за ${(elapsedMs / 1000).toFixed(1)} с`;

                        await triggerDownload(d.file, d.downloadName, 'stats_projects');

                        $('stats-result').innerHTML = `
                            <div style="padding:14px 16px; background:rgba(63,185,80,0.1);
                                        border:1px solid var(--ok); border-radius:6px;
                                        font-size:13px;">
                                ✓ Отчёт сформирован за ${(elapsedMs / 1000).toFixed(1)} с.
                                Всего issues: <b>${d.total || 0}</b>.
                                Проектов успешно: ${d.projectsOk || 0} из ${d.projectsRequested || 0}.
                            </div>
                        `;
                        $('stats-result').style.display = 'block';
                        toast('Отчёт по проектам готов');

                        $('stats-start-btn').disabled = false;
                        $('stats-start-btn').textContent = '📊 Сформировать отчёт';
                        $('stats-actions').style.display = 'flex';
                    } else if (event === 'error') {
                        throw new Error(d.message || 'Ошибка формирования отчёта');
                    }
                }
            }

        } catch (e) {
            stopTimer();
            if (e.name === 'AbortError') {
                progressText.textContent = 'Отменено';
            } else {
                progressText.textContent = 'Ошибка: ' + e.message;
                $('stats-result').innerHTML = `
                    <div style="padding:12px 14px; background:rgba(248,81,73,0.1);
                                border:1px solid var(--err); border-radius:6px;
                                font-size:13px; color:var(--err);">
                        ✗ ${esc(e.message)}
                    </div>
                `;
                $('stats-result').style.display = 'block';
                toast('Ошибка: ' + e.message, true);
            }
            $('stats-start-btn').disabled = false;
            $('stats-start-btn').textContent = '📊 Сформировать отчёт';
            $('stats-actions').style.display = 'flex';
        } finally {
            abortController = null;
        }
    });
}