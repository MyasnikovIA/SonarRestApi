import { esc, toast } from '../../common.js';

export async function showJiraIssueModal(keys, ctx, projectName, filters) {
    let cfg = {};
    try { cfg = await ctx.api('/api/jira/config'); } catch (e) { console.warn(e); }

    const f = filters || {};
    const isAll = (keys === null);

    // Баннер с ошибкой, если бэкенд вернул listError
    let errorBanner = '';
    if (cfg.listError) {
        errorBanner = `
            <div style="margin-bottom: 12px; font-size: 12px; color: var(--err); background: rgba(248,81,73,0.1); border: 1px solid var(--err); border-radius: 6px; padding: 8px 12px;">
                ⚠ ${esc(cfg.listError)}
            </div>
        `;
    }
    if (cfg.sessionError) {
        errorBanner += `
            <div style="margin-bottom: 12px; font-size: 12px; color: var(--warn); background: rgba(210,153,34,0.1); border: 1px solid var(--warn); border-radius: 6px; padding: 8px 12px;">
                ⚠ Сессия: ${esc(cfg.sessionError)}
            </div>
        `;
    }

    const defaultSummary = `${projectName}: Необходимо исправить ошибки, найденные SonarQube`;
    const defaultDescription = `Необходимо проанализировать ошибки, найденные SonarQube... \n\nПроект SonarQube: ${projectName}\nSeverity: ${f.sev || 'ALL'} / ${f.qual || 'ALL'}\nВсего issues (по фильтру): ${isAll ? 'весь список' : keys.length}\n\nПолный отчёт — в ZIP-архиве, прикреплённом к задаче.\nСсылка на скачивание — в комментарии ниже.`;

    const renderOpts = (arr, selectedVal, valueKey, labelKey, emptyLabel) => {
        let html = emptyLabel ? `<option value="">${esc(emptyLabel)}</option>` : '';
        if (arr && arr.length > 0) {
            html += arr.map(item => {
                const val = item[valueKey];
                const label = item[labelKey] || val;
                const isSel = String(val).toLowerCase() === String(selectedVal).toLowerCase() ? 'selected' : '';
                return `<option value="${esc(val)}" ${isSel}>${esc(label)}</option>`;
            }).join('');
        } else {
            html += `<option value="${esc(selectedVal || '')}">${esc(selectedVal || '—')}</option>`;
        }
        return html;
    };

    const inputStyle = "width:100%; padding:6px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:4px; font-size:12px;";

    let assignees = cfg.assignees || [];
    let currentUserObj = assignees.find(u => u.name === cfg.username);

    if (!currentUserObj) {
        currentUserObj = {
            name: cfg.username,
            displayName: (cfg.userDisplayName || cfg.username) + " (вы)"
        };
        assignees.unshift(currentUserObj);
    } else {
        currentUserObj.displayName = currentUserObj.displayName + " (вы)";
    }

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.style.zIndex = '2000';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 850px;">
            <div class="modal-header">
                <h3 style="margin:0;">Создать задачу в Jira</h3>
                <button class="modal-close" type="button">&times;</button>
            </div>
            <div class="modal-body">

                ${errorBanner}

                <div style="margin-bottom: 12px; font-size: 12px; color: var(--text-dim); background: var(--bg3); border: 1px solid var(--border); border-radius: 6px; padding: 8px 12px; display: flex; align-items: center; gap: 8px;">
                    <span>👤 Задача будет создана от имени:</span>
                    <b style="color: var(--accent);">${esc(cfg.userDisplayName || cfg.username || 'Неизвестный пользователь')}</b>
                </div>

                <div style="display:grid; grid-template-columns: 1fr 1fr 1fr; gap:10px; margin-bottom:16px; font-size:12px; background:var(--bg3); border:1px solid var(--border); border-radius:6px; padding:12px;">

                    <label style="display:flex; flex-direction:column; gap:4px;">
                        <span style="color:var(--text-dim);">Проект:</span>
                        <select id="ji-project" style="${inputStyle}">
                            ${renderOpts(cfg.projects, cfg.configProject, 'id', 'name')}
                        </select>
                    </label>

                    <label style="display:flex; flex-direction:column; gap:4px;">
                        <span style="color:var(--text-dim);">Тип задачи:</span>
                        <select id="ji-issueType" style="${inputStyle}">
                            ${renderOpts(cfg.issueTypes, cfg.configIssueType, 'id', 'name')}
                        </select>
                    </label>

                    <label style="display:flex; flex-direction:column; gap:4px;">
                        <span style="color:var(--text-dim);">Ссылка на эпик:</span>
                        <select id="ji-epic" style="${inputStyle}">
                            <option value="">Загрузка...</option>
                        </select>
                    </label>

                    <label style="display:flex; flex-direction:column; gap:4px;">
                        <span style="color:var(--text-dim);">Приоритет:</span>
                        <select id="ji-priority" style="${inputStyle}">
                            ${renderOpts(cfg.priorities, cfg.configPriority, 'name', 'name', '— не задан —')}
                        </select>
                    </label>

                    <label style="display:flex; flex-direction:column; gap:4px;">
                        <span style="color:var(--text-dim);">Уровень безопасности:</span>
                        <select id="ji-security" style="${inputStyle}">
                            ${renderOpts(cfg.securityLevels, cfg.configSecurity, 'id', 'name', '— не задан —')}
                        </select>
                    </label>

                    <label style="display:flex; flex-direction:column; gap:4px;">
                        <span style="color:var(--text-dim);">Команда:</span>
                        <select id="ji-team" style="${inputStyle}">
                            <option value="">Загрузка...</option>
                        </select>
                    </label>

                    <label style="display:flex; flex-direction:column; gap:4px;">
                        <span style="color:var(--text-dim);">Исполнитель:</span>
                        <select id="ji-assignee" style="${inputStyle}">
                            ${renderOpts(assignees, cfg.username, 'name', 'displayName')}
                        </select>
                    </label>

                    <label style="display:flex; flex-direction:column; gap:4px; grid-column: span 2;">
                        <span style="color:var(--text-dim);">Метки:</span>
                        <div id="ji-labels-container" style="display:flex; flex-wrap:wrap; gap:4px; padding:4px; background:var(--bg); border:1px solid var(--border); border-radius:4px; min-height:31px;">
                            <input type="text" id="ji-labels-input" list="ji-labels-datalist" placeholder="Введите метку..." style="flex:1; min-width:100px; background:transparent; border:none; color:var(--text); outline:none; font-size:12px;">
                            <datalist id="ji-labels-datalist">
                                ${(cfg.availableLabels || []).map(l => `<option value="${esc(l)}">`).join('')}
                            </datalist>
                        </div>
                    </label>

                    <label style="display:flex; flex-direction:column; gap:4px;">
                        <span style="color:var(--text-dim);">Компоненты (через запятую):</span>
                        <input type="text" id="ji-components" value="${esc(cfg.configComponents || '')}" style="${inputStyle}">
                    </label>

                </div>

                <div id="ji-form-area">
                    <label style="display:block; margin-bottom:12px;">
                        <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">Заголовок задачи</span>
                        <input type="text" id="ji-summary" value="${esc(defaultSummary)}" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                    </label>
                    <label style="display:block; margin-bottom:12px;">
                        <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">Описание</span>
                        <textarea id="ji-description" rows="5" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; font-family:var(--mono); font-size:12px;">${esc(defaultDescription)}</textarea>
                    </label>

                    <div id="ji-progress-container" style="display:none; margin-top:16px;">
                        <div style="height:8px; background:var(--bg3); border-radius:4px; overflow:hidden; border:1px solid var(--border);">
                            <div id="ji-progress-fill" style="height:100%; width:0%; background:var(--accent); transition:width 0.3s;"></div>
                        </div>
                        <div id="ji-progress-text" style="font-size:11px; color:var(--text-dim); margin-top:4px; text-align:right;">0%</div>
                    </div>
                </div>

                <div id="ji-result" style="display:none;"></div>
                <div id="ji-status" style="font-size:12px; color:var(--text-dim); margin-top:12px; min-height:18px;"></div>

                <div id="ji-actions" style="display:flex; justify-content:flex-end; gap:8px; margin-top:16px;">
                    <button id="ji-cancel" class="btn">Отмена</button>
                    <button id="ji-create" class="btn btn-accent">Создать задачу</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const $ = (id) => overlay.querySelector('#' + id);
    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    $('ji-cancel').addEventListener('click', close);

    const selectedLabels = new Set();
    if (cfg.configLabels) cfg.configLabels.split(',').forEach(l => { if(l.trim()) selectedLabels.add(l.trim()); });

    const renderLabels = () => {
        const container = $('ji-labels-container');
        const input = $('ji-labels-input');
        container.innerHTML = '';

        selectedLabels.forEach(label => {
            const chip = document.createElement('span');
            chip.style.cssText = 'display:inline-flex; align-items:center; gap:4px; background:var(--bg3); border:1px solid var(--border); border-radius:4px; padding:2px 6px; font-size:11px;';
            chip.innerHTML = `${esc(label)} <span style="cursor:pointer; color:var(--err); font-weight:bold;" data-label="${esc(label)}">&times;</span>`;
            chip.querySelector('span').addEventListener('click', (e) => {
                selectedLabels.delete(e.target.dataset.label);
                renderLabels();
            });
            container.appendChild(chip);
        });
        container.appendChild(input);
    };
    renderLabels();

    $('ji-labels-input').addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ',') {
            e.preventDefault();
            const val = e.target.value.trim().replace(/,$/, '');
            if (val) {
                selectedLabels.add(val);
                e.target.value = '';
                renderLabels();
                e.target.focus();
            }
        }
    });

    $('ji-labels-input').addEventListener('input', (e) => {
        if (e.inputType === 'insertReplacementText' || e.inputType === 'insertFromDrop') {
            const val = e.target.value.trim();
            if (val) {
                selectedLabels.add(val);
                e.target.value = '';
                renderLabels();
                e.target.focus();
            }
        }
    });

    (async () => {
        try {
            const res = await ctx.api('/api/jira/teams');
            $('ji-team').innerHTML = renderOpts(res.teams, cfg.configTeam, 'name', 'name');
        } catch (e) {
            $('ji-team').innerHTML = '<option value="">Ошибка загрузки</option>';
        }
    })();

    const loadEpics = async (projectKey) => {
        $('ji-epic').innerHTML = '<option value="">Загрузка...</option>';
        try {
            const res = await ctx.api('/api/jira/epics?project=' + encodeURIComponent(projectKey));
            $('ji-epic').innerHTML = renderOpts(res.epics, cfg.configEpic, 'id', 'name', '— нет эпика —');
        } catch (e) {
            $('ji-epic').innerHTML = '<option value="">Ошибка загрузки</option>';
        }
    };

    loadEpics(cfg.configProject);

    $('ji-project').addEventListener('change', (e) => {
        loadEpics(e.target.value);
    });

    $('ji-create').addEventListener('click', async () => {
        const summary = $('ji-summary').value.trim();
        const description = $('ji-description').value;
        if (!summary) { toast('Укажите заголовок', true); return; }

        const btn = $('ji-create');
        btn.disabled = true;
        btn.textContent = 'Создаю…';
        $('ji-progress-container').style.display = 'block';
        $('ji-status').textContent = 'Формирование ZIP-архива...';

        let progress = 5;
        const progressFill = $('ji-progress-fill');
        const progressText = $('ji-progress-text');

        const timer = setInterval(() => {
            progress += (90 - progress) * 0.1;
            progressFill.style.width = progress + '%';
            progressText.textContent = Math.round(progress) + '%';

            if (progress > 30 && progress < 35) $('ji-status').textContent = 'Создание задачи в Jira...';
            if (progress > 60 && progress < 65) $('ji-status').textContent = 'Загрузка ZIP-архива...';
            if (progress > 85 && progress < 90) $('ji-status').textContent = 'Добавление комментария...';
        }, 500);

        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project.trim());
        p.set('summary', summary);
        p.set('description', description);
        p.set('sev', f.sev || ctx.state.sev || 'ALL');
        p.set('qual', f.qual || ctx.state.qual || 'ALL');
        p.set('statusFilter', f.statusFilter || 'ALL');

        p.set('tags', (f.tags || []).join(','));
        p.set('excludedTags', (f.excludedTags || []).join(','));
        p.set('selectedRules', (f.rules || []).join(','));
        p.set('filterText', f.filterText || '');

        p.set('overrideProject', $('ji-project').value);
        p.set('overrideIssueType', $('ji-issueType').value);
        p.set('overrideEpic', $('ji-epic').value);
        p.set('overridePriority', $('ji-priority').value);
        p.set('overrideSecurity', $('ji-security').value);
        p.set('overrideTeam', $('ji-team').value);
        p.set('overrideLabels', Array.from(selectedLabels).join(','));
        p.set('overrideComponents', $('ji-components').value);
        p.set('overrideAssignee', $('ji-assignee').value);

        const opts = {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: isAll ? JSON.stringify({}) : JSON.stringify({ keys })
        };

        try {
            const res = await ctx.api('/api/jira/create-issue?' + p.toString(), opts);
            clearInterval(timer);
            $('ji-progress-container').style.display = 'none';
            $('ji-status').textContent = '';
            $('ji-form-area').style.display = 'none';
            $('ji-actions').style.display = 'none';

            const successHtml = `
                <div style="margin-top:16px; padding:16px; background:rgba(63,185,80,0.1); border:1px solid var(--ok); border-radius:6px;">
                    <div style="font-size:15px; font-weight:bold; color:var(--ok); margin-bottom:12px;">✓ Задача успешно создана</div>
                    <div style="margin-bottom:8px; font-size:14px;">
                        <span style="color:var(--text-dim);">Номер задачи:</span>
                        <b style="font-size:18px; color:var(--text);">${esc(res.issueKey)}</b>
                    </div>
                    <div style="margin-bottom:16px; font-size:13px;">
                        <span style="color:var(--text-dim);">Ссылка:</span>
                        <a href="${esc(res.url)}" target="_blank" style="color:var(--accent); text-decoration:underline;">${esc(res.url)}</a>
                    </div>
                    <div style="display:flex; gap:8px; flex-wrap:wrap;">
                        <a href="${esc(res.url)}" target="_blank" class="btn btn-accent" style="text-decoration:none; padding:8px 16px; display:inline-flex; align-items:center;">Открыть в Jira</a>
                        <button id="ji-copy-link" class="btn">📋 Копировать ссылку</button>
                        <button id="ji-close-success" class="btn">Закрыть</button>
                    </div>
                </div>
            `;

            $('ji-result').innerHTML = successHtml;
            $('ji-result').style.display = 'block';

            $('ji-copy-link').addEventListener('click', () => {
                navigator.clipboard.writeText(res.url);
                toast('Ссылка скопирована в буфер обмена');
            });

            $('ji-close-success').addEventListener('click', close);
            toast(`Создана задача ${res.issueKey}`);

        } catch (e) {
            clearInterval(timer);
            $('ji-status').innerHTML = `<span style="color:var(--err);">✗ ${esc(e.message)}</span>`;
            toast('Ошибка: ' + e.message, true);
            btn.disabled = false;
            btn.textContent = 'Создать задачу';
        }
    });
}