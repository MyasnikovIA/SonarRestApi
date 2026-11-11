import { statusBadge } from '../../common.js';

// runBulkWithProgress теперь живёт в общем модуле и используется
// всеми вкладками (accepted, perfile, issues, details).
// Реэкспорт — чтобы существующие импорты `from './helpers.js'`
// продолжали работать без изменений.
export { runBulkWithProgress } from '../shared/bulk-progress.js';

export function getReopenTransition(status) {
    if (status === 'CONFIRMED') return 'unconfirm';
    return 'reopen';
}

export function acceptedBadge(it) {
    const res = (it.resolution || '').toUpperCase();
    if (res === 'WONTFIX') {
        return '<span class="status-tag" data-status="WONTFIX" title="RESOLVED / WONTFIX">✗ не исправлять</span>';
    }
    if (res === 'FALSE_POSITIVE') {
        return '<span class="status-tag" data-status="FALSE_POSITIVE" title="RESOLVED / FALSE_POSITIVE">✕ ложно</span>';
    }
    if (res === 'FIXED') {
        return '<span class="status-tag" data-status="FIXED" title="RESOLVED / FIXED">✔ исправлено</span>';
    }
    return statusBadge(it.issueStatus || it.status);
}