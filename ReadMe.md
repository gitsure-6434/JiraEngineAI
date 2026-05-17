# AI Integrated Jira Automation Tool

Intelligent Jira triage API: paste bug text (and optional ticket IDs), get **similar historical issues** and an **AI-recommended fix** powered by **Ollama** and **Qdrant**.

No Jira ticket creation. No MongoDB.

## Architecture

```text
[POST /api/v1/analyze]  text (+ optional ticketIds)
        │
        ├─► Embed query (Ollama)
        ├─► Search Qdrant cache (fast repeat queries)
        ├─► Search Qdrant jira_issues (similar tickets)
        ├─► Optional: fetch live ticket context (Jira REST API)
        └─► RAG prompt → Ollama chat → JSON response

[POST /api/v1/ingest]   JQL → fetch from Jira → embed → Qdrant jira_issues
```

## Repository structure

```text
.
├── backend/                 # Spring Boot API
├── frontend/                # (planned) UI
├── docker-compose.yml       # Qdrant
├── ReadMe.md
└── Synopsis.md
```

## Prerequisites

1. **Docker** — for Qdrant  
2. **Ollama** — running at `http://localhost:11434`  
3. **Jira Cloud** — base URL, email, API token (for ingest & optional live ticket lookup)

### Ollama models

```bash
ollama pull llama3.1              # chat (you already have this)
ollama pull nomic-embed-text      # embeddings for vector search (required)
```

`llama3.1` generates fixes. `nomic-embed-text` creates vectors (768 dimensions).

## Quick start

### 1. Start Qdrant

```bash
docker compose up -d
```

### 2. Configure environment

```bash
export JIRA_BASE_URL="https://your-domain.atlassian.net"
export JIRA_EMAIL="you@example.com"
export JIRA_API_TOKEN="your-api-token"
export OLLAMA_CHAT_MODEL="llama3.1"
export OLLAMA_EMBEDDING_MODEL="nomic-embed-text"
```

### 3. Start backend

```bash
cd backend
mvn spring-boot:run
```

### 4. Ingest historical Jira issues

```bash
curl -X POST http://localhost:8080/api/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "jql": "project = PROJ ORDER BY created DESC",
    "maxResults": 50
  }'
```

### 5. Analyze a bug

Text only:

```bash
curl -X POST http://localhost:8080/api/v1/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Users get 401 Unauthorized after OAuth token refresh on the login API"
  }'
```

With optional ticket IDs (fetches live context from Jira):

```bash
curl -X POST http://localhost:8080/api/v1/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Same error happening again on staging",
    "ticketIds": ["PROJ-101", "PROJ-88"],
    "topK": 5
  }'
```

## API reference

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/health` | Health check |
| POST | `/api/v1/ingest` | Load issues from Jira into Qdrant |
| POST | `/api/v1/analyze` | Similar issues + recommended fix |

### Analyze response

- `similarIssues` — matches from vector DB  
- `rootCauseSummary` — plain English diagnosis  
- `recommendedFix` — step-by-step fix  
- `recommendedCodePatch` — Java/Spring patch when applicable  
- `reproductionSteps` — how to reproduce  
- `relatedTicketIds` — linked ticket keys  
- `fromCache` — `true` if served from Qdrant search cache  

## Vector DB collections (Qdrant)

| Collection | Purpose |
|------------|---------|
| `jira_issues` | Historical Jira tickets (A) |
| `search_cache` | Past analyze queries + responses (B) |

## Authentication (for learning)

The API is **open** on localhost — no login required. This is fine for local development.

**JWT auth** (planned for production) would mean: only requests with a valid token can call `/analyze` and `/ingest`, protecting your Jira credentials and Ollama usage. Not implemented yet.

## Tests

```bash
cd backend
mvn verify
```

## Configuration

See `backend/src/main/resources/application.yml` for all settings.



Pending: JWT auth and UI part



sureshcursor@proton.me