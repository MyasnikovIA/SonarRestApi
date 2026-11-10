// Общие helpers — используются всеми вкладками.

export function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

export function highlightCode(codeStr) {
    let safe = esc(codeStr);
    return safe.replace(/§HL_START§/g, '<mark class="sonar-issue-mark">')
               .replace(/§HL_END§/g, '</mark>');
}

export function toast(msg, isErr = false) {
    const el = document.createElement('div');
    el.className = 'toast' + (isErr ? ' err' : '');
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 4000);
}

export async function api(path, opts = {}) {
    const r = await fetch(path, {
        ...opts,
        headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) }
    });
    const text = await r.text();
    let data;
    try { data = JSON.parse(text); } catch { data = { raw: text }; }
    if (!r.ok) {
        throw new Error(data.error || data.status || `HTTP ${r.status}`);
    }
    return data;
}

export async function apiText(path, body, method = 'POST') {
    const r = await fetch(path, {
        method,
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body
    });
    const text = await r.text();
    let data;
    try { data = JSON.parse(text); } catch { data = { raw: text }; }
    if (!r.ok) throw new Error(data.error || `HTTP ${r.status}`);
    return data;
}

export function statusBadge(status) {
    if (!status) return '';
    const s = String(status).toUpperCase();
    if (s === 'OPEN' || s === 'REOPENED') return '';

    const map = {
        'ACCEPTED':       { icon: '✓',  text: 'принято' },
        'CONFIRMED':      { icon: '❗', text: 'подтверждено' },
        'FALSE_POSITIVE': { icon: '✕',  text: 'ложно' },
        'RESOLVED':       { icon: '✔',  text: 'исправлено' },
        'FIXED':          { icon: '✔',  text: 'исправлено' },
        'WONTFIX':        { icon: '✗',  text: 'не исправлять' },
        'CLOSED':         { icon: '⛔', text: 'закрыто' },
        // Security Hotspots
        'TO_REVIEW':      { icon: '⚠',  text: 'к проверке' },
        'REVIEWED':       { icon: '✔',  text: 'проверено' }
    };
    const m = map[s] || { icon: '💬', text: s.toLowerCase() };

    return `<span class="status-tag" data-status="${esc(s)}" title="Статус: ${esc(s)}">${m.icon} ${m.text}</span>`;
}

// ==================== TAGS EDITOR ====================

// ==================== TAGS EDITOR ====================

/**
 * Открывает модальное окно для редактирования тегов группы issues.
 * @param {Array} issues - список issue (должен содержать .key и .tags)
 * @param {Object} ctx - контекст приложения
 * @param {Function} onDone - коллбэк после успешного сохранения
 */
export async function showTagEditor(issues, ctx, onDone) {
    if (!issues.length) return;

    const keys = issues.map(it => it.key);
    const initialTags = [...(issues[0].tags || [])];

    let availableTags = [];
    try {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        const d = await ctx.api('/api/tags?' + p.toString());
        availableTags = d.tags || [];
    } catch (e) {
        console.warn('Не удалось загрузить доступные теги', e);
    }

    // Добавляем текущие теги, если их нет в доступных (кастомные/устаревшие)
    initialTags.forEach(t => {
        if (!availableTags.includes(t)) availableTags.push(t);
    });

    // Приоритетные теги (после «уже добавленных»)
    const PRIORITY_TAGS = ['CWE', 'not-require-fix', 'require-fix'];
    const initialSet = new Set(initialTags);
    const selectedTags = new Set(initialTags); // состояние чекбоксов

    // Порядок: уже добавленные → CWE → not-require-fix → require-fix → остальные (alphabet)
    function getOrderedTags(query) {
        const q = String(query || '').trim().toLowerCase();
        const matches = (t) => !q || t.toLowerCase().includes(q);

        const already = initialTags.filter(matches);

        const priority = PRIORITY_TAGS.filter(
            t => availableTags.includes(t) && !initialSet.has(t) && matches(t)
        );

        const rest = availableTags
            .filter(t => !initialSet.has(t) && !PRIORITY_TAGS.includes(t) && matches(t))
            .sort((a, b) => a.localeCompare(b));

        return [...already, ...priority, ...rest];
    }

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 600px;">
            <div class="modal-header">
                <h3 style="margin:0;">Редактирование тегов</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body">
                <p style="margin-top:0; color:var(--text-dim);">Выбрано ошибок: <b>${issues.length}</b></p>

                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Доступные теги</label>
                    <input type="text" id="tags-filter" placeholder="Фильтр по тегам..."
                           style="width:100%; box-sizing:border-box; padding:6px 10px; margin-bottom:8px;
                                  border-radius:6px; border:1px solid var(--border);
                                  background:var(--bg); color:var(--text);">
                    <div id="tags-list"
                         style="max-height: 300px; overflow-y: auto; display: flex; flex-wrap: wrap;
                                gap: 8px; padding: 8px; background: var(--bg3);
                                border: 1px solid var(--border); border-radius: 6px;"></div>
                </div>

                <div style="display:flex; justify-content:flex-end; gap:8px; margin-top:20px;">
                    <button id="tags-cancel" class="btn">Отмена</button>
                    <button id="tags-save" class="btn btn-accent">Сохранить</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const listEl  = overlay.querySelector('#tags-list');
    const filterEl = overlay.querySelector('#tags-filter');

    function renderList() {
        const ordered = getOrderedTags(filterEl.value);
        if (ordered.length === 0) {
            const msg = filterEl.value.trim()
                ? 'Нет тегов по фильтру'
                : 'Нет доступных тегов';
            listEl.innerHTML = `<div style="color:var(--text-dim); font-size:12px;">${msg}</div>`;
            return;
        }
        listEl.innerHTML = ordered.map(t => `
            <label style="display: flex; align-items: center; gap: 6px; padding: 4px 8px;
                          background: var(--bg); border: 1px solid var(--border);
                          border-radius: 4px; cursor: pointer;">
                <input type="checkbox" class="tag-cb" value="${esc(t)}" ${selectedTags.has(t) ? 'checked' : ''}>
                ${esc(t)}
            </label>
        `).join('');
    }

    renderList();

    // Делегирование: работает и после перерисовки списка при фильтрации
    filterEl.addEventListener('input', renderList);
    listEl.addEventListener('change', (e) => {
        if (e.target.classList.contains('tag-cb')) {
            if (e.target.checked) selectedTags.add(e.target.value);
            else                  selectedTags.delete(e.target.value);
        }
    });

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.querySelector('#tags-cancel').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    overlay.querySelector('#tags-save').addEventListener('click', async () => {
        const checkedTags = [...selectedTags];
        const tagsStr = checkedTags.join(',');

        const btn = overlay.querySelector('#tags-save');
        btn.disabled = true;
        btn.textContent = 'Сохранение...';

        try {
            const p = new URLSearchParams();
            p.set('keys', keys.join(','));
            p.set('tags', tagsStr);

            await ctx.api('/api/set_tags?' + p, { method: 'POST' });
            toast('Теги успешно сохранены');
            close();
            if (onDone) onDone();
        } catch (e) {
            toast('Ошибка сохранения тегов: ' + e.message, true);
            btn.disabled = false;
            btn.textContent = 'Сохранить';
        }
    });
}

