# Combined part 1 of 5

- files: 18
- bytes: 136508

## SonarIssuesApi.java

```
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


    /**
     * ИЗМЕНЕНО: пустая строка = «БЕЗ фильтра по статусам».
     *
     * При передаче в fetchPageRaw / countWithStatuses / facetWithStatuses
     * параметр issueStatuses НЕ добавляется в запрос, и SonarQube возвращает
     * issues во ВСЕХ статусах (OPEN, CONFIRMED, REOPENED, ACCEPTED,
     * FALSE_POSITIVE, FIXED и любых других, которые есть в проекте).
     *
     * Фильтрация в отчётах (qual-summary, team-zip, team-html, экспорт)
     * выполняется ТОЛЬКО по тегу 'not-require-fix' — через параметр notTags
     * (issues с этим тегом исключаются), а также по sev/qual/rules.
     *
     * Почему не "OPEN,CONFIRMED,REOPENED,ACCEPTED,FALSE_POSITIVE,FIXED":
     *   - REOPENED не поддерживается сервером 10.x+ (MQR-модель),
     *     что вызывало вечный retry и IllegalStateException в fetchPageRaw;
     *   - явный список не нужен: без issueStatuses SonarQube и так
     *     возвращает всё, включая ACCEPTED.
     */
    public static final String ALL_STATUSES = "";
    // public static final String ALL_STATUSES =
    //        "OPEN,CONFIRMED,ACCEPTED,FALSE_POSITIVE,FIXED";

    /**
     * Логический «пакет» для отображения прогресса в UI.
     * SonarQube всё равно режет ps до 500 (см. effectivePsLimit),
     * поэтому 3000 = 6 подстраниц по 500. В UI показываем «пакет X/Y».
     */
    private static final int BATCH_SIZE = 3000;

    private static final int FILE_HEAD_LINES = 50;
    private static final int SNIPPET_CONTEXT = 10;
    private static final int SAFETY_MAX_PAGES = 10_000;

    private static final int HARD_LIMIT_10000 = 10_000;
    private static final int STATS_CHUNK_SIZE = 3_000;
    private static final double FACET_COVERAGE_THRESHOLD = 0.9;

    private static final Set<String> DEFAULT_MQR_STATUSES = Set.of(
            "OPEN", "CONFIRMED", "FALSE_POSITIVE", "ACCEPTED", "FIXED"
    );

    private static final Map<String, String> LEGACY_TO_MQR_STATUS = Map.of(
            "REOPENED", "OPEN",
            "CLOSED",   "FIXED",
            "WONTFIX",  "FALSE_POSITIVE",
            "RESOLVED", "FIXED"
    );

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

    private final Set<String> failedComponents = ConcurrentHashMap.newKeySet();

    private static boolean reopenedSupported = true;
    private static volatile int effectivePsLimit = FETCH_PAGE_SIZE;
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

    @FunctionalInterface
    public interface FetchProgressListener {
        void onProgress(int fetched, int total, String message);
    }

    /**
     * Отправляет в listener сообщение с разбивкой по «пакетам» (BATCH_SIZE=3000).
     * Формат: "<context> — пакет 3/8, загружено 1500/4608".
     */
    private static void emitBatch(FetchProgressListener listener,
                                  int current, int total, String context) {
        if (listener == null) return;
        int safeTotal  = Math.max(1, total);
        int batchNum   = Math.max(1, (current + BATCH_SIZE - 1) / BATCH_SIZE);
        int batchTotal = Math.max(1, (safeTotal + BATCH_SIZE - 1) / BATCH_SIZE);
        listener.onProgress(current, safeTotal,
                String.format("%s — пакет %d/%d, загружено %d/%d",
                        context, batchNum, batchTotal, current, safeTotal));
    }

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

    private String sanitizeStatuses(String statuses) {
        if (statuses == null || statuses.isEmpty()) return "";

        Set<String> allowed = serverAllowedStatuses;
        if (allowed == null || allowed.isEmpty()) allowed = DEFAULT_MQR_STATUSES;

        Set<String> result = new LinkedHashSet<>();
        List<String> dropped = new ArrayList<>();

        for (String raw : statuses.split(",")) {
            String t = raw.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;

            // ИЗМЕНЕНО: сначала ВСЕГДА мапим legacy → MQR (REOPENED→OPEN,
            // RESOLVED/CLOSED→FIXED, WONTFIX→FALSE_POSITIVE) и только потом
            // сверяемся с allowed. Это гарантирует, что REOPENED никогда
            // не уйдёт на сервер в MQR-модели, даже если он случайно
            // попал в serverAllowedStatuses.
            String mapped = LEGACY_TO_MQR_STATUS.get(t);
            if (mapped != null) {
                if (allowed.contains(mapped)) {
                    result.add(mapped);
                } else {
                    dropped.add(t);
                }
                continue;
            }

            if (allowed.contains(t)) {
                result.add(t);
            } else {
                dropped.add(t);
            }
        }

        if (!dropped.isEmpty()) {
            System.err.println("[SonarIssuesApi] Отфильтрованы статусы, "
                    + "неподдерживаемые сервером: " + dropped
                    + " (допустимые: " + allowed + ")");
        }

        return result.isEmpty() ? "" : String.join(",", result);
    }

    private String buildQueryWithStatuses(String statuses, String impactSeverity, String quality,
                                          String rule, String tags, String notTags) {
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
        if (tags != null && !tags.isEmpty()) {
            q.append("&tags=").append(enc(tags));
        }
        if (notTags != null && !notTags.isEmpty()) {
            q.append("&notTags=").append(enc(notTags));
        }
        String st = sanitizeStatuses(statuses);
        if (!st.isEmpty()) {
            q.append("&issueStatuses=").append(enc(st));
        }
        q.append("&additionalFields=").append(enc("_all"));
        q.append("&timeZone=").append(enc("Asia/Novosibirsk"));
        return q.toString();
    }

    public JSONObject fetchPageRaw(String statuses, String impactSeverity, String quality,
                                   String rule, int p, int ps) throws Exception {
        return fetchPageRaw(statuses, impactSeverity, quality, rule, null, null, p, ps);
    }

    public JSONObject fetchPageRaw(String statuses, String impactSeverity, String quality,
                                   String rule, String tags, String notTags, int p, int ps) throws Exception {
        // ИЗМЕНЕНО: работаем с локальной копией, чтобы на retry пересобрать запрос
        // с другим набором статусов. Раньше catch-блок печатал "Переключаемся на …",
        // но `continue` крутил тот же запрос, и через 3 попытки летел
        // IllegalStateException. Теперь при retry реально пересобираем statuses.
        String effectiveStatuses = statuses;
        int attempt = 0;
        while (attempt++ < 3) {
            String template = "/api/issues/search?"
                    + buildQueryWithStatuses(effectiveStatuses, impactSeverity, quality, rule, tags, notTags)
                    + "&ps={PS}&p=" + p;
            try {
                return getJsonWithPsFallback(template, ps, "issues/search");
            } catch (RuntimeException e) {
                String msg = e.getMessage();

                // 1) Сервер сообщил список допустимых статусов — обновляем кэш
                //    И пересобираем запрос через sanitizeStatuses.
                if (msg != null && msg.contains("issueStatuses")
                        && msg.toLowerCase().contains("must be one of")) {
                    Set<String> parsed = parseAllowedStatusesFromError(msg);
                    if (parsed != null && !parsed.isEmpty()
                            && !parsed.equals(serverAllowedStatuses)) {
                        serverAllowedStatuses = parsed;
                        String sanitized = sanitizeStatuses(effectiveStatuses);
                        System.err.println("[SonarIssuesApi] Сервер сообщил допустимые статусы: "
                                + parsed + ". Пересобираю " + effectiveStatuses
                                + " → " + (sanitized.isEmpty() ? "<без фильтра>" : sanitized));
                        effectiveStatuses = sanitized;
                        continue;
                    }
                }

                // 2) Сервер не поддерживает legacy-статус (REOPENED и т.п.) —
                //    пересобираем запрос, чтобы убрать его из issueStatuses.
                if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                    reopenedSupported = false;
                    String sanitized = sanitizeStatuses(effectiveStatuses);
                    if (!sanitized.equals(effectiveStatuses) && !sanitized.isEmpty()) {
                        System.err.println("[SonarIssuesApi] Сервер не поддерживает legacy-статус. "
                                + "Пересобираю " + effectiveStatuses + " → " + sanitized);
                        effectiveStatuses = sanitized;
                        continue;
                    }
                    System.err.println("[SonarIssuesApi] Санитизация не изменила запрос ("
                            + effectiveStatuses + "). Пробрасываю ошибку.");
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
                    + buildQueryWithStatuses(statuses, impactSeverity, quality, null, null, null)
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

            // ИСПРАВЛЕНО: emitBatch() + пачковая информация
            emitBatch(listener, grandFetched + out.size(),
                    grandTotal > 0 ? grandTotal : -1,
                    String.format("Файлы %d/%d (пакет %d/%d)",
                            Math.min(i + batch.size(), files.size()),
                            files.size(), batchIdx, totalBatches));

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
        String context = rulesCsv != null && !rulesCsv.isEmpty()
                ? "Правило " + rulesCsv
                : "Загрузка " + (components.length() > 40 ? components.substring(0, 40) + "…" : components);

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

                // ИСПРАВЛЕНО: пакетный прогресс с этапом
                emitBatch(listener, grandFetched + out.size(),
                        grandTotal > 0 ? grandTotal : total,
                        context);

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
                + buildQueryWithStatuses(statuses, impactSeverity, quality, rule, null, null)
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
        return fetchPageRawForComponent(statuses, impactSeverity, quality, rule, component, null, null, p, ps);
    }

    public JSONObject fetchPageRawForComponent(String statuses, String impactSeverity, String quality,
                                               String rule, String component, String tags, String notTags, int p, int ps) throws Exception {
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
        if (tags != null && !tags.isEmpty()) {
            q.append("&tags=").append(enc(tags));
        }
        if (notTags != null && !notTags.isEmpty()) {
            q.append("&notTags=").append(enc(notTags));
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

    public List<Issue> fetchAllByStatuses(String statuses, String impactSeverity,
                                          String quality, FetchProgressListener listener) throws Exception {

        int total = countWithStatuses(statuses, impactSeverity, quality);
        if (listener != null) {
            listener.onProgress(0, total, "Всего issues: " + total
                    + " (пакетов: " + ((total + BATCH_SIZE - 1) / BATCH_SIZE) + ")");
        }
        if (total == 0) return new ArrayList<>();

        if (total <= HARD_LIMIT_10000) {
            return fetchPagedDirectWithProgress(statuses, impactSeverity, quality, total, listener);
        }

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

            // ИСПРАВЛЕНО: пакетный прогресс
            emitBatch(listener, out.size(), total, "Прямая загрузка");

            if (arr.length() < ps) break;
            page++;
        }
        return out;
    }

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

            // ИСПРАВЛЕНО: пакетный прогресс
            emitBatch(listener, out.size(), grandTotal,
                    String.format("Пакет файлов %d/%d (%d файлов, ~%d issues)",
                            chunkIdx, chunks.size(), keys.size(), chunkSum));

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
```

