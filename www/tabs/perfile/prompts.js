import { detectLanguage, highlightForPrompt } from '../../common.js';

export function buildStatusCommentPrompt(issue, ruleName, snippet, status, statusLabel, availableTags) {
    const code = highlightForPrompt(snippet, issue.line).trim();
    const lang = detectLanguage(issue.component);
    const severity = issue.impactSeverity || issue.severity || '';
    const tagsList = (availableTags && availableTags.length)
        ? availableTags.join(', ')
        : 'любые релевантные';
    const filePath = issue.component || '';

    return [
        'Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).',
        'Проект мультиязычный. НЕ предполагай язык — он указан ниже.',
        '',
        'ЗАДАЧА:',
        '1. Подтверди или скорректируй выбранный пользователем статус: "' + status + '" — ' + statusLabel,
        '2. Определи реальную критичность.',
        '3. Дай РЕШЕНИЕ: нужно ли исправлять этот баг?',
        '4. Выбери 2–3 тега из списка доступных (обязательно с фикс-тегом).',
        '5. Объясни в 1–3 предложениях.',
        '',
        'ОПРЕДЕЛЕНИЯ СТАТУСОВ:',
        '- falsepositive: SonarQube ошибся, проблемы нет.',
        '- accept: реальная проблема, исправим позже (техдолг).',
        '- confirm: реальная и срочная проблема (security, потеря данных).',
        '- resolve: уже исправлено в коде.',
        '- wontfix: реальная проблема, но не будем исправлять.',
        '',
        '=== КРИТИЧЕСКИ ВАЖНО: СГЕНЕРИРОВАННЫЙ КОД ===',
        'Файл считается СГЕНЕРИРОВАННЫМ, если ЛЮБОЙ из признаков верен:',
        '- путь содержит "/Api/" и имя файла заканчивается на "Api.php" (OpenAPI/Swagger client),',
        '- путь содержит "/Model/" (сгенерированные модели),',
        '- в коде есть маркеры: "ObjectSerializer", "handleResponseWithDataType", "ApiException",',
        '  "$this->config", "$this->headerSelector", "buildQuery", "ObjectSerializer::deserialize",',
        '- расширения: .pb.go, .pb.php, .pb.cc, .g.cs, *_generated.*, *_pb2.py, *-openapi.*, *.g.dart.',
        '',
        'Для СГЕНЕРИРОВАННОГО кода:',
        '- статус = accept ИЛИ wontfix (никогда confirm/resolve/falsepositive),',
        '- фикс-тег = ВСЕГДА "not-require-fix",',
        '- НЕ предлагай рефакторинг, объединение веток, добавление default в switch,',
        '  удаление дублирования — это нормальная особенность генератора,',
        '  правки затрутся при следующей регенерации,',
        '- ПРИМЕР: дублирование case 401 и case 422 в *Api.php — типичный сгенерированный',
        '  switch, править НЕ нужно, ставить not-require-fix.',
        '',
        '=== КРИТИЧЕСКИ ВАЖНО: ЯЗЫКОВЫЕ СИГНАЛЫ ===',
        'Если ты пишешь "функционально работает корректно", "не влияет на работу",',
        '"нарушает читаемость но не ломает", "косметическая проблема" — это ЯВНЫЙ СИГНАЛ',
        'поставить фикс-тег "not-require-fix", а не "require-fix".',
        '',
        '=== РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===',
        'Верни РОВНО ОДИН из двух тегов в поле "Теги:"',
        '- "require-fix"     — баг РЕАЛЬНЫЙ, в РУЧНОМ коде, его НУЖНО исправить.',
        '- "not-require-fix" — баг НЕ требует исправления: сгенерированный код,',
        '  false positive, стилистика без последствий, wontfix.',
        '',
        'СВЯЗЬ СТАТУСА И ФИКС-ТЕГА:',
        '- status=confirm       → require-fix',
        '- status=resolve       → not-require-fix (уже исправлено)',
        '- status=wontfix       → not-require-fix',
        '- status=falsepositive → not-require-fix',
        '- status=accept        → require-fix (ручной код) или not-require-fix (сгенерированный)',
        '',
        'ДОСТУПНЫЕ ТЕГИ (используй ТОЛЬКО из этого списка): ' + tagsList,
        'В поле "Теги:" верни СНАЧАЛА require-fix или not-require-fix,',
        'затем 1–2 тега СТРОГО из списка выше.',
        '',
        'Контекст:',
        '- Правило: ' + (ruleName || issue.rule || ''),
        '- Сообщение: ' + (issue.message || ''),
        '- Файл: ' + filePath + (issue.line ? ':' + issue.line : ''),
        '- Severity: ' + severity,
        '- Язык: ' + lang.name,
        '',
        'Код:',
        '```' + lang.codeFence,
        code,
        '```',
        '',
        'ФОРМАТ ОТВЕТА (СТРОГО, без отклонений):',
        '1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"',
        '2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"',
        '3. Строка 3: "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"',
        '4. Строки 4+: 1–3 предложения.',
        '',
        'Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.'
    ].join('\n');
}

