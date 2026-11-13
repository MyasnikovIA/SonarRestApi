import { esc, toast, highlightCode } from '../../../common.js';
import { bindOllamaResolveButton, bindOllamaSettingsButton } from '../../../ollama.js';
import { getMappedStatus, getReopenTransition } from '../../perfile/status.js';
import { parseOllamaResponse } from '../../perfile/parse-response.js';
import { buildStatusCommentPrompt } from '../../perfile/prompts.js';
import { bindStatusPromptButton } from '../../perfile/helpers.js';
import {
    PRIORITY_TAGS,
    fetchAvailableTags,
    buildOrderedTags,
    renderTagCheckboxes,
    setIssueTags,
    applyParsedTags
} from '../../perfile/tags.js';

// ---- Человекочитаемые названия полей SonarQube ----
const FIELD_LABELS = {
    'status':     'Статус',
    'resolution': 'Резолюция',
    'assignee':   'Исполнитель',
    'tags':       'Теги',
    'severity':   'Критичность',
    'type':       'Тип',
    'file':       'Файл',
    'rule':       'Правило',
    'comment':    'Комментарий',
};

function fieldLabel(key) {
    return FIELD_LABELS[key] || key;
}

function formatDate(iso) {
    if (!iso) return '';
    try { return new Date(iso).toLocaleString('ru-RU'); }
    catch { return iso; }
}

function renderTimelineEvent(e) {
    const dateStr = formatDate(e.date);

    if (e.type === 'comment') {
        return `
            <div style="margin-bottom:10px; padding-bottom:10px; border-bottom:1px solid var(--border);">
                <div style="margin-bottom:4px; display:flex; align-items:center; gap:6px; flex-wrap:wrap;">
                    <span>💬</span>
                    <span style="color:var(--accent); font-weight:bold;">${esc(e.userName)}</span>
                    <span style="color:var(--text-dim);">${esc(dateStr)}</span>
                </div>
                <div style="background:var(--bg); border-left:3px solid var(--accent);
                            padding:6px 10px; border-radius:0 4px 4px 0;
                            white-space:pre-wrap;">${esc(e.text)}</div>
            </div>
        `;
    }

    const hasStatusChange = (e.diffs || []).some(d => d.key === 'status');
    const icon = hasStatusChange ? '🔄' : '✏️';

    const diffsHtml = (e.diffs || []).map(diff => {
        const label = fieldLabel(diff.key);
        const oldVal = diff.oldValue || '—';
        const newVal = diff.newValue || '—';
        return `
            <div style="margin:2px 0 2px 20px;">
                <span style="color:var(--text-dim);">${esc(label)}:</span>
                <span style="color:var(--text-dim); text-decoration:line-through;">${esc(oldVal)}</span>
                &rarr;
                <b style="color:var(--text);">${esc(newVal)}</b>
            </div>
        `;
    }).join('');

    return `
        <div style="margin-bottom:10px; padding-bottom:10px; border-bottom:1px solid var(--border);">
            <div style="margin-bottom:4px; display:flex; align-items:center; gap:6px; flex-wrap:wrap;">
                <span>${icon}</span>
                <span style="color:var(--accent); font-weight:bold;">${esc(e.userName)}</span>
                <span style="color:var(--text-dim);">${esc(dateStr)}</span>
            </div>
            ${diffsHtml}
        </div>
    `;
}