## jira/JiraIssueCommentService.java

```
package ru.miacomsoft.sonar.jira;

import org.json.JSONObject;
import ru.miacomsoft.sonar.jira.JiraClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class JiraIssueCommentService {

    private final JiraClient jira;

    public JiraIssueCommentService(JiraClient jira) { this.jira = jira; }

    public void add(String issueIdOrKey, String body) throws IOException, InterruptedException {
        jira.post("/rest/api/2/issue/" + enc(issueIdOrKey) + "/comment",
                new JSONObject().put("body", body).toString());
    }

    public int count(String issueIdOrKey) throws IOException, InterruptedException {
        return new JSONObject(jira.get("/rest/api/2/issue/" + enc(issueIdOrKey) + "/comment"))
                .optInt("total", 0);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
```

## server/Defaults.java

````
package ru.miacomsoft.sonar.server;

/**
 * Шаблоны файлов, которые сервер создаёт при первом запуске.
 * Существующие файлы никогда не перезаписываются.
 */
public final class Defaults {

    private Defaults() {}

    // ============================================================
    // session.properties — рабочий скелет.
    // Создаётся автоматически, если файла нет в корне проекта.
    // Пользователь заполняет sonar.url + sonar.token и перезапускает
    // (или делает PUT /api/session с тем же текстом).
    // ============================================================
    public static final String SESSION_PROPERTIES_TEMPLATE = """
            # ========================================================
            # session.properties — параметры подключения к SonarQube
            # ========================================================
            #
            # 1. Сгенерируйте Personal Access Token:
            #    https://<sonar>/account/security
            # 2. Заполните поля sonar.url и sonar.token ниже.
            # 3. Перезапустите сервер (или выполните PUT /api/session).
            #
            # sonar.auth:
            #   bearer — SonarQube 9.x / 10.x / 2025.x (рекомендуется)
            #   basic  — старые версии (Authorization: Basic user:token)
            #   cookie — legacy: сырой Cookie из браузера
            # ========================================================

            sonar.url=
            sonar.user=

            sonar.auth=bearer
            sonar.token=

            # --- Альтернатива: cookie-аутентификация (legacy) ---
            #sonar.auth=cookie
            #sonar.cookie=OAUTHSTATE=...; XSRF-TOKEN=...; JWT-SESSION=...
            """;

    // ============================================================
    // prompts/auto-resolve.txt
    // ============================================================
    public static final String PROMPT_AUTO_RESOLVE = """
            Ты — опытный разработчик и эксперт по SonarQube.

            ЗАДАЧА: определить статус, критичность, решение о фиксе, теги и обоснование.

            ОПРЕДЕЛЕНИЯ СТАТУСОВ:
            - falsepositive: SonarQube ошибся.
            - accept: реальная проблема, откладываем.
            - confirm: реальная и срочная.
            - resolve: уже исправлено.
            - wontfix: не будем исправлять.

            === ПРИОРИТЕТ ПРАВИЛ (сверху вниз) ===
            1) РЕАЛЬНАЯ ЛОГИЧЕСКАЯ ОШИБКА — приоритет выше, чем "код сгенерирован".
               Признаки: count($x) < 0, strlen() < 0, size() < 0, index < 0 для
               неотрицательного индекса, недостижимые throw/return/break (dead-code),
               разыменование null без проверки, потеря данных, неверный знак сравнения
               в валидации, off-by-one в срезах, неверный контракт относительно OpenAPI.
               Для таких дефектов: статус = accept или confirm, фикс-тег = "require-fix".

            2) СТИЛИСТИКА / СТРУКТУРА — статус = accept или wontfix,
               фикс-тег = "not-require-fix". Дублирование, отсутствие default в switch,
               длина методов, naming, неиспользуемые импорты, магические числа.

            === НЕ ЯВЛЯЕТСЯ ОШИБКОЙ (типичные false positive) ===
            - ИСПОЛЬЗОВАНИЕ СТРОК ВМЕСТО КОНСТАНТ ("magic strings", "string literals").
            - Дублирование одинаковых строковых литералов в разных местах.
            - Отсутствие default в switch (если switch покрывает enum полностью).
            - Магические числа в тестах и фикстурах.
            - Naming (camelCase/snake_case), отступы, порядок импортов.
            - Отсутствие Javadoc / PHPDoc в сгенерированном коде.
            - Длина метода, вложенность, число параметров в сгенерированном коде.
            - "Unused import" в сгенерированном коде.

            === РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===
            Верни РОВНО ОДИН из двух тегов в поле "Теги:"
            - "require-fix"     — дефект РЕАЛЬНЫЙ, НУЖНО исправить.
            - "not-require-fix" — исправление не требуется.

            === ВЗАИМОИСКЛЮЧАЮЩИЕ ТЕГИ ===
            "require-fix" и "not-require-fix" — взаимоисключающие.
            Верни РОВНО ОДИН из них, НИКОГДА оба одновременно.

            ДОСТУПНЫЕ ТЕГИ: [TAGS_LIST]

            Контекст:
            - Правило: [RULE_NAME_OR_KEY]
            - Сообщение: [MESSAGE]
            - Файл: [LOCATION]
            - Severity: [SEVERITY]
            - Язык: [LANGUAGE]

            Код:
            ```[LANGUAGE_FENCE]```
            
            [CODE]
            """;

