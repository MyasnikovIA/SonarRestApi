import { toast, showTagEditor } from '../../common.js';
import { getReopenTransition, runBulkWithProgress } from './helpers.js';
import { showChangeStatusModal } from './modals/change-status-modal.js';
import { showChangelogModal } from './modals/changelog-modal.js';
import { openBulkModal } from './modals/bulk-modal.js';

export function showContextMenu(x, y, clickedIssue, targetIssues, ctx, reloadCb) {
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

    // 🔓 Снять пометку (Reopen) — с прогресс-модалкой
    menu.querySelector('#ctx-reopen').addEventListener('click', async () => {
        if (!confirm(`Снять пометку (reopen) у ${targetIssues.length} issues?`)) return;

        await runBulkWithProgress({
            title: 'Снятие пометок (Reopen)',
            issues: targetIssues,
            perItemAction: async (it, _i, signal) => {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', getReopenTransition(it.status));
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST', signal });
                if (res.success === false) throw new Error(res.error || 'Не удалось сбросить');
            }
        });

        ctx.refreshStatus?.();
        reloadCb();
    });

    // ❌ Переоткрытое замечание — с прогресс-модалкой
    menu.querySelector('#ctx-reopened').addEventListener('click', async () => {
        if (!confirm(`Установить статус "Переоткрытое замечание" у ${targetIssues.length} issues?`)) return;

        await runBulkWithProgress({
            title: 'Переоткрытие замечаний',
            issues: targetIssues,
            perItemAction: async (it, _i, signal) => {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', 'reopen');
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST', signal });
                if (res.success === false) throw new Error(res.error || 'Не удалось открыть');
            }
        });

        ctx.refreshStatus?.();
        reloadCb();
    });
}