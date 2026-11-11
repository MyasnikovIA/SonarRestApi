import {
    esc, toast, highlightCode,
    detectLanguage, cleanSnippet
} from '../common.js';
import { bindOllamaResolveButton, bindOllamaSettingsButton } from '../ollama.js';

export const snippet = {
    id: 'snippet',
    title: 'Код ошибки (Snippet)',
    filters: ['sev', 'qual', 'issueKey'],

    async mount(el, ctx) {
        const key = ctx.state.issueKey;
        if (!key) {
            el.innerHTML = '<div class="empty">Введите issueKey в фильтре сверху</div>';
            return;
        }
        el.innerHTML = '<div class="empty">loading…</div>';
        try {
            const p = new URLSearchParams();
            if (ctx.state.project) p.set('project', ctx.state.project);
            p.set('issueKey', key);

            const d = await ctx.api('/api/snippet?' + p);
            const it = d.issue;
            if (!it) {
                el.innerHTML = '<div class="empty">Issue не найден</div>';
                return;
            }

            const isMarked = it.status !== 'OPEN' && it.status !== 'REOPENED';

            el.innerHTML = `
                <div class="card">
                    <div style="display:flex; gap:10px; border-top:1px solid var(--border); padding-top:16px;">
                        ${!isMarked
                            ? `<button id="sn-btn-mark" class="btn btn-accent">Установить статус</button>`
                            : `<button id="sn-btn-cancel" class="btn btn-danger">Отменить комментарий</button>`}
                    </div>
                    <h3>${esc(it.key)}</h3>
                    <div style="margin:8px 0"><b>${esc(it.message)}</b></div>
                    ${d.ruleName ? `<div style="color:var(--text-dim)">${esc(d.ruleName)}</div>` : ''}
                    <div style="margin:8px 0;color:var(--text-dim);font-size:12px">
                        <code>${esc(it.rule)}</code> · ${esc(it.component)} · L${it.line} ·
                        <span class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</span> ·
                        status: <span style="color:${isMarked ? 'var(--ok)' : 'var(--text)'}; font-weight:bold;">${esc(it.status)}</span>
                    </div>

                    ${(d.snippet && d.snippet.length)
                        ? `<pre class="code">${highlightCode(d.snippet.join('\n'))}</pre>`
                        : `<div class="empty">(фрагмент кода недоступен)</div>`}

                    <div style="color:var(--text-dim);font-size:11px;margin-top:8px; margin-bottom: 16px;">source: ${esc(d.source)}</div>

                </div>`;

            if (!isMarked) {
                el.querySelector('#sn-btn-mark').addEventListener('click', () => {
                    // NEW: передаём ruleName и snippet для формирования промпта
                    openStatusModal(it, d.ruleName, d.snippet, ctx, () => this.mount(el, ctx));
                });
            } else {
                el.querySelector('#sn-btn-cancel').addEventListener('click', async () => {
                    if (!confirm(`Снять пометку (reopen) с issue ${it.key}?`)) return;

                    const btn = el.querySelector('#sn-btn-cancel');
                    btn.disabled = true;
                    btn.textContent = 'Отмена...';

                    try {
                        const p = new URLSearchParams(ctx.qs());
                        p.set('key', it.key);
                        p.set('dryRun', 'false');
                        await ctx.api('/api/cancel?' + p, { method: 'POST' });
                        toast('Пометка снята');
                        ctx.refreshStatus?.();
                        this.mount(el, ctx);
                    } catch (e) {
                        toast(e.message, true);
                        btn.disabled = false;
                        btn.textContent = 'Отменить комментарий';
                    }
                });
            }

        } catch (e) {
            el.innerHTML = `<div class="card"><div class="sev-HIGH">Ошибка: ${esc(e.message)}</div></div>`;
        }
    },
};

