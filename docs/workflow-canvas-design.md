# 工作流画布编排设计（Canvas Workflow Editor）

> 状态：设计稿（待评审）
> 关联：`docs/workflow-harness-design.md`（工作流数据模型与 Agent 执行链路）
> 需求：工作流/流水线支持画布拖拽式编排，降低使用门槛

---

## 1. 组件选型调研

### 1.1 候选对比

| 维度 | **React Flow (@xyflow/react)** | AntV X6 | LogicFlow | Rete.js |
|------|------|------|------|------|
| 协议 | MIT（核心库） | MIT | Apache-2.0 | MIT |
| React 集成 | **原生 React**（节点=React 组件，受控 nodes/edges） | 命令式 SVG 引擎，需 `@antv/x6-react-shape` 桥接 | 需 `@logicflow/react-node-registry` | 插件化，概念较重 |
| 与项目栈契合 | **React 18 ✓ / zustand 4 ✓**（React Flow 内部即用 zustand ^4.4） | 项目已有 G6（可视化）但非编辑器，API 不通用 | 一般 | 一般 |
| AI 工作流生态 | **事实标准**：Dify、Langflow、Flowise、n8n(部分) 均基于 React Flow | 国内流程图/BPMN 较多 | 国内审批流 | 数据流编程 |
| 自定义节点 | 完整 React 组件 + Tailwind/shadcn 直接可用 | HTML/React 挂载点，样式隔离成本高 | 类似 X6 | 复杂 |
| 内置能力 | 缩略图、对齐线、框选、键盘、撤销模板、自动布局示例（dagre/elk） | 丰富（snap、快捷键） | 中等 | 弱 |
| 生态/文档 | 最好（示例全覆盖：拖拽添加、循环校验、撤销重做、复制粘贴、自动布局） | 好（中文） | 一般 | 一般 |
| 包体积 | ~120KB（unpacked），gzip 后可控 | 较大 | 中等 | 中等 |

### 1.2 选型结论

**React Flow 12.x（`@xyflow/react`）**，理由：

1. **React 原生**：自定义节点就是普通 React 组件，可直接复用项目的 shadcn/Tailwind 设计系统与暗色 token；X6/LogicFlow 的 React 节点是"挂载桥接"，样式与状态管理都更别扭。
2. **AI 工作流事实标准**：Dify / Langflow / Flowise 均基于它，节点形态（LLM、工具、条件分支）与本项目工作流高度同构，可直接借鉴交互。
3. **栈完全兼容**：peer `react>=17`、内部依赖 `zustand^4.4`，项目为 React 18.3 + zustand 4.5，无版本冲突。
4. **免费能力足够**：拖拽添加节点、连线校验（防环）、自动布局（dagre/elk）、撤销重做、复制粘贴、保存恢复均有官方免费示例。

**注意（坑）**：

- React Flow **UI 组件库**（`ui.reactflow.dev` 的 shadcn 组件）当前要求 **React 19 + Tailwind 4**，与项目 React 18 + Tailwind 3.4 不符 → **不使用该组件库**，用核心库 + 自有 shadcn 组件手写节点（工作量可控，约 4 个节点类型）。
- 官方 **AI Workflow Editor 模板是 Pro 收费** → 不依赖，参考免费示例自行实现。
- 默认右下角有 "React Flow" 署名角标；MIT 允许隐藏（`proOptions={{ hideAttribution: true }}`），建议保留以示尊重（不影响功能）。

---

## 2. 数据模型设计

### 2.1 现状与目标

当前 `t_workflow.steps` 是**线性步骤 + 每步 when 条件**：

```json
{"steps":[{"action":"...","tool":"...","when":"物流延误"}],"notes":"..."}
```

画布需要真正的**图结构**（节点+连线+分支）。设计原则：

> **图是编辑态的事实源，线性步骤是运行态的编译产物。**
> `steps` 保留不动（Agent 注入链路零改动），新增 `graph_json` 存画布结构；保存画布时服务端编译出 `steps`。

### 2.2 节点/边模型

