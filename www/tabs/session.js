export const session = {
    id: 'session',
    title: 'Сессия (Session)',
    filters: [],

    async mount(el, ctx) {
        el.innerHTML = `
            <div class="card" style="max-width:860px;">
                <h3 style="margin-top:0;">Подключение к SonarQube</h3>
                <p style="color:var(--text-dim); margin-top:0; font-size:13px;">
                    Параметры хранятся в <code>session.properties</code> на сервере.
                    При первом запуске файл создаётся автоматически с пустыми полями —
                    нажмите «Настроить подключение», введите адрес, логин и токен.
                </p>

                <div id="session-summary"
                     style="margin-bottom:16px; font-size:13px; line-height:1.7;
                            background:var(--bg3); border:1px solid var(--border);
                            border-radius:6px; padding:12px 14px;">
                    <div style="color:var(--text-dim);">Загрузка статуса…</div>
                </div>

                <button id="btn-open-session-modal" class="btn btn-accent">
                    ⚙ Настроить подключение
                </button>
            </div>`;

        await refreshSummary(el, ctx);

        el.querySelector('#btn-open-session-modal').addEventListener('click', () => {
            openSessionModal(ctx, () => refreshSummary(el, ctx));
        });
    },
};

// ==================== summary ====================

async function refreshSummary(el, ctx) {
    const box = el.querySelector('#session-summary');
    if (!box) return;

    try {
        const d = await ctx.api('/api/session');
        const parts = [];

        if (!d.exists) {
            parts.push('<div><span style="color:var(--warn);">⚠ session.properties отсутствует</span></div>');
            parts.push('<div style="color:var(--text-dim);">Нажмите «Настроить подключение», чтобы создать.</div>');
        } else {
            parts.push(`<div><b>Файл:</b> <code>${ctx.esc(d.path || '')}</code></div>`);
            parts.push(`<div><b>URL:</b> ${d.url
                ? ctx.esc(d.url)
                : '<span style="color:var(--warn);">— не задан</span>'}</div>`);
            parts.push(`<div><b>Логин:</b> ${d.user
                ? ctx.esc(d.user)
                : '<span style="color:var(--warn);">— не задан</span>'}</div>`);

            const auth = (d.authType || 'bearer').toLowerCase();
            parts.push(`<div><b>Метод:</b> <code>${ctx.esc(auth)}</code></div>`);

            if (auth === 'cookie') {
                parts.push(`<div><b>Cookie:</b> ${d.hasCookie
                    ? '<span style="color:var(--ok);">✓ установлен</span>'
                    : '<span style="color:var(--warn);">— не задан</span>'}</div>`);
            } else {
                parts.push(`<div><b>Токен:</b> ${d.hasToken
                    ? '<span style="color:var(--ok);">✓ установлен</span>'
                    : '<span style="color:var(--warn);">— не задан</span>'}</div>`);
            }
        }

        box.innerHTML = parts.join('');
    } catch (e) {
        box.innerHTML = `<div style="color:var(--err);">✗ ${ctx.esc(e.message)}</div>`;
    }
}

// ==================== modal ====================

