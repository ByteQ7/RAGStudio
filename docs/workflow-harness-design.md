# Harness 架构重构 + 工作流（Workflow）功能设计

> 状态：已实施（2026-09-19）
> 关联：`docs/skill-management-design.md`（SKILL 版本化先例）、`docs/agentscope-react-agent-loop.md`（Agent 循环现状）
> 需求来源：用户提出"把多步骤、带分支的问题解决方案固定为工作流，Agent 不必每次重新推理"；并要求把散落的 Harness 能力统一成模块。

---

## 1. 背景

### 1.1 问题

1. **业务问题**：部分问题的解决方案有 6~7 步且含分支，不同分支导向不同结果。当前每次都要 LLM 现场推理"该怎么做"，导致：步骤顺序不稳定、分支判断偶发走错、token 与延迟成本高。
2. **架构问题**：Agent = Model + Harness。当前项目的 Harness 能力（上下文组装、工具注册、提示词拼装、约束注入、步骤记录、流式过滤）全部散落在 `AgentScopeReActExecutor`（1315 行）与 `StreamChatPipeline` 中：
   - 上下文：`AgentContext` 字段靠 7 个重载构造器传递，含死字段；`kbContext` 恒为空
   - 工具：`buildToolkit` 硬编码 5 类来源，全量注册；`ToolRetriever` 存在但未接线
   - 提示词：`buildSystemPrompt` 拼接模板槽位 + 目标摘要 + 历史 SYSTEM + 前置指令，约束文案一半在 `.st`、一半硬编码在常量
   - 约束：`agent-reminder.st` 的 4 个 section 由 if-else 决定是否注入
   - 无法在循环中动态增删上下文（"用了就清除"无法实现）

### 1.2 目标

1. 把 Harness 能力收敛为 `rag/core/harness/` 模块，子包化：上下文 / 工具 / 提示词 / 约束 / 步骤 / 流式；
2. 新增"工作流"工件：从对话中提取 → 用户确认 → 固定 → 下次相似问题自动召回；
3. 工作流召回后**名称+描述注入上下文**，Agent 选中并使用后，**其余候选的名称+描述从后续迭代的上下文中清除**（不在 AgentState 中留下痕迹）；
4. 行为兼容：Phase 1 重构不改变任何现有 Agent 行为与 SSE 协议。

---

## 2. 关键技术验证（AgentScope 2.0）

| 结论 | 证据 |
|------|------|
| `MiddlewareBase.onReasoning` 在**每轮迭代**的模型调用前触发，可改写 `ReasoningInput.messages()` / `tools()` / `options()` | `MiddlewareChain.build(...).apply(new ReasoningInput(modelInput, tools, options))`（`ReActAgent.java:1975-1990`） |
| system prompt 由 `onSystemPrompt` 管道生成后经 `prependSystemMsg(event.getInputMessages(), event.getSystemMessage())` 拼到消息头部 | `ReActAgent.java:3262-3274`、`seedSystemMsg`（`:544-557`） |
| 中间件改写 **不落 AgentState 记忆**（记忆只记 reasoning 产出与 tool result） | `reasoning()` 中 modelInput 为局部变量 |
| 每轮可动态增删工具 schema（`ReasoningInput.tools()`） | `ReActAgent.java:1948-1951` |
| `Hook` 体系已 `@Deprecated(forRemoval=true)`，Middleware 为推荐方式 | `Hook.java` / `MiddlewareBase.java` |

结论：**"注入候选 → 选中后清除"用 `onReasoning` 中间件实现，不污染记忆、跨迭代生效。**

---

## 3. 总体架构

### 3.1 目录结构

```
rag/core/harness/
├── context/
│   ├── AgentContext.java              # 迁移自 rag.core.agent，Builder 化，删死字段
│   ├── ContextBlock.java              # 动态上下文块（id/priority/content/active）
│   ├── DynamicContextRegistry.java    # 每请求动态上下文注册表（含 activate/deactivate）
│   └── DynamicContextMiddleware.java  # onReasoning：把 active 块拼进 system message（每轮重建）
├── prompt/
│   └── SystemPromptAssembler.java     # 基础模板 + 槽位 + 目标摘要 + 历史 SYSTEM + 约束
├── constraint/
│   ├── AgentConstraint.java           # 接口（id/order/isActive/render）
│   └── constraints/                   # KbIrrelevant / KbForced / MultiTurn / HistoryImage
├── tool/
│   ├── HarnessToolProvider.java       # 扩展点：工作流等贡献工具
│   ├── ToolRegistryAssembler.java     # 组装 Toolkit（内置 + MCP + SKILL + provider）
│   ├── ProjectToolAdapter.java        # 迁移
│   ├── McpToolAdapter.java            # 迁移
│   ├── RagSearchTool.java             # 迁移
│   ├── TimeTool.java                  # 迁移
│   ├── ToolRetriever.java             # 迁移（接入按需筛选）
│   └── ToolCardStore.java             # 迁移
├── step/
│   ├── AgentStep.java                 # 迁移
│   └── AgentAction.java               # 迁移
└── stream/
    └── ThinkTagStreamFilter.java      # 迁移
```

