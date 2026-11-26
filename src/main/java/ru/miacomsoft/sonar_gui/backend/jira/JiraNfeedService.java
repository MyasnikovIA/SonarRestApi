package ru.miacomsoft.sonar_gui.backend.jira;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Копия ru.miacomsoft.sonar.jira.JiraNfeedService. */
public class JiraNfeedService {

    private final JiraClient jira;

    public JiraNfeedService(JiraClient jira) { this.jira = jira; }

    public List<JiraFieldService.AllowedValue> loadOptions(String fieldId,
                                                           String issueKeyOrId) {
        try {
            JSONObject body = new JSONObject()
                    .put("customFieldId", fieldId)
                    .put("userInput", "")
                    .put("view", "EDIT")
                    .put("formData", new JSONObject())
                    .put("startIndex", 0);
            if (issueKeyOrId != null) {
                body.put("fieldContext",
                        new JSONObject().put("issueKeyOrId", issueKeyOrId));
            }
            String resp = jira.post("/rest/nfeed/3.0/nFeed/field/input/options",
                    body.toString());
            if (resp == null || resp.isBlank()) return List.of();

            JSONArray arr;
            String t = resp.trim();
            if (t.startsWith("[")) arr = new JSONArray(t);
            else arr = new JSONObject(t).optJSONArray("options");
            if (arr == null) return List.of();

            List<JiraFieldService.AllowedValue> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", o.optString("value", null));
                String label = o.optString("value",
                        o.optString("label", o.optString("name", null)));
                out.add(new JiraFieldService.AllowedValue(id, label));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}