    // ============================================================
    // prompts/status-comment.txt
    // ============================================================
    public static final String PROMPT_STATUS_COMMENT = """
            Ты — опытный разработчик и эксперт по статическому анализу кода (SonarQube).
            Проект мультиязычный. НЕ предполагай язык — он указан ниже.

            ЗАДАЧА:
            1. Подтверди или скорректируй выбранный пользователем статус: "[STATUS]" — [STATUS_LABEL]
            2. Определи реальную критичность.
            3. Дай РЕШЕНИЕ: нужно ли исправлять этот баг?
            4. Выбери 2–3 тега из списка доступных (обязательно с фикс-тегом).
            5. Объясни в 1–3 предложениях.

            ОПРЕДЕЛЕНИЯ СТАТУСОВ:
            - falsepositive: SonarQube ошибся, проблемы нет.
            - accept: реальная проблема, исправим позже (техдолг).
            - confirm: реальная и срочная проблема (security, потеря данных).
            - resolve: уже исправлено в коде.
            - wontfix: реальная проблема, но не будем исправлять.

            === РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===
            Верни РОВНО ОДИН из двух тегов:
            - "require-fix"     — баг РЕАЛЬНЫЙ, его НУЖНО исправить.
            - "not-require-fix" — баг НЕ требует исправления.

            ДОСТУПНЫЕ ТЕГИ (используй ТОЛЬКО из этого списка): [TAGS_LIST]

            Контекст:
            - Правило: [RULE_NAME_OR_KEY]
            - Сообщение: [MESSAGE]
            - Файл: [LOCATION]
            - Severity: [SEVERITY]
            - Язык: [LANGUAGE]

            Код:
            ```[LANGUAGE_FENCE]```
            [CODE]
            """;

    // ============================================================
    // prompts/bulk-status-comment.txt
    // ============================================================
    public static final String PROMPT_BULK_STATUS_COMMENT = """
            Ты — опытный разработчик и эксперт по SonarQube.
            Обработай ГРУППУ замечаний (одинаковый статус для всех).

            ЗАДАЧА:
            1. Подтверди статус "[STATUS]" — [STATUS_LABEL] для всей группы.
            2. Определи общую критичность.
            3. Дай ОДНО общее РЕШЕНИЕ: нужно ли исправлять эти баги?
            4. Предложи 2–3 общих тега (обязательно с фикс-тегом).
            5. Объясни в 1–3 предложениях.

            === РЕШЕНИЕ О НЕОБХОДИМОСТИ ИСПРАВЛЕНИЯ ===
            Верни РОВНО ОДИН из двух тегов:
            - "require-fix"     — группа содержит РЕАЛЬНЫЕ баги.
            - "not-require-fix" — исправление не требуется.

            ДОСТУПНЫЕ ТЕГИ: [TAGS_LIST]

            Замечания ([ISSUES_COUNT]):
            [ISSUES_LIST]

            ФОРМАТ ОТВЕТА (СТРОГО):
            1. "Статус: <accept|falsepositive|confirm|resolve|wontfix>"
            2. "Критичность: <подтверждаю|завышена|занижена> — <обоснование>"
            3. "Теги: <require-fix|not-require-fix>, <tag2>, <tag3>"
            4. Строки 4+: 1–3 предложения.
            """;

    // ============================================================
    // prompts/analyze-issue.txt
    // ============================================================
    public static final String PROMPT_ANALYZE_ISSUE = PROMPT_AUTO_RESOLVE;
}
````

## server/PromptTemplate.java

```
package ru.miacomsoft.sonar.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Загружает шаблоны промптов из каталога и подставляет [TAG] → значение.
 *
 * Синтаксис тэгов: [NAME] где NAME = [A-Z_][A-Z0-9_]*.
 * Всё остальное (включая [0], [info], [text with space]) не трогается.
 * Неизвестные тэги заменяются на пустую строку.
 */
public class PromptTemplate {

    private static final Pattern TAG_RE = Pattern.compile("\\[([A-Z_][A-Z0-9_]*)\\]");

    private final Path promptsDir;

    public PromptTemplate(Path promptsDir) {
        this.promptsDir = promptsDir;
    }

    public Path getPromptsDir() {
        return promptsDir;
    }

    public boolean exists(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!name.matches("[A-Za-z0-9_.-]+")) return false;
        return Files.isRegularFile(promptsDir.resolve(name + ".txt"));
    }

    /** Список доступных имён промптов (без .txt), отсортированный. */
    public List<String> listAvailable() {
        if (!Files.isDirectory(promptsDir)) return Collections.emptyList();
        try (Stream<Path> s = Files.list(promptsDir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".txt"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public String loadRaw(String name) throws Exception {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Имя промпта не задано");
        }
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Недопустимое имя промпта: " + name);
        }
        Path file = promptsDir.resolve(name + ".txt");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                    "Промпт не найден: " + name + " (каталог: " + promptsDir + ")");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public String render(String name, Map<String, String> params) throws Exception {
        return renderString(loadRaw(name), params);
    }

    public static String renderString(String raw, Map<String, String> params) {
        if (raw == null) return "";
        if (params == null) params = new LinkedHashMap<>();

        Matcher m = TAG_RE.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tag = m.group(1);
            String val = params.get(tag);
            if (val == null) val = "";
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
```

## server/SessionManager.java

