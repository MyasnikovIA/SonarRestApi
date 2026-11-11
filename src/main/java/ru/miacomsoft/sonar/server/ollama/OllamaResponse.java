package ru.miacomsoft.sonar.server.ollama;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OllamaResponse {

    private static final Pattern STATUS_RE =
            Pattern.compile("Статус:\\s*(accept|falsepositive|confirm|resolve|wontfix)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS_RE =
            Pattern.compile("Теги:\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);

    public String       status       = "";
    public String       comment      = "";
    public List<String> tags         = new ArrayList<>();
    public String       fixDecision  = "require-fix";
    public String       raw          = "";

    public static OllamaResponse parse(String raw) {
        OllamaResponse r = new OllamaResponse();
        if (raw == null) raw = "";
        r.raw = raw;

        String cleaned = raw.replaceAll("[*`_]", "");

        Matcher sm = STATUS_RE.matcher(cleaned);
        if (sm.find()) r.status = sm.group(1).toLowerCase();

        List<String> rawTags = new ArrayList<>();
        Matcher tm = TAGS_RE.matcher(cleaned);
        if (tm.find()) {
            String tagsStr = tm.group(1);
            for (String t : tagsStr.split("[,;\\s]+")) {
                String v = t.replaceAll("[*`_]", "").trim().toLowerCase();
                if (!v.isEmpty()) rawTags.add(v);
            }
        }

        boolean hasRequire    = rawTags.contains("require-fix");
        boolean hasNotRequire = rawTags.contains("not-require-fix");
        rawTags.removeIf(t -> t.equals("require-fix") || t.equals("not-require-fix"));

        if (hasRequire && !hasNotRequire)      r.fixDecision = "require-fix";
        else if (hasNotRequire && !hasRequire) r.fixDecision = "not-require-fix";
        else                                    r.fixDecision = "require-fix";

        r.tags.add(r.fixDecision);
        for (String t : rawTags) {
            if (r.tags.size() >= 3) break;
            r.tags.add(t);
        }

        StringBuilder cb = new StringBuilder();
        boolean inComment = false;
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("Статус:") || t.startsWith("Теги:")) continue;
            if (t.startsWith("Критичность:")) {
                inComment = true;
                cb.append(line).append('\n');
                continue;
            }
            if (inComment) cb.append(line).append('\n');
        }
        r.comment = cb.toString().trim();

        if (r.comment.isEmpty()) {
            StringBuilder alt = new StringBuilder();
            for (String line : raw.split("\\r?\\n")) {
                String t = line.trim();
                if (t.startsWith("Статус:") || t.startsWith("Теги:")) continue;
                alt.append(line).append('\n');
            }
            r.comment = alt.toString().trim();
        }
        return r;
    }
}