import { formatJira } from '../helpers.js';

export async function exportJira(issues, projectName, host) {
    const blocks = [];
    const now = new Date().toLocaleString('ru-RU');

    blocks.push(`h3. Проект: ${projectName}\n`);
    blocks.push(`*Всего issues:* ${issues.length}\n*Сгенерировано:* ${now}\n\n`);

    for (const it of issues) {
        blocks.push(formatJira(it, it._snippet, projectName, host));
    }

    const text = blocks.join('\n');
    const filename = `${projectName.replace(/[^A-Za-z0-9._-]/g, '_')}_${Date.now()}.txt`;
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });

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