package ru.miacomsoft.sonar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SonarIssuesApi {

    public static final String ACTIVE_STATUSES = "OPEN,CONFIRMED,REOPENED";
    public static final int FETCH_PAGE_SIZE = 3000;

    private static final int FILE_HEAD_LINES = 50;
    private static final int SNIPPET_CONTEXT = 10;
    private static final int SAFETY_MAX_PAGES = 10_000;

    /** Жёсткий лимит SonarQube: результат одного запроса ограничен 10 000. */
    private static final int HARD_LIMIT_10000 = 10_000;

    /** Размер «фрагмента» для чанкования файлов при сборе статистики. */
    private static final int STATS_CHUNK_SIZE = 3_000;

    /** Минимальный уровень покрытия facets=files, при котором доверяем чанкованию. */
    private static final double FACET_COVERAGE_THRESHOLD = 0.9;

    /**
     * MQR-статусы, которые SonarQube принимает в параметре {@code issueStatuses}
     * (по умолчанию). Если сервер вернёт свой список — он заменит этот набор
     * через {@link #serverAllowedStatuses}.
     */
    private static final Set<String> DEFAULT_MQR_STATUSES = Set.of(
            "OPEN", "CONFIRMED", "FALSE_POSITIVE", "ACCEPTED", "FIXED"
    );

    /**
     * Маппинг legacy-статусов (старая модель SonarQube) в MQR-статусы.
     * Нужен, чтобы не падать на WONTFIX / CLOSED / REOPENED / RESOLVED.
     */
    private static final Map<String, String> LEGACY_TO_MQR_STATUS = Map.of(
            "REOPENED", "OPEN",
            "CLOSED",   "FIXED",
            "WONTFIX",  "FALSE_POSITIVE",
            "RESOLVED", "FIXED"
    );

    /** Регулярка для парсинга списка допустимых статусов из текста ошибки 400. */
    private static final Pattern ALLOWED_STATUSES_PATTERN = Pattern.compile(
            "must be one of:\\s*\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);

    private static final Pattern PS_LIMIT_LTE = Pattern.compile(
            "must be less than or equal to (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_LIMIT_LT = Pattern.compile(
            "must be less than (\\d+)", Pattern.CASE_INSENSITIVE);

    private final SonarClient client;
    private final String projectKey;
    private final Map<String, String> ruleNameCache;
    private final Map<String, List<String>> snippetCache;

    // кэш недоступных компонентов (sources/lines 404) — чтобы не спамить запросами
    private final Set<String> failedComponents = ConcurrentHashMap.newKeySet();

    private static boolean reopenedSupported = true;
    private static volatile int effectivePsLimit = FETCH_PAGE_SIZE;

    /**
     * Список статусов, который реально принимает текущий сервер.
     * {@code null} — ещё не известно, используем {@link #DEFAULT_MQR_STATUSES}.
     */
    private static volatile Set<String> serverAllowedStatuses = null;

    public SonarIssuesApi(SonarClient client, String projectKey) {
        this(client, projectKey, new HashMap<>(), new HashMap<>());
    }

    public SonarIssuesApi(SonarClient client, String projectKey,
                          Map<String, String> ruleNameCache,
                          Map<String, List<String>> snippetCache) {
        this.client = client;
        this.projectKey = projectKey;
        this.ruleNameCache = ruleNameCache != null ? ruleNameCache : new HashMap<>();
        this.snippetCache  = snippetCache  != null ? snippetCache  : new HashMap<>();
    }

    private static String enc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ============================================================
    // NEW: прогресс-колбэк для SSE-отчёта статистики
    // ============================================================

    @FunctionalInterface
    public interface FetchProgressListener {
        void onProgress(int fetched, int total, String message);
    }

    // ============================================================
    // ps-fallback
    // ============================================================

    private static String normalizeJsonQuotes(String msg) {
        if (msg == null) return null;
        return msg.replace("\\u0027", "'").replace("\\u0022", "\"").replace("\\\"", "\"");
    }

    private static boolean isPsLimitError(String msg) {
        if (msg == null) return false;
        String norm = normalizeJsonQuotes(msg).toLowerCase();
        boolean hasPs = norm.contains("'ps'") || norm.contains("\"ps\"") || norm.contains(" ps ");
        return hasPs && norm.contains("must be less than");
    }

    private static int parsePsLimitFromError(String msg) {
        if (msg == null) return -1;
        String norm = normalizeJsonQuotes(msg);
        Matcher m = PS_LIMIT_LTE.matcher(norm);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {} }
        m = PS_LIMIT_LT.matcher(norm);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {} }
        return -1;
    }

    private static int clampPs(int requestedPs) {
        int limit = effectivePsLimit;
        return requestedPs < limit ? requestedPs : limit;
    }

    private JSONObject getJsonWithPsFallback(String urlTemplate, int requestedPs, String logContext)
            throws Exception {
        int ps = clampPs(requestedPs);
        int safetyRetries = 5;
        while (safetyRetries-- > 0) {
            String url = urlTemplate.replace("{PS}", String.valueOf(ps));
            try {
                return new JSONObject(client.get(url));
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                if (isPsLimitError(msg)) {
                    int parsed = parsePsLimitFromError(msg);
                    int newLimit = (parsed > 0 && parsed < ps) ? parsed : (ps - 1);
                    if (newLimit > 0) {
                        effectivePsLimit = newLimit;
                        System.err.println("[SonarIssuesApi] " + logContext
                                + ": SonarQube ограничил ps до " + newLimit
                                + " (запрошено " + ps + "). Кэширую.");
                        ps = newLimit;
                        continue;
                    }
                }
                throw e;
            }
        }
        throw new IllegalStateException("Не удалось подобрать допустимый ps для " + logContext);
    }

    // ============================================================
    // NEW: нормализация статусов (MQR)
    // ============================================================

    /**
     * Парсит список допустимых статусов из текста ошибки:
     * {@code "... must be one of: [OPEN, CONFIRMED, FALSE_POSITIVE, ACCEPTED, FIXED]"}.
     */
    private static Set<String> parseAllowedStatusesFromError(String msg) {
        if (msg == null) return null;
        String norm = normalizeJsonQuotes(msg);
        Matcher m = ALLOWED_STATUSES_PATTERN.matcher(norm);
        if (!m.find()) return null;
        Set<String> result = new LinkedHashSet<>();
        for (String s : m.group(1).split(",")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) result.add(t);
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Приводит произвольный список статусов к допустимым значениям
     * параметра {@code issueStatuses} в SonarQube MQR:
     * <ul>
     *   <li>валидные MQR-статусы оставляем как есть;</li>
     *   <li>legacy-статусы (REOPENED/CLOSED/WONTFIX/RESOLVED) маппим в ближайший MQR;</li>
     *   <li>неизвестные — отбрасываем с логом;</li>
     *   <li>если после фильтрации ничего не осталось — вернём "", что означает
     *       «не передавать issueStatuses вообще» (эквивалент «все»).</li>
     * </ul>
     *
     * @return строка для параметра {@code issueStatuses} или "" — не передавать.
     */
    private String sanitizeStatuses(String statuses) {
        if (statuses == null || statuses.isEmpty()) return "";

        Set<String> allowed = serverAllowedStatuses;
        if (allowed == null || allowed.isEmpty()) allowed = DEFAULT_MQR_STATUSES;

        Set<String> result = new LinkedHashSet<>();
        List<String> dropped = new ArrayList<>();

        for (String raw : statuses.split(",")) {
            String t = raw.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;

            if (allowed.contains(t)) {
                result.add(t);
                continue;
            }

            String mapped = LEGACY_TO_MQR_STATUS.get(t);
            if (mapped != null && allowed.contains(mapped)) {
                result.add(mapped);
                continue;
            }

            dropped.add(t);
        }

        if (!dropped.isEmpty()) {
            System.err.println("[SonarIssuesApi] Отфильтрованы статусы, "
                    + "неподдерживаемые сервером: " + dropped
                    + " (допустимые: " + allowed + ")");
        }

        return result.isEmpty() ? "" : String.join(",", result);
    }

    // ============================================================
    // Build queries
    // ============================================================

    private String buildQueryWithStatuses(String statuses, String impactSeverity, String quality, String rule) {
        StringBuilder q = new StringBuilder();
        q.append("components=").append(enc(projectKey));
        q.append("&s=").append(enc("FILE_LINE"));
        if (impactSeverity != null && !"ALL".equals(impactSeverity)) {
            q.append("&impactSeverities=").append(enc(impactSeverity));
        }
        if (quality != null && !"ALL".equals(quality)) {
            q.append("&impactSoftwareQualities=").append(enc(quality));
        }
        if (rule != null && !rule.isEmpty()) {
            q.append("&rules=").append(enc(rule));
        }
        String st = sanitizeStatuses(statuses);
        if (!st.isEmpty()) {
            q.append("&issueStatuses=").append(enc(st));
        }
        q.append("&additionalFields=").append(enc("_all"));
        q.append("&timeZone=").append(enc("Asia/Novosibirsk"));
        return q.toString();
    }

    // ============================================================
    // Публичное API
    // ============================================================

    public JSONObject fetchPageRaw(String statuses, String impactSeverity, String quality,
                                   String rule, int p, int ps) throws Exception {
        int attempt = 0;
        while (attempt++ < 3) {
            String template = "/api/issues/search?"
                    + buildQueryWithStatuses(statuses, impactSeverity, quality, rule)
                    + "&ps={PS}&p=" + p;
            try {
                return getJsonWithPsFallback(template, ps, "issues/search");
            } catch (RuntimeException e) {
                String msg = e.getMessage();

                // 1) Сервер сообщил свой список допустимых статусов — кэшируем и повторяем.
                if (msg != null && msg.contains("issueStatuses")
                        && msg.toLowerCase().contains("must be one of")) {
                    Set<String> parsed = parseAllowedStatusesFromError(msg);
                    if (parsed != null && !parsed.isEmpty()
                            && !parsed.equals(serverAllowedStatuses)) {
                        serverAllowedStatuses = parsed;
                        System.err.println("[SonarIssuesApi] Сервер сообщил допустимые статусы: "
                                + parsed + ". Повторяем запрос с фильтрацией.");
                        continue;
                    }
                }

                // 2) Совместимость со старыми сборками без REOPENED.
                if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                    reopenedSupported = false;
                    System.err.println("[SonarIssuesApi] Сервер не поддерживает REOPENED. "
                            + "Переключаемся на OPEN,CONFIRMED.");
                    continue;
                }

                throw e;
            }
        }
        throw new IllegalStateException("fetchPageRaw: не удалось выполнить запрос после повторов");
    }

    public List<Issue> fetchOpenPage(String impactSeverity, String quality, int page, int pageSize) throws Exception {
        JSONObject raw = fetchPageRaw(ACTIVE_STATUSES, impactSeverity, quality, null, page, pageSize);
        JSONArray arr = raw.optJSONArray("issues");
        List<Issue> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                out.add(Issue.from(arr.getJSONObject(i), projectKey));
            }
        }
        return out;
    }

    public int count(String impactSeverity, String quality) throws Exception {
        return countWithStatuses(ACTIVE_STATUSES, impactSeverity, quality);
    }

    public int countWithStatuses(String statuses, String impactSeverity, String quality) throws Exception {
        // Идём через fetchPageRaw, чтобы получить ту же защиту от 400 по статусам/ps.
        JSONObject root = fetchPageRaw(statuses, impactSeverity, quality, null, 1, 1);
        return root.getInt("total");
    }

    public List<Issue> fetchAll(String impactSeverity, String quality) throws Exception {
        return fetchOpen(impactSeverity, quality);
    }

    public List<Issue> fetchAccepted(String impactSeverity, String quality) throws Exception {
        return fetchAllByStatuses("ACCEPTED", impactSeverity, quality);
    }

    public List<Issue> fetchAllByStatuses(String statuses, String impactSeverity, String quality) throws Exception {
        try {
            return fetchAllByStatusesDirect(statuses, impactSeverity, quality);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Can return only the first 10000 results")) {
                System.err.println("[SonarIssuesApi] Достигнут лимит 10000. Разбиваем запрос...");
                return fetchAllByParts(statuses, impactSeverity, quality);
            }
            if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                return fetchAllByStatuses(sanitizeStatuses(statuses), impactSeverity, quality);
            }
            throw e;
        }
    }

    private List<Issue> fetchAllByStatusesDirect(String statuses, String impactSeverity, String quality)
            throws Exception {
        List<Issue> out = new ArrayList<>();
        int page = 1;
        int total = -1;
        while (total < 0 || out.size() < total) {
            String template = "/api/issues/search?"
                    + buildQueryWithStatuses(statuses, impactSeverity, quality, null)
                    + "&ps={PS}&p=" + page;
            JSONObject root = getJsonWithPsFallback(template, FETCH_PAGE_SIZE, "issues/search(direct)");
            total = root.getInt("total");
            JSONArray arr = root.getJSONArray("issues");
            if (arr.isEmpty()) break;
            for (int i = 0; i < arr.length(); i++) {
                out.add(Issue.from(arr.getJSONObject(i), projectKey));
            }
            page++;
            if (page > SAFETY_MAX_PAGES) break;
        }
        return out;
    }

    private List<Issue> fetchAllByParts(String statuses, String impactSeverity, String quality) throws Exception {
        List<Issue> out = new ArrayList<>();
        String[] sevs  = { "BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO" };
        String[] quals = { "MAINTAINABILITY", "RELIABILITY", "SECURITY" };
        boolean sevAll  = (impactSeverity == null || "ALL".equalsIgnoreCase(impactSeverity));
        boolean qualAll = (quality == null || "ALL".equalsIgnoreCase(quality));
        if (!sevAll && !qualAll) {
            System.err.println("[SonarIssuesApi] Лимит 10000 для " + impactSeverity + "/" + quality + ". Разбиваем по файлам...");
            return fetchAllByFiles(statuses, impactSeverity, quality);
        }
        for (String s : sevs) {
            if (!sevAll && !impactSeverity.equalsIgnoreCase(s)) continue;
            for (String q : quals) {
                if (!qualAll && !quality.equalsIgnoreCase(q)) continue;
                try {
                    out.addAll(fetchAllByStatusesDirect(statuses, s, q));
                } catch (RuntimeException e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("Can return only the first 10000 results")) {
                        System.err.println("[SonarIssuesApi] Лимит 10000 для " + s + "/" + q + ". Разбиваем по файлам...");
                        out.addAll(fetchAllByFiles(statuses, s, q));
                    } else if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                        out.addAll(fetchAllByStatusesDirect(sanitizeStatuses(statuses), s, q));
                    } else {
                        throw e;
                    }
                }
            }
        }
        return out;
    }

    public static class BatchLimitExceededException extends RuntimeException {
        public BatchLimitExceededException(String msg) { super(msg); }
    }

    // ============================================================
    // fetchAllByFiles (пачки по 20) — сохранён + перегрузка с прогрессом
    // ============================================================

    private List<Issue> fetchAllByFiles(String statuses, String impactSeverity, String quality) throws Exception {
        return fetchAllByFiles(statuses, impactSeverity, quality, 0, -1, null);
    }

    private List<Issue> fetchAllByFiles(String statuses, String impactSeverity, String quality,
                                        int grandFetched, int grandTotal,
                                        FetchProgressListener listener) throws Exception {
        System.out.println("[SonarIssuesApi] Получаем список всех файлов проекта...");
        List<String> files = fetchProjectFiles();
        System.out.println("[SonarIssuesApi] Найдено файлов: " + files.size() + ". Обходим пачками по 20...");
        List<Issue> out = new ArrayList<>();
        int batchSize = 20;
        int totalBatches = (files.size() + batchSize - 1) / batchSize;
        for (int i = 0; i < files.size(); i += batchSize) {
            int batchIdx = i / batchSize + 1;
            List<String> batch = files.subList(i, Math.min(i + batchSize, files.size()));
            String components = String.join(",", batch);
            if (listener != null) {
                listener.onProgress(grandFetched + out.size(),
                        grandTotal > 0 ? grandTotal : -1,
                        "Пакет файлов " + batchIdx + "/" + totalBatches + " (" + batch.size() + " файлов)");
            }
            try {
                out.addAll(fetchAllByComponents(statuses, impactSeverity, quality, components,
                        grandFetched, grandTotal, listener));
            } catch (BatchLimitExceededException e) {
                System.out.println("[SonarIssuesApi] Пачка превысила лимит. Разбиваем на отдельные файлы...");
                for (String singleFile : batch) {
                    out.addAll(fetchAllByComponents(statuses, impactSeverity, quality, singleFile,
                            grandFetched, grandTotal, listener));
                }
            }
        }
        System.out.println("[SonarIssuesApi] Обход файлов завершен. Собрано: " + out.size() + " замечаний.");
        return out;
    }

    // ============================================================
    // fetchAllByComponents — сохранён + перегрузка с прогрессом
    // ============================================================

    private List<Issue> fetchAllByComponents(String statuses, String impactSeverity,
                                             String quality, String components) throws Exception {
        return fetchAllByComponents(statuses, impactSeverity, quality, components, 0, -1, null);
    }

    private List<Issue> fetchAllByComponents(String statuses, String impactSeverity,
                                             String quality, String components,
                                             int grandFetched, int grandTotal,
                                             FetchProgressListener listener) throws Exception {
        return fetchAllByComponents(statuses, impactSeverity, quality, components,
                null, grandFetched, grandTotal, listener);
    }

    private List<Issue> fetchAllByComponents(String statuses, String impactSeverity,
                                             String quality, String components,
                                             String rulesCsv,
                                             int grandFetched, int grandTotal,
                                             FetchProgressListener listener) throws Exception {
        List<Issue> out = new ArrayList<>();
        int page = 1;
        int total = -1;
        String actualStatuses = sanitizeStatuses(statuses);
        while (total < 0 || out.size() < total) {
            StringBuilder urlSb = new StringBuilder("/api/issues/search?");
            urlSb.append("components=").append(enc(components));
            urlSb.append("&s=").append(enc("FILE_LINE"));
            if (impactSeverity != null && !"ALL".equalsIgnoreCase(impactSeverity))
                urlSb.append("&impactSeverities=").append(enc(impactSeverity));
            if (quality != null && !"ALL".equalsIgnoreCase(quality))
                urlSb.append("&impactSoftwareQualities=").append(enc(quality));
            if (rulesCsv != null && !rulesCsv.isEmpty())
                urlSb.append("&rules=").append(enc(rulesCsv));
            if (!actualStatuses.isEmpty())
                urlSb.append("&issueStatuses=").append(enc(actualStatuses));
            urlSb.append("&additionalFields=").append(enc("_all"));
            urlSb.append("&timeZone=").append(enc("Asia/Novosibirsk"));
            urlSb.append("&ps={PS}&p=").append(page);
            try {
                JSONObject root = getJsonWithPsFallback(urlSb.toString(), FETCH_PAGE_SIZE,
                        "issues/search(component)");
                total = root.getInt("total");
                JSONArray arr = root.getJSONArray("issues");
                if (arr.isEmpty()) break;
                for (int j = 0; j < arr.length(); j++) {
                    out.add(Issue.from(arr.getJSONObject(j), projectKey));
                }
                if (listener != null) {
                    listener.onProgress(grandFetched + out.size(),
                            grandTotal > 0 ? grandTotal : total,
                            "Загружено " + (grandFetched + out.size()) + " issues"
                                    + (grandTotal > 0 ? " из " + grandTotal : ""));
                }
                page++;
                if (page > SAFETY_MAX_PAGES) break;
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("Can return only the first 10000 results")) {
                    if (components.contains(",") || (rulesCsv == null && components.contains(","))) {
                        throw new BatchLimitExceededException("Batch limit exceeded");
                    } else if (rulesCsv != null && rulesCsv.contains(",")) {
                        throw new BatchLimitExceededException("Batch limit exceeded (rules)");
                    } else {
                        System.err.println("[SonarIssuesApi] Файл " + components
                                + (rulesCsv != null ? " / rules " + rulesCsv : "")
                                + " содержит >10000 замечаний. Взяты первые " + out.size() + ".");
                        break;
                    }
                } else {
                    throw e;
                }
            }
        }
        return out;
    }

    public List<String> fetchProjectFilesList() throws Exception {
        List<String> files = new ArrayList<>();
        int page = 1;
        while (page <= SAFETY_MAX_PAGES) {
            String url = "/api/components/tree?component=" + enc(projectKey)
                    + "&qualifiers=FIL&ps={PS}&p=" + page;
            JSONObject root = getJsonWithPsFallback(url, FETCH_PAGE_SIZE, "components/tree");
            JSONArray components = root.optJSONArray("components");
            if (components == null || components.isEmpty()) break;
            for (int i = 0; i < components.length(); i++) {
                files.add(components.getJSONObject(i).getString("key"));
            }
            JSONObject paging = root.optJSONObject("paging");
            if (paging != null) {
                int total = paging.optInt("total", -1);
                if (total >= 0 && files.size() >= total) break;
            }
            page++;
        }
        return files;
    }

    private List<String> fetchProjectFiles() throws Exception {
        return fetchProjectFilesList();
    }

    public Map<String, Integer> facet(String impactSeverity, String quality, String facetName) throws Exception {
        return facetWithStatuses(ACTIVE_STATUSES, impactSeverity, quality, null, facetName);
    }

    public Map<String, Integer> facetWithStatuses(String statuses, String impactSeverity,
                                                  String quality, String rule, String facetName)
            throws Exception {
        String url = "/api/issues/search?"
                + buildQueryWithStatuses(statuses, impactSeverity, quality, rule)
                + "&ps=1&facets=" + enc(facetName);
        JSONObject root = new JSONObject(client.get(url));
        JSONArray facets = root.getJSONArray("facets");
        Map<String, Integer> result = new TreeMap<>();
        for (int i = 0; i < facets.length(); i++) {
            JSONObject f = facets.getJSONObject(i);
            if (!facetName.equals(f.getString("property"))) continue;
            JSONArray vals = f.getJSONArray("values");
            for (int j = 0; j < vals.length(); j++) {
                JSONObject v = vals.getJSONObject(j);
                int c = v.getInt("count");
                if (c > 0) result.put(v.getString("val"), c);
            }
        }
        return result;
    }

    public Map<String, List<Issue>> issuesByFile(String impactSeverity, String quality) throws Exception {
        List<Issue> all = fetchOpen(impactSeverity, quality);
        Map<String, List<Issue>> byFile = new TreeMap<>();
        for (Issue it : all) {
            byFile.computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
        }
        for (List<Issue> list : byFile.values()) {
            list.sort(Comparator.comparingInt(i -> i.line));
        }
        return byFile;
    }

    public Map<String, List<Issue>> issuesByFileByStatuses(String statuses,
                                                           String impactSeverity,
                                                           String quality) throws Exception {
        List<Issue> all = fetchAllByStatuses(statuses, impactSeverity, quality);
        Map<String, List<Issue>> byFile = new TreeMap<>();
        for (Issue it : all) {
            byFile.computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
        }
        for (List<Issue> list : byFile.values()) {
            list.sort(Comparator.comparingInt(i -> i.line));
        }
        return byFile;
    }

    public Issue fetchIssue(String issueKey) throws Exception {
        String url = "/api/issues/search?issues=" + enc(issueKey)
                + "&additionalFields=" + enc("_all");
        JSONObject root = new JSONObject(client.get(url));
        JSONArray arr = root.getJSONArray("issues");
        if (arr.isEmpty()) return null;
        return Issue.from(arr.getJSONObject(0), projectKey);
    }

    public JSONArray fetchChangelog(String issueKey) throws Exception {
        String url = "/api/issues/changelog?issue=" + enc(issueKey);
        JSONObject root = new JSONObject(client.get(url));
        return root.optJSONArray("changelog");
    }

    public List<String> fetchTags(String query) throws Exception {
        String url = "/api/issues/tags?ps=100";
        if (query != null && !query.isEmpty()) {
            url += "&q=" + enc(query);
        }
        JSONObject root = new JSONObject(client.get(url));
        JSONArray arr = root.getJSONArray("tags");
        List<String> res = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            res.add(arr.getString(i));
        }
        return res;
    }

    public String fetchRuleName(String ruleKey) throws Exception {
        if (ruleKey == null || ruleKey.isEmpty()) return "";
        if (ruleNameCache.containsKey(ruleKey)) {
            return ruleNameCache.get(ruleKey);
        }
        String url = "/api/rules/show?key=" + enc(ruleKey);
        JSONObject root = new JSONObject(client.get(url));
        String name = root.getJSONObject("rule").optString("name");
        ruleNameCache.put(ruleKey, name);
        return name;
    }

    // ============================================================
    // SNIPPETS
    // ============================================================

    private static int resolveEffectiveLine(Issue issue) {
        if (issue.line > 0) return issue.line;
        if (issue.textRange != null && issue.textRange.startLine > 0) {
            return issue.textRange.startLine;
        }
        return -1;
    }

    private static boolean hasHighlightableRange(Issue issue) {
        if (issue.textRange == null) return false;
        if (issue.textRange.startLine <= 0 || issue.textRange.endLine <= 0) return false;
        if (issue.textRange.startLine > issue.textRange.endLine) return false;
        if (issue.textRange.startLine == issue.textRange.endLine
                && issue.textRange.startOffset == issue.textRange.endOffset) return false;
        return true;
    }

    public List<String> fetchSnippet(String issueKey) throws Exception {
        if (snippetCache.containsKey(issueKey)) {
            return snippetCache.get(issueKey);
        }
        Issue issue = fetchIssue(issueKey);
        if (issue == null) {
            snippetCache.put(issueKey, Collections.emptyList());
            return Collections.emptyList();
        }

        List<String> lines;
        if (issue.component == null || issue.component.isEmpty()) {
            lines = Collections.emptyList();
        } else {
            int effectiveLine = resolveEffectiveLine(issue);
            boolean expectHighlight = hasHighlightableRange(issue);

            if (effectiveLine > 0) {
                List<String> viaSnippets = tryIssueSnippets(issue);
                boolean hasHighlight = viaSnippets != null
                        && viaSnippets.stream().anyMatch(l -> l.contains("§HL_START§"));

                if (viaSnippets != null && !viaSnippets.isEmpty()) {
                    if (!expectHighlight || hasHighlight) {
                        lines = viaSnippets;
                    } else {
                        List<String> fallback = trySourcesLinesAround(issue, effectiveLine);
                        if (fallback != null && !fallback.isEmpty()) {
                            lines = fallback;
                        } else {
                            lines = viaSnippets;
                        }
                    }
                } else {
                    lines = trySourcesLinesAround(issue, effectiveLine);
                    if (lines == null || lines.isEmpty()) {
                        lines = trySourcesLinesHead(issue, FILE_HEAD_LINES);
                    }
                    if (lines == null) lines = Collections.emptyList();
                }
            } else {
                lines = trySourcesLinesHead(issue, FILE_HEAD_LINES);
            }
        }

        snippetCache.put(issueKey, lines);
        return lines;
    }

    private List<String> tryIssueSnippets(Issue issue) {
        try {
            String url = "/api/sources/issue_snippets?issueKey=" + enc(issue.key);
            String json = client.get(url);
            JSONObject root = new JSONObject(json);
            if (root.isEmpty()) return null;

            String fileKey = root.keys().next();
            JSONObject fileObj = root.getJSONObject(fileKey);
            JSONArray sources = fileObj.getJSONArray("sources");
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < sources.length(); i++) {
                JSONObject src = sources.getJSONObject(i);
                int lineNum = src.getInt("line");
                String code = src.optString("code");
                code = maybeHighlight(code, lineNum, issue);
                code = cleanHtml(code);
                lines.add(String.format("%4d | %s", lineNum, code));
            }
            return lines;
        } catch (Exception e) {
            String m = e.getMessage();
            if (m == null || !m.contains("HTTP 404")) {
                System.err.println("[SonarIssuesApi] issue_snippets failed for "
                        + issue.key + ": " + m);
            }
            return null;
        }
    }

    private List<String> trySourcesLinesAround(Issue issue, int centerLine) {
        String componentKey = buildFullComponentKey(issue.component);
        if (componentKey == null || componentKey.isEmpty()) return null;
        int trEnd = (issue.textRange != null && issue.textRange.endLine >= centerLine)
                ? issue.textRange.endLine
                : centerLine;
        int from = Math.max(1, centerLine - SNIPPET_CONTEXT);
        int to   = trEnd + SNIPPET_CONTEXT;
        return trySourcesLines(issue, componentKey, from, to);
    }

    private List<String> trySourcesLinesHead(Issue issue, int maxLines) {
        String componentKey = buildFullComponentKey(issue.component);
        if (componentKey == null || componentKey.isEmpty()) return Collections.emptyList();
        List<String> result = trySourcesLines(issue, componentKey, 1, Math.max(1, maxLines));
        return result != null ? result : Collections.emptyList();
    }

    private List<String> trySourcesLines(Issue issue, String componentKey, int from, int to) {
        if (failedComponents.contains(componentKey)) {
            return null;
        }
        try {
            String url = "/api/sources/lines?key=" + enc(componentKey)
                    + "&from=" + from + "&to=" + to;
            String json = client.get(url);
            JSONObject root = new JSONObject(json);
            JSONArray sources = root.optJSONArray("sources");
            if (sources == null) return null;

            List<String> lines = new ArrayList<>();
            for (int i = 0; i < sources.length(); i++) {
                JSONObject src = sources.getJSONObject(i);
                int lineNum = src.getInt("line");
                String code = src.optString("code");
                code = maybeHighlight(code, lineNum, issue);
                code = cleanHtml(code);
                lines.add(String.format("%4d | %s", lineNum, code));
            }
            return lines;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("HTTP 404") || msg.contains("not found"))) {
                failedComponents.add(componentKey);
                System.out.println("[SonarIssuesApi] source unavailable for component "
                        + componentKey + " (cached, will skip further attempts)");
            } else {
                System.err.println("[SonarIssuesApi] sources/lines failed for "
                        + componentKey + " [" + from + "-" + to + "]: " + msg);
            }
            return null;
        }
    }

    private String maybeHighlight(String code, int lineNum, Issue issue) {
        if (code == null || code.isEmpty()) return code;
        if (code.contains("<mark class=\"sonar-issue-mark\">")) return code;
        if (!hasHighlightableRange(issue)) return code;
        return applyHighlight(code, lineNum, issue.textRange);
    }

    private String buildFullComponentKey(String component) {
        if (component == null || component.isEmpty()) return null;
        if (projectKey == null || projectKey.isEmpty()) return component;
        if (component.startsWith(projectKey + ":")) return component;
        return projectKey + ":" + component;
    }

    public void invalidateIssue(String issueKey) {
        snippetCache.remove(issueKey);
    }

    private String applyHighlight(String html, int lineNum, Issue.TextRange range) {
        if (range == null) return html;
        if (range.startLine <= 0 || range.endLine <= 0) return html;
        if (range.startLine > range.endLine) return html;
        if (range.startLine == range.endLine
                && range.startOffset == range.endOffset) return html;
        if (lineNum < range.startLine || lineNum > range.endLine) return html;
        if (html.contains("<mark class=\"sonar-issue-mark\">")) return html;

        StringBuilder sb = new StringBuilder();
        int visibleCount = 0;
        boolean startInserted = false;
        boolean endInserted = false;
        if (lineNum > range.startLine) {
            sb.append("<mark class=\"sonar-issue-mark\">");
            startInserted = true;
        }
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') {
                int endTag = html.indexOf('>', i);
                if (endTag != -1) {
                    sb.append(html, i, endTag + 1);
                    i = endTag;
                    continue;
                }
            }
            if (c == '&') {
                int endEntity = html.indexOf(';', i);
                if (endEntity != -1 && endEntity - i < 10) {
                    sb.append(html, i, endEntity + 1);
                    i = endEntity;
                    visibleCount++;
                    if (lineNum == range.endLine && visibleCount == range.endOffset && !endInserted) {
                        sb.append("</mark>");
                        endInserted = true;
                    }
                    continue;
                }
            }
            if (lineNum == range.startLine && visibleCount == range.startOffset && !startInserted) {
                sb.append("<mark class=\"sonar-issue-mark\">");
                startInserted = true;
            }
            sb.append(c);
            visibleCount++;
            if (lineNum == range.endLine && visibleCount == range.endOffset && !endInserted) {
                sb.append("</mark>");
                endInserted = true;
            }
        }
        if (lineNum == range.startLine && !startInserted) {
            sb.insert(0, "<mark class=\"sonar-issue-mark\">");
        }
        if (lineNum == range.endLine && !endInserted) {
            sb.append("</mark>");
        } else if (lineNum < range.endLine && !endInserted) {
            sb.append("</mark>");
        }
        return sb.toString();
    }

    private String cleanHtml(String html) {
        if (html == null) return "";
        Pattern pattern = Pattern.compile("<mark class=\"sonar-issue-mark\">(.*?)</mark>", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb,
                    "§HL_START§" + Matcher.quoteReplacement(matcher.group(1)) + "§HL_END§");
        }
        matcher.appendTail(sb);
        String res = sb.toString();
        res = res.replaceAll("<[^>]+>", "");
        return res.replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    public List<Issue> fetchHotspots(String vulnerabilityProbability) throws Exception {
        List<Issue> out = new ArrayList<>();
        String[] statuses = { "TO_REVIEW", "REVIEWED" };
        for (String status : statuses) {
            int page = 1;
            int total = -1;
            int fetched = 0;
            while ((total < 0 || fetched < total) && page <= SAFETY_MAX_PAGES) {
                String url = "/api/hotspots/search?project=" + enc(projectKey)
                        + "&status=" + enc(status)
                        + "&ps={PS}&p=" + page;
                JSONObject root = getJsonWithPsFallback(url, FETCH_PAGE_SIZE, "hotspots/search");
                int rootTotal = root.optInt("total", -1);
                JSONObject paging = root.optJSONObject("paging");
                if (paging != null) rootTotal = paging.optInt("total", rootTotal);
                total = rootTotal;
                JSONArray arr = root.optJSONArray("hotspots");
                if (arr == null || arr.isEmpty()) break;
                fetched += arr.length();
                for (int i = 0; i < arr.length(); i++) {
                    Issue issue = Issue.fromHotspot(arr.getJSONObject(i), projectKey);
                    if (vulnerabilityProbability != null
                            && !"ALL".equalsIgnoreCase(vulnerabilityProbability)
                            && !vulnerabilityProbability.isEmpty()
                            && !vulnerabilityProbability.equalsIgnoreCase(issue.severity)) {
                        continue;
                    }
                    out.add(issue);
                }
                page++;
            }
        }
        return out;
    }

    public List<Issue> fetchOpen(String impactSeverity, String quality) throws Exception {
        return fetchAllByStatuses(ACTIVE_STATUSES, impactSeverity, quality);
    }

    public JSONObject fetchPageRawForComponent(String statuses, String impactSeverity, String quality,
                                               String rule, String component, int p, int ps) throws Exception {
        StringBuilder q = new StringBuilder();
        q.append("components=").append(enc(component));
        q.append("&s=").append(enc("FILE_LINE"));
        if (impactSeverity != null && !"ALL".equals(impactSeverity)) {
            q.append("&impactSeverities=").append(enc(impactSeverity));
        }
        if (quality != null && !"ALL".equals(quality)) {
            q.append("&impactSoftwareQualities=").append(enc(quality));
        }
        if (rule != null && !rule.isEmpty()) {
            q.append("&rules=").append(enc(rule));
        }
        String st = sanitizeStatuses(statuses);
        if (!st.isEmpty()) {
            q.append("&issueStatuses=").append(enc(st));
        }
        q.append("&additionalFields=").append(enc("_all"));
        q.append("&timeZone=").append(enc("Asia/Novosibirsk"));

        String template = "/api/issues/search?" + q + "&ps={PS}&p=" + p;
        return getJsonWithPsFallback(template, ps, "issues/search(component-page)");
    }

    public JSONObject fetchIssuesByKeys(String keysCsv) throws Exception {
        String url = "/api/issues/search?issues=" + enc(keysCsv)
                + "&additionalFields=" + enc("_all");
        return new JSONObject(client.get(url));
    }

    public String fetchFullSource(String componentKey) {
        if (componentKey == null || componentKey.isEmpty()) return "";
        if (failedComponents.contains(componentKey)) return "";
        try {
            String url = "/api/sources/lines?key=" + enc(componentKey) + "&from=1&to=100000";
            String json = client.get(url);
            JSONObject root = new JSONObject(json);
            JSONArray sources = root.optJSONArray("sources");
            if (sources == null) return "";

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sources.length(); i++) {
                JSONObject lineObj = sources.getJSONObject(i);
                String code = lineObj.optString("code", "");
                String cleanLine = cleanHtml(code);
                sb.append(cleanLine).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("HTTP 404") || msg.contains("not found"))) {
                failedComponents.add(componentKey);
            } else {
                System.err.println("[SonarIssuesApi] Ошибка получения полного кода для "
                        + componentKey + ": " + msg);
            }
            return "";
        }
    }

    public static int getEffectivePsLimit() {
        return effectivePsLimit;
    }

    // ============================================================
    // Статистика по проектам с прогрессом
    // ============================================================

    public List<Issue> fetchAllByStatuses(String statuses, String impactSeverity,
                                          String quality, FetchProgressListener listener) throws Exception {

        int total = countWithStatuses(statuses, impactSeverity, quality);
        if (listener != null) {
            listener.onProgress(0, total, "Всего issues: " + total);
        }
        if (total == 0) return new ArrayList<>();

        // ---------- 1. Простой путь: total ≤ 10 000 ----------
        if (total <= HARD_LIMIT_10000) {
            return fetchPagedDirectWithProgress(statuses, impactSeverity, quality, total, listener);
        }

        // ---------- 2. Пробуем facets=files для чанкования ----------
        Map<String, Integer> fileCounts = null;
        try {
            fileCounts = facetWithStatuses(statuses, impactSeverity, quality, null, "files");
        } catch (Exception e) {
            System.err.println("[SonarIssuesApi] facets=files failed: " + e.getMessage());
        }

        if (fileCounts != null && !fileCounts.isEmpty()) {
            int sum = 0;
            for (int c : fileCounts.values()) sum += c;
            double coverage = (double) sum / total;
            System.out.println("[SonarIssuesApi] facets=files: " + fileCounts.size()
                    + " files, sum=" + sum + ", total=" + total
                    + ", coverage=" + String.format("%.1f", coverage * 100) + "%");
            if (coverage >= FACET_COVERAGE_THRESHOLD) {
                return fetchByFileChunks(statuses, impactSeverity, quality,
                        fileCounts, total, listener);
            }
            System.err.println("[SonarIssuesApi] facets truncated (coverage "
                    + String.format("%.1f", coverage * 100) + "%), откат на обход по components/tree");
        }

        // ---------- 3. Фолбэк: обход по всем файлам, пачки по 20 ----------
        if (listener != null) {
            listener.onProgress(0, total, "Фасеты недоступны, идём по списку файлов...");
        }
        return fetchAllByFiles(statuses, impactSeverity, quality, 0, total, listener);
    }

    private List<Issue> fetchPagedDirectWithProgress(String statuses, String impactSeverity,
                                                     String quality, int expectedTotal,
                                                     FetchProgressListener listener) throws Exception {
        List<Issue> out = new ArrayList<>();
        int ps = computeSafePs(expectedTotal);
        int page = 1;
        int total = expectedTotal;

        while (out.size() < total && page <= SAFETY_MAX_PAGES) {
            JSONObject root = fetchPageRaw(statuses, impactSeverity, quality, null, page, ps);
            if (page == 1) {
                total = root.optInt("total", total);
            }
            JSONArray arr = root.optJSONArray("issues");
            if (arr == null || arr.isEmpty()) break;

            for (int i = 0; i < arr.length(); i++) {
                out.add(Issue.from(arr.getJSONObject(i), projectKey));
            }
            if (listener != null) {
                listener.onProgress(out.size(), total,
                        "Загружено " + out.size() + " из " + total);
            }
            if (arr.length() < ps) break;
            page++;
        }
        return out;
    }

    /** Наибольший ps, при котором ceil(total/ps)*ps ≤ 10 000. */
    private int computeSafePs(int total) {
        if (total <= 0) return 1000;
        int maxPs = Math.min(FETCH_PAGE_SIZE, total);
        for (int ps = maxPs; ps >= 500; ps -= 100) {
            int pages = (total + ps - 1) / ps;
            if ((long) pages * ps <= HARD_LIMIT_10000) return ps;
        }
        return 500;
    }

    private List<Issue> fetchByFileChunks(String statuses, String impactSeverity,
                                          String quality, Map<String, Integer> fileCounts,
                                          int grandTotal, FetchProgressListener listener) throws Exception {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(fileCounts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<List<Map.Entry<String, Integer>>> chunks = new ArrayList<>();
        List<Map.Entry<String, Integer>> current = new ArrayList<>();
        int currentSum = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            if (!current.isEmpty() && currentSum + e.getValue() > STATS_CHUNK_SIZE) {
                chunks.add(current);
                current = new ArrayList<>();
                currentSum = 0;
            }
            current.add(e);
            currentSum += e.getValue();
        }
        if (!current.isEmpty()) chunks.add(current);

        List<Issue> out = new ArrayList<>();
        int chunkIdx = 0;
        for (List<Map.Entry<String, Integer>> chunk : chunks) {
            chunkIdx++;
            List<String> keys = new ArrayList<>();
            int chunkSum = 0;
            boolean hasHugeFile = false;
            for (Map.Entry<String, Integer> e : chunk) {
                keys.add(e.getKey());
                chunkSum += e.getValue();
                if (e.getValue() > HARD_LIMIT_10000) hasHugeFile = true;
            }

            if (listener != null) {
                listener.onProgress(out.size(), grandTotal,
                        "Пакет " + chunkIdx + "/" + chunks.size()
                                + " · " + keys.size() + " файлов · ~" + chunkSum + " issues");
            }

            if (chunk.size() == 1 && hasHugeFile) {
                out.addAll(splitFileByRules(statuses, impactSeverity, quality,
                        keys.get(0), out.size(), grandTotal, listener));
                continue;
            }

            String components = String.join(",", keys);
            try {
                out.addAll(fetchAllByComponents(statuses, impactSeverity, quality,
                        components, out.size(), grandTotal, listener));
            } catch (BatchLimitExceededException e) {
                System.out.println("[SonarIssuesApi] Пакет " + chunkIdx
                        + " превысил лимит — дробим на отдельные файлы");
                for (Map.Entry<String, Integer> entry : chunk) {
                    if (entry.getValue() > HARD_LIMIT_10000) {
                        out.addAll(splitFileByRules(statuses, impactSeverity, quality,
                                entry.getKey(), out.size(), grandTotal, listener));
                    } else {
                        out.addAll(fetchAllByComponents(statuses, impactSeverity, quality,
                                entry.getKey(), out.size(), grandTotal, listener));
                    }
                }
            }
        }
        return out;
    }

    private List<Issue> splitFileByRules(String statuses, String impactSeverity,
                                         String quality, String fileKey,
                                         int grandFetched, int grandTotal,
                                         FetchProgressListener listener) throws Exception {
        if (listener != null) {
            listener.onProgress(grandFetched, grandTotal,
                    "Файл " + fileKey + " содержит >10000 issues, дробим по правилам...");
        }
        Map<String, Integer> ruleCounts;
        try {
            ruleCounts = facetRulesForComponent(statuses, impactSeverity, quality, fileKey);
        } catch (Exception e) {
            System.err.println("[SonarIssuesApi] facets=rules для " + fileKey + " failed: " + e.getMessage());
            return fetchAllByComponents(statuses, impactSeverity, quality, fileKey,
                    grandFetched, grandTotal, listener);
        }
        if (ruleCounts == null || ruleCounts.isEmpty()) {
            System.err.println("[SonarIssuesApi] facets=rules пуст для " + fileKey
                    + " — забираем в пределах лимита");
            return fetchAllByComponents(statuses, impactSeverity, quality, fileKey,
                    grandFetched, grandTotal, listener);
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(ruleCounts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<List<Map.Entry<String, Integer>>> chunks = new ArrayList<>();
        List<Map.Entry<String, Integer>> current = new ArrayList<>();
        int currentSum = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            if (!current.isEmpty() && currentSum + e.getValue() > HARD_LIMIT_10000) {
                chunks.add(current);
                current = new ArrayList<>();
                currentSum = 0;
            }
            current.add(e);
            currentSum += e.getValue();
        }
        if (!current.isEmpty()) chunks.add(current);

        List<Issue> out = new ArrayList<>();
        int ruleChunkIdx = 0;
        for (List<Map.Entry<String, Integer>> chunk : chunks) {
            ruleChunkIdx++;
            List<String> rules = new ArrayList<>();
            for (Map.Entry<String, Integer> e : chunk) rules.add(e.getKey());
            String rulesCsv = String.join(",", rules);
            if (listener != null) {
                listener.onProgress(grandFetched + out.size(), grandTotal,
                        "Файл " + fileKey + ": пакет правил " + ruleChunkIdx + "/" + chunks.size());
            }
            out.addAll(fetchAllByComponents(statuses, impactSeverity, quality,
                    fileKey, rulesCsv, grandFetched + out.size(), grandTotal, listener));
        }
        return out;
    }

    private Map<String, Integer> facetRulesForComponent(String statuses, String impactSeverity,
                                                        String quality, String component) throws Exception {
        StringBuilder q = new StringBuilder();
        q.append("components=").append(enc(component));
        q.append("&s=").append(enc("FILE_LINE"));
        if (impactSeverity != null && !"ALL".equalsIgnoreCase(impactSeverity))
            q.append("&impactSeverities=").append(enc(impactSeverity));
        if (quality != null && !"ALL".equalsIgnoreCase(quality))
            q.append("&impactSoftwareQualities=").append(enc(quality));
        String st = sanitizeStatuses(statuses);
        if (!st.isEmpty()) q.append("&issueStatuses=").append(enc(st));
        q.append("&additionalFields=").append(enc("_all"));
        q.append("&timeZone=").append(enc("Asia/Novosibirsk"));
        q.append("&ps=1&facets=").append(enc("rules"));

        JSONObject root = new JSONObject(client.get("/api/issues/search?" + q));
        JSONArray facets = root.optJSONArray("facets");
        Map<String, Integer> result = new TreeMap<>();
        if (facets == null) return result;
        for (int i = 0; i < facets.length(); i++) {
            JSONObject f = facets.getJSONObject(i);
            if (!"rules".equals(f.optString("property"))) continue;
            JSONArray vals = f.optJSONArray("values");
            if (vals == null) continue;
            for (int j = 0; j < vals.length(); j++) {
                JSONObject v = vals.getJSONObject(j);
                int c = v.optInt("count", 0);
                if (c > 0) result.put(v.optString("val"), c);
            }
        }
        return result;
    }
}