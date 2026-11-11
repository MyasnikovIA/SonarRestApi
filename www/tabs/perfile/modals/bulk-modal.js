import { esc, toast } from '../../../common.js';
import { bindOllamaResolveButton, bindOllamaSettingsButton } from '../../../ollama.js';
import { parseOllamaResponse } from '../parse-response.js';
import { buildBulkStatusCommentPrompt } from '../prompts.js';
import {
    PRIORITY_TAGS,
    fetchAvailableTags,
    buildOrderedTags,
    renderTagCheckboxes,
    setIssueTags,
    applyParsedTags
} from '../tags.js';

// ============================================================
// AI не может обработать тысячи issue за раз:
//   1) URL-сервер отклоняет keys=... длиннее ~8 KB (1520 ключей = 60 KB)
//   2) сервер делает 1 запрос SonarQube на каждый ключ (1520 × ~200 мс = 5 мин)
//   3) Ollama не умеет работать с промптом 200+ KB (контекст 4–8k токенов)
//
// Поэтому при issues.length > MAX_AI_ISSUES строим компактный промпт
// локально: группируем по rule и передаём в LLM сводку по правилам.
// ============================================================
const MAX_AI_ISSUES = 50;
const COMPACT_TOP_RULES = 20;    // сколько правил показывать в сводке
const COMPACT_SAMPLES_PER_RULE = 2;

