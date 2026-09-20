# RAGStudio — Agentic RAG Platform

<p align="center">
  <em>ReACT Agent-driven Q&A platform with multi-modal, multi-source retrieval</em>
</p>

<p align="center">
  <a href="README.md">📖 中文版</a> · <b>English</b>
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
| **Modular Harness** | Context / tool registry / prompts / constraints / steps / streaming split into dedicated packages; a dynamic-context middleware rebuilds the system message every iteration; tool sources assembled by `ToolRegistryAssembler` (built-ins + MCP + SKILL + extension point) |
| **Official SDK Model Layer** | Vendor official SDKs first (DashScope / Zhipu / VolcEngine ark / OpenAI / Anthropic), OpenAI/Anthropic-compatible strategy for the rest — sync / streaming / deep-thinking params |
| **22 Providers Ready** | Seed config for 22 providers (BaiLian, DeepSeek, SiliconFlow, Zhipu, Moonshot, xAI, Xiaomi MiMo, iFlytek Spark, 360 Brain, …) with 55 preset models |
| **Structured Output Fallback** | LLM structured output degrades gracefully: JSON Schema → JSON Output → prompt-only, per-model capability aware |
| **Unified Tool Discovery** | `tool_reader` enumerates MCP + SKILL registries so the LLM discovers and invokes any tool at runtime |
| **Workflow Distillation & Recall** | Extract multi-step procedures from conversations → confirm via card (confirm / cancel / refine) → save for reuse; low-threshold vector recall injects names + descriptions only, then steps are loaded on demand and other candidates cleared |
| **Canvas Orchestration** | React Flow canvases for workflows and ingestion pipelines: drag nodes/edges, condition branches, auto layout, undo/redo, live validation; graphs compile into runtime steps / node configs |
| **Role-Based Access** | Normalized `admin` / `user` roles; admin APIs guarded consistently, regular users get chat only |
| **Multi-Model Routing** | DB-driven dynamic config; automatic failover when a provider fails |
| **Hybrid Search** | pgvector semantic + pg_trgm keyword, fused via RRF (Reciprocal Rank Fusion) |
| **Graph RAG** | LLM entity/relation extraction per chunk + local subgraph retrieval channel fused into RRF; admin knowledge-graph visualization |
| **MinerU Parsing** | Advanced PDF parsing via local or remote MinerU, with Tika + multimodal LLM fallback |
| **Deep Thinking** | Configurable reasoning depth (0–100%) with step-by-step chain-of-thought |
| **Multi-Modal Chat** | Image upload (paste/file), S3 storage, presigned HTTP URLs; multimodal knowledge base with IMAGE chunks retrieved as vectors |
| **Conversation Groups** | Group conversations with per-group instructions auto-injected into the pipeline |
| **Retrieval Quality** | Embedding-based KB semantic selection + score-cluster dynamic TopK + multimodal Rerank (images sent as base64 data URIs) — **97% answer accuracy** on a 100-question eval set |
| **SKILL System** | `SKILL.md` + optional `skill.yaml`, versioned in DB (history/diff/import/rollback) — no Java or MCP server required; all http/script/command skills run in a unified Docker sandbox |
| **Full-Chain Tracing** | Lightweight distributed tracing for every pipeline stage |
| **Ingestion Pipeline** | Canvas-orchestrated deterministic engine: fetch → parse → chunk → enhance → index, supporting exclusive-branch DAGs while staying compatible with legacy linear chains |
| **Dashboard & Monitoring** | Admin dashboard with real-time KPI, request trends, model usage stats |

---

## Screenshots

### Chat

**Welcome screen (sample questions · KB picker · deep-thinking toggle)**

<img src="docs/assets/screenshots/chat.png" width="100%"/>

**KB-grounded answer (streaming · inline citations · source panel)**

<img src="docs/assets/screenshots/chat-session.png" width="100%"/>

**Workflow extraction confirmation (step preview · confirm/cancel/refine · decision receipt)**

<img src="docs/assets/screenshots/chat-workflow-confirm.png" width="100%"/>

### Login

**Split-screen brand login (feature highlights · light/dark theme · password visibility · responsive)**

<img src="docs/assets/screenshots/login.png" width="100%"/>

**Dark theme**

<img src="docs/assets/screenshots/login-dark.png" width="100%"/>

### Workflows & Canvas Orchestration