```
package ru.miacomsoft.sonar.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class SessionManager {

    public static final String DEFAULT_FILE = "session.properties";

    private final Path configFile;

    private String url;
    private String user;
    private String token;
    private String cookie;
    private String authType;

    public SessionManager(Path configFile) throws IOException {
        this.configFile = configFile;
        load();
    }

    public SessionManager(String rawContent) throws IOException {
        this.configFile = null;
        loadFromString(rawContent);
    }

    private void load() throws IOException {
        if (configFile == null || !Files.exists(configFile)) {
            throw new IllegalArgumentException("Файл конфигурации не найден: "
                    + (configFile == null ? "<null>" : configFile.toAbsolutePath()));
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        }
        applyProps(props);
    }

    private void loadFromString(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Пустое содержимое session.properties");
        }
        Properties props = new Properties();
        try (StringReader sr = new StringReader(raw)) {
            props.load(sr);
        }
        applyProps(props);
    }

    private void applyProps(Properties props) {
        this.url      = trim(props.getProperty("sonar.url", ""));
        this.user     = trim(props.getProperty("sonar.user", ""));
        this.token    = trim(props.getProperty("sonar.token", ""));
        this.cookie   = trim(props.getProperty("sonar.cookie", ""));
        this.authType = trim(props.getProperty("sonar.auth", "bearer")).toLowerCase();

        if (url.isEmpty()) {
            throw new IllegalArgumentException("В session.properties не задан sonar.url");
        }
        if ("cookie".equals(authType)) {
            if (cookie.isEmpty()) {
                throw new IllegalArgumentException(
                        "sonar.auth=cookie, но sonar.cookie пуст. Вставьте Cookie из браузера.");
            }
        } else if ("basic".equals(authType)) {
            if (token.isEmpty()) {
                throw new IllegalArgumentException(
                        "sonar.auth=basic, но sonar.token пуст (нужен personal access token).");
            }
            if (user.isEmpty()) {
                throw new IllegalArgumentException(
                        "sonar.auth=basic, но sonar.user пуст.");
            }
        } else {
            if (token.isEmpty()) {
                throw new IllegalArgumentException(
                        "sonar.auth=bearer, но sonar.token пуст. "
                                + "Сгенерируйте токен: https://<sonar>/account/security");
            }
        }
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    public String  getUrl()        { return url; }
    public String  getUser()       { return user; }
    public String  getToken()      { return token; }
    public String  getCookie()     { return cookie; }
    public String  getAuthType()   { return authType; }
    public Path    getConfigFile() { return configFile; }

    public boolean hasToken()  { return token != null && !token.isBlank(); }
    public boolean hasCookie() { return cookie != null && !cookie.isBlank(); }
    public boolean isCookieAuth() { return "cookie".equalsIgnoreCase(authType); }

    public String getHost() {
        try {
            java.net.URI u = java.net.URI.create(url);
            String h = u.getHost();
            int    p = u.getPort();
            if (h == null || h.isBlank()) return url;
            return (p > 0 && p != 80 && p != 443) ? h + ":" + p : h;
        } catch (Exception e) {
            return url;
        }
    }

    public String readRaw() {
        if (configFile == null || !Files.exists(configFile)) return "";
        try {
            return Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
```

## server/StaticHandler.java

```
package ru.miacomsoft.sonar.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Раздаёт статику из каталога WWW.
 *  - "/"           -> index.html
 *  - любой файл    -> отдаётся как есть, MIME по расширению
 *  - /api/*        -> 404 JSON (чтобы статика не «съела» несуществующие API-пути)
 *  - защита от path traversal: результат normalize() обязан начинаться с root
 */
public class StaticHandler implements HttpHandler {

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html",  "text/html; charset=utf-8"),
            Map.entry("css",   "text/css; charset=utf-8"),
            Map.entry("js",    "application/javascript; charset=utf-8"),
            Map.entry("mjs",   "application/javascript; charset=utf-8"),
            Map.entry("json",  "application/json; charset=utf-8"),
            Map.entry("svg",   "image/svg+xml"),
            Map.entry("png",   "image/png"),
            Map.entry("jpg",   "image/jpeg"),
            Map.entry("jpeg",  "image/jpeg"),
            Map.entry("gif",   "image/gif"),
            Map.entry("ico",   "image/x-icon"),
            Map.entry("woff",  "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf",   "font/ttf"),
            Map.entry("map",   "application/json")
    );

    private final Path root;

    public StaticHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }

            String path = ex.getRequestURI().getPath();
            if (path == null || path.isEmpty()) path = "/";

            // API-пути, которые не поймали реальные хендлеры, не отдаём как статику
            if (path.startsWith("/api/")) {
                sendJson(ex, 404, "{\"error\":\"Not Found\",\"status\":404}");
                return;
            }

            if (path.equals("/") || path.equals("/index.html")) {
                Path idx = root.resolve("index.html");
                if (Files.isRegularFile(idx)) { sendFile(ex, idx); return; }
                sendText(ex, 404, "index.html not found in " + root);
                return;
            }

            // Срезаем ведущий '/', резолвим, нормализуем — и проверяем, что не убежали из root
            Path target = root.resolve(path.substring(1)).normalize();
            if (!target.startsWith(root)) {
                sendText(ex, 403, "Forbidden");
                return;
            }
            if (!Files.isRegularFile(target)) {
                sendText(ex, 404, "Not Found: " + path);
                return;
            }
            sendFile(ex, target);
        } finally {
            ex.close();
        }
    }

    private void sendFile(HttpExchange ex, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        int i = name.lastIndexOf('.');
        String ext = i >= 0 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
        String mime = MIME.getOrDefault(ext, "application/octet-stream");

        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", body);
    }

    private static void sendText(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", body);
    }

    private static void send(HttpExchange ex, int status, String mime, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
```

## server/console/ConsoleContext.java

```
package ru.miacomsoft.sonar.server.console;

import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BootstrapFiles;
import ru.miacomsoft.sonar.server.OllamaConfig;
import ru.miacomsoft.sonar.server.PromptTemplate;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Общее изменяемое состояние консоли и хелперы, которые переиспользуются
 * несколькими группами команд.
 *
 * Вынесено из {@code Console}, чтобы:
 *   - уменьшить размер основного класса;
 *   - дать всем подкомандам единый доступ к state/project/flags;
 *   - сосредоточить в одном месте persistence (console.properties)
 *     и общие операции над снимком/issues.
 */
public class ConsoleContext {

    private final ServerState state;
    private final OllamaConfig ollamaConfig;
    private final PromptTemplate template;
    private final Path consoleStateFile;

    private volatile String  project;
    private volatile boolean running           = true;
    private volatile boolean autoRunning       = false;
    private volatile boolean autoStopRequested = false;

    public ConsoleContext(ServerState state, OllamaConfig ollamaConfig, Path consoleStateFile) {
        this.state            = state;
        this.ollamaConfig     = ollamaConfig;
        this.consoleStateFile = consoleStateFile;
        this.template         = new PromptTemplate(BootstrapFiles.resolvePromptsDir());

        String cliProject = state.getDefaultProject();
        String saved      = loadSavedProject();
        if (cliProject != null && !cliProject.isEmpty()) {
            this.project = cliProject;
        } else if (saved != null && !saved.isEmpty()) {
            this.project = saved;
            System.out.println("[console] восстановлен project из " + consoleStateFile + ": " + saved);
        } else {
            this.project = "";
        }
    }

    // ==================== accessors ====================

    public ServerState   state()           { return state; }
    public OllamaConfig  ollamaConfig()    { return ollamaConfig; }
    public PromptTemplate template()       { return template; }
    public Path          consoleStateFile(){ return consoleStateFile; }

    public String getProject() { return project; }

    /** Устанавливает и сохраняет в console.properties. */
    public void setProject(String value) {
        this.project = (value == null) ? "" : value;
        saveProject(this.project);
    }

    /** Сбрасывает project и сохраняет пустое значение. */
    public void clearProject() {
        this.project = "";
        saveProject("");
    }

    public boolean isRunning()             { return running; }
    public void    setRunning(boolean v)   { this.running = v; }

    public boolean isAutoRunning()             { return autoRunning; }
    public void    setAutoRunning(boolean v)   { this.autoRunning = v; }

    public boolean isAutoStopRequested()             { return autoStopRequested; }
    public void    setAutoStopRequested(boolean v)   { this.autoStopRequested = v; }

    // ==================== persistence ====================

    private String loadSavedProject() {
        if (consoleStateFile == null || !Files.exists(consoleStateFile)) return "";
        try (InputStream in = Files.newInputStream(consoleStateFile)) {
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("project", "").trim();
        } catch (IOException e) {
            System.err.println("[console] не удалось прочитать " + consoleStateFile
                    + ": " + e.getMessage());
            return "";
        }
    }

    private void saveProject(String value) {
        if (consoleStateFile == null) return;
        try {
            Properties p = new Properties();
            p.setProperty("project", value == null ? "" : value);
            Path parent = consoleStateFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(consoleStateFile)) {
                p.store(out, "Console state (last selected project)");
            }
        } catch (IOException e) {
            System.err.println("[console] не удалось сохранить " + consoleStateFile
                    + ": " + e.getMessage());
        }
    }

    // ==================== shared helpers ====================

    public boolean requireProject() {
        if (project == null || project.isEmpty()) {
            System.out.println("[console] project не задан. Используйте 'project <KEY>' "
                    + "или задайте --project / SONAR_PROJECT при старте.");
            return false;
        }
        return true;
    }

    public Snapshot snapshotFor(String projectKey) {
        Snapshot s = state.getSnapshot();
        return (s != null && s.projectKey.equals(projectKey)) ? s : null;
    }

    /**
     * ИЗМЕНЕНО: раньше без снимка вызывался {@code api.fetchAll(sev, qual)},
     * который внутри использует ACTIVE_STATUSES (OPEN,CONFIRMED,REOPENED)
     * — то есть БЕЗ ACCEPTED. Из-за этого команды qual-summary / summary /
     * details / perfile / matrix / issues не учитывали принятые замечания
     * (и другие resolved-статусы). Теперь явно запрашиваем ALL_STATUSES
     * (OPEN,CONFIRMED,REOPENED,ACCEPTED,FALSE_POSITIVE,RESOLVED,CLOSED,WONTFIX).
     * sanitizeStatuses() в SonarIssuesApi сам смапит legacy → MQR и отбросит
     * неподдерживаемые сервером статусы.
     *
     * Если снимок уже построен — по-прежнему используем его (снимок содержит
     * ровно те статусы, с которыми он собирался через Snapshot.build()).
     */
    public List<Issue> fetchIssues(String project, String sev, String qual) throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) return s.filter(sev, qual);
        return state.newApi(project)
                .fetchAllByStatuses(SonarIssuesApi.ALL_STATUSES, sev, qual);
    }

    /**
     * ИЗМЕНЕНО: аналогично fetchIssues() — issuesByFile(sev, qual) использует
     * ACTIVE_STATUSES. Заменено на issuesByFileByStatuses(ALL_STATUSES, …),
     * чтобы ACCEPTED попадал в отчёты по файлам.
     */
    public Map<String, List<Issue>> fetchIssuesByFile(String project, String sev, String qual)
            throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) return s.filterByFile(sev, qual);
        return state.newApi(project)
                .issuesByFileByStatuses(SonarIssuesApi.ALL_STATUSES, sev, qual);
    }

    public List<String> fetchSnippetCached(String project, String key) throws Exception {
        Snapshot s = snapshotFor(project);
        if (s != null) {
            List<String> cached = s.snippet(key);
            if (!cached.isEmpty()) return cached;
        }
        return state.newApi(project).fetchSnippet(key);
    }

    public static String[] sevQual(String[] a, int from) {
        String sev  = a.length > from     ? a[from].toUpperCase(Locale.ROOT)         : "MEDIUM";
        String qual = a.length > from + 1 ? a[from + 1].toUpperCase(Locale.ROOT)     : "MAINTAINABILITY";
        return new String[]{sev, qual};
    }

    public static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
```