// ==================== ПРОМПТ ДЛЯ AI (комментарий к статусу Sonar) ====================
function buildStatusCommentPrompt(issue, ruleName, snippet, status, statusLabel) {
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
        '=== ПРИЗНАКИ СГЕНЕРИРОВАННОГО КОДА ===',
        'Файл считается СГЕНЕРИРОВАННЫМ, если ЛЮБОЙ из признаков верен:',
        '- путь содержит "/Api/" и имя файла заканчивается на "Api.php" (OpenAPI/Swagger client),',
        '- путь содержит "/Model/" (сгенерированные модели),',
        '- в коде есть маркеры: "ObjectSerializer", "handleResponseWithDataType", "ApiException",',
        '  "$this->config", "$this->headerSelector", "buildQuery", "ObjectSerializer::deserialize",',
        '- расширения: .pb.go, .pb.php, .pb.cc, .g.cs, *_generated.*, *_pb2.py, *-openapi.*, *.g.dart.',
        '',
        'ВАЖНО: сгенерированность файла — это КОНТЕКСТ, а не приговор.',
        'Решение о фиксе определяется ХАРАКТЕРОМ ДЕФЕКТА, а не происхождением файла.',
        '',
        '=== ПРИОРИТЕТ ПРАВИЛ (сверху вниз) ===',
        '1) РЕАЛЬНАЯ ЛОГИЧЕСКАЯ ОШИБКА — приоритет выше, чем "код сгенерирован".',
        '   Признаки (примеры):',
        '   - сравнение с заведомо невозможным значением: count($x) < 0,',
        '     strlen($x) < 0, size() < 0, index < 0 для неотрицательного индекса;',
        '     как следствие — недостижимая ветка throw/return/break (dead-code);',
        '   - разыменование null без проверки, где по контракту null недопустим;',
        '   - перепутаны == и =, инвертировано условие, неверный знак сравнения',
        '     (< вместо >, <= вместо <) в валидации входных данных;',
        '   - off-by-one в индексах/срезах, потеря или перезапись данных без сохранения;',
        '   - утечка ресурса, небезопасный парсинг пользовательского ввода;',
        '   - неверный тип поля в публичном контракте относительно OpenAPI-спеки.',
        '   Для таких дефектов: статус = accept или confirm, фикс-тег = "require-fix".',
        '   Исправлять нужно в коде, либо в спеке/генераторе — но дефект реален.',
        '2) СТИЛИСТИКА / СТРУКТУРА — статус = accept или wontfix,',
        '   фикс-тег = "not-require-fix". Дублирование (в т.ч. одинаковые case в switch),',
        '   отсутствие default в switch, длина методов, вложенность, naming,',
        '   отсутствие Javadoc, неиспользуемые импорты, магические числа —',
        '   всё, что исчезнет при регенерации и НЕ влияет на поведение.',
        '',
        '=== ЯЗЫКОВЫЕ СИГНАЛЫ (привязаны к типу дефекта) ===',
        'Фразы "нарушает читаемость но не ломает", "косметическая проблема",',
        '"стилистика", "не соответствует конвенции", "избыточно, но работает" —',
        'это сигнал поставить "not-require-fix".',
        'Фразы "условие никогда не выполнится", "недостижимый код", "dead code",',
        '"всегда false/true", "логическая ошибка", "ветка мертва",',
        '"потенциальный NullPointer/TypeError", "потеря данных", "неверный контракт" —',
        'это сигнал поставить "require-fix", даже если файл сгенерирован.',
        '',
        '=== РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===',
        'Верни РОВНО ОДИН из двух тегов в поле "Теги:"',
        '- "require-fix"     — дефект РЕАЛЬНЫЙ (логическая ошибка / dead-code /',
        '                        неверный контракт / потеря данных), НУЖНО исправить —',
        '                        независимо от того, сгенерирован файл или написан вручную.',
        '- "not-require-fix" — исправление не требуется: стилистика, дублирование,',
        '                        false positive, wontfix, шум сгенерированного кода',
        '                        без влияния на поведение.',
        '',
        '=== СВЯЗЬ СТАТУСА И ФИКС-ТЕГА ===',
        '- status=confirm       → require-fix',
        '- status=resolve       → not-require-fix (уже исправлено)',
        '- status=wontfix       → not-require-fix',
        '- status=falsepositive → not-require-fix',
        '- status=accept:',
        '    * ручной код + реальный баг          → require-fix',
        '    * сгенерированный код + реальный баг → require-fix   ← это важно',
        '    * ручной код + стилистика            → not-require-fix',
        '    * сгенерированный код + стилистика   → not-require-fix',
        '',
        '=== ЭТАЛОННЫЙ ПРИМЕР ===',
        'Файл: src/Model/AddDirsItems.php (сгенерирован OpenAPI-генератором)',
        'Правило: Array or Countable object count comparisons should make sense',
        'Код: if (!is_null($services) && (count($services) < 0)) { throw ... }',
        'Правильный ответ:',
        '  Статус: accept',
        '  Критичность: подтверждаю — count() всегда >= 0, ветка throw недостижима,',
        '    это dead-code и неверная валидация входных данных.',
        '  Теги: require-fix, code-smell',
        '  Обоснование: файл сгенерирован, но дефект реальный; проверка длины массива',
        '    не работает — надо исправить в коде или в спеке/генераторе.',
        '',
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
        'ВНИМАНИЕ: Слова "Статус:" и "Критичность:" должны встречаться в твоем ответе',
        'ТОЛЬКО ОДИН РАЗ (в самом начале). НЕ ПОВТОРЯЙ структуру ответа!',
        '1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"',
        '2. Строка 2: "Критичность: <вердикт> — <обоснование в одном предложении>"',
        '   Допустимые вердикты: подтверждаю, завышена, занижена, требует уточнения.',
        '3. Строка 3: "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"',
        '4. Строки 4+: 1–3 предложения (БЕЗ префиксов "Статус:" или "Критичность:"),',
        '   объясняющие суть проблемы и причину выбора.',
        '5. Пиши по-русски, БЕЗ markdown (никаких **, __, ###, списков).',
        '',
        'Верни ТОЛЬКО этот текст. Ничего кроме него.'
    );

    return lines.join('\n');
}

