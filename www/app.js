import { api, apiText, esc, toast } from './common.js';
import { summary } from './tabs/summary.js';
import { matrix }  from './tabs/matrix.js';
import { perfile } from './tabs/perfile.js';
import { details } from './tabs/details.js';
import { issues }  from './tabs/issues.js';
import { accepted } from './tabs/accepted.js';
import { snippet } from './tabs/snippet.js';
import { session } from './tabs/session.js';
import { status }  from './tabs/status.js';
import { openOllamaSettingsModal } from './ollama.js';

// ВОТ ЗДЕСЬ БЫЛ ПРОПУЩЕН accepted — теперь он добавлен
const TABS = [summary, matrix, perfile, details, issues, accepted, snippet, session, status];
const TAB_BY_ID = Object.fromEntries(TABS.map(t => [t.id, t]));

const state = {
    project:  localStorage.getItem('project')  || '',
    sev:      localStorage.getItem('sev')      || 'MEDIUM',
    qual:     localStorage.getItem('qual')     || 'MAINTAINABILITY',
    snippets: Number(localStorage.getItem('snippets') || 5),
    issueKey: localStorage.getItem('issueKey') || '',
    activeTab: localStorage.getItem('activeTab') || 'summary',
};

const ctx = {
    state,
    api,
    apiText,
    esc,
    toast,
    qs() {
        const p = new URLSearchParams();
        if (state.project) p.set('project', state.project);
        p.set('sev', state.sev);
        p.set('qual', state.qual);
        return p.toString();
    },
    refreshStatus: () => refreshStatusBar(),
};

// ============ Роутер ============

async function activateTab(id) {
    const tab = TAB_BY_ID[id];
    if (!tab) return;

    state.activeTab = id;
    localStorage.setItem('activeTab', id);

    document.querySelectorAll('.tab').forEach(t =>
        t.classList.toggle('active', t.dataset.tab === id));

    const filtersEl = document.getElementById('filters');
    const wanted = new Set(tab.filters || []);

    document.querySelectorAll('#filters label[data-filter]').forEach(l => {
        l.classList.toggle('hidden', !wanted.has(l.dataset.filter));
    });

    if (filtersEl) {
        filtersEl.classList.toggle('hidden', wanted.size === 0);
    }

    document.querySelectorAll('.panel').forEach(p =>
        p.classList.toggle('active', p.id === `panel-${id}`));

    const activePanel = document.getElementById(`panel-${id}`);
    if (activePanel) {
        let contentContainer = activePanel.querySelector('.tab-content');
        if (!contentContainer) {
            contentContainer = document.createElement('div');
            contentContainer.className = 'tab-content';
            activePanel.appendChild(contentContainer);
        }
        await tab.mount(contentContainer, ctx);
    }
}

// ============ Загрузка ключей ============

async function loadKeysToSelect() {
    const select = document.getElementById('issueKey');
    if (!select) return;

    const currentVal = select.value;
    select.innerHTML = '<option value="">Загрузка...</option>';

    try {
        const d = await api('/api/keys?' + ctx.qs());
        const keys = d.keys || [];

        select.innerHTML = '<option value="">-- Выберите ключ --</option>';
        keys.forEach(k => {
            const opt = document.createElement('option');
            opt.value = k;
            opt.textContent = k;
            select.appendChild(opt);
        });

        if (currentVal && keys.includes(currentVal)) {
            select.value = currentVal;
        } else if (keys.length > 0) {
            select.value = keys[0];
            state.issueKey = keys[0];
            localStorage.setItem('issueKey', state.issueKey);
        } else {
            state.issueKey = '';
            localStorage.setItem('issueKey', '');
        }
    } catch (e) {
        select.innerHTML = '<option value="">Ошибка загрузки</option>';
        toast('Не удалось загрузить ключи: ' + e.message, true);
    }
}

async function downloadKeys() {
    const btn = document.getElementById('btn-download-keys');
    const oldText = btn.textContent;
    btn.textContent = '...';
    btn.disabled = true;

    try {
        const d = await api('/api/keys?' + ctx.qs());
        const keys = d.keys || [];

        if (keys.length === 0) {
            toast('Список ключей пуст', true);
            return;
        }

        const text = keys.join('\n');
        const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
        const url = URL.createObjectURL(blob);

        const a = document.createElement('a');
        a.href = url;
        a.download = `keys_${state.project}_${state.sev}_${state.qual}.txt`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);

        toast('Список ключей скачан');
    } catch (e) {
        toast(e.message, true);
    } finally {
        btn.textContent = oldText;
        btn.disabled = false;
    }
}