```jsonc
{
  "version": 1,
  "nodes": [
    { "id": "start", "kind": "start", "x": 0, "y": 120, "data": {} },
    { "id": "n1", "kind": "step", "x": 220, "y": 120,
      "data": { "action": "查询订单状态", "tool": "order_query" } },
    { "id": "c1", "kind": "condition", "x": 440, "y": 120,
      "data": { "expression": "物流是否延误" } },
    { "id": "n2", "kind": "step", "x": 660, "y": 40,
      "data": { "action": "发起物流赔付流程", "tool": null } },
    { "id": "n3", "kind": "step", "x": 660, "y": 200,
      "data": { "action": "转商品质检流程", "tool": null } },
    { "id": "end", "kind": "end", "x": 880, "y": 120, "data": {} }
  ],
  "edges": [
    { "id": "e1", "source": "start", "target": "n1" },
    { "id": "e2", "source": "n1", "target": "c1" },
    { "id": "e3", "source": "c1", "target": "n2", "label": "是" },
    { "id": "e4", "source": "c1", "target": "n3", "label": "否" },
    { "id": "e5", "source": "n2", "target": "end" },
    { "id": "e6", "source": "n3", "target": "end" }
  ]
}
```

节点类型（`kind`）：

| kind | 必填字段 | 出边约束 | 渲染 |
|------|----------|----------|------|
| `start` | — | 恰好 1 条 | 圆角胶囊，绿点 |
| `step` | `action` 必填，`tool` 可选 | ≥1 条（可多条=并行分支） | 卡片：序号 + action + 工具徽标 |
| `condition` | `expression` 必填 | ≥2 条，每条 `label` 必填（如"是/否"，或任意条件文本） | 菱形/圆角矩形，黄调 |
| `end` | — | 0 条 | 圆角胶囊，灰点 |

### 2.3 存储与兼容

```sql
ALTER TABLE t_workflow ADD COLUMN IF NOT EXISTS graph_json TEXT;
```

- **旧数据**（`graph_json IS NULL`）：读取时由服务端 `WorkflowGraph.fromSteps()` **自动生成线性图**（start → n1 → n2 → ... → end；带 `when` 的步骤前置一个 condition 节点），前端可无缝编辑；首次保存画布后落库。
- **steps 仍是运行态事实源**：`workflow_use` / 提示词注入 / 召回全链路**零改动**。
- **编译规则**（graph → steps）：拓扑排序后按序输出；
  - `condition` 节点本身不作为步骤输出，其表达式转为其后置分支步骤的 `when`（如 `when="物流是否延误：是"`）；
  - 汇聚节点（多入边）条件取并集描述（`"（物流延误 或 商品质量）"`），保证自然语言可读；
  - 无法线性化的图（如带环）在校验阶段直接拒绝保存。

### 2.4 校验规则（保存时，服务端）

| 规则 | 级别 |
|------|------|
| 恰好 1 个 start、恰好 1 个 end | ERROR |
| 无孤立节点（除 start/end 外必须有入边和出边） | ERROR |
| 无环（DAG） | ERROR |
| condition 节点出边 ≥2 且每条边 label 非空 | ERROR |
| step 节点 `action` 非空、长度限制（复用现有 `WorkflowJson`） | ERROR |
| 节点总数 ≤ 50（防爆炸） | ERROR |
| 所有路径可达 end | WARN |
| 节点坐标越界（±100000） | WARN（自动 clamp） |

---

## 3. 前端设计

### 3.1 页面结构

```
/admin/workflows/:name/edit  （画布编辑页，独立路由）

┌─────────────────────────────────────────────────────────────┐
│ ← 退款争议处理   [撤销][重做]  [自动布局]  [校验] [保存]      │
├──────────┬────────────────────────────────────┬─────────────┤
│ 节点面板  │           画布（React Flow）         │  属性面板    │
│          │                                    │             │
│ ▸ 开始    │   ┌──────┐    ┌──────┐             │ 选中节点后：  │
│ ▸ 步骤    │   │ 开始 │───▶│ 步骤 │──▶ …        │ 动作文本     │
│ ▸ 条件    │   └──────┘    └──────┘             │ 建议工具     │
│ ▸ 结束    │                                    │ 条件表达式   │
│          │        [缩略图] [缩放] [适应画布]     │ [删除节点]   │
├──────────┴────────────────────────────────────┴─────────────┤
│ 状态栏：6 个节点 · 6 条连线 · 校验通过（或错误列表）           │
└─────────────────────────────────────────────────────────────┘
```

