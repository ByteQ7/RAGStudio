# 知识图谱可视化交互改造规划（Graph Visualization Plan）

> 状态：**已实施完成**（2026-08-23，M1–M4 一次性交付）
> 范围：后管「知识图谱」页（`/admin/knowledge/:kbId/graph`）的图谱视图交互能力
> 目标：从「静态 mermaid 图片」升级为「可缩放/平移/点击/聚焦的交互式关系图谱」
> 实施说明：选型 G6 v5；组件位于 `frontend/src/pages/admin/knowledge/graph/`（GraphCanvas / GraphToolbar / GraphSearchBox / GraphLegend / EntityDetailPanel / graphVisual）；`KnowledgeGraphPage` 图谱 Tab 已替换 mermaid，懒加载 chunk 不影响首屏

---

## 1. 背景与目标

### 1.1 现状

当前图谱视图（`KnowledgeGraphPage.tsx`）用 **mermaid flowchart** 渲染后端子图数据（`GET /admin/graph/kb/{kbId}/graph`），输出为静态 SVG：

- ❌ 无法缩放/平移，图谱一大就看不清关系
- ❌ 节点不可点击，查看实体详情需切到「实体管理」Tab 手找
- ❌ 无法聚焦某个实体展开其局部子图
- ❌ 边标签在节点密集时重叠、无 hover 提示
- ❌ 无搜索定位、无图例、无节点大小/权重语义

后端数据已具备交互化的全部条件（子图接口支持 `focusEntityId` 聚焦、实体详情/列表接口齐备），**后端基本无需改动**，本规划为纯前端改造。

### 1.2 目标

| 目标 | 说明 |
|------|------|
| 基础导航 | 滚轮缩放、画布拖拽平移、双击空白重置视图 |
| 节点洞察 | 点击查看实体详情（右侧面板）、悬停高亮一阶邻居 |
| 聚焦探索 | 双击节点以该实体为锚点重新拉取子图（聚焦展开） |
| 快速定位 | 搜索实体名定位高亮、类型过滤 |
| 信息密度 | 节点大小=关系数、颜色=实体类型、边标签可开关、图例 |
| 体验细节 | 工具栏（缩放/布局/截图/全屏）、空态/截断提示、加载态 |

---

## 2. 需求梳理

### 2.1 交互清单（P0 必做 / P1 建议 / P2 可选）

| 编号 | 交互 | 优先级 | 说明 |
|------|------|--------|------|
| I-01 | 滚轮缩放（0.2x–3x，光标为中心） | P0 | 图可视化基础能力 |
| I-02 | 画布拖拽平移 | P0 | 同上 |
| I-03 | 双击空白处重置视图（fitView） | P0 | 迷路时一键回正 |
| I-04 | 节点 hover 高亮一阶邻居 + 弱化其余 | P0 | 关系感知 |
| I-05 | 点击节点 → 右侧详情面板 | P0 | 展示实体名称/类型/描述/别名/关系列表（调 `/admin/graph/entities/{id}`） |
| I-06 | 双击节点 → 聚焦展开 | P0 | 以该节点为锚调子图接口（`focusEntityId`），新节点带入场动画 |
| I-07 | 边 hover 显示谓词 tooltip；点击边高亮两端 | P0 | 关系语义 |
| I-08 | 工具栏：放大/缩小/重置 | P0 | 右上角悬浮 |
| I-09 | 工具栏：布局开关（力导重排 / 锁定布局） | P1 | 拖拽节点后可锁定 |
| I-10 | 工具栏：边标签显示开关 | P1 | 密图去噪 |
| I-11 | 工具栏：截图导出（PNG） | P1 | canvas 渲染天然支持 |
| I-12 | 工具栏：全屏 | P1 | 大图浏览 |
| I-13 | 实体搜索定位（输入框 → 高亮 + 居中） | P1 | 复用实体列表接口关键词检索 |
| I-14 | 类型图例（左下角）+ 类型过滤 | P1 | 颜色语义 |
| I-15 | 节点大小按关系数（度数）映射 | P1 | 信息密度 |
| I-16 | 截断提示（`truncated=true` 时引导双击聚焦） | P0 | 防止"为什么只有 200 个节点"困惑 |
| I-17 | 空态/未开启提示（沿用现有文案） | P0 | 保留现状 |
| I-18 | 节点拖拽（力导布局下拖后固定） | P2 | 手动整理布局 |

