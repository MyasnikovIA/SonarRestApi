// Обёртки над /api/prompt/build. Сервер читает prompts/<name>.txt
// и подставляет параметры — здесь только формирование запроса.

import { buildPromptFromApi } from '../../common.js';

/**
 * Один issue со статусом (модалка / snippet).
 * Сервер сам достанет ruleName, snippet, теги, язык.
 */
export async function buildStatusCommentPrompt(ctx, issueKey, status, statusLabel) {
    const params = {
        name: 'status-comment',
        issueKey,
        status
    };
    if (statusLabel) params.statusLabel = statusLabel;
    return buildPromptFromApi(ctx, params);
}