// Общие helpers — используются всеми вкладками.

export function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

// Функция для подсветки кода
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

// ==================== AI PROMPT ====================

// Универсальная функция копирования (работает даже на HTTP)
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
        // Запрашиваем сниппет (на случай, если его нет в объекте)
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

        // 1. Сразу копируем в буфер
        copyToClipboard(promptText);

        // 2. Показываем модалку
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

/**
 * Возвращает сниппет с визуальными маркерами места ошибки (>>> ... <<<).
 * Использует маркеры §HL_START§, если они есть.
 * Если их нет — выделяет строку по номеру targetLine.
 */
export function highlightForPrompt(snippet, targetLine) {
    let lines = Array.isArray(snippet) ? snippet : (snippet || '').split('\n');

    return lines.map(line => {
        // 1. Если есть маркеры SonarQube — заменяем их
        if (line.includes('§HL_START§') || line.includes('§HL_END§')) {
            return line.replace(/§HL_START§/g, '>>> ').replace(/§HL_END§/g, ' <<<');
        }

        // 2. Если маркеров нет, но в начале строки есть номер (например "200 | ")
        if (targetLine) {
            const match = line.match(/^\s*(\d+)\s*\|/);
            if (match && parseInt(match[1], 10) === parseInt(targetLine, 10)) {
                // Добавляем маркеры к этой строке
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