**Workflow management (conversation extraction · vector recall index · enable/rebuild)**

<img src="docs/assets/screenshots/workflows.png" width="100%"/>

**Workflow canvas (branches · drag & connect · auto layout · undo/redo)**

<img src="docs/assets/screenshots/workflow-canvas.png" width="100%"/>

**Ingestion pipelines (pipeline management · task execution)**

<img src="docs/assets/screenshots/ingestion.png" width="100%"/>

**Pipeline canvas (exclusive-branch DAG · node config · live validation)**

<img src="docs/assets/screenshots/ingestion-canvas.png" width="100%"/>

### Knowledge Base & Documents

**Knowledge base management (embedding model · parser · doc stats)**

<img src="docs/assets/screenshots/knowledge-list.png" width="100%"/>

**Document management (multi-format upload · status · chunk counts)**

<img src="docs/assets/screenshots/knowledge-documents.png" width="100%"/>

**Chunk management (edit · enable/disable · token stats)**

<img src="docs/assets/screenshots/knowledge-chunks.png" width="100%"/>

### Graph RAG

**Graph RAG control (retrieval switch · extraction model · per-KB status)**

<img src="docs/assets/screenshots/graph-rag.png" width="100%"/>

**Knowledge graph · entity management (extraction · merge · build logs)**

<img src="docs/assets/screenshots/knowledge-graph.png" width="100%"/>

### Observability

**Admin dashboard (KPI · traffic · AI performance · quality snapshot)**

<img src="docs/assets/screenshots/dashboard.png" width="100%"/>

**Traces (success rate · avg/P95 latency · run list)**

<img src="docs/assets/screenshots/traces.png" width="100%"/>

**Trace detail (node-level execution timing · incl. workflow recall stage)**

<img src="docs/assets/screenshots/trace-detail.png" width="100%"/>

### Models & Tools

**Model management (22 providers · API config · connectivity check)**

<img src="docs/assets/screenshots/ai-models.png" width="100%"/>

**Default models (per-scenario chat/summary/rerank/embedding routing)**

<img src="docs/assets/screenshots/default-models.png" width="100%"/>

**MCP servers (runtime registration · connection status · tool counts)**

<img src="docs/assets/screenshots/mcp-servers.png" width="100%"/>

**SKILL management (versioned skills · sync status)**

<img src="docs/assets/screenshots/skills.png" width="100%"/>

**Prompt management (full-chain Agent prompts, live edit & hot reload)**

<img src="docs/assets/screenshots/prompts.png" width="100%"/>

### System Administration

**Users**

<img src="docs/assets/screenshots/users.png" width="100%"/>

**System settings (vector space · MinerU parsing service)**

<img src="docs/assets/screenshots/settings.png" width="100%"/>

**Alert settings (email alerts · circuit-breaker threshold)**

<img src="docs/assets/screenshots/alert-settings.png" width="100%"/>

**Sample questions (welcome-page recommended prompts)**

<img src="docs/assets/screenshots/sample-questions.png" width="100%"/>

**Query term mapping (query normalization rules, scoped per KB)**

<img src="docs/assets/screenshots/query-term-mapping.png" width="100%"/>

---

## Architecture

### System Overview

```
┌──────────────────┐       HTTP / SSE       ┌──────────────────────────────────────────────┐
│  Frontend        │ ◄────────────────────► │  bootstrap (Spring Boot, :9090)              │
│  React 18 + TS   │                        │                                              │
│  Vite / Zustand  │                        │  Controllers ──► StreamChatPipeline          │
└──────────────────┘                        │                      │                       │
                                            │                      ▼                       │
                                            │  AgentScope ReActAgent loop (Harness)        │
                                            │   ├─ rag_search ──► Hybrid Retrieval (RRF)   │
                                            │   ├─ tool_reader ─► MCP / SKILL registries   │
                                            │   ├─ workflow_* ──► extract/save/recall      │
                                            │   └─ FINISH ──────► streamed answer + [^N]   │
                                            └────────┬─────────────────────┬───────────────┘
                                                     │                     │
                                           ┌─────────▼─────────┐  ┌────────▼─────────────────┐
                                           │ infra-ai          │  │ framework                │
                                           │ LLM SDK gateways, │  │ cache / security / MQ /  │
                                           │ embedding, rerank,│  │ DB / distributed ID /    │
                                           │ model routing     │  │ trace                    │
                                           └─────────┬─────────┘  └──────────────────────────┘
                                                     │
                        ┌───────────────────┬────────┴────────┬──────────────┬──────────────┐
                        ▼                   ▼                 ▼              ▼              ▼
                  LLM providers      PostgreSQL          Redis        RocketMQ       S3 (MinIO)
                  (22 vendors)       + pgvector                                     Docker sandbox
```

