import { esc } from '../../common.js';

export const PRIORITY_TAGS = ['CWE', 'not-require-fix', 'require-fix'];

/**
 * Взаимоисключающие (противоположные) теги.
 * Если в ответе LLM приходит тег из ключа — все теги из массива значений
 * снимаются с selectedTags ПЕРЕД добавлением нового.
 */
export const OPPOSITE_TAGS = {
    'require-fix':     ['not-require-fix'],
    'not-require-fix': ['require-fix'],
};

/**
 * SonarQube требует, чтобы каждый тег соответствовал
 * ^[a-z0-9\+#\-\.]+$ — без пробелов, кириллицы и прочих символов.
 */
export function isValidTag(tag) {
    return typeof tag === 'string' && /^[a-z0-9\+\#\-\.]+$/.test(tag);
}

export async function fetchAvailableTags(ctx) {
    try {
        const d = await ctx.api('/api/tags');
        return d.tags || [];
    } catch (e) {
        console.warn('Не удалось загрузить доступные теги', e);
        return [];
    }
}

export function buildOrderedTags(availableTags, initialTags, priorityTags, query) {
    const q = String(query || '').trim().toLowerCase();
    const matches = (t) => !q || t.toLowerCase().includes(q);
    const initialSet = new Set(initialTags);

    const already = initialTags.filter(matches);
    const priority = priorityTags.filter(
        t => availableTags.includes(t) && !initialSet.has(t) && matches(t)
    );
    const rest = availableTags
        .filter(t => !initialSet.has(t) && !priorityTags.includes(t) && matches(t))
        .sort((a, b) => a.localeCompare(b));

    return [...already, ...priority, ...rest];
}

export function renderTagCheckboxes(container, orderedTags, selectedTags) {
    if (!container) return;
    if (orderedTags.length === 0) {
        container.innerHTML = `<div style="color:var(--text-dim); font-size:12px;">Нет тегов по фильтру</div>`;
        return;
    }
    container.innerHTML = orderedTags.map(t => `
        <label style="display:flex; align-items:center; gap:4px; font-size:12px;
                      cursor:pointer; padding:3px 8px; background:var(--bg);
                      border:1px solid var(--border); border-radius:4px;">
            <input type="checkbox" class="tag-cb" value="${esc(t)}" ${selectedTags.has(t) ? 'checked' : ''}>
            ${esc(t)}
        </label>
    `).join('');
}

/**
 * Применяет теги из ответа LLM к selectedTags:
 *   1. Отбрасывает невалидные теги (SonarQube-формат ^[a-z0-9\+#\-\.]+$).
 *   2. Снимает взаимоисключающие (противоположные) теги.
 *   3. Добавляет новые теги (и пополняет availableTags).
 *   4. Гарантирует: в selectedTags остаётся ровно ОДИН фикс-тег.
 */
export function applyParsedTags(selectedTags, parsedTags, availableTags) {
    const removed = [];
    const added   = [];
    const skipped = [];

    if (!parsedTags || parsedTags.length === 0) {
        return { removed, added, skipped };
    }

    const valid = [];
    for (const t of parsedTags) {
        if (isValidTag(t)) {
            valid.push(t);
        } else {
            skipped.push(t);
            console.warn('[tags] invalid tag skipped:', JSON.stringify(t));
        }
    }

    for (const newTag of valid) {
        const opposites = OPPOSITE_TAGS[newTag] || [];
        for (const opp of opposites) {
            if (selectedTags.has(opp)) {
                selectedTags.delete(opp);
                removed.push(opp);
            }
        }
    }

    for (const tag of valid) {
        if (!availableTags.includes(tag)) availableTags.push(tag);
        if (!selectedTags.has(tag)) {
            selectedTags.add(tag);
            added.push(tag);
        }
    }

    return { removed, added, skipped };
}

/**
 * Устанавливает теги для одной или нескольких issues.
 * Отправляет ОБА параметра: keys (для прокси) и issue (для SonarQube).
 * Для нескольких issues — последовательные запросы.
 */
export async function setIssueTags(ctx, keysOrKey, tagsArray) {
    const keys = Array.isArray(keysOrKey) ? keysOrKey : [String(keysOrKey)];
    const rawArr = Array.isArray(tagsArray) ? tagsArray : [String(tagsArray)];
    const validArr = rawArr.filter(isValidTag);

    if (validArr.length !== rawArr.length) {
        console.warn('[tags] filtered out invalid tags:',
            rawArr.filter(t => !isValidTag(t)));
    }

    const tagsStr = validArr.join(',');

    let success = 0;
    const errors = [];

    for (const key of keys) {
        try {
            const p = new URLSearchParams();
            if (ctx.state.project) p.set('project', ctx.state.project);
            p.set('keys',  key);
            p.set('issue', key);
            p.set('tags',  tagsStr);
            await ctx.api('/api/set_tags?' + p.toString(), { method: 'POST' });
            success++;
        } catch (e) {
            errors.push({ key, message: e.message });
        }
    }

    if (success === 0 && errors.length > 0) {
        throw new Error(errors[0].message);
    }

    return { success, total: keys.length, errors };
}