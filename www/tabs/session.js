export const session = {
    id: 'session',
    title: 'Сессия (Session)',
    filters: [],

    async mount(el, ctx) {
        if (el.dataset.mounted === '1') {
            // При повторном заходе просто обновляем индикаторы/поля с сервера
            await loadSessionState(el, ctx);
            return;
        }

        el.innerHTML = `
            <div class="card" style="max-width:860px;">
                <h3 style="margin-top:0;">Подключение к SonarQube</h3>
                <p style="color:var(--text-dim); margin-top:0; font-size:13px;">
                    Параметры хранятся в <code>session.properties</code> на сервере.
                    При первом запуске файл создаётся автоматически с пустыми полями —
                    заполните адрес, логин и токен и нажмите «Сохранить».
                </p>

                <label style="display:block; margin-bottom:12px;">
                    <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                        Адрес SonarQube — <code>sonar.url</code>
                    </span>
                    <input type="text" id="sess-url"
                           placeholder="https://sonar.bars.group"
                           style="width:100%; padding:8px 10px; background:var(--bg); color:var(--text);
                                  border:1px solid var(--border); border-radius:6px;
                                  font-family:var(--mono); font-size:13px;">
                </label>

                <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-bottom:12px;">
                    <label>
                        <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                            Логин — <code>sonar.user</code>
                        </span>
                        <input type="text" id="sess-user"
                               placeholder="login@example.com"
                               autocomplete="username"
                               style="width:100%; padding:8px 10px; background:var(--bg); color:var(--text);
                                      border:1px solid var(--border); border-radius:6px;
                                      font-size:13px;">
                    </label>

                    <label>
                        <span style="display:block; font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                            Метод — <code>sonar.auth</code>
                        </span>
                        <select id="sess-auth"
                                style="width:100%; padding:8px 10px; background:var(--bg); color:var(--text);
                                       border:1px solid var(--border); border-radius:6px; font-size:13px;">
                            <option value="bearer">bearer — Personal Access Token</option>
                            <option value="basic">basic  — логин + пароль</option>
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
                                → создать токен
                            </a>
                        </span>
                        <div style="display:flex; gap:8px;">
                            <input type="password" id="sess-token"
                                   placeholder="squ_..."
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
                        <span style="display:flex; justify-content:space-between; align-items:center;
                                     font-size:12px; margin-bottom:4px; color:var(--text-dim);">
                            <span>
                                Cookie — <code>sonar.cookie</code>
                                <span id="sess-cookie-indicator" style="margin-left:6px;"></span>
                            </span>
                        </span>
                        <textarea id="sess-cookie" rows="3"
                                  placeholder="OAUTHSTATE=...; XSRF-TOKEN=...; JWT-SESSION=..."
                                  style="width:100%; padding:8px 10px; background:var(--bg); color:var(--text);
                                         border:1px solid var(--border); border-radius:6px;
                                         font-family:var(--mono); font-size:12px; resize:vertical;"></textarea>
                    </label>
                </div>

                <p class="warn" style="margin-top:16px; font-size:12px;">
                    ⚠ Токен и Cookie — секретные данные. Не оставляйте этот экран
                    в записи трансляции и не публикуйте скриншоты.
                </p>

                <div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin-top:16px;">
                    <button id="btn-session-load" class="btn">↻ Загрузить с сервера</button>
                    <button id="btn-session-save" class="btn btn-accent">💾 Сохранить и подключиться</button>
                    <span id="session-status" style="margin-left:auto; font-size:12px;"></span>
                </div>

                <pre id="session-result" class="output" style="margin-top:16px; display:none;"></pre>
            </div>`;

        el.dataset.mounted = '1';

        const $ = (id) => el.querySelector('#' + id);

        const updateTokenLink = () => {
            const raw = $('sess-url').value.trim() || 'https://sonar.bars.group';
            const base = raw.replace(/\/+$/, '');
            $('sess-token-link').href = base + '/account/security';
        };

        const updateAuthVisibility = () => {
            const auth = $('sess-auth').value;
            $('sess-token-block').style.display  = auth === 'cookie' ? 'none' : 'block';
            $('sess-cookie-block').style.display = auth === 'cookie' ? 'block' : 'none';
        };

        $('sess-url').addEventListener('input', updateTokenLink);
        $('sess-auth').addEventListener('change', updateAuthVisibility);

        $('sess-toggle-token').addEventListener('click', () => {
            const t = $('sess-token');
            t.type = t.type === 'password' ? 'text' : 'password';
        });

        $('btn-session-load').addEventListener('click', () => loadSessionState(el, ctx));
        $('btn-session-save').addEventListener('click', () => saveSessionState(el, ctx));

        updateTokenLink();
        updateAuthVisibility();
        await loadSessionState(el, ctx);
    },
};

// ==================== helpers ====================

