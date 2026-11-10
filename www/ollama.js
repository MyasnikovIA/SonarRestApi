// Клиент Ollama и UI для настройки.
// Требования к Ollama-серверу при работе из браузера:
//   OLLAMA_HOST=0.0.0.0:11434   (если обращаетесь по сети)
//   OLLAMA_ORIGINS=*            (разрешить CORS с вашего origin)
// Иначе fetch из браузера упадёт с ошибкой CORS.

import { esc } from './common.js';

// ==================== УТИЛИТЫ ====================

/**
 * Нормализует URL: убирает слэши в конце и добавляет http://, если схема не указана.
 */
function normalizeUrl(url) {
    let u = String(url || '').trim();
    if (!u) return '';
    u = u.replace(/\/+$/, '');
    // Если нет протокола (http:// или https://), добавляем http://
    if (!/^https?:\/\//i.test(u)) {
        u = 'http://' + u;
    }
    return u;
}

/**
 * Форматирует размер из байт в человекочитаемый вид (GB, MB).
 */
function formatSize(bytes) {
    if (!bytes || isNaN(bytes)) return '';
    const gb = bytes / (1024 * 1024 * 1024);
    if (gb >= 1) return ` (${gb.toFixed(1)} GB)`;
    const mb = bytes / (1024 * 1024);
    return ` (${mb.toFixed(0)} MB)`;
}

// ==================== КЛАСС ====================

export class OllamaClient {
    static STORAGE_KEY = 'ollama-settings';

    static DEFAULT_SETTINGS = {
        url: 'http://localhost:11434',
        model: '',
        systemPrompt: 'Ты — опытный разработчик. Отвечай на русском языке, кратко и по делу. ' +
                      'Соблюдай строго формат, который указан в пользовательском сообщении.'
    };

    static getSettings() {
        try {
            const raw = localStorage.getItem(OllamaClient.STORAGE_KEY);
            if (raw) {
                const parsed = JSON.parse(raw);
                return { ...OllamaClient.DEFAULT_SETTINGS, ...parsed };
            }
        } catch (_) { /* ignore */ }
        return { ...OllamaClient.DEFAULT_SETTINGS };
    }

    static saveSettings(settings) {
        localStorage.setItem(OllamaClient.STORAGE_KEY, JSON.stringify(settings));
    }

    static isConfigured() {
        const s = OllamaClient.getSettings();
        return !!(s.url && s.model);
    }

    constructor(settings) {
        const s = settings || OllamaClient.getSettings();
        this.url = normalizeUrl(s.url);
        this.model = s.model || '';
        this.systemPrompt = s.systemPrompt || '';
    }

    // GET /api/tags — проверка доступности сервера
    async testConnection(url) {
        const base = normalizeUrl(url || this.url);
        if (!base) throw new Error('URL Ollama не задан');

        let res;
        try {
            res = await fetch(base + '/api/tags', { method: 'GET' });
        } catch (e) {
            // Ошибка сети или CORS
            throw new Error('Сеть/CORS: ' + e.message);
        }

        if (!res.ok) {
            let text = '';
            try { text = await res.text(); } catch (_) {}
            throw new Error('HTTP ' + res.status + (text ? ' — ' + text.slice(0, 200) : ''));
        }
        return res.json();
    }

    // GET /api/tags — список моделей с размерами
    async listModels(url) {
        const data = await this.testConnection(url);
        const models = Array.isArray(data.models) ? data.models : [];

        // Возвращаем массив объектов { name, size, details }
        return models
            .map(m => ({
                name: m.name || m.model,
                size: m.size || 0,
                details: m.details || {}
            }))
            .filter(m => m.name);
    }

    // POST /api/chat со stream:true — асинхронный генератор кусочков
    async *streamChat({ prompt, signal, url, model, systemPrompt }) {
        const base = normalizeUrl(url || this.url);
        const useModel = model || this.model;
        const sys = systemPrompt != null ? systemPrompt : this.systemPrompt;

        if (!base) throw new Error('URL Ollama не задан');
        if (!useModel) throw new Error('Модель Ollama не выбрана');

        const messages = [];
        if (sys && String(sys).trim()) {
            messages.push({ role: 'system', content: String(sys) });
        }
        messages.push({ role: 'user', content: String(prompt || '') });

        let res;
        try {
            res = await fetch(base + '/api/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    model: useModel,
                    messages,
                    stream: true
                }),
                signal
            });
        } catch (e) {
            if (e.name === 'AbortError') throw e;
            throw new Error('Сеть/CORS: ' + e.message);
        }

        if (!res.ok) {
            let text = '';
            try { text = await res.text(); } catch (_) {}
            throw new Error('Ollama: HTTP ' + res.status + (text ? ' — ' + text.slice(0, 200) : ''));
        }
        if (!res.body) throw new Error('Ollama: пустой ответ');

        const reader = res.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        try {
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                buffer += decoder.decode(value, { stream: true });

                let nl;
                while ((nl = buffer.indexOf('\n')) !== -1) {
                    const line = buffer.slice(0, nl).trim();
                    buffer = buffer.slice(nl + 1);
                    if (!line) continue;

                    let obj;
                    try { obj = JSON.parse(line); } catch (_) { continue; }

                    if (obj.error) throw new Error('Ollama: ' + obj.error);

                    const chunk =
                        (obj.message && obj.message.content) ||
                        obj.response ||
                        '';
                    if (chunk) yield chunk;

                    if (obj.done) return;
                }
            }

            // flush хвост без \n
            const tail = buffer.trim();
            if (tail) {
                try {
                    const obj = JSON.parse(tail);
                    const chunk =
                        (obj.message && obj.message.content) ||
                        obj.response ||
                        '';
                    if (chunk) yield chunk;
                } catch (_) { /* ignore */ }
            }
        } finally {
            try { reader.releaseLock(); } catch (_) {}
        }
    }

    // Удобная обёртка без стрима
    async chat({ prompt, signal, url, model, systemPrompt }) {
        let out = '';
        for await (const chunk of this.streamChat({ prompt, signal, url, model, systemPrompt })) {
            out += chunk;
        }
        return out;
    }
}

