export const session = {
    id: 'session',
    title: 'Сессия (Session)',
    filters: [],

    async mount(el, ctx) {
        // Форму строим один раз — иначе при переключении вкладки потеряется textarea
        if (el.dataset.mounted === '1') return;

        el.innerHTML = `
            <div class="row">
                <button id="btn-session-load" class="btn">Load current session</button>
            </div>
            <p class="warn">⚠ Session dump содержит cookie — не показывайте этот экран в записи / публичном чате.</p>
            <textarea id="session-raw" placeholder="Host: sonar.example.com&#10;Cookie: ...&#10;X-XSRF-TOKEN: ..."></textarea>
            <div class="row">
                <button id="btn-session-save" class="btn btn-accent">Save session (PUT /api/session)</button>
            </div>
            <pre id="session-result" class="output"></pre>`;

        const $ = (id) => el.querySelector('#' + id);

        async function loadSession() {
            try {
                const d = await ctx.api('/api/session');
                $('session-raw').value = d.raw || '';
                $('session-result').textContent =
                    `path: ${d.path}\nexists: ${d.exists}\nhost: ${d.host || ''}\nhasCookie: ${d.hasCookie}`;
            } catch (e) {
                ctx.toast(e.message, true);
            }
        }

        async function saveSession() {
            const raw = $('session-raw').value;
            if (!raw.trim()) { ctx.toast('Пустой session dump', true); return; }

            $('session-result').textContent = 'saving…';
            const p = new URLSearchParams();
            if (ctx.state.project) p.set('project', ctx.state.project);
            p.set('build', 'true');

            try {
                const d = await ctx.apiText('/api/session?' + p, raw, 'PUT');
                $('session-result').textContent = JSON.stringify(d, null, 2);
                ctx.toast('Session saved, build started');
                ctx.refreshStatus?.();
            } catch (e) {
                $('session-result').textContent = 'error: ' + e.message;
                ctx.toast(e.message, true);
            }
        }

        $('btn-session-load').addEventListener('click', loadSession);
        $('btn-session-save').addEventListener('click', saveSession);

        el.dataset.mounted = '1';
        loadSession();   // автозагрузка при первом открытии
    },
};