- **拖拽添加**：左侧面板拖入画布（React Flow 官方 DnD 示例）；
- **连线**：拖 Handle 连接；`isValidConnection` 实时校验（start 无入边、end 无出边、禁止自环/成环）；
- **选中编辑**：右侧属性面板（受控表单），边选中可编辑 label（条件分支）；
- **删除**：选中 + Delete 键 / 属性面板按钮；
- **自动布局**：`@dagrejs/dagre` 分层布局（官方 dagre 示例，轻量 ~30KB）；
- **撤销/重做**：useUndoRedo 自定义 hook（官方示例，基于快照栈）；
- **暗色模式**：`colorMode` 跟随 `themeStore`；节点用 `--color-*` token。

### 3.2 组件拆分

```
frontend/src/pages/admin/workflows/
├── WorkflowCanvasPage.tsx          # 页面骨架（路由入口）
├── canvas/
│   ├── WorkflowCanvas.tsx          # ReactFlow 容器 + 受控状态
│   ├── nodes/
│   │   ├── StartNode.tsx
│   │   ├── StepNode.tsx
│   │   ├── ConditionNode.tsx
│   │   └── EndNode.tsx
│   ├── NodePalette.tsx             # 左侧拖拽面板
│   ├── PropertyPanel.tsx           # 右侧属性编辑
│   ├── CanvasToolbar.tsx           # 顶部工具条
│   ├── graphModel.ts               # 前端图模型 <-> React Flow 节点/边转换
│   └── useUndoRedo.ts              # 快照式撤销重做
└── WorkflowListPage.tsx            # 改造：详情区增加"画布编辑"入口
```

### 3.3 交互细节

- 列表页详情区：现有"步骤列表"保留只读展示；新增「画布编辑」按钮跳转 → `/admin/workflows/:name/edit`；
- 画布页离开时脏检查（`beforeunload` + 路由拦截提示）；
- 保存成功 toast + 版本号/变更说明（复用现有 `change_log`）；
- 小屏降级：宽度 < 1024px 时提示"建议桌面端编辑"，仍可用（属性面板改抽屉）。

---

## 4. 后端设计

### 4.1 接口变更

| Method | Path | 变更 |
|--------|------|------|
| GET | `/admin/workflows/{name}` | 响应新增 `graph`（`graph_json` 为空时服务端自动生成线性图） |
| PUT | `/admin/workflows/{name}` | 请求体新增可选 `graph`；提供时执行图校验 + 编译，`steps` 由服务端生成（请求里的 steps 忽略） |
| POST | `/admin/workflows` | 同上 |
| POST | `/admin/workflows/validate` | （新增，可选）仅校验图并返回错误列表，供画布"校验"按钮 |

### 4.2 后端类

```
rag/workflow/graph/
├── WorkflowGraph.java              # record：nodes/edges/version + 嵌套 Node/Edge
├── WorkflowGraphValidator.java     # 结构校验（§2.4）
├── WorkflowGraphCompiler.java      # graph -> List<WorkflowStep>（拓扑排序 + 条件合成）
└── WorkflowGraphFactory.java       # steps -> 线性 graph（旧数据兼容）
```

- `WorkflowService.saveOrUpdate` 增加图参数分支：有 graph → 校验 → 编译出 steps → 落库 `graph_json` + `steps`；
- 现有 `WorkflowJson`（steps 校验/编解码）保留，编译产物复用其校验；
- Agent 侧（`workflow_use`、召回、提取工具）**不改**。

### 4.3 与对话提取的关系

`workflow_extract` 从对话提取的仍是**线性 steps**（LLM 输出图结构稳定性差）。
服务端接收后 `WorkflowGraphFactory.fromSteps()` 生成线性图 → 用户在画布上二次加工分支。
（后续可选：提取提示词升级为输出分支，作为增强项，不在本期。）

---

## 5. 依赖与体积

```bash
npm i @xyflow/react@^12 @dagrejs/dagre
```

| 包 | 用途 | gzip 估算 |
|----|------|-----------|
| `@xyflow/react` | 画布核心 | ~50KB |
| `@dagrejs/dagre` | 自动布局 | ~25KB |
| 合计 | 仅 `/admin/workflows/:name/edit` 路由 lazy 加载 | ~75KB |

不影响聊天主包（路由级 code-split）。

---

## 6. 实施计划

