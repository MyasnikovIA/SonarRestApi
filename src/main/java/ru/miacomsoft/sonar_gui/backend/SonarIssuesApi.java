package ru.miacomsoft.sonar_gui.backend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SonarIssuesApi {

    public static final String ACTIVE_STATUSES = "OPEN,CONFIRMED,REOPENED";
    public static final int    FETCH_PAGE_SIZE = 3000;

    public static final String ALL_STATUSES = "";

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
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    public interface FetchProgressListener {
        void onProgress(int fetched, int total, String message);
    }

    private static void emitBatch(FetchProgressListener listener,
                                  int current, int total, String context) {
        if (listener == null) return;
        int safeTotal  = Math.max(1, total);
        int batchNum   = Math.max(1, (current + BATCH_SIZE - 1) / BATCH_SIZE);
        int batchTotal = Math.max(1, (safeTotal + BATCH_SIZE - 1) / BATCH_SIZE);
        String msg = String.format("%s — пакет %d/%d, загружено %d/%d",
                context, batchNum, batchTotal, current, safeTotal);
        System.out.println("[SonarIssuesApi] " + msg);
        listener.onProgress(current, safeTotal, msg);
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
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {} }
        m = PS_LIMIT_LT.matcher(norm);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {} }
        return -1;
    }

    private static int clampPs(int requestedPs) {
        int limit = effectivePsLimit;
        return Math.min(requestedPs, limit);
    }

    private JSONObject getJsonWithPsFallback(String urlTemplate, int requestedPs,
                                             String logContext) throws Exception {
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
                        System.out.println("[SonarIssuesApi] Сервер отклонил ps=" + ps + ". Снижаем лимит до " + newLimit + " для " + logContext);
                        effectivePsLimit = newLimit;
                        ps = newLimit;
                        continue;
                    }
                }
                System.err.println("[SonarIssuesApi] Ошибка запроса к " + logContext + ": " + msg);
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
        for (String raw : statuses.split(",")) {
            String t = raw.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            String mapped = LEGACY_TO_MQR_STATUS.get(t);
            if (mapped != null) {
                if (allowed.contains(mapped)) result.add(mapped);
                continue;
            }
            if (allowed.contains(t)) result.add(t);
        }
        return result.isEmpty() ? "" : String.join(",", result);
    }

    private String buildQueryWithStatuses(String statuses, String impactSeverity,
                                          String quality, String rule, String tags,
                                          String notTags) {
        StringBuilder q = new StringBuilder();
        q.append("components=").append(enc(projectKey));
        q.append("&s=").append(enc("FILE_LINE"));
        if (impactSeverity != null && !"ALL".equals(impactSeverity))
            q.append("&impactSeverities=").append(enc(impactSeverity));
        if (quality != null && !"ALL".equals(quality))
            q.append("&impactSoftwareQualities=").append(enc(quality));
        if (rule != null && !rule.isEmpty()) q.append("&rules=").append(enc(rule));
        if (tags != null && !tags.isEmpty()) q.append("&tags=").append(enc(tags));
        if (notTags != null && !notTags.isEmpty()) q.append("&notTags=").append(enc(notTags));
        String st = sanitizeStatuses(statuses);
        if (!st.isEmpty()) q.append("&issueStatuses=").append(enc(st));
        q.append("&additionalFields=").append(enc("_all"));
        q.append("&timeZone=").append(enc("Asia/Novosibirsk"));
        return q.toString();
    }

    public JSONObject fetchPageRaw(String statuses, String impactSeverity, String quality,
                                   String rule, int p, int ps) throws Exception {
        return fetchPageRaw(statuses, impactSeverity, quality, rule, null, null, p, ps);
    }

    public JSONObject fetchPageRaw(String statuses, String impactSeverity, String quality,
                                   String rule, String tags, String notTags, int p, int ps)
            throws Exception {
        String effectiveStatuses = statuses;
        int attempt = 0;
        while (attempt++ < 3) {
            String template = "/api/issues/search?"
                    + buildQueryWithStatuses(effectiveStatuses, impactSeverity, quality,
                    rule, tags, notTags)
                    + "&ps={PS}&p=" + p;
            try {
                return getJsonWithPsFallback(template, ps, "issues/search");
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("issueStatuses")
                        && msg.toLowerCase().contains("must be one of")) {
                    Set<String> parsed = parseAllowedStatusesFromError(msg);
                    if (parsed != null && !parsed.isEmpty()
                            && !parsed.equals(serverAllowedStatuses)) {
                        serverAllowedStatuses = parsed;
                        String sanitized = sanitizeStatuses(effectiveStatuses);
                        effectiveStatuses = sanitized;
                        continue;
                    }
                }
                if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                    String sanitized = sanitizeStatuses(effectiveStatuses);
                    if (!sanitized.equals(effectiveStatuses) && !sanitized.isEmpty()) {
                        effectiveStatuses = sanitized;
                        continue;
                    }
                }
                throw e;
            }
        }
        throw new IllegalStateException("fetchPageRaw: не удалось выполнить запрос после повторов");
    }

    public List<Issue> fetchOpenPage(String impactSeverity, String quality,
                                     int page, int pageSize) throws Exception {
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

    public int countWithStatuses(String statuses, String impactSeverity, String quality)
            throws Exception {
        JSONObject root = fetchPageRaw(statuses, impactSeverity, quality, null, 1, 1);
        return root.getInt("total");
    }

    public List<Issue> fetchAll(String impactSeverity, String quality) throws Exception {
        return fetchOpen(impactSeverity, quality);
    }

    public List<Issue> fetchOpen(String impactSeverity, String quality) throws Exception {
        return fetchAllByStatuses(ACTIVE_STATUSES, impactSeverity, quality);
    }

    public List<Issue> fetchAccepted(String impactSeverity, String quality) throws Exception {
        return fetchAllByStatuses("ACCEPTED", impactSeverity, quality);
    }

    public List<Issue> fetchAllByStatuses(String statuses, String impactSeverity,
                                          String quality) throws Exception {
        return fetchAllByStatuses(statuses, impactSeverity, quality, null);
    }

    public List<Issue> fetchAllByStatuses(String statuses, String impactSeverity,
                                          String quality, FetchProgressListener listener) throws Exception {
        System.out.println("[SonarIssuesApi] Начало загрузки issues (sev=" + impactSeverity + ", qual=" + quality + ")");
        try {
            return fetchAllByStatusesDirect(statuses, impactSeverity, quality, listener);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Can return only the first 10000 results")) {
                System.out.println("[SonarIssuesApi] Внимание: достигнут лимит SonarQube в 10 000 записей. Запуск разбиения по частям...");
                return fetchAllByParts(statuses, impactSeverity, quality, listener);
            }
            if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                System.out.println("[SonarIssuesApi] Корректировка статусов из-за несовместимости версий SonarQube...");
                return fetchAllByStatuses(sanitizeStatuses(statuses), impactSeverity, quality, listener);
            }
            System.err.println("[SonarIssuesApi] Ошибка при загрузке issues: " + msg);
            throw e;
        }
    }

    private List<Issue> fetchAllByStatusesDirect(String statuses, String impactSeverity,
                                                 String quality) throws Exception {
        return fetchAllByStatusesDirect(statuses, impactSeverity, quality, null);
    }

    private List<Issue> fetchAllByStatusesDirect(String statuses, String impactSeverity,
                                                 String quality, FetchProgressListener listener) throws Exception {
        List<Issue> out = new ArrayList<>();
        int page = 1;
        int total = -1;
        while (total < 0 || out.size() < total) {
            String template = "/api/issues/search?"
                    + buildQueryWithStatuses(statuses, impactSeverity, quality, null, null, null)
                    + "&ps={PS}&p=" + page;
            JSONObject root = getJsonWithPsFallback(template, FETCH_PAGE_SIZE,
                    "issues/search(direct)");
            total = root.getInt("total");
            JSONArray arr = root.getJSONArray("issues");
            if (arr.isEmpty()) break;
            for (int i = 0; i < arr.length(); i++) {
                out.add(Issue.from(arr.getJSONObject(i), projectKey));
            }
            if (listener != null) {
                listener.onProgress(out.size(), total, String.format("Загрузка: %d из %d", out.size(), total));
            }
            page++;
            if (page > SAFETY_MAX_PAGES) break;
        }
        return out;
    }

    private List<Issue> fetchAllByParts(String statuses, String impactSeverity,
                                        String quality) throws Exception {
        return fetchAllByParts(statuses, impactSeverity, quality, null);
    }

    private List<Issue> fetchAllByParts(String statuses, String impactSeverity,
                                        String quality, FetchProgressListener listener) throws Exception {
        List<Issue> out = new ArrayList<>();
        String[] sevs  = {"BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"};
        String[] quals = {"MAINTAINABILITY", "RELIABILITY", "SECURITY"};
        boolean sevAll  = (impactSeverity == null || "ALL".equalsIgnoreCase(impactSeverity));
        boolean qualAll = (quality == null || "ALL".equalsIgnoreCase(quality));

        if (!sevAll && !qualAll) {
            System.out.println("[SonarIssuesApi] Запуск загрузки по файлам (фильтры по sev/qual заданы жестко)");
            return fetchAllByFiles(statuses, impactSeverity, quality, 0, -1, listener);
        }

        System.out.println("[SonarIssuesApi] Разбиение по категориям Severity/Quality...");
        for (String s : sevs) {
            if (!sevAll && !impactSeverity.equalsIgnoreCase(s)) continue;
            for (String q : quals) {
                if (!qualAll && !quality.equalsIgnoreCase(q)) continue;
                try {
                    out.addAll(fetchAllByStatusesDirect(statuses, s, q, listener));
                } catch (RuntimeException e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("Can return only the first 10000 results")) {
                        System.out.println("[SonarIssuesApi] Лимит 10000 для " + s + "/" + q + ". Переход на файлы...");
                        out.addAll(fetchAllByFiles(statuses, s, q, out.size(), -1, listener));
                    } else if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                        out.addAll(fetchAllByStatusesDirect(sanitizeStatuses(statuses), s, q, listener));
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

    private List<Issue> fetchAllByFiles(String statuses, String impactSeverity, String quality,
                                        int grandFetched, int grandTotal,
                                        FetchProgressListener listener) throws Exception {
        List<String> files = fetchProjectFilesList();
        System.out.println("[SonarIssuesApi] Загрузка разбита на файлы. Всего файлов: " + files.size());
        List<Issue> out = new ArrayList<>();
        int batchSize = 20;
        int totalBatches = (files.size() + batchSize - 1) / batchSize;
        for (int i = 0; i < files.size(); i += batchSize) {
            int batchIdx = i / batchSize + 1;
            List<String> batch = files.subList(i, Math.min(i + batchSize, files.size()));
            String components = String.join(",", batch);

            emitBatch(listener, grandFetched + out.size(),
                    grandTotal > 0 ? grandTotal : -1,
                    String.format("Файлы %d/%d (пакет %d/%d)",
                            Math.min(i + batch.size(), files.size()),
                            files.size(), batchIdx, totalBatches));

            try {
                out.addAll(fetchAllByComponents(statuses, impactSeverity, quality, components,
                        grandFetched + out.size(), grandTotal, listener));
            } catch (BatchLimitExceededException e) {
                System.out.println("[SonarIssuesApi] Лимит для пакета файлов. Разбиваем на отдельные файлы...");
                for (String singleFile : batch) {
                    out.addAll(fetchAllByComponents(statuses, impactSeverity, quality, singleFile,
                            grandFetched + out.size(), grandTotal, listener));
                }
            }
        }
        return out;
    }

    private List<Issue> fetchAllByComponents(String statuses, String impactSeverity,
                                             String quality, String components)
            throws Exception {
        return fetchAllByComponents(statuses, impactSeverity, quality, components,
                0, -1, null);
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
                : "Загрузка " + (components.length() > 40
                ? components.substring(0, 40) + "…" : components);

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
                emitBatch(listener, grandFetched + out.size(),
                        grandTotal > 0 ? grandTotal : total, context);
                page++;
                if (page > SAFETY_MAX_PAGES) break;
            } catch (RuntimeException e) {
                if (e.getMessage() != null
                        && e.getMessage().contains("Can return only the first 10000 results")) {
                    if (components.contains(",") || (rulesCsv != null && rulesCsv.contains(","))) {
                        throw new BatchLimitExceededException("Batch limit exceeded");
                    }
                    System.out.println("[SonarIssuesApi] Лимит 10000 для одного файла/правила. Загрузка прервана для " + context);
                    break;
                } else throw e;
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

    public Map<String, Integer> facet(String impactSeverity, String quality, String facetName)
            throws Exception {
        return facetWithStatuses(ACTIVE_STATUSES, impactSeverity, quality, null, facetName);
    }

    public Map<String, Integer> facetWithStatuses(String statuses, String impactSeverity,
                                                  String quality, String rule,
                                                  String facetName) throws Exception {
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

    public Map<String, List<Issue>> issuesByFile(String impactSeverity, String quality)
            throws Exception {
        List<Issue> all = fetchOpen(impactSeverity, quality);
        Map<String, List<Issue>> byFile = new TreeMap<>();
        for (Issue it : all) byFile.computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
        for (List<Issue> list : byFile.values()) list.sort((a, b) -> Integer.compare(a.line, b.line));
        return byFile;
    }

    public Map<String, List<Issue>> issuesByFileByStatuses(String statuses,
                                                           String impactSeverity,
                                                           String quality) throws Exception {
        List<Issue> all = fetchAllByStatuses(statuses, impactSeverity, quality);
        Map<String, List<Issue>> byFile = new TreeMap<>();
        for (Issue it : all) byFile.computeIfAbsent(it.component, k -> new ArrayList<>()).add(it);
        for (List<Issue> list : byFile.values()) list.sort((a, b) -> Integer.compare(a.line, b.line));
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
        if (query != null && !query.isEmpty()) url += "&q=" + enc(query);
        JSONObject root = new JSONObject(client.get(url));
        JSONArray arr = root.getJSONArray("tags");
        List<String> res = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) res.add(arr.getString(i));
        return res;
    }

    public String fetchRuleName(String ruleKey) throws Exception {
        if (ruleKey == null || ruleKey.isEmpty()) return "";
        if (ruleNameCache.containsKey(ruleKey)) return ruleNameCache.get(ruleKey);
        String url = "/api/rules/show?key=" + enc(ruleKey);
        JSONObject root = new JSONObject(client.get(url));
        String name = root.getJSONObject("rule").optString("name");
        ruleNameCache.put(ruleKey, name);
        return name;
    }

    public String fetchFullSource(String componentKey) {
        if (componentKey == null || componentKey.isEmpty()) return "";
        if (failedComponents.contains(componentKey)) return "";
        try {
            String url = "/api/sources/lines?key=" + enc(componentKey) + "&from=1&to=100000";
            JSONObject root = new JSONObject(client.get(url));
            JSONArray sources = root.optJSONArray("sources");
            if (sources == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sources.length(); i++) {
                String code = sources.getJSONObject(i).optString("code", "");
                sb.append(cleanHtml(code)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("HTTP 404") || msg.contains("not found"))) {
                System.err.println("[SonarIssuesApi] Файл не найден (404): " + componentKey);
                failedComponents.add(componentKey);
            } else {
                System.err.println("[SonarIssuesApi] Ошибка получения исходника для " + componentKey + ": " + msg);
            }
            return "";
        }
    }

    public List<String> fetchSnippet(String issueKey) throws Exception {
        if (snippetCache.containsKey(issueKey)) return snippetCache.get(issueKey);
        Issue issue = fetchIssue(issueKey);
        if (issue == null) { snippetCache.put(issueKey, Collections.emptyList()); return Collections.emptyList(); }

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
                    if (!expectHighlight || hasHighlight) lines = viaSnippets;
                    else {
                        List<String> fallback = trySourcesLinesAround(issue, effectiveLine);
                        lines = (fallback != null && !fallback.isEmpty()) ? fallback : viaSnippets;
                    }
                } else {
                    lines = trySourcesLinesAround(issue, effectiveLine);
                    if (lines == null || lines.isEmpty())
                        lines = trySourcesLinesHead(issue, FILE_HEAD_LINES);
                    if (lines == null) lines = Collections.emptyList();
                }
            } else {
                lines = trySourcesLinesHead(issue, FILE_HEAD_LINES);
            }
        }
        snippetCache.put(issueKey, lines);
        return lines;
    }

    private static int resolveEffectiveLine(Issue issue) {
        if (issue.line > 0) return issue.line;
        if (issue.textRange != null && issue.textRange.startLine > 0) return issue.textRange.startLine;
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

    private List<String> tryIssueSnippets(Issue issue) {
        try {
            String url = "/api/sources/issue_snippets?issueKey=" + enc(issue.key);
            JSONObject root = new JSONObject(client.get(url));
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
                lines.add(String.format("%4d | %s", lineNum, cleanHtml(code)));
            }
            return lines;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> trySourcesLinesAround(Issue issue, int centerLine) {
        String componentKey = buildFullComponentKey(issue.component);
        if (componentKey == null || componentKey.isEmpty()) return null;
        int trEnd = (issue.textRange != null && issue.textRange.endLine >= centerLine)
                ? issue.textRange.endLine : centerLine;
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
        if (failedComponents.contains(componentKey)) return null;
        try {
            String url = "/api/sources/lines?key=" + enc(componentKey)
                    + "&from=" + from + "&to=" + to;
            JSONObject root = new JSONObject(client.get(url));
            JSONArray sources = root.optJSONArray("sources");
            if (sources == null) return null;
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < sources.length(); i++) {
                JSONObject src = sources.getJSONObject(i);
                int lineNum = src.getInt("line");
                String code = src.optString("code");
                code = maybeHighlight(code, lineNum, issue);
                lines.add(String.format("%4d | %s", lineNum, cleanHtml(code)));
            }
            return lines;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("HTTP 404") || msg.contains("not found"))) {
                System.err.println("[SonarIssuesApi] Файл не найден (404): " + componentKey);
                failedComponents.add(componentKey);
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

    public void invalidateIssue(String issueKey) { snippetCache.remove(issueKey); }

    private String applyHighlight(String html, int lineNum, Issue.TextRange range) {
        if (range == null || range.startLine <= 0 || range.endLine <= 0) return html;
        if (range.startLine > range.endLine) return html;
        if (range.startLine == range.endLine && range.startOffset == range.endOffset) return html;
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
                if (endTag != -1) { sb.append(html, i, endTag + 1); i = endTag; continue; }
            }
            if (c == '&') {
                int endEntity = html.indexOf(';', i);
                if (endEntity != -1 && endEntity - i < 10) {
                    sb.append(html, i, endEntity + 1);
                    i = endEntity;
                    visibleCount++;
                    if (lineNum == range.endLine && visibleCount == range.endOffset && !endInserted) {
                        sb.append("</mark>"); endInserted = true;
                    }
                    continue;
                }
            }
            if (lineNum == range.startLine && visibleCount == range.startOffset && !startInserted) {
                sb.append("<mark class=\"sonar-issue-mark\">"); startInserted = true;
            }
            sb.append(c);
            visibleCount++;
            if (lineNum == range.endLine && visibleCount == range.endOffset && !endInserted) {
                sb.append("</mark>"); endInserted = true;
            }
        }
        if (lineNum == range.startLine && !startInserted)
            sb.insert(0, "<mark class=\"sonar-issue-mark\">");
        if (lineNum == range.endLine && !endInserted) sb.append("</mark>");
        else if (lineNum < range.endLine && !endInserted) sb.append("</mark>");
        return sb.toString();
    }

    private String cleanHtml(String html) {
        if (html == null) return "";
        Pattern pattern = Pattern.compile("<mark class=\"sonar-issue-mark\">(.*?)</mark>",
                Pattern.DOTALL);
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
}