export async function buildBulkStatusCommentPrompt(issues, status, statusLabel, ctx, availableTags) {
    const tagsList = (availableTags && availableTags.length)
        ? availableTags.join(', ')
        : 'любые релевантные';

    const issuesList = issues.map((it, idx) =>
        `${idx + 1}. ${it.key} | ${it.rule} | ${it.component}:${it.line} | ${it.message || ''}`
    ).join('\n');

    return [
        'Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).',
        'Тебе нужно обработать ГРУППУ замечаний (одинаковый статус для всех).',
        '',
        'ЗАДАЧА:',
        '1. Подтверди статус "' + status + '" — ' + statusLabel + ' для всей группы.',
        '2. Определи общую критичность.',
        '3. Дай ОДНО общее РЕШЕНИЕ: нужно ли исправлять эти баги?',
        '4. Предложи 2–3 общих тега (обязательно с фикс-тегом).',
        '5. Объясни в 1–3 предложениях.',
        '',
        'ОПРЕДЕЛЕНИЯ СТАТУСОВ:',
        '- falsepositive: SonarQube ошибся.',
        '- accept: реальная проблема, исправим позже.',
        '- confirm: реальная и срочная.',
        '- resolve: уже исправлено.',
        '- wontfix: не будем исправлять.',
        '',
        '=== КРИТИЧЕСКИ ВАЖНО: СГЕНЕРИРОВАННЫЙ КОД ===',
        'Группа считается СГЕНЕРИРОВАННОЙ, если у БОЛЬШИНСТВА issues:',
        '- путь содержит "/Api/" и имя *Api.php (OpenAPI client), или "/Model/",',
        '- в коде маркеры ObjectSerializer, handleResponseWithDataType, ApiException,',
        '- расширения .pb.go, *.g.cs, *_generated.*, *-openapi.*.',
        'Для такой группы: статус = accept/wontfix, фикс-тег = "not-require-fix".',
        '',
        'СИГНАЛЫ not-require-fix в тексте: "функционально работает корректно",',
        '"не влияет на работу", "нарушает читаемость но не ломает" — сразу not-require-fix.',
        '',
        '=== РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===',
        'Верни РОВНО ОДИН из двух тегов в поле "Теги:"',
        '- "require-fix"     — группа содержит РЕАЛЬНЫЕ баги в РУЧНОМ коде.',
        '- "not-require-fix" — не требует исправления (FP, сгенерированный код, стилистика).',
        '',
        'ДОСТУПНЫЕ ТЕГИ (только из списка): ' + tagsList,
        '',
        'Замечания (' + issues.length + '):',
        issuesList,
        '',
        'ФОРМАТ ОТВЕТА (СТРОГО):',
        '1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"',
        '2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"',
        '3. Строка 3: "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"',
        '4. Строки 4+: 1–3 предложения.',
        '',
        'Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.'
    ].join('\n');
}

export function buildAutoResolvePromptAsync(issue, ctx, availableTags) {
    const snippet = issue.snippet || [];
    const code = snippet.join('\n');
    const lang = detectLanguage(issue.component);
    const severity = issue.impactSeverity || issue.severity || '';
    const tagsList = (availableTags && availableTags.length)
        ? availableTags.join(', ')
        : 'любые релевантные';
    const filePath = issue.component || '';

    return [
        'Ты — опытный разработчик и эксперт по SonarQube.',
        '',
        'ЗАДАЧА: определить статус, критичность, решение о фиксе, теги и обоснование.',
        '',
        'ОПРЕДЕЛЕНИЯ СТАТУСОВ:',
        '- falsepositive: SonarQube ошибся.',
        '- accept: реальная проблема, откладываем.',
        '- confirm: реальная и срочная.',
        '- resolve: уже исправлено.',
        '- wontfix: не будем исправлять.',
        '',
        '=== КРИТИЧЕСКИ ВАЖНО: СГЕНЕРИРОВАННЫЙ КОД ===',
        'Файл СГЕНЕРИРОВАН, если:',
        '- путь содержит "/Api/" + *Api.php (OpenAPI), или "/Model/",',
        '- маркеры в коде: ObjectSerializer, handleResponseWithDataType, ApiException,',
        '- расширения: .pb.go, *.g.cs, *_generated.*, *_pb2.py, *-openapi.*.',
        'Для сгенерированного: статус accept/wontfix, тег "not-require-fix",',
        'НЕ предлагай рефакторинг — правки затрутся при регенерации.',
        '',
        'Если пишешь "функционально работает корректно", "не влияет на работу" —',
        'это ЯВНЫЙ СИГНАЛ not-require-fix.',
        '',
        '=== РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===',
        'Верни РОВНО ОДИН из двух тегов в поле "Теги:"',
        '- "require-fix"     — баг РЕАЛЬНЫЙ и НУЖНО исправить.',
        '- "not-require-fix" — не требует исправления (FP, сгенерированный, wontfix).',
        '',
        'ДОСТУПНЫЕ ТЕГИ (только из списка): ' + tagsList,
        'В поле "Теги:" верни СНАЧАЛА require-fix или not-require-fix,',
        'затем 1–2 тега СТРОГО из списка.',
        '',
        'Контекст:',
        '- Правило: ' + (issue.ruleName || issue.rule || ''),
        '- Сообщение: ' + (issue.message || ''),
        '- Файл: ' + filePath + (issue.line ? ':' + issue.line : ''),
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
        '3. Строка 3: "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"',
        '4. Строки 4+: 1–3 предложения.',
        '',
        'Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.'
    ].join('\n');
}