### Request Flow

```
User Question
  │
  ▼
StreamChatPipeline
  ├─ 1. Memory Loading — history + summary + group instruction
  ├─ 2. Strong Entity ID Detection — ID-like queries skip rewrite/KB-select
  ├─ 3. Query Rewrite — multi-turn rewriting + question splitting
  ├─ 4. KB Semantic Selection — embedding-based, filters irrelevant KBs
  ├─ 5. Workflow Recall — low-threshold cosine candidates (name + description only)
  └─ 6. Agent Loop — iterate until FINISH
        ├─ Tools: rag_search / MCP / SKILL / workflow_* (retrieval runs inside the loop)
        ├─ Thought → Action → Observation → continue
        └─ Thought → FINISH → Final Answer (streaming, [^chunk_N] citations)
```

### Tech Stack

| Layer | Stack |
|-------|-------|
| Backend | Java 17, Spring Boot 3.5, MyBatis-Plus, RocketMQ, Sa-Token |
| AI Engine | AgentScope ReActAgent + official SDK gateways (OpenAI / DashScope / Anthropic / VolcEngine / Zhipu, OpenAI/Anthropic-compatible fallback) |
| Vector Store | PostgreSQL + pgvector (HNSW index) + pg_trgm (GIN index) |
| Frontend | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Zustand, AntV G6 (graph view), React Flow + dagre (canvas orchestration), Mermaid |
| Infrastructure | Redis, Docker sandbox (SKILL isolation), S3 storage (MinIO / RustFS) |

### Module Structure

Maven multi-module project; dependency direction is **bootstrap → infra-ai / framework** (never the reverse).

```
ragstudio
├── bootstrap/     — All business code (controllers, services, agent loop, retrieval, graph)
├── framework/     — Cache, DB, security, exceptions, MQ, distributed IDs
└── infra-ai/      — LLM clients & SDK gateways, embedding, rerank, model routing, reasoning
```

Key packages inside `bootstrap` (`com.byteq.ai.ragstudio`):

| Package | Responsibility |
|---------|----------------|
| `rag/service/pipeline` | `StreamChatPipeline` orchestration (memory → rewrite → KB selection → workflow recall → agent loop) |
| `rag/core/harness` | Agent harness: dynamic context (`context/`), tool assembly (`tool/`), prompts (`prompt/`), constraints (`constraint/`), steps & streaming |
| `rag/core/agent` | AgentScope ReActAgent loop engine (model selection, event→SSE, citations, tracing) |
| `rag/core/retrieve` | Retrieval channels (pgvector + pg_trgm) fused via RRF; `postprocessor/` Rerank + dynamic TopK |
| `rag/core/memory` / `core/rewrite` | Conversation memory (history/summary/compression); multi-turn query rewriting |
| `rag/workflow` | Workflows: conversation extraction/confirmation/save, vector recall, graph validation & compilation, admin endpoints |
| `knowledge/` | Knowledge base & document management, MQ consumers, scheduled sync |
| `ingestion/` | Document pipeline: fetch → parse → chunk → enhance → index; graph validation/compilation and exclusive-branch execution |
| `graph/` | Graph RAG: LLM entity/relation extraction + local subgraph retrieval channel |
| `aimodel/` | Model config, multi-model routing and failover |
| `mcp/` / `skillstore/` | MCP server registry; DB-versioned SKILL storage & sandbox execution |

