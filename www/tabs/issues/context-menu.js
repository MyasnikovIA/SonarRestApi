import { toast, fetchAndShowPrompt, showTagEditor } from '../../common.js';
import { getReopenTransition } from './status.js';
import { openIssueModal } from './modals/issue-modal.js';
import { openBulkModal } from './modals/bulk-modal.js';
import { openAutoBulkModal } from './modals/auto-bulk-modal.js';

export function showIssueContextMenu(x, y, clickedIssue, targetIssues, filteredData, ctx, reloadCb, onSelectAll, onDeselectAll) {
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