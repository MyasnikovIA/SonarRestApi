import {
    esc, toast, highlightCode, fetchAndShowPrompt,
    detectLanguage, cleanSnippet, highlightForPrompt, statusBadge,
    showTagEditor
} from '../common.js';
import {
    bindOllamaResolveButton, bindOllamaSettingsButton,
    OllamaClient, openOllamaSettingsModal
} from '../ollama.js';

const MAX_PROMPT_LENGTH = 16000;

// ==================== HELPERS ====================

function getMappedStatus(sonarStatus) {
    if (!sonarStatus) return 'accept';
    const s = String(sonarStatus).toUpperCase();
    if (s === 'ACCEPTED') return 'accept';
    if (s === 'CONFIRMED') return 'confirm';
    if (s === 'FALSE_POSITIVE') return 'falsepositive';
    if (s === 'FIXED' || s === 'RESOLVED') return 'resolve';
    if (s === 'WONTFIX') return 'wontfix';
    return 'accept';
}

function getReopenTransition(status) {
    if (status === 'CONFIRMED') return 'unconfirm';
    return 'reopen';
}

/**
 * Парсер ответа Ollama. Теперь возвращает { status, comment, tags }.
 * Формат ответа:
 *   Статус: <accept|falsepositive|confirm|resolve|wontfix>
 *   Критичность: <вердикт> — <обоснование>
 *   Теги: tag1, tag2, tag3
 *   <1-3 предложения с объяснением>
 */
