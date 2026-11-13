import { toast } from '../../common.js';

export async function showExportModal(filters, formatType, ctx, projectName) {
    // filters содержит: sev, qual, rule, hideProcessed, selectedTags, excludedTags, selectedRules, filterText, statusFilter

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.style.zIndex = '2000';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 520px;">
            <div class="modal-header">
                <h3 style="margin:0;">Генерация отчета на сервере</h3>
            </div>
            <div class="modal-body">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; font-size:12px;">
                    <span>Статус: <b id="exp-status">Инициализация...</b></span>
                    <span id="exp-counter">0 / 0</span>
                </div>
                <div style="height:8px; background:var(--bg3); border-radius:4px; overflow:hidden; border:1px solid var(--border);">
                    <div id="exp-fill" style="height:100%; width:0%; background:var(--accent); transition: width 0.15s;"></div>
                </div>
                <div id="exp-current" style="margin-top:10px; font-size:12px; color:var(--text-dim); white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">—</div>
                <div style="margin-top:16px; display:flex; justify-content:flex-end; gap:8px;">
                    <button id="exp-cancel" class="btn btn-danger" type="button">Остановить</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const statusEl = overlay.querySelector('#exp-status');
    const counterEl = overlay.querySelector('#exp-counter');
    const fillEl = overlay.querySelector('#exp-fill');
    const currentEl = overlay.querySelector('#exp-current');
    const cancelBtn = overlay.querySelector('#exp-cancel');

    let eventSource = null;

    const close = () => {
        if (eventSource) {
            eventSource.close();
            eventSource = null;
        }
        overlay.remove();
    };

    cancelBtn.addEventListener('click', () => {
        if (eventSource) {
            statusEl.textContent = 'Остановка...';
            eventSource.close();
            eventSource = null;
        }
        close();
        toast('Генерация остановлена', true);
    });

    const p = new URLSearchParams();
    if (ctx.state.project) p.set('project', ctx.state.project.trim());
    // Используем ТОЛЬКО те фильтры, которые пришли из вкладки "Отчет"
    p.set('sev', filters.sev);
    p.set('qual', filters.qual);
    p.set('format', formatType);
    if (filters.rule) p.set('rule', filters.rule);
    if (filters.hideProcessed) p.set('hideProcessed', 'true');
    if (filters.selectedTags && filters.selectedTags.length > 0) p.set('tags', filters.selectedTags.join(','));
    if (filters.excludedTags && filters.excludedTags.length > 0) p.set('excludedTags', filters.excludedTags.join(','));
    if (filters.selectedRules && filters.selectedRules.length > 0) p.set('selectedRules', filters.selectedRules.join(','));
    if (filters.filterText) p.set('filterText', filters.filterText);
    if (filters.statusFilter) p.set('statusFilter', filters.statusFilter);

    const sseUrl = '/api/export-stream?' + p.toString();
    console.log('[SSE] Подключение к', sseUrl);

    eventSource = new EventSource(sseUrl);

    eventSource.addEventListener('progress', (e) => {
        const data = JSON.parse(e.data);
        statusEl.textContent = data.message || 'Загрузка...';
        if (data.total) {
            counterEl.textContent = `${data.current} / ${data.total}`;
            const pct = Math.min(100, Math.round((data.current / data.total) * 100));
            fillEl.style.width = pct + '%';
        }
        if (data.page) {
            currentEl.textContent = `Страница ${data.page}...`;
        }
    });

    eventSource.addEventListener('complete', (e) => {
        const data = JSON.parse(e.data);
        statusEl.textContent = 'Готово! Скачивание файла...';
        fillEl.style.width = '100%';

        // Скачиваем файл по ссылке
        const downloadUrl = '/api/export-download?file=' + encodeURIComponent(data.file);

        const a = document.createElement('a');
        a.href = downloadUrl;
        a.download = '';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);

        toast('Отчет успешно сформирован и скачан!');
        setTimeout(() => close(), 2000);
    });

    eventSource.addEventListener('error', (e) => {
        let msg = 'Ошибка генерации отчета';
        try {
            const data = JSON.parse(e.data);
            msg = data.message || msg;
        } catch (err) { }
        statusEl.textContent = msg;
        statusEl.style.color = 'var(--err)';
        toast(msg, true);
        if (eventSource) {
            eventSource.close();
            eventSource = null;
        }
    });

    eventSource.onerror = (e) => {
        if (eventSource && eventSource.readyState === EventSource.CLOSED) {
            statusEl.textContent = 'Соединение с сервером закрыто.';
            statusEl.style.color = 'var(--err)';
        }
    };
}