// ==================== AI PROMPT ====================

export function copyToClipboard(text) {
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).catch(() => fallbackCopy(text));
    } else {
        fallbackCopy(text);
    }
}

function fallbackCopy(text) {
    const textArea = document.createElement("textarea");
    textArea.value = text;
    textArea.style.top = "0";
    textArea.style.left = "0";
    textArea.style.position = "fixed";
    textArea.style.opacity = "0";
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    try {
        document.execCommand('copy');
        toast('Промпт скопирован в буфер обмена');
    } catch (err) {
        toast('Не удалось скопировать. Выделите текст вручную.', true);
    }
    document.body.removeChild(textArea);
}

export async function fetchAndShowPrompt(issue, ctx) {
    try {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        p.set('issueKey', issue.key);
        const d = await ctx.api('/api/snippet?' + p);

        const it = d.issue || issue;
        const snippetLines = d.snippet || [];
        const codeText = snippetLines.join('\n');
        const ruleName = d.ruleName || it.ruleName || '';

        const promptText = "Пожалуйста, проанализируй следующую проблему, найденную статическим анализатором SonarQube.\n\n" +
            "### Детали ошибки:\n" +
            "- **Правило:** " + it.rule + " (" + ruleName + ")\n" +
            "- **Сообщение:** " + it.message + "\n" +
            "- **Критичность (Severity):** " + (it.impactSeverity || it.severity) + "\n" +
            "- **Файл:** " + it.component + "\n" +
            "- **Строка:** " + it.line + "\n\n" +
            "### Фрагмент кода:\n" +
            "```\n" +
            codeText + "\n" +
            "```\n\n" +
            "### Задание:\n" +
            "1. Объясни, почему данная конструкция считается ошибочной или опасной (в чем суть проблемы).\n" +
            "2. Оцени реальную серьезность проблемы в контексте данного кода.\n" +
            "3. Предложи исправленный вариант кода (с комментариями изменений).";

        copyToClipboard(promptText);
        showPromptModal(promptText);
    } catch (e) {
        toast('Не удалось загрузить данные для промпта: ' + e.message, true);
    }
}

