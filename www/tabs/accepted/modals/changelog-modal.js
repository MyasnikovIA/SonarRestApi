import { esc } from '../../../common.js';

// ---- Человекочитаемые названия полей SonarQube ----
const FIELD_LABELS = {
    'status':     'Статус',
    'resolution': 'Резолюция',
    'assignee':   'Исполнитель',
    'tags':       'Теги',
    'severity':   'Критичность',
    'type':       'Тип',
    'file':       'Файл',
    'rule':       'Правило',
    'comment':    'Комментарий',
};

function fieldLabel(key) {
    return FIELD_LABELS[key] || key;
}

function formatDate(iso) {
    if (!iso) return '';
    try {
        return new Date(iso).toLocaleString('ru-RU');
    } catch {
        return iso;
    }
}

// ---- Рендер одного события ----
function renderEvent(e) {
    const dateStr = formatDate(e.date);

    // ---- Комментарий ----
    if (e.type === 'comment') {
        return `
            <div style="margin-bottom:12px; padding-bottom:12px; border-bottom:1px solid var(--border);">
                <div style="margin-bottom:6px; display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                    <span style="font-size:14px;">💬</span>
                    <span style="color:var(--accent); font-weight:bold;">${esc(e.userName)}</span>
                    <span style="color:var(--text-dim); font-weight:normal;">${esc(dateStr)}</span>
                    <span style="color:var(--text-dim); font-weight:normal; margin-left:auto; font-size:11px;">
                        [${esc(e.issueKey)}]
                    </span>
                </div>
                <div style="background:var(--bg); border-left:3px solid var(--accent);
                            padding:8px 10px; border-radius:0 4px 4px 0;
                            white-space:pre-wrap; color:var(--text);">
                    ${esc(e.text)}
                </div>
            </div>
        `;
    }

    // ---- Изменение полей ----
    const hasStatusChange = e.diffs.some(d => d.key === 'status');
    const icon = hasStatusChange ? '🔄' : '✏️';

    const diffsHtml = e.diffs.map(diff => {
        const label = fieldLabel(diff.key);
        const oldVal = diff.oldValue || '—';
        const newVal = diff.newValue || '—';
        return `
            <div style="margin:3px 0 3px 22px;">
                <span style="color:var(--text-dim);">${esc(label)}:</span>
                <span style="color:var(--text-dim); text-decoration:line-through;">${esc(oldVal)}</span>
                &rarr;
                <b style="color:var(--text);">${esc(newVal)}</b>
            </div>
        `;
    }).join('');

    return `
        <div style="margin-bottom:12px; padding-bottom:12px; border-bottom:1px solid var(--border);">
            <div style="margin-bottom:6px; display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                <span style="font-size:14px;">${icon}</span>
                <span style="color:var(--accent); font-weight:bold;">${esc(e.userName)}</span>
                <span style="color:var(--text-dim); font-weight:normal;">${esc(dateStr)}</span>
                <span style="color:var(--text-dim); font-weight:normal; margin-left:auto; font-size:11px;">
                    [${esc(e.issueKey)}]
                </span>
            </div>
            ${diffsHtml}
        </div>
    `;
}

// ---- Модалка ----
export function showChangelogModal(issues, ctx) {
    if (!issues.length) return;
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 700px;">
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

    // Параллельно: changelog + snippet (для comments) по каждому issue
    Promise.all(issues.map(it => {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        p.set('issueKey', it.key);
        return Promise.all([
            ctx.api('/api/changelog?' + p).catch(() => ({ changelog: [] })),
            ctx.api('/api/snippet?' + p).catch(() => ({ issue: { comments: [] } }))
        ]);
    })).then(results => {
        const events = [];

        results.forEach(([ch, sn], idx) => {
            const issueKey = issues[idx].key;
            const changelog = ch.changelog || [];
            const comments = (sn.issue && sn.issue.comments) || [];

            // 1) Изменения полей (status, resolution, tags, assignee...)
            changelog.forEach(entry => {
                events.push({
                    type: 'change',
                    issueKey,
                    user: entry.user,
                    userName: entry.userName || entry.user || 'Пользователь',
                    date: entry.creationDate,
                    diffs: entry.diffs || []
                });
            });

            // 2) Комментарии
            comments.forEach(c => {
                events.push({
                    type: 'comment',
                    issueKey,
                    user: c.login,
                    userName: c.login || 'Пользователь',
                    date: c.createdAt,
                    text: c.markdown || c.htmlText || ''
                });
            });
        });

        // Сортировка по убыванию даты (свежие сверху)
        events.sort((a, b) => {
            const da = a.date ? new Date(a.date).getTime() : 0;
            const db = b.date ? new Date(b.date).getTime() : 0;
            return db - da;
        });

        const body = overlay.querySelector('#changelog-body');
        if (!events.length) {
            body.innerHTML = '<div class="empty">Нет истории изменений</div>';
            return;
        }

        // Обновляем заголовок — показываем количество событий
        overlay.querySelector('.modal-header h3').textContent =
            `История изменений (${events.length})`;

        body.innerHTML = `
            <div style="background:var(--bg3); border:1px solid var(--border); border-radius:6px;
                        padding:12px; max-height:550px; overflow-y:auto; font-size:12px;">
                ${events.map(renderEvent).join('')}
            </div>
        `;
    }).catch(e => {
        overlay.querySelector('#changelog-body').innerHTML =
            `<div class="sev-HIGH">Ошибка: ${esc(e.message)}</div>`;
    });
}