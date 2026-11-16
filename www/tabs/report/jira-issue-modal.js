import { esc, toast } from '../../common.js';

export async function showJiraIssueModal(keys, ctx, projectName, filters) {
    let cfg = {};
    try { cfg = await ctx.api('/api/jira/config'); } catch (e) { console.warn(e); }

    const f = filters || {};
    const isAll = (keys === null);

    const defaultSummary = `${projectName}: Необходимо исправить ошибки, найденные SonarQube`;
    const defaultDescription = `Необходимо проанализировать ошибки, найденные SonarQube... \n\nПроект SonarQube: ${projectName}\nSeverity: ${f.sev || 'ALL'} / ${f.qual || 'ALL'}\nВсего issues (по фильтру): ${isAll ? 'весь список' : keys.length}\n\nПолный отчёт — в ZIP-архиве, прикреплённом к задаче.\nСсылка на скачивание — в комментарии ниже.`;

    const summaryRows = [
        ['Проект', cfg.project], ['Тип задачи', cfg.issueType],
        ['Приоритет', cfg.priority], ['Команда', cfg.team], ['Исполнитель', cfg.assignee]
    ].filter(([, v]) => v);

    const summaryHtml = summaryRows.map(([k, v]) =>
        `<div><span style="color:var(--text-dim); display:inline-block; min-width:130px;">${esc(k)}:</span> <b>${esc(String(v))}</b></div>`
    ).join('');

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.style.zIndex = '2000';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 620px;">
            <div class="modal-header">
                <h3 style="margin:0;">Создать задачу в Jira</h3>
                <button class="modal-close" type="button">&times;</button>
            </div>
            <div class="modal-body">
                <div style="margin-bottom:16px; font-size:12px; background:var(--bg3); border:1px solid var(--border); border-radius:6px; padding:10px 12px;">
                    ${summaryHtml}
                </div>

                <div id="ji-form-area">
                    <label style="display:block; margin-bottom:12px;">
                        <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">Заголовок задачи</span>
                        <input type="text" id="ji-summary" value="${esc(defaultSummary)}" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                    </label>
                    <label style="display:block; margin-bottom:12px;">
                        <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">Описание</span>
                        <textarea id="ji-description" rows="7" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">${esc(defaultDescription)}</textarea>
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

    $('ji-create').addEventListener('click', async () => {
        const summary = $('ji-summary').value.trim();
        const description = $('ji-description').value;
        if (!summary) { toast('Укажите заголовок', true); return; }

        const btn = $('ji-create');
        btn.disabled = true;
        btn.textContent = 'Создаю…';
        $('ji-progress-container').style.display = 'block';
        $('ji-status').textContent = 'Формирование ZIP-архива...';

        // ============================================================
        // ЛОГ В КОНСОЛЬ БРАУЗЕРА: СТАРТ
        // ============================================================
        console.log('%c━━━ СОЗДАНИЕ ЗАДАЧИ В JIRA ━━━', 'color:#58a6ff; font-weight:bold; font-size:14px;');
        console.log('[Jira] URL       :', cfg.url);
        console.log('[Jira] Проект    :', cfg.project);
        console.log('[Jira] Тип       :', cfg.issueType);
        console.log('[Jira] Sonar     :', projectName, '(sev=' + (f.sev || ctx.state.sev) + ', qual=' + (f.qual || ctx.state.qual) + ')');
        console.log('[Jira] Issues    :', isAll ? 'все по фильтру' : keys.length);
        console.log('[Jira] Summary   :', summary);

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

        const opts = {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: isAll ? JSON.stringify({}) : JSON.stringify({ keys })
        };

        try {
            const res = await ctx.api('/api/jira/create-issue?' + p.toString(), opts);

            // Останавливаем прогресс
            clearInterval(timer);
            $('ji-progress-container').style.display = 'none';
            $('ji-status').textContent = '';
            $('ji-form-area').style.display = 'none';
            $('ji-actions').style.display = 'none';

            // ============================================================
            // ЛОГ В КОНСОЛЬ БРАУЗЕРА: УСПЕХ
            // ============================================================
            console.log('%c━━━ ✓ ЗАДАЧА СОЗДАНА ━━━', 'color:#3fb950; font-weight:bold; font-size:14px;');
            console.log('%c НОМЕР ЗАДАЧИ: ' + res.issueKey, 'color:#3fb950; font-weight:bold; font-size:16px; background:#0d1117; padding:4px 8px;');
            console.log('%c ССЫЛКА: ' + res.url, 'color:#58a6ff;');
            console.log('[Jira] ID задачи :', res.issueId);
            console.log('[Jira] Исполнитель:', res.assignee);
            console.log('[Jira] Issues     :', res.issuesCount);
            console.log('[Jira] Файлов     :', res.filesCount);
            console.log('[Jira] Вложений   :', res.attachmentsCount);
            if (res.attachments && res.attachments.length > 0) {
                console.log('[Jira] Вложения:');
                res.attachments.forEach(a => {
                    console.log('  → ' + a.filename + ' — ' + a.url);
                });
            }
            console.log('%c━━━━━━━━━━━━━━━━━━━━━━━━━━', 'color:#3fb950;');

            // Копируем ссылку в консоль, чтобы легко было перейти
            console.info('Чтобы открыть задачу — выполните в консоли: window.open("' + res.url + '")');

            // Показываем красивый блок с результатом
            const successHtml = `
                <div style="margin-top:16px; padding:16px; background:rgba(63,185,80,0.1); border:1px solid var(--ok); border-radius:6px;">
                    <div style="font-size:15px; font-weight:bold; color:var(--ok); margin-bottom:12px;">
                        ✓ Задача успешно создана
                    </div>
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
            console.error('%c✗ ОШИБКА СОЗДАНИЯ ЗАДАЧИ', 'color:#f85149; font-weight:bold; font-size:14px;');
            console.error('[Jira] Сообщение:', e.message);
            $('ji-status').innerHTML = `<span style="color:var(--err);">✗ ${esc(e.message)}</span>`;
            toast('Ошибка: ' + e.message, true);
            btn.disabled = false;
            btn.textContent = 'Создать задачу';
        }
    });
}