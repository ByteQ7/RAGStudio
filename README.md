# RAGStudio — Agentic RAG Platform

<p align="center">
  <em>ReACT Agent-driven Q&A platform with multi-modal, multi-source retrieval</em>
</p>

<p align="center">
  <a href="README_cn.md"><img src="https://img.shields.io/badge/📖_中文版-6366f1?style=for-the-badge&logo=readme&logoColor=white" alt="中文版" height="36"/></a>
</p>

---

<p align="center">
  <a href="https://openlist.qbyte.top/@s/GVWZlUAk?preview=video" target="_blank">
    <img src="https://img.shields.io/badge/▶_Watch_Demo_Video-7c3aed?style=for-the-badge&logo=youtubegaming&logoColor=white&labelColor=581c87" alt="Watch Demo Video" height="48"/>
  </a>
</p>

## Overview

**RAGStudio** is a **Java 17 + Spring Boot 3.5** powered AI Q&A platform. All requests flow through a **ReACT Agent Loop** — the LLM autonomously reasons, calls tools (KB search, MCP, custom skills), observes results, and iterates until producing a final answer.

### Key Capabilities

| Capability | Description |
|------------|-------------|
| **ReACT Agent Loop** | Thought → Action → Observation cycle replaces traditional linear RAG pipelines |
| **Multi-Model Routing** | DB-driven dynamic config, automatic failover across BaiLian / DeepSeek / SiliconFlow |
| **Hybrid Search** | pgvector semantic + tsvector keyword, fused via RRF (Reciprocal Rank Fusion) |
| **MCP Protocol** | Dynamic external tool discovery and autonomous Agent invocation |
| **Deep Thinking** | Configurable reasoning depth (0–100%) with step-by-step chain-of-thought |
| **Multi-Modal Chat** | Image upload (paste/file), S3 storage, presigned HTTP URLs for browser display |
| **SKILL System** | YAML-defined custom tools loaded from `skills/` dir — no Java or MCP server required |
| **Full-Chain Tracing** | Lightweight distributed tracing for every pipeline stage |
| **Ingestion Pipeline** | Visual document processing pipeline: fetch → parse → chunk → enhance → index |
| **Dashboard & Monitoring** | Admin dashboard with real-time KPI, request trends, model usage stats |
| **Ingestion Pipeline** | Visual document processing pipeline: fetch → parse → chunk → enhance → index |
| **Dashboard & Monitoring** | Admin dashboard with real-time KPI, request trends, model usage stats |

---

## Architecture

```
User Question
  │
  ▼
StreamChatPipeline
  ├─ 1. Memory Loading — conversation history + summary
  ├─ 2. Tool Registry — MCP + rag_search + skills
  ├─ 3. KB Relevance Check — light LLM call filters relevant KBs
  └─ 4. Agent Loop — iterate until FINISH
        ├─ Thought → Action → Observation → continue
        └─ Thought → FINISH → Final Answer (streaming)
```

### Tech Stack

| Layer | Stack |
|-------|-------|
| Backend | Java 17, Spring Boot 3.5, MyBatis-Plus, RocketMQ, Sa-Token |
| AI Engine | ReACT Agent Loop + Spring AI (OpenAI-compatible) |
| Vector Store | PostgreSQL + pgvector (HNSW index) + tsvector (GIN index) |
| Frontend | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Zustand |
| Infrastructure | Redis, Docker sandbox (SKILL isolation), S3 storage |

### Module Structure

```
ragstudio
├── bootstrap/     — All business code (controllers, services, agents)
├── framework/     — Cache, DB, security, exceptions, MQ, distributed IDs
└── infra-ai/      — LLM clients, embedding, rerank, model routing, reasoning
```

---

## Quick Start

**Prerequisites:** JDK 17+, Maven 3.8+, Node.js 18+, PostgreSQL 14+ (pgvector), Redis 6+, Docker

```bash
# 1. Infrastructure (Docker)
# ── RocketMQ (choose by CPU arch) ──
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d       # ARM64
docker compose -f resources/docker/rocketmq-stack-amd-5.2.0.compose.yaml up -d   # AMD64
# ── PostgreSQL + pgvector ──
docker run -d --name pgvector -e POSTGRES_DB=ragstudio -e POSTGRES_PASSWORD=postgres -p 5432:5432 pgvector/pgvector:pg16
# ── Redis ──
docker run -d --name redis -p 6379:6379 redis:7-alpine
# ── MinIO (S3-compatible storage) ──
docker run -d --name minio -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=admin -e MINIO_ROOT_PASSWORD=password minio/minio server /data --console-address ":9001"

# 2. Database initialization
createdb -U postgres ragstudio
psql -U postgres -d ragstudio -f resources/database/V2/schema_pg.sql
psql -U postgres -d ragstudio -f resources/database/V2/init_data_pg.sql

# 3. Environment config
cp .env-example .env   # edit DB / Redis / RocketMQ / S3 settings
# .env lives at project root; bootstrap reads it via spring-dotenv (../.env)

# 4. Start backend
cd bootstrap && mvn spring-boot:run   # → http://localhost:9090/api/ragstudio

# 5. Start frontend
cd frontend && npm install && npm run dev   # → http://localhost:5173
```