export async function openReportIssueModal(issue, ctx, reloadCb) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 900px;">
            <div class="modal-header">
                <h3 style="margin:0;">Детали ошибки</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body" id="rep-modal-body">
                <div class="empty">Загрузка...</div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    try {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        p.set('issueKey', issue.key);

        // Параллельно: данные issue + changelog + список тегов
        const [d, ch, availableTags] = await Promise.all([
            ctx.api('/api/snippet?' + p),
            ctx.api('/api/changelog?' + p).catch(() => ({ changelog: [] })),
            fetchAvailableTags(ctx)
        ]);

        const it = d.issue || issue;
        const changelog = ch.changelog || [];
        const comments = it.comments || [];
        const currentTags = it.tags || [];
        currentTags.forEach(t => { if (!availableTags.includes(t)) availableTags.push(t); });

        const initialTags = [...currentTags];
        const selectedTags = new Set(initialTags);
        let tagsQuery = '';

        // --- Единый таймлайн ---
        const events = [];
        changelog.forEach(entry => {
            events.push({
                type: 'change',
                userName: entry.userName || entry.user || 'Пользователь',
                date: entry.creationDate,
                diffs: entry.diffs || []
            });
        });
        comments.forEach(c => {
            events.push({
                type: 'comment',
                userName: c.login || 'Пользователь',
                date: c.createdAt,
                text: c.markdown || c.htmlText || ''
            });
        });
        // Хронологический порядок: сначала старые, потом новые
        events.sort((a, b) => {
            const da = a.date ? new Date(a.date).getTime() : 0;
            const db = b.date ? new Date(b.date).getTime() : 0;
            return da - db;
        });

        const currentStatusValue = getMappedStatus(it.status);
        const canReopen = it.status && it.status !== 'OPEN' && it.status !== 'REOPENED';

        const timelineHtml = events.length > 0
            ? `<div style="background:var(--bg3); border:1px solid var(--border);
                          border-radius:6px; padding:12px; max-height:280px;
                          overflow-y:auto; font-size:12px;">
                ${events.map(renderTimelineEvent).join('')}
               </div>`
            : `<div class="empty">Нет истории изменений</div>`;

        const body = overlay.querySelector('#rep-modal-body');
        body.innerHTML = `
            <div style="margin-bottom:12px;">
                <b style="font-size:16px;">${esc(it.message)}</b>
                <div style="color:var(--text-dim); margin-top:4px;">${esc(d.ruleName || it.rule)}</div>
            </div>

            <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:16px; font-size:12px; color:var(--text-dim);">
                <div><b>Файл:</b> ${esc(it.component)}</div>
                <div><b>Строка:</b> ${it.line > 0 ? it.line : '— (весь файл)'}</div>
                <div><b>Severity:</b> <span class="sev-${esc(it.impactSeverity || it.severity || '')}">${esc(it.impactSeverity || it.severity)}</span></div>
                <div><b>Текущий статус:</b> ${esc(it.status)}</div>
            </div>

            ${(d.snippet && d.snippet.length)
                ? `<pre class="code" style="max-height:300px; overflow:auto;">${highlightCode(d.snippet.join('\n'))}</pre>`
                : `<div class="empty">Код недоступен</div>`}

            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px;">Теги</label>
                <input type="text" id="rep-modal-tags-filter" placeholder="Фильтр по тегам..."
                       style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                              border-radius:6px; border:1px solid var(--border);
                              background:var(--bg); color:var(--text);">
                <div id="rep-modal-tags-list"
                     style="max-height:180px; overflow-y:auto; display:flex; flex-wrap:wrap;
                            gap:6px; padding:8px; background:var(--bg3);
                            border:1px solid var(--border); border-radius:6px;"></div>
            </div>

            <div id="rep-modal-ai-tags-hint"
                 style="display:none; margin-top:12px; padding:8px;
                        background:var(--bg3); border:1px solid var(--accent);
                        border-radius:6px; font-size:12px;"></div>

            <div style="margin-top:16px;">
                <label style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                    📜 История изменений (${events.length})
                </label>
                ${timelineHtml}
            </div>

            <div style="margin-top:20px; padding-top:16px; border-top:1px solid var(--border);">
                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Статус перехода</label>
                    <select id="rep-modal-status" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                        <option value="accept" ${currentStatusValue === 'accept' ? 'selected' : ''}>Принять (Это ошибка, но исправлена будет позднее)</option>
                        <option value="falsepositive" ${currentStatusValue === 'falsepositive' ? 'selected' : ''}>Ложно (Анализ содержит ошибки)</option>
                        <option value="confirm" ${currentStatusValue === 'confirm' ? 'selected' : ''}>Подтвердить</option>
                        <option value="resolve" ${currentStatusValue === 'resolve' ? 'selected' : ''}>Исправлено</option>
                        <option value="wontfix" ${currentStatusValue === 'wontfix' ? 'selected' : ''}>Не исправлять</option>
                    </select>
                </div>
                <div>
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Новый комментарий</label>
                    <textarea id="rep-modal-comment" rows="4" style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;" placeholder="Введите новый комментарий..."></textarea>
                </div>
                <div style="margin-top:16px; display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap;">
                    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                        <button id="rep-modal-btn-gen-prompt" type="button" class="btn" title="Сформировать промпт для LLM">Сгенерировать промпт</button>
                        <button id="rep-modal-btn-ai-resolve" type="button" class="btn" title="Отправить промпт в Ollama">Получить резолюцию ИИ</button>
                        ${canReopen ? `<button id="rep-modal-btn-reopen" class="btn btn-danger">Reopen</button>` : ''}
                    </div>
                    <div style="display:flex; gap:8px; align-items:center;">
                        <button id="rep-modal-btn-cancel" class="btn">Отмена</button>
                        <button id="rep-modal-btn-apply" class="btn btn-accent">Применить</button>
                        <button id="rep-modal-btn-ollama-settings" type="button" class="btn" title="Настройки Ollama">⚙ Ollama</button>
                    </div>
                </div>
            </div>
        `;

        // ---- Теги ----
        function renderModalTagList() {
            const ordered = buildOrderedTags(availableTags, initialTags, PRIORITY_TAGS, tagsQuery);
            renderTagCheckboxes(overlay.querySelector('#rep-modal-tags-list'), ordered, selectedTags);
        }
        renderModalTagList();

        overlay.querySelector('#rep-modal-tags-filter').addEventListener('input', (e) => {
            tagsQuery = e.target.value;
            renderModalTagList();
        });

        overlay.querySelector('#rep-modal-tags-list').addEventListener('change', (e) => {
            if (e.target.classList.contains('tag-cb')) {
                if (e.target.checked) selectedTags.add(e.target.value);
                else                  selectedTags.delete(e.target.value);
            }
        });

        // ---- Промпт ----
        bindStatusPromptButton(overlay, it, d.ruleName, d.snippet, ctx);

        // ---- Ollama ----
        bindOllamaResolveButton(overlay, {
            ctx,
            buildPrompt: (status, statusLabel) =>
                buildStatusCommentPrompt(ctx, it.key, status, statusLabel),
            commentSelector: '#rep-modal-comment',
            btnSelector: '#rep-modal-btn-ai-resolve',
            onComplete: (fullResponse) => {
                const parsed = parseOllamaResponse(fullResponse);

                const statusSelect = overlay.querySelector('#rep-modal-status');
                if (statusSelect && parsed.status) statusSelect.value = parsed.status;

                const commentTa = overlay.querySelector('#rep-modal-comment');
                if (commentTa) commentTa.value = parsed.comment;

                if (parsed.tags && parsed.tags.length > 0) {
                    const { removed } = applyParsedTags(selectedTags, parsed.tags, availableTags);
                    renderModalTagList();

                    const hint = overlay.querySelector('#rep-modal-ai-tags-hint');
                    hint.style.display = 'block';
                    let msg = `🤖 AI предлагает теги: <b>${esc(parsed.tags.join(', '))}</b>`;
                    if (removed.length > 0) {
                        msg += `<br><span style="color:var(--warn);">Снято (взаимоисключающие): ${esc(removed.join(', '))}</span>`;
                    }
                    msg += `<br><span style="color:var(--text-dim);"> (отмечены в списке выше — поправьте при необходимости)</span>`;
                    hint.innerHTML = msg;
                }
            }
        });
        bindOllamaSettingsButton(overlay, ctx, '#rep-modal-btn-ollama-settings');

        // ---- Применить (сохраняет всё: и ручное, и от Ollama) ----
        overlay.querySelector('#rep-modal-btn-apply').addEventListener('click', async () => {
            const status = overlay.querySelector('#rep-modal-status').value;
            const comment = overlay.querySelector('#rep-modal-comment').value;
            const tagsStr = [...selectedTags].join(',');
            const tagsChanged = tagsStr !== (it.tags || []).join(',');

            const btn = overlay.querySelector('#rep-modal-btn-apply');
            btn.disabled = true;
            btn.textContent = 'Применение...';

            try {
                // 1) Сначала теги
                if (tagsChanged) {
                    try {
                        const result = await setIssueTags(ctx, it.key, [...selectedTags]);
                        if (result.errors && result.errors.length > 0) {
                            toast('Теги не установлены: ' + result.errors[0].message, true);
                        }
                    } catch (e) {
                        console.warn('Не удалось применить теги:', e);
                    }
                }

                // 2) Затем статус + комментарий
                const pp = new URLSearchParams(ctx.qs());
                pp.set('key', it.key);
                pp.set('transition', status);
                pp.set('comment', comment);
                pp.set('dryRun', 'false');
                const res = await ctx.api('/api/mark?' + pp, { method: 'POST' });
                if (res.success === false) throw new Error(res.error || 'Ошибка применения');

                toast('Применено');
                close();
                ctx.refreshStatus?.();
                if (reloadCb) reloadCb();
            } catch (e) {
                toast(e.message, true);
                btn.disabled = false;
                btn.textContent = 'Применить';
            }
        });

        // ---- Reopen ----
        if (canReopen) {
            overlay.querySelector('#rep-modal-btn-reopen').addEventListener('click', async () => {
                if (!confirm('Отменить комментарий (reopen)?')) return;
                try {
                    const pp = new URLSearchParams(ctx.qs());
                    pp.set('key', it.key);
                    pp.set('transition', getReopenTransition(it.status));
                    pp.set('dryRun', 'false');
                    const res = await ctx.api('/api/mark?' + pp, { method: 'POST' });
                    if (res.success === false) throw new Error(res.error || 'reopen failed');
                    toast('Статус сброшен');
                    close();
                    ctx.refreshStatus?.();
                    if (reloadCb) reloadCb();
                } catch (e) {
                    toast(e.message, true);
                }
            });
        }

        overlay.querySelector('#rep-modal-btn-cancel').addEventListener('click', close);

    } catch (e) {
        overlay.querySelector('#rep-modal-body').innerHTML =
            `<div class="sev-HIGH">Ошибка: ${esc(e.message)}</div>`;
    }
}