`infra-ai` provides the vendor SDK gateway layer (official SDKs for OpenAI / DashScope / Anthropic / Zhipu / VolcEngine, OpenAI/Anthropic-compatible fallback for the rest), plus embedding, rerank, model routing, structured-output fallback and in-process chunk cropping (`crop/`).

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
cd frontend && npm install && npm run dev   # → http://localhost:51023
```

> **Note:** Backend context-path is `/api/ragstudio`. The Vite dev proxy forwards `/api` → `localhost:9090`, so no CORS config is needed in development. The frontend dev server uses a fixed port **51023** (`strictPort`, to avoid port drift when multiple projects coexist). Default admin account `admin / admin` — change the password after first login.

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
- **Modular Harness**: context, tools, prompts, constraints, steps and streaming converge under `rag/core/harness/`; `DynamicContextMiddleware` rebuilds the system message every iteration, enabling "use once, then clear" dynamic context
- **Tools**: `rag_search` (hybrid retrieval) + `tool_reader` (MCP/SKILL discovery) + skills + MCP + `workflow_*` — all registered in the Toolkit, assembled by `ToolRegistryAssembler`
- **KB Semantic Selection**: embedding similarity decides which KBs to search, with tie-band protection and threshold gating — irrelevant questions (chitchat) trigger no retrieval at all
- **Query Rewrite**: multi-turn rewriting with question splitting; simple questions skip the LLM via rules; strong entity IDs (order numbers, doc IDs) bypass rewrite/selection and hit exact retrieval
- **Structured Output Fallback**: JSON Schema → JSON Output → prompt-only, chosen per model capability; capability mislabels degrade-and-retry automatically
- **Citations**: answers carry `[^chunk_N]` numbered citations resolved to source KB documents

### Workflows

Distill multi-step, branching solutions into reusable workflows so the Agent does not re-reason every time:

- **Extraction**: `workflow_extract` drafts a workflow from the current conversation (structured steps + branches) without persisting anything
- **Card confirmation**: the `WorkflowConfirm` card previews steps and overwrite warnings, offering confirm / cancel / refine; the server only allows `workflow_save` when a draft exists and the user reply is affirmative
- **Recall injection**: a low-threshold vector recall stage (default 0.25, TopK 8) injects names + descriptions only — better to over-recall than miss
- **Progressive disclosure**: `workflow_use` loads full steps and branch conditions on demand, then clears other candidates via the dynamic-context middleware
- **Admin**: list / detail / enable / rebuild index, with both canvas and linear quick editing

### Canvas Orchestration

Both workflows and ingestion pipelines have React Flow canvases (`@xyflow/react` + dagre auto layout):

| Dimension | Workflow canvas | Pipeline canvas |
|-----------|-----------------|-----------------|
| Execution | LLM soft execution (graph compiles to natural-language steps + `when`) | Deterministic engine (graph compiles to node configs + exclusive branches) |
| Node kinds | start / step / condition / end | start / processor (7 types) / condition gateway / end |
| Branches | condition-node edge labels | structured edge conditions evaluated at runtime (first match wins, unconditional edge as fallback) |
| Capabilities | drag & connect (cycle guarded), property panel, auto layout, undo/redo, dirty check, dark mode | same + shared node config forms, legacy linear chains auto-converted |

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
- **Visualization**: admin graph page with interactive knowledge-graph view (AntV G6), entity management (merge same/alias entities) and build logs

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
- All `http`/`script`/`command` skills run in a unified Docker sandbox (read-only filesystem, dropped capabilities, timeout); sandbox containers are pooled with fixed DNS and network enabled by default (per-skill override via `config.network`)
- Admin Skills page: version management, diff view, diagnostics for load failures

### Tracing & Monitoring

- Full-chain distributed tracing: every pipeline stage records duration, status, error
- Admin dashboard with latency/success trends, node-level trace waterfall
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
| `rag.skills.sandbox.enabled` | `true` | Docker sandbox isolation for http/script/command skills |
| `rag.skills.sandbox.network-enabled` | `true` | Network access inside the sandbox (per-skill override via `config.network`) |
| `rag.skills.sandbox.pool-size` | `1` | Persistent sandbox pool size (0 = create per execution) |
| `rag.skills.script-timeout-ms` | `30000` | Script execution timeout (ms) |
| `rag.workflow.enabled` | `true` | Workflow feature master switch (extract/save/recall) |
| `rag.workflow.recall-threshold` | `0.25` | Workflow recall cosine threshold (low = recall-biased) |
| `rag.workflow.recall-top-k` | `8` | Max recall candidates per turn |
| `rag.workflow.draft-ttl-minutes` | `30` | Draft TTL for confirmation (minutes) |
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
  <a href="README.md">📖 中文版本</a> · <a href="LICENSE">MIT License</a> · Built by ByteQ
</p>
