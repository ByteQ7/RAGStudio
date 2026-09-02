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

**RAGStudio** is a **Java 17 + Spring Boot 3.5** powered AI Q&A platform. All requests flow through an **AgentScope ReActAgent** — the LLM autonomously reasons, calls tools (KB search, MCP, custom skills), observes results, and iterates until producing a final answer.

### Key Capabilities

| Capability | Description |
|------------|-------------|
| **AgentScope Agent Engine** | ReActAgent (native tool calling) with streaming event bus mapped to SSE; tool results injected as observations |
| **Official SDK Model Layer** | Vendor official SDKs first (DashScope / Zhipu / VolcEngine ark / OpenAI / Anthropic), OpenAI/Anthropic-compatible strategy for the rest — sync / streaming / deep-thinking params |
| **22 Providers Ready** | Seed config for 22 providers (BaiLian, DeepSeek, SiliconFlow, Zhipu, Moonshot, xAI, Xiaomi MiMo, iFlytek Spark, 360 Brain, …) with 54 preset models |
| **Structured Output Fallback** | LLM structured output degrades gracefully: JSON Schema → JSON Output → prompt-only, per-model capability aware |
| **Unified Tool Discovery** | `tool_reader` enumerates MCP + SKILL registries so the LLM discovers and invokes any tool at runtime |
| **Multi-Model Routing** | DB-driven dynamic config; automatic failover when a provider fails |
| **Hybrid Search** | pgvector semantic + pg_trgm keyword, fused via RRF (Reciprocal Rank Fusion) |
| **Graph RAG** | LLM entity/relation extraction per chunk + local subgraph retrieval channel fused into RRF; admin knowledge-graph visualization |
| **MinerU Parsing** | Advanced PDF parsing via local or remote MinerU, with Tika + multimodal LLM fallback |
| **Deep Thinking** | Configurable reasoning depth (0–100%) with step-by-step chain-of-thought |
| **Multi-Modal Chat** | Image upload (paste/file), S3 storage, presigned HTTP URLs; multimodal knowledge base with IMAGE chunks retrieved as vectors |
| **Conversation Groups** | Group conversations with per-group instructions auto-injected into the pipeline |
| **Retrieval Quality** | Embedding-based KB semantic selection + score-cluster dynamic TopK + multimodal Rerank (images sent as base64 data URIs) — **97% answer accuracy** on a 100-question eval set |
| **SKILL System** | `SKILL.md` + optional `skill.yaml`, versioned in DB (history/diff/import/rollback) — no Java or MCP server required |
| **Full-Chain Tracing** | Lightweight distributed tracing for every pipeline stage |
| **Ingestion Pipeline** | Visual document processing pipeline: fetch → parse → chunk → enhance → index |
| **Dashboard & Monitoring** | Admin dashboard with real-time KPI, request trends, model usage stats |

---

## Architecture

```
User Question
  │
  ▼
StreamChatPipeline
  ├─ 1. Memory Loading — history + summary + group instruction
  ├─ 2. Strong Entity ID Detection — ID-like queries skip rewrite/KB-select
  ├─ 3. Query Rewrite — multi-turn rewriting + question splitting
  ├─ 4. KB Semantic Selection — embedding-based, filters irrelevant KBs
  └─ 5. Agent Loop — iterate until FINISH
        ├─ Tools: rag_search / MCP / SKILL (retrieval runs inside the loop)
        ├─ Thought → Action → Observation → continue
        └─ Thought → FINISH → Final Answer (streaming, [^chunk_N] citations)
```

### Tech Stack

| Layer | Stack |
|-------|-------|
| Backend | Java 17, Spring Boot 3.5, MyBatis-Plus, RocketMQ, Sa-Token |
| AI Engine | AgentScope ReActAgent + official SDK gateways (OpenAI / DashScope / Anthropic / VolcEngine / Zhipu, OpenAI/Anthropic-compatible fallback) |
| Vector Store | PostgreSQL + pgvector (HNSW index) + pg_trgm (GIN index) |
| Frontend | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Zustand, AntV G6 (graph view), Mermaid |
| Infrastructure | Redis, Docker sandbox (SKILL isolation), S3 storage (MinIO / RustFS) |

### Module Structure

```
ragstudio
├── bootstrap/     — All business code (controllers, services, agent loop, retrieval, graph)
├── framework/     — Cache, DB, security, exceptions, MQ, distributed IDs
└── infra-ai/      — LLM clients & SDK gateways, embedding, rerank, model routing, reasoning
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
# ── MinIO (S3-compatible storage; RustFS or any S3-compatible service works too) ──
docker run -d --name minio -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=admin -e MINIO_ROOT_PASSWORD=password minio/minio server /data --console-address ":9001"

# 2. Database initialization
createdb -U postgres ragstudio
psql -U postgres -d ragstudio -f resources/database/schema_all.sql   # full schema + seed data (fresh install only)

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
               Action → web-search({"query": "June 21 holiday"})
               Observation → Father's Day

Iteration 2:  Thought → information sufficient
               Action → FINISH
               Final Answer → Today is June 21, 2026. It's Father's Day.
```

- **Native Tool Calling**: AgentScope ReActAgent drives the loop with native function calling; tool results are injected as observations (isolated role, no confusion with user speech)
- **Tools**: `rag_search` (hybrid retrieval) + `tool_reader` (MCP/SKILL discovery) + skills + MCP — all registered in the Toolkit
- **KB Semantic Selection**: embedding similarity decides which KBs to search, with tie-band protection and threshold gating — irrelevant questions (chitchat) trigger no retrieval at all
- **Query Rewrite**: multi-turn rewriting with question splitting; simple questions skip the LLM via rules; strong entity IDs (order numbers, doc IDs) bypass rewrite/selection and hit exact retrieval
- **Structured Output Fallback**: JSON Schema → JSON Output → prompt-only, chosen per model capability; capability mislabels degrade-and-retry automatically
- **Citations**: answers carry `[^chunk_N]` numbered citations resolved to source KB documents

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
| Keyword | PostgreSQL pg_trgm `ILIKE` substring match | GIN (`gin_trgm_ops`) |

