import { toast } from '../../common.js';
import { buildStatusCommentPrompt } from './prompts.js';

export async function copyToClipboard(text) {
    if (navigator.clipboard && window.isSecureContext) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch (e) { /* fallback ниже */ }
    }
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-1000px';
    ta.style.left = '-1000px';
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    return ok;
}

export function flashButton(btn, text, ms) {
    if (!btn) return;
    const original = btn.dataset.originalText || btn.textContent;
    btn.dataset.originalText = original;
    btn.textContent = text;
    btn.disabled = true;
    setTimeout(function () {
        btn.textContent = original;
        btn.disabled = false;
    }, ms || 1500);
}

/**
 * Привязывает кнопку "Сгенерировать промпт" к модалке.
 * ruleName и snippet больше не нужны — сервер достаёт их сам
 * (сигнатура сохранена для обратной совместимости с вызывающими).
 */
export function bindStatusPromptButton(overlay, issue, ruleName, snippet, ctx) {
    const btn = overlay.querySelector('#modal-btn-gen-prompt');
    if (!btn) return;
    btn.addEventListener('click', async function () {
        const sel = overlay.querySelector('#modal-status');
        if (!sel) return;
        const status = sel.value;
        const statusLabel = sel.selectedOptions[0]
            ? sel.selectedOptions[0].textContent.trim()
            : status;

        let prompt;
        try {
            prompt = await buildStatusCommentPrompt(ctx, issue.key, status, statusLabel);
        } catch (e) {
            toast('Не удалось построить промпт: ' + e.message, true);
            return;
        }

        const ok = await copyToClipboard(prompt);

        if (ok) {
            flashButton(btn, 'Промпт скопирован!');
            toast('Промпт для статуса "' + status + '" скопирован в буфер обмена');
        } else {
            const ta = overlay.querySelector('#modal-comment');
            if (ta && !ta.value) {
                ta.value = prompt;
                ta.focus();
                ta.select();
            }
            flashButton(btn, 'Не удалось скопировать', 2000);
            toast('Промпт вставлен в поле комментария (статус "' + status + '")', true);
        }
    });
}