async function loadSessionState(el, ctx) {
    const $ = (id) => el.querySelector('#' + id);
    const status = $('session-status');
    const result = $('session-result');
    const indTkn = $('sess-token-indicator');
    const indCok = $('sess-cookie-indicator');

    if (status) status.textContent = 'загрузка…';

    try {
        const d = await ctx.api('/api/session');

        // Не-секретные поля заполняем из ответа
        $('sess-url').value  = d.url  || '';
        $('sess-user').value = d.user || '';
        const auth = (d.authType || 'bearer').toLowerCase();
        $('sess-auth').value = ['bearer', 'basic', 'cookie'].includes(auth)
            ? auth : 'bearer';

        // Секреты НЕ возвращаем в форму: пользователь вводит их заново при изменении
        $('sess-token').value  = '';
        $('sess-cookie').value = '';

        // Индикаторы наличия секретов
        if (indTkn) {
            indTkn.innerHTML = d.hasToken
                ? '<span style="color:var(--ok);">✓ установлен</span>'
                : '<span style="color:var(--warn);">— не задан</span>';
        }
        if (indCok) {
            indCok.innerHTML = d.hasCookie
                ? '<span style="color:var(--ok);">✓ установлен</span>'
                : '<span style="color:var(--warn);">— не задан</span>';
        }

        // Видимость блоков под выбранный метод
        const curAuth = $('sess-auth').value;
        $('sess-token-block').style.display  = curAuth === 'cookie' ? 'none' : 'block';
        $('sess-cookie-block').style.display = curAuth === 'cookie' ? 'block' : 'none';

        // Ссылка «создать токен» = <sonar.url>/account/security
        const rawUrl = $('sess-url').value || 'https://sonar.bars.group';
        $('sess-token-link').href = rawUrl.replace(/\/+$/, '') + '/account/security';

        // Общий статус
        if (status) {
            if (d.exists && d.url && (d.hasToken || d.hasCookie)) {
                status.innerHTML = '<span style="color:var(--ok);">✓ сессия настроена</span>';
            } else if (d.exists) {
                status.innerHTML = '<span style="color:var(--warn);">⚠ сессия не заполнена</span>';
            } else {
                status.innerHTML = '<span style="color:var(--warn);">⚠ session.properties отсутствует</span>';
            }
        }

        if (result) {
            result.style.display = 'none';
            result.textContent = '';
        }
    } catch (e) {
        if (status) status.innerHTML =
            `<span style="color:var(--err);">✗ ${ctx.esc(e.message)}</span>`;
    }
}

async function saveSessionState(el, ctx) {
    const $ = (id) => el.querySelector('#' + id);
    const status = $('session-status');
    const result = $('session-result');
    const btn    = $('btn-session-save');

    let   url    = $('sess-url').value.trim();
    const user   = $('sess-user').value.trim();
    const auth   = $('sess-auth').value;
    const token  = $('sess-token').value.trim();
    const cookie = $('sess-cookie').value.trim();

    // Автодополнение схемы: sonar.bars.group → https://sonar.bars.group
    if (url && !/^https?:\/\//i.test(url)) {
        url = 'https://' + url;
        $('sess-url').value = url;
    }

    // ---------- Валидация ----------
    if (!url) {
        ctx.toast('Укажите адрес SonarQube (sonar.url)', true);
        $('sess-url').focus();
        return;
    }
    if (auth === 'cookie') {
        if (!cookie) {
            ctx.toast('Вставьте Cookie из браузера или выберите другой метод', true);
            $('sess-cookie').focus();
            return;
        }
    } else if (auth === 'basic') {
        if (!user) {
            ctx.toast('Для basic-аутентификации нужен логин', true);
            $('sess-user').focus();
            return;
        }
        if (!token) {
            ctx.toast('Для basic-аутентификации нужен пароль (sonar.token)', true);
            $('sess-token').focus();
            return;
        }
    } else { // bearer
        if (!token) {
            ctx.toast('Введите Personal Access Token (ссылка «создать токен»)', true);
            $('sess-token').focus();
            return;
        }
    }

    // ---------- Сборка текста session.properties ----------
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

    // ---------- Сохранение ----------
    btn.disabled = true;
    const oldText = btn.textContent;
    btn.textContent = 'Сохранение…';
    if (status) status.textContent = 'сохраняем…';

    try {
        const p = new URLSearchParams();
        if (ctx.state.project) p.set('project', ctx.state.project);
        p.set('build', 'true');

        // PUT /api/session, тело — text/plain
        const d = await ctx.apiText('/api/session?' + p.toString(), body, 'PUT');

        if (status) {
            status.innerHTML = '<span style="color:var(--ok);">✓ сохранено</span>' +
                (d.buildStarted
                    ? ' <span style="color:var(--text-dim);">(снимок собирается…)</span>'
                    : ' <span style="color:var(--text-dim);">(build пропущен)</span>');
        }

        if (result) {
            result.style.display = 'block';
            // Никаких секретов — только безопасные поля
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

        // Перечитываем (индикаторы и статус обновятся)
        await loadSessionState(el, ctx);

        // Поле token/cookie после сохранения всегда пустое — это секрет
        $('sess-token').value  = '';
        $('sess-cookie').value = '';
    } catch (e) {
        if (status) status.innerHTML =
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
}