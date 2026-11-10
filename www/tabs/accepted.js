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

            let filterText       = '';
            let severityFilter   = '';
            let typeFilter       = '';
            let resolutionFilter = '';
            let selectedKeys     = new Set();

            el.innerHTML = `
                <div class="pf-header-sticky">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                        <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
                            <h3 style="margin:0;">${esc(d.project)} · Обработанные: ${d.total}</h3>
                            ${renderFacetChips('sev', severities)}
                            ${renderFacetChips('type', types)}
                            ${renderFacetChips('res', resolutions)}
                        </div>
                        <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                            <input type="text" id="acc-filter" placeholder="Фильтр по ключу, файлу или сообщению..."
                                   style="padding:6px 10px; border-radius:6px; border:1px solid var(--border); background:var(--bg); color:var(--text); width:250px;">
                            <button id="acc-bulk-reopen" class="btn btn-danger" style="display:none;">Reopen выбранные (0)</button>
                        </div>
                    </div>
                </div>
                <div id="acc-list"></div>
            `;

            const listEl      = el.querySelector('#acc-list');
            const filterInput = el.querySelector('#acc-filter');
            const bulkBtn     = el.querySelector('#acc-bulk-reopen');
            const masterCb    = el.querySelector('#acc-master-checkbox');

            const applyFilters = () => {
                const q = filterText.toLowerCase();
                return issues.filter(it => {
                    if (severityFilter && (it.impactSeverity || it.severity) !== severityFilter) return false;
                    if (typeFilter && it.type !== typeFilter) return false;
                    if (resolutionFilter && it.resolution !== resolutionFilter) return false;
                    if (!q) return true;
                    return (it.key || '').toLowerCase().includes(q)
                        || (it.message || '').toLowerCase().includes(q)
                        || (it.rule || '').toLowerCase().includes(q)
                        || (it.component || '').toLowerCase().includes(q);
                });
            };

            const renderList = () => {
                const filtered = applyFilters();
                if (!filtered.length) {
                    listEl.innerHTML = '<div class="empty">Нет обработанных issues по фильтру</div>';
                    updateBulkBtn();
                    return;
                }

                listEl.innerHTML = `
                    <div class="card" style="padding:0; overflow:hidden;">
                        <div style="overflow-x:auto">
                            <table>
                                <thead>
                                    <tr>
                                        <th style="width:30px; padding-left:16px;">
                                            <input type="checkbox" id="acc-master-checkbox" title="Выделить всё" style="cursor:pointer;">
                                        </th>
                                        <th>key</th>
                                        <th>sev</th>
                                        <th>component:line</th>
                                        <th>rule</th>
                                        <th>message</th>
                                        <th>status</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    ${filtered.map(it => `
                                        <tr class="pf-issue-row" data-key="${esc(it.key)}" style="cursor:pointer;">
                                            <td style="padding-left:16px;">
                                                <input type="checkbox" class="acc-issue-checkbox" data-key="${esc(it.key)}" ${selectedKeys.has(it.key) ? 'checked' : ''}>
                                            </td>
                                            <td><code>${esc(it.key)}</code></td>
                                            <td class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</td>
                                            <td>${esc(it.component)}:${it.line}</td>
                                            <td>${esc(it.rule)}</td>
                                            <td>${esc(it.message)}</td>
                                            <td>${acceptedBadge(it)}</td>
                                        </tr>
                                    `).join('')}
                                </tbody>
                            </table>
                        </div>
                    </div>
                `;

                const currentMasterCb = listEl.querySelector('#acc-master-checkbox');
                const allFilteredKeys = filtered.map(it => it.key);
                if (allFilteredKeys.length > 0 && allFilteredKeys.every(k => selectedKeys.has(k))) {
                    currentMasterCb.checked = true;
                    currentMasterCb.indeterminate = false;
                } else if (allFilteredKeys.some(k => selectedKeys.has(k))) {
                    currentMasterCb.checked = false;
                    currentMasterCb.indeterminate = true;
                } else {
                    currentMasterCb.checked = false;
                    currentMasterCb.indeterminate = false;
                }

                currentMasterCb.addEventListener('change', e => {
                    const isChecked = e.target.checked;
                    if (isChecked) {
                        filtered.forEach(it => selectedKeys.add(it.key));
                    } else {
                        filtered.forEach(it => selectedKeys.delete(it.key));
                    }
                    renderList();
                    updateBulkBtn();
                });

                listEl.querySelectorAll('.acc-issue-checkbox').forEach(cb => {
                    cb.addEventListener('change', e => {
                        if (e.target.checked) selectedKeys.add(e.target.dataset.key);
                        else selectedKeys.delete(e.target.dataset.key);
                        updateBulkBtn();
                    });
                });

                listEl.querySelectorAll('.pf-issue-row').forEach(row => {
                    row.addEventListener('dblclick', e => {
                        if (e.target.type === 'checkbox') return;
                        const it = filtered.find(x => x.key === row.dataset.key);
                        if (it) openAcceptedModal(it, ctx, () => accepted.mount(el, ctx));
                    });

                    row.addEventListener('contextmenu', e => {
                        e.preventDefault();
                        const key = row.dataset.key;
                        const issue = filtered.find(x => x.key === key);

                        let targetIssues = [];
                        if (selectedKeys.size > 0) {
                            targetIssues = issues.filter(it => selectedKeys.has(it.key));
                        } else {
                            targetIssues = [issue];
                        }

                        showContextMenu(e.pageX, e.pageY, issue, targetIssues, ctx, () => {
                            accepted.mount(el, ctx);
                        });
                    });
                });

                updateBulkBtn();
            };

            const updateBulkBtn = () => {
                bulkBtn.style.display = selectedKeys.size > 0 ? 'inline-block' : 'none';
                bulkBtn.textContent = `Reopen выбранные (${selectedKeys.size})`;
            };

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

            bulkBtn.addEventListener('click', async () => {
                const keys = [...selectedKeys];
                if (!keys.length) return;
                if (!confirm(`Снять пометку (reopen) у ${keys.length} issues?`)) return;

                let success = 0;
                for (const key of keys) {
                    try {
                        const pp = new URLSearchParams(ctx.qs());
                        pp.set('key', key);
                        pp.set('transition', 'reopen');
                        pp.set('dryRun', 'false');
                        const res = await ctx.api('/api/mark?' + pp, { method: 'POST' });
                        if (res.success === false) throw new Error(res.error || 'Ошибка');
                        success++;
                    } catch (e) {
                        console.error('Reopen failed for ' + key, e);
                    }
                }
                ctx.toast(`Снято с ${success} из ${keys.length}`);
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
        let success = 0;
        for (const it of targetIssues) {
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
        toast(`Отменено ${success} из ${targetIssues.length}`);
        ctx.refreshStatus?.();
        reloadCb();
    });

    menu.querySelector('#ctx-reopened').addEventListener('click', async () => {
        if (!confirm(`Установить статус "Переоткрытое замечание" у ${targetIssues.length} issues?`)) return;
        let success = 0;
        for (const it of targetIssues) {
            try {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', 'reopen');
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                if (res.success === false) throw new Error(res.error || 'Не удалось открыть');
                success++;
            } catch (e) {
                console.error(e);
                toast('Ошибка для ' + it.key + ': ' + e.message, true);
            }
        }
        toast(`Открыто ${success} из ${targetIssues.length}`);
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

    // Загружаем доступные теги для чекбоксов
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

        // 1. Меняем статус и добавляем комментарий
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

        // 2. Если теги изменились — обновляем их
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