// ==================== UI НАСТРОЕК ====================

export function openOllamaSettingsModal(ctx) {
    const current = OllamaClient.getSettings();

    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-content" style="max-width: 640px;">
            <div class="modal-header">
                <h3 style="margin:0;">Настройки Ollama</h3>
                <button class="modal-close">&times;</button>
            </div>
            <div class="modal-body">

                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Адрес сервера Ollama</label>
                    <div style="display:flex; gap:8px;">
                        <input id="ollama-url" type="text" placeholder="http://192.168.1.100:11434"
                               value="${esc(current.url)}"
                               style="flex:1; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                        <button id="ollama-test" type="button" class="btn">Проверить доступ</button>
                    </div>
                    <div id="ollama-test-result" style="font-size:12px; margin-top:4px; color:var(--text-dim);">
                        Укажите полный адрес, например: <code>http://IP:11434</code>
                    </div>
                </div>

                <div style="margin-bottom:12px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Модель</label>
                    <div style="display:flex; gap:8px;">
                        <select id="ollama-model"
                                style="flex:1; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px;">
                            <option value="">-- загрузите список --</option>
                        </select>
                        <button id="ollama-refresh-models" type="button" class="btn">Обновить список</button>
                    </div>
                </div>

                <div style="margin-bottom:16px;">
                    <label style="display:block; font-size:12px; margin-bottom:4px;">Первоначальный промпт (system)</label>
                    <textarea id="ollama-system-prompt" rows="5"
                              style="width:100%; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--border); border-radius:6px; resize:vertical;"
                              placeholder="Системная инструкция для модели...">${esc(current.systemPrompt)}</textarea>
                </div>

                <div style="font-size:11px; color:var(--text-dim); margin-bottom:16px; background:var(--bg); padding:8px; border-radius:6px;">
                    <b>Не подключается?</b><br>
                    1. Убедитесь, что Ollama запущена с <code>OLLAMA_HOST=0.0.0.0</code> (для доступа по сети).<br>
                    2. Если браузер блокирует CORS, запустите Ollama с <code>OLLAMA_ORIGINS=*</code>.<br>
                    3. Если сайт открыт по <b>HTTPS</b>, обратиться к <b>HTTP</b> адресу Ollama не получится (Mixed Content) — используйте прокси или HTTPS.
                </div>

                <div style="display:flex; justify-content:flex-end; gap:8px;">
                    <button id="ollama-cancel" class="btn">Отмена</button>
                    <button id="ollama-save" class="btn btn-accent">Сохранить</button>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.querySelector('.modal-close').addEventListener('click', close);
    overlay.querySelector('#ollama-cancel').addEventListener('click', close);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

    const urlInput      = overlay.querySelector('#ollama-url');
    const testBtn       = overlay.querySelector('#ollama-test');
    const testResult    = overlay.querySelector('#ollama-test-result');
    const modelSel      = overlay.querySelector('#ollama-model');
    const refreshBtn    = overlay.querySelector('#ollama-refresh-models');
    const systemInput   = overlay.querySelector('#ollama-system-prompt');

    // Заполняем текущую модель, чтобы пользователь видел её до нажатия «Обновить»
    if (current.model) {
        const opt = document.createElement('option');
        opt.value = current.model;
        opt.textContent = current.model;
        modelSel.appendChild(opt);
    }

    async function refreshModels(showToast) {
        const url = urlInput.value.trim();
        if (!url) {
            testResult.textContent = 'Укажите адрес сервера';
            testResult.style.color = 'var(--err)';
            return;
        }

        const prevValue = modelSel.value;
        refreshBtn.disabled = true;
        refreshBtn.textContent = '...';
        testResult.textContent = 'Загрузка списка моделей…';
        testResult.style.color = 'var(--text-dim)';

        try {
            // Используем класс, который автоматически нормализует URL
            const client = new OllamaClient({ url });
            const models = await client.listModels(url);

            modelSel.innerHTML = '';
            if (!models.length) {
                const o = document.createElement('option');
                o.value = '';
                o.textContent = '-- модели не найдены --';
                modelSel.appendChild(o);
            } else {
                models.forEach(m => {
                    const o = document.createElement('option');
                    o.value = m.name;
                    // Отображаем имя модели и её размер
                    o.textContent = m.name + formatSize(m.size);
                    modelSel.appendChild(o);
                });

                const want = prevValue || current.model;
                // models теперь массив объектов, поэтому используем .some()
                if (want && models.some(m => m.name === want)) {
                    modelSel.value = want;
                }
            }

            testResult.textContent = 'Доступен. Моделей: ' + models.length;
            testResult.style.color = 'var(--ok)';
            if (showToast) ctx.toast('Список моделей обновлён');
        } catch (e) {
            testResult.textContent = 'Ошибка: ' + e.message;
            testResult.style.color = 'var(--err)';
            if (showToast) ctx.toast('Не удалось получить модели: ' + e.message, true);
        } finally {
            refreshBtn.disabled = false;
            refreshBtn.textContent = 'Обновить список';
        }
    }

    testBtn.addEventListener('click', () => refreshModels(true));
    refreshBtn.addEventListener('click', () => refreshModels(true));

    // Автоматическая подгрузка при открытии, ТОЛЬКО если URL не стандартный localhost
    if (urlInput.value.trim() && !urlInput.value.includes('localhost')) {
        refreshModels(false);
    }

    overlay.querySelector('#ollama-save').addEventListener('click', () => {
        const rawUrl = urlInput.value.trim();
        const settings = {
            url: rawUrl ? normalizeUrl(rawUrl) : OllamaClient.DEFAULT_SETTINGS.url,
            model: modelSel.value || '',
            systemPrompt: systemInput.value
        };
        OllamaClient.saveSettings(settings);
        ctx.toast('Настройки Ollama сохранены');
        close();
    });
}