function parseOllamaResponse(raw) {
    const text = String(raw || '');
    let status = '';
    let comment = '';
    let tags = [];

    const statusMatch = text.match(/Статус:\s*(accept|falsepositive|confirm|resolve|wontfix)/i);
    if (statusMatch) status = statusMatch[1].toLowerCase();

    const tagsMatch = text.match(/Теги:\s*([^\n]+)/i);
    if (tagsMatch) {
        tags = tagsMatch[1]
            .split(/[,;]/)
            .map(t => t.replace(/[*_`]/g, '').trim().toLowerCase())
            .filter(t => t.length > 0);
    }

    // NEW: гарантируем наличие одного из фикс-тегов.
    // Если модель вернула оба или ни одного — берём require-fix (безопаснее).
    const hasRequire    = tags.includes('require-fix');
    const hasNotRequire = tags.includes('not-require-fix');

    // убираем оба из массива, чтобы нормализовать
    tags = tags.filter(t => t !== 'require-fix' && t !== 'not-require-fix');

    let fixDecision;
    if (hasRequire && !hasNotRequire)      fixDecision = 'require-fix';
    else if (hasNotRequire && !hasRequire) fixDecision = 'not-require-fix';
    else                                    fixDecision = 'require-fix'; // fallback

    // ставим фикс-тег первым
    tags.unshift(fixDecision);

    // Ограничиваем общее число тегов до 3
    tags = tags.slice(0, 3);

    // comment = всё, что после "Критичность:" (включая её)
    const lines = text.split(/\r?\n/);
    const commentLines = [];
    let inComment = false;
    for (const line of lines) {
        const trimmed = line.trim();
        if (/^Статус:/i.test(trimmed)) continue;
        if (/^Теги:/i.test(trimmed)) continue;
        if (/^Критичность:/i.test(trimmed)) {
            inComment = true;
            commentLines.push(line);
            continue;
        }
        if (inComment) commentLines.push(line);
    }
    comment = commentLines.join('\n').trim();

    if (!comment) {
        comment = lines
            .filter(l => !/^Статус:/i.test(l.trim()) && !/^Теги:/i.test(l.trim()))
            .join('\n')
            .trim();
    }

    return { status, comment, tags, fixDecision };
}

// ==================== PROMPT BUILDERS (с поддержкой тегов) ====================

function buildStatusCommentPrompt(issue, ruleName, snippet, status, statusLabel, availableTags) {
    const code = highlightForPrompt(snippet, issue.line).trim();
    const lang = detectLanguage(issue.component);
    const severity = issue.impactSeverity || issue.severity || '';
    const tagsList = (availableTags && availableTags.length)
        ? availableTags.join(', ')
        : 'любые релевантные';

    return [
        'Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).',
        'Проект мультиязычный. НЕ предполагай язык — он указан ниже.',
        '',
        'ЗАДАЧА:',
        '1. Подтверди или скорректируй выбранный пользователем статус: "' + status + '" — ' + statusLabel,
        '2. Определи реальную критичность.',
        '3. Дай РЕШЕНИЕ: нужно ли исправлять этот баг?',
        '4. Выбери 2–3 тега из списка доступных (обязательно с фикс-тегом).',
        '5. Объясни в 1–3 предложениях.',
        '',
        'ОПРЕДЕЛЕНИЯ СТАТУСОВ:',
        '- falsepositive: SonarQube ошибся, проблемы нет.',
        '- accept: реальная проблема, исправим позже (техдолг).',
        '- confirm: реальная и срочная проблема (security, потеря данных).',
        '- resolve: уже исправлено в коде.',
        '- wontfix: реальная проблема, но не будем исправлять (генерированный код).',
        '',
        'РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ (обязательно!):',
        'Верни РОВНО ОДИН из двух тегов в поле "Теги:"',
        '- "require-fix"     — баг РЕАЛЬНЫЙ и его НУЖНО исправить (в т.ч. если status=confirm или resolve, но в коде ещё есть проблема).',
        '- "not-require-fix" — баг НЕ требует исправления:',
        '   * SonarQube ошибся (false positive),',
        '   * код сгенерирован (OpenAPI, ORM, protobuf) и не должен правиться вручную,',
        '   * стилистическая мелочь без функциональных последствий,',
        '   * решено не исправлять (wontfix) по архитектурным причинам.',
        '',
        'СВЯЗЬ СТАТУСА И ФИКС-ТЕГА (подсказка):',
        '- status=confirm  → обычно require-fix',
        '- status=resolve  → обычно not-require-fix (уже исправлено)',
        '- status=wontfix  → usually not-require-fix',
        '- status=accept   → require-fix, если реальная проблема; not-require-fix, если сгенерировано/техдолг',
        '- status=falsepositive → not-require-fix',
        '',
        'ДОСТУПНЫЕ ТЕГИ: ' + tagsList,
        'В поле "Теги:" верни СНАЧАЛА require-fix или not-require-fix,',
        'затем 1–2 любых подходящих тега из списка выше.',
        '',
        'Контекст:',
        '- Правило: ' + (ruleName || issue.rule || ''),
        '- Сообщение: ' + (issue.message || ''),
        '- Файл: ' + (issue.component || '') + (issue.line ? ':' + issue.line : ''),
        '- Severity: ' + severity,
        '- Язык: ' + lang.name,
        '',
        'Код:',
        '```' + lang.codeFence,
        code,
        '```',
        '',
        'ФОРМАТ ОТВЕТА (СТРОГО, без отклонений):',
        '1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"',
        '2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"',
        '3. Строка 3: "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"',
        '   - Первый тег ОБЯЗАТЕЛЬНО "require-fix" ИЛИ "not-require-fix"',
        '   - Затем 1–2 тега из списка доступных',
        '   - Итого 2–3 тега',
        '4. Строки 4+: 1–3 предложения, объясняющие решение (в т.ч. почему фиксить или нет).',
        '',
        'Пиши по-русски, без markdown (никаких **, __, #, списков).',
        'Верни ТОЛЬКО этот текст.'
    ].join('\n');
}

async function buildBulkStatusCommentPrompt(issues, status, statusLabel, ctx, availableTags) {
    const tagsList = (availableTags && availableTags.length)
        ? availableTags.join(', ')
        : 'любые релевантные';

    const issuesList = issues.map((it, idx) =>
        `${idx + 1}. ${it.key} | ${it.rule} | ${it.component}:${it.line} | ${it.message || ''}`
    ).join('\n');

    return [
        'Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).',
        'Тебе нужно обработать ГРУППУ замечаний (одинаковый статус для всех).',
        '',
        'ЗАДАЧА:',
        '1. Подтверди статус "' + status + '" — ' + statusLabel + ' для всей группы.',
        '2. Определи общую критичность.',
        '3. Дай ОДНО общее РЕШЕНИЕ: нужно ли исправлять эти баги?',
        '4. Предложи 2–3 общих тега (обязательно с фикс-тегом).',
        '5. Объясни в 1–3 предложениях.',
        '',
        'ОПРЕДЕЛЕНИЯ СТАТУСОВ:',
        '- falsepositive: SonarQube ошибся.',
        '- accept: реальная проблема, исправим позже.',
        '- confirm: реальная и срочная.',
        '- resolve: уже исправлено.',
        '- wontfix: не будем исправлять.',
        '',
        'РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ (обязательно!):',
        'Верни РОВНО ОДИН из двух тегов в поле "Теги:"',
        '- "require-fix"     — группа содержит РЕАЛЬНЫЕ баги, которые нужно исправить.',
        '- "not-require-fix" — баги не требуют исправления (false positive, сгенерированный код, стилистика, wontfix).',
        '',
        'СВЯЗЬ СТАТУСА И ФИКС-ТЕГА (подсказка):',
        '- status=confirm  → require-fix',
        '- status=resolve  → not-require-fix',
        '- status=wontfix  → not-require-fix',
        '- status=falsepositive → not-require-fix',
        '- status=accept   → require-fix или not-require-fix (по сути)',
        '',
        'ДОСТУПНЫЕ ТЕГИ: ' + tagsList,
        '',
        'Замечания (' + issues.length + '):',
        issuesList,
        '',
        'ФОРМАТ ОТВЕТА (СТРОГО):',
        '1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"',
        '2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"',
        '3. Строка 3: "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"',
        '   - Первый тег ОБЯЗАТЕЛЬНО "require-fix" ИЛИ "not-require-fix"',
        '   - Затем 1–2 тега из списка',
        '4. Строки 4+: 1–3 предложения.',
        '',
        'Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.'
    ].join('\n');
}

function buildAutoResolvePromptAsync(issue, ctx, availableTags) {
    const snippet = issue.snippet || [];
    const code = snippet.join('\n');
    const lang = detectLanguage(issue.component);
    const severity = issue.impactSeverity || issue.severity || '';
    const tagsList = (availableTags && availableTags.length)
        ? availableTags.join(', ')
        : 'любые релевантные';

    return [
        'Ты — опытный разработчик и эксперт по SonarQube.',
        '',
        'ЗАДАЧА: определить статус, критичность, решение о фиксе, теги и обоснование.',
        '',
        'ОПРЕДЕЛЕНИЯ СТАТУСОВ:',
        '- falsepositive: SonarQube ошибся (проблемы нет).',
        '- accept: реальная проблема, откладываем (техдолг).',
        '- confirm: реальная и срочная (security/потеря данных/падение).',
        '- resolve: уже исправлено.',
        '- wontfix: реальная проблема, но не будем исправлять.',
        '',
        'РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ (обязательно!):',
        'Верни РОВНО ОДИН из двух тегов в поле "Теги:"',
        '- "require-fix"     — баг РЕАЛЬНЫЙ и его НУЖНО исправить.',
        '- "not-require-fix" — баг НЕ требует исправления:',
        '   * false positive,',
        '   * сгенерированный код (OpenAPI/ORM/protobuf),',
        '   * стилистическая мелочь без последствий,',
        '   * решено не исправлять (wontfix).',
        '',
        'СВЯЗЬ СТАТУСА И ФИКС-ТЕГА (подсказка):',
        '- confirm  → require-fix',
        '- resolve  → not-require-fix',
        '- wontfix  → not-require-fix',
        '- falsepositive → not-require-fix',
        '- accept   → require-fix или not-require-fix (по сути)',
        '',
        'ДОСТУПНЫЕ ТЕГИ: ' + tagsList,
        'В поле "Теги:" верни СНАЧАЛА require-fix или not-require-fix,',
        'затем 1–2 подходящих тега из списка.',
        '',
        'Контекст:',
        '- Правило: ' + (issue.ruleName || issue.rule || ''),
        '- Сообщение: ' + (issue.message || ''),
        '- Файл: ' + (issue.component || '') + (issue.line ? ':' + issue.line : ''),
        '- Severity: ' + severity,
        '- Язык: ' + lang.name,
        '',
        'Код:',
        '```' + lang.codeFence,
        code,
        '```',
        '',
        'ФОРМАТ ОТВЕТА (СТРОГО):',
        '1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"',
        '2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"',
        '3. Строка 3: "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"',
        '   - Первый тег ОБЯЗАТЕЛЬНО "require-fix" ИЛИ "not-require-fix"',
        '   - Затем 1–2 из списка',
        '4. Строки 4+: 1–3 предложения (в т.ч. почему фиксить или нет).',
        '',
        'Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.'
    ].join('\n');
}

// ==================== UTILS ====================

async function fetchAvailableTags(ctx) {
    try {
        const d = await ctx.api('/api/tags');
        return d.tags || [];
    } catch (e) {
        console.warn('Не удалось загрузить доступные теги', e);
        return [];
    }
}

// ==================== EXPORTED TAB ====================

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
            let selectedKeys = new Set();

            el.innerHTML = `
                <div class="pf-header-sticky">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                        <div style="display:flex; align-items:center; gap:12px;">
                            <input type="checkbox" id="pf-master-checkbox" title="Выделить всё" style="width:18px; height:18px; cursor:pointer;">
                            <h3 style="margin:0;">${esc(d.project)} · ${esc(d.severity)}/${esc(d.quality)} · ${d.issuesCount} issues · ${d.filesCount} файлов</h3>
                        </div>
                        <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                            <label style="display:flex; align-items:center; gap:4px; font-size:12px; cursor:pointer;" title="Скрыть issues, у которых уже установлен статус">
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
                }).filter(f => f.file.toLowerCase().includes(query) || f.issues.length > 0);
            };

            const renderList = () => {
                const filtered = applyFilters();

                if (filtered.length === 0) {
                    listEl.innerHTML = '<div class="empty">Ничего не найдено по фильтру</div>';
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

                        ${f.issues.length > 0 ? `
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
                        </tbody></table>` : ''}
                    </div>
                `).join('');

                syncCheckboxes(filtered);

                listEl.querySelectorAll('.pf-issue-checkbox').forEach(cb => {
                    cb.addEventListener('change', (e) => {
                        if (e.target.checked) selectedKeys.add(e.target.dataset.key);
                        else selectedKeys.delete(e.target.dataset.key);
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
                            else selectedKeys.delete(it.key);
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

                        showIssueContextMenu(e.pageX, e.pageY, issue, targetIssues, filtered, ctx, () => {
                            document.querySelector('.tab[data-tab="perfile"]').click();
                        }, () => {
                            selectedKeys.clear();
                            filtered.forEach(f => f.issues.forEach(it => selectedKeys.add(it.key)));
                            renderList();
                        }, () => {
                            selectedKeys.clear();
                            renderList();
                        });
                    });
                });
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

            const syncFileCheckbox = (fileName, filtered) => {
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

            const updateBulkBtn = () => {
                bulkBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                bulkBtn.textContent = `Действия с выбранными (${selectedKeys.size})`;

                const visibleIssues = applyFilters().flatMap(f => f.issues);
                const allVisibleChecked = visibleIssues.length > 0 && visibleIssues.every(it => selectedKeys.has(it.key));
                masterCb.checked = allVisibleChecked;
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
                    document.querySelector('.tab[data-tab="perfile"]').click();
                });
            });

            renderList();
        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};

