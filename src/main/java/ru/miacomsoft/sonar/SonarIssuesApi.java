package ru.miacomsoft.sonar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SonarIssuesApi {

    public static final String ACTIVE_STATUSES = "OPEN,CONFIRMED,REOPENED";
    public static final int FETCH_PAGE_SIZE = 3000;

    /** Сколько первых строк файла показывать для issue без какой-либо локации. */
    private static final int FILE_HEAD_LINES = 50;

    /** Сколько строк контекста брать вокруг textRange в fallback-режиме. */
    private static final int SNIPPET_CONTEXT = 10;

    private static final int SAFETY_MAX_PAGES = 10_000;

    private static final Pattern PS_LIMIT_LTE = Pattern.compile(
            "must be less than or equal to (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_LIMIT_LT = Pattern.compile(
            "must be less than (\\d+)", Pattern.CASE_INSENSITIVE);

    private final SonarClient client;
    private final String projectKey;
    private final Map<String, String> ruleNameCache;
    private final Map<String, List<String>> snippetCache;

    private static boolean reopenedSupported = true;
    private static volatile int effectivePsLimit = FETCH_PAGE_SIZE;

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
    // ps-fallback
    // ============================================================

    private static String normalizeJsonQuotes(String msg) {
        if (msg == null) return null;
        return msg.replace("\\u0027", "'")
                .replace("\\u0022", "\"")
                .replace("\\\"", "\"");
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
    // Build queries
    // ============================================================

    private String sanitizeStatuses(String statuses) {
        if (statuses == null || statuses.isEmpty()) return "";
        if (!reopenedSupported && statuses.contains("REOPENED")) {
            String st = statuses.replace("REOPENED", "")
                    .replace(",,", ",")
                    .replaceAll("^,|,$", "");
            return st.isEmpty() ? "OPEN" : st;
        }
        return statuses;
    }

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
                    + buildQueryWithStatuses(sanitizeStatuses(statuses), impactSeverity, quality, rule)
                    + "&ps={PS}&p=" + p;
            try {
                return getJsonWithPsFallback(template, ps, "issues/search");
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                    reopenedSupported = false;
                    System.err.println("[SonarIssuesApi] Сервер не поддерживает статус REOPENED. Переключаемся на OPEN,CONFIRMED.");
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

    public int countWithStatuses(String statuses, String impactSeverity, String quality)
            throws Exception {
        String url = "/api/issues/search?"
                + buildQueryWithStatuses(statuses, impactSeverity, quality, null) + "&ps=1";
        JSONObject root = new JSONObject(client.get(url));
        return root.getInt("total");
    }

    public List<Issue> fetchAll(String impactSeverity, String quality) throws Exception {
        return fetchOpen(impactSeverity, quality);
    }

    public List<Issue> fetchAccepted(String impactSeverity, String quality) throws Exception {
        return fetchAllByStatuses("ACCEPTED", impactSeverity, quality);
    }

    public List<Issue> fetchAllByStatuses(String statuses, String impactSeverity, String quality)
            throws Exception {
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

    private List<Issue> fetchAllByParts(String statuses, String impactSeverity, String quality)
            throws Exception {
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
        System.out.println("[SonarIssuesApi] Получаем список всех файлов проекта...");
        List<String> files = fetchProjectFiles();
        System.out.println("[SonarIssuesApi] Найдено файлов: " + files.size() + ". Обходим пачками по 20...");
        List<Issue> out = new ArrayList<>();
        int batchSize = 20;
        for (int i = 0; i < files.size(); i += batchSize) {
            List<String> batch = files.subList(i, Math.min(i + batchSize, files.size()));
            String components = String.join(",", batch);
            System.out.printf("[SonarIssuesApi] Обработка файлов %d - %d из %d...%n",
                    i + 1, Math.min(i + batchSize, files.size()), files.size());
            try {
                out.addAll(fetchAllByComponents(statuses, impactSeverity, quality, components));
            } catch (BatchLimitExceededException e) {
                System.out.println("[SonarIssuesApi] Пачка превысила лимит. Разбиваем на отдельные файлы...");
                for (String singleFile : batch) {
                    out.addAll(fetchAllByComponents(statuses, impactSeverity, quality, singleFile));
                }
            }
        }
        System.out.println("[SonarIssuesApi] Обход файлов завершен. Собрано: " + out.size() + " замечаний.");
        return out;
    }

    private List<Issue> fetchAllByComponents(String statuses, String impactSeverity,
                                             String quality, String components) throws Exception {
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
                page++;
                if (page > SAFETY_MAX_PAGES) break;
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("Can return only the first 10000 results")) {
                    if (components.contains(",")) {
                        throw new BatchLimitExceededException("Batch limit exceeded");
                    } else {
                        System.err.println("[SonarIssuesApi] Файл " + components
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

    public Map<String, List<Issue>> issuesByFile(String impactSeverity, String quality)
            throws Exception {
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

    /**
     * Какая строка используется для поиска фрагмента:
     *  - issue.line, если > 0
     *  - иначе textRange.startLine, если > 0
     *  - иначе -1 (нет привязки)
     */
    private static int resolveEffectiveLine(Issue issue) {
        if (issue.line > 0) return issue.line;
        if (issue.textRange != null && issue.textRange.startLine > 0) {
            return issue.textRange.startLine;
        }
        return -1;
    }

    /**
     * Признак того, что подсветка возможна (есть ненулевой диапазон).
     */
    private static boolean hasHighlightableRange(Issue issue) {
        if (issue.textRange == null) return false;
        if (issue.textRange.startLine <= 0 || issue.textRange.endLine <= 0) return false;
        if (issue.textRange.startLine > issue.textRange.endLine) return false;
        if (issue.textRange.startLine == issue.textRange.endLine
                && issue.textRange.startOffset == issue.textRange.endOffset) return false;
        return true;
    }

    /**
     * Возвращает фрагмент кода для issue.
     *  - line > 0                       → issue_snippets (быстро, с контекстом)
     *  - line <= 0, но textRange > 0    → sources/lines вокруг textRange + подсветка
     *  - иначе                          → sources/lines, первые FILE_HEAD_LINES строк
     */
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

                if (viaSnippets != null && !viaSnippets.isEmpty()
                        && (!expectHighlight || hasHighlight)) {
                    // Годится: либо подсветка есть, либо она и не ожидалась
                    lines = viaSnippets;
                } else {
                    // issue_snippets не дал подсветку (или вернул пусто) — fallback
                    if (viaSnippets != null && !viaSnippets.isEmpty() && !expectHighlight) {
                        lines = viaSnippets;
                    } else {
                        System.out.println("[SonarIssuesApi] snippet fallback for " + issueKey
                                + ": sources/lines around " + effectiveLine
                                + (expectHighlight ? " (expected highlight)" : ""));
                        lines = trySourcesLinesAround(issue, effectiveLine);
                        if (lines == null || lines.isEmpty()) {
                            lines = trySourcesLinesHead(issue, FILE_HEAD_LINES);
                        }
                    }
                }
            } else {
                lines = trySourcesLinesHead(issue, FILE_HEAD_LINES);
            }

            // диагностика: если textRange задан, но подсветки так и нет
            if (expectHighlight) {
                boolean hasHighlight = lines.stream().anyMatch(l -> l.contains("§HL_START§"));
                if (!hasHighlight) {
                    System.out.println("[SonarIssuesApi] WARN: no highlight for " + issueKey
                            + " (textRange=" + issue.textRange.startLine + ":"
                            + issue.textRange.startOffset + "-"
                            + issue.textRange.endLine + ":"
                            + issue.textRange.endOffset + ", effectiveLine=" + effectiveLine + ")");
                }
            }
        }

        snippetCache.put(issueKey, lines);
        return lines;
    }

    /** issue_snippets — работает для issues с line > 0. Возвращает null при полной неудаче. */
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
            System.err.println("[SonarIssuesApi] issue_snippets failed for "
                    + issue.key + ": " + e.getMessage());
            return null;
        }
    }

    /** sources/lines с диапазоном вокруг эффективной строки. */
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

    /** sources/lines: первые N строк файла. */
    private List<String> trySourcesLinesHead(Issue issue, int maxLines) {
        String componentKey = buildFullComponentKey(issue.component);
        if (componentKey == null || componentKey.isEmpty()) return Collections.emptyList();
        List<String> result = trySourcesLines(issue, componentKey, 1, Math.max(1, maxLines));
        return result != null ? result : Collections.emptyList();
    }

    /** Общий метод: GET /api/sources/lines?key=<componentKey>&from=<from>&to=<to>. */
    private List<String> trySourcesLines(Issue issue, String componentKey, int from, int to) {
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
            System.err.println("[SonarIssuesApi] sources/lines недоступен для "
                    + issue.component + " [" + from + "-" + to + "]: " + e.getMessage());
            return null;
        }
    }

    /**
     * Применяет подсветку, если нужно:
     *  - если код уже содержит <mark class="sonar-issue-mark"> — не дублируем
     *  - иначе, если textRange валиден — вызываем applyHighlight
     */
    private String maybeHighlight(String code, int lineNum, Issue issue) {
        if (code == null || code.isEmpty()) return code;
        if (code.contains("<mark class=\"sonar-issue-mark\">")) {
            return code;
        }
        if (!hasHighlightableRange(issue)) return code;
        return applyHighlight(code, lineNum, issue.textRange);
    }

    /**
     * Восстанавливает полный ключ компонента вида "<projectKey>:<path>".
     * В Issue.from component уже без префикса проекта.
     */
    private String buildFullComponentKey(String component) {
        if (component == null || component.isEmpty()) return null;
        if (projectKey == null || projectKey.isEmpty()) return component;
        if (component.startsWith(projectKey + ":")) return component;
        return projectKey + ":" + component;
    }

    public void invalidateIssue(String issueKey) {
        snippetCache.remove(issueKey);
    }

    /**
     * Подсветка фрагмента mark'ами.
     * Защищённые случаи:
     *  - textRange null / startLine<=0 / endLine<=0 → без изменений
     *  - startLine > endLine → без изменений
     *  - zero-width range (startLine==endLine && startOffset==endOffset) → без изменений
     *  - строка вне диапазона → без изменений
     *  - в html уже есть <mark class="sonar-issue-mark"> → без изменений
     */
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

    public static int getEffectivePsLimit() {
        return effectivePsLimit;
    }
}