## server/console/ConsoleHelp.java

```
package ru.miacomsoft.sonar.server.console;

/**
 * Единственная ответственность — вывод длинного текста справки.
 * Вынесено из Console, чтобы не раздувать основной класс.
 */
public final class ConsoleHelp {

    private ConsoleHelp() {}

    public static void print() {
        System.out.println("""
        Console commands (все команды — дубли REST-эндпоинтов):

        Основное:
          help, ?                 — эта справка
          quit, exit, q           — остановить консоль (сервер продолжит работу)
          status                  — состояние сессии, снимка и auto-обработки
          health                  — краткая проверка
          project [KEY | clear]   — показать/задать консольный project
                                    (последний выбор запоминается в console.properties
                                     и восстанавливается при следующем запуске,
                                     если не задан --project=)

        Сессия (session.properties — стандартный SonarQube Web API):
          session show            — прочитать session.properties
          session set <path>      — заменить session.properties из файла + reload + auto build
          session reload          — перечитать session.properties с диска
          session save <path>     — сохранить текущий raw в файл

        Снимок:
          refresh                 — пересобрать снимок в фоне (async)
          refresh wait            — пересобрать снимок синхронно
          refresh issue <KEY>     — обновить один issue в снимке

        Отчёты (по умолчанию sev=MEDIUM, qual=MAINTAINABILITY):
          summary  [SEV] [QUAL]              — сводка + facets
          matrix   [SEV] [QUAL]              — матрица file × rule
          perfile  [SEV] [QUAL]              — замечания по файлам
          details  [SEV] [QUAL] [snippets=N] — подробно с фрагментами кода
          issues   [SEV] [QUAL]              — плоский список
          keys     [SEV] [QUAL]              — только ключи
          snippet  <KEY>                     — один issue + код

          qual-summary [QUAL]:[SEV]...       — Сводный отчет по категориям (Quality) и уровням (Severity).
                                               Показывает количество ошибок для каждой категории в проекте.
                                               Исключает замечания с тегом 'not-require-fix'.
                                               Если аргументы не указаны, выводит все категории со всеми уровнями.
                                               [QUAL] — SECURITY, RELIABILITY, MAINTAINABILITY, SECURITY_HOTSPOT, ALL
                                               [SEV] — BLOCKER, HIGH, MEDIUM, LOW, INFO, ALL
                                               Пример 1 (все категории, все уровни):
                                                 qual-summary
                                               Пример 2 (конкретные категории и уровни):
                                                 qual-summary SECURITY:HIGH,BLOCKER RELIABILITY:LOW
                                               Пример 3 (одна категория, все уровни):
                                                 qual-summary SECURITY:ALL

          team-zip [SEV] [QUAL] [имя_файла.zip] — Формирует отчет 'Команды и Модули'
                                               по всему проекту. Раскладывает файлы
                                               по папкам (Отдел/Модуль) и упаковывает в ZIP.
                                               Исключает замечания с тегом 'not-require-fix'.
                                               [SEV] — критичность (BLOCKER, HIGH, MEDIUM, LOW, INFO, ALL).
                                               [QUAL] — качество (SECURITY, RELIABILITY, MAINTAINABILITY, SECURITY_HOTSPOT, ALL).
                                               Можно передать несколько через запятую.
                                               Пример 1: team-zip HIGH ALL report.zip
                                               Пример 2: team-zip BLOCKER,MEDIUM RELIABILITY,SECURITY C:\\reports\\sonar.zip

          team-html [SEV] [QUAL] <каталог>    — Формирует отчет 'Команды и Модули' в виде 
                                               HTML-файлов (без архивации). 
                                               Сначала все файлы генерируются во временном 
                                               общем каталоге, а затем физически перемещаются 
                                               в подкаталоги: <каталог>/Отдел/Модуль/.
                                               В каждом HTML-файле есть информация об ошибке,
                                               фрагмент кода и полный листинг исходника.
                                               Исключает замечания с тегом 'not-require-fix'.
                                               [SEV] и [QUAL] аналогичны team-zip.
                                               Пример 1: team-html HIGH ALL C:\\Reports\\MyProj
                                               Пример 2: team-html BLOCKER,MEDIUM MAINTAINABILITY C:\\Reports\\MyProj

        Пометки:
          mark <KEY> <transition> [comment...]  — пометить один issue
          mark <transition> [SEV] [QUAL] [--yes] — пометить всё по фильтру
              transition = accept | falsepositive | confirm | resolve | wontfix | reopen
              без --yes — dry-run
          cancel <KEY>                          — снять пометку (reopen)
          cancelall [SEV] [QUAL] [--yes]        — снять пометки по фильтру

        Промпты (из prompts/*.txt, как /api/prompt/build в web):
          prompt list                                       — список шаблонов
          prompt <name> <KEY> [status=accept] [statusLabel=...]
          prompt <name> keys=A,B,C [status=...]             — группа issues
          prompt <name> sev=ALL qual=ALL [status=...]       — по фильтру
          prompt <name> ... save=<path>                     — сохранить в файл

          Примеры:
            prompt status-comment 30637c2f-... status=accept
            prompt bulk-status-comment keys=30637c2f-...,abc123 status=accept
            prompt analyze-issue 30637c2f-...
            prompt auto-resolve 30637c2f-... save=/tmp/prompt.txt

          Допустимые <name>:
            status-comment          → prompts/status-comment.txt
            bulk-status-comment     → prompts/bulk-status-comment.txt
            analyze-issue           → prompts/analyze-issue.txt
            auto-resolve            → prompts/auto-resolve.txt

        Ollama (LLM для авто-обработки):
          ollama show                         — текущие настройки
          ollama set url <URL>                — например http://localhost:11434
          ollama set model <MODEL>            — например llama3 или qwen2.5-coder
          ollama set timeout <SECONDS>        — таймаут запроса (по умолчанию 300)
          ollama set temperature <FLOAT>      — температура (по умолчанию 0.2)
          ollama test                         — проверить соединение и список моделей
          ollama reload                       — перечитать *.properties с диска

        Авто-обработка (массовая, аналог кнопки «Добавить автоматически комментарий»):
          auto [SEV] [QUAL]           — dry-run: показать, что будет обработано
          auto [SEV] [QUAL] --yes     — запустить обработку (синхронно)
          auto key <KEY>              — dry-run по одному issue
          auto key <KEY> --yes        — обработать один issue
        """);
    }
}
```