| 阶段 | 内容 | 验证 |
|------|------|------|
| P0 依赖验证 | 安装依赖，最小画布 demo 跑通（React18+Tailwind3 兼容、暗色） | `npm run build` |
| P1 后端图模型 | `WorkflowGraph/Validator/Compiler/Factory`、schema 加列、接口支持 graph、单测（编译/校验/旧数据兼容） | `mvnw test` |
| P2 画布 MVP | ReactFlow 容器 + 4 类节点 + 拖拽添加 + 连线校验 + 属性面板 + 保存 | 手工操作全流程 |
| P3 体验完善 | 自动布局、撤销重做、缩略图、脏检查、暗色、移动端降级 | 截图/交互验证 |
| P4 收尾 | 旧数据迁移验证（steps→图）、文档、spotless/tsc/lint/build | 全量测试 |

**预估**：P1 约 1 天，P2 约 1.5 天，P3 约 1 天，合计 3.5~4 天（含联调）。

---

## 7. 风险与取舍

| 风险 | 缓解 |
|------|------|
| 图 → 线性 steps 编译有损（并行/复杂汇聚表达不精确） | 编译条件用自然语言描述（LLM 可读即可）；运行态仍是软执行；后续若做确定性执行引擎再升级 |
| 图与 steps 双份数据不一致 | steps 是编译产物，服务端单点生成，禁止客户端直传 steps（有 graph 时） |
| 旧数据无 graph | 读取时自动生成线性图，首次保存落库；无需批量迁移脚本 |
| 用户画的图过于复杂 | 节点 ≤50 硬限制；校验实时反馈；官方"防环"示例 |
| React Flow UI 组件库版本不兼容 | 明确不使用，节点全部自研（约 4 个组件） |
| 拖拽与聊天页手势冲突 | 独立路由页，不复用聊天画布 |

---

## 8. 待确认决策

1. **入口形态**：独立路由页 `/admin/workflows/:name/edit`（推荐，空间大） vs 列表页内嵌 Tab；
2. **流水线（ingestion pipeline）是否同批做**：其模型已有 `nextNodeId + conditionJson`，可复用画布组件，但涉及执行引擎语义，建议**作为下一期**；
3. **运行态是否要升级为确定性执行引擎**（按图逐节点执行工具）：本期不做，保持"软执行"（图只用于编辑/展示/编译），后续单独立项；
4. 节点上限 50、是否允许并行分支多出边（推荐允许，编译为并列条件）。

---

## 9. 实施状态（2026-09-20）

P0~P4 已实现并端到端验证。决策确认：独立路由页、本期只做工作流（流水线下一期）、不做确定性执行引擎、节点上限 50 且允许并行分支多出边。

### 9.1 交付物

**后端**

| 类 | 说明 |
|----|------|
| `graph/WorkflowGraph` | 图模型（nodes/edges + 4 种节点类型）；`isEmpty` 标注 `@JsonIgnore` 避免派生属性污染 JSON |
| `graph/WorkflowGraphValidator` | 结构校验（单 start/end、无环、条件出边≥2 且标签必填、孤立节点、节点≤50/连线≤100、坐标/长度约束），返回 ERROR/WARN |
| `graph/WorkflowGraphCompiler` | 图 → 线性 steps：Kahn 拓扑排序（同层稳定）+ 路径条件合成（`（条件：是） 且 步骤when`，汇聚用 `或`），超长编译结果拒绝保存 |
| `graph/WorkflowGraphFactory` | 线性 steps → 线性图（旧数据兼容，`when` 保留在节点上） |
| `graph/WorkflowGraphJson` | 图 JSON 编解码（宽容解析未知字段） |
| `WorkflowService` | `saveOrUpdate(..., graph, ...)`：图存在时校验+编译，steps 以编译产物为准；线性保存时同步生成线性图（图与 steps 始终一致）；`resolveGraph` 读取时自动补图 |
| `WorkflowAdminService` / `WorkflowController` | 详情返回 `graph`；新建/编辑接受 `graph`；新增 `POST /admin/workflows/validate` |
| `t_workflow.graph_json` | 画布图存储列（schema_all.sql + 存量库 `ADD COLUMN IF NOT EXISTS`） |

**前端**