export function openBulkModal(issues, ctx, reloadCb) {
    if (!issues.length) { toast('Нет доступных issues для изменения', true); return; }

    (async () => {
        const availableTags = await fetchAvailableTags(ctx);

        const currentTags = issues[0].tags || [];
        currentTags.forEach(t => { if (!availableTags.includes(t)) availableTags.push(t); });

        const initialTags = [...currentTags];
        const selectedTags = new Set(initialTags);
        let tagsQuery = '';

        const aiWillBeCompact = issues.length > MAX_AI_ISSUES;

        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        overlay.innerHTML = `
            <div class="modal-content" style="max-width: 600px;">
                <div class="modal-header">
                    <h3 style="margin:0;">Массовое действие</h3>
                    <button class="modal-close">&times;</button>
                </div>
                <div class="modal-body">
                    <p>Выбрано ошибок: <b>${issues.length}</b></p>

                    ${aiWillBeCompact ? `
                        <div style="margin-bottom:12px; padding:8px 10px;
                                    background:var(--bg3); border:1px solid var(--accent);
                                    border-radius:6px; font-size:12px; color:var(--text-dim);">
                            ℹ️ <b>${issues.length} issues</b> — больше лимита AI (${MAX_AI_ISSUES}).
                            При нажатии «Получить резолюцию ИИ» будет отправлена <b>сводка по правилам</b>
                            (топ-${COMPACT_TOP_RULES} + примеры), а не каждое замечание по отдельности.
                            Статус и теги из ответа применятся ко всей группе.
                        </div>
                    ` : ''}

                    <div style="margin-bottom:12px;">
                        <label style="display:block; font-size:12px; margin-bottom:4px;">Статус перехода</label>
                        <select id="bulk-status" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                            <option value="accept">Принять (Это ошибка, но исправлена будет позднее)</option>
                            <option value="falsepositive">Ложно (Анализ содержит ошибки)</option>
                            <option value="confirm">Подтвердить</option>
                            <option value="resolve">Исправлено</option>
                            <option value="wontfix">Не исправлять</option>
                        </select>
                    </div>
                    <div style="margin-bottom:12px;">
                        <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                        <textarea id="bulk-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
                    </div>
                    <div style="margin-bottom:16px;">
                        <label style="display:block; font-size:12px; margin-bottom:4px;">Теги</label>
                        <input type="text" id="bulk-tags-filter" placeholder="Фильтр по тегам..."
                               style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                                      border-radius:6px; border:1px solid var(--border);
                                      background:var(--bg); color:var(--text);">
                        <div id="bulk-tags-list"
                             style="max-height: 150px; overflow-y: auto; display: flex; flex-wrap: wrap;
                                    gap: 6px; padding: 8px; background: var(--bg3);
                                    border: 1px solid var(--border); border-radius: 6px;"></div>
                    </div>
                    <div id="bulk-ai-tags-hint" style="display:none; margin-bottom:12px; padding:8px; background:var(--bg3); border:1px solid var(--accent); border-radius:6px; font-size:12px;"></div>
                    <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                        <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                            <button id="bulk-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM">Сгенерировать промпт</button>
                            <button id="bulk-btn-ai-resolve" type="button" class="btn" title="Получить резолюцию от Ollama">Получить резолюцию ИИ</button>
                        </div>
                        <div style="display:flex; gap:8px; align-items:center;">
                            <button id="bulk-cancel" class="btn">Отмена</button>
                            <button id="bulk-apply" class="btn btn-accent">Применить</button>
                            <button id="bulk-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
                        </div>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(overlay);

        const close = () => overlay.remove();
        overlay.querySelector('.modal-close').addEventListener('click', close);
        overlay.querySelector('#bulk-cancel').addEventListener('click', close);
        overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

        function renderBulkTagList() {
            const ordered = buildOrderedTags(availableTags, initialTags, PRIORITY_TAGS, tagsQuery);
            renderTagCheckboxes(overlay.querySelector('#bulk-tags-list'), ordered, selectedTags);
        }

        renderBulkTagList();

        overlay.querySelector('#bulk-tags-filter').addEventListener('input', (e) => {
            tagsQuery = e.target.value;
            renderBulkTagList();
        });

        overlay.querySelector('#bulk-tags-list').addEventListener('change', (e) => {
            if (e.target.classList.contains('tag-cb')) {
                if (e.target.checked) selectedTags.add(e.target.value);
                else                  selectedTags.delete(e.target.value);
            }
        });

        // ============ Сгенерировать промпт (без AI) ============

        overlay.querySelector('#bulk-btn-gen-prompt').addEventListener('click', async () => {
            const status = overlay.querySelector('#bulk-status').value;
            const statusLabel = overlay.querySelector('#bulk-status').selectedOptions[0].textContent.trim();

            let prompt;
            try {
                prompt = aiWillBeCompact
                    ? buildCompactPrompt(issues, status, statusLabel, availableTags)
                    : await buildBulkStatusCommentPrompt(ctx, issues, status, statusLabel);
            } catch (e) {
                toast('Не удалось построить промпт: ' + e.message, true);
                return;
            }

            try {
                await navigator.clipboard.writeText(prompt);
                toast('Промпт скопирован в буфер обмена');
            } catch (e) {
                const ta = overlay.querySelector('#bulk-comment');
                ta.value = prompt; ta.focus(); ta.select();
                toast('Промпт вставлен в поле комментария', true);
            }
        });

        // ============ Получить резолюцию ИИ ============

        bindOllamaResolveButton(overlay, {
            ctx,
            buildPrompt: async (status, statusLabel) => {
                if (aiWillBeCompact) {
                    // Большая партия → компактный промпт по правилам
                    return buildCompactPrompt(issues, status, statusLabel, availableTags);
                }
                // Маленькая партия → обычный путь через сервер
                return buildBulkStatusCommentPrompt(ctx, issues, status, statusLabel);
            },
            commentSelector: '#bulk-comment',
            btnSelector: '#bulk-btn-ai-resolve',
            onComplete: (fullResponse) => {
                const parsed = parseOllamaResponse(fullResponse);

                const statusSel = overlay.querySelector('#bulk-status');
                if (statusSel && parsed.status) statusSel.value = parsed.status;

                const commentTa = overlay.querySelector('#bulk-comment');
                if (commentTa) commentTa.value = parsed.comment;

                if (parsed.tags && parsed.tags.length > 0) {
                    const { removed } = applyParsedTags(selectedTags, parsed.tags, availableTags);
                    renderBulkTagList();

                    const hint = overlay.querySelector('#bulk-ai-tags-hint');
                    hint.style.display = 'block';
                    let msg = `🤖 AI предлагает теги: <b>${esc(parsed.tags.join(', '))}</b>`;
                    if (removed.length > 0) {
                        msg += `<br><span style="color:var(--warn);">Снято (взаимоисключающие): ${esc(removed.join(', '))}</span>`;
                    }
                    msg += `<br><span style="color:var(--text-dim);"> (отмечены в списке выше)</span>`;
                    hint.innerHTML = msg;
                }
            }
        });
        bindOllamaSettingsButton(overlay, ctx, '#bulk-btn-ollama-settings');

        // ============ Применить ============

        overlay.querySelector('#bulk-apply').addEventListener('click', async () => {
            const status = overlay.querySelector('#bulk-status').value;
            const comment = overlay.querySelector('#bulk-comment').value;
            const tagsStr = [...selectedTags].join(',');
            const tagsChanged = tagsStr !== (issues[0].tags || []).join(',');

            const btn = overlay.querySelector('#bulk-apply');
            btn.disabled = true;
            btn.textContent = 'Применение...';

            let success = 0;
            for (const it of issues) {
                try {
                    const p = new URLSearchParams(ctx.qs());
                    p.set('key', it.key);
                    p.set('transition', status);
                    p.set('comment', comment);
                    p.set('dryRun', 'false');
                    const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                    if (res.success === false) throw new Error(res.error || 'Ошибка');
                    success++;
                } catch (e) {
                    console.error('Failed for ' + it.key, e);
                }
                btn.textContent = `Применение ${success}/${issues.length}...`;
            }

            if (tagsChanged) {
                try {
                    const keys = issues.map(it => it.key);
                    const result = await setIssueTags(ctx, keys, [...selectedTags]);
                    if (result.errors && result.errors.length > 0) {
                        const failed = result.errors.length;
                        const ok = result.success;
                        ctx.toast(`Теги: ${ok}/${result.total} успешно, ${failed} с ошибкой`, true);
                    }
                } catch (e) {
                    console.error('Ошибка применения тегов', e);
                    ctx.toast('Статусы применены, но теги не обновились: ' + e.message, true);
                }
            }

            ctx.toast(`Применено к ${success} из ${issues.length}`);
            close();
            ctx.refreshStatus?.();
            if (reloadCb) reloadCb();
        });
    })();
}

// ============================================================
// Компактный промпт для больших партий (issues.length > MAX_AI_ISSUES)
// ============================================================
function buildCompactPrompt(issues, status, statusLabel, availableTags) {
    // Группируем по правилу
    const byRule = new Map();
    issues.forEach(it => {
        const r = it.rule || '(unknown)';
        if (!byRule.has(r)) byRule.set(r, []);
        byRule.get(r).push(it);
    });

    const rulesSorted = [...byRule.entries()]
        .sort((a, b) => b[1].length - a[1].length);
    const totalRules = rulesSorted.length;
    const shownRules = rulesSorted.slice(0, COMPACT_TOP_RULES);

    const statusLabels = {
        accept:        'Принять (Это ошибка, но исправлена будет позднее)',
        falsepositive: 'Ложно (Анализ содержит ошибки)',
        confirm:       'Подтвердить',
        resolve:       'Исправлено',
        wontfix:       'Не исправлять'
    };
    const finalStatusLabel = statusLabel || statusLabels[status] || status;

    const tagsLine = (availableTags && availableTags.length)
        ? availableTags.join(', ')
        : 'любые релевантные';

    const lines = [];
    lines.push('Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).');
    lines.push('');
    lines.push(`ЗАДАЧА: обработать ГРУППУ из ${issues.length} замечаний (одинаковый статус для всех).`);
    lines.push('');
    lines.push('ОПРЕДЕЛЕНИЯ СТАТУСОВ:');
    lines.push('- falsepositive: SonarQube ошибся.');
    lines.push('- accept: реальная проблема, исправим позже.');
    lines.push('- confirm: реальная и срочная.');
    lines.push('- resolve: уже исправлено.');
    lines.push('- wontfix: не будем исправлять.');
    lines.push('');
    lines.push('=== РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===');
    lines.push('Верни РОВНО ОДИН из двух тегов в поле "Теги:"');
    lines.push('- "require-fix"     — группа содержит РЕАЛЬНЫЕ баги (логические ошибки, dead-code,');
    lines.push('                       неверный контракт, потеря данных), НУЖНО исправить.');
    lines.push('- "not-require-fix" — исправление не требуется: стилистика, дублирование,');
    lines.push('                       false positive, шум сгенерированного кода.');
    lines.push('');
    lines.push('=== СВЯЗЬ СТАТУСА И ФИКС-ТЕГА ===');
    lines.push('- status=confirm       → require-fix');
    lines.push('- status=resolve       → not-require-fix');
    lines.push('- status=wontfix       → not-require-fix');
    lines.push('- status=falsepositive → not-require-fix');
    lines.push('- status=accept        → require-fix (реальный баг) или not-require-fix (стилистика)');
    lines.push('');
    lines.push('ДОСТУПНЫЕ ТЕГИ (используй ТОЛЬКО из списка): ' + tagsLine);
    lines.push('В поле "Теги:" верни СНАЧАЛА require-fix или not-require-fix,');
    lines.push('затем 1–2 тега СТРОГО из списка выше.');
    lines.push('');
    lines.push(`Пользователь выбрал статус: "${status}" — ${finalStatusLabel}`);
    lines.push(`Подтверди этот статус для всей группы или скорректируй, если он явно неверный.`);
    lines.push('');
    lines.push(`=== СОДЕРЖИМОЕ ГРУППЫ ===`);
    lines.push(`Всего замечаний: ${issues.length}`);
    lines.push(`Уникальных правил: ${totalRules}`);
    if (totalRules > COMPACT_TOP_RULES) {
        lines.push(`Показаны ${COMPACT_TOP_RULES} самых частых правил (с примерами).`);
        lines.push(`Остальные ${totalRules - COMPACT_TOP_RULES} правил с малым количеством замечаний опущены для краткости.`);
    }
    lines.push('');
    lines.push('Замечания по правилам:');
    lines.push('');
    shownRules.forEach(([rule, list], idx) => {
        lines.push(`${idx + 1}. ${rule}  — ${list.length} замечаний`);
        const samples = list.slice(0, COMPACT_SAMPLES_PER_RULE);
        samples.forEach(s => {
            const loc = (s.component || '?') + (s.line > 0 ? ':' + s.line : '');
            const msg = (s.message || '').slice(0, 140);
            lines.push(`     • ${loc} — ${msg}`);
        });
        if (list.length > COMPACT_SAMPLES_PER_RULE) {
            lines.push(`     … и ещё ${list.length - COMPACT_SAMPLES_PER_RULE} в этом правиле`);
        }
        lines.push('');
    });
    lines.push('ФОРМАТ ОТВЕТА (СТРОГО):');
    lines.push('1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"');
    lines.push('2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"');
    lines.push('3. Строка 3: "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"');
    lines.push('4. Строки 4+: 1–3 предложения с общим выводом по группе.');
    lines.push('');
    lines.push('Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.');

    return lines.join('\n');
}