## server/exporters/HtmlPerFileFullExporter.java

```
package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class HtmlPerFileFullExporter {

    public static String formatHtmlFile(List<Issue> issues, String projectName, String host,
                                        String fileName, String fullSource) {
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());
        StringBuilder html = new StringBuilder(8192);

        html.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Отчет: ").append(escapeHtml(projectName))
                .append(" - ").append(escapeHtml(fileName)).append("</title>\n");
        html.append("<style>\n");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;line-height:1.5;color:#172b4d;max-width:1000px;margin:0 auto;padding:20px;background:#fafbfc;}\n");
        html.append("h1,h2{color:#0052cc;margin-bottom:8px;}\n");
        html.append("a{color:#0052cc;text-decoration:none;}a:hover{text-decoration:underline;}\n");
        html.append(".issue{border:1px solid #dfe1e6;border-radius:4px;background:#fff;padding:16px;margin-bottom:16px;}\n");
        html.append("details{margin-top:12px;border:1px solid #dfe1e6;border-radius:4px;background:#f4f5f7;}\n");
        html.append("summary{cursor:pointer;padding:8px 12px;font-weight:600;color:#42526e;user-select:none;}\n");
        html.append("summary:hover{background:#ebecf0;}\n");
        html.append("pre{margin:0;padding:12px;background:#fff;overflow-x:auto;border-top:1px solid #dfe1e6;border-radius:0 0 4px 4px;font-family:monospace;font-size:13px;white-space:pre-wrap;}\n");
        html.append("mark.sonar-issue-mark{background-color:rgba(248,81,73,0.25);border-bottom:2px solid #f85149;color:inherit;padding:0 2px;border-radius:2px;}\n");
        html.append("</style>\n</head>\n<body>\n");

        html.append("<h1>Проект: ").append(escapeHtml(projectName)).append("</h1>\n");
        html.append("<h2>Файл: ").append(escapeHtml(fileName)).append("</h2>\n");
        html.append("<p><em>Сгенерировано: ").append(now)
                .append(" &middot; Всего issues: ").append(issues.size()).append("</em></p>\n<hr/>\n");

        for (Issue it : issues) {
            String link = "https://" + host + "/project/issues?id=" + urlEnc(projectName) + "&open=" + urlEnc(it.key);
            String fullComponentKey = projectName + ":" + it.component;
            String sourceLink = "https://" + host + "/code?id=" + urlEnc(projectName) + "&selected=" + urlEnc(fullComponentKey);

            String ruleName = (it.ruleName != null && !it.ruleName.isEmpty()) ? " — " + it.ruleName : "";
            String tagsStr = (it.tags == null || it.tags.isEmpty()) ? "нет" : String.join(", ", it.tags);
            String lastComment = getLastComment(it);

            html.append("<div class=\"issue\">\n");
            html.append("<p><strong>Идентификатор ошибки:</strong> ").append(escapeHtml(it.key));
            if (it.line > 0) html.append(" (Строка: ").append(it.line).append(")");
            html.append("</p>\n");
            html.append("<p><strong>Ссылка:</strong> <a href=\"").append(escapeHtml(link))
                    .append("\" target=\"_blank\">").append(escapeHtml(link)).append("</a></p>\n");
            html.append("<p><strong>Ссылка на исходник:</strong> <a href=\"")
                    .append(escapeHtml(sourceLink)).append("\" target=\"_blank\">")
                    .append(escapeHtml(fullComponentKey)).append("</a></p>\n");
            html.append("<p><strong>Правило:</strong> ").append(escapeHtml(it.rule))
                    .append(escapeHtml(ruleName)).append("</p>\n");
            html.append("<p><strong>Теги:</strong> ").append(escapeHtml(tagsStr)).append("</p>\n");
            html.append("<p><strong>Сообщение:</strong> ").append(escapeHtml(it.message)).append("</p>\n");
            html.append("<p><strong>Пользовательский комментарий:</strong> ")
                    .append(escapeHtml(lastComment)).append("</p>\n");

            // Блок с фрагментом кода (взят из Issue, если есть)
            if (it.snippet != null && !it.snippet.isEmpty()) {
                html.append("<details>\n<summary>Показать фрагмент кода</summary>\n<pre><code>");
                for (int i = 0; i < it.snippet.size(); i++) {
                    if (i > 0) html.append('\n');
                    html.append(renderSnippetLine(it.snippet.get(i)));
                }
                html.append("</code></pre>\n</details>\n");
            }
            html.append("</div>\n");
        }

        // Блок с полным исходным кодом файла
        if (fullSource != null && !fullSource.isEmpty()) {
            html.append("<div class=\"issue\" style=\"background:#f4f5f7;\">\n");
            html.append("<details>\n<summary>Показать полный исходный код файла</summary>\n<pre><code>");
            html.append(escapeHtml(fullSource));
            html.append("</code></pre>\n</details>\n");
            html.append("</div>\n");
        }

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static String renderSnippetLine(String line) {
        if (line == null) return "";
        String result = escapeHtml(line)
                .replace("§HL_START§", "<mark class=\"sonar-issue-mark\">")
                .replace("§HL_END§", "</mark>");
        return result.replace("<mark class=\"sonar-issue-mark\"></mark>", "");
    }

    private static String getLastComment(Issue issue) {
        if (issue.comments == null || issue.comments.isEmpty()) return "нет";
        Issue.Comment latest = null;
        for (Issue.Comment c : issue.comments) {
            if (latest == null || String.valueOf(c.createdAt).compareTo(String.valueOf(latest.createdAt)) > 0) {
                latest = c;
            }
        }
        if (latest == null) return "нет";
        if (latest.markdown != null && !latest.markdown.isEmpty()) return latest.markdown;
        return latest.htmlText != null ? latest.htmlText : "нет";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}
```

## server/exporters/JiraExporter.java