RRF formula: `score = Σ 1/(60 + rank)` — no manual weight tuning needed.

A post-processing chain (per-KB fusion → Rerank → dynamic TopK) refines the results: text and image chunks are jointly scored by a multimodal Rerank model (e.g. qwen3-vl-rerank), with images sent as base64 data URIs (no public URL required). Score-cluster-aware dynamic TopK decides how many chunks reach the LLM based on the score distribution. **97% answer accuracy** on a 100-question internal eval set.

### Graph RAG

- **Extraction**: LLM-based entity/relation extraction per chunk (structured output, incremental with per-chunk caching and self-repair on validation failure)
- **Retrieval**: query entities are matched against the graph, local subgraph retrieved by hop expansion and injected as context triples into the RRF fusion — enabled via `rag.graph.retrieval.*`, master switch controlled from the admin "Knowledge Graph" page (off by default)
- **Visualization**: admin graph page with interactive knowledge-graph view (AntV G6), build logs and stats

### Knowledge Base & Documents

- Multi-format upload: PDF, DOCX, HTML, Markdown, Excel (file or URL)
- **MinerU parsing**: layout-aware PDF parsing via local or remote (mineru.net free API) MinerU, with Tika fallback; tables/images in PDFs extracted by multimodal LLM
- Three chunking strategies: `fixed_size` (overlap), `recursive` (multi-level separators), `structure_aware` (markdown-aware)
- Scheduled sync with ETag/Hash change detection
- Chunk view/edit/enable-disable per document

### Conversation Groups

- Create/rename/delete groups and batch-move conversations into/out of groups
- Group-specific instructions are automatically injected into conversations of that group
- New conversations started from a group page are auto-assigned

### MCP Integration

- Register external MCP servers at runtime (SSE / Streamable HTTP)
- Agent autonomously discovers and invokes tools during the loop
- Failure retry: Agent can retry or switch to alternative tools

### SKILL System

Define skills as `SKILL.md` (metadata source of truth) with an optional `skill.yaml` — no code needed:

````markdown
# skills/my-skill/SKILL.md
---
name: my-skill
description: "Query internal API. Use when the user asks about xxx."
---

## Steps
...
````

- `name`/`description` live in the SKILL.md frontmatter (Agent Skills open standard — portable across agents)
- Types: `http` (REST API), `script` (shell scripts), `command` (executables); skills without execution config are knowledge-only (activated via `tool_reader`)
- **DB-versioned storage**: skills are stored in the database (`t_skill` / `t_skill_version` / `t_skill_file` / `t_skill_blob`) with version history, file-level diff, rollback, zip import/export and GitHub import; the `skills/` directory acts as a workspace reconciled from DB at startup (legacy dirs are auto-imported)
- `script`/`command` run in Docker sandbox (`--read-only`, `--cap-drop=ALL`, `--network=none`, 30s timeout)
- Admin Skills page: version management, diff view, diagnostics for load failures

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
| `rag.agent.timeout-ms` | `120000` | Agent overall execution timeout (ms) |
| `rag.skills.dir` | `${ragstudio.data-dir}/skills` | SKILL workspace directory |
| `rag.skills.max-versions` | `0` | Skill version retention (0 = unlimited) |
| `rag.skills.allowed-commands` | `""` | Skill command whitelist (empty = command type disabled) |
| `rag.skills.sandbox.enabled` | `true` | Docker sandbox isolation for script/command skills |
| `rag.skills.script-timeout-ms` | `30000` | Script execution timeout (ms) |
| `rag.query-rewrite.enabled` | `true` | Multi-turn query rewriting (simple questions handled by rules) |
| `rag.search.default-top-k` | `10` | Top-K retrieval results |
| `rag.search.max-final-chunks` | `5` | Baseline chunk count after rerank (dynamic TopK target) |
| `rag.search.channels.hybrid-rrf.k` | `60` | RRF smoothing constant |
| `rag.search.crop.enabled` | `false` | Semantic chunk cropping (in-process bge-small-zh-v1.5, enable with `RAG_CROP_ENABLED=true` in `.env`) |
| `rag.memory.history-keep-turns` | `6` | Recent conversation turns to keep |
| `rag.memory.compress-threshold` | `12` | Compression trigger threshold |
| `rag.memory.summary-enabled` | `true` | Enable conversation summary |
| `rag.memory.title-max-length` | `30` | Max chat title length |
| `rag.rate-limit.global.max-concurrent` | `3` | Max concurrent chat sessions |
| `rag.rate-limit.global.max-wait-seconds` | `15` | Queue wait timeout (seconds) |
| `rag.model-routing.selection.failure-threshold` | `2` | Consecutive failures before a model is temporarily routed out |
| `rag.graph.retrieval.enabled` | `true` | Graph retrieval channel (master switch in admin, default off) |
| `rag.trace.enabled` | `true` | Enable distributed tracing |
| `mineru.enabled` | `false` | MinerU document parsing (false = Tika/multimodal fallback only) |
| `app.default-avatar-url` | `https://avatars.githubusercontent.com/u/583231?v=4` | Default user avatar |

---

<p align="center">
  <a href="LICENSE">MIT License</a> · Built by ByteQ
</p>