// ============ status bar & progress ============

async function refreshStatusBar() {
    try {
        const h = await api('/api/health');
        const dot = document.getElementById('status-dot');
        const txt = document.getElementById('status-text');
        const progContainer = document.getElementById('progress-container');
        const progFill = document.getElementById('progress-bar-fill');
        const progText = document.getElementById('progress-text');

        dot.className = 'dot';

        if (!h.sessionReady) {
            dot.classList.add('err');
            txt.textContent = 'session not ready';
            progContainer.classList.add('hidden');
        }
        else if (h.snapshotStatus === 'BUILDING') {
            dot.classList.add('building');
            txt.textContent = 'building snapshot…';

            progContainer.classList.remove('hidden');

            const cur = h.snapshotProgress || 0;
            const tot = h.snapshotProgressTotal || 0;
            const phase = h.snapshotPhase || 'starting';

            if (tot > 0) {
                progFill.classList.remove('indeterminate');
                const pct = Math.min(100, Math.round((cur / tot) * 100));
                progFill.style.width = pct + '%';
                progText.textContent = `${phase} (${cur}/${tot})`;
            } else {
                progFill.classList.add('indeterminate');
                progText.textContent = phase;
            }
        }
        else if (h.snapshotReady) {
            dot.classList.add('ok');
            const at = h.snapshotBuiltAt
                ? new Date(h.snapshotBuiltAt).toLocaleTimeString() : '';
            txt.textContent = `ready · ${h.snapshotIssuesCount} issues · ${at}`;
            progContainer.classList.add('hidden');
        }
        else {
            dot.classList.add('err');
            txt.textContent = 'snapshot empty';
            progContainer.classList.add('hidden');
        }

        if (!state.project && h.defaultProject) {
            state.project = h.defaultProject;
            document.getElementById('project').value = state.project;
            loadMeasures();
        }
    } catch {
        document.getElementById('status-dot').className = 'dot err';
        document.getElementById('status-text').textContent = 'API unreachable';
        document.getElementById('progress-container').classList.add('hidden');
    }
}

// ============ measures (project statistics) ============

async function loadMeasures() {
    const bar = document.getElementById('measures-bar');
    if (!bar) return;

    if (!state.project) {
        bar.classList.add('hidden');
        return;
    }

    try {
        const p = new URLSearchParams({ project: state.project });
        const d = await api('/api/measures?' + p);
        renderMeasures(d);
    } catch (e) {
        console.warn('measures load failed:', e.message);
        bar.classList.add('hidden');
    }
}

function formatNum(n) {
    n = Number(n) || 0;
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000)     return (n / 1_000).toFixed(1) + 'K';
    return String(n);
}

function ratingLetter(r) {
    const n = Math.round(Number(r) || 0);
    if (n < 1) return '—';
    if (n > 5) return 'E';
    return ['A', 'B', 'C', 'D', 'E'][n - 1];
}

function setRatingClass(el, r) {
    if (!el) return;
    el.classList.remove('rating-a', 'rating-b', 'rating-c', 'rating-d', 'rating-e');
    const n = Math.round(Number(r) || 0);
    if (n >= 1 && n <= 5) {
        el.classList.add('rating-' + ['a', 'b', 'c', 'd', 'e'][n - 1]);
    }
}

function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

