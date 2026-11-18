// ==================== Statistics HTML EXPORT ====================
// Единая таблица параметров в формате "prefix: value" с количеством.

function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

function sanitize(s) {
    if (!s) return 'stats';
    return String(s).replace(/[^A-Za-z0-9._-]/g, '_');
}

function formatNow() {
    return new Date().toLocaleString('ru-RU');
}

/**
 * Возвращает массив строк { param, count } для одной группы (severity/quality/tag).
 * Формат param: "<prefix>: <value>".
 * Сортировка: по убыванию количества, затем по имени.
 */
function buildParamRows(obj, prefix) {
    return Object.entries(obj || {})
        .filter(([, v]) => Number(v) > 0)
        .map(([k, v]) => ({ param: `${prefix}: ${k}`, count: Number(v) }))
        .sort((a, b) => b.count - a.count || a.param.localeCompare(b.param));
}

/**
 * Рендерит объединённую таблицу параметров (sev + qual + tag).
 */
function renderUnifiedBreakdown(p) {
    const rows = [
        ...buildParamRows(p.bySeverity, 'sev'),
        ...buildParamRows(p.byQuality,  'qual'),
        ...buildParamRows(p.byTag,      'tag')
    ];

    if (!rows.length) {
        return `<div class="no-data">— нет данных —</div>`;
    }

    return `
        <table class="breakdown-table">
            <thead>
                <tr>
                    <th>Параметр</th>
                    <th style="text-align:right;">Количество</th>
                </tr>
            </thead>
            <tbody>
                ${rows.map(r => {
                    const [prefix, ...rest] = r.param.split(': ');
                    const value = rest.join(': ');
                    return `
                        <tr>
                            <td>
                                <span class="param-prefix">${esc(prefix)}:</span>
                                <span class="param-value">${esc(value)}</span>
                            </td>
                            <td class="param-count">${r.count}</td>
                        </tr>
                    `;
                }).join('')}
            </tbody>
        </table>
    `;
}

/**
 * Один блок проекта.
 */
function renderProjectBlock(key, p) {
    return `
        <div class="project-block">
            <div class="project-header">
                <span class="project-icon">📁</span>
                <h2>${esc(key)}</h2>
            </div>

            <div class="totals-row">
                <div class="total-badge">
                    <span class="total-label">Всего issues</span>
                    <span class="total-value">${Number(p.totalIssues || 0)}</span>
                </div>
                <div class="total-badge">
                    <span class="total-label">Всего файлов</span>
                    <span class="total-value">${Number(p.totalFiles || 0)}</span>
                </div>
            </div>

            ${renderUnifiedBreakdown(p)}
        </div>
    `;
}

/**
 * Генерирует HTML-отчёт и скачивает его.
 *
 * @param {object} data — ответ от POST /api/stats/projects
 */