保留原位：`rag/core/skill/*`（SkillTool/ToolReaderTool/WebSearchTool/SkillLoader/SandboxExecutor 属技能引擎）、`rag/core/agent/AgentScopeReActExecutor`（循环引擎）、`rag/core/tool/{Tool,ToolResult,ToolNameUtil}`（共享抽象，避免 harness ↔ skill 循环）。

### 3.2 依赖方向（ArchUnit `rag.core.(*)` 无环约束下推演）

```
harness.context ← harness.prompt / harness.constraint
harness.tool    → harness.context / skill / mcp / retrieve / tool
harness.step    → 无内部依赖
harness.stream  → 无内部依赖
agent           → harness.*（执行器编排）
skill           → tool（ToolReaderTool 读 SkillLoader；WebSearchTool 用 RetrievedChunk）
```

无环：context 为叶子；prompt/constraint 只依赖 context + `rag.core.prompt`（模板工具）；tool 依赖 skill/mcp/retrieve（单向，skill 不反向依赖 tool 子包）。

### 3.3 Agent 循环改造点

`AgentScopeReActExecutor.run()` 五步（选模型 / 组装 Toolkit / SystemPrompt / 建 Agent / 订阅）保持，其中：

- 组装 Toolkit → `ToolRegistryAssembler.assemble(ctx, providers)`；
- SystemPrompt → `SystemPromptAssembler.assemble(ctx, toolNames, constraints)`；
- 新增第 6 步：`.middleware(new DynamicContextMiddleware(registry, ctx.getDynamicContexts()))`；
- 事件处理 / SSE 映射 / 引用溯源 / trace 逻辑不动，`AgentStep` 等引用改为新包路径。

---

## 4. 工作流功能设计

### 4.1 数据模型

单表 `t_workflow`（工作流为结构化文本，不引入文件树/blob 版本体系；每次编辑 update，保留 `change_log`）：