function renderMeasures(d) {
    const bar = document.getElementById('measures-bar');
    if (!bar) return;
    bar.classList.remove('hidden');

    // Quality Gate
    const status = String(d.alertStatus || '').toUpperCase();
    const qg = document.getElementById('measure-qg');
    if (qg) {
        qg.textContent =
            status === 'OK'    ? '✓ QG' :
            status === 'ERROR' ? '✗ QG' :
            status === 'WARN'  ? '⚠ QG' : '? QG';
        qg.classList.remove('qg-ok', 'qg-err', 'qg-warn', 'qg-unknown');
        qg.classList.add(
            status === 'OK'    ? 'qg-ok' :
            status === 'ERROR' ? 'qg-err' :
            status === 'WARN'  ? 'qg-warn' : 'qg-unknown');
    }

    // Total: legacy violations, иначе сумма MQR
    const total =
        Number(d.violations) ||
        (Number(d.maintainabilityIssues) + Number(d.reliabilityIssues) + Number(d.securityIssues)) ||
        0;
    setText('measure-total', formatNum(total));

    // Разбивка: MQR приоритет, legacy fallback
    const secCount = Number(d.securityIssues)        || Number(d.vulnerabilities) || 0;
    const relCount = Number(d.reliabilityIssues)     || Number(d.bugs)            || 0;
    const mntCount = Number(d.maintainabilityIssues) || Number(d.codeSmells)      || 0;

    setText('measure-security-count',        formatNum(secCount));
    setText('measure-reliability-count',     formatNum(relCount));
    setText('measure-maintainability-count', formatNum(mntCount));

    // Рейтинги
    const secR = document.getElementById('measure-security-rating');
    const relR = document.getElementById('measure-reliability-rating');
    const mntR = document.getElementById('measure-maintainability-rating');

    if (secR) { secR.textContent = ratingLetter(d.securityRating);        setRatingClass(secR, d.securityRating); }
    if (relR) { relR.textContent = ratingLetter(d.reliabilityRating);     setRatingClass(relR, d.reliabilityRating); }
    if (mntR) { mntR.textContent = ratingLetter(d.maintainabilityRating); setRatingClass(mntR, d.maintainabilityRating); }

    // Дополнительные
    setText('measure-accepted', formatNum(d.acceptedIssues || 0));

    const cov = Number(d.coverage);
    setText('measure-coverage', Number.isFinite(cov) ? cov.toFixed(1) + '%' : '—');

    const dup = Number(d.duplicatedLinesDensity);
    setText('measure-dup', Number.isFinite(dup) ? dup.toFixed(1) + '%' : '—');
}

// ============ refreshSnapshot ============

async function refreshSnapshot() {
    if (!state.project) { toast('Сначала задайте project', true); return; }
    const p = new URLSearchParams({ project: state.project });
    try {
        const d = await api('/api/refresh?' + p, { method: 'POST' });
        toast(d.message || d.status);
        refreshStatusBar();
        loadMeasures();
    } catch (e) { toast(e.message, true); }
}

// ============ init ============

function initUI() {
    const nav = document.getElementById('tabs');
    TABS.forEach(t => {
        const b = document.createElement('button');
        b.className = 'tab';
        b.dataset.tab = t.id;
        b.textContent = t.title;
        b.addEventListener('click', () => activateTab(t.id));
        nav.appendChild(b);
    });

    const wrap = document.getElementById('panels');
    TABS.forEach(t => {
        const s = document.createElement('section');
        s.className = 'panel';
        s.id = `panel-${t.id}`;
        wrap.appendChild(s);
    });

    const $ = (id) => document.getElementById(id);
    $('project').value  = state.project;
    $('sev').value      = state.sev;
    $('qual').value     = state.qual;
    $('snippets').value = state.snippets;

    loadKeysToSelect();
    loadMeasures();

    $('project').addEventListener('change', e => {
        state.project = e.target.value.trim();
        localStorage.setItem('project', state.project);
        loadKeysToSelect();
        loadMeasures();
    });
    $('sev').addEventListener('change', e => {
        state.sev = e.target.value;
        localStorage.setItem('sev', state.sev);
        loadKeysToSelect();
    });
    $('qual').addEventListener('change', e => {
        state.qual = e.target.value;
        localStorage.setItem('qual', state.qual);
        loadKeysToSelect();
    });
    $('snippets').addEventListener('change', e => {
        state.snippets = Number(e.target.value) || 0;
        localStorage.setItem('snippets', state.snippets);
    });

    $('issueKey').addEventListener('change', e => {
        state.issueKey = e.target.value;
        localStorage.setItem('issueKey', state.issueKey);
        if (state.activeTab === 'snippet') {
            activateTab('snippet');
        }
    });

    $('btn-download-keys').addEventListener('click', downloadKeys);
    $('btn-load').addEventListener('click', () => {
        loadKeysToSelect();
        loadMeasures();
        activateTab(state.activeTab);
    });
    $('btn-refresh').addEventListener('click', refreshSnapshot);

    $('btn-ollama-settings').addEventListener('click', () => {
        openOllamaSettingsModal(ctx);
    });

    activateTab(state.activeTab);
}

initUI();
refreshStatusBar();
setInterval(refreshStatusBar, 5000);

// Периодическое обновление мер (отдельный интервал — реже, т.к. данные крупные)
setInterval(loadMeasures, 30000);