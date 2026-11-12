import { formatMd } from '../helpers.js';

export async function exportMd(issues, projectName, host) {
    const blocks = [];
    const now = new Date().toLocaleString('ru-RU');

    blocks.push(`# Отчет по проекту: ${projectName}\n`);
    blocks.push(`**Сгенерировано:** ${now}\n**Всего issues:** ${issues.length}\n\n---\n`);

    for (const it of issues) {
        blocks.push(formatMd(it, it._snippet, projectName, host));
        blocks.push('\n---\n');
    }

    const text = blocks.join('\n');
    const filename = `${projectName.replace(/[^A-Za-z0-9._-]/g, '_')}_${Date.now()}.md`;
    const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' });

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