| 文件 | 说明 |
|------|------|
| `pages/admin/workflows/WorkflowCanvasPage.tsx` | 独立路由 `/admin/workflows/:name/edit`；加载/保存/校验/撤销重做/自动布局/脏检查/离开确认 |
| `canvas/graphModel.ts` | 前后端模型互转、连线校验（防自环/成环/重复/start 入边/end 出边）、指纹计算（脏标记） |
| `canvas/WorkflowNodes.tsx` | 4 类自定义节点（Start/Step/Condition/End），复用 `--color-*` token，支持选中态 |
| `canvas/NodePalette.tsx` | 左侧拖拽面板；start/end 已存在时置灰 |
| `canvas/PropertyPanel.tsx` | 右侧属性编辑（步骤动作/工具/条件；条件表达式；连线分支标签）+ 删除 |
| `router.tsx` / `AdminLayout.tsx` | 路由注册 + 全高布局（无内边距、内容区不滚动） |
| `WorkflowListPage.tsx` | 详情区新增「画布编辑」入口；线性编辑器提示不覆盖画布分支 |
| `services/workflowService.ts` | `WorkflowGraph` 类型、`validateWorkflowGraph`、保存负载支持 `graph` |

### 9.2 交互能力

- 拖拽添加节点（`NODE_DND_TYPE`）、拖圆点连线（实时校验并 toast 阻止非法连接）、Delete 删除
- 自动布局（`@dagrejs/dagre` LR 分层）
- 撤销/重做（快照栈，上限 50；属性输入 800ms 合并为一个撤销点）
- 未保存脏标记 + 浏览器 `beforeunload` + 页内「放弃并离开」确认
- 校验按钮 → 状态栏展示首条 ERROR/WARN + toast 汇总；保存时后端二次校验
- 暗色模式：`colorMode={theme}` + 缩略图按节点类型着色

### 9.3 验证记录

| 项 | 结果 |
|----|------|
| 后端单测 | 164/164 通过（新增 `WorkflowGraphTest` 17 项：校验/编译/旧数据转换/JSON 往返） |
| 画布建流 | `POST /admin/workflows` 带图 → 编译产物 `1.查询订单 when=null / 2.发起赔付 when=（物流是否延误：是） / 3.转质检 when=（物流是否延误：否）` |
| 校验接口 | 非法图（条件缺标签+成环）返回 3 条 ERROR |
| 旧数据兼容 | 线性创建 → GET 详情自动返回 4 节点 3 连线线性图，`when` 保留在 step 节点 |
| Agent 链路 | 画布工作流被召回，`workflow_use` Observation 含编译后的分支条件，Agent 按条件执行 |
| 图编辑 | PUT 带新图 → 分支移除后编译产物同步为 2 步线性 |
| 前端构建 | `tsc` / `eslint`（新增文件 0 error）/ `npm run build` 通过；React Flow 仅进入画布路由包（207KB → gzip ~60KB，主包无污染） |
| 渲染验证 | 无头 Chrome 截图核验浅色/暗色两套（节点、连线标签、属性面板、缩略图、控件） |

### 9.4 画布内完整编辑（2026-09-20 补充）

工作流画布从"仅编辑已有工作流"升级为**完整编排器**，节点与基本信息均可在画布内增删改编排：

| 能力 | 入口 |
|------|------|
| **新增节点** | 左侧面板拖拽（开始/步骤/条件/结束）到画布 |
| **删除节点/连线** | 选中 + Delete/Backspace，或右侧属性面板删除按钮 |
| **编辑节点** | 右侧属性面板：步骤（动作/建议工具/执行条件）、条件（表达式）、连线（分支标签） |
| **编排** | 拖动节点、拖圆点连线（实时校验防环/自环/重复）、自动布局（dagre） |
| **编辑基本信息** | 工具条下方信息栏直接编辑：标识（新建时可填，编辑态只读）、标题、描述、说明 |
| **新建工作流** | 列表页「新建」→ `/admin/workflows/new`（独立路由，无 `:name`）；画布预置 `开始 → 占位步骤 → 结束`，保存调用 `createWorkflow` 后 `replace` 跳转正式编辑路由 |

配套调整：

- 脏标记从"仅图指纹"扩展为**图 + 基本信息**组合指纹，标题/描述/说明改动同样触发未保存提示；
- 列表页「新建」入口切到画布，原新建弹窗分支移除（弹窗仅保留线性步骤快速编辑）；
- 撤销/重做覆盖节点、连线与属性修改（输入类改动 800ms 合并为一个撤销点）。

