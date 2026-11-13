import { toast } from '../../common.js';

export async function showExportModal(keys, formatType, ctx, projectName, filters) {
    const isAllSelected = (keys === null);
    const f = filters || {};

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

    let abortController = null;

    const close = () => {
        if (abortController) {
            abortController.abort();
            abortController = null;
        }
        overlay.remove();
    };

    cancelBtn.addEventListener('click', () => {
        if (abortController) {
            statusEl.textContent = 'Остановка...';
            abortController.abort();
            abortController = null;
        }
        close();
        toast('Генерация остановлена', true);
    });

    const p = new URLSearchParams();
    if (ctx.state.project) p.set('project', ctx.state.project.trim());
    p.set('sev', f.sev || ctx.state.sev);
    p.set('qual', f.qual || ctx.state.qual);
    p.set('format', formatType);
    if (f.tags && f.tags.length > 0) p.set('tags', f.tags.join(','));
    if (f.excludedTags && f.excludedTags.length > 0) p.set('excludedTags', f.excludedTags.join(','));
    if (f.rules && f.rules.length > 0) p.set('selectedRules', f.rules.join(','));
    if (f.filterText) p.set('filterText', f.filterText);
    if (f.statusFilter) p.set('statusFilter', f.statusFilter);

    const url = '/api/export-stream?' + p.toString();
    console.log('[SSE] ' + (isAllSelected ? 'GET (все)' : 'POST (' + keys.length + ' ключей)') + ' на ' + url);

    abortController = new AbortController();

    try {
        let response;
        if (isAllSelected) {
            response = await fetch(url, { method: 'GET', signal: abortController.signal });
        } else {
            response = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ keys: keys }),
                signal: abortController.signal
            });
        }

        if (!response.ok) throw new Error(`HTTP ${response.status}: ${await response.text()}`);

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

                let event = 'message';
                let data = '';
                for (const line of chunk.split('\n')) {
                    if (line.startsWith('event: ')) event = line.substring(7).trim();
                    else if (line.startsWith('data: ')) data += line.substring(6).trim();
                }

                if (event === 'progress') {
                    const d = JSON.parse(data);
                    statusEl.textContent = d.message || 'Загрузка...';
                    if (d.total) {
                        counterEl.textContent = `${d.current} / ${d.total}`;
                        fillEl.style.width = Math.min(100, Math.round((d.current / d.total) * 100)) + '%';
                    }
                    if (d.page) currentEl.textContent = `Страница ${d.page}...`;
                } else if (event === 'complete') {
                    const d = JSON.parse(data);
                    statusEl.textContent = 'Готово! Скачивание файла...';
                    fillEl.style.width = '100%';
                    const downloadUrl = '/api/export-download?file=' + encodeURIComponent(d.file);
                    const a = document.createElement('a');
                    a.href = downloadUrl;
                    a.download = '';
                    document.body.appendChild(a);
                    a.click();
                    document.body.removeChild(a);
                    toast('Отчет успешно сформирован и скачан!');
                    setTimeout(() => close(), 2000);
                } else if (event === 'error') {
                    const d = JSON.parse(data);
                    throw new Error(d.message || 'Ошибка генерации');
                }
            }
        }
    } catch (err) {
        if (err.name === 'AbortError') {
            console.log('[SSE] Прервано пользователем');
        } else {
            console.error('[SSE] Ошибка:', err);
            statusEl.textContent = 'Ошибка: ' + err.message;
            statusEl.style.color = 'var(--err)';
            toast('Ошибка генерации: ' + err.message, true);
        }
    } finally {
        abortController = null;
    }
}