### 2.2 非目标（本期不做）

- 图编辑（增删节点/边）——实体合并已在「实体管理」Tab 完成
- 社区/全局检索视图（Phase 2 预留在设计文档，与本交互改造解耦）
- 移动端深度适配（后管页面，桌面为主，仅保证可用）

---

## 3. 技术选型

### 3.1 候选方案对比

| 方案 | 缩放/平移 | 力导布局 | 节点点击/高亮 | 包体积 | React 集成 | 维护 |
|------|-----------|----------|---------------|--------|-----------|------|
| **G6 v5**（@antv/g6） | ✅ 内建 | ✅ 内建（force/forceAtlas2） | ✅ 内建事件 + state | ~300KB gzip（按需） | 命令式挂载（ref+effect），文档有 React 示例 | 蚂蚁开源，中文文档完善 |
| ReactFlow（xyflow） | ✅ 内建 | ⚠️ 需 d3-force/dagre 自算 | ✅ onNodeClick 声明式 | ~200KB | ✅ 声明式最佳 | 活跃，英文文档 |
| ECharts graph | ✅ roam | ✅ force 布局 | ⚠️ 事件可用但自定义弱 | ~100KB（按需） | 命令式 | 百度开源，中文文档 |
| D3 手写 | 需自实现 | 需自实现 | 需自实现 | 0（大） | 命令式 | 自维护成本最高 |
| mermaid 增强 | ❌ 不支持 | ❌ | ❌ | - | - | - |

### 3.2 结论：采用 **G6 v5（@antv/g6）**

决策理由：

1. **核心需求全内建**：缩放/平移/力导布局/hover 高亮/点击事件/聚焦动画是 G6 的"图分析"主场能力，ReactFlow 的关系图力导需额外引入 d3-force 自算布局，ECharts 的自定义交互能力不足以支撑 P0 全清单；
2. **数据零改造**：后端子图 `{nodes:[{id,name,type}], links:[{source,target,predicate}]}` 与 G6 的 `{nodes, edges}` 数据模型天然对齐，`focusEntityId` 聚焦模式已有接口，前端只做一次字段映射；
3. **团队/文档**：中文文档与示例完善（antv.antgroup.com），项目为中文团队，排查问题成本低；
4. **可渐进替换**：G6 v5 支持按需引入，可先用 `Graph`（canvas）渲染器承载全部 P0，P2 再考虑 WebGL。

**备选**：若评审后倾向 React 声明式生态，退回 ReactFlow + `d3-force` 方案（交互规格不变，仅实现层替换）。

### 3.3 依赖与加载策略

- 新增依赖：`@antv/g6`（^5.x）
- **动态加载**：`React.lazy` + 页面内 `import('@antv/g6')`，仅图谱 Tab 激活时加载，不拖累首屏与聊天页
- mermaid 依赖**保留**（聊天消息 `MermaidBlock.tsx` 仍在用），仅图谱页移除

---

## 4. 交互设计规格

### 4.1 画布与导航

- 渲染：G6 `Graph`（Canvas 渲染器），容器占满 Tab 主体（约 100% × 600px+）
- 滚轮缩放：光标为中心，比例 0.2x–3x，缩放动画 200ms
- 平移：画布空白区左键拖拽
- 双击空白：`fitView()` 重置（含缩放），动画 300ms
- 节点拖拽：P2 开关「布局锁定」：默认开启（节点不可拖，避免误触）；关闭后可拖，拖完固定位置，力导不回收

### 4.2 节点与边

