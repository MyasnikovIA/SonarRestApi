import { toast, fetchAndShowPrompt, showTagEditor } from '../../common.js';
import { getReopenTransition } from './status.js';
import { openIssueModal } from './modals/issue-modal.js';
import { openBulkModal } from './modals/bulk-modal.js';
import { openAutoBulkModal } from './modals/auto-bulk-modal.js';

export function showIssueContextMenu(x, y, clickedIssue, targetIssues, filteredData, ctx, reloadCb, onSelectAll, onDeselectAll, isAllSelected = false, totalCount = 0) {
    document.querySelector('.ctx-menu')?.remove();

    // Если выбрано ВСЁ, то количество = totalCount (848)
    // Иначе - размер массива targetIssues (может быть 1 или N)
    const displayCount = isAllSelected ? totalCount : targetIssues.length;

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
        ${hasUnmarked ? `<div class="ctx-item" style="border-top:1px solid var(--border); margin-top:4px; padding-top:8px;" id="ctx-comment">Добавить комментарий (${displayCount})</div>` : ''}
        ${hasUnmarked ? `<div class="ctx-item" id="ctx-auto-comment">Добавить автоматически комментарий (${displayCount})</div>` : ''}
        ${hasMarked ? `<div class="ctx-item" id="ctx-remove">Отменить комментарий (Reopen) (${displayCount})</div>` : ''}
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
            // Если выбрано всё, targetIssues содержит только текущую страницу.
            // Для корректной массовой обработки мы передаем targetIssues,
            // а логика загрузки всех 848 записей будет в bulk-modal, если нужно.
            // Либо, если isAllSelected, мы можем передать пустой массив и флаг,
            // но проще передать то что есть, а модалка сама догрузит.
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