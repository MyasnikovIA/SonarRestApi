import { esc, toast } from '../../common.js';
import { formatJira, formatMd, formatTxt, formatConfluence, formatHtmlFile } from './helpers.js';

// Функция для безопасного формирования имени файла по шаблону:
// Project_(Path_to_File.ext).html
function makeFileName(projectName, originalFilePath) {
    // 1. Убираем имя проекта из пути файла (если оно есть)
    let cleanPath = originalFilePath;
    if (cleanPath.includes(':')) {
        cleanPath = cleanPath.split(':').pop();
    }
    // 2. Заменяем слэши на подчеркивания
    cleanPath = cleanPath.replace(/[\\/]/g, '_');
    // 3. Убираем недопустимые символы
    cleanPath = cleanPath.replace(/[^A-Za-z0-9._-]/g, '_');
    // 4. Очищаем имя проекта
    const safeProject = projectName.replace(/[^A-Za-z0-9._-]/g, '_');

    return `${safeProject}_(${cleanPath}).html`;
}

export async function showExportModal(issues, formatType, ctx, projectName) {
    if (!issues.length) return;

    let dirHandle = null;
    // Пытаемся использовать File System Access API (только Chrome/Edge, только HTTPS/localhost)
    if (formatType === 'html-files') {
        if (window.showDirectoryPicker) {
            try {
                dirHandle = await window.showDirectoryPicker({ mode: 'readwrite' });
                // Принудительно запрашиваем разрешение на запись для всей папки
                if (await dirHandle.queryPermission({ mode: 'readwrite' }) !== 'granted') {
                    const perm = await dirHandle.requestPermission({ mode: 'readwrite' });
                    if (perm !== 'granted') {
                        toast('Нет разрешения на запись в выбранную папку', true);
                        return;
                    }
                }
            } catch (e) {
                if (e.name === 'AbortError') {
                    toast('Выбор папки отменен', true);
                    return;
                }
                console.warn('Directory picker failed', e);
            }
        } else {
            toast('Ваш браузер не поддерживает выбор папки (нужен Chrome/Edge и HTTPS). Файлы будут скачиваться по одному.', true);
        }
    }

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.style.zIndex = '2000';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 520px;">
            <div class="modal-header">
                <h3 style="margin:0;">Генерация отчета (${formatType === 'confluence' ? 'Confluence' : (formatType === 'html-files' ? 'HTML по файлам' : formatType.toUpperCase())})</h3>
            </div>
            <div class="modal-body">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; font-size:12px;">
                    <span>Прогресс: <b id="exp-counter">0 / ${issues.length}</b></span>
                    <span id="exp-status" style="color:var(--text-dim);">подготовка...</span>
                </div>
                <div style="height:8px; background:var(--bg3); border-radius:4px; overflow:hidden; border:1px solid var(--border);">
                    <div id="exp-fill" style="height:100%; width:0%; background:var(--accent); transition: width 0.15s;"></div>
                </div>
                <div id="exp-current" style="margin-top:10px; font-size:12px; color:var(--text-dim); white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">—</div>
                <div style="margin-top:16px; display:flex; justify-content:flex-end; gap:8px;">
                    <button id="exp-cancel" class="btn btn-danger" type="button">Остановить</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const counterEl = overlay.querySelector('#exp-counter');
    const statusEl = overlay.querySelector('#exp-status');
    const fillEl = overlay.querySelector('#exp-fill');
    const currentEl = overlay.querySelector('#exp-current');
    const cancelBtn = overlay.querySelector('#exp-cancel');

    let cancelled = false;
    const controller = new AbortController();

    cancelBtn.addEventListener('click', () => {
        cancelled = true;
        controller.abort();
        statusEl.textContent = 'Остановка...';
    });

    const blocks = [];
    const total = issues.length;
    const now = new Date().toLocaleString('ru-RU');

    let host = 'sonar.bars.group';
    try {
        const status = await ctx.api('/api/status');
        if (status.session && status.session.host) {
            host = status.session.host;
        }
    } catch (e) {
        console.warn('Не удалось получить host для ссылок', e);
    }

    // Заголовки для единых файлов
    if (formatType === 'jira') {
        blocks.push(`h3. Проект: ${projectName}\n`);
        blocks.push(`*Всего issues:* ${total}\n*Сгенерировано:* ${now}\n\n`);
    } else if (formatType === 'md') {
        blocks.push(`# Отчет по проекту: ${projectName}\n`);
        blocks.push(`**Сгенерировано:** ${now}\n**Всего issues:** ${total}\n\n---\n`);
    } else if (formatType === 'txt') {
        blocks.push(`=== Отчет по проекту: ${projectName} ===\n`);
        blocks.push(`Сгенерировано: ${now}\nВсего issues: ${total}\n\n`);
    } else if (formatType === 'confluence') {
        blocks.push(`<h2>Проект: ${projectName}</h2>\n`);
        blocks.push(`<p><em>Сгенерировано: ${now} &middot; Всего issues: ${total}</em></p>\n<hr/>\n`);
    }

    const filesMap = new Map();

    // ЭТАП 1: Загрузка всех сниппетов
    for (let i = 0; i < total; i++) {
        if (cancelled) break;
        const it = issues[i];

        counterEl.textContent = `${i + 1} / ${total}`;
        currentEl.textContent = it.key + ' — ' + it.component;
        statusEl.textContent = 'загрузка кода...';
        fillEl.style.width = `${Math.round(((i) / total) * 100)}%`;

        let snippet = [];
        if (!it._snippet) {
            try {
                const p = new URLSearchParams({ project: ctx.state.project, issueKey: it.key });
                const d = await ctx.api('/api/snippet?' + p, { signal: controller.signal });
                snippet = d.snippet || [];
                it._snippet = snippet;
                if (d.issue && d.issue.comments) {
                    it.comments = d.issue.comments;
                }
            } catch (e) {
                if (e.name === 'AbortError') break;
                console.warn('Failed to fetch snippet for ' + it.key, e);
            }
        } else {
            snippet = it._snippet;
        }

        if (formatType === 'html-files') {
            const fileName = it.component || '(unknown)';
            if (!filesMap.has(fileName)) filesMap.set(fileName, []);
            filesMap.get(fileName).push(it);
        } else {
            if (formatType === 'jira')       blocks.push(formatJira(it, snippet, projectName, host));
            if (formatType === 'md')         blocks.push(formatMd(it, snippet, projectName, host));
            if (formatType === 'txt')        blocks.push(formatTxt(it, snippet, projectName, host));
            if (formatType === 'confluence') blocks.push(formatConfluence(it, snippet, projectName, host));

            if (formatType === 'md')  blocks.push('\n---\n');
            if (formatType === 'txt') blocks.push('\n----------------------------------------\n');
        }
    }

    // ЭТАП 2: Сохранение файлов
    fillEl.style.width = '100%';

    if (!cancelled) {
        if (formatType === 'html-files') {
            const filesArray = Array.from(filesMap.entries());

            if (dirHandle) {
                // Сохраняем напрямую в выбранную папку (без подтверждений)
                statusEl.textContent = 'сохранение файлов в папку...';
                let savedCount = 0;

                for (const [fileName, fileIssues] of filesArray) {
                    if (cancelled) break;

                    const safeFilename = makeFileName(projectName, fileName);
                    currentEl.textContent = `Сохранение: ${safeFilename}`;

                    try {
                        const html = formatHtmlFile(fileIssues, projectName, host, fileName);
                        const fileHandle = await dirHandle.getFileHandle(safeFilename, { create: true });
                        const writable = await fileHandle.createWritable();
                        await writable.write(html);
                        await writable.close();
                        savedCount++;
                        statusEl.textContent = `Сохранено ${savedCount} из ${filesArray.length}`;
                    } catch (err) {
                        console.error('Ошибка записи файла:', safeFilename, err);
                        toast(`Ошибка записи ${safeFilename}: ${err.message}`, true);
                    }
                }
            } else {
                // Поштучное скачивание (Fallback для HTTP, браузер может спрашивать имя)
                statusEl.textContent = 'скачивание файлов...';
                let delay = 0;
                for (const [fileName, fileIssues] of filesArray) {
                    if (cancelled) break;

                    const html = formatHtmlFile(fileIssues, projectName, host, fileName);
                    const safeFilename = makeFileName(projectName, fileName);

                    currentEl.textContent = `Скачивание: ${safeFilename}`;

                    setTimeout(() => {
                        const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = safeFilename;
                        document.body.appendChild(a);
                        a.click();
                        document.body.removeChild(a);
                        URL.revokeObjectURL(url);
                    }, delay);
                    delay += 300; // 300ms между скачиваниями, чтобы браузер не блокировал
                }
            }
        } else if (blocks.length > 1) {
            const text = blocks.join('\n');
            const ext = formatType === 'jira' ? 'txt' : (formatType === 'confluence' ? 'html' : formatType);
            const mime = formatType === 'md' ? 'text/markdown' : (formatType === 'confluence' ? 'text/html' : 'text/plain');

            const filename = `${projectName.replace(/[^A-Za-z0-9._-]/g, '_')}_${Date.now()}.${ext}`;
            const blob = new Blob([text], { type: `${mime};charset=utf-8` });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }
    }

    statusEl.textContent = cancelled ? 'Отменено пользователем' : 'Готово!';
    cancelBtn.textContent = 'Закрыть';
    cancelBtn.classList.remove('btn-danger');
    cancelBtn.classList.add('btn-accent');
    cancelBtn.replaceWith(cancelBtn.cloneNode(true));
    overlay.querySelector('#exp-cancel').addEventListener('click', () => overlay.remove());
}