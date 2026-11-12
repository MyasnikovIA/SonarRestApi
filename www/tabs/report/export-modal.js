import { toast } from '../../common.js';
import { exportJira } from './exporters/exportJira.js';
import { exportMd } from './exporters/exportMd.js';
import { exportTxt } from './exporters/exportTxt.js';
import { exportConfluence } from './exporters/exportConfluence.js';
import { exportHtmlZip } from './exporters/exportHtmlZip.js';

export async function showExportModal(issues, formatType, ctx, projectName) {
    if (!issues.length) return;

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.style.zIndex = '2000';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 520px;">
            <div class="modal-header">
                <h3 style="margin:0;">Генерация отчета (${formatType === 'confluence' ? 'Confluence' : (formatType === 'html-files' ? 'HTML в ZIP' : formatType.toUpperCase())})</h3>
            </div>
            <div class="modal-body">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; font-size:12px;">
                    <span>Прогресс: <b id="exp-counter">0 / ${issues.length}</b></span>
                    <span id="exp-status" style="color:var(--text-dim);">подготовка...</span>
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

    const counterEl = overlay.querySelector('#exp-counter');
    const statusEl = overlay.querySelector('#exp-status');
    const fillEl = overlay.querySelector('#exp-fill');
    const currentEl = overlay.querySelector('#exp-current');
    const cancelBtn = overlay.querySelector('#exp-cancel');

    let cancelled = false;
    const controller = new AbortController();

    cancelBtn.addEventListener('click', () => {
        cancelled = true;
        controller.abort();
        statusEl.textContent = 'Остановка...';
    });

    const total = issues.length;

    let host = 'sonar.bars.group';
    try {
        const status = await ctx.api('/api/status');
        if (status.session && status.session.host) {
            host = status.session.host;
        }
    } catch (e) {
        console.warn('Не удалось получить host для ссылок', e);
    }

    // ЭТАП 1: Загрузка всех сниппетов (общий этап для всех форматов)
    for (let i = 0; i < total; i++) {
        if (cancelled) break;
        const it = issues[i];

        counterEl.textContent = `${i + 1} / ${total}`;
        currentEl.textContent = it.key + ' — ' + it.component;
        statusEl.textContent = 'загрузка кода...';
        fillEl.style.width = `${Math.round(((i) / total) * 100)}%`;

        if (!it._snippet) {
            try {
                const p = new URLSearchParams({ project: ctx.state.project, issueKey: it.key });
                const d = await ctx.api('/api/snippet?' + p, { signal: controller.signal });
                it._snippet = d.snippet || [];
                if (d.issue && d.issue.comments) {
                    it.comments = d.issue.comments;
                }
            } catch (e) {
                if (e.name === 'AbortError') break;
                console.warn('Failed to fetch snippet for ' + it.key, e);
                it._snippet = [];
            }
        }
    }

    // ЭТАП 2: Генерация и скачивание
    if (!cancelled) {
        fillEl.style.width = '100%';
        statusEl.textContent = 'формирование файлов...';
        currentEl.textContent = 'Пожалуйста, подождите...';

        try {
            if (formatType === 'jira') {
                await exportJira(issues, projectName, host);
            } else if (formatType === 'md') {
                await exportMd(issues, projectName, host);
            } else if (formatType === 'txt') {
                await exportTxt(issues, projectName, host);
            } else if (formatType === 'confluence') {
                await exportConfluence(issues, projectName, host);
            } else if (formatType === 'html-files') {
                statusEl.textContent = 'упаковка в ZIP-архив...';
                await exportHtmlZip(issues, projectName, host);
            }
            statusEl.textContent = 'Готово! Файл успешно скачан.';
        } catch (err) {
            console.error('Ошибка экспорта:', err);
            statusEl.textContent = 'Ошибка экспорта';
            toast('Не удалось создать файл: ' + err.message, true);
        }
    } else {
        statusEl.textContent = 'Отменено пользователем';
    }

    cancelBtn.textContent = 'Закрыть';
    cancelBtn.classList.remove('btn-danger');
    cancelBtn.classList.add('btn-accent');
    cancelBtn.replaceWith(cancelBtn.cloneNode(true));
    overlay.querySelector('#exp-cancel').addEventListener('click', () => overlay.remove());
}