```
package ru.miacomsoft.sonar.server.exporters;

import ru.miacomsoft.sonar.Issue;

public class JiraExporter {
    public static String exportSingle(Issue issue, String projectName, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("h4. ").append(issue.key).append("\n");
        sb.append("*Ссылка:* https://").append(host).append("/project/issues?id=").append(projectName).append("&open=").append(issue.key).append("\n");
        sb.append("*Правило:* ").append(issue.rule).append("\n");
        sb.append("*Файл:* ").append(issue.component).append(":").append(issue.line).append("\n");
        sb.append("*Сообщение:* ").append(issue.message).append("\n");
        sb.append("{code}\n").append(getSnippetAsString(issue)).append("\n{code}\n\n");
        return sb.toString();
    }
    private static String getSnippetAsString(Issue it) {
        return "// Код недоступен в серверном экспорте (требуется асинхронная загрузка сниппетов)";
    }
}
```

## server/handlers/CancelHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/cancel
 *
 * Два режима:
 *   1) ?key=ISSUE_KEY&dryRun=true|false
 *      — снять пометку (reopen) с одного issue.
 *
 *   2) ?sev=&qual=&dryRun=true|false
 *      — снять пометки со всех issues по фильтру (reopen по одному).
 *
 * dryRun=true по умолчанию — только план, ничего не меняется.
 */
public class CancelHandler extends BaseHandler {

    public CancelHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String key = param(q, "key", null);
        String sev = param(q, "sev", "MEDIUM");
        String qual = param(q, "qual", "MAINTAINABILITY");
        boolean dryRun = paramBool(q, "dryRun", true);

        SonarBulkApi bulk = newBulk();