// ==================== КОНТЕКСТНОЕ МЕНЮ ====================

function showIssueContextMenu(x, y, clickedIssue, targetIssues, filteredData, ctx, reloadCb, onSelectAll, onDeselectAll) {
    document.querySelector('.ctx-menu')?.remove();

    const hasUnmarked = targetIssues.some(it => it.status === 'OPEN' || it.status === 'REOPENED');
    const hasMarked = targetIssues.some(it => it.status !== 'OPEN' && it.status !== 'REOPENED');

    const menu = document.createElement('div');
    menu.className = 'ctx-menu';
    menu.style.left = x + 'px';
    menu.style.top = y + 'px';

    menu.innerHTML = `
        <div class="ctx-item" id="ctx-ai-prompt">📝 Сгенерировать промпт</div>
        <div class="ctx-item" id="ctx-details">Посмотреть детали</div>
        <div class="ctx-item" id="ctx-edit-tags">🏷️ Редактировать теги</div>
        <div class="ctx-item" style="border-top:1px solid var(--border); margin-top:4px; padding-top:8px;" id="ctx-select-all">Выделить все</div>
        <div class="ctx-item" id="ctx-deselect-all">Снять выделение</div>
        ${hasUnmarked ? `<div class="ctx-item" style="border-top:1px solid var(--border); margin-top:4px; padding-top:8px;" id="ctx-comment">Добавить комментарий (${targetIssues.filter(it => it.status === 'OPEN' || it.status === 'REOPENED').length})</div>` : ''}
        ${hasUnmarked ? `<div class="ctx-item" id="ctx-auto-comment">Добавить автоматически комментарий (${targetIssues.filter(it => it.status === 'OPEN' || it.status === 'REOPENED').length})</div>` : ''}
        ${hasMarked ? `<div class="ctx-item" id="ctx-remove">Отменить комментарий (Reopen) (${targetIssues.filter(it => it.status !== 'OPEN' && it.status !== 'REOPENED').length})</div>` : ''}
    `;

    document.body.appendChild(menu);

    const closeMenu = () => menu.remove();
    document.addEventListener('click', closeMenu, { once: true });

    menu.querySelector('#ctx-ai-prompt').addEventListener('click', () => {
        if (targetIssues.length > 0) fetchAndShowPrompt(targetIssues[0], ctx);
    });

    menu.querySelector('#ctx-details').addEventListener('click', () => {
        openIssueModal(clickedIssue, ctx, reloadCb);
    });

    menu.querySelector('#ctx-edit-tags').addEventListener('click', () => {
        showTagEditor(targetIssues, ctx, reloadCb);
    });

    menu.querySelector('#ctx-select-all').addEventListener('click', onSelectAll);
    menu.querySelector('#ctx-deselect-all').addEventListener('click', onDeselectAll);

    if (hasUnmarked) {
        menu.querySelector('#ctx-comment').addEventListener('click', () => {
            openBulkModal(targetIssues.filter(it => it.status === 'OPEN' || it.status === 'REOPENED'), ctx, reloadCb);
        });
        menu.querySelector('#ctx-auto-comment').addEventListener('click', () => {
            openAutoBulkModal(targetIssues.filter(it => it.status === 'OPEN' || it.status === 'REOPENED'), ctx, reloadCb);
        });
    }

    if (hasMarked) {
        menu.querySelector('#ctx-remove').addEventListener('click', async () => {
            const reopenableIssues = targetIssues.filter(it => it.status !== 'OPEN' && it.status !== 'REOPENED');

            if (!confirm(`Отменить комментарий (reopen) у ${reopenableIssues.length} issues?`)) return;

            let success = 0;
            for (let i = 0; i < reopenableIssues.length; i++) {
                const it = reopenableIssues[i];
                try {
                    const p = new URLSearchParams(ctx.qs());
                    p.set('key', it.key);
                    p.set('transition', getReopenTransition(it.status));
                    p.set('dryRun', 'false');
                    const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                    if (res.success === false) throw new Error(res.error || 'Не удалось сбросить');
                    success++;
                } catch (e) {
                    console.error(e);
                    toast('Ошибка для ' + it.key + ': ' + e.message, true);
                }
            }
            toast(`Отменено ${success} из ${reopenableIssues.length}`);
            ctx.refreshStatus?.();
            reloadCb();
        });
    }
}