// ==================== ПРИВЯЗКА КНОПОК В МОДАЛКАХ ====================
/**
 * Привязывает кнопку "Получить резолюцию ИИ".
 * @param {HTMLElement} overlay - корень модалки, где есть #modal-btn-ai-resolve
 * @param {{
 *   ctx: any,
 *   buildPrompt: (status: string, statusLabel: string) => string | Promise<string>,
 *   commentSelector?: string,
 *   btnSelector?: string
 * }} opts
 */
export function bindOllamaResolveButton(overlay, opts) {
    const {
        ctx,
        buildPrompt,
        commentSelector = '#modal-comment',
        btnSelector = '#modal-btn-ai-resolve',
        onComplete // <--- ДОБАВЛЕНО: коллбэк для обработки финального ответа
    } = opts;

    const btn = overlay.querySelector(btnSelector);
    if (!btn) return;

    let controller = null;

    btn.addEventListener('click', async () => {
        if (controller) {
            controller.abort();
            return;
        }

        const sel = overlay.querySelector('#modal-status') || overlay.querySelector('#bulk-status');
        const status = sel ? sel.value : '';
        const statusLabel = (sel && sel.selectedOptions[0])
            ? sel.selectedOptions[0].textContent.trim()
            : status;

        const settings = OllamaClient.getSettings();
        if (!settings.url || !settings.model) {
            ctx.toast('Сначала настройте Ollama (кнопка «⚙ Ollama»)', true);
            openOllamaSettingsModal(ctx);
            return;
        }

        let prompt;
        try {
            prompt = await buildPrompt(status, statusLabel);
        } catch (e) {
            ctx.toast('Не удалось построить промпт: ' + e.message, true);
            return;
        }

        const comment = overlay.querySelector(commentSelector);
        if (comment) comment.value = '';

        const originalText = btn.textContent;
        btn.textContent = '⏹ Остановить';

        controller = new AbortController();
        const signal = controller.signal;

        try {
            const client = new OllamaClient(settings);
            let fullResponse = ''; // <--- Собираем весь ответ

            for await (const chunk of client.streamChat({ prompt, signal })) {
                fullResponse += chunk;
                if (comment) {
                    comment.value += chunk;
                    comment.scrollTop = comment.scrollHeight;
                }
            }

            // Вывод в консоль для контроля
            console.log("=== OLLAMA RAW RESPONSE ===");
            console.log(fullResponse);
            console.log("===========================");

            // Вызываем коллбэк, если он передан
            if (onComplete) {
                onComplete(fullResponse);
            }

            ctx.toast('Резолюция ИИ получена');
        } catch (e) {
            if (e.name === 'AbortError') {
                ctx.toast('Генерация прервана', true);
            } else {
                ctx.toast('Ошибка Ollama: ' + e.message, true);
            }
        } finally {
            controller = null;
            btn.textContent = originalText;
        }
    });
}
export function bindOllamaSettingsButton(overlay, ctx, btnSelector = '#modal-btn-ollama-settings') {
    const btn = overlay.querySelector(btnSelector);
    if (!btn) return;
    btn.addEventListener('click', () => openOllamaSettingsModal(ctx));
}
