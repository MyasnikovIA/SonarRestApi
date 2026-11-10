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
        // Пользователь уже выбрал статус в UI — генерируем к нему комментарий
        lines.push(
            'ЗАДАЧА: Пользователь уже выбрал статус перехода в SonarQube:',
            '  "' + status + '" — ' + statusLabel,
            'Сгенерируй КОРОТКИЙ комментарий, объясняющий этот статус для данного кода.',
            'Статус в ответе должен быть РОВНО "' + status + '" (не меняй его).',
            ''
        );
    } else {
        // Статус не задан — модель выбирает сама
        lines.push(
            'ЗАДАЧА: Определи, есть ли в коде РЕАЛЬНЫЙ дефект, на который указало правило,',
            'и выбери корректный статус. Ты не обязан находить false positive —',
            'если проблема реальна, так и скажи.',
            '',
            'ОПРЕДЕЛЕНИЯ СТАТУСОВ (согласованы с UI SonarQube):',
            '- falsepositive: SonarQube ОШИБСЯ в анализе. В коде НЕТ проблемы,',
            '  на которую указало правило (примеры: параметризованный SQL выдан за',
            '  инъекцию; пустой catch с логированием через SLF4J; магическое число,',
            '  являющееся публичной константой фреймворка).',
            '- accept: РЕАЛЬНАЯ ошибка, которую исправят ПОЗЖЕ (техдолг, бэклог).',
            '  ЭТО СТАТУС ПО УМОЛЧАНИЮ для любой реальной, но НЕ срочной ошибки.',
            '- confirm: РЕАЛЬНАЯ и СРОЧНАЯ ошибка — security, потеря данных,',
            '  падение, некорректный контракт API.',
            '- resolve: ошибка уже исправлена в коде (виден fix).',
            '- wontfix: РЕАЛЬНАЯ ошибка, но решено НЕ исправлять (генерированный код,',
            '  архитектурное решение).',
            '',
            'КРИТИЧЕСКИ ВАЖНО:',
            '1. Сообщение правила само описывает конкретный дефект',
            '   ("count() always >=0, update this test", "condition always true",',
            '   "unused variable") — это РЕАЛЬНАЯ проблема, НЕ falsepositive.',
            '   Ставь accept по умолчанию.',
            '2. Сгенерированный код (OpenAPI, protobuf, ORM) — wontfix или accept,',
            '   НЕ falsepositive.',
            '3. Мёртвый код (count()<0, always-false/true, недостижимые ветки) —',
            '   реальная ошибка, accept (или wontfix), НЕ falsepositive.',
            '4. Не путай accept и confirm:',
            '   accept  = "исправим позже" (техдолг);',
            '   confirm = "исправляем прямо сейчас" (security/data loss/crash/контракт).',
            '   Сомневаешься — выбирай accept.',
            ''
        );
    }

    // Общий хвост для обоих режимов
    lines.push(
        'АНАЛИЗ КРИТИЧНОСТИ:',
        'SonarQube оценил как: **' + (severity || 'не указана') + '**',
        '- HIGH/CRITICAL/BLOCKER + код реально безопасен → falsepositive.',
        '- LOW/INFO + проблема реально опасна → confirm или accept.',
        '- MEDIUM и любая другая — по сути (accept/confirm/wontfix/falsepositive).',
        '',
        'ЯЗЫКОВЫЕ ОСОБЕННОСТИ (примеры):',
        '- PHP: дублирование строк в OpenAPI — wontfix (генерированный код).',
        '- PHP: count() < 0 — реальная ошибка (мёртвая проверка), accept.',
        '- Java: пустой catch без логирования — реальная ошибка, accept.',
        '- Java: пустой catch с логированием SLF4J — falsepositive.',
        '- JS/TS: == vs === — реальная ошибка, accept (confirm, если в auth/security).',
        '- SQL: SELECT * — accept для мелкого; confirm для тяжёлого join на проде.',
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
        '3. Строка 3 и далее: 1–3 предложения (БЕЗ префиксов "Статус:" или',
        '   "Критичность:"), объясняющие суть проблемы и причину выбора.',
        '4. Пиши по-русски, БЕЗ markdown (никаких **, __, ###, списков).',
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