```sql
CREATE TABLE IF NOT EXISTS t_workflow (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL UNIQUE,   -- 唯一标识（^[a-z0-9]+(-[a-z0-9]+)*$）
    title         VARCHAR(128) NOT NULL,          -- 展示名（中文可）
    description   VARCHAR(1024) NOT NULL,         -- 何时使用（召回注入用）
    steps         TEXT         NOT NULL,          -- 结构化步骤 JSON
    source        VARCHAR(16)  NOT NULL DEFAULT 'EXTRACTED',  -- EXTRACTED / MANUAL
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    embedding     TEXT,                            -- 名称+描述的向量缓存（JSON 数组）
    change_log    VARCHAR(512),
    updated_by    VARCHAR(64),
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

`steps` JSON 结构（提取产物，允许分支）：

```json
{
  "steps": [
    { "id": 1, "action": "查询订单状态", "tool": "order_query", "args": {"orderId": "${input.orderId}"} },
    { "id": 2, "action": "判断争议类型", "branch": [
        { "when": "物流延误", "goto": 3 },
        { "when": "商品质量", "goto": 5 }
    ]},
    { "id": 3, "action": "..." }
  ],
  "notes": "边界说明/前置条件"
}
```

> 步骤中的 `tool` 为"建议调用"而非强制绑定：工作流以自然语言步骤 + 分支条件为主，Agent 执行时仍可调用任意工具；结构化字段用于后续演进（确定性执行引擎）预留。

### 4.2 三个内置工具

| 工具名 | 作用 | 关键约束 |
|--------|------|----------|
| `workflow_extract` | 从当前会话上下文提取工作流草稿（结构化输出），写 Redis 草稿并返回确认文案 | 只生成草稿，不落库；草稿 key = `conversationId` |
| `workflow_save` | 用户确认后落库 | **服务端校验**：Redis 中存在草稿 + 当前提问为肯定语义（`^确认|保存|是的|yes|...`）→ 否则拒绝（防模型擅自保存） |
| `workflow_use` | 按名称加载完整步骤到上下文 | 加载后调用 `DynamicContextRegistry.activate("workflow:steps:<name>")` 并 `deactivate("workflow:candidates")` → 下一轮迭代起候选清单被清除 |

工具注册方式：`WorkflowToolProvider implements HarnessToolProvider`，由 `ToolRegistryAssembler` 统一注册；工具描述包含"何时使用"，与 SKILL 一致受 `ToolNameUtil` 规范化。

### 4.3 确认交互（复用现有协议）

- 后端 `workflow_extract` 返回的 Observation 文本包含：

```
[WORKFLOW_CONFIRM]
name: refund-dispute
title: 退款争议处理
description: 用户投诉订单退款相关问题时使用
steps:
1. 查询订单状态
2. 判断争议类型：物流延误 → 3；商品质量 → 5
...
[/WORKFLOW_CONFIRM]
```

- 前端在 `MessageItem` 新增解析（与 `[USER_CHOICE]` 同级），渲染确认卡片：`确认保存` / `取消` / `补充说明`（带输入框）；
- 点击后作为用户消息发送：`确认保存` / `取消` / 用户补充文本 → 触发下一轮 Agent 调用 → `workflow_save` 或重新 `workflow_extract`。

### 4.4 召回阶段（pipeline）

在 `StreamChatPipeline.doExecuteAgent` 的"KB 语义选择"之后新增：

```
traceNode("工作流召回", "WORKFLOW_RECALL", () -> {
    candidates = workflowRecallService.recall(userOriginalQuestion);   // 低阈值 embedding 余弦
    if (!candidates.isEmpty()) {
        registry.register(new ContextBlock(
            "workflow:candidates",
            renderCandidateList(candidates),   // 名称 + 描述 + 使用指引
            priority 10));
    }
});
```

- 阈值低（默认 0.25，可配 `rag.workflow.recall-threshold`），宁多勿漏；
- 只注入 name/title/description，不注入 steps；
- 召回失败（embedding 不可用）→ 静默降级，不影响对话。

### 4.5 上下文清除机制（核心）

```
迭代 1：system = [基础模板] + [候选工作流清单] + ...
        模型：workflow_use(name=refund-dispute)
迭代 2：workflow_use 执行时 registry.deactivate("workflow:candidates") 且 register(steps block)
        onReasoning 中间件每轮重建 system message
        → 迭代 2 的 system 只含 [被选中工作流的步骤]，候选清单消失
```

实现要点：`DynamicContextMiddleware.onSystemPrompt` 返回 `base + activeBlocks`；`DynamicContextRegistry` 为每请求实例（非 Spring 单例），线程安全（工具执行线程与事件线程）。

### 4.6 管理端

`WorkflowController`（`/admin/workflows`，`@SaCheckRole(ADMIN)`）：

| Method | Path | 说明 |
|--------|------|------|
| GET | `/admin/workflows` | 列表 |
| GET | `/admin/workflows/{name}` | 详情（含 steps） |
| POST | `/admin/workflows` | 手工新建 |
| PUT | `/admin/workflows/{name}` | 编辑（重新计算 embedding） |
| DELETE | `/admin/workflows/{name}` | 删除 |
| POST | `/admin/workflows/{name}/toggle` | 启停 |
| POST | `/admin/workflows/rebuild-index` | 重建向量索引（模型切换后） |

前端：`WorkflowListPage.tsx`（参照 `SkillListPage` 双栏：左列表 + 右详情/编辑），路由 `/admin/workflows`，菜单"工作流管理"。

---

## 5. 配置

```yaml
rag:
  workflow:
    enabled: true                    # 工作流功能总开关
    recall-threshold: 0.25           # 召回余弦阈值（低阈值宁多勿漏）
    recall-top-k: 8
    draft-ttl-minutes: 30            # Redis 草稿有效期
