import { formatHtmlFile } from '../helpers.js';
// Путь к JSZip: из web/tabs/report/exporters/ выходим в web/lib/
import JSZip from '../../../lib/jszip.min.js';

function makeFileName(originalFilePath) {
    let cleanPath = originalFilePath;
    if (cleanPath.includes(':')) {
        cleanPath = cleanPath.split(':').pop();
    }
    cleanPath = cleanPath.replace(/[\\/]/g, '_');
    cleanPath = cleanPath.replace(/[^A-Za-z0-9._-]/g, '_');
    return `${cleanPath}.html`;
}

export async function exportHtmlZip(issues, projectName, host) {
    const filesMap = new Map();

    // Группируем ошибки по файлам
    issues.forEach(it => {
        const fileName = it.component || '(unknown)';
        if (!filesMap.has(fileName)) filesMap.set(fileName, []);
        filesMap.get(fileName).push(it);
    });

    const zip = new JSZip();
    const filesArray = Array.from(filesMap.entries());

    for (const [fileName, fileIssues] of filesArray) {
        const html = formatHtmlFile(fileIssues, projectName, host, fileName);
        const safeFilename = makeFileName(fileName);
        zip.file(safeFilename, html);
    }

    // Генерируем ZIP-файл
    const content = await zip.generateAsync({ type: 'blob' });
    const zipFilename = `${projectName.replace(/[^A-Za-z0-9._-]/g, '_')}_html_report.zip`;

    const url = URL.createObjectURL(content);
    const a = document.createElement('a');
    a.href = url;
    a.download = zipFilename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);

    return true;
}