// ==================== МОДАЛЬНЫЕ ОКНА ====================

async function openIssueModal(issue, ctx, reloadCb) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 800px;">
            <div class="modal-header">
                <h3 style="margin:0;">Детали ошибки</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body" id="modal-body">
                <div class="empty">Загрузка...</div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    try {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        p.set('issueKey', issue.key);
        const d = await ctx.api('/api/snippet?' + p);
        const it = d.issue || issue;

        // Загружаем доступные теги для промпта
        const availableTags = await fetchAvailableTags(ctx);

        const currentStatusValue = getMappedStatus(it.status);
        const canReopen = it.status && it.status !== 'OPEN' && it.status !== 'REOPENED';

        const comments = it.comments || d.comments || [];
        const commentsHtml = comments.length > 0 ? `
            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">История комментариев:</label>
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

        // Состояние для AI-предложенных тегов
        let parsedTags = [];
        let tagsManuallyEdited = false;

        const body = overlay.querySelector('#modal-body');
        body.innerHTML = `
            <div style="margin-bottom:12px;">
                <b style="font-size:16px;">${esc(it.message)}</b>
                <div style="color:var(--text-dim); margin-top:4px;">${esc(d.ruleName || it.rule)}</div>
            </div>

            <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:16px; font-size:12px; color:var(--text-dim);">
                <div><b>Файл:</b> ${esc(it.component)}</div>
                <div><b>Строка:</b> ${it.line}</div>
                <div><b>Severity:</b> <span class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</span></div>
                <div><b>Текущий статус:</b> ${esc(it.status)}</div>
            </div>

            ${(d.snippet && d.snippet.length)
                ? `<pre class="code" style="max-height:300px; overflow:auto;">${highlightCode(d.snippet.join('\n'))}</pre>`
                : `<div class="empty">Код недоступен</div>`}

            ${commentsHtml}

            <div id="modal-ai-tags-hint" style="display:none; margin-top:12px; padding:8px; background:var(--bg3); border:1px solid var(--accent); border-radius:6px; font-size:12px;"></div>

            <div style="margin-top:20px; padding-top:16px; border-top:1px solid var(--border);">
                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Статус перехода</label>
                    <select id="modal-status" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                        <option value="accept" ${currentStatusValue === 'accept' ? 'selected' : ''}>Принять (Это ошибка, но исправлена будет позднее)</option>
                        <option value="falsepositive" ${currentStatusValue === 'falsepositive' ? 'selected' : ''}>Ложно (Анализ содержит ошибки)</option>
                        <option value="confirm" ${currentStatusValue === 'confirm' ? 'selected' : ''}>Подтвердить</option>
                        <option value="resolve" ${currentStatusValue === 'resolve' ? 'selected' : ''}>Исправлено</option>
                        <option value="wontfix" ${currentStatusValue === 'wontfix' ? 'selected' : ''}>Не исправлять</option>
                    </select>
                </div>
                <div>
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Новый комментарий</label>
                    <textarea id="modal-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите новый комментарий..."></textarea>
                </div>
                <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                        <button id="modal-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM">Сгенерировать промпт</button>
                        <button id="modal-btn-ai-resolve" type="button" class="btn" title="Получить резолюцию от Ollama">Получить резолюцию ИИ</button>
                        <button id="modal-btn-tags" type="button" class="btn">🏷️ Изменить теги</button>
                        ${canReopen ? `<button id="modal-btn-reopen" class="btn btn-danger">Отменить комментарий (Reopen)</button>` : ''}
                    </div>
                    <div style="display:flex; gap:8px; align-items:center;">
                        <button id="modal-btn-cancel" class="btn">Отмена</button>
                        <button id="modal-btn-apply" class="btn btn-accent">Применить</button>
                        <button id="modal-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
                    </div>
                </div>
            </div>
        `;

        // Кнопка "Сгенерировать промпт" (копирование в буфер + textarea fallback)
        overlay.querySelector('#modal-btn-gen-prompt').addEventListener('click', async () => {
            const sel = overlay.querySelector('#modal-status');
            const status = sel.value;
            const statusLabel = sel.selectedOptions[0].textContent.trim();
            const prompt = buildStatusCommentPrompt(it, d.ruleName, d.snippet, status, statusLabel, availableTags);
            try {
                await navigator.clipboard.writeText(prompt);
                toast('Промпт скопирован в буфер обмена');
            } catch (e) {
                const ta = overlay.querySelector('#modal-comment');
                ta.value = prompt;
                ta.focus(); ta.select();
                toast('Промпт вставлен в поле комментария', true);
            }
        });

        bindOllamaResolveButton(overlay, {
            ctx,
            buildPrompt: (status, statusLabel) =>
                buildStatusCommentPrompt(it, d.ruleName, d.snippet, status, statusLabel, availableTags),
            commentSelector: '#modal-comment',
            onComplete: (fullResponse) => {
                const parsed = parseOllamaResponse(fullResponse);

                const statusSelect = overlay.querySelector('#modal-status');
                if (statusSelect && parsed.status) {
                    statusSelect.value = parsed.status;
                }

                const commentTa = overlay.querySelector('#modal-comment');
                if (commentTa) {
                    commentTa.value = parsed.comment;
                }

                // NEW: сохраняем теги и показываем подсказку
                parsedTags = parsed.tags || [];
                if (parsedTags.length > 0) {
                    const hint = overlay.querySelector('#modal-ai-tags-hint');
                    hint.style.display = 'block';
                    hint.innerHTML = `🤖 AI предлагает теги: <b>${esc(parsedTags.join(', '))}</b><br>
                        <span style="color:var(--text-dim);">Будут применены при нажатии «Применить».</span>`;
                }
            }
        });
        bindOllamaSettingsButton(overlay, ctx);

        // Ручное редактирование тегов
        overlay.querySelector('#modal-btn-tags').addEventListener('click', () => {
            tagsManuallyEdited = true;
            showTagEditor([it], ctx, () => {
                close();
                reloadCb();
            });
        });

        overlay.querySelector('#modal-btn-apply').addEventListener('click', async () => {
            const status = overlay.querySelector('#modal-status').value;
            const comment = overlay.querySelector('#modal-comment').value;
            try {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', status);
                p.set('comment', comment);
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                if (res.success === false) throw new Error(res.error || 'Ошибка применения');

                // NEW: применяем AI-теги (если не было ручного редактирования)
                if (parsedTags.length > 0 && !tagsManuallyEdited) {
                    try {
                        await ctx.api('/api/set_tags?keys=' + encodeURIComponent(it.key) + '&tags=' + encodeURIComponent(parsedTags.join(',')), { method: 'POST' });
                    } catch (e) {
                        console.warn('Не удалось применить AI-теги:', e);
                        ctx.toast('Статус применён, но теги не установлены: ' + e.message, true);
                    }
                }

                ctx.toast('Успешно применено');
                close();
                ctx.refreshStatus?.();
                reloadCb();
            } catch (e) {
                ctx.toast(e.message, true);
            }
        });

        if (canReopen) {
            overlay.querySelector('#modal-btn-reopen').addEventListener('click', async () => {
                if (!confirm('Отменить комментарий (reopen)?')) return;
                try {
                    const p = new URLSearchParams(ctx.qs());
                    p.set('key', it.key);
                    p.set('transition', getReopenTransition(it.status));
                    p.set('dryRun', 'false');
                    const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                    if (res.success === false) throw new Error(res.error || 'reopen failed');
                    ctx.toast('Статус сброшен');
                    close();
                    ctx.refreshStatus?.();
                    reloadCb();
                } catch (e) {
                    ctx.toast(e.message, true);
                }
            });
        }

        overlay.querySelector('#modal-btn-cancel').addEventListener('click', close);

    } catch (e) {
        overlay.querySelector('#modal-body').innerHTML = `<div class="sev-HIGH">${esc(e.message)}</div>`;
    }
}

function openBulkModal(issues, ctx, reloadCb) {
    if (!issues.length) { toast('Нет доступных issues для изменения', true); return; }

    // async IIFE чтобы дождаться тегов
    (async () => {
        let availableTags = [];
        try {
            const d = await ctx.api('/api/tags');
            availableTags = d.tags || [];
        } catch (e) {
            console.warn('Не удалось загрузить теги', e);
        }

        const currentTags = issues[0].tags || [];
        const initialTags = [...currentTags];
        currentTags.forEach(t => {
            if (!availableTags.includes(t)) availableTags.push(t);
        });

        const PRIORITY_TAGS = ['CWE', 'not-require-fix', 'require-fix'];
        const initialSet = new Set(initialTags);
        const selectedTags = new Set(initialTags);
        let tagsQuery = '';

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
                        <input type="text" id="bulk-tags-filter" placeholder="Фильтр по тегам..."
                               style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                                      border-radius:6px; border:1px solid var(--border);
                                      background:var(--bg); color:var(--text);">
                        <div id="bulk-tags-list"
                             style="max-height: 150px; overflow-y: auto; display: flex; flex-wrap: wrap;
                                    gap: 8px; padding: 8px; background: var(--bg3);
                                    border: 1px solid var(--border); border-radius: 6px;"></div>
                    </div>
                    <div id="bulk-ai-tags-hint" style="display:none; margin-bottom:12px; padding:8px; background:var(--bg3); border:1px solid var(--accent); border-radius:6px; font-size:12px;"></div>
                    <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                        <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                            <button id="bulk-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM">Сгенерировать промпт</button>
                            <button id="bulk-btn-ai-resolve" type="button" class="btn" title="Получить резолюцию от Ollama">Получить резолюцию ИИ</button>
                            <button id="bulk-btn-tags" type="button" class="btn">🏷️ Изменить теги</button>
                        </div>
                        <div style="display:flex; gap:8px; align-items:center;">
                            <button id="bulk-cancel" class="btn">Отмена</button>
                            <button id="bulk-apply" class="btn btn-accent">Применить</button>
                            <button id="bulk-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
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

        // ---------- Теги: рендер и обработчики ----------
        function getOrderedTags(query) {
            const q = String(query || '').trim().toLowerCase();
            const matches = (t) => !q || t.toLowerCase().includes(q);

            const already = initialTags.filter(matches);
            const priority = PRIORITY_TAGS.filter(
                t => availableTags.includes(t) && !initialSet.has(t) && matches(t)
            );
            const rest = availableTags
                .filter(t => !initialSet.has(t) && !PRIORITY_TAGS.includes(t) && matches(t))
                .sort((a, b) => a.localeCompare(b));

            return [...already, ...priority, ...rest];
        }

        function renderBulkTagList() {
            const container = overlay.querySelector('#bulk-tags-list');
            if (!container) return;
            const ordered = getOrderedTags(tagsQuery);
            if (ordered.length === 0) {
                container.innerHTML = `<div style="color:var(--text-dim); font-size:12px;">${
                    tagsQuery.trim() ? 'Нет тегов по фильтру' : 'Нет доступных тегов'
                }</div>`;
                return;
            }
            container.innerHTML = ordered.map(t => `
                <label style="display: flex; align-items: center; gap: 4px; font-size: 12px; cursor: pointer;">
                    <input type="checkbox" class="bulk-tag-cb" value="${esc(t)}" ${selectedTags.has(t) ? 'checked' : ''}>
                    ${esc(t)}
                </label>
            `).join('');
        }

        renderBulkTagList();

        overlay.querySelector('#bulk-tags-filter').addEventListener('input', (e) => {
            tagsQuery = e.target.value;
            renderBulkTagList();
        });

        overlay.querySelector('#bulk-tags-list').addEventListener('change', (e) => {
            if (e.target.classList.contains('bulk-tag-cb')) {
                if (e.target.checked) selectedTags.add(e.target.value);
                else                  selectedTags.delete(e.target.value);
            }
        });

        // ---------- AI ----------
        overlay.querySelector('#bulk-btn-gen-prompt').addEventListener('click', async () => {
            const status = overlay.querySelector('#bulk-status').value;
            const statusLabel = overlay.querySelector('#bulk-status').selectedOptions[0].textContent.trim();
            const prompt = await buildBulkStatusCommentPrompt(issues, status, statusLabel, ctx, availableTags);
            try {
                await navigator.clipboard.writeText(prompt);
                toast('Промпт скопирован в буфер обмена');
            } catch (e) {
                const ta = overlay.querySelector('#bulk-comment');
                ta.value = prompt; ta.focus(); ta.select();
                toast('Промпт вставлен в поле комментария', true);
            }
        });

        bindOllamaResolveButton(overlay, {
            ctx,
            buildPrompt: (status, statusLabel) => buildBulkStatusCommentPrompt(issues, status, statusLabel, ctx, availableTags),
            commentSelector: '#bulk-comment',
            btnSelector: '#bulk-btn-ai-resolve',
            onComplete: (fullResponse) => {
                const parsed = parseOllamaResponse(fullResponse);

                const statusSel = overlay.querySelector('#bulk-status');
                if (statusSel && parsed.status) statusSel.value = parsed.status;

                const commentTa = overlay.querySelector('#bulk-comment');
                if (commentTa) commentTa.value = parsed.comment;

                // NEW: отмечаем AI-теги в чекбоксах
                if (parsed.tags && parsed.tags.length > 0) {
                    parsed.tags.forEach(tag => {
                        // Если тега нет в списке — добавим как "выбранный"
                        if (!availableTags.includes(tag)) {
                            availableTags.push(tag);
                        }
                        selectedTags.add(tag);
                    });
                    renderBulkTagList();

                    const hint = overlay.querySelector('#bulk-ai-tags-hint');
                    hint.style.display = 'block';
                    hint.innerHTML = `🤖 AI предлагает теги: <b>${esc(parsed.tags.join(', '))}</b>
                        <span style="color:var(--text-dim);"> (отмечены в списке выше)</span>`;
                }
            }
        });
        bindOllamaSettingsButton(overlay, ctx, '#bulk-btn-ollama-settings');

        // Ручное редактирование тегов
        overlay.querySelector('#bulk-btn-tags').addEventListener('click', () => {
            // Сбрасываем AI-флаг: пользователь выбрал ручной режим
            showTagEditor(issues, ctx, () => {
                close();
                reloadCb();
            });
        });

        // ---------- Apply ----------
        overlay.querySelector('#bulk-apply').addEventListener('click', async () => {
            const status = overlay.querySelector('#bulk-status').value;
            const comment = overlay.querySelector('#bulk-comment').value;

            const checkedTags = [...selectedTags];
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

            // Применяем теги, если они изменились
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
    })();
}

function openAutoBulkModal(issues, ctx, reloadCb) {
    if (!issues.length) { toast('Нет доступных issues для изменения', true); return; }

    (async () => {
        // Загружаем теги один раз
        const availableTags = await fetchAvailableTags(ctx);

        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        overlay.innerHTML = `
            <div class="modal-content" style="max-width: 800px;">
                <div class="modal-header">
                    <h3 style="margin:0;" id="auto-header">Автоматическая обработка (0 / ${issues.length})</h3>
                    <button class="modal-close">&times;</button>
                </div>
                <div class="modal-body">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 16px;">
                        <div id="auto-progress" style="font-weight: bold; color: var(--accent);">
                            Ожидание запуска...
                        </div>
                        <label style="display:flex; align-items:center; gap:6px; font-size:13px; cursor:pointer; user-select:none;">
                            <input type="checkbox" id="auto-chk-semi" checked style="width:16px; height:16px; cursor:pointer;">
                            Полуавтоматический режим (с подтверждением)
                        </label>
                    </div>

                    <div class="card" style="margin-bottom: 16px; border-color: var(--accent);">
                        <div style="font-size: 12px; color: var(--text-dim); margin-bottom: 8px;">
                            Текущая ошибка: <b id="auto-current-key">-</b> (Файл: <span id="auto-current-file">-</span>)
                        </div>

                        <div id="auto-current-msg" style="margin-bottom: 12px; font-size: 14px; font-weight: 500; line-height: 1.4; color: var(--text);">
                            -
                        </div>

                        <div style="margin-bottom:12px;">
                            <label style="display:block; font-size:12px; margin-bottom:4px;">Статус перехода</label>
                            <select id="auto-status" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                                <option value="accept">Принять (Это ошибка, но исправлена будет позднее)</option>
                                <option value="falsepositive">Ложно (Анализ содержит ошибки)</option>
                                <option value="confirm">Подтвердить</option>
                                <option value="resolve">Исправлено</option>
                                <option value="wontfix">Не исправлять</option>
                            </select>
                        </div>

                        <div style="margin-bottom:12px;">
                            <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                            <textarea id="auto-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
                        </div>

                        <div id="auto-ai-tags-hint" style="display:none; margin-bottom:12px; padding:8px; background:var(--bg3); border:1px solid var(--accent); border-radius:6px; font-size:12px;"></div>

                        <div style="display:flex; gap:10px; flex-wrap:wrap;">
                            <button id="auto-btn-start" class="btn btn-accent">▶️ Запустить</button>
                            <button id="auto-btn-apply" class="btn" disabled>✅ Подтвердить и далее</button>
                            <button id="auto-btn-skip" class="btn" disabled>⏭️ Пропустить</button>
                            <button id="auto-btn-stop" class="btn btn-danger" style="display:none;">⏹️ Остановить</button>
                            <button id="auto-btn-tags" type="button" class="btn">🏷️ Изменить теги</button>
                            <button id="auto-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
                        </div>
                    </div>

                    <div style="display:flex; justify-content:space-between; align-items:center;">
                        <span id="auto-log" style="font-size:12px; color:var(--text-dim);">Нажмите «Запустить» для начала</span>
                        <button id="auto-btn-cancel" class="btn">Закрыть</button>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(overlay);

        const ui = {
            header: overlay.querySelector('#auto-header'),
            progress: overlay.querySelector('#auto-progress'),
            chkSemi: overlay.querySelector('#auto-chk-semi'),
            currentKey: overlay.querySelector('#auto-current-key'),
            currentFile: overlay.querySelector('#auto-current-file'),
            currentMsg: overlay.querySelector('#auto-current-msg'),
            statusSel: overlay.querySelector('#auto-status'),
            commentTa: overlay.querySelector('#auto-comment'),
            btnStart: overlay.querySelector('#auto-btn-start'),
            btnApply: overlay.querySelector('#auto-btn-apply'),
            btnSkip: overlay.querySelector('#auto-btn-skip'),
            btnStop: overlay.querySelector('#auto-btn-stop'),
            btnTags: overlay.querySelector('#auto-btn-tags'),
            aiTagsHint: overlay.querySelector('#auto-ai-tags-hint'),
            log: overlay.querySelector('#auto-log')
        };

        let currentIndex = 0;
        let isProcessing = false;
        let hasGenerated = false;
        let abortController = null;
        let currentSuggestedTags = []; // NEW: теги, предложенные AI для текущего issue

        const close = () => {
            if (isProcessing || hasGenerated) {
                if (!confirm('Процесс не завершен. Вы уверены, что хотите выйти?')) return;
            }
            if (abortController) abortController.abort();
            overlay.remove();
            if (reloadCb) reloadCb();
        };

        overlay.querySelector('.modal-close').addEventListener('click', close);
        overlay.querySelector('#auto-btn-cancel').addEventListener('click', close);

        // Ручное редактирование тегов для текущего issue
        ui.btnTags.addEventListener('click', () => {
            if (currentIndex >= issues.length) return;
            const it = issues[currentIndex];
            showTagEditor([it], ctx, () => {
                // Не сбрасываем прогресс
            });
        });

        function updateUI() {
            if (currentIndex >= issues.length) {
                ui.header.textContent = `Обработка завершена (${issues.length} / ${issues.length})`;
                ui.btnStart.style.display = 'none';
                ui.btnApply.disabled = true;
                ui.btnSkip.disabled = true;
                ui.btnStop.style.display = 'none';
                ui.progress.textContent = 'Все ошибки обработаны.';
                return;
            }

            ui.header.textContent = `Автоматическая обработка (${currentIndex + 1} / ${issues.length})`;
            const it = issues[currentIndex];
            ui.currentKey.textContent = it.key;
            ui.currentFile.textContent = it.component;
            ui.currentMsg.textContent = it.message || 'Нет описания ошибки';

            if (isProcessing) {
                ui.btnStart.style.display = 'none';
                ui.btnStop.style.display = 'inline-block';
                ui.btnApply.disabled = true;
                ui.btnSkip.disabled = true;
            } else if (hasGenerated) {
                ui.btnStart.style.display = 'none';
                ui.btnStop.style.display = 'none';
                ui.btnApply.disabled = false;
                ui.btnSkip.disabled = false;
            } else {
                ui.btnStart.style.display = 'inline-block';
                ui.btnStop.style.display = 'none';
                ui.btnApply.disabled = true;
                ui.btnSkip.disabled = true;
            }
        }

        async function processNext() {
            if (isProcessing || currentIndex >= issues.length) return;
            isProcessing = true;
            updateUI();

            const it = issues[currentIndex];
            ui.progress.textContent = 'Анализ кода...';
            ui.commentTa.value = 'Генерация промпта...';
            ui.log.textContent = 'Подготовка данных...';
            ui.aiTagsHint.style.display = 'none';
            currentSuggestedTags = [];

            try {
                if (!it.snippet || !it.snippet.length) {
                    const p = new URLSearchParams();
                    if (ctx.state.project) p.set('project', ctx.state.project);
                    p.set('issueKey', it.key);
                    const d = await ctx.api('/api/snippet?' + p);
                    it.snippet = d.snippet || [];
                    it.ruleName = d.ruleName || it.rule;
                }

                const prompt = buildAutoResolvePromptAsync(it, ctx, availableTags);
                const settings = OllamaClient.getSettings();
                if (!settings.url || !settings.model) throw new Error("Ollama не настроена. Нажмите ⚙ Ollama.");

                ui.progress.textContent = 'Запрос к Ollama...';
                ui.log.textContent = 'Ожидание ответа от модели...';

                abortController = new AbortController();
                const client = new OllamaClient(settings);
                let fullResponse = '';

                for await (const chunk of client.streamChat({ prompt, signal: abortController.signal })) {
                    fullResponse += chunk;
                    ui.commentTa.value = fullResponse;
                }

                const parsed = parseOllamaResponse(fullResponse);
                ui.statusSel.value = parsed.status;
                ui.commentTa.value = parsed.comment;

                // NEW: сохраняем предложенные AI теги и показываем подсказку
                currentSuggestedTags = parsed.tags || [];
                if (currentSuggestedTags.length > 0) {
                    ui.aiTagsHint.style.display = 'block';
                    ui.aiTagsHint.innerHTML = `🤖 AI предлагает теги: <b>${esc(currentSuggestedTags.join(', '))}</b>
                        <span style="color:var(--text-dim);"> (будут применены при подтверждении)</span>`;
                }

                hasGenerated = true;
                ui.progress.textContent = 'Готово к применению';
                isProcessing = false;
                updateUI();

                if (ui.chkSemi.checked) {
                    ui.log.textContent = 'Проверьте данные и нажмите «Подтвердить» или «Пропустить».';
                } else {
                    ui.log.textContent = 'Автоматический режим. Применение через 1 сек...';
                    setTimeout(() => {
                        if (!ui.chkSemi.checked && hasGenerated && !isProcessing) {
                            applyCurrent();
                        }
                    }, 1000);
                }

            } catch (e) {
                if (e.name === 'AbortError') {
                    ui.log.textContent = 'Остановлено пользователем.';
                    ui.progress.textContent = 'Пауза.';
                    isProcessing = false;
                    hasGenerated = false;
                    updateUI();
                    return;
                }
                ui.log.textContent = 'Ошибка: ' + e.message;
                ui.progress.textContent = 'Остановлено из-за ошибки Ollama';
                isProcessing = false;
                hasGenerated = false;
                updateUI();
                toast('Ошибка Ollama: ' + e.message, true);
            } finally {
                abortController = null;
            }
        }

        async function applyCurrent() {
            if (isProcessing || currentIndex >= issues.length) return;
            isProcessing = true;
            updateUI();

            const it = issues[currentIndex];
            const status = ui.statusSel.value;
            const comment = ui.commentTa.value;

            ui.log.textContent = 'Применение...';

            try {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', status);
                p.set('comment', comment);
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                if (res.success === false) throw new Error(res.error || 'Ошибка применения');

                // NEW: применяем AI-теги для текущего issue
                if (currentSuggestedTags.length > 0) {
                    try {
                        await ctx.api('/api/set_tags?keys=' + encodeURIComponent(it.key) + '&tags=' + encodeURIComponent(currentSuggestedTags.join(',')), { method: 'POST' });
                    } catch (e) {
                        console.warn('Не удалось применить AI-теги:', e);
                        toast('Теги не установлены: ' + e.message, true);
                    }
                }

                it.status = status;
                currentIndex++;
                hasGenerated = false;
                currentSuggestedTags = [];
                ui.aiTagsHint.style.display = 'none';
                ui.commentTa.value = '';
                ui.log.textContent = 'Успешно применено. Переход к следующей...';

                isProcessing = false;
                updateUI();

                if (currentIndex < issues.length) {
                    processNext();
                }

            } catch (e) {
                ui.log.textContent = 'Ошибка применения: ' + e.message;
                isProcessing = false;
                updateUI();
                toast(e.message, true);
            }
        }

        function skipCurrent() {
            if (currentIndex >= issues.length) return;
            currentIndex++;
            hasGenerated = false;
            currentSuggestedTags = [];
            ui.aiTagsHint.style.display = 'none';
            ui.commentTa.value = '';
            ui.log.textContent = 'Пропущено пользователем.';
            updateUI();
            if (currentIndex < issues.length) {
                processNext();
            }
        }

        ui.btnStart.addEventListener('click', () => {
            if (currentIndex >= issues.length) return;
            processNext();
        });

        ui.btnStop.addEventListener('click', () => {
            if (abortController) abortController.abort();
        });

        ui.btnApply.addEventListener('click', () => {
            applyCurrent();
        });

        ui.btnSkip.addEventListener('click', () => {
            skipCurrent();
        });

        ui.chkSemi.addEventListener('change', () => {
            if (!ui.chkSemi.checked && hasGenerated && !isProcessing) {
                ui.log.textContent = 'Режим изменен на автоматический. Применение...';
                setTimeout(() => {
                    if (!ui.chkSemi.checked && hasGenerated && !isProcessing) {
                        applyCurrent();
                    }
                }, 500);
            }
        });

        overlay.querySelector('#auto-btn-ollama-settings').addEventListener('click', () => openOllamaSettingsModal(ctx));

        updateUI();
    })();
}