**自查修复（2026-09-20）**

| # | 问题 | 处理 |
|---|------|------|
| 1 | **路由冲突**：新建用 `/admin/workflows/new/edit`，而 `new` 是合法工作流名（后端 `NAME_PATTERN` 允许）→ 编辑名为 new 的工作流会误入新建态 | 新建改独立路由 `/admin/workflows/new`（无 `:name`），`isCreate = !name`；流水线同理改为 `/admin/ingestion/pipelines/new` |
| 2 | 编辑态保存未校验标题/描述且未 trim | 两模式统一校验，提交前 trim |
| 3 | 加载/新建跳转后未重置选中态与校验结果，属性面板可能残留旧节点 | 加载时重置 `selectedNode/selectedEdge/validation` |
| 4 | 流水线新建的脏标记只含图，改名称/描述不触发未保存提示 | 指纹扩展为 图 + 名称 + 描述 |
| 5 | 新建首帧 `loading=true` 出现加载态闪烁 | 初始值改为 `!isCreate` |
| 6 | AdminLayout 全高条件未覆盖新路由 | 纳入两个 `/new` 路径 |

验证：`npx tsc -b --noEmit` 0 错误；`npm run build` 通过；无头 Chrome DOM 断言核验新建模式渲染（信息栏输入、工具栏「创建工作流」、节点 `start/step-1/end` 与连线 `e-1/e-2`、暗色 class 生效）；真实后端 E2E：画布新建的空白图载荷创建成功并编译出步骤，且 `new` 确实是合法工作流名（佐证冲突为真）。

### 9.5 下一期

- 流水线（ingestion pipeline）画布重构：复用本画布组件与交互，落地时按其 `nextNodeId + conditionJson` 模型适配（已确认在本次之后进行）

---

## 10. 流水线（Ingestion Pipeline）画布重构（2026-09-20）

在工作流画布之后，按确认的范围完成摄入流水线的画布化。与工作流不同，流水线是**确定性执行**，
因此本次是真正的引擎改造：单链 → 排他分支 DAG。

### 10.1 改造前的关键问题

| # | 问题 |
|---|------|
| 1 | 执行模型是单链表（`nextNodeId`），不支持分支/并行；`conditionJson` 只是节点级门禁（不满足则跳过该节点，不改变走向） |
| 2 | **条件协议前后端不匹配**：前端写 `{field, op, value}` + 扁平字段 `source_type`，后端读 `operator` + `source.type` → UI 配置的条件会让节点被静默跳过 |
| 3 | 前端是表单卡片 + 上下移按钮，非可视化编排 |
| 4 | 更新流水线传空 `nodes` 数组不会清空节点（静默保留旧链） |
| 5 | 分支/汇聚场景下任务节点日志顺序按静态链计算，不反映实际执行顺序 |

### 10.2 架构：图（编辑态）+ 节点行（运行态）

```
t_ingestion_pipeline.graph_json（新增，TEXT）——画布图，编辑态事实源
        │ 保存：校验 → 编译
        ▼
t_ingestion_pipeline_node（新增 branches_json TEXT）——运行态
   next_node_id = 无条件后继（兜底）
   branches_json = [{condition, nextNodeId}]（排他分支）
```

**排他分支语义**：节点执行完后按 branches 顺序对摄入上下文求值，首个命中者作为后继；
全部不命中走 `next_node_id`（兜底）；兜底为空则流水线正常结束。条件网关（condition 节点）
不产出运行节点——编译时把其出边（含条件）内联到前驱 processor 的分支上。

### 10.3 交付物

**后端**

