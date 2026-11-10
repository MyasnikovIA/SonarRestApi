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
        StringBuilder q = new StringBuilder();
        q.append("components=").append(enc(projectKey));
        q.append("&s=").append(enc("FILE_LINE"));
        if (impactSeverity != null && !"ALL".equals(impactSeverity)) {
            q.append("&impactSeverities=").append(enc(impactSeverity));
        }
        if (quality != null && !"ALL".equals(quality)) {
            q.append("&impactSoftwareQualities=").append(enc(quality));
        }
        q.append("&issueStatuses=").append(enc("CONFIRMED,OPEN"));
        q.append("&additionalFields=").append(enc("_all"));
        q.append("&timeZone=").append(enc("Asia/Novosibirsk"));
        return q.toString();
    }

    public int count(String impactSeverity, String quality) throws Exception {
        String url = "/api/issues/search?" + buildQuery(impactSeverity, quality) + "&ps=1";
        JSONObject root = new JSONObject(client.get(url));
        return root.getInt("total");
    }

    public List<Issue> fetchAll(String impactSeverity, String quality) throws Exception {
        List<Issue> out = new ArrayList<>();
        int page = 1, pageSize = 500, total = -1;

        while (total < 0 || out.size() < total) {
            String url = "/api/issues/search?" + buildQuery(impactSeverity, quality)
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

    public Map<String, Integer> facet(String impactSeverity, String quality, String facetName)
            throws Exception {
        String url = "/api/issues/search?" + buildQuery(impactSeverity, quality)
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
        List<Issue> all = fetchAll(impactSeverity, quality);
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

        // 1. Получаем Issue, чтобы узнать точные координаты ошибки (textRange)
        Issue issue = fetchIssue(issueKey);
        if (issue == null) {
            snippetCache.put(issueKey, Collections.emptyList());
            return Collections.emptyList();
        }

        // 2. Получаем исходный код
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

            // 3. Внедряем теги <mark> в сырой HTML на основе координат
            if (issue.textRange != null) {
                code = applyHighlight(code, lineNum, issue.textRange);
            }

            // 4. Очищаем от HTML и меняем <mark> на маркеры §HL_START§
            code = cleanHtml(code);
            lines.add(String.format("%4d | %s", lineNum, code));
        }
        snippetCache.put(issueKey, lines);
        return lines;
    }

    /**
     * Вставляет теги <mark class="sonar-issue-mark"> в HTML-код на основе textRange.
     * Учитывает HTML-теги и сущности (например, &gt;), чтобы правильно считать видимые символы.
     */
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

            // Пропускаем HTML-теги
            if (c == '<') {
                int endTag = html.indexOf('>', i);
                if (endTag != -1) {
                    sb.append(html.substring(i, endTag + 1));
                    i = endTag;
                    continue;
                }
            }

            // Пропускаем HTML-сущности (но считаем их за 1 видимый символ)
            if (c == '&') {
                int endEntity = html.indexOf(';', i);
                if (endEntity != -1 && endEntity - i < 10) {
                    sb.append(html.substring(i, endEntity + 1));
                    i = endEntity;
                    visibleCount++;
                    if (lineNum == range.endLine && visibleCount == range.endOffset && !endInserted) {
                        sb.append("</mark>");
                        endInserted = true;
                    }
                    continue;
                }
            }

            // Вставляем начало выделения
            if (lineNum == range.startLine && visibleCount == range.startOffset && !startInserted) {
                sb.append("<mark class=\"sonar-issue-mark\">");
                startInserted = true;
            }

            sb.append(c);
            visibleCount++;

            // Вставляем конец выделения
            if (lineNum == range.endLine && visibleCount == range.endOffset && !endInserted) {
                sb.append("</mark>");
                endInserted = true;
            }
        }

        // Обработка краевых случаев (например, если ошибка до конца строки)
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

    public void invalidateIssue(String issueKey) {
        snippetCache.remove(issueKey);
    }

    /**
     * Извлекает <mark> в наши спец-маркеры и удаляет остальной HTML.
     */
    private String cleanHtml(String html) {
        if (html == null) return "";

        // Ищем наши теги <mark> (сгенерированные методом applyHighlight)
        Pattern pattern = Pattern.compile(
                "<mark class=\"sonar-issue-mark\">(.*?)</mark>",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String content = matcher.group(1);
            // QuoteReplacement экранирует символы $, чтобы не ломать регулярку
            matcher.appendReplacement(sb, "§HL_START§" + Matcher.quoteReplacement(content) + "§HL_END§");
        }
        matcher.appendTail(sb);
        String res = sb.toString();

        // Удаляем оставшиеся HTML-теги (подсветка синтаксиса)
        res = res.replaceAll("<[^>]+>", "");

        // Декодируем HTML-сущности
        return res.replace("&gt;", ">")
                .replace("&lt;", "<")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}