        if (key != null && !key.isEmpty()) {
            handleSingle(ex, project, key, dryRun, bulk);
        } else {
            handleBulk(ex, project, sev, qual, dryRun, bulk);
        }
    }

    private void handleSingle(HttpExchange ex, String project, String key,
                              boolean dryRun, SonarBulkApi bulk) throws Exception {
        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("key", key);
        o.put("dryRun", dryRun);

        if (dryRun) {
            o.put("applied", false);
            sendJson(ex, 200, o);
            return;
        }

        boolean success;
        try {
            Issue stub = new Issue();
            stub.key = key;
            Set<String> failed = bulk.reopenIssues(List.of(stub));
            success = !failed.contains(key);
            if (!success) o.put("error", "reopen failed");
        } catch (Exception e) {
            success = false;
            o.put("error", e.getMessage());
        }

        o.put("applied", true);
        o.put("success", success);
        sendJson(ex, 200, o);
    }

    private void handleBulk(HttpExchange ex, String project, String sev, String qual,
                            boolean dryRun, SonarBulkApi bulk) throws Exception {
        SonarIssuesApi api = newApi(project);
        List<Issue> issues = api.fetchAll(sev, qual);

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("issuesCount", issues.size());
        o.put("dryRun", dryRun);

        if (dryRun) {
            o.put("applied", false);
            sendJson(ex, 200, o);
            return;
        }

        Set<String> failed = bulk.reopenIssues(issues);
        o.put("applied", true);
        o.put("succeeded", issues.size() - failed.size());
        o.put("failed", failed.size());
        o.put("failedKeys", new JSONArray(failed));
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/ChangelogHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.Map;

/**
 * GET /api/changelog?issueKey=KEY
 */
public class ChangelogHandler extends BaseHandler {

    public ChangelogHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String issueKey = param(q, "issueKey", null);

        if (issueKey == null || issueKey.isEmpty()) {
            sendError(ex, 400, "Параметр issueKey обязателен");
            return;
        }

        JSONArray changelog = newApi(project).fetchChangelog(issueKey);

        JSONObject o = new JSONObject();
        o.put("changelog", changelog);

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/OpenIssuesHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.List;
import java.util.Map;

public class OpenIssuesHandler extends BaseHandler {

    public OpenIssuesHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);

        String sev      = param(q, "sev",  "ALL");
        String qual     = param(q, "qual", "ALL");
        String rule     = param(q, "rule", null);
        boolean withCode = paramBool(q, "snippets", true);
        int page = paramInt(q, "p", 1);
        int ps = paramInt(q, "ps", 50);

        SonarIssuesApi api = newApi(project);
        // 6 аргументов
        JSONObject rawResponse = api.fetchPageRaw(SonarIssuesApi.ACTIVE_STATUSES, sev, qual, rule, page, ps);
        JSONArray rawIssues = rawResponse.optJSONArray("issues");

        JSONArray arr = new JSONArray();
        if (rawIssues != null) {
            for (int i = 0; i < rawIssues.length(); i++) {
                Issue it = Issue.from(rawIssues.getJSONObject(i), project);
                JSONObject io = it.toJson();

                if (io.optString("ruleName", "").isEmpty()
                        && it.rule != null && !it.rule.isEmpty()) {
                    try { io.put("ruleName", api.fetchRuleName(it.rule)); }
                    catch (Exception ignored) { io.put("ruleName", ""); }
                }

                if (withCode) {
                    try {
                        List<String> snippet = fetchSnippetCached(project, it.key);
                        io.put("snippet", new JSONArray(snippet));
                    } catch (Exception e) {
                        io.put("snippet", new JSONArray());
                        io.put("snippetError", e.getMessage());
                    }
                } else {
                    io.put("snippet", new JSONArray());
                }

                arr.put(io);
            }
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("severity", sev);
        o.put("quality", qual);
        o.put("rule", rule != null ? rule : "");
        o.put("total", rawResponse.optInt("total", 0));
        o.put("p", page);
        o.put("ps", ps);
        o.put("statuses", new JSONArray(new String[]{"OPEN", "CONFIRMED", "REOPENED"}));
        o.put("snippetsIncluded", withCode);
        o.put("issues", arr);
        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/ProjectsHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

/**
 * GET /api/projects
 * Возвращает полный список доступных проектов в SonarQube.
 */
public class ProjectsHandler extends BaseHandler {

    public ProjectsHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        JSONArray allProjects = new JSONArray();
        int page = 1;
        int pageSize = 500;
        int total = -1;

        try {
            while (total < 0 || allProjects.length() < total) {
                // Используем /api/components/search?qualifiers=TRK, так как он доступен обычным пользователям
                String url = "/api/components/search?qualifiers=TRK&p=" + page + "&ps=" + pageSize;
                String resp = state.getClient().get(url);
                JSONObject root = new JSONObject(resp);

                JSONObject paging = root.optJSONObject("paging");
                if (paging != null) {
                    total = paging.optInt("total", 0);
                }

                JSONArray components = root.optJSONArray("components");
                if (components == null || components.length() == 0) {
                    break;
                }

                for (int i = 0; i < components.length(); i++) {
                    JSONObject comp = components.getJSONObject(i);
                    JSONObject proj = new JSONObject();
                    proj.put("key", comp.optString("key"));
                    proj.put("name", comp.optString("name"));
                    allProjects.put(proj);
                }

                page++;
                if (page > 50) break; // Safety limit
            }
        } catch (Exception e) {
            // Если нет прав даже на просмотр (крайне редко), просто возвращаем пустой список
            System.err.println("[ProjectsHandler] Не удалось получить список проектов: " + e.getMessage());
        }

        JSONObject result = new JSONObject();
        result.put("projects", allProjects);
        sendJson(ex, 200, result);
    }
}
```

## server/handlers/SetTagsHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarBulkApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SetTagsHandler extends BaseHandler {

    public SetTagsHandler(ServerState state) {
        super(state);
    }

    @Override
    protected boolean allowedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        resolveProject(ex); // Валидация проекта
        Map<String, String> q = parseQuery(ex);
        String keysStr  = param(q, "keys",  "");
        String issueStr = param(q, "issue", "");
        String tagsStr  = param(q, "tags",  "");

// Новый клиент шлёт keys=KEY&issue=KEY; старый — только keys=KEY1,KEY2.
// Если keys пуст, но issue передан — используем issue как единственный ключ.
        if (keysStr.isEmpty() && !issueStr.isEmpty()) {
            keysStr = issueStr;
        }
        if (keysStr.isEmpty()) {
            sendError(ex, 400, "Параметр keys (или issue) обязателен");
            return;
        }

        List<Issue> issues = new ArrayList<>();
        for (String k : keysStr.split(",")) {
            if (!k.trim().isEmpty()) {
                Issue stub = new Issue();
                stub.key = k.trim();
                issues.add(stub);
            }
        }

        SonarBulkApi bulk = newBulk();
        bulk.setTags(issues, tagsStr);

        JSONObject o = new JSONObject();
        o.put("success", true);
        o.put("count", issues.size());
        o.put("tags", tagsStr);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/SnippetHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.Issue;
import ru.miacomsoft.sonar.SonarIssuesApi;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;
import ru.miacomsoft.sonar.server.Snapshot;

import java.util.List;
import java.util.Map;

public class SnippetHandler extends BaseHandler {

    public SnippetHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        String project = resolveProject(ex);
        Map<String, String> q = parseQuery(ex);
        String issueKey = param(q, "issueKey", null);
        if (issueKey == null || issueKey.isEmpty()) {
            sendError(ex, 400, "Параметр issueKey обязателен");
            return;
        }

        Snapshot snap = snapshotFor(project);
        Issue issue = snap != null ? snap.byKey.get(issueKey) : null;
        if (issue == null) {
            SonarIssuesApi api = newApi(project);
            issue = api.fetchIssue(issueKey);
            if (issue == null) {
                sendError(ex, 404, "Issue не найден: " + issueKey);
                return;
            }
            try { issue.ruleName = api.fetchRuleName(issue.rule); }
            catch (Exception ignored) {}
        }

        JSONObject o = new JSONObject();
        o.put("project", project);
        o.put("issue", issue.toJson());
        try {
            if (issue.ruleName != null && !issue.ruleName.isEmpty()) {
                o.put("ruleName", issue.ruleName);
            } else {
                o.put("ruleName", newApi(project).fetchRuleName(issue.rule));
            }
        } catch (Exception e) { o.put("ruleName", ""); }

        try {
            List<String> snippet = fetchSnippetCached(project, issueKey);
            o.put("snippet", new JSONArray(snippet));
        } catch (Exception e) {
            o.put("snippet", new JSONArray());
            o.put("snippetError", e.getMessage());
        }

        putSource(o, project);
        sendJson(ex, 200, o);
    }
}
```

## server/handlers/SourceFullHandler.java

```
package ru.miacomsoft.sonar.server.handlers;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.BaseHandler;
import ru.miacomsoft.sonar.server.ServerState;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GET /api/sources/full?key=COMPONENT_KEY&from=1&to=1002&format=json|txt|html
 *
 * Возвращает объединенный листинг кода файла.
 * - format=json (по умолчанию) — возвращает JSON с чистым текстом.
 * - format=txt — возвращает чистый текст (text/plain).
 * - format=html — возвращает HTML-страницу с подсветкой синтаксиса (text/html).
 */
public class SourceFullHandler extends BaseHandler {

    public SourceFullHandler(ServerState state) {
        super(state);
    }

    @Override
    protected void handleRequest(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex);
        String key = param(q, "key", null);
        if (key == null || key.isEmpty()) {
            sendError(ex, 400, "Параметр key обязателен (например: MEDMIS-tMIS.mis:Cache/FileCache.inc)");
            return;
        }

        // По умолчанию запрашиваем с 1-й строки, и до 100000-й (чтобы захватить весь файл)
        int from = paramInt(q, "from", 1);
        int to = paramInt(q, "to", 100000);

        // Формат ответа: json (по умолчанию), txt или html
        String format = param(q, "format", "json").toLowerCase();

        String url = "/api/sources/lines?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&from=" + from + "&to=" + to;

        // Получаем сырой ответ от SonarQube
        String resp = state.getClient().get(url);
        JSONObject root = new JSONObject(resp);
        JSONArray sources = root.optJSONArray("sources");

        StringBuilder sb = new StringBuilder();
        if (sources != null) {
            for (int i = 0; i < sources.length(); i++) {
                JSONObject lineObj = sources.getJSONObject(i);
                String code = lineObj.optString("code", "");

                // Для txt и json форматов убираем HTML-теги и декодируем сущности
                if (!"html".equals(format)) {
                    code = cleanHtml(code);
                }

                sb.append(code);
                if (i < sources.length() - 1) {
                    sb.append("\n");
                }
            }
        }

        if ("txt".equals(format)) {
            // Отдаем чистый текст
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else if ("html".equals(format)) {
            // Отдаем HTML с минимальной стилизацией для подсветки SonarQube
            String html = "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n"
                    + "<title>" + key + "</title>\n"
                    + "<style>\n"
                    + "body { background-color: #0d1117; color: #e6edf3; font-family: monospace; padding: 20px; }\n"
                    + "pre { margin: 0; white-space: pre-wrap; word-wrap: break-word; font-size: 14px; }\n"
                    + "/* Классы подсветки SonarQube */\n"
                    + ".k { color: #ff7b72; font-weight: bold; } /* keyword */\n"
                    + ".s { color: #a5d6ff; } /* string */\n"
                    + ".c, .cd, .j { color: #8b949e; font-style: italic; } /* comments */\n"
                    + ".sym { color: #79c0ff; } /* symbols */\n"
                    + "</style>\n</head>\n<body>\n<pre><code>"
                    + sb.toString()
                    + "</code></pre>\n</body>\n</html>";

            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            // По умолчанию (json)
            JSONObject result = new JSONObject();
            result.put("key", key);
            result.put("format", "json");
            result.put("code", sb.toString());
            result.put("linesCount", sources != null ? sources.length() : 0);
            sendJson(ex, 200, result);
        }
    }

    /**
     * Очищает строку от HTML-тегов и декодирует основные HTML-сущности.
     */
    private static String cleanHtml(String html) {
        if (html == null) return "";
        // Удаляем все HTML-теги (например, <span class="k">)
        String res = html.replaceAll("<[^>]+>", "");
        // Декодируем сущности
        return res.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}
```

## server/ollama/OllamaClient.java

```
package ru.miacomsoft.sonar.server.ollama;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.sonar.server.OllamaConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Тонкий синхронный клиент к Ollama API.
 * Использует /api/generate (stream=false).
 */
public class OllamaClient {

    private final OllamaConfig config;
    private final HttpClient http;

    public OllamaClient(OllamaConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Отправляет prompt и возвращает полный ответ модели. */
    public String chat(String prompt) throws Exception {
        if (!config.isConfigured()) {
            throw new IllegalStateException("Ollama не настроена (url/model). Используйте: ollama set url/model ...");
        }

        JSONObject body = new JSONObject();
        body.put("model",  config.getModel());
        body.put("prompt", prompt);
        body.put("stream", false);

        JSONObject options = new JSONObject();
        options.put("temperature", config.getTemperature());
        body.put("options", options);

        String endpoint = joinUrl(config.getUrl(), "/api/generate");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept",       "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Ollama HTTP " + resp.statusCode()
                    + ": " + truncate(resp.body(), 500));
        }

        JSONObject json = new JSONObject(resp.body());
        if (json.has("error")) {
            throw new RuntimeException("Ollama error: " + json.getString("error"));
        }
        return json.optString("response", "");
    }

    /** Проверка соединения: GET /api/tags (список моделей). */
    public void testConnection() throws Exception {
        String endpoint = joinUrl(config.getUrl(), "/api/tags");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
        JSONObject json = new JSONObject(resp.body());
        JSONArray models = json.optJSONArray("models");
        if (models != null) {
            System.out.println("Доступные модели:");
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.getJSONObject(i);
                System.out.println("  - " + m.optString("name", "?"));
            }
        }
    }

    private static String joinUrl(String base, String path) {
        if (base == null) base = "";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + path;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
```

