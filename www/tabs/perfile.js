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
        <div class="ctx-item" id="ctx-edit-tags">🏷️ Редактировать теги</div> <!-- НОВОЕ -->
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

    // НОВОЕ: Обработчик тегов
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
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Новый комментарий (старые сохраняются в истории SonarQube)</label>
                    <textarea id="modal-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите новый комментарий для изменения/дополнения..."></textarea>
                </div>
                <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                        <button id="modal-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM: сгенерировать короткий комментарий к выбранному статусу ошибки в SonarQube">Сгенерировать промпт</button>
                        <button id="modal-btn-ai-resolve" type="button" class="btn" title="Отправить промпт в Ollama и вставить ответ в поле «Комментарий»">Получить резолюцию ИИ</button>

                        <!-- НОВАЯ КНОПКА: ТЕГИ -->
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

        bindStatusPromptButton(overlay, it, d.ruleName, d.snippet, ctx);

        bindOllamaResolveButton(overlay, {
            ctx,
            buildPrompt: (status, statusLabel) =>
                buildStatusCommentPrompt(it, d.ruleName, d.snippet, status, statusLabel),
            commentSelector: '#modal-comment',
            onComplete: (fullResponse) => {
                const parsed = parseOllamaResponse(fullResponse);

                console.log("=== PARSED RESULT ===");
                console.log("Status:", parsed.status);
                console.log("Comment:", parsed.comment);
                console.log("=====================");

                const statusSelect = overlay.querySelector('#modal-status');
                if (statusSelect && parsed.status) {
                    statusSelect.value = parsed.status;
                }

                const commentTa = overlay.querySelector('#modal-comment');
                if (commentTa) {
                    commentTa.value = parsed.comment;
                }
            }
        });
        bindOllamaSettingsButton(overlay, ctx);

        // НОВОЕ: Обработчик кнопки тегов в модалке
        overlay.querySelector('#modal-btn-tags').addEventListener('click', () => {
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
                if (!confirm('Отменить комментарий (reopen)? Старые комментарии останутся в истории.')) return;
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
                <div style="margin-bottom:16px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                    <textarea id="bulk-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
                </div>
                <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                        <button id="bulk-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM для всей группы ошибок">Сгенерировать промпт</button>
                        <button id="bulk-btn-ai-resolve" type="button" class="btn" title="Отправить промпт в Ollama и вставить ответ в поле «Комментарий»">Получить резолюцию ИИ</button>

                        <!-- НОВАЯ КНОПКА: ТЕГИ -->
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

    // НОВОЕ: Обработчик кнопки тегов в массовой модалке
    overlay.querySelector('#bulk-btn-tags').addEventListener('click', () => {
        showTagEditor(issues, ctx, () => {
            close();
            reloadCb();
        });
    });

    overlay.querySelector('#bulk-btn-gen-prompt').addEventListener('click', async () => {
        const status = overlay.querySelector('#bulk-status').value;
        const statusLabel = overlay.querySelector('#bulk-status').selectedOptions[0].textContent.trim();

        const btn = overlay.querySelector('#bulk-btn-gen-prompt');
        const originalText = btn.textContent;
        btn.textContent = 'Подготовка...';
        btn.disabled = true;

        try {
            const prompt = await buildBulkStatusCommentPrompt(issues, status, statusLabel, ctx);
            const ok = await copyToClipboard(prompt);

            if (ok) {
                toast('Промпт для группы скопирован в буфер обмена');
            } else {
                const ta = overlay.querySelector('#bulk-comment');
                if (ta) {
                    ta.value = prompt;
                    ta.focus();
                    ta.select();
                }
                toast('Промпт вставлен в поле комментария', true);
            }
        } catch (e) {
            toast(e.message, true);
        } finally {
            btn.textContent = originalText;
            btn.disabled = false;
        }
    });

    bindOllamaResolveButton(overlay, {
        ctx,
        buildPrompt: (status, statusLabel) => buildBulkStatusCommentPrompt(issues, status, statusLabel, ctx),
        commentSelector: '#bulk-comment',
        btnSelector: '#bulk-btn-ai-resolve'
    });

    bindOllamaSettingsButton(overlay, ctx, '#bulk-btn-ollama-settings');

    overlay.querySelector('#bulk-apply').addEventListener('click', async () => {
        const status = overlay.querySelector('#bulk-status').value;
        const comment = overlay.querySelector('#bulk-comment').value;

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

        ctx.toast(`Применено к ${success} из ${issues.length}`);
        close();
        ctx.refreshStatus?.();
        reloadCb();
    });
}

function openAutoBulkModal(issues, ctx, reloadCb) {
    if (!issues.length) { toast('Нет доступных issues для изменения', true); return; }
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

                    <div style="margin-bottom:16px;">
                        <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                        <textarea id="auto-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
                    </div>

                    <div style="display:flex; gap:10px; flex-wrap:wrap;">
                        <button id="auto-btn-start" class="btn btn-accent">▶️ Запустить</button>
                        <button id="auto-btn-apply" class="btn" disabled>✅ Подтвердить и далее</button>
                        <button id="auto-btn-skip" class="btn" disabled>⏭️ Пропустить</button>
                        <button id="auto-btn-stop" class="btn btn-danger" style="display:none;">⏹️ Остановить</button>

                        <!-- НОВАЯ КНОПКА: ТЕГИ -->
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
        btnTags: overlay.querySelector('#auto-btn-tags'), // <-- НОВОЕ
        log: overlay.querySelector('#auto-log')
    };

    let currentIndex = 0;
    let isProcessing = false;
    let hasGenerated = false;
    let abortController = null;

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

    // НОВОЕ: Обработчик кнопки тегов для текущего issue
    ui.btnTags.addEventListener('click', () => {
        if (currentIndex >= issues.length) return;
        const it = issues[currentIndex];
        showTagEditor([it], ctx, () => {
            // Ничего не делаем, чтобы не сбрасывать прогресс
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

        try {
            if (!it.snippet || !it.snippet.length) {
                const p = new URLSearchParams();
                if (ctx.state.project) p.set('project', ctx.state.project);
                p.set('issueKey', it.key);
                const d = await ctx.api('/api/snippet?' + p);
                it.snippet = d.snippet || [];
                it.ruleName = d.ruleName || it.rule;
            }

            const prompt = buildAutoResolvePromptAsync(it, ctx);
            const settings = OllamaClient.getSettings();
            if (!settings.url || !settings.model) throw new Error("Ollama не настроена. Нажмите ⚙ Ollama.");

            console.log("=== OLLAMA REQUEST PROMPT (AUTO) ===");
            console.log(prompt);
            console.log("====================================");

            ui.progress.textContent = 'Запрос к Ollama...';
            ui.log.textContent = 'Ожидание ответа от модели...';

            abortController = new AbortController();
            const client = new OllamaClient(settings);
            let fullResponse = '';

            for await (const chunk of client.streamChat({ prompt, signal: abortController.signal })) {
                fullResponse += chunk;
                ui.commentTa.value = fullResponse;
            }

            console.log("=== OLLAMA FINAL RESPONSE (AUTO) ===");
            console.log(fullResponse);
            console.log("====================================");

            const parsed = parseOllamaResponse(fullResponse);
            ui.statusSel.value = parsed.status;
            ui.commentTa.value = parsed.comment;
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

            it.status = status;
            currentIndex++;
            hasGenerated = false;
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
}