| 元素 | 静态样式 | 交互态 |
|------|----------|--------|
| 节点 | 圆点 + 标签；颜色=`typeColor[type]`（沿用现有 9 色映射）；半径=8 + log2(度数+1)×4 映射（度数由 links 前端统计） | hover：命中节点放大 1.2x + 一阶邻居描边高亮 + 其余降透明（0.25）；点击：外环选中描边 |
| 边 | 直线 + 箭头（direction=1 有向）；颜色 `--color-border-secondary`；`truncated=false` 时默认显示谓词标签（小号、可开关） | hover：谓词标签加粗 + 高亮；点击：两端节点外环高亮 |
| 新增节点（聚焦展开） | 入场动画：透明度 0→1 + 缩放 0.6→1（200ms），聚焦锚点脉冲提示 | - |

### 4.3 聚焦展开（双击节点）

```
双击节点 N
  ├─ loading 态（画布中心转圈）
  ├─ GET /admin/graph/kb/{kbId}/graph?focusEntityId={N.id}&maxNodes=200
  ├─ 合并差异：已存在节点保留位置（不抖动），新节点入场动画
  ├─ 视图平移 + 缩放至 N 居中（fitView 以 N 为中心）
  └─ 失败 → toast 错误，保持原视图
```

### 4.4 右侧详情面板（点击节点）

| 区块 | 内容 | 数据源 |
|------|------|--------|
| 实体信息 | 展示名、类型 Badge、规范化名、描述 | `GET /admin/graph/entities/{id}` |
| 别名 | 别名 chips（可编辑？本期只读） | 同上 |
| 关系列表 | 表格：谓词 / 关联实体 / 方向，点击行跳转定位到对应节点 | 同接口返回（`GraphEntityVO` 含关系） |
| 操作 | 「聚焦展开」（=双击）、「在实体管理中定位」（跳实体管理 Tab 并按名称过滤） | - |

### 4.5 工具栏（右上角悬浮，半透明）

```
[🔍 搜索实体]  [＋] [－] [⤢ 重置] | [🧭 布局:力导/锁定] [🏷 标签] [📷 截图] [⛶ 全屏]
```

- 搜索：输入 ≥2 字符 debounce 300ms 调实体列表接口（keyword），下拉选实体 → 画布定位高亮
- 截图：`canvas.toDataURL('image/png')` 下载
- 全屏：容器 `requestFullscreen`

### 4.6 图例与状态

- 左下角图例：9 种实体类型色点 + 类型名（点击切换显隐过滤）
- 右上角截断提示条：`truncated=true` → 常驻 banner「当前仅展示关系最密切的 N 个节点，双击节点可聚焦展开」
- 空态：沿用现有「图谱暂无数据 / 总开关未开启」文案

---

## 5. 前端架构设计

### 5.1 组件划分

```
pages/admin/knowledge/KnowledgeGraphPage.tsx        （改造：图谱 Tab 替换渲染层）
└── graph/
    ├── GraphCanvas.tsx          G6 封装：挂载/destroy、数据映射、事件转发、聚焦/定位 API
    ├── GraphToolbar.tsx         工具栏（缩放/布局/标签/截图/全屏）
    ├── EntityDetailPanel.tsx    右侧详情面板（点击节点触发）
    ├── GraphSearchBox.tsx       实体搜索定位
    └── GraphLegend.tsx          类型图例 + 过滤
```

### 5.2 数据流

```
后端 subgraph VO ──映射──> G6 {nodes, edges}
  nodes: {id, name(→label), type(→style.fill)}
  edges: {source, target, predicate(→label, data 保留)}
G6 事件 ──> 组件状态
  click node ──> EntityDetailPanel（异步拉详情）
  dblclick node ──> 调 subgraph(focusEntityId) ──> 增量更新 GraphCanvas
```

### 5.3 G6 封装要点（GraphCanvas）

- **生命周期**：`useEffect` 内 `new Graph({...})`，cleanup 必须 `graph.destroy()`（防 React StrictMode 双挂载泄漏）
- **数据更新**：`graph.setData()` + `graph.render()`；聚焦展开用「合并 diff」保留既有节点坐标（G6 `getNodeData` 回填位置后整体 setData，避免位置抖动）
- **事件**：`on('node:click')` / `on('node:dblclick')` / `on('node:pointerenter/leave')` / `on('edge:click')` 转发给 React 状态
- **工具栏联动**：`graph.zoomTo()/fitView()/getData()/setLayout()` 等通过 `useImperativeHandle` 暴露
- **布局缓存**：力导布局使用固定随机种子（G6 支持自定义 `randomSeed`），同图重复渲染位置稳定
- **大图降级**：`nodes.length > 500` 时强制隐藏边标签 + 关闭入场动画（保流畅）

