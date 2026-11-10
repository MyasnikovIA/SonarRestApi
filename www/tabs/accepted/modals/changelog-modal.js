import { esc } from '../../../common.js';

export function showChangelogModal(issues, ctx) {
    if (!issues.length) return;
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 600px;">
            <div class="modal-header">
                <h3 style="margin:0;">История изменений</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body" id="changelog-body">
                <div class="empty">Загрузка...</div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    Promise.all(issues.map(it =>
        ctx.api('/api/changelog?issueKey=' + encodeURIComponent(it.key))
           .catch(() => ({ changelog: [] }))
    )).then(results => {
        let allLogs = [];
        results.forEach((r, idx) => {
            const it = issues[idx];
            const logs = r.changelog || [];
            logs.forEach(c => {
                allLogs.push({ ...c, issueKey: it.key });
            });
        });

        allLogs.sort((a, b) => new Date(b.creationDate) - new Date(a.creationDate));

        const body = overlay.querySelector('#changelog-body');
        if (!allLogs.length) {
            body.innerHTML = '<div class="empty">Нет истории изменений</div>';
            return;
        }

        body.innerHTML = `
            <div style="background:var(--bg3); border:1px solid var(--border); border-radius:6px; padding:12px; max-height:500px; overflow-y:auto; font-size:12px;">
                ${allLogs.map(c => `
                    <div style="margin-bottom:12px; padding-bottom:12px; border-bottom:1px solid var(--border);">
                        <div style="color:var(--accent); font-weight:bold; margin-bottom:4px;">
                            ${esc(c.userName || c.user)} <span style="color:var(--text-dim); font-weight:normal;">(${new Date(c.creationDate).toLocaleString()})</span>
                            <span style="color:var(--text-dim); font-weight:normal; margin-left:8px;">[${esc(c.issueKey)}]</span>
                        </div>
                        <div>
                            ${c.diffs.map(diff => `<div><span style="color:var(--text-dim)">${esc(diff.key)}:</span> ${esc(diff.oldValue || '—')} &rarr; <b>${esc(diff.newValue)}</b></div>`).join('')}
                        </div>
                    </div>
                `).join('')}
            </div>
        `;
    }).catch(e => {
        overlay.querySelector('#changelog-body').innerHTML = `<div class="sev-HIGH">Ошибка: ${esc(e.message)}</div>`;
    });
}