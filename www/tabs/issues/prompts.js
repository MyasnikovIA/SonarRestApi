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

/**
 * Группа issues (bulk).
 */
export async function buildBulkStatusCommentPrompt(ctx, issues, status, statusLabel) {
    const keys = issues.map(it => it.key).join(',');
    const params = {
        name: 'bulk-status-comment',
        keys,
        status
    };
    if (statusLabel) params.statusLabel = statusLabel;
    return buildPromptFromApi(ctx, params);
}

/**
 * Авто-режим (auto-bulk-modal): LLM сам выбирает статус.
 */
export async function buildAutoResolvePromptAsync(ctx, issueKey) {
    return buildPromptFromApi(ctx, {
        name: 'auto-resolve',
        issueKey
    });
}