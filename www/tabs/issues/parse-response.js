/**
 * Парсер ответа Ollama: { status, comment, tags, fixDecision }.
 * Гарантирует наличие ровно одного фикс-тега: require-fix | not-require-fix.
 *
 * ВАЖНО: LLM часто возвращает ответ в markdown-обёртке:
 *   **Статус:** accept
 *   **Критичность:** ...
 *   **Теги:** not-require-fix, code-smell
 *
 * Поэтому перед матчингом снимаем *, _, ` — иначе regex "Статус:\s*(...)"
 * не найдёт статус, parsed.status = '', в запрос уйдёт transition=
 * и сервер ответит 400 Bad Request.
 */
export function parseOllamaResponse(raw) {
    const text = String(raw || '');

    // Снимаем markdown-обёртки ДО всех regex. Это ключевая строка —
    // именно её отсутствие вызывало 400 Bad Request при переходе.
    const cleaned = text.replace(/[*_`]/g, '');

    let status = '';
    let comment = '';
    let tags = [];

    // ---------- Статус ----------
    const statusMatch = cleaned.match(/Статус:\s*(accept|falsepositive|confirm|resolve|wontfix)/i);
    if (statusMatch) status = statusMatch[1].toLowerCase();

    // ---------- Теги ----------
    const tagsMatch = cleaned.match(/Теги:\s*([^\n]+)/i);
    if (tagsMatch) {
        tags = tagsMatch[1]
            .toLowerCase()
            .split(/[,;\s]+/)
            .map(t => t.trim())
            .filter(t => /^[a-z0-9\+\#\-\.]+$/.test(t));
    }

    // ---------- Взаимоисключающие фикс-теги ----------
    const hasRequire    = tags.includes('require-fix');
    const hasNotRequire = tags.includes('not-require-fix');

    tags = tags.filter(t => t !== 'require-fix' && t !== 'not-require-fix');

    let fixDecision;
    if (hasRequire && !hasNotRequire)      fixDecision = 'require-fix';
    else if (hasNotRequire && !hasRequire) fixDecision = 'not-require-fix';
    else                                    fixDecision = 'require-fix';

    tags.unshift(fixDecision);
    tags = tags.slice(0, 3);

    // ---------- Комментарий ----------
    // Используем cleaned, чтобы "**Критичность:**" матчилось как "Критичность:".
    const lines = cleaned.split(/\r?\n/);
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

    // Fallback: если «Критичность:» не встретилась — берём всё,
    // кроме служебных строк «Статус:» и «Теги:».
    if (!comment) {
        comment = lines
            .filter(l => !/^Статус:/i.test(l.trim()) && !/^Теги:/i.test(l.trim()))
            .join('\n')
            .trim();
    }

    return { status, comment, tags, fixDecision };
}