# PROJECT SYNOPSIS: AI JIRA TRIAGE ENGINE

## Overview

An API-first tool that helps developers debug faster by combining **live Jira data**, **semantic vector search**, and **local LLM reasoning (Ollama)**. Users submit plain text (bug description, stack traces, notes) and optionally Jira ticket IDs. The system returns similar historical issues and a structured recommended fix.

This is **not** a Jira clone — it does not create or manage tickets.

## Core workflow

```text
[User text (+ optional ticket IDs)]
              │
              ▼
     [Ollama embeddings]
              │
              ▼
   [Qdrant: search_cache] ──► cache hit? → fast response
              │
              ▼
   [Qdrant: jira_issues] ──► similar historical bugs
              │
              ▼
   [Jira REST API] ──► optional live ticket context
              │
              ▼
   [Ollama llama3.1 RAG prompt]
              │
              ▼
[rootCauseSummary + recommendedFix + codePatch + steps]
              │
              ▼
   [Store in search_cache for next time]
```

## Technology stack

| Layer | Technology |
|-------|------------|
| API | Java 17, Spring Boot 3.x, WebClient |
| LLM | Ollama (`llama3.1` chat, `nomic-embed-text` embeddings) |
| Vector DB | Qdrant (Docker) |
| Issue source | Jira Cloud REST API (live) |
| Tests | JUnit 5, MockMvc |

## Features

1. **Ingest** — Pull closed/open issues from Jira via JQL, embed, store in Qdrant  
2. **Analyze** — Semantic search + Ollama RAG for diagnosis and fix recommendation  
3. **Dual vector storage** — Historical issues + search result cache  
4. **Resilient errors** — Global `@ControllerAdvice` with consistent JSON errors  

## Planned

- Web UI (`frontend/`)  
- JWT authentication for production  
- Sprint/QA automation extensions  
