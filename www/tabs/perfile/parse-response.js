/**
 * Парсер ответа Ollama: { status, comment, tags, fixDecision }.
 * Гарантирует наличие ровно одного фикс-тега: require-fix | not-require-fix.
 */
export function parseOllamaResponse(raw) {
    const text = String(raw || '');
    let status = '';
    let comment = '';
    let tags = [];

    const statusMatch = text.match(/Статус:\s*(accept|falsepositive|confirm|resolve|wontfix)/i);
    if (statusMatch) status = statusMatch[1].toLowerCase();

    const tagsMatch = text.match(/Теги:\s*([^\n]+)/i);
    if (tagsMatch) {
        tags = tagsMatch[1]
            .split(/[,;]/)
            .map(t => t.replace(/[*_`]/g, '').trim().toLowerCase())
            .filter(t => t.length > 0);
    }

    const hasRequire    = tags.includes('require-fix');
    const hasNotRequire = tags.includes('not-require-fix');

    tags = tags.filter(t => t !== 'require-fix' && t !== 'not-require-fix');

    let fixDecision;
    if (hasRequire && !hasNotRequire)      fixDecision = 'require-fix';
    else if (hasNotRequire && !hasRequire) fixDecision = 'not-require-fix';
    else                                    fixDecision = 'require-fix';

    tags.unshift(fixDecision);
    tags = tags.slice(0, 3);

    const lines = text.split(/\r?\n/);
    const commentLines = [];
    let inComment = false;
    for (const line of lines) {
        const trimmed = line.trim();
        if (/^Статус:/i.test(trimmed)) continue;
        if (/^Теги:/i.test(trimmed)) continue;
        if (/^Критичность:/i.test(trimmed)) {
            inComment = true;
            commentLines.push(line);
            continue;
        }
        if (inComment) commentLines.push(line);
    }
    comment = commentLines.join('\n').trim();
    if (!comment) {
        comment = lines
            .filter(l => !/^Статус:/i.test(l.trim()) && !/^Теги:/i.test(l.trim()))
            .join('\n')
            .trim();
    }

    return { status, comment, tags, fixDecision };
}