### 5.4 新增/调整 API（frontend/src/services/graphService.ts）

| 函数 | 端点 | 说明 |
|------|------|------|
| `getGraphEntity(id)` | `GET /admin/graph/entities/{id}` | 新增：实体详情（现后端已有，前端补封装） |
| `getGraphSubgraph(kbId, {focusEntityId, maxNodes})` | 已有 | 不变，聚焦参数已支持 |
| `getGraphEntities(kbId, {keyword, ...})` | 已有 | 搜索定位复用 |

### 5.5 后端配套（本期）

| 项 | 结论 |
|----|------|
| 子图接口 | **不改**（focusEntityId 已支持） |
| 实体详情接口 | **不改**（已存在） |
| 可选增强（P2，本期不做） | subgraph 返回边 `weight`（节点/边粗细更精确）；返回节点度数（省前端统计） |

---

## 6. 实施里程碑

| 里程碑 | 内容 | 交付物 | 验收标准 |
|--------|------|--------|----------|
| **M1 基础渲染** | 引入 G6、动态加载；GraphCanvas 替换 mermaid 渲染 | 图谱 Tab 可交互画布 | 缩放/平移/重置可用；视图与现状等效（颜色/标签一致） |
| **M2 核心交互** | hover 高亮、点击详情面板、双击聚焦展开、截断提示 | P0 交互全量 | 双击聚焦后新节点入场、原节点不抖动；详情面板数据正确 |
| **M3 工具与体验** | 工具栏（缩放/布局/标签/截图/全屏）、搜索定位、图例过滤、空态 | P1 全量 + P0 打磨 | 各控件可用；大子图（>500 节点）不卡顿 |
| **M4 打磨与验证** | 布局种子稳定、动画细节、错误态、浏览器兼容（Chrome/Edge/Firefox） | 最终版 | 手工测试用例全绿；`tsc --noEmit` 通过 |

预计工作量：M1（0.5d）→ M2（1d）→ M3（1d）→ M4（0.5d），合计 **约 3 人日**。

---

## 7. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| G6 包体积（~300KB gzip） | 首屏加载 | React.lazy 仅图谱 Tab 动态加载；G6 v5 按需引入（Graph 核心 + force 布局插件） |
| G6 与 React 18 StrictMode 双挂载 | 画布重复/泄漏 | GraphCanvas 内严格 destroy 清理 + 幂等初始化；开发期验证 |
| 力导布局每次渲染位置抖动 | 体验差 | 固定随机种子 + 聚焦展开合并 diff 保留坐标 |
| 大子图渲染卡顿（200–800 节点） | 交互掉帧 | 节点数阈值降级（隐藏边标签/关闭动画）；maxNodes 上限维持 200 |
| mermaid 移除误伤聊天页 | 聊天渲染坏 | 不动 `MermaidBlock.tsx`，仅图谱页替换，回归验证 |
| 后端 subgraph 超时（万级边库） | 聚焦展开慢 | 维持 maxNodes=200 上限 + 前端 loading 态 + 错误 toast 保底 |

---

## 8. 验收清单（摘要）

- [x] 图谱视图支持滚轮缩放、拖拽平移、双击重置
- [x] 悬停节点高亮一阶邻居并弱化其余
- [x] 点击节点弹出右侧详情面板（名称/类型/描述/别名/关系列表）
- [x] 双击节点聚焦展开局部子图，原节点位置不抖动
- [x] 工具栏：缩放、重置、布局锁定、边标签开关、截图、全屏
- [x] 搜索实体可定位高亮；类型图例可见且可过滤
- [x] 截断提示引导聚焦；空态/未开启文案保留
- [x] 图谱 Tab 懒加载（页面级 chunk，含 G6 约 420KB gzip），不拖累首屏；`npx tsc --noEmit` 零错误
- [x] 聊天页 mermaid 渲染不受影响（`MermaidBlock.tsx` 未改动）