export function detectLanguage(component) {
    const path = String(component || '').split(':')[0].toLowerCase();
    if (/\.d\.ts$/.test(path)) return { name: 'TypeScript', codeFence: 'typescript' };

    const m = path.match(/\.([a-z0-9]+)$/);
    if (!m) return { name: 'неизвестный (определи по коду)', codeFence: '' };

    const map = {
        php:  { name: 'PHP',         codeFence: 'php' },
        js:   { name: 'JavaScript',  codeFence: 'javascript' },
        mjs:  { name: 'JavaScript',  codeFence: 'javascript' },
        cjs:  { name: 'JavaScript',  codeFence: 'javascript' },
        jsx:  { name: 'JavaScript (React)', codeFence: 'jsx' },
        ts:   { name: 'TypeScript',  codeFence: 'typescript' },
        tsx:  { name: 'TypeScript (React)', codeFence: 'tsx' },
        vue:  { name: 'Vue',         codeFence: 'vue' },
        html: { name: 'HTML',        codeFence: 'html' },
        css:  { name: 'CSS',         codeFence: 'css' },
        scss: { name: 'SCSS',        codeFence: 'scss' },
        less: { name: 'LESS',        codeFence: 'less' },
        java: { name: 'Java',        codeFence: 'java' },
        kt:   { name: 'Kotlin',      codeFence: 'kotlin' },
        kts:  { name: 'Kotlin',      codeFence: 'kotlin' },
        groovy: { name: 'Groovy',    codeFence: 'groovy' },
        scala:  { name: 'Scala',     codeFence: 'scala' },
        sql:  { name: 'SQL',         codeFence: 'sql' },
        pks:  { name: 'PL/SQL',      codeFence: 'plsql' },
        pkb:  { name: 'PL/SQL',      codeFence: 'plsql' },
        pls:  { name: 'PL/SQL',      codeFence: 'plsql' },
        plb:  { name: 'PL/SQL',      codeFence: 'plsql' },
        prc:  { name: 'PL/SQL',      codeFence: 'plsql' },
        fnc:  { name: 'PL/SQL',      codeFence: 'plsql' },
        trg:  { name: 'PL/SQL',      codeFence: 'plsql' },
        pck:  { name: 'PL/SQL',      codeFence: 'plsql' },
        py:   { name: 'Python',      codeFence: 'python' },
        go:   { name: 'Go',          codeFence: 'go' },
        rb:   { name: 'Ruby',        codeFence: 'ruby' },
        cs:   { name: 'C#',          codeFence: 'csharp' },
        cpp:  { name: 'C++',         codeFence: 'cpp' },
        cc:   { name: 'C++',         codeFence: 'cpp' },
        cxx:  { name: 'C++',         codeFence: 'cpp' },
        hpp:  { name: 'C++',         codeFence: 'cpp' },
        hxx:  { name: 'C++',         codeFence: 'cpp' },
        c:    { name: 'C',           codeFence: 'c' },
        h:    { name: 'C/C++',       codeFence: 'c' },
        rs:   { name: 'Rust',        codeFence: 'rust' },
        swift:{ name: 'Swift',       codeFence: 'swift' },
        xml:  { name: 'XML',         codeFence: 'xml' },
        yaml: { name: 'YAML',        codeFence: 'yaml' },
        yml:  { name: 'YAML',        codeFence: 'yaml' },
        json: { name: 'JSON',        codeFence: 'json' },
        sh:   { name: 'Shell',       codeFence: 'bash' },
        bash: { name: 'Shell',       codeFence: 'bash' }
    };
    return map[m[1]] || { name: m[1].toUpperCase(), codeFence: m[1] };
}

export function cleanSnippet(snippet) {
    const raw = Array.isArray(snippet) ? snippet.join('\n') : (snippet || '');
    return raw.replace(/§HL_START§|§HL_END§/g, '');
}

export function highlightForPrompt(snippet, targetLine) {
    let lines = Array.isArray(snippet) ? snippet : (snippet || '').split('\n');

    return lines.map(line => {
        if (line.includes('§HL_START§') || line.includes('§HL_END§')) {
            return line.replace(/§HL_START§/g, '>>> ').replace(/§HL_END§/g, ' <<<');
        }
        if (targetLine) {
            const match = line.match(/^\s*(\d+)\s*\|/);
            if (match && parseInt(match[1], 10) === parseInt(targetLine, 10)) {
                return line.replace(/^(\s*\d+\s*\|)/, '$1 >>> ') + ' <<<';
            }
        }
        return line;
    }).join('\n');
}

function showPromptModal(text) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 800px;">
            <div class="modal-header">
                <h3 style="margin:0;">Промпт для AI</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body">
                <p style="margin-top:0; color:var(--text-dim); font-size:13px;">
                    Промпт уже скопирован в буфер обмена. Вы можете вставить его в ChatGPT, Claude или любую другую нейросеть.
                </p>
                <textarea id="ai-prompt-text" style="width:100%; height:300px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; padding:12px; font-family:var(--mono); font-size:13px; resize:vertical;" readonly>${esc(text)}</textarea>
                <div style="display:flex; justify-content:flex-end; gap:8px; margin-top:16px;">
                    <button id="ai-prompt-copy" class="btn btn-accent">📋 Скопировать еще раз</button>
                    <button id="ai-prompt-ok" class="btn">OK</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.querySelector('#ai-prompt-ok').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    overlay.querySelector('#ai-prompt-copy').addEventListener('click', () => {
        copyToClipboard(text);
    });
}