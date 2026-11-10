package ru.miacomsoft.sonar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Issue {

    public static class Impact {
        public String softwareQuality;
        public String severity;

        public Impact() {}
        public Impact(String softwareQuality, String severity) {
            this.softwareQuality = softwareQuality;
            this.severity = severity;
        }
    }

    public static class TextRange {
        public int startLine;
        public int endLine;
        public int startOffset;
        public int endOffset;
    }

    public String key;
    public String rule;
    public String ruleName;
    public String severity;
    public String impactSeverity;
    public String component;
    public int line;
    public String message;
    public String status;           // OPEN / RESOLVED / CLOSED / REOPENED / CONFIRMED
    public String resolution;       // FIXED / FALSE_POSITIVE / WONTFIX / ...
    public String issueStatus;      // ACCEPTED / CONFIRMED / OPEN / ...
    public String effort;
    public String type;
    public String author;
    public String creationDate;
    public String updateDate;
    public int flowsCount;
    public List<String> tags = new ArrayList<>();
    public List<String> transitions = new ArrayList<>();
    public List<Impact> impacts = new ArrayList<>();
    public TextRange textRange;

    public static Issue from(JSONObject o, String projectKey) {
        Issue i = new Issue();
        i.key = o.optString("key");
        i.rule = o.optString("rule");
        i.severity = o.optString("severity");
        i.component = o.optString("component");
        if (projectKey != null && i.component.startsWith(projectKey + ":")) {
            i.component = i.component.substring(projectKey.length() + 1);
        }
        i.line = o.optInt("line", -1);
        i.message = o.optString("message");
        i.status = o.optString("status");
        i.resolution = o.optString("resolution");
        i.issueStatus = o.optString("issueStatus");
        i.effort = o.optString("effort");
        i.type = o.optString("type");
        i.author = o.optString("author");
        i.creationDate = o.optString("creationDate");
        i.updateDate = o.optString("updateDate");

        JSONObject tr = o.optJSONObject("textRange");
        if (tr != null) {
            i.textRange = new TextRange();
            i.textRange.startLine = tr.optInt("startLine");
            i.textRange.endLine = tr.optInt("endLine");
            i.textRange.startOffset = tr.optInt("startOffset");
            i.textRange.endOffset = tr.optInt("endOffset");
        }

        JSONArray impacts = o.optJSONArray("impacts");
        if (impacts != null) {
            for (int k = 0; k < impacts.length(); k++) {
                JSONObject im = impacts.getJSONObject(k);
                i.impacts.add(new Impact(
                        im.optString("softwareQuality", ""),
                        im.optString("severity", "")
                ));
            }
        }
        if (!i.impacts.isEmpty()) {
            i.impactSeverity = i.impacts.get(0).severity;
        }

        JSONArray flows = o.optJSONArray("flows");
        if (flows != null) i.flowsCount = flows.length();

        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            for (int t = 0; t < tags.length(); t++) i.tags.add(tags.getString(t));
        }
        JSONArray trans = o.optJSONArray("transitions");
        if (trans != null) {
            for (int t = 0; t < trans.length(); t++) i.transitions.add(trans.getString(t));
        }
        return i;
    }

    public boolean matches(String sev, String qual) {
        boolean sevAll = sev == null || sev.isEmpty() || "ALL".equals(sev);
        boolean qualAll = qual == null || qual.isEmpty() || "ALL".equals(qual);
        if (sevAll && qualAll) return true;

        if (impacts.isEmpty()) {
            if (!sevAll && severity != null && !severity.isEmpty() && !severity.equals(sev)) {
                return false;
            }
            return true;
        }

        for (Impact im : impacts) {
            boolean sOk = sevAll || sev.equals(im.severity);
            boolean qOk = qualAll || qual.equals(im.softwareQuality);
            if (sOk && qOk) return true;
        }
        return false;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("key", key);
        o.put("rule", rule);
        o.put("ruleName", ruleName != null ? ruleName : "");
        o.put("severity", severity != null ? severity : "");
        o.put("impactSeverity", impactSeverity != null ? impactSeverity : "");
        o.put("component", component);
        o.put("line", line);
        o.put("message", message);
        o.put("status", status != null ? status : "");
        o.put("resolution", resolution != null ? resolution : "");
        o.put("issueStatus", issueStatus != null ? issueStatus : "");
        o.put("effort", effort != null ? effort : "");
        o.put("type", type != null ? type : "");
        o.put("author", author != null ? author : "");
        o.put("creationDate", creationDate != null ? creationDate : "");
        o.put("updateDate", updateDate != null ? updateDate : "");
        o.put("flowsCount", flowsCount);
        o.put("tags", new JSONArray(tags));
        o.put("transitions", new JSONArray(transitions));

        JSONArray impactsArr = new JSONArray();
        for (Impact im : impacts) {
            JSONObject imo = new JSONObject();
            imo.put("softwareQuality", im.softwareQuality);
            imo.put("severity", im.severity);
            impactsArr.put(imo);
        }
        o.put("impacts", impactsArr);
        return o;
    }

    public String url(String host, String projectKey) {
        return url("https", host, projectKey);
    }

    public String url(String scheme, String host, String projectKey) {
        return scheme + "://" + host + "/project/issues?id=" + projectKey
                + "&issues=" + key + "&open=" + key;
    }

    @Override
    public String toString() {
        return String.format("%-40s %-12s %s", component + ":" + line, rule, message);
    }
}