| 类 | 说明 |
|----|------|
| `ingestion/domain/graph/IngestionGraph` | 图模型：start / processor / condition / end 四类节点；processor 携带 nodeType+settings+门禁条件；condition 仅作视觉分组，分支条件挂在出边上 |
| `IngestionGraphValidator` | 结构校验：单 start/end、无环、processor 类型合法、多出边时最多一条无条件（兜底）、条件节点入边不得带条件、不可连条件节点、节点≤50/连线≤100、至少 1 个处理节点 |
| `IngestionGraphCompiler` | 图 → `List<NodeConfig>`：Kahn 拓扑排序 + 条件网关内联 + end 映射为 null（终止）+ 分支/兜底拆分 |
| `IngestionGraphFactory` | 旧节点行 → 线性图（保留原始 nodeId，兼容任务日志）；已含分支的旧行 → 生成条件网关 |
| `IngestionGraphJson` | 图 JSON 编解码（宽容未知字段） |
| `NodeConfig.Branch` | 新增分支模型 `{condition, nextNodeId}`；`PipelineDefinition` 无需改动 |
| `IngestionEngine` | 三处改造：`resolveNextNode` 排他分支选择；`validatePipeline` 三色 DFS 环检测覆盖分支边；`findStartNode` 把分支目标计入被引用集合 |
| `ConditionEvaluator` | 修复：支持 `{expr}` 高级表达式；`op` 作为 `operator` 兼容别名；字段名归一化（`source_type`→`source.type` 等）；枚举按名称小写比较（`SourceType.FILE` ↔ `"file"`） |
| `IngestionPipelineServiceImpl` | `create/update` 接受 `graph`：校验+编译+落库 graph_json；线性保存时自动生成线性图（图与节点始终一致）；修复空数组不清空；详情返回 `graph`；新增 `validateGraph` |
| `IngestionTaskServiceImpl` | 有分支时任务节点日志按实际执行顺序编号 |
| API | `POST /ingestion/pipelines/validate`；`IngestionPipelineVO.graph`；Create/Update Request 支持 `graph` |
| Schema | `t_ingestion_pipeline.graph_json`、`t_ingestion_pipeline_node.branches_json`（schema_all.sql + 存量库 ADD COLUMN） |

**前端**

| 文件 | 说明 |
|------|------|
| `pages/admin/ingestion/pipeline/pipelineFormModel.ts` | 表单模型与纯函数（从 IngestionPage 抽取）：类型/常量/默认值/`buildSettings`/`buildNodeForm`/条件与解析规则处理；条件构建器改为输出后端规范协议（`operator` + `source.type`），修复旧协议 bug |
| `pages/admin/ingestion/pipeline/PipelineNodeFields.tsx` | 按节点类型的配置字段（7 类节点 + 节点门禁条件），弹窗与画布共用 |
| `pages/admin/ingestion/canvas/pipelineGraphModel.ts` | 图模型互转、连线校验（防环/自环/重复/start 入边/end 出边）、图指纹、缩略图配色、节点摘要 |
| `pages/admin/ingestion/canvas/PipelineNodes.tsx` | 4 类自定义节点（Start/Processor/Condition/End），按节点类型区分图标与配色 |
| `pages/admin/ingestion/canvas/IngestionNodePalette.tsx` | 左侧拖拽面板（7 类处理节点 + 条件分支） |
| `pages/admin/ingestion/canvas/IngestionPropertyPanel.tsx` | 右侧属性面板：处理节点配置（复用 PipelineNodeFields）、条件网关备注、连线分支条件（字段构建器 + 高级 JSON） |
| `pages/admin/ingestion/IngestionCanvasPage.tsx` | 独立路由 `/admin/ingestion/pipelines/:id/edit`：加载/保存/校验/撤销重做/自动布局/脏检查/暗色 |
| `IngestionPage.tsx` | 流水线列表新增「画布编辑」入口 |
| `services/ingestionService.ts` | `IngestionGraph` 类型、`validateIngestionPipelineGraph`、payload 支持 `graph` |

### 10.4 新建流水线也走画布（2026-09-20 补充）

数据通道的「新建流水线」入口不再打开表单弹窗，而是跳转画布新建页（`/admin/ingestion/pipelines/new/edit`）：

- **新建模式**：画布页通过 `id === "new"` 识别；工具条提供名称（必填）/描述输入，保存按钮为「创建流水线」；
- **模板起步**：工具条下方提供模板切换（标准 / 简洁 / 深度 / 空白画布），一键生成线性图，套用模板可撤销；
- **创建流程**：保存时调用 `createIngestionPipeline({name, description, graph})`（服务端校验 + 编译），成功后 `navigate(..., { replace: true })` 切换到正式编辑路由，返回键直达列表；
- **表单弹窗**：新建模式的模板选择 UI 已从 `IngestionPage` 移除（模板统一收敛到 `pipelineFormModel.PIPELINE_TEMPLATES`），弹窗仅保留编辑用途；原新建分支保留为兼容代码但 UI 不再触达。

