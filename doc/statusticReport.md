# Новый сервис POST /api/stats/projects

Ниже полный листинг нового файла и точечное изменение в `ApiServer.java`.

## Пример запроса и ответа

**Запрос:**

```http
POST /api/stats/projects
Content-Type: application/json

{
  "projects":     ["MEDMIS-tMIS.mis", "MEDMIS-Backend"],
  "includedTags": ["require-fix"],
  "excludedTags": ["techdebt"],
  "statuses":     "OPEN,CONFIRMED,REOPENED"
}
```

**Ответ:**

```json
{
  "includedTags": ["require-fix"],
  "excludedTags": ["techdebt"],
  "statuses": "OPEN,CONFIRMED,REOPENED",
  "projectsRequested": 2,
  "projectsOk": 2,
  "projectsFailed": 0,
  "projects": {
    "MEDMIS-tMIS.mis": {
      "totalIssues": 128,
      "totalFiles": 34,
      "bySeverity": { "HIGH": 12, "MEDIUM": 96, "LOW": 20 },
      "byQuality":  { "MAINTAINABILITY": 100, "RELIABILITY": 40, "SECURITY": 8 },
      "byTag":      { "require-fix": 128, "cwe": 4, "phpunit": 2 }
    },
    "MEDMIS-Backend": {
      "totalIssues": 42,
      "totalFiles": 11,
      "bySeverity": { "HIGH": 3, "MEDIUM": 39 },
      "byQuality":  { "MAINTAINABILITY": 30, "RELIABILITY": 12 },
      "byTag":      { "require-fix": 42, "cwe": 1 }
    }
  },
  "totals": {
    "totalIssues": 170,
    "totalFiles": 45,
    "bySeverity": { "HIGH": 15, "MEDIUM": 135, "LOW": 20 },
    "byQuality":  { "MAINTAINABILITY": 130, "RELIABILITY": 52, "SECURITY": 8 },
    "byTag":      { "require-fix": 170, "cwe": 5, "phpunit": 2 }
  }
}
```
