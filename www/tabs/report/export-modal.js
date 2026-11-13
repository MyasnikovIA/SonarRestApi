import { toast } from '../../common.js';

/**
 * Скачивает файл по URL через fetch+blob.
 * Устойчиво к mixed-content и к cross-origin <a download>.
 */
async function triggerDownload(filePath) {
    const downloadUrl = '/api/export-download?file=' + encodeURIComponent(filePath);
    try {
        const response = await fetch(downloadUrl, { method: 'GET' });
        if (!response.ok) {
            const txt = await response.text();
            throw new Error(`HTTP ${response.status}: ${txt.slice(0, 200)}`);
        }
        const blob = await response.blob();
        const blobUrl = URL.createObjectURL(blob);

        let filename = (filePath.split(/[\\/]/).pop() || 'report');
        const cd = response.headers.get('Content-Disposition');
        if (cd) {
            const m = cd.match(/filename="?([^";]+)"?/);
            if (m) filename = m[1];
        }

        const a = document.createElement('a');
        a.href = blobUrl;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(blobUrl), 5000);
    } catch (e) {
        console.error('[SSE] Ошибка скачивания:', e);
        toast('Ошибка скачивания: ' + e.message, true);
    }
}

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
                    <span id="exp-status" style="color:var(--text-dim);">Инициализация…</span>
                    <span id="exp-counter" style="font-family:var(--mono); font-weight:bold;">0 / 0</span>
                </div>
                <div style="display:flex; justify-content:space-between; align-items:center; margin-top:6px; margin-bottom:10px; font-size:11px;">
                    <span id="exp-stage" style="color:var(--text-dim);">—</span>
                    <span style="color:var(--text-dim);">Прошло: <b id="exp-elapsed" style="font-family:var(--mono); color:var(--accent);">0.0 с</b></span>
                </div>
                <div style="height:8px; background:var(--bg3); border-radius:4px; overflow:hidden; border:1px solid var(--border);">
                    <div id="exp-fill" style="height:100%; width:0%; background:var(--accent); transition: width 0.15s;"></div>
                </div>
                <div style="margin-top:16px; display:flex; justify-content:flex-end; gap:8px;">
                    <button id="exp-cancel" class="btn btn-danger" type="button">Остановить</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const statusEl  = overlay.querySelector('#exp-status');
    const counterEl = overlay.querySelector('#exp-counter');
    const stageEl   = overlay.querySelector('#exp-stage');
    const elapsedEl = overlay.querySelector('#exp-elapsed');
    const fillEl    = overlay.querySelector('#exp-fill');
    const cancelBtn = overlay.querySelector('#exp-cancel');

    let abortController = null;
    let elapsedTimer    = null;
    let cancelledByUser = false;
    const startMs = performance.now();

    elapsedTimer = setInterval(() => {
        elapsedEl.textContent = `${((performance.now() - startMs) / 1000).toFixed(1)} с`;
    }, 200);

    const stopTimer = () => { if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; } };
    const close = () => {
        stopTimer();
        if (abortController) { abortController.abort(); abortController = null; }
        overlay.remove();
    };

    cancelBtn.addEventListener('click', () => {
        cancelledByUser = true;
        const elapsedMs = performance.now() - startMs;
        console.log(`[SSE] ⏹ Прервано пользователем через ${(elapsedMs / 1000).toFixed(2)} с`);
        if (abortController) {
            statusEl.textContent = `Остановка… (${(elapsedMs / 1000).toFixed(2)} с)`;
            abortController.abort();
            abortController = null;
        }
        stopTimer();
        close();
        toast(`Генерация остановлена (${(elapsedMs / 1000).toFixed(2)} с)`, true);
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
    console.log(`[SSE] ▶ СТАРТ: format=${formatType}, `
        + `${isAllSelected ? 'ALL (по фильтрам)' : (keys.length + ' ключей')}, `
        + `project=${ctx.state.project || '?'}`);

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

        const reader  = response.body.getReader();
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
                    if (d.message) statusEl.textContent = d.message;
                    if (d.stage) stageEl.textContent = `Этап: ${d.stage}`;
                    if (d.total) {
                        counterEl.textContent = `${d.current} / ${d.total}`;
                        fillEl.style.width = Math.min(100, Math.round((d.current / d.total) * 100)) + '%';
                    }
                    if (typeof d.elapsedMs === 'number' && d.elapsedMs > 0) {
                        elapsedEl.textContent = `${(d.elapsedMs / 1000).toFixed(1)} с`;
                    }
                } else if (event === 'complete') {
                    const d = JSON.parse(data);
                    stopTimer();

                    const elapsedMs = performance.now() - startMs;
                    console.log(`[SSE] ✅ ГОТОВО за ${(elapsedMs / 1000).toFixed(2)} с`
                        + ` | сервер: ${d.elapsedText || '—'}`
                        + ` | файл: ${d.file}`
                        + ` | issues: ${d.total}`);

                    statusEl.textContent = `Готово за ${(elapsedMs / 1000).toFixed(2)} с — скачивание…`;
                    elapsedEl.textContent = `${(elapsedMs / 1000).toFixed(1)} с`;
                    stageEl.textContent = 'Завершено';
                    fillEl.style.width = '100%';

                    await triggerDownload(d.file);

                    toast(`Отчет готов за ${(elapsedMs / 1000).toFixed(2)} с — скачивание...`);
                    setTimeout(() => close(), 2000);
                } else if (event === 'error') {
                    const d = JSON.parse(data);
                    throw new Error(d.message || 'Ошибка генерации');
                }
            }
        }
    } catch (err) {
        stopTimer();
        const elapsedMs = performance.now() - startMs;
        if (cancelledByUser) { /* залогировано */ }
        else if (err.name === 'AbortError') {
            console.log(`[SSE] ⏹ Прервано через ${(elapsedMs / 1000).toFixed(2)} с (abort)`);
        } else {
            console.error(`[SSE] ❌ ОШИБКА через ${(elapsedMs / 1000).toFixed(2)} с:`, err);
            statusEl.textContent = `Ошибка за ${(elapsedMs / 1000).toFixed(2)} с: ${err.message}`;
            statusEl.style.color = 'var(--err)';
            toast(`Ошибка генерации (${(elapsedMs / 1000).toFixed(2)} с): ${err.message}`, true);
        }
    } finally {
        stopTimer();
        abortController = null;
    }
}