function openSessionModal(ctx, onDone) {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width:640px;">
            <div class="modal-header">
                <h3 style="margin:0;">Подключение к SonarQube</h3>
                <button class="modal-close" type="button">&times;</button>
            </div>
            <div class="modal-body">
                <p style="color:var(--text-dim); margin-top:0; font-size:13px;">
                    Параметры хранятся в <code>session.properties</code>.
                    При первом запуске файл создаётся автоматически — заполните
                    поля и нажмите «Сохранить».
                </p>

                <label style="display:block; margin-bottom:12px;">
                    <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                        Адрес SonarQube — <code>sonar.url</code>
                    </span>
                    <input type="text" id="sess-url" placeholder="https://sonar.bars.group"
                           style="width:100%; padding:8px 10px; background:var(--bg); color:var(--text);
                                  border:1px solid var(--border); border-radius:6px;
                                  font-family:var(--mono); font-size:13px;">
                </label>

                <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-bottom:12px;">
                    <label>
                        <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                            Логин — <code>sonar.user</code>
                        </span>
                        <input type="text" id="sess-user" placeholder="login@example.com"
                               autocomplete="username"
                               style="width:100%; padding:8px 10px; background:var(--bg); color:var(--text);
                                      border:1px solid var(--border); border-radius:6px; font-size:13px;">
                    </label>
                    <label>
                        <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                            Метод — <code>sonar.auth</code>
                        </span>
                        <select id="sess-auth"
                                style="width:100%; padding:8px 10px; background:var(--bg); color:var(--text);
                                       border:1px solid var(--border); border-radius:6px; font-size:13px;">
                            <option value="bearer">bearer — Personal Access Token</option>
                            <option value="basic">basic — логин + пароль</option>
                            <option value="cookie">cookie — сессия из браузера (legacy)</option>
                        </select>
                    </label>
                </div>

                <div id="sess-token-block">
                    <label style="display:block;">
                        <span style="display:flex; justify-content:space-between; align-items:center;
                                     font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                            <span>
                                Токен — <code>sonar.token</code>
                                <span id="sess-token-indicator" style="margin-left:6px;"></span>
                            </span>
                            <a id="sess-token-link"
                               href="https://sonar.bars.group/account/security"
                               target="_blank" rel="noopener"
                               style="color:var(--accent); text-decoration:none; font-size:12px;">
                                🔗 создать токен (откроется в новой вкладке)
                            </a>
                        </span>
                        <div style="display:flex; gap:8px;">
                            <input type="password" id="sess-token" placeholder="squ_..."
                                   autocomplete="new-password"
                                   style="flex:1; padding:8px 10px; background:var(--bg); color:var(--text);
                                          border:1px solid var(--border); border-radius:6px;
                                          font-family:var(--mono); font-size:13px;">
                            <button type="button" id="sess-toggle-token" class="btn"
                                    title="Показать / скрыть" style="padding:8px 12px;">👁</button>
                        </div>
                    </label>
                </div>

                <div id="sess-cookie-block" style="display:none;">
                    <label style="display:block;">
                        <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                            Cookie — <code>sonar.cookie</code>
                            <span id="sess-cookie-indicator" style="margin-left:6px;"></span>
                        </span>
                        <textarea id="sess-cookie" rows="3"
                                  placeholder="OAUTHSTATE=...; XSRF-TOKEN=...; JWT-SESSION=..."
                                  style="width:100%; padding:8px 10px; background:var(--bg); color:var(--text);
                                         border:1px solid var(--border); border-radius:6px;
                                         font-family:var(--mono); font-size:12px; resize:vertical;"></textarea>
                    </label>
                </div>

                <p class="warn" style="margin-top:16px; font-size:12px;">
                    ⚠ Токен и Cookie — секретные данные. Не оставляйте это окно
                    в записи трансляции и не публикуйте скриншоты.
                </p>

                <div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin-top:16px;">
                    <button id="sess-save" class="btn btn-accent">💾 Сохранить и подключиться</button>
                    <button id="sess-cancel" class="btn">Отмена</button>
                    <span id="sess-status" style="margin-left:auto; font-size:12px;"></span>
                </div>

                <pre id="sess-result" class="output"
                     style="margin-top:16px; display:none;"></pre>
            </div>
        </div>`;
    document.body.appendChild(overlay);

    const $ = (id) => overlay.querySelector('#' + id);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    $('sess-cancel').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    // --- toggle token visibility ---
    $('sess-toggle-token').addEventListener('click', () => {
        const t = $('sess-token');
        t.type = t.type === 'password' ? 'text' : 'password';
    });

    // --- auth switch (token ⇄ cookie) ---
    const updateAuthVisibility = () => {
        const auth = $('sess-auth').value;
        $('sess-token-block').style.display  = auth === 'cookie' ? 'none'  : 'block';
        $('sess-cookie-block').style.display = auth === 'cookie' ? 'block' : 'none';
    };
    $('sess-auth').addEventListener('change', updateAuthVisibility);

    // --- token link follows sonar.url ---
    const updateTokenLink = () => {
        const raw = $('sess-url').value.trim() || 'https://sonar.bars.group';
        $('sess-token-link').href = raw.replace(/\/+$/, '') + '/account/security';
    };
    $('sess-url').addEventListener('input', updateTokenLink);

    // --- initial load ---
    (async () => {
        const status = $('sess-status');
        status.textContent = 'загрузка…';
        try {
            const d = await ctx.api('/api/session');
            $('sess-url').value  = d.url  || '';
            $('sess-user').value = d.user || '';

            const auth = (d.authType || 'bearer').toLowerCase();
            $('sess-auth').value = ['bearer', 'basic', 'cookie'].includes(auth)
                ? auth : 'bearer';

            $('sess-token').value  = '';
            $('sess-cookie').value = '';

            const indTkn = $('sess-token-indicator');
            indTkn.innerHTML = d.hasToken
                ? '<span style="color:var(--ok);">✓ установлен</span>'
                : '<span style="color:var(--warn);">— не задан</span>';

            const indCok = $('sess-cookie-indicator');
            indCok.innerHTML = d.hasCookie
                ? '<span style="color:var(--ok);">✓ установлен</span>'
                : '<span style="color:var(--warn);">— не задан</span>';

            updateAuthVisibility();
            updateTokenLink();
            status.textContent = '';
        } catch (e) {
            status.innerHTML = `<span style="color:var(--err);">✗ ${ctx.esc(e.message)}</span>`;
        }
    })();

    // --- save ---
    $('sess-save').addEventListener('click', async () => {
        const btn    = $('sess-save');
        const status = $('sess-status');
        const result = $('sess-result');

        let   url    = $('sess-url').value.trim();
        const user   = $('sess-user').value.trim();
        const auth   = $('sess-auth').value;
        const token  = $('sess-token').value.trim();
        const cookie = $('sess-cookie').value.trim();

        if (url && !/^https?:\/\//i.test(url)) {
            url = 'https://' + url;
            $('sess-url').value = url;
        }

        // --- validation ---
        if (!url) {
            ctx.toast('Укажите адрес SonarQube (sonar.url)', true);
            $('sess-url').focus();
            return;
        }
        if (auth === 'cookie' && !cookie) {
            ctx.toast('Вставьте Cookie или выберите другой метод', true);
            $('sess-cookie').focus();
            return;
        }
        if (auth === 'basic') {
            if (!user)  { ctx.toast('Для basic нужен логин', true); $('sess-user').focus(); return; }
            if (!token) { ctx.toast('Для basic нужен пароль (sonar.token)', true); $('sess-token').focus(); return; }
        }
        if (auth === 'bearer' && !token) {
            ctx.toast('Введите Personal Access Token (ссылка «создать токен»)', true);
            $('sess-token').focus();
            return;
        }

        // --- build session.properties ---
        const lines = ['# SonarQube connection'];
        lines.push('sonar.url=' + url);
        lines.push('sonar.user=' + user);
        lines.push('sonar.auth=' + auth);
        if (auth === 'cookie') {
            lines.push('sonar.cookie=' + cookie);
        } else {
            lines.push('sonar.token=' + token);
        }
        const body = lines.join('\n') + '\n';

        btn.disabled = true;
        const oldText = btn.textContent;
        btn.textContent = 'Сохранение…';
        status.textContent = 'сохраняем…';

        try {
            const p = new URLSearchParams();
            if (ctx.state.project) p.set('project', ctx.state.project);
            p.set('build', 'true');

            const d = await ctx.apiText('/api/session?' + p.toString(), body, 'PUT');

            status.innerHTML = '<span style="color:var(--ok);">✓ сохранено</span>' +
                (d.buildStarted
                    ? ' <span style="color:var(--text-dim);">(снимок собирается…)</span>'
                    : ' <span style="color:var(--text-dim);">(build пропущен)</span>');

            if (result) {
                result.style.display = 'block';
                // секретов в ответе нет, поэтому безопасно печатать всё
                result.textContent = JSON.stringify({
                    saved:        d.saved,
                    path:         d.path,
                    url:          d.url,
                    user:         d.user,
                    authType:     d.authType,
                    hasToken:     d.hasToken,
                    hasCookie:    d.hasCookie,
                    buildStarted: d.buildStarted,
                    buildProject: d.buildProject || null
                }, null, 2);
            }

            ctx.toast('session.properties сохранён и применён');
            ctx.refreshStatus?.();

            // секреты в полях не оставляем
            $('sess-token').value  = '';
            $('sess-cookie').value = '';

            // закрываем через полсекунды, чтобы пользователь увидел результат
            setTimeout(() => {
                close();
                if (onDone) onDone();
            }, 500);

        } catch (e) {
            status.innerHTML =
                `<span style="color:var(--err);">✗ ${ctx.esc(e.message)}</span>`;
            if (result) {
                result.style.display = 'block';
                result.textContent = 'error: ' + e.message;
            }
            ctx.toast('Ошибка сохранения: ' + e.message, true);
        } finally {
            btn.disabled = false;
            btn.textContent = oldText;
        }
    });
}