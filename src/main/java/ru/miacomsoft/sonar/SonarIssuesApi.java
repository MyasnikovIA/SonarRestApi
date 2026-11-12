package ru.miacomsoft.sonar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SonarIssuesApi {

    private final SonarClient client;
    private final String projectKey;
    private final Map<String, String> ruleNameCache;
    private final Map<String, List<String>> snippetCache;

    public SonarIssuesApi(SonarClient client, String projectKey) {
        this(client, projectKey, new HashMap<>(), new HashMap<>());
    }

    public SonarIssuesApi(SonarClient client, String projectKey,
                          Map<String, String> ruleNameCache,
                          Map<String, List<String>> snippetCache) {
        this.client = client;
        this.projectKey = projectKey;
        this.ruleNameCache = ruleNameCache != null ? ruleNameCache : new HashMap<>();
        this.snippetCache = snippetCache != null ? snippetCache : new HashMap<>();
    }

    private static String enc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String buildQuery(String impactSeverity, String quality) {
        return buildQueryWithStatuses("OPEN,REOPENED", impactSeverity, quality);
    }

    private String buildQueryWithStatuses(String statuses, String impactSeverity, String quality) {
        StringBuilder q = new StringBuilder();
        q.append("components=").append(enc(projectKey));
        q.append("&s=").append(enc("FILE_LINE"));
        if (impactSeverity != null && !"ALL".equals(impactSeverity)) {
            q.append("&impactSeverities=").append(enc(impactSeverity));
        }
        if (quality != null && !"ALL".equals(quality)) {
            q.append("&impactSoftwareQualities=").append(enc(quality));
        }
        String st = (statuses == null || statuses.isEmpty()) ? "OPEN,REOPENED" : statuses;
        q.append("&issueStatuses=").append(enc(st));
        q.append("&additionalFields=").append(enc("_all"));
        q.append("&timeZone=").append(enc("Asia/Novosibirsk"));
        return q.toString();
    }

    public int count(String impactSeverity, String quality) throws Exception {
        try {
            return countWithStatuses("OPEN,REOPENED", impactSeverity, quality);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                return countWithStatuses("OPEN", impactSeverity, quality);
            }
            if (msg != null && msg.contains("Can return only the first 10000 results")) {
                // Если фасеты не могут отдать точное число, считаем через полный сбор
                return fetchAll(impactSeverity, quality).size();
            }
            throw e;
        }
    }

    public int countWithStatuses(String statuses, String impactSeverity, String quality)
            throws Exception {
        String url = "/api/issues/search?"
                + buildQueryWithStatuses(statuses, impactSeverity, quality) + "&ps=1";
        JSONObject root = new JSONObject(client.get(url));
        return root.getInt("total");
    }

    public List<Issue> fetchAll(String impactSeverity, String quality) throws Exception {
        return fetchOpen(impactSeverity, quality);
    }

    public List<Issue> fetchAccepted(String impactSeverity, String quality) throws Exception {
        return fetchAllByStatuses("ACCEPTED", impactSeverity, quality);
    }

    /**
     * Обертка для получения замечаний с разбивкой, если срабатывает лимит в 10 000.
     */
    public List<Issue> fetchAllByStatuses(String statuses, String impactSeverity, String quality)
            throws Exception {
        try {
            // Пытаемся получить всё одним запросом
            return fetchAllByStatusesDirect(statuses, impactSeverity, quality);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            // Если ошибка 400 из-за лимита в 10 000, начинаем разбивать запрос
            if (msg != null && msg.contains("Can return only the first 10000 results")) {
                System.err.println("[SonarIssuesApi] Достигнут лимит 10000. Разбиваем запрос...");
                return fetchAllByParts(statuses, impactSeverity, quality);
            }
            // Если ошибка со статусом REOPENED на старых SonarQube, откатываемся на OPEN
            if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                return fetchAllByStatusesDirect("OPEN", impactSeverity, quality);
            }
            throw e;
        }
    }

    private List<Issue> fetchAllByStatusesDirect(String statuses, String impactSeverity, String quality)
            throws Exception {
        List<Issue> out = new ArrayList<>();
        int page = 1, pageSize = 500, total = -1;

        while (total < 0 || out.size() < total) {
            String url = "/api/issues/search?"
                    + buildQueryWithStatuses(statuses, impactSeverity, quality)
                    + "&ps=" + pageSize + "&p=" + page;
            JSONObject root = new JSONObject(client.get(url));
            total = root.getInt("total");
            JSONArray arr = root.getJSONArray("issues");
            if (arr.isEmpty()) break;

            for (int i = 0; i < arr.length(); i++) {
                out.add(Issue.from(arr.getJSONObject(i), projectKey));
            }
            page++;
        }
        return out;
    }

    private List<Issue> fetchAllByParts(String statuses, String impactSeverity, String quality)
            throws Exception {
        List<Issue> out = new ArrayList<>();

        String[] sevs = { "BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO" };
        String[] quals = { "MAINTAINABILITY", "RELIABILITY", "SECURITY" };

        boolean sevAll = (impactSeverity == null || "ALL".equalsIgnoreCase(impactSeverity));
        boolean qualAll = (quality == null || "ALL".equalsIgnoreCase(quality));

        // Если фильтры уже заданы жестко, но все равно > 10000, идем по файлам
        if (!sevAll && !qualAll) {
            return fetchAllByFiles(statuses, impactSeverity, quality);
        }

        // Разбиваем по severity и quality
        for (String s : sevs) {
            if (!sevAll && !impactSeverity.equalsIgnoreCase(s)) continue;

            for (String q : quals) {
                if (!qualAll && !quality.equalsIgnoreCase(q)) continue;

                try {
                    out.addAll(fetchAllByStatusesDirect(statuses, s, q));
                } catch (RuntimeException e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("Can return only the first 10000 results")) {
                        System.err.println("[SonarIssuesApi] Достигнут лимит 10000 для " + s + "/" + q + ". Разбиваем по файлам...");
                        out.addAll(fetchAllByFiles(statuses, s, q));
                    } else {
                        throw e;
                    }
                }
            }
        }
        return out;
    }

    private List<Issue> fetchAllByFiles(String statuses, String impactSeverity, String quality)
            throws Exception {
        List<String> files = fetchProjectFiles();
        List<Issue> out = new ArrayList<>();
        int batchSize = 20; // Берем по 20 файлов, чтобы наверняка уложиться в 10000

        for (int i = 0; i < files.size(); i += batchSize) {
            List<String> batch = files.subList(i, Math.min(i + batchSize, files.size()));
            String components = String.join(",", batch);

            int page = 1, pageSize = 500, total = -1;
            while (total < 0 || out.size() < total) {
                String url = "/api/issues/search?"
                        + "components=" + enc(components)
                        + "&s=" + enc("FILE_LINE")
                        + (impactSeverity != null && !"ALL".equalsIgnoreCase(impactSeverity) ? "&impactSeverities=" + enc(impactSeverity) : "")
                        + (quality != null && !"ALL".equalsIgnoreCase(quality) ? "&impactSoftwareQualities=" + enc(quality) : "")
                        + "&issueStatuses=" + enc(statuses)
                        + "&additionalFields=" + enc("_all")
                        + "&timeZone=" + enc("Asia/Novosibirsk")
                        + "&ps=" + pageSize + "&p=" + page;

                JSONObject root = new JSONObject(client.get(url));
                total = root.getInt("total");
                JSONArray arr = root.getJSONArray("issues");
                if (arr.isEmpty()) break;

                for (int j = 0; j < arr.length(); j++) {
                    out.add(Issue.from(arr.getJSONObject(j), projectKey));
                }
                page++;
            }
        }
        return out;
    }

    private List<String> fetchProjectFiles() throws Exception {
        List<String> files = new ArrayList<>();
        int page = 1, pageSize = 500;
        while (true) {
            String url = "/api/components/tree?component=" + enc(projectKey)
                    + "&qualifiers=FIL&ps=" + pageSize + "&p=" + page;
            JSONObject root = new JSONObject(client.get(url));
            JSONArray components = root.optJSONArray("components");
            if (components == null || components.isEmpty()) break;

            for (int i = 0; i < components.length(); i++) {
                files.add(components.getJSONObject(i).getString("key"));
            }
            page++;
        }
        return files;
    }

    public Map<String, Integer> facet(String impactSeverity, String quality, String facetName)
            throws Exception {
        try {
            return facetWithStatuses("OPEN,REOPENED", impactSeverity, quality, facetName);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("REOPENED") || msg.contains("issueStatuses"))) {
                return facetWithStatuses("OPEN", impactSeverity, quality, facetName);
            }
            throw e;
        }
    }

    public Map<String, Integer> facetWithStatuses(String statuses, String impactSeverity,
                                                  String quality, String facetName)
            throws Exception {
        String url = "/api/issues/search?"
                + buildQueryWithStatuses(statuses, impactSeverity, quality)
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

    public List<String> fetchSnippet(String issueKey) throws Exception {
        if (snippetCache.containsKey(issueKey)) {
            return snippetCache.get(issueKey);
        }

        Issue issue = fetchIssue(issueKey);
        if (issue == null) {
            snippetCache.put(issueKey, Collections.emptyList());
            return Collections.emptyList();
        }

        String url = "/api/sources/issue_snippets?issueKey=" + enc(issueKey);
        String json = client.get(url);
        JSONObject root = new JSONObject(json);
        if (root.isEmpty()) {
            snippetCache.put(issueKey, Collections.emptyList());
            return Collections.emptyList();
        }

        String fileKey = root.keys().next();
        JSONObject fileObj = root.getJSONObject(fileKey);
        JSONArray sources = fileObj.getJSONArray("sources");

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject src = sources.getJSONObject(i);
            int lineNum = src.getInt("line");
            String code = src.optString("code");

            if (issue.textRange != null) {
                code = applyHighlight(code, lineNum, issue.textRange);
            }

            code = cleanHtml(code);
            lines.add(String.format("%4d | %s", lineNum, code));
        }
        snippetCache.put(issueKey, lines);
        return lines;
    }

    public void invalidateIssue(String issueKey) {
        snippetCache.remove(issueKey);
    }

    private String applyHighlight(String html, int lineNum, Issue.TextRange range) {
        if (range == null) return html;
        if (lineNum < range.startLine || lineNum > range.endLine) return html;

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

        Pattern pattern = Pattern.compile(
                "<mark class=\"sonar-issue-mark\">(.*?)</mark>",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String content = matcher.group(1);
            matcher.appendReplacement(sb,
                    "§HL_START§" + Matcher.quoteReplacement(content) + "§HL_END§");
        }
        matcher.appendTail(sb);
        String res = sb.toString();

        res = res.replaceAll("<[^>]+>", "");
        return res.replace("&gt;", ">")
                .replace("&lt;", "<")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    public List<Issue> fetchHotspots(String vulnerabilityProbability) throws Exception {
        List<Issue> out = new ArrayList<>();
        String[] statuses = { "TO_REVIEW", "REVIEWED" };

        for (String status : statuses) {
            int page = 1, pageSize = 500, total = -1, fetched = 0;

            while (total < 0 || fetched < total) {
                String url = "/api/hotspots/search?project=" + enc(projectKey)
                        + "&status=" + enc(status)
                        + "&ps=" + pageSize
                        + "&p=" + page;

                JSONObject root = new JSONObject(client.get(url));

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
                if (page > 100) break;
            }
        }
        return out;
    }

    public List<Issue> fetchOpen(String impactSeverity, String quality) throws Exception {
        return fetchAllByStatuses("OPEN,REOPENED", impactSeverity, quality);
    }
}