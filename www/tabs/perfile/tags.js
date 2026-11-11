import { esc } from '../../common.js';

export const PRIORITY_TAGS = ['CWE', 'not-require-fix', 'require-fix'];

/**
 * Взаимоисключающие (противоположные) теги.
 * Если в ответе LLM приходит тег из ключа — все теги из массива значений
 * снимаются с selectedTags ПЕРЕД добавлением нового.
 *
 * Расширяйте по мере необходимости. Текущие пары:
 *   require-fix     ⟷ not-require-fix
 */
export const OPPOSITE_TAGS = {
    'require-fix':     ['not-require-fix'],
    'not-require-fix': ['require-fix'],
};

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
 *   1. Сначала снимает взаимоисключающие (противоположные) теги.
 *   2. Затем добавляет новые теги (и пополняет availableTags).
 *   3. Гарантирует: в selectedTags остаётся ровно ОДИН фикс-тег
 *      (require-fix ИЛИ not-require-fix, но не оба сразу).
 *
 * @param {Set<string>}  selectedTags  — текущее состояние чекбоксов (мутируется)
 * @param {string[]}     parsedTags    — теги из parseOllamaResponse().tags
 * @param {string[]}     availableTags — список доступных тегов (мутируется)
 * @returns {{ removed: string[], added: string[] }}
 */
export function applyParsedTags(selectedTags, parsedTags, availableTags) {
    const removed = [];
    const added = [];

    if (!parsedTags || parsedTags.length === 0) {
        return { removed, added };
    }

    // 1. Снимаем противоположные теги (для каждой новой пары)
    for (const newTag of parsedTags) {
        const opposites = OPPOSITE_TAGS[newTag] || [];
        for (const opp of opposites) {
            if (selectedTags.has(opp)) {
                selectedTags.delete(opp);
                removed.push(opp);
            }
        }
    }

    // 2. Добавляем новые теги (и пополняем availableTags)
    for (const tag of parsedTags) {
        if (!tag || tag.trim() === '') continue;
        if (!availableTags.includes(tag)) {
            availableTags.push(tag);
        }
        if (!selectedTags.has(tag)) {
            selectedTags.add(tag);
            added.push(tag);
        }
    }

    return { removed, added };
}

/**
 * Устанавливает теги для одной или нескольких issues.
 *
 * Прокси на 6060:
 *   - требует query-параметр `keys` (иначе 400)
 *   - форвардит параметры в SonarQube `/api/issues/set_tags` как есть
 *   - SonarQube принимает только `issue` (единственное число)
 *
 * Поэтому отправляем ОБА параметра одновременно: keys=KEY (для прокси)
 * и issue=KEY (для SonarQube). Для нескольких issues — последовательные
 * запросы, т.к. SonarQube API принимает только одну issue за вызов.
 *
 * @returns {Promise<{success:number, total:number, errors:Array<{key,message}>}>}
 * @throws  {Error} если ни один запрос не удался
 */
export async function setIssueTags(ctx, keysOrKey, tagsArray) {
    const keys = Array.isArray(keysOrKey) ? keysOrKey : [String(keysOrKey)];
    const tagsStr = Array.isArray(tagsArray) ? tagsArray.join(',') : String(tagsArray);

    let success = 0;
    const errors = [];

    for (const key of keys) {
        try {
            const p = new URLSearchParams();
            if (ctx.state.project) p.set('project', ctx.state.project);
            p.set('keys',  key);   // для валидации прокси
            p.set('issue', key);   // для SonarQube /api/issues/set_tags
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