import { esc, toast, showTagEditor } from '../../../common.js';
import { OllamaClient, openOllamaSettingsModal } from '../../../ollama.js';
import { parseOllamaResponse } from '../parse-response.js';
import { buildAutoResolvePromptAsync } from '../prompts.js';
import {
    PRIORITY_TAGS,
    fetchAvailableTags,
    buildOrderedTags,
    renderTagCheckboxes,
    setIssueTags,
    applyParsedTags
} from '../tags.js';

export function openAutoBulkModal(issues, ctx, reloadCb) {
    if (!issues.length) { toast('Нет доступных issues для изменения', true); return; }

    (async () => {
        const availableTags = await fetchAvailableTags(ctx);
        for (const it of issues) {
            (it.tags || []).forEach(t => {
                if (!availableTags.includes(t)) availableTags.push(t);
            });
        }

        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        overlay.innerHTML = `
            <div class="modal-content" style="max-width: 800px;">
                <div class="modal-header">
                    <h3 style="margin:0;" id="auto-header">Автоматическая обработка (0 / ${issues.length})</h3>
                    <button class="modal-close">&times;</button>
                </div>
                <div class="modal-body">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 16px;">
                        <div id="auto-progress" style="font-weight: bold; color: var(--accent);">
                            Ожидание запуска...
                        </div>
                        <label style="display:flex; align-items:center; gap:6px; font-size:13px; cursor:pointer; user-select:none;">
                            <input type="checkbox" id="auto-chk-semi" checked style="width:16px; height:16px; cursor:pointer;">
                            Полуавтоматический режим (с подтверждением)
                        </label>
                    </div>

                    <div class="card" style="margin-bottom: 16px; border-color: var(--accent);">
                        <div style="font-size: 12px; color: var(--text-dim); margin-bottom: 8px;">
                            Текущая ошибка: <b id="auto-current-key">-</b> (Файл: <span id="auto-current-file">-</span>)
                        </div>

                        <div id="auto-current-msg" style="margin-bottom: 12px; font-size: 14px; font-weight: 500; line-height: 1.4; color: var(--text);">
                            -
                        </div>

                        <div style="margin-bottom:12px;">
                            <label style="display:block; font-size:12px; margin-bottom:4px;">Статус перехода</label>
                            <select id="auto-status" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                                <option value="accept">Принять (Это ошибка, но исправлена будет позднее)</option>
                                <option value="falsepositive">Ложно (Анализ содержит ошибки)</option>
                                <option value="confirm">Подтвердить</option>
                                <option value="resolve">Исправлено</option>
                                <option value="wontfix">Не исправлять</option>
                            </select>
                        </div>

                        <div style="margin-bottom:12px;">
                            <label style="display:block; font-size:12px; margin-bottom:4px;">Комментарий</label>
                            <textarea id="auto-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите многострочный комментарий..."></textarea>
                        </div>

                        <div id="auto-ai-tags-hint" style="display:none; margin-bottom:12px; padding:8px; background:var(--bg3); border:1px solid var(--accent); border-radius:6px; font-size:12px;"></div>

                        <div style="margin-bottom:12px;">
                            <label style="display:block; font-size:12px; margin-bottom:4px;">Теги</label>
                            <input type="text" id="auto-tags-filter" placeholder="Фильтр по тегам..."
                                   style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                                          border-radius:6px; border:1px solid var(--border);
                                          background:var(--bg); color:var(--text);">
                            <div id="auto-tags-list"
                                 style="max-height:140px; overflow-y:auto; display:flex; flex-wrap:wrap;
                                        gap:6px; padding:8px; background:var(--bg3);
                                        border:1px solid var(--border); border-radius:6px;"></div>
                        </div>

                        <div style="display:flex; gap:10px; flex-wrap:wrap;">
                            <button id="auto-btn-start" class="btn btn-accent">▶️ Запустить</button>
                            <button id="auto-btn-apply" class="btn" disabled>✅ Подтвердить и далее</button>
                            <button id="auto-btn-skip" class="btn" disabled>⏭️ Пропустить</button>
                            <button id="auto-btn-stop" class="btn btn-danger" style="display:none;">⏹️ Остановить</button>
                            <button id="auto-btn-tags" type="button" class="btn">🏷️ Изменить теги (в отдельном окне)</button>
                            <button id="auto-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
                        </div>
                    </div>

                    <div style="display:flex; justify-content:space-between; align-items:center;">
                        <span id="auto-log" style="font-size:12px; color:var(--text-dim);">Нажмите «Запустить» для начала</span>
                        <button id="auto-btn-cancel" class="btn">Закрыть</button>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(overlay);

        const ui = {
            header: overlay.querySelector('#auto-header'),
            progress: overlay.querySelector('#auto-progress'),
            chkSemi: overlay.querySelector('#auto-chk-semi'),
            currentKey: overlay.querySelector('#auto-current-key'),
            currentFile: overlay.querySelector('#auto-current-file'),
            currentMsg: overlay.querySelector('#auto-current-msg'),
            statusSel: overlay.querySelector('#auto-status'),
            commentTa: overlay.querySelector('#auto-comment'),
            btnStart: overlay.querySelector('#auto-btn-start'),
            btnApply: overlay.querySelector('#auto-btn-apply'),
            btnSkip: overlay.querySelector('#auto-btn-skip'),
            btnStop: overlay.querySelector('#auto-btn-stop'),
            btnTags: overlay.querySelector('#auto-btn-tags'),
            aiTagsHint: overlay.querySelector('#auto-ai-tags-hint'),
            tagsFilter: overlay.querySelector('#auto-tags-filter'),
            tagsList: overlay.querySelector('#auto-tags-list'),
            log: overlay.querySelector('#auto-log')
        };

        let currentIndex = 0;
        let isProcessing = false;
        let hasGenerated = false;
        let abortController = null;
        let currentSuggestedTags = [];
        let selectedTags = new Set();
        let tagsQuery = '';

        function renderAutoTagList() {
            const it = issues[currentIndex];
            const initialTags = it ? (it.tags || []) : [];
            const ordered = buildOrderedTags(availableTags, initialTags, PRIORITY_TAGS, tagsQuery);
            renderTagCheckboxes(ui.tagsList, ordered, selectedTags);
        }

        function loadCurrentTags() {
            const it = issues[currentIndex];
            selectedTags = new Set(it ? (it.tags || []) : []);
            tagsQuery = '';
            if (ui.tagsFilter) ui.tagsFilter.value = '';
            ui.aiTagsHint.style.display = 'none';
            ui.aiTagsHint.innerHTML = '';
            renderAutoTagList();
        }

        ui.tagsFilter.addEventListener('input', (e) => {
            tagsQuery = e.target.value;
            renderAutoTagList();
        });

        ui.tagsList.addEventListener('change', (e) => {
            if (e.target.classList.contains('tag-cb')) {
                if (e.target.checked) selectedTags.add(e.target.value);
                else                  selectedTags.delete(e.target.value);
            }
        });

        loadCurrentTags();

        const close = () => {
            if (isProcessing || hasGenerated) {
                if (!confirm('Процесс не завершен. Вы уверены, что хотите выйти?')) return;
            }
            if (abortController) abortController.abort();
            overlay.remove();
            if (reloadCb) reloadCb();
        };

        overlay.querySelector('.modal-close').addEventListener('click', close);
        overlay.querySelector('#auto-btn-cancel').addEventListener('click', close);

        ui.btnTags.addEventListener('click', () => {
            if (currentIndex >= issues.length) return;
            const it = issues[currentIndex];
            showTagEditor([it], ctx, async () => {
                try {
                    const p = new URLSearchParams();
                    if (ctx.state.project) p.set('project', ctx.state.project);
                    p.set('issueKey', it.key);
                    const d = await ctx.api('/api/snippet?' + p);
                    if (d.issue && Array.isArray(d.issue.tags)) {
                        it.tags = d.issue.tags;
                        it.tags.forEach(t => {
                            if (!availableTags.includes(t)) availableTags.push(t);
                        });
                    }
                } catch (e) {
                    console.warn('Не удалось перечитать теги:', e);
                }
                loadCurrentTags();
            });
        });

        function updateUI() {
            if (currentIndex >= issues.length) {
                ui.header.textContent = `Обработка завершена (${issues.length} / ${issues.length})`;
                ui.btnStart.style.display = 'none';
                ui.btnApply.disabled = true;
                ui.btnSkip.disabled = true;
                ui.btnStop.style.display = 'none';
                ui.progress.textContent = 'Все ошибки обработаны.';
                return;
            }

            ui.header.textContent = `Автоматическая обработка (${currentIndex + 1} / ${issues.length})`;
            const it = issues[currentIndex];
            ui.currentKey.textContent = it.key;
            ui.currentFile.textContent = it.component;
            ui.currentMsg.textContent = it.message || 'Нет описания ошибки';

            if (isProcessing) {
                ui.btnStart.style.display = 'none';
                ui.btnStop.style.display = 'inline-block';
                ui.btnApply.disabled = true;
                ui.btnSkip.disabled = true;
            } else if (hasGenerated) {
                ui.btnStart.style.display = 'none';
                ui.btnStop.style.display = 'none';
                ui.btnApply.disabled = false;
                ui.btnSkip.disabled = false;
            } else {
                ui.btnStart.style.display = 'inline-block';
                ui.btnStop.style.display = 'none';
                ui.btnApply.disabled = true;
                ui.btnSkip.disabled = true;
            }
        }

        async function processNext() {
            if (isProcessing || currentIndex >= issues.length) return;
            isProcessing = true;
            updateUI();

            const it = issues[currentIndex];
            ui.progress.textContent = 'Анализ кода...';
            ui.commentTa.value = 'Генерация промпта...';
            ui.log.textContent = 'Подготовка данных...';

            try {
                const prompt = await buildAutoResolvePromptAsync(ctx, it.key);

                const settings = OllamaClient.getSettings();
                if (!settings.url || !settings.model) throw new Error("Ollama не настроена. Нажмите ⚙ Ollama.");

                console.groupCollapsed('[Ollama AUTO] request ' + it.key);
                console.log('URL:', settings.url);
                console.log('Model:', settings.model);
                console.log('Prompt length:', prompt.length, 'chars');
                console.log('--- PROMPT BEGIN ---');
                console.log(prompt);
                console.log('--- PROMPT END ---');
                console.groupEnd();

                ui.progress.textContent = 'Запрос к Ollama...';
                ui.log.textContent = 'Ожидание ответа от модели...';

                abortController = new AbortController();
                const client = new OllamaClient(settings);
                let fullResponse = '';

                for await (const chunk of client.streamChat({ prompt, signal: abortController.signal })) {
                    fullResponse += chunk;
                    ui.commentTa.value = fullResponse;
                }

                console.groupCollapsed('[Ollama AUTO] response ' + it.key);
                console.log(fullResponse);
                console.groupEnd();

                const parsed = parseOllamaResponse(fullResponse);
                ui.statusSel.value = parsed.status;
                ui.commentTa.value = parsed.comment;

                currentSuggestedTags = parsed.tags || [];
                if (currentSuggestedTags.length > 0) {
                    const { removed } = applyParsedTags(selectedTags, currentSuggestedTags, availableTags);
                    renderAutoTagList();

                    ui.aiTagsHint.style.display = 'block';
                    let msg = `🤖 AI предлагает теги: <b>${esc(currentSuggestedTags.join(', '))}</b>`;
                    if (removed.length > 0) {
                        msg += `<br><span style="color:var(--warn);">Снято (взаимоисключающие): ${esc(removed.join(', '))}</span>`;
                    }
                    msg += `<br><span style="color:var(--text-dim);"> (отмечены в списке ниже — поправьте при необходимости)</span>`;
                    ui.aiTagsHint.innerHTML = msg;
                }

                hasGenerated = true;
                ui.progress.textContent = 'Готово к применению';
                isProcessing = false;
                updateUI();

                if (ui.chkSemi.checked) {
                    ui.log.textContent = 'Проверьте данные и нажмите «Подтвердить» или «Пропустить».';
                } else {
                    ui.log.textContent = 'Автоматический режим. Применение через 1 сек...';
                    setTimeout(() => {
                        if (!ui.chkSemi.checked && hasGenerated && !isProcessing) {
                            applyCurrent();
                        }
                    }, 1000);
                }

            } catch (e) {
                if (e.name === 'AbortError') {
                    ui.log.textContent = 'Остановлено пользователем.';
                    ui.progress.textContent = 'Пауза.';
                    isProcessing = false;
                    hasGenerated = false;
                    updateUI();
                    return;
                }
                ui.log.textContent = 'Ошибка: ' + e.message;
                ui.progress.textContent = 'Остановлено из-за ошибки Ollama';
                isProcessing = false;
                hasGenerated = false;
                updateUI();
                toast('Ошибка Ollama: ' + e.message, true);
            } finally {
                abortController = null;
            }
        }

        async function applyCurrent() {
            if (isProcessing || currentIndex >= issues.length) return;
            isProcessing = true;
            updateUI();

            const it = issues[currentIndex];
            const status = ui.statusSel.value;
            const comment = ui.commentTa.value;

            ui.log.textContent = 'Применение...';

            try {
                const p = new URLSearchParams(ctx.qs());
                p.set('key', it.key);
                p.set('transition', status);
                p.set('comment', comment);
                p.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + p, { method: 'POST' });
                if (res.success === false) throw new Error(res.error || 'Ошибка применения');

                const tagsStr = [...selectedTags].join(',');
                const tagsChanged = tagsStr !== (it.tags || []).join(',');
                if (tagsChanged) {
                    try {
                        const result = await setIssueTags(ctx, it.key, [...selectedTags]);
                        if (result.errors && result.errors.length > 0) {
                            toast('Теги не установлены: ' + result.errors[0].message, true);
                        }
                    } catch (e) {
                        console.warn('Не удалось применить теги:', e);
                        toast('Теги не установлены: ' + e.message, true);
                    }
                }

                it.status = status;
                it.tags = [...selectedTags];

                currentIndex++;
                hasGenerated = false;
                currentSuggestedTags = [];
                ui.commentTa.value = '';
                ui.log.textContent = 'Успешно применено. Переход к следующей...';

                isProcessing = false;
                loadCurrentTags();
                updateUI();

                if (currentIndex < issues.length) {
                    processNext();
                }

            } catch (e) {
                ui.log.textContent = 'Ошибка применения: ' + e.message;
                isProcessing = false;
                updateUI();
                toast(e.message, true);
            }
        }

        function skipCurrent() {
            if (currentIndex >= issues.length) return;
            currentIndex++;
            hasGenerated = false;
            currentSuggestedTags = [];
            ui.commentTa.value = '';
            ui.log.textContent = 'Пропущено пользователем.';
            loadCurrentTags();
            updateUI();
            if (currentIndex < issues.length) {
                processNext();
            }
        }

        ui.btnStart.addEventListener('click', () => {
            if (currentIndex >= issues.length) return;
            processNext();
        });

        ui.btnStop.addEventListener('click', () => {
            if (abortController) abortController.abort();
        });

        ui.btnApply.addEventListener('click', () => {
            applyCurrent();
        });

        ui.btnSkip.addEventListener('click', () => {
            skipCurrent();
        });

        ui.chkSemi.addEventListener('change', () => {
            if (!ui.chkSemi.checked && hasGenerated && !isProcessing) {
                ui.log.textContent = 'Режим изменен на автоматический. Применение...';
                setTimeout(() => {
                    if (!ui.chkSemi.checked && hasGenerated && !isProcessing) {
                        applyCurrent();
                    }
                }, 500);
            }
        });

        overlay.querySelector('#auto-btn-ollama-settings').addEventListener('click', () => openOllamaSettingsModal(ctx));

        updateUI();
    })();
}