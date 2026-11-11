import { esc, toast } from '../../../common.js';
import { bindOllamaResolveButton, bindOllamaSettingsButton } from '../../../ollama.js';
import { parseOllamaResponse } from '../parse-response.js';
import { buildBulkStatusCommentPrompt } from '../prompts.js';
import { runBulkWithProgress } from '../../shared/bulk-progress.js';
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
//   1) URL-сервер отклоняет keys=... длиннее ~8 KB
//   2) сервер делает 1 запрос SonarQube на каждый ключ
//   3) Ollama не умеет работать с промптом 200+ KB
//
// При issues.length > MAX_AI_ISSUES строим промпт локально:
//   - первые SAMPLE_SIZE issues — с полным кодом (через /api/snippet)
//   - остальные — только сводка по правилам и файлам
//   - в промпте явно говорим LLM, что группа больше выборки
// ============================================================
const MAX_AI_ISSUES  = 50;    // порог: выше — переключаемся на «выборку»
const SAMPLE_SIZE    = 15;    // сколько issues с кодом попадает в промпт
const MAX_FILE_LIST  = 20;    // сколько файлов показывать в сводке
const MAX_LINES_PER_SNIPPET = 40;   // обрезаем очень длинные сниппеты

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
                            В Ollama будет отправлена <b>выборка из ${SAMPLE_SIZE} issues с кодом</b>
                            и сводка по остальным <b>${issues.length - SAMPLE_SIZE}</b>.
                            В промпте явно указано, что группа больше — модель учтёт это
                            и применит решение ко всей группе.
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

        // ---- Сгенерировать промпт ----
        overlay.querySelector('#bulk-btn-gen-prompt').addEventListener('click', async () => {
            const status = overlay.querySelector('#bulk-status').value;
            const statusLabel = overlay.querySelector('#bulk-status').selectedOptions[0].textContent.trim();

            const btn = overlay.querySelector('#bulk-btn-gen-prompt');
            const orig = btn.textContent;
            btn.disabled = true;
            btn.textContent = aiWillBeCompact
                ? `⏳ Собираю ${SAMPLE_SIZE} сниппетов…`
                : '⏳ Готовлю промпт…';

            let prompt;
            try {
                prompt = aiWillBeCompact
                    ? await buildSampledPrompt(ctx, issues, status, statusLabel, availableTags)
                    : await buildBulkStatusCommentPrompt(ctx, issues, status, statusLabel);
            } catch (e) {
                toast('Не удалось построить промпт: ' + e.message, true);
                btn.disabled = false;
                btn.textContent = orig;
                return;
            } finally {
                btn.disabled = false;
                btn.textContent = orig;
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

        // ---- Получить резолюцию ИИ ----
        bindOllamaResolveButton(overlay, {
            ctx,
            buildPrompt: async (status, statusLabel) => {
                if (aiWillBeCompact) {
                    return await buildSampledPrompt(ctx, issues, status, statusLabel, availableTags);
                }
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

        // ---- Применить ----
        overlay.querySelector('#bulk-apply').addEventListener('click', async () => {
            const status = overlay.querySelector('#bulk-status').value;
            const comment = overlay.querySelector('#bulk-comment').value;
            const tagsStr = [...selectedTags].join(',');
            const tagsChanged = tagsStr !== (issues[0].tags || []).join(',');

            close();

            const result = await runBulkWithProgress({
                title: 'Массовое применение статуса',
                issues,
                perItemAction: async (it, _i, signal) => {
                    const p = new URLSearchParams(ctx.qs());
                    p.set('key', it.key);
                    p.set('transition', status);
                    p.set('comment', comment);
                    p.set('dryRun', 'false');
                    const res = await ctx.api('/api/mark?' + p, { method: 'POST', signal });
                    if (res.success === false) throw new Error(res.error || 'Ошибка');
                }
            });

            // Локально обновляем статус/теги у успешных — rawData.files
            // родительской вкладки perfile получит свежие данные.
            if (result.success > 0) {
                const failedKeys = new Set(result.failed.map(f => f.key));
                issues.forEach(it => {
                    if (!failedKeys.has(it.key)) {
                        it.status = status;
                        if (tagsChanged) it.tags = [...selectedTags];
                    }
                });
            }

            if (tagsChanged && result.success > 0) {
                const failedKeys = new Set(result.failed.map(f => f.key));
                const okKeys = issues.map(it => it.key).filter(k => !failedKeys.has(k));
                if (okKeys.length > 0) {
                    try {
                        const tagResult = await setIssueTags(ctx, okKeys, [...selectedTags]);
                        if (tagResult.errors && tagResult.errors.length > 0) {
                            ctx.toast(`Теги: ${tagResult.success}/${tagResult.total} успешно, ${tagResult.errors.length} с ошибкой`, true);
                        }
                    } catch (e) {
                        console.error('Ошибка применения тегов', e);
                        ctx.toast('Статусы применены, но теги не обновились: ' + e.message, true);
                    }
                }
            }

            if (result.cancelled) {
                ctx.toast(`Остановлено. Применено ${result.success} из ${issues.length}`, true);
            } else if (result.failed.length > 0) {
                ctx.toast(`Применено ${result.success} из ${issues.length}, ошибок: ${result.failed.length}`, true);
            } else {
                ctx.toast(`Применено к ${result.success} из ${issues.length}`);
            }

            ctx.refreshStatus?.();
            if (reloadCb) reloadCb();
        });
    })();
}

// ============================================================
// Промпт с выборкой: первые SAMPLE_SIZE issues с кодом + сводка остальных.
// Асинхронно тянет /api/snippet для SAMPLE_SIZE issues параллельно.
// ============================================================
async function buildSampledPrompt(ctx, issues, status, statusLabel, availableTags) {
    const total        = issues.length;
    const sampleIssues = issues.slice(0, SAMPLE_SIZE);
    const restIssues   = issues.slice(SAMPLE_SIZE);

    // --- 1. Параллельно получаем код для sample ---
    const sampleData = await Promise.all(sampleIssues.map(async (it) => {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        p.set('issueKey', it.key);

        try {
            const d = await ctx.api('/api/snippet?' + p);
            return {
                issue:    d.issue || it,
                snippet:  d.snippet || [],
                ruleName: d.ruleName || it.ruleName || ''
            };
        } catch (e) {
            return { issue: it, snippet: [], ruleName: it.ruleName || '' };
        }
    }));

    // --- 2. Сводка по остальным ---
    const restByRule = new Map();
    restIssues.forEach(it => {
        const r = it.rule || '(unknown)';
        if (!restByRule.has(r)) restByRule.set(r, []);
        restByRule.get(r).push(it);
    });

    const restFilesSet = new Set();
    restIssues.forEach(it => { if (it.component) restFilesSet.add(it.component); });
    const restFiles       = [...restFilesSet].sort();
    const shownFiles      = restFiles.slice(0, MAX_FILE_LIST);
    const hiddenFilesCnt  = Math.max(0, restFiles.length - MAX_FILE_LIST);

    // --- 3. Собираем промпт ---
    const lines = [];

    lines.push('Ты — опытный разработчик и эксперт по SonarQube.');
    lines.push('');
    lines.push(`ЗАДАЧА: обработать ГРУППУ из ${total} замечаний SonarQube (одинаковый статус для всех).`);
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
    lines.push(`ДОСТУПНЫЕ ТЕГИ (используй ТОЛЬКО из списка): ${(availableTags || []).join(', ') || 'любые релевантные'}`);
    lines.push('В поле "Теги:" верни СНАЧАЛА require-fix или not-require-fix,');
    lines.push('затем 1–2 тега СТРОГО из списка выше.');
    lines.push('');
    lines.push(`Пользователь выбрал статус: "${status}" — ${statusLabel || status}`);
    lines.push('Подтверди этот статус для всей группы или скорректируй, если он явно неверный.');
    lines.push('');

    // ---- Явное предупреждение, что группа больше выборки ----
    lines.push('=== ВАЖНО: ПАРТИЯ ПРЕВЫШАЕТ ЛИМИТ КОНТЕКСТА ===');
    lines.push(`Всего замечаний в группе: ${total}.`);
    lines.push(`В промпт включена ВЫБОРКА из ${sampleIssues.length} замечаний с кодом.`);
    if (restIssues.length > 0) {
        lines.push(`Остальные ${restIssues.length} замечаний — без кода, но относятся к тем же правилам/файлам.`);
        lines.push('При выработке решения считай, что вся группа СИСТЕМАТИЧЕСКИ одинакова по характеру:');
        lines.push('  • если выборка показывает стилистику/false positive → not-require-fix для ВСЕЙ группы;');
        lines.push('  • если выборка показывает реальный баг → require-fix для ВСЕЙ группы.');
        lines.push('Твой ответ применится ко всем ' + total + ' замечаниям, а не только к выборке.');
    }
    lines.push('');

    // ---- Выборка с кодом ----
    lines.push(`=== ВЫБОРКА С КОДОМ (${sampleIssues.length} из ${total}) ===`);
    lines.push('');

    for (let i = 0; i < sampleData.length; i++) {
        const { issue, snippet, ruleName } = sampleData[i];
        const loc = (issue.component || '?') + (issue.line > 0 ? ':' + issue.line : '');
        const sev = issue.impactSeverity || issue.severity || '';

        lines.push(`--- Issue ${i + 1}/${sampleIssues.length} ---`);
        lines.push(`Правило: ${issue.rule || '?'}${ruleName ? ' — ' + ruleName : ''}`);
        lines.push(`Файл: ${loc}`);
        if (sev) lines.push(`Severity: ${sev}`);
        lines.push(`Сообщение: ${(issue.message || '').slice(0, 300)}`);

        if (snippet && snippet.length > 0) {
            lines.push('Код:');
            lines.push('```');
            snippet.slice(0, MAX_LINES_PER_SNIPPET).forEach(line => lines.push(line));
            if (snippet.length > MAX_LINES_PER_SNIPPET) {
                lines.push(`... (ещё ${snippet.length - MAX_LINES_PER_SNIPPET} строк)`);
            }
            lines.push('```');
        } else {
            lines.push('(код недоступен)');
        }
        lines.push('');
    }

    // ---- Сводка по остальным ----
    if (restIssues.length > 0) {
        lines.push(`=== ОСТАЛЬНЫЕ ${restIssues.length} ISSUES (код не передан) ===`);
        lines.push('Эти замечания относятся к тем же правилам/файлам, что и выборка:');
        lines.push('');

        const rulesSorted = [...restByRule.entries()]
            .sort((a, b) => b[1].length - a[1].length);
        rulesSorted.forEach(([rule, list]) => {
            const filesCnt = new Set(list.map(x => x.component).filter(Boolean)).size;
            lines.push(`- ${rule} — ${list.length} замечаний в ${filesCnt} файлах`);
        });
        lines.push('');

        if (restFiles.length > 0) {
            lines.push(`Файлы с этими замечаниями (${restFiles.length}):`);
            shownFiles.forEach(f => lines.push(`  • ${f}`));
            if (hiddenFilesCnt > 0) {
                lines.push(`  … и ещё ${hiddenFilesCnt} файлов`);
            }
            lines.push('');
        }

        lines.push('=== КАК УЧЕСТЬ ОСТАЛЬНЫЕ ===');
        lines.push(`Твоё решение применится ко всем ${total} замечаниям сразу.`);
        lines.push(`Выборка из ${sampleIssues.length} issues выше показывает характер ошибок.`);
        lines.push('Если она репрезентативна — распространи вывод на всю группу.');
        lines.push('Если выборка смешанная — выбери статус/тег, который лучше всего');
        lines.push(`описывает большинство из ${total} замечаний.`);
        lines.push('');
    }

    lines.push('ФОРМАТ ОТВЕТА (СТРОГО):');
    lines.push('1. Строка 1: "Статус: <accept|falsepositive|confirm|resolve|wontfix>"');
    lines.push('2. Строка 2: "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"');
    lines.push('3. Строка 3: "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"');
    lines.push('4. Строки 4+: 1–3 предложения с общим выводом по группе.');
    lines.push('');
    lines.push('Пиши по-русски, без markdown. Верни ТОЛЬКО этот текст.');

    return lines.join('\n');
}