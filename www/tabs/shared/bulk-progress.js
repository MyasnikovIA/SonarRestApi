import { esc } from '../../common.js';

/**
 * Показывает прогресс-бар и последовательно вызывает perItemAction для каждой issue.
 *
 * ВАЖНО: пока процесс не завершён (или не нажата кнопка «Остановить»),
 * окно нельзя закрыть ни Esc, ни кликом снаружи, ни крестиком — крестика нет.
 * Это защищает от случайного скрытия окна во время долгой операции.
 *
 * @param {object} opts
 * @param {string}   opts.title         — заголовок окна
 * @param {Array}    opts.issues        — список issues (должен иметь .key и, опционально, .component)
 * @param {function} opts.perItemAction — async (issue, index, signal) => void;
 *                                        должен бросать Error при ошибке,
 *                                        при abort — пробрасывать AbortError.
 * @returns {Promise<{ success:number, failed:Array<{key,message}>, cancelled:boolean }>}
 */
export async function runBulkWithProgress({ title, issues, perItemAction }) {
    const total = issues.length;
    if (total === 0) return { success: 0, failed: [], cancelled: false };

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.style.zIndex = '2000';   // выше любых других модалок (в CSS .modal-overlay = 1000)
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 520px;">
            <div class="modal-header">
                <h3 style="margin:0;">${esc(title)}</h3>
                <!-- крестика НЕТ намеренно: окно нельзя закрыть до завершения -->
            </div>
            <div class="modal-body">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; font-size:12px;">
                    <span>Прогресс: <b id="bulk-prog-counter">0 / ${total}</b></span>
                    <span id="bulk-prog-status" style="color:var(--text-dim);">выполняется…</span>
                </div>
                <div style="height:8px; background:var(--bg3); border-radius:4px; overflow:hidden; border:1px solid var(--border);">
                    <div id="bulk-prog-fill" style="height:100%; width:0%; background:var(--accent); transition: width 0.15s;"></div>
                </div>
                <div id="bulk-prog-current"
                     style="margin-top:10px; font-size:12px; color:var(--text-dim);
                            white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">—</div>
                <div id="bulk-prog-errors"
                     style="margin-top:8px; font-size:12px; color:var(--sev-HIGH);
                            max-height:120px; overflow-y:auto;"></div>
                <div style="margin-top:16px; display:flex; justify-content:flex-end; gap:8px;">
                    <button id="bulk-prog-cancel" class="btn btn-danger">Остановить</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const fill      = overlay.querySelector('#bulk-prog-fill');
    const counter   = overlay.querySelector('#bulk-prog-counter');
    const statusEl  = overlay.querySelector('#bulk-prog-status');
    const currentEl = overlay.querySelector('#bulk-prog-current');
    const errorsEl  = overlay.querySelector('#bulk-prog-errors');
    const cancelBtn = overlay.querySelector('#bulk-prog-cancel');

    let cancelled = false;
    let success   = 0;
    let lastDone  = 0;
    const failed  = [];
    const abortController = new AbortController();

    // ============================================================
    // Блокируем всё, что могло бы закрыть окно до завершения процесса
    // ============================================================
    let finished = false;
    const blockEsc = (e) => {
        if (!finished && e.key === 'Escape') {
            e.preventDefault();
            e.stopPropagation();
        }
    };
    const blockClick = (e) => {
        if (!finished && e.target === overlay) {
            e.preventDefault();
            e.stopPropagation();
        }
    };
    document.addEventListener('keydown', blockEsc, true);
    overlay.addEventListener('click', blockClick, true);

    const updateProgress = (done) => {
        const pct = total ? Math.round((done / total) * 100) : 0;
        fill.style.width = pct + '%';
        counter.textContent = `${done} / ${total}`;
    };

    const onCancel = () => {
        cancelled = true;
        statusEl.textContent = 'Остановка…';
        cancelBtn.disabled = true;
        abortController.abort();
    };
    cancelBtn.addEventListener('click', onCancel);

    for (let i = 0; i < total; i++) {
        if (cancelled) break;
        const it = issues[i];
        currentEl.textContent = it.key + (it.component ? ' — ' + it.component : '');

        try {
            await perItemAction(it, i, abortController.signal);
            success++;
        } catch (e) {
            if (e && e.name === 'AbortError') {
                cancelled = true;
                break;
            }
            failed.push({ key: it.key, message: e.message });
            const div = document.createElement('div');
            div.textContent = `✗ ${it.key}: ${e.message}`;
            errorsEl.appendChild(div);
        }

        lastDone = i + 1;
        updateProgress(lastDone);
    }

    // ============================================================
    // Процесс завершён — снимаем блокировки и разрешаем закрытие
    // ============================================================
    finished = true;
    document.removeEventListener('keydown', blockEsc, true);
    overlay.removeEventListener('click', blockClick, true);

    const finalDone = cancelled ? lastDone : total;
    updateProgress(finalDone);
    statusEl.textContent = cancelled
        ? `Остановлено: ${success} / ${total}`
        : `Готово: ${success} / ${total}`;
    currentEl.textContent = '—';

    cancelBtn.disabled = false;
    cancelBtn.classList.remove('btn-danger');
    cancelBtn.classList.add('btn-accent');
    cancelBtn.textContent = 'Закрыть';
    cancelBtn.removeEventListener('click', onCancel);
    cancelBtn.addEventListener('click', () => overlay.remove(), { once: true });

    return { success, failed, cancelled };
}