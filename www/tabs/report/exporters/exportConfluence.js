import { formatConfluence } from '../helpers.js';

export async function exportConfluence(issues, projectName, host) {
    const blocks = [];
    const now = new Date().toLocaleString('ru-RU');

    blocks.push(`<h2>Проект: ${projectName}</h2>\n`);
    blocks.push(`<p><em>Сгенерировано: ${now} &middot; Всего issues: ${issues.length}</em></p>\n<hr/>\n`);

    for (const it of issues) {
        blocks.push(formatConfluence(it, it._snippet, projectName, host));
    }

    const text = blocks.join('\n');
    const filename = `${projectName.replace(/[^A-Za-z0-9._-]/g, '_')}_${Date.now()}.html`;
    const blob = new Blob([text], { type: 'text/html;charset=utf-8' });

    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);

    return true;
}