> **Note:** Backend context-path is `/api/ragstudio`. Vite dev proxy forwards `/api` → `localhost:9090`, so no CORS config is needed in development.

---

## Features

### Agent Loop

```
Iteration 0:  Thought → need today's date
               Action → time_now({})
               Observation → June 21, 2026

Iteration 1:  Thought → check festival
               Action → web_search({"query": "June 21 holiday"})
               Observation → Father's Day

Iteration 2:  Thought → information sufficient
               Action → FINISH
               Final Answer → Today is June 21, 2026. It's Father's Day.
```

- **Plan-then-Execute**: Multi-step tasks start with a Plan field, then execute step by step
- **KB Relevance Check**: Before entering the loop, a light LLM call determines which KBs are relevant — only those are searched
- **Missing Parameters**: Searchable params (dates) auto-retrieved; user-specific params (cities) prompt a clarifying question
- **Format Correction**: If LLM skips ReACT format, an auto-correction prompt is injected and retried once

### Deep Thinking

Configurable reasoning depth (0–100%) via a slider in the chat UI. Higher levels produce step-by-step chain-of-thought before the final answer, visible in the streaming output. The reasoning content is persisted in `t_message.thinking_content`.

### Multi-Modal Chat

- Upload images via file picker or Ctrl+V paste (up to 10 per message)
- Images stored to S3, served via presigned HTTP URLs
- Supported in both regular and Agent modes

### Hybrid Search (RRF Fusion)

Two parallel search channels fused via RRF:

| Channel | Method | Index |
|---------|--------|-------|
| Vector | pgvector cosine similarity | HNSW |
| Keyword | PostgreSQL tsvector `plainto_tsquery` + `ts_rank` | GIN |

RRF formula: `score = Σ 1/(60 + rank)` — no manual weight tuning needed.

### Knowledge Base & Documents

- Multi-format upload: PDF, DOCX, HTML, Markdown (file or URL)
- Three chunking strategies: `fixed_size` (overlap), `recursive` (multi-level separators), `structure_aware` (markdown-aware)
- Scheduled sync with ETag/Hash change detection
- Chunk view/edit/enable-disable per document

### MCP Integration

- Register external MCP servers at runtime (SSE / Streamable HTTP)
- Agent autonomously discovers and invokes tools during the loop
- Failure retry: Agent can retry or switch to alternative tools

### SKILL System

Define custom tools as YAML in `skills/{name}/` — no code needed:

```yaml
# skills/my-tool/skill.yaml
name: my-tool
description: "Query internal API"
type: http
url: https://internal.example.com/api
```

- Types: `http` (REST API), `script` (shell scripts), `command` (executables)
- `script`/`command` run in Docker sandbox (`--read-only`, `--cap-drop=ALL`, `--network=none`, 30s timeout)
- Auto-loaded at startup, no additional configuration needed

### Tracing & Monitoring

- Full-chain distributed tracing: every pipeline stage records duration, status, error
- Admin dashboard with latency/success trends, per-node trace inspection
- Message feedback (like/dislike) with reason collection

---

## Config Reference

Key application config (`bootstrap/src/main/resources/application.yaml`):

| Key | Default | Description |
|-----|---------|-------------|
| `rag.agent.max-iterations` | `10` | Max Agent loop iterations |
| `rag.agent.tool-timeout-ms` | `30000` | Single tool call timeout (ms) |
| `rag.skills.allowed-commands` | `""` | Skill command whitelist (empty = command type disabled) |
| `rag.skills.sandbox.enabled` | `true` | Docker sandbox isolation |
| `rag.search.default-top-k` | `10` | Top-K retrieval results |
| `rag.search.channels.hybrid-rrf.k` | `60` | RRF smoothing constant |
| `rag.search.channels.hybrid-rrf.top-k` | `5` | Final results after RRF fusion |
| `rag.memory.history-keep-turns` | `4` | Recent conversation turns to keep |
| `rag.memory.compress-threshold` | `8` | Compression trigger threshold |
| `rag.memory.summary-enabled` | `true` | Enable conversation summary |
| `rag.memory.title-max-length` | `30` | Max chat title length |
| `rag.trace.enabled` | `true` | Enable distributed tracing |
| `rag.rate-limit.global.max-concurrent` | `1` | Max concurrent chat sessions |
| `rag.rate-limit.global.max-wait-seconds` | `15` | Queue wait timeout (seconds) |
| `rag.semantic-highlight.enabled` | `false` | Semantic chunk cropping |
| `rag.query-rewrite.enabled` | `true` | Multi-turn query rewriting |
| `app.default-avatar-url` | `https://avatars.githubusercontent.com/u/583231?v=4` | Default user avatar |

---

<p align="center">
  <a href="LICENSE">MIT License</a> · Built by ByteQ
</p>