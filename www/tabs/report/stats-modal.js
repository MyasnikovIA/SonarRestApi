import { esc, toast } from '../../common.js';

const ALL_STATUSES = [
    'OPEN', 'CONFIRMED', 'FALSE_POSITIVE', 'FIXED',
    'ACCEPTED', 'REOPENED', 'CLOSED', 'WONTFIX'
];

/**
 * Модал статистики по проектам.
 * Phase 1: выбор проектов + статусов + исключаемых тегов
 * Phase 2: SSE-прогресс + автозагрузка готового HTML
 */
export async function showStatsModal(ctx) {
    // ---- Загрузка данных ----
    let allProjects = [];
    try {
        const d = await ctx.api('/api/projects');
        allProjects = (d.projects || [])
            .filter(p => p && p.key)
            .sort((a, b) => (a.key || '').localeCompare(b.key || ''));
    } catch (e) {
        toast('Не удалось загрузить проекты: ' + e.message, true);
        return;
    }
    if (!allProjects.length) {
        toast('Список проектов пуст', true);
        return;
    }

    let allTags = [];
    try {
        const d = await ctx.api('/api/tags');
        allTags = (d.tags || []).sort((a, b) => a.localeCompare(b));
    } catch (e) { /* не критично */ }

    // ---- Состояние ----
    const selectedProjects = new Set();
    const selectedStatuses = new Set();
    const excludedTags     = new Set();
    let projectQuery = '';
    let tagQuery     = '';

    // ---- Разметка ----
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.style.zIndex = '2000';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 820px;">
            <div class="modal-header">
                <h3 style="margin:0;">📊 Статистика по проектам</h3>
                <button class="modal-close" type="button">&times;</button>
            </div>
            <div class="modal-body">

                <!-- ===== Форма выбора ===== -->
                <div id="stats-form-view">
                    <div style="margin-bottom:18px;">
                        <label style="display:block; font-size:12px; margin-bottom:6px; color:var(--text-dim);">
                            Статусы <span style="font-weight:normal;">(ничего не выбрано = все)</span>
                        </label>
                        <div id="stats-statuses" style="display:flex; flex-wrap:wrap; gap:6px;"></div>
                    </div>

                    <div style="margin-bottom:8px; display:flex; justify-content:space-between; align-items:center;">
                        <label style="font-size:12px; color: var(--text-dim);">
                            Проекты: <b id="stats-count" style="color:var(--accent);">0</b> / ${allProjects.length}
                        </label>
                        <div style="display:flex; gap:6px;">
                            <button id="stats-select-all" class="btn" style="padding:4px 10px; font-size:11px;" type="button">Выделить все</button>
                            <button id="stats-clear" class="btn" style="padding:4px 10px; font-size:11px;" type="button">Сбросить</button>
                        </div>
                    </div>

                    <input type="text" id="stats-search" placeholder="Поиск по ключу или названию проекта..."
                           style="width:100%; box-sizing:border-box; padding:8px 12px; margin-bottom:8px;
                                  border-radius:6px; border:1px solid var(--border);
                                  background:var(--bg); color:var(--text); font-size:13px;">

                    <div id="stats-projects"
                         style="max-height:240px; overflow-y:auto; display:flex; flex-direction:column;
                                gap:2px; padding:8px; background:var(--bg3);
                                border:1px solid var(--border); border-radius:6px; margin-bottom:18px;"></div>

                    <div style="margin-bottom:8px; display:flex; justify-content:space-between; align-items:center;">
                        <label style="font-size:12px; color: var(--text-dim);">
                            Исключить теги: <b id="stats-excluded-count" style="color:var(--accent);">0</b>
                        </label>
                        <button id="stats-excluded-clear" class="btn" style="padding:4px 10px; font-size:11px;" type="button">Сбросить</button>
                    </div>

                    <input type="text" id="stats-tag-search" placeholder="Поиск по тегам..."
                           style="width:100%; box-sizing:border-box; padding:8px 12px; margin-bottom:8px;
                                  border-radius:6px; border:1px solid var(--border);
                                  background:var(--bg); color:var(--text); font-size:13px;">

                    <div id="stats-tags"
                         style="max-height:180px; overflow-y:auto; display:flex; flex-wrap:wrap;
                                gap:6px; padding:8px; background:var(--bg3);
                                border:1px solid var(--border); border-radius:6px;"></div>

                    <div id="stats-error"
                         style="display:none; margin-top:12px; padding:8px 12px;
                                background:rgba(248,81,73,0.1); border:1px solid var(--err);
                                border-radius:6px; font-size:12px; color:var(--err);"></div>

                    <div style="display:flex; justify-content:flex-end; gap:8px; margin-top:20px;">
                        <button id="stats-cancel" class="btn" type="button">Отмена</button>
                        <button id="stats-generate" class="btn btn-accent" type="button">📊 Сформировать отчёт</button>
                    </div>
                </div>

                <!-- ===== Прогресс ===== -->
                <div id="stats-progress-view" style="display:none;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; font-size:12px;">
                        <span id="stats-stage" style="color:var(--text-dim);">Инициализация…</span>
                        <span id="stats-counter" style="font-family:var(--mono); font-weight:bold;">0 / 0</span>
                    </div>
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; font-size:11px;">
                        <span id="stats-message" style="color:var(--text-dim);">—</span>
                        <span style="color:var(--text-dim);">Прошло: <b id="stats-elapsed" style="font-family:var(--mono); color:var(--accent);">0.0 с</b></span>
                    </div>
                    <div style="height:10px; background:var(--bg3); border-radius:5px; overflow:hidden; border:1px solid var(--border);">
                        <div id="stats-fill" style="height:100%; width:0%; background:var(--accent); transition: width 0.2s;"></div>
                    </div>
                    <div style="margin-top:20px; display:flex; justify-content:flex-end; gap:8px;">
                        <button id="stats-abort" class="btn btn-danger" type="button">⏹ Остановить</button>
                    </div>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const $ = (id) => overlay.querySelector('#' + id);
    let abortController = null;
    let elapsedTimer = null;
    let startMs = 0;

    const close = () => {
        if (abortController) { try { abortController.abort(); } catch (_) {} }
        if (elapsedTimer) clearInterval(elapsedTimer);
        overlay.remove();
    };

    overlay.querySelector('.modal-close').addEventListener('click', close);
    $('stats-cancel').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    // ---- Статусы ----
    function renderStatuses() {
        const container = $('stats-statuses');
        container.innerHTML = ALL_STATUSES.map(s => `
            <label style="display:flex; align-items:center; gap:4px; font-size:12px;
                          cursor:pointer; padding:3px 10px; background:var(--bg);
                          border:1px solid var(--border); border-radius:4px; user-select:none;">
                <input type="checkbox" class="stats-status-cb" value="${s}" ${selectedStatuses.has(s) ? 'checked' : ''}>
                ${s}
            </label>
        `).join('');
        container.querySelectorAll('.stats-status-cb').forEach(cb => {
            cb.addEventListener('change', (e) => {
                if (e.target.checked) selectedStatuses.add(e.target.value);
                else                  selectedStatuses.delete(e.target.value);
            });
        });
    }
    renderStatuses();

    // ---- Проекты ----
    function updateProjectCount() { $('stats-count').textContent = selectedProjects.size; }

    function renderProjects() {
        const q = projectQuery.toLowerCase().trim();
        const visible = allProjects.filter(p =>
            !q || (p.key || '').toLowerCase().includes(q) || (p.name || '').toLowerCase().includes(q));

        const container = $('stats-projects');
        if (!visible.length) {
            container.innerHTML = `<div style="color:var(--text-dim); font-size:12px; padding:12px; text-align:center;">Нет проектов по фильтру</div>`;
            updateProjectCount();
            return;
        }

        container.innerHTML = visible.map(p => {
            const isSel = selectedProjects.has(p.key);
            const sub = (p.name && p.name !== p.key)
                ? `<span style="color:var(--text-dim); margin-left:auto; font-size:11px;">${esc(p.name)}</span>` : '';
            return `
                <label style="display:flex; align-items:center; gap:8px; font-size:12px;
                              cursor:pointer; padding:5px 8px; border-radius:4px;
                              background:${isSel ? 'var(--bg)' : 'transparent'};">
                    <input type="checkbox" class="stats-proj-cb" value="${esc(p.key)}" ${isSel ? 'checked' : ''}>
                    <span style="font-family:var(--mono);">${esc(p.key)}</span>
                    ${sub}
                </label>
            `;
        }).join('');

        container.querySelectorAll('.stats-proj-cb').forEach(cb => {
            cb.addEventListener('change', (e) => {
                if (e.target.checked) selectedProjects.add(e.target.value);
                else                  selectedProjects.delete(e.target.value);
                updateProjectCount();
                const label = e.target.closest('label');
                if (label) label.style.background = e.target.checked ? 'var(--bg)' : 'transparent';
            });
        });

        updateProjectCount();
    }
    renderProjects();

    $('stats-search').addEventListener('input', (e) => {
        projectQuery = e.target.value;
        renderProjects();
    });

    $('stats-select-all').addEventListener('click', () => {
        const q = projectQuery.toLowerCase().trim();
        allProjects.forEach(p => {
            if (!q || (p.key || '').toLowerCase().includes(q) || (p.name || '').toLowerCase().includes(q)) {
                selectedProjects.add(p.key);
            }
        });
        renderProjects();
    });

    $('stats-clear').addEventListener('click', () => {
        selectedProjects.clear();
        renderProjects();
    });

    // ---- Теги ----
    function updateExcludedCount() { $('stats-excluded-count').textContent = excludedTags.size; }

    function renderTags() {
        const q = tagQuery.toLowerCase().trim();
        const visible = allTags.filter(t => !q || t.toLowerCase().includes(q));

        const container = $('stats-tags');
        if (!visible.length) {
            container.innerHTML = `<div style="color:var(--text-dim); font-size:12px; padding:12px; width:100%; text-align:center;">
                ${allTags.length === 0 ? 'Нет доступных тегов' : 'Нет тегов по фильтру'}</div>`;
            updateExcludedCount();
            return;
        }

        container.innerHTML = visible.map(t => `
            <label style="display:flex; align-items:center; gap:4px; font-size:12px;
                          cursor:pointer; padding:3px 10px; background:var(--bg);
                          border:1px solid var(--border); border-radius:4px; user-select:none;">
                <input type="checkbox" class="stats-tag-cb" value="${esc(t)}" ${excludedTags.has(t) ? 'checked' : ''}>
                ${esc(t)}
            </label>
        `).join('');

        container.querySelectorAll('.stats-tag-cb').forEach(cb => {
            cb.addEventListener('change', (e) => {
                if (e.target.checked) excludedTags.add(e.target.value);
                else                  excludedTags.delete(e.target.value);
                updateExcludedCount();
            });
        });

        updateExcludedCount();
    }
    renderTags();

    $('stats-tag-search').addEventListener('input', (e) => {
        tagQuery = e.target.value;
        renderTags();
    });

    $('stats-excluded-clear').addEventListener('click', () => {
        excludedTags.clear();
        renderTags();
    });

    // ---- Генерация ----
    $('stats-generate').addEventListener('click', async () => {
        $('stats-error').style.display = 'none';

        if (selectedProjects.size === 0) {
            $('stats-error').style.display = 'block';
            $('stats-error').textContent = 'Выберите хотя бы один проект';
            return;
        }

        $('stats-form-view').style.display = 'none';
        $('stats-progress-view').style.display = 'block';

        startMs = performance.now();
        elapsedTimer = setInterval(() => {
            $('stats-elapsed').textContent = ((performance.now() - startMs) / 1000).toFixed(1) + ' с';
        }, 200);

        const body = {
            projects: Array.from(selectedProjects)
        };
        if (excludedTags.size > 0)      body.excludedTags = Array.from(excludedTags);
        if (selectedStatuses.size > 0)  body.statuses = Array.from(selectedStatuses).join(',');

        abortController = new AbortController();

        try {
            const response = await fetch('/api/stats/projects-stream', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
                signal: abortController.signal
            });

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${await response.text()}`);
            }
            if (!response.body) throw new Error('Пустой ответ');

            const reader = response.body.getReader();
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

                    let eventName = 'message';
                    let dataStr = '';
                    for (const line of chunk.split('\n')) {
                        if (line.startsWith('event: ')) eventName = line.substring(7).trim();
                        else if (line.startsWith('data: ')) dataStr += line.substring(6).trim();
                    }

                    let d;
                    try { d = JSON.parse(dataStr); } catch (_) { continue; }

                    if (eventName === 'progress') handleProgress(d);
                    else if (eventName === 'complete') { handleComplete(d); return; }
                    else if (eventName === 'error') { throw new Error(d.message || 'Ошибка генерации'); }
                }
            }

            // Поток закрылся без complete
            throw new Error('Сервер закрыл поток без события complete');

        } catch (err) {
            if (err.name === 'AbortError') {
                toast('Генерация прервана пользователем', true);
                close();
                return;
            }
            console.error('[stats] error:', err);
            $('stats-stage').textContent = 'Ошибка';
            $('stats-stage').style.color = 'var(--err)';
            $('stats-message').textContent = err.message;
            toast('Ошибка: ' + err.message, true);
        } finally {
            if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
            abortController = null;
        }
    });

    $('stats-abort').addEventListener('click', () => {
        if (abortController) abortController.abort();
    });

    // ---- Прогресс ----
    function handleProgress(d) {
        const stage = d.stage || '';
        const current = Number(d.current) || 0;
        const total   = Number(d.total) || 0;
        const pIdx    = Number(d.projectIndex) || 0;
        const pTotal  = Number(d.projectTotal) || 1;

        let pct = 0;

        if (stage === 'init') {
            pct = 1;
            $('stats-counter').textContent = `0 / ${pTotal}`;
        } else if (stage === 'fetch') {
            const projFraction = (total > 0) ? (current / total) : 0;
            pct = ((pIdx - 1) + projFraction) / pTotal * 85;
            $('stats-counter').textContent = current + ' / ' + (total > 0 ? total : '?');
        } else if (stage === 'project-done') {
            pct = pIdx / pTotal * 85;
            $('stats-counter').textContent = `${pIdx} / ${pTotal}`;
        } else if (stage === 'error') {
            pct = pIdx / pTotal * 85;
        } else if (stage === 'aggregating') {
            pct = 85;
            $('stats-counter').textContent = `${pTotal} / ${pTotal}`;
        } else if (stage === 'rendering') {
            pct = 90;
        } else if (stage === 'saving') {
            pct = 95;
        }

        $('stats-fill').style.width = Math.min(100, Math.max(0, pct)) + '%';
        if (d.message) $('stats-message').textContent = d.message;

        const stageLabels = {
            init: 'Инициализация',
            fetch: `Получение данных · ${d.projectKey || ''} (${pIdx}/${pTotal})`,
            'project-done': `Проект завершён · ${d.projectKey || ''} (${pIdx}/${pTotal})`,
            error: `Ошибка в проекте · ${d.projectKey || ''}`,
            aggregating: 'Агрегация',
            rendering: 'Формирование HTML',
            saving: 'Сохранение файла'
        };
        $('stats-stage').textContent = stageLabels[stage] || stage;
        $('stats-stage').style.color = 'var(--text-dim)';

        if (typeof d.elapsedMs === 'number' && d.elapsedMs > 0) {
            $('stats-elapsed').textContent = (d.elapsedMs / 1000).toFixed(1) + ' с';
        }
    }

    // ---- Завершение ----
    async function handleComplete(d) {
        if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }

        $('stats-fill').style.width = '100%';
        $('stats-stage').textContent = 'Готово';
        $('stats-stage').style.color = 'var(--ok)';
        $('stats-message').textContent = 'Скачивание файла…';

        try {
            const qp = new URLSearchParams();
            qp.set('file', d.file);
            qp.set('name', d.downloadName || 'stats_projects.html');

            const r = await fetch('/api/export-download?' + qp.toString());
            if (!r.ok) throw new Error('HTTP ' + r.status);

            const blob = await r.blob();
            const blobUrl = URL.createObjectURL(blob);

            // Имя
            let filename = d.downloadName || 'stats_projects.html';
            const cd = r.headers.get('Content-Disposition');
            if (cd) {
                const mStar = cd.match(/filename\*=UTF-8''([^;]+)/i);
                if (mStar) { try { filename = decodeURIComponent(mStar[1]); } catch (_) {} }
            }

            const a = document.createElement('a');
            a.href = blobUrl;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(() => URL.revokeObjectURL(blobUrl), 5000);

            toast(`Отчёт готов: ${d.projectsOk || 0} из ${d.projectsRequested || 0} проектов`);
            setTimeout(close, 1200);

        } catch (e) {
            console.error('[stats] download error:', e);
            $('stats-message').textContent = 'Ошибка скачивания: ' + e.message;
            $('stats-stage').style.color = 'var(--err)';
            toast('Ошибка скачивания: ' + e.message, true);
        }
    }
}