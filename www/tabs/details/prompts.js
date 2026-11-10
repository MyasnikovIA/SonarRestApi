import { detectLanguage, highlightForPrompt } from '../../common.js';

export function buildStatusCommentPrompt(issue, ruleName, snippet, status, statusLabel) {
    const code = highlightForPrompt(snippet, issue.line).trim();
    const lang = detectLanguage(issue.component);
    const severity = issue.impactSeverity || issue.severity || '';
    const hasSelectedStatus = !!(status && statusLabel);

    const lines = [
        'Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).',
        'Проект мультиязычный: PHP, JavaScript, TypeScript, Java, Kotlin, SQL, PL/SQL,',
        'Python, Go, Ruby, C#, C++, C, Rust, Swift, Vue и другие языки.',
        'НЕ делай предположений о языке — он указан ниже (определён по расширению файла).',
        ''
    ];

    if (hasSelectedStatus) {
        lines.push(
            'ЗАДАЧА: Пользователь уже выбрал статус перехода в SonarQube:',
            '  "' + status + '" — ' + statusLabel,
            'Сгенерируй КОРОТКИЙ комментарий, объясняющий этот статус для данного кода.',
            'Статус в ответе должен быть РОВНО "' + status + '" (не меняй его).',
            ''
        );
    } else {
        lines.push(
            'ЗАДАЧА: Определи, есть ли в коде РЕАЛЬНЫЙ дефект, на который указало правило,',
            'и выбери корректный статус.',
            '',
            'ОПРЕДЕЛЕНИЯ СТАТУСОВ:',
            '- falsepositive: SonarQube ошибся в анализе.',
            '- accept: реальная проблема, исправим позже (техдолг).',
            '- confirm: реальная и срочная проблема.',
            '- resolve: уже исправлено в коде.',
            '- wontfix: реальная проблема, но не будем исправлять.',
            ''
        );
    }

    lines.push(
        'АНАЛИЗ КРИТИЧНОСТИ:',
        'SonarQube оценил как: **' + (severity || 'не указана') + '**',
        '',
        'Контекст ошибки:',
        '- Правило: '   + (ruleName || issue.rule || ''),
        '- Сообщение: ' + (issue.message || ''),
        '- Файл: '      + (issue.component || '') + (issue.line ? ':' + issue.line : ''),
        '- Язык: '      + lang.name,
        '',
        'Фрагмент кода (маркеры >>> и <<< показывают конкретное место):',
        '```' + lang.codeFence,
        code,
        '```',
        '',
        'Требования к ответу (СТРОГО, без отклонений):',
        '1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"',
        '2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"',
        '3. Строка 3 и далее: 1–3 предложения без префиксов.',
        '4. Пиши по-русски, без markdown.',
        '',
        'Верни ТОЛЬКО этот текст.'
    );

    return lines.join('\n');
}

export async function buildBulkStatusCommentPrompt(issues, status, statusLabel, ctx) {
    const issuesList = issues.map((it, idx) =>
        `${idx + 1}. ${it.key} | ${it.rule} | ${it.component}:${it.line} | ${it.message || ''}`
    ).join('\n');

    return [
        'Ты — опытный разработчик и эксперт по SonarQube.',
        'Тебе нужно обработать ГРУППУ замечаний (одинаковый статус для всех).',
        '',
        'ЗАДАЧА:',
        '1. Подтверди статус "' + status + '" — ' + statusLabel + ' для всей группы.',
        '2. Определи общую критичность.',
        '3. Объясни в 1–3 предложениях.',
        '',
        'ОПРЕДЕЛЕНИЯ СТАТУСОВ:',
        '- falsepositive: SonarQube ошибся.',
        '- accept: реальная проблема, исправим позже.',
        '- confirm: реальная и срочная.',
        '- resolve: уже исправлено.',
        '- wontfix: не будем исправлять.',
        '',
        'Замечания (' + issues.length + '):',
        issuesList,
        '',
        'ФОРМАТ ОТВЕТА (СТРОГО):',
        '1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"',
        '2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"',
        '3. Строки 3+: 1–3 предложения.',
        '',
        'Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.'
    ].join('\n');
}

export function buildAutoResolvePromptAsync(issue, ctx) {
    const snippet = issue.snippet || [];
    const code = snippet.join('\n');
    const lang = detectLanguage(issue.component);
    const severity = issue.impactSeverity || issue.severity || '';

    return [
        'Ты — опытный разработчик и эксперт по SonarQube.',
        '',
        'ЗАДАЧА: определить статус, критичность и обоснование.',
        '',
        'ОПРЕДЕЛЕНИЯ СТАТУСОВ:',
        '- falsepositive: SonarQube ошибся.',
        '- accept: реальная проблема, откладываем.',
        '- confirm: реальная и срочная.',
        '- resolve: уже исправлено.',
        '- wontfix: не будем исправлять.',
        '',
        'Контекст:',
        '- Правило: ' + (issue.ruleName || issue.rule || ''),
        '- Сообщение: ' + (issue.message || ''),
        '- Файл: ' + (issue.component || '') + (issue.line ? ':' + issue.line : ''),
        '- Severity: ' + severity,
        '- Язык: ' + lang.name,
        '',
        'Код:',
        '```' + lang.codeFence,
        code,
        '```',
        '',
        'ФОРМАТ ОТВЕТА (СТРОГО):',
        '1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"',
        '2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"',
        '3. Строки 3+: 1–3 предложения.',
        '',
        'Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.'
    ].join('\n');
}