```

`.env-example` 对应 `WORKFLOW_ENABLED` / `WORKFLOW_RECALL_THRESHOLD`。

---

## 6. 实施计划与验证

| 阶段 | 内容 | 验证 |
|------|------|------|
| P1 Harness 重构 | 上述 3.1 目录与改造 | `./mvnw -q compile`、`ArchitectureTest`、现有单测全绿；行为不变 |
| P2 工作流后端 | 表 + 存储/召回/工具/管线 | 新增单测（校验/召回/上下文清除） |
| P3 工作流前端 | 确认卡片 + 管理页 + 路由菜单 | `npx tsc --noEmit`、`npm run lint`、`npm run build` |
| P4 收尾 | 文档、spotless、全量测试 | `./mvnw test`、`spotless:check` |

## 7. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 重构引入行为回归 | P1 保持逻辑逐行等价，仅移动位置与收敛抽象；现有测试 + 新增 Harness 单测 |
| 中间件改写 system 与 AgentScope 版本耦合 | 已核对 2.0.0 源码；若升级框架，Harness 中间件为唯一适配点 |
| 模型擅自调用 `workflow_save` | 服务端语义校验（草稿存在 + 用户肯定回复），不信任模型 |
| 工作流分支靠 LLM 判断不确定 | 步骤文本显式写清分支条件；后续可演进为确定性执行引擎（结构化 `steps` 已预留） |
| 召回低阈值带来噪声 | 候选只注入名称+描述（token 成本低）；模型可忽略；`enabled` 可整体关闭 |

---

## 8. 实施状态（2026-09-19）

P1~P4 全部完成并端到端验证。

### 8.1 交付物

**Harness 重构（Phase 1）**

| 子模块 | 类 | 说明 |
|--------|-----|------|
| `harness/context` | `AgentContext`（Builder 化，删死字段）`ContextBlock` `DynamicContextRegistry` `DynamicContextMiddleware` `ContextBlockIds` | 每请求动态上下文；中间件每轮重建 system message |
| `harness/prompt` | `SystemPromptAssembler` | 模板槽位 + 目标摘要 + 历史 SYSTEM + 约束编排 |
| `harness/constraint` | `AgentConstraint` + `MultiTurnConstraint` `HistoryImageConstraint` `KbForcedConstraint` | 原 `agent-reminder.st` 的 if-else 改为可插拔约束 |
| `harness/tool` | `ToolRegistryAssembler` `ToolAssemblyContext` `HarnessToolProvider` + 迁移的 `ProjectToolAdapter` `McpToolAdapter` `RagSearchTool` `TimeTool` `ToolRetriever` `ToolCardStore` `ToolCard` | 工具可见性唯一入口，新增 `HarnessToolProvider` 扩展点 |
| `harness/step` `harness/stream` | `AgentStep` `AgentAction` `ThinkTagStreamFilter` | 纯迁移 |

`AgentScopeReActExecutor` 从 1315 行降至约 1050 行（净减 252 行），只保留模型选择、事件→SSE 映射、引用溯源、trace。

**工作流（Phase 2）**

- `resources/database/schema_all.sql` 尾部新增 `t_workflow` 表
- `rag/workflow/`：`WorkflowDO` / `WorkflowMapper`、`WorkflowService`（CRUD + 向量缓存 + 变更事件）、`WorkflowRecallService`（内存索引 + 低阈值余弦召回）、`WorkflowDraftStore`（Redis 草稿，TTL 可配）、`WorkflowJson`（编解码 + 校验 + name 规范化）、`WorkflowRenderer`（候选清单/步骤/确认标记）、`WorkflowToolProvider`、`WorkflowAdminService`、`controller/WorkflowController`（7 个端点）
- `rag/workflow/tool/`：`WorkflowExtractTool` `WorkflowSaveTool`（含服务端确认防呆）`WorkflowUseTool`
- `StreamChatPipeline` 新增"工作流召回"trace 阶段（KB 语义选择之后）
- 配置：`application.yaml` 的 `rag.workflow.*`、`.env-example` 的 `WORKFLOW_ENABLED` / `WORKFLOW_RECALL_THRESHOLD`
- `RAGStudioApplication` 的 `@MapperScan` 增加 workflow mapper 包

**前端**

- `components/chat/WorkflowConfirm.tsx`：确认卡片（YES / NO / Other 带输入框），`MessageItem` 解析 `[WORKFLOW_CONFIRM]` JSON 载荷
- `services/workflowService.ts`、`pages/admin/workflows/WorkflowListPage.tsx`（双栏 + 步骤编辑 + 启停 + 重建索引）
- `router.tsx` 路由与 `AdminLayout` 菜单/面包屑

### 8.2 端到端验证记录

真实实例（`server.port=9091`）+ 真实模型验证：

| 场景 | 结果 |
|------|------|
| 管理端新建/详情/启停/删除 | 通过；`indexed` 标记正确反映向量缓存状态 |
| 向量索引构建 | 日志 `工作流召回索引构建完成: 1 / 1 条启用工作流` |
| 召回 + 使用 | 提问"客户投诉订单退款一直没到账，物流也延误了" → Agent 自动 `workflow_use(name=refund-dispute)`，Observation 返回完整步骤与分支条件 |
| 建议工具缺失降级 | 步骤建议的 `order_query` 不存在 → Agent 用 `tool_reader` 搜索替代，未中断流程 |
| 提取 + 确认卡片 | "把刚才这个退款争议的处理流程固定成工作流" → `workflow_extract` 生成草稿，最终回答携带 `[WORKFLOW_CONFIRM]` JSON；**未擅自调用 `workflow_save`** |
| 确认保存 | 用户回"确认保存" → `workflow_save(action=confirm)` 落库成功（DB 校验：`source=EXTRACTED`、`change_log=从对话提取并确认`、5 步） |
| Other 分支 | 用户回"第三步有问题，应该先核对库存" → Agent 改调 `workflow_extract` 重新提取（新增库存核对步骤），**未触发保存** |
| 上下文清除（单测） | `DynamicContextMiddlewareIntegrationTest`：真实 `ReActAgent` 两轮迭代，第 1 轮注入候选、工具失效后第 2 轮候选消失且基础提示词保留 |
| 全量测试 | 145/145 通过（含 `ArchitectureTest` 6/6）；`spotless:check`、`npx tsc --noEmit`、`npm run build` 通过 |

### 8.3 与原始需求的对应

| 需求 | 实现 |
|------|------|
| 1. SKILL 从对话提取工作流 | `workflow_extract`（内置工具，非 SKILL 包；理由：需要访问 AgentContext 与 Redis 草稿，纯声明式 SKILL 无法承载） |
| 2. 正确回答后用户可要求固定 | 工具描述限定"用户明确要求把刚才的流程固定"时调用 |
| 3. Agent 正常调用工具即可发现 | 工具注册进 Toolkit，模型按描述自主调用 |
| 4. 固定前展示 + YES/NO/Other | `[WORKFLOW_CONFIRM]` 标记 + 前端 `WorkflowConfirm` 卡片；Other 走 `sendMessage("补充说明：…")` 触发重新提取 |
| 5. 低精度 Embedding + 低阈值召回，注入名称与描述 | `WorkflowRecallService`（默认阈值 0.25、TopK 8），候选清单只含 name/title/description |
| 6. 流程不注入（选中才加载） | `workflow_use` 按需加载完整步骤（渐进式披露） |
| 额外：选中后清除其余候选 | `DynamicContextMiddleware` + `ContextBlockIds.WORKFLOW_CANDIDATES` 失效机制 |

### 8.4 部署注意

1. 存量库执行增量 DDL（`schema_all.sql` 中 `t_workflow` 段）建表；
2. 召回向量复用 `tool_selector` 默认模型（Embedding 能力），可在 `.env` 调整 `WORKFLOW_RECALL_THRESHOLD`；
3. 模型切换后调用 `POST /admin/workflows/rebuild-index` 或等待下次工作流变更自动重建；
4. Redis 不可用时：草稿无法跨轮确认（提取会提示），召回静默降级为不注入，均不影响对话主链路。

---

## 9. 自查修复记录（2026-09-19）

端到端验证后的系统性复查，发现并修复 6 个问题：

| # | 问题 | 影响 | 修复 |
|---|------|------|------|
| 1 | `WorkflowController.create` 用原始 name 查详情，服务端会规范化（`Refund Dispute` → `refund-dispute`），导致返回 404 | 后管新建非 kebab-case 名称时报错 | 用 `create()` 返回的实体 name 查详情 |
| 2 | 编辑工作流标题/描述后向量不重算（`applyEmbedding(force=false)` 只比较模型） | **召回语义与展示文本失配**：改名后仍按旧语义召回 | 新增 `embedding_text_hash` 列 + `embeddingStale()` 判定（模型或文本指纹变化即重算） |
| 3 | 提取的工作流与已有同名时静默覆盖 | 用户可能无意丢失已沉淀流程 | 草稿记录 `overwrite` 标记 → 卡片显示"将覆盖更新"警告 + 工具 Observation 要求模型显式提示 |
| 4 | 召回索引与查询模型不一致时仍做余弦比对 | 模型切换后产生无意义召回噪声 | 索引记录 `indexModel`，不一致时跳过召回并提示等待重建 |
| 5 | 召回路径每次对话直查 DB 读模型配置（`getModelId` 无缓存） | 主链路增加无谓 DB 查询 | `currentEmbeddingModel()` 加 30s 进程内缓存 |
| 6 | 每次工作流变更 `new Thread` 重建索引 | 批量操作时线程无界增长 | 单线程执行器（队列容量 1，重复触发丢弃，全量重建天然幂等） |

另修复：`WorkflowConfirm` 历史消息中的卡片未禁用（应仅最后一条可交互）、`ToolRegistryAssembler` 日志硬编码"内置=4"（实为 3）、清理 `ToolAssemblyContext.newNameSet` / `WorkflowDraftStore.fromDO` 死代码。

验证：147/147 测试通过（新增 `embeddingTextHashDetectsTitleOrDescriptionChange`、`confirmBlockCarriesOverwriteFlag`）；真实实例复验 name 规范化、向量指纹变化、同名覆盖提示三项修复均生效。

---

## 10. 前端确认交互优化（2026-09-19）

调研腾讯元宝、Claude Agent SDK（`AskUserQuestion`）、Vercel AI Elements `Confirmation`、Tool UI `ApprovalCard`、AgentsKit `ToolConfirmation` 等主流 Agent 客户端后，按以下共识重构确认 UI：

### 10.1 调研结论

| 共识 | 代表实现 |
|------|----------|
| 确认有**生命周期状态**（request → approved/rejected → receipt 回执） | Vercel AI Elements、Tool UI、AgentsKit |
| **三档确认强度**：可逆轻确认、破坏性显式确认、灾难性强确认（输入短语） | Brainy《AI Agent UI Design Patterns》、UI Potion |
| 选项带 **description** 说明后果 | Claude Agent SDK `AskUserQuestion` 的 `{label, description}` |
| **可达性**：Escape 取消、`role="status"` 播报、`prefers-reduced-motion` | Tool UI、shadcn Confirmation |
| 结构化选项 + **自由文本兜底**并存 | Claude SDK `response` 字段、腾讯云选项卡节点默认带"其它" |

反模式：所有操作同权重 → 用户无脑点过（Brainy 明确点名）。

### 10.2 实施内容

**`WorkflowConfirm` 重写**（`frontend/src/components/chat/WorkflowConfirm.tsx`）

- **三段式状态机**：`request`（待确认）→ 提交 → `receipt`（confirmed / cancelled / edited 只读回执）
- **回执态可从消息历史还原**：`MessageList.findNextUserMessage` 向后扫描 6 条找下一条用户消息 → `MessageItem.inferWorkflowDecision` 判定决策（与后端 `WorkflowSaveTool.isAffirmative` 语义对齐）→ 刷新页面后回执仍正确
- **视觉权重分层**：确认保存实心 emerald（主）、取消为纯文字按钮（次）、修改意见为常驻输入框
- **内联输入常驻**：去掉"点击展开"二次交互；`⌘/Ctrl + Enter` 提交；输入时禁止折叠步骤
- **可达性**：`aria-label` 卡片、`aria-live="polite"` + `role="status"` 回执、`motion-reduce:transition-none`、焦点环
- **暗色适配**：`--color-*` token + `dark:` 变体（原实现为硬编码 `gray-*`/`indigo-*`）
- **步骤折叠**：超过 4 步可折叠；回执/失效态固定展开（便于回顾）

**`UserChoices` 扩展**（`frontend/src/components/chat/UserChoices.tsx`）

- 支持选项 `description`（`types/index.ts` 的 `UserChoiceOption` 增加可选字段）
- 历史回执：`selectedText` 匹配时高亮已选项 + 未选项降透明度 + `role="status"` 提示
- `aria-pressed`、焦点环、暗色适配；历史消息（非最后一条）禁用交互

**解析层**（`MessageItem.tsx`）

- `[USER_CHOICE]` 支持 `选项文本 | 选项说明` 语法（` | ` 分隔，向后兼容纯文本）

**后端 Prompt**（`react-system-agentscope.st` 规则 8）

- 补充说明语法：`当选项后果不明显时（如保存、覆盖、删除），用 | 追加一句说明`

### 10.3 验证

- `npx tsc --noEmit`、`eslint`（新增文件 0 error）、`npm run build` 通过
- 无头 Chrome 渲染真实组件截图验证 7 个状态：request、confirmed/cancelled/edited 回执、overwrite 警告、历史失效态、UserChoices 含说明/历史回执
- 暗色模式计算样式验证：`--color-bg-container: #222222`、卡片背景 `rgb(34,34,34)`