async function copyToClipboard(text) {
    if (navigator.clipboard && window.isSecureContext) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch (e) { /* fallback ниже */ }
    }
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-1000px';
    ta.style.left = '-1000px';
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    return ok;
}

function flashButton(btn, text, ms) {
    if (!btn) return;
    const original = btn.dataset.originalText || btn.textContent;
    btn.dataset.originalText = original;
    btn.textContent = text;
    btn.disabled = true;
    setTimeout(function () {
        btn.textContent = original;
        btn.disabled = false;
    }, ms || 1500);
}
function bindStatusPromptButton(overlay, issue, ruleName, snippet, ctx) {
    const btn = overlay.querySelector('#modal-btn-gen-prompt');
    if (!btn) return;
    btn.addEventListener('click', async function () {
        const sel = overlay.querySelector('#modal-status');
        if (!sel) return;
        const status = sel.value;
        const statusLabel = sel.selectedOptions[0]
            ? sel.selectedOptions[0].textContent.trim()
            : status;

        const prompt = buildStatusCommentPrompt(issue, ruleName, snippet, status, statusLabel);
        const ok = await copyToClipboard(prompt);

        if (ok) {
            flashButton(btn, 'Промпт скопирован!');
            // NEW: показываем, для какого статуса сгенерирован промпт
            toast('Промпт для статуса "' + status + '" скопирован в буфер обмена');
        } else {
            const ta = overlay.querySelector('#modal-comment');
            if (ta && !ta.value) {
                ta.value = prompt;
                ta.focus();
                ta.select();
            }
            flashButton(btn, 'Не удалось скопировать', 2000);
            toast('Промпт вставлен в поле комментария (статус "' + status + '")', true);
        }
    });
}

// ==================== МОДАЛЬНОЕ ОКНО СМЕНЫ СТАТУСА ====================
function openStatusModal(issue, ruleName, snippet, ctx, onDone) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 600px;">
            <div class="modal-header">
                <h3 style="margin:0;">Изменение статуса</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body">
                <p style="margin-top:0; color:var(--text-dim);">Выберите статус и оставьте комментарий для:<br><b>${esc(issue.key)}</b></p>

                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Статус перехода</label>
                    <select id="modal-status" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                        <option value="accept">Принять (Это ошибка, но исправлена будет позднее)</option>
                        <option value="falsepositive">Ложно (Анализ содержит ошибки)</option>
                        <option value="confirm">Подтвердить</option>
                        <option value="resolve">Исправлено</option>
                        <option value="wontfix">Не исправлять</option>
                    </select>
                </div>

                <div style="margin-bottom:16px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                    <textarea id="modal-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
                </div>

                <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                        <button id="modal-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM: сгенерировать короткий комментарий к выбранному статусу ошибки в SonarQube">Сгенерировать промпт</button>
                        <button id="modal-btn-ai-resolve" type="button" class="btn" title="Отправить промпт в Ollama и вставить ответ в поле «Комментарий»">Получить резолюцию ИИ</button>
                    </div>
                    <div style="display:flex; gap:8px; align-items:center;">
                        <button id="modal-cancel" class="btn">Отмена</button>
                        <button id="modal-apply" class="btn btn-accent">Применить</button>
                        <button id="modal-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
                    </div>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.querySelector('#modal-cancel').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    bindStatusPromptButton(overlay, issue, ruleName, snippet, ctx);

    bindOllamaResolveButton(overlay, {
        ctx,
        buildPrompt: (status, statusLabel) =>
            buildStatusCommentPrompt(issue, ruleName, snippet, status, statusLabel),
        commentSelector: '#modal-comment'
    });
    bindOllamaSettingsButton(overlay, ctx);

    overlay.querySelector('#modal-apply').addEventListener('click', async () => {
        const status = overlay.querySelector('#modal-status').value;
        const comment = overlay.querySelector('#modal-comment').value;

        const btn = overlay.querySelector('#modal-apply');
        btn.disabled = true;
        btn.textContent = 'Применение...';

        try {
            const p = new URLSearchParams(ctx.qs());
            p.set('key', issue.key);
            p.set('transition', status);
            p.set('comment', comment);
            p.set('dryRun', 'false');
            await ctx.api('/api/mark?' + p, { method: 'POST' });

            toast('Статус успешно изменен');
            close();
            ctx.refreshStatus?.();
            if (onDone) onDone();
        } catch (e) {
            toast(e.message, true);
            btn.disabled = false;
            btn.textContent = 'Применить';
        }
    });
}