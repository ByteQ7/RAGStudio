# RAGStudio — Agentic RAG Platform

<p align="center">
  <em>ReACT Agent-driven Q&A platform with multi-modal, multi-source retrieval</em>
</p>

<p align="center">
  <a href="README_cn.md"><img src="https://img.shields.io/badge/中文版-blue?style=for-the-badge" alt="中文版"/></a>
</p>

---

<p align="center">
  <video src="https://openlist.qbyte.top/@s/GVWZlUAk?preview=video" controls width="800">
    Your browser does not support the video tag. <a href="https://openlist.qbyte.top/@s/GVWZlUAk" target="_blank">Watch Demo Video</a>
  </video>
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
# Infrastructure (Docker)
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d
docker run -d --name pgvector -e POSTGRES_DB=ragstudio -e POSTGRES_PASSWORD=postgres -p 5432:5432 pgvector/pgvector:pg16
docker run -d --name redis -p 6379:6379 redis:7-alpine

# Database
createdb -U postgres ragstudio
psql -U postgres -d ragstudio -f resources/database/V2/schema_pg.sql
psql -U postgres -d ragstudio -f resources/database/V2/init_data_pg.sql

# Backend
cp .env-example .env   # edit DB/Redis/RocketMQ config
cd bootstrap && mvn spring-boot:run   # → http://localhost:9090

# Frontend
cd frontend && npm install && npm run dev   # → http://localhost:5173
```

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
- Auto-loaded at startup + 15s polling hot-reload

### Tracing & Monitoring

- Full-chain distributed tracing: every pipeline stage records duration, status, error
- Admin dashboard with latency/success trends, per-node trace inspection
- Message feedback (like/dislike) with reason collection

---

## Config Reference

Key application config (`application.yaml`):

| Key | Default | Description |
|-----|---------|-------------|
| `rag.agent.max-iterations` | `10` | Max Agent loop iterations |
| `rag.agent.tool-timeout-ms` | `30000` | Single tool call timeout |
| `rag.search.default-top-k` | `5` | Top-K retrieval results |
| `rag.memory.history-keep-turns` | `8` | Recent conversation turns to keep |
| `rag.memory.summary-start-turns` | `9` | Summary triggers after N turns |
| `rag.memory.compress-threshold` | `historyKeepTurns * 2` | Compression trigger threshold |
| `rag.trace.enabled` | `true` | Enable distributed tracing |

---

<p align="center">
  <a href="LICENSE">MIT License</a> · Built by ByteQ
</p>