export function exportStats(data) {
    const now = formatNow();
    const projects = data.projects || {};
    const totals = data.totals || {};
    const projectKeys = Object.keys(projects).sort((a, b) => a.localeCompare(b));

    const failedCount = (data.projectsRequested || 0) - (data.projectsOk || 0);

    const totalsBlockHtml = (totals.totalIssues !== undefined) ? `
        <div class="summary-block">
            <h2>📈 Суммарно по всем проектам</h2>
            <div class="totals-row">
                <div class="total-badge summary-badge">
                    <span class="total-label">Всего issues</span>
                    <span class="total-value">${Number(totals.totalIssues || 0)}</span>
                </div>
                <div class="total-badge summary-badge">
                    <span class="total-label">Всего файлов</span>
                    <span class="total-value">${Number(totals.totalFiles || 0)}</span>
                </div>
            </div>
            ${renderUnifiedBreakdown(totals)}
        </div>
    ` : '';

    const projectsHtml = projectKeys.length
        ? projectKeys.map(k => renderProjectBlock(k, projects[k])).join('')
        : `<div style="text-align:center; color:#6b778c; padding:40px; font-style:italic;">Нет данных по проектам</div>`;

    const html = `<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<title>Статистика по проектам — ${now}</title>
<style>
    body {
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        line-height: 1.5; color: #172b4d;
        max-width: 1200px; margin: 0 auto; padding: 24px 20px 40px;
        background: #fafbfc;
    }
    h1 { color: #0052cc; margin: 0 0 4px; font-size: 26px; }
    h2 {
        color: #172b4d; margin: 0 0 16px; font-size: 18px;
        border-bottom: 2px solid #0052cc; padding-bottom: 8px;
    }
    .meta { color: #6b778c; font-size: 13px; margin-bottom: 24px; }

    .filters-box {
        background: #fff; border: 1px solid #dfe1e6; border-radius: 8px;
        padding: 14px 18px; margin-bottom: 24px; font-size: 13px; line-height: 1.8;
    }
    .filters-box .row { display: flex; gap: 8px; }
    .filters-box .label { color: #6b778c; min-width: 140px; }
    .filters-box b { color: #172b4d; }

    .summary-block {
        background: linear-gradient(135deg, #0052cc 0%, #0747a6 100%);
        color: #fff; border-radius: 10px; padding: 22px 26px; margin-bottom: 32px;
        box-shadow: 0 4px 14px rgba(0,82,204,0.2);
    }
    .summary-block h2 { color: #fff; border-bottom-color: rgba(255,255,255,0.35); }
    .summary-block .breakdown-table { color: #fff; }
    .summary-block .breakdown-table th { color: rgba(255,255,255,0.85); border-bottom-color: rgba(255,255,255,0.3); }
    .summary-block .breakdown-table td { border-bottom-color: rgba(255,255,255,0.15); }
    .summary-block .param-prefix { color: rgba(255,255,255,0.7); }
    .summary-block .param-value { color: #fff; }
    .summary-block .param-count { color: #fff; }
    .summary-block .total-badge { background: rgba(255,255,255,0.15); border-color: rgba(255,255,255,0.35); }
    .summary-block .total-label { color: rgba(255,255,255,0.85); }
    .summary-block .total-value { color: #fff; }

    .project-block {
        background: #fff; border: 1px solid #dfe1e6; border-radius: 10px;
        padding: 22px 26px; margin-bottom: 24px;
        box-shadow: 0 1px 3px rgba(0,0,0,0.06);
    }
    .project-header {
        display: flex; align-items: center; gap: 8px;
        margin-bottom: 18px; padding-bottom: 4px;
    }
    .project-header h2 {
        margin: 0; border: none; padding: 0;
        font-size: 17px; font-family: monospace; word-break: break-all;
    }
    .project-icon { font-size: 20px; }

    .totals-row {
        display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 20px;
    }
    .total-badge {
        display: flex; flex-direction: column; align-items: center;
        background: #f4f5f7; border: 1px solid #dfe1e6; border-radius: 8px;
        padding: 12px 24px; min-width: 130px;
    }
    .total-label {
        font-size: 10.5px; color: #6b778c; text-transform: uppercase;
        letter-spacing: 0.6px; font-weight: 600;
    }
    .total-value {
        font-size: 26px; font-weight: 700; color: #0052cc;
        margin-top: 2px; font-variant-numeric: tabular-nums;
    }

    .breakdown-table {
        width: 100%; border-collapse: collapse; font-size: 13px;
    }
    .breakdown-table th {
        text-align: left; color: #6b778c; font-weight: 700;
        padding: 8px 12px; border-bottom: 2px solid #dfe1e6;
        font-size: 11px; text-transform: uppercase; letter-spacing: 0.3px;
    }
    .breakdown-table td {
        padding: 8px 12px; border-bottom: 1px solid #ebecf0;
    }
    .breakdown-table tr:last-child td { border-bottom: none; }
    .breakdown-table tr:hover td { background: #f4f5f7; }

    .param-prefix {
        color: #6b778c; font-family: monospace; font-size: 12px;
        font-weight: 600;
    }
    .param-value {
        color: #172b4d; font-family: monospace; font-size: 12.5px;
        margin-left: 4px;
    }
    .param-count {
        text-align: right; font-weight: 700;
        font-variant-numeric: tabular-nums; font-size: 14px;
        color: #0052cc;
    }

    .no-data {
        text-align: center; color: #6b778c; font-style: italic;
        padding: 20px; font-size: 13px;
    }

    .footer {
        text-align: center; color: #6b778c; font-size: 12px;
        margin-top: 40px; padding-top: 20px;
        border-top: 1px solid #dfe1e6;
    }

    @media print {
        body { background: #fff; }
        .project-block, .summary-block { break-inside: avoid; box-shadow: none; }
    }
</style>
</head>
<body>
    <h1>📊 Статистика по проектам</h1>
    <div class="meta">Сгенерировано: ${now}</div>

    <div class="filters-box">
        <div class="row"><span class="label">Проектов запрошено:</span><b>${Number(data.projectsRequested || 0)}</b></div>
        <div class="row"><span class="label">Успешно:</span><b>${Number(data.projectsOk || 0)}</b>${failedCount > 0 ? ` · <span style="color:#de350b;">Ошибок: ${failedCount}</span>` : ''}</div>
        ${(data.excludedTags && data.excludedTags.length)
            ? `<div class="row"><span class="label">Исключены теги:</span><b>${esc(data.excludedTags.join(', '))}</b></div>`
            : ''}
        <div class="row"><span class="label">Статусы:</span><b>${data.statuses ? esc(data.statuses) : 'все'}</b></div>
    </div>

    ${totalsBlockHtml}

    <h2 style="color:#172b4d; border-bottom:2px solid #0052cc; margin-top:32px; font-size:20px;">Проекты (${projectKeys.length})</h2>

    ${projectsHtml}

    <div class="footer">SonarQube Stats Report · ${now}</div>
</body>
</html>`;

    const filename = `stats_projects_${Date.now()}.html`;
    const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);

    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}