### 10.5 附带修复：前端类型检查曾被跳过（重要）

本次发现 `frontend` 的 `npx tsc --noEmit` 实际是**空操作**：根 `tsconfig.json` 是 project references（`"files": []` + `references`），不带 `-b`/`-p` 时不会检查任何子项目；而 `npm run build` 仅执行 `vite build`（不做类型检查）。因此历史错误被长期掩盖。

正确命令（本次已用其全量修复）：

```bash
npx tsc -b --noEmit                      # 检查 app + node 两个子项目
npx tsc -p tsconfig.app.json --noEmit    # 仅前端源码
```

修复清单（类型级修复，除注明外不改运行时行为）：

| 文件 | 问题 | 处理 |
|------|------|------|
| `utils/error.ts` | `getErrorMessage(error, fallback)` 第二参必填，12 处单参调用 | fallback 设默认值 `"操作失败"` |
| `components/shared/DiffView.tsx` | `add` 可能为空未收窄；`expanded` 参数未使用 | 局部变量收窄；移除未使用参数 |
| `Knowledge/GraphCanvas.tsx` | G6 v5 `fitView/autoFit` 不支持 `padding`（原配置被静默忽略）；事件 `target` 类型缺失；异步回调丢失 null 收窄 | **padding 上移到 graph 级（修正适配内边距）**；事件断言；捕获局部 data |
| `knowledge/graph/layout.ts`、`layout.worker.ts` | `LayoutNode` 缺 d3-force 运行时写入的 `x/y/vx/vy` | 补可选字段 |
| `graphVisual.ts` | `labelPlacement` 字面量拓宽为 string | `as const` |
| `SkillDiffDialog.tsx` | 未使用的导入 | 移除 |
| `chatStore.ts`、`types/index.ts` | 防御性读取 `groupId` 但类型缺失 | `ConversationGroup` 增补 `groupId?: string` |
| `WorkflowConfirm.tsx`（本功能） | `RefObject<HTMLTextAreaElement \| null>` 与 `ref` 类型不兼容 | `useRef<HTMLTextAreaElement>(null)` |

结果：`npx tsc -b --noEmit` 0 错误。遗留 lint 错误（`MarkdownRenderer` 的 `any`、`chatStore` 的 `no-unsafe-finally`）为历史问题，本次未处理。建议把 AGENTS.md 中的类型检查命令改为 `npx tsc -b --noEmit`。

### 10.6 验证记录

| 项 | 结果 |
|----|------|
| 后端单测 | 181/181 通过（新增 `IngestionGraphTest` 17 项：校验/编译/网关内联/旧数据往返/引擎分支选择/协议兼容/JSON） |
| 存量兼容 | 种子流水线「通用文档通道」GET 详情自动返回 6 节点线性图，原始 nodeId（step_1..4）保留 |
| 分支创建 | 8 节点分支图创建成功，编译产物正确（parser 节点：分支→enhancer(PDF)、兜底→chunker），`branches_json` 正确落库 |
| 引擎分支（真实文件） | 上传 `.md` → 条件不匹配走兜底 chunker；PUT 图改为 `text/plain` 后上传 `.txt` → **走 parser 条件分支**（节点日志 s1→s2） |
| 校验接口 | 非法图返回 ERROR 列表（多无条件出边等） |
| 前端 | `tsc`/`eslint`（0 error）/`npm run build` 通过；画布路由包 43KB（gzip ~14KB），主包无污染 |
| 渲染 | 无头 Chrome 核验浅色/暗色（处理节点图标配色、条件网关、连线标签、属性面板表单、缩略图） |

### 10.7 与工作流画布的差异

| 维度 | 工作流 | 流水线 |
|------|--------|--------|
| 执行语义 | LLM 软执行（图编译为自然语言步骤） | 引擎确定性执行（图编译为 NodeConfig + 分支） |
| 条件承载 | 步骤文本 `when` | 连线 `condition`（结构化，运行时求值） |
| 分支语义 | 自然语言描述 | 排他分支，首个命中者生效，无条件边兜底 |
| 图运行时角色 | 编辑/展示 | 编辑 + 运行态定义（编译产物） |
