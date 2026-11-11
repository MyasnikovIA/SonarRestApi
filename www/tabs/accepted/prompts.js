// Обёртки над /api/prompt/build. Сервер читает prompts/<name>.txt
// и подставляет параметры — здесь только формирование запроса.

import { buildPromptFromApi } from '../../common.js';

export async function buildStatusCommentPrompt(ctx, issueKey, status, statusLabel) {
    const params = {
        name: 'status-comment',
        issueKey,
        status
    };
    if (statusLabel) params.statusLabel = statusLabel;
    return buildPromptFromApi(ctx, params);
}

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

export async function buildAutoResolvePromptAsync(ctx, issueKey) {
    return buildPromptFromApi(ctx, {
        name: 'auto-resolve',
        issueKey
    });
}