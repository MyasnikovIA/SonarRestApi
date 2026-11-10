/**
 * Парсер ответа Ollama для details-модалки.
 * Возвращает только { status, comment } — теги в details не используются.
 */
export function parseOllamaResponse(raw) {
    const text = String(raw || '');
    let status = '';

    const statusMatch = text.match(/Статус:\s*(accept|falsepositive|confirm|resolve|wontfix)/i);
    if (statusMatch) status = statusMatch[1].toLowerCase();

    const lines = text.split(/\r?\n/);
    const commentLines = [];
    let inComment = false;
    for (const line of lines) {
        const trimmed = line.trim();
        if (/^Статус:/i.test(trimmed)) continue;
        if (/^Критичность:/i.test(trimmed)) {
            inComment = true;
            commentLines.push(line);
            continue;
        }
        if (inComment) commentLines.push(line);
    }
    let comment = commentLines.join('\n').trim();
    if (!comment) {
        comment = lines.filter(l => !/^Статус:/i.test(l.trim())).join('\n').trim();
    }
    return { status, comment };
}