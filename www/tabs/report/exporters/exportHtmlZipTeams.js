import { formatHtmlFile } from '../helpers.js';
import JSZip from '../../../lib/jszip.min.js';

// Простой парсер CSV, поддерживающий кавычки и переносы строк внутри ячеек
function parseCSV(text) {
    const result = [];
    let row = [];
    let col = '';
    let inQuotes = false;

    for (let i = 0; i < text.length; i++) {
        const char = text[i];
        if (inQuotes) {
            if (char === '"') {
                if (text[i + 1] === '"') {
                    col += '"';
                    i++;
                } else {
                    inQuotes = false;
                }
            } else {
                col += char;
            }
        } else {
            if (char === '"') {
                inQuotes = true;
            } else if (char === ',') {
                row.push(col);
                col = '';
            } else if (char === '\n' || char === '\r') {
                if (char === '\r' && text[i + 1] === '\n') i++;
                row.push(col);
                result.push(row);
                row = [];
                col = '';
            } else {
                col += char;
            }
        }
    }

    if (col || row.length) {
        row.push(col);
        result.push(row);
    }

    return result;
}

async function getTeamMapping() {
    const map = {};
    try {
        const res = await fetch('/api/team_mapping');
        if (!res.ok) return map;

        const csv = await res.text();
        const rows = parseCSV(csv);

        // Пропускаем заголовок (i = 1)
        for (let i = 1; i < rows.length; i++) {
            const r = rows[i];
            if (!r || r.length < 3) continue;

            // Очищаем ячейки от кавычек и пробелов
            const cleanCell = (val) => val ? val.replace(/^"|"$/g, '').trim() : '';

            const colA = cleanCell(r[0]); // Форма (обычно путь)
            const colB = cleanCell(r[1]); // Переопределяется формами
            const colC = cleanCell(r[2]); // Переопределяет формы

            // Ищем имя команды. В примере оно находится в колонке R (индекс 17) "Ответственный аналитик" или "Владелец тМИС"
            // Но пользователь просил брать команду из колонки C (индекс 2) или следующей непустой.
            // Давайте искать "Уварова Ксения" или "TT" и т.д. в колонках с 2 по 20.
            let team = '';
            for (let c = 2; c < Math.min(r.length, 25); c++) {
                const val = cleanCell(r[c]);
                // Если значение не пустое и не похоже на путь к файлу (не содержит .frm, .pas и т.д.)
                if (val && !val.includes('/') && !val.includes('\\') && !val.includes('.frm')) {
                    team = val;
                    break;
                }
            }

            // Записываем в маппинг возможные пути из колонок A, B, C
            if (colA) map[colA] = team;
            if (colB) map[colB] = team;
            if (colC) map[colC] = team;
        }
    } catch (e) {
        console.warn('Не удалось загрузить CSV с сервера:', e);
    }
    return map;
}

function makeTeamFileName(originalFilePath, mapping) {
    let cleanPath = originalFilePath;
    // Отделяем имя проекта (если есть), например "project:src/Api.php" -> "src/Api.php"
    if (cleanPath.includes(':')) {
        cleanPath = cleanPath.split(':').pop();
    }

    // Нормализуем слэши для поиска в маппинге
    const searchPath = cleanPath.replace(/\\/g, '/');
    const searchPathNoSlash = searchPath.replace(/^\//, '');
    const searchPathWithSlash = '/' + searchPathNoSlash;

    // Ищем команду
    let team = mapping[searchPath] || mapping[searchPathNoSlash] || mapping[searchPathWithSlash];

    // Заменяем слэши на подчеркивания для безопасного имени файла
    let safePath = cleanPath.replace(/[\\/]/g, '_');
    safePath = safePath.replace(/[^A-Za-z0-9._-]/g, '_');

    if (team) {
        const safeTeam = team.replace(/[^A-Za-z0-9._-]/g, '_');
        return `(${safeTeam})_${safePath}.html`;
    } else {
        return `${safePath}.html`;
    }
}

export async function exportHtmlZipTeams(issues, projectName, host) {
    const mapping = await getTeamMapping();
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
        const safeFilename = makeTeamFileName(fileName, mapping);
        zip.file(safeFilename, html);
    }

    const content = await zip.generateAsync({ type: 'blob' });
    const zipFilename = `${projectName.replace(/[^A-Za-z0-9._-]/g, '_')}_html_teams_report.zip`;

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