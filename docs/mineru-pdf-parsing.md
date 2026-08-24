# MinerU PDF 智能解析接入设计

> 状态：**设计中（调研完成）**　|　作者：ByteQ　|　日期：2026-08-21
>
> 目标：为文本型知识库引入 **MinerU** 处理 PDF 中的公式、文字、表格，并在前端提供「本地 MinerU / 远程 MinerU / 多模态 LLM」三选一的解析引擎配置，替代当前慢速的多模态 LLM 兜底链路。

---

## 1. 背景与问题

### 1.1 当前 PDF 解析链路

当前 `TikaDocumentParser.extractAsMarkdown` 对 PDF 的处理（`extractPdfWithVision`）：

```
PDF
 ├─ 检测：Tabula 检测表格 + PDFBox 检测图片
 ├─ 若「有表格 或 有图片」
 │     └─ 整篇走 多模态 LLM（DocumentVisionExtractor.extractPdfWithVision）
 ├─ 若「纯文本型」
 │     └─ Tika(XHTML→Markdown) + Tabula 表格补充
 └─ 若 Tika 为空 → PDFBox 逐页提取
```

### 1.2 存在的问题

| 问题 | 说明 |
|------|------|
| **慢** | 含表格/图片的 PDF 整篇走多模态 LLM，逐页渲染成图片传 base64，一次推理又慢又贵 |
| **页数截断** | `MAX_VISION_PAGES = 10`，超过 10 页的 PDF 尾部内容直接丢弃（`DocumentVisionExtractor`、`PdfTableExtractor` 均受限） |
| **公式弱** | 多模态 LLM 对数学公式/化学式的识别不稳定，输出质量不可控 |
| **依赖网络/云** | 多模态识别强依赖云端 `doc_image` 模型，无网络或未配置模型时整个解析瘫痪 |

### 1.3 调研结论

**MinerU**（上海 AI 实验室 OpenDataLab 开源，AGPL-3.0）正是为「复杂文档 → LLM 就绪 Markdown」而生：

- **输出结构**：标题 `#` 层级、公式→LaTeX（`$...$` / `$$...$$`）、表格→HTML/Markdown、图片→base64，**直接兼容现有 `StructureAwareTextChunker`**（识别 Heading / Paragraph / CodeFence / Atomic）。
- **OCR 84+ 语言**，中文友好；阅读顺序重建，双栏/图文混排不乱。
- **三大引擎**：
  - `Pipeline`：原子模型流水线（版面检测 → OCR → 公式 → 表格 → 重组），CPU/GPU 均可，快、无幻觉，**推荐日常默认**。
  - `VLM`：视觉语言模型理解复杂布局，精度高，慢。
  - `Hybrid`：VLM + Pipeline 混合，低幻觉高精度。
- **部署方式**：REST API（`mineru-api`，OpenAI 兼容 `/file_parse`）、Docker、Desktop、Gradio、**MCP Server**。
- **GPU 版约 15× 快于 CPU**；官方镜像 GPU-only，CPU 需 slim 镜像装 `mineru[core]` 并指定 `backend=pipeline`。

> 关键契合点：MinerU 输出是标准 Markdown，**可以直接无缝喂给现有的 `StructureAwareTextChunker`，无需新写分块器**。只需把「分块前的内容来源」从多模态 LLM 换成 MinerU 即可。

---

## 2. 设计目标

1. **三选一解析引擎**：前端（知识库创建/编辑）提供「本地 MinerU / 远程 MinerU / 多模态 LLM」选择，默认 `AUTO`（自动：优先 MinerU，失败回退）。
2. **解析质量兜底**：MinerU 结果不达标时自动回退多模态 LLM（保底），不让旧能力丢失。
3. **Markdown 直通**：MinerU 输出的 Markdown 直接走现有 `StructureAwareTextChunker` 分块入库，复用现有链路。
4. **平滑可回退**：未部署 MinerU / 未配置端点 / MinerU 异常时，自动降级到现有 Tika + 多模态链路，不影响线上。
5. **配置分层**：解析引擎为**知识库级偏好**（默认），允许**文档级覆盖**（上传单篇文档时临时指定）。

---

## 3. 术语与职责划分

| 术语 | 含义 | 归属层 |
|------|------|--------|
| 解析引擎（ParserEngine） | 用哪种工具解析复杂文档（PDF/扫描件） | 知识库级（默认）+ 文档级（覆盖） |
| `LOCAL_MINERU` | 调用本机部署的 MinerU（REST/MCP） | 配置于系统设置 |
| `REMOTE_MINERU` | 调用远程 MinerU 服务（SaaS/私有云 REST API） | 配置于系统设置 |
| `MULTIMODAL_LLM` | 走现有多模态 LLM（`doc_image` 模型）兜底 | 现有能力 |
| `AUTO` | 自动：优先 MinerU，失败回退多模态 LLM | 默认值 |

---

## 4. 配置设计（前后端贯穿）

### 4.1 解析引擎枚举（后端）

新增 `ParseEngine` 枚举（`bootstrap/.../core/parser/ParseEngine.java`）：

```java
public enum ParseEngine {
    AUTO("AUTO", "自动（优先MinerU，失败回退多模态LLM）"),
    LOCAL_MINERU("LOCAL_MINERU", "本地 MinerU"),
    REMOTE_MINERU("REMOTE_MINERU", "远程 MinerU"),
    MULTIMODAL_LLM("MULTIMODAL_LLM", "多模态 LLM");

    private final String value;
    private final String label;

    public static ParseEngine normalize(String v) {
        // 空/null → AUTO；未知值 → AUTO
    }
}
```

> 说明：`LOCAL_MINERU` / `REMOTE_MINERU` 运行时行为差异只在「端点地址」，故共用同一套 MinerU 客户端，仅 baseUrl 不同。二者前端仍分开显示，便于用户显式表达部署位置（也便于未来为本地 vs 远程走不同鉴权/并发策略）。

### 4.2 系统级 MinerU 端点配置（新增表 / 或复用配置表）

MinerU 服务地址属于**全局基础设施配置**，不属于某个知识库。设计为**独立配置表**或并入现有系统设置。

**方案：新增 `t_mineru_config` 表**（隔离清晰，含启停开关）：

```sql
CREATE TABLE t_mineru_config (
    id          VARCHAR(64) PRIMARY KEY,
    -- 本地 / 远程 各一套
    local_enabled    BOOLEAN DEFAULT FALSE,      -- 是否启用本地 MinerU
    local_base_url   VARCHAR(512),               -- 如 http://127.0.0.1:8000
    local_backend    VARCHAR(32) DEFAULT 'pipeline', -- pipeline | vlm | hybrid
    local_extra      VARCHAR(1024),              -- 预留扩展（并发、lang_list 等 JSON）
    remote_enabled   BOOLEAN DEFAULT FALSE,
    remote_base_url  VARCHAR(512),
    remote_api_key   VARCHAR(512),               -- 可选
    remote_backend   VARCHAR(32) DEFAULT 'pipeline',
    remote_extra     VARCHAR(1024),
    updated_by       VARCHAR(64),
    update_time      TIMESTAMP DEFAULT now()
);
```

> 若项目已有「系统设置键值表」，可将其作为一组 key 存储（如 `mineru.local.baseUrl` 等），避免新增表。落地时以现有系统设置实现为准，此处给出独立表作为推荐形态。

### 4.3 知识库级解析引擎配置（修改 `t_knowledge_base`）

新增一列 `parse_engine`，与 `supports_image_embedding` 同级：

```sql
ALTER TABLE t_knowledge_base
    ADD COLUMN parse_engine VARCHAR(32) DEFAULT 'AUTO';
```

- 默认 `AUTO`。
- 多模态知识库（`supports_image_embedding=1`）仍优先走既有多模态流程；`parse_engine` 在文本型知识库生效。

### 4.4 文档级解析引擎覆盖（修改 `t_knowledge_document`）

新增一列，支持单篇文档临时指定：

```sql
ALTER TABLE t_knowledge_document
    ADD COLUMN parse_engine VARCHAR(32);
```

- `NULL` → 沿用知识库级 `parse_engine`。
- 有值 → 覆盖知识库级配置。

---

## 5. 后端架构设计

### 5.1 解析器新增：`MineruDocumentParser`

新增 `DocumentParser` 实现，注册到 `DocumentParserSelector`（策略模式自动收集）：

```
core/parser/
 ├─ MineruDocumentParser.java   ← 新增
 ├─ TikaDocumentParser.java     （现有）
 ├─ MarkdownDocumentParser.java （现有）
 └─ DocumentParserSelector.java （现有，自动收集）
```

**关键设计点：**

1. **`getParserType()` 返回 `ParserType.MINERU`**（新增枚举值）。
2. **`supports(String mimeType)`**：只对 PDF（`application/pdf` / `pdf`）及可被 MinerU 处理的格式返回 true；其余返回 false，不抢 Tika 的活。
3. **`extractAsMarkdown()`**：调用 `MineruClient`（见 5.2），将 PDF 字节 POST 到 MinerU `/file_parse`，取返回的 `md_content`。
4. **超时保护**：MinerU 首调需下载模型（CPU 60–120s），单次解析超时设为 **300s**（比现有多模态 120s 更长，因模型加载+解析），但通过 daemon 线程池 + Future 控制，避免占用业务线程。
5. **健康检查**：启动/首次调用时探测端点连通性；不可用则日志告警并跳过，走 AUTO 回退。

### 5.2 统一客户端：`MineruClient`

新增 `MineruClient`（HTTP 客户端，复用项目现有 OkHttp 依赖 `okhttp`）：

```
infra-ai/.../mineru/
 ├─ MineruClient.java          // 统一封装本地/远程 MinerU REST 调用
 ├─ MineruProperties.java      // 读取 t_mineru_config / 配置项
 ├─ MineruRequest.java         // file_parse 请求参数（backend、lang_list、return_md）
 └─ MineruResponse.java        // 响应解析（md_content、middle_json 等）
```

**核心接口：**

```java
public interface MineruClient {
    /** 解析文档字节为 Markdown */
    String parse(byte[] bytes, String fileName, MineruEndpoint endpoint);

    /** 探测端点连通性（用于健康检查） */
    boolean ping(MineruEndpoint endpoint);
}
```

**MineruEndpoint 抽象**（本地 / 远程 统一模型）：

```java
public record MineruEndpoint(String baseUrl, String backend, String apiKey, String lang) {
    public static MineruEndpoint local()   { ... }   // 读 local_* 配置
    public static MineruEndpoint remote()  { ... }   // 读 remote_* 配置
    public static boolean localConfigured() { ... }  // local_enabled && baseUrl 非空
    public static boolean remoteConfigured(){ ... }
}
```

### 5.3 解析引擎选择策略：`ParseEngineResolver`

新增决策器，把「知识库级/文档级配置 + 引擎可用性」解析为实际执行路径。核心方法是**纯函数式决策**，便于测试：

```
core/parser/
 └─ ParseEngineResolver.java   ← 新增
```

```java
@Component
public class ParseEngineResolver {

    /**
     * 根据知识库配置 + 文档配置 + 引擎可用性，决策 PDF 解析走哪条路径。
     * @return 实际解析执行器（Mineru / Multimodal / Tika），失败时由上层回退
     */
    public DocumentParser resolveParser(ParseEngine kbEngine,
                                        ParseEngine docEngine,
                                        String mimeType,
                                        boolean mineralAvailable) {
        ParseEngine effective = docEngine != null ? docEngine : kbEngine;

        // 1) 非 PDF / 非复杂文档 → 交回 Tika（MinerU 只管复杂文档）
        if (!isPdf(mimeType) && !isMineruDocType(mimeType)) {
            return tikaParser;
        }

        // 2) 显式多模态 → 走多模态（TikaDocumentParser 内部会触发）
        if (effective == MULTIMODAL_LLM) {
            return tikaParser; // Tika 内部对含图表 PDF 已走多模态 LLM
        }

        // 3) 显式 MinerU（本地/远程）→ 若能连通走 MinerU，否则降级
        if (effective == LOCAL_MINERU || effective == REMOTE_MINERU) {
            return mineralAvailable ? mineruParser : tikaParser;
        }

        // 4) AUTO → 优先 MinerU，不可用则 Tika（Tika 内部再回退多模态）
        return mineralAvailable ? mineruParser : tikaParser;
    }
}
```

> **决策规则**：
> - 显式 `MULTIMODAL_LLM` → 永远走多模态（保持用户明确意图）。
> - 显式 `LOCAL_MINERU` / `REMOTE_MINERU` 但端点未配置/不可达 → 告警并降级 Tika（Tika 内部对复杂 PDF 仍会走多模态，保底不丢内容）。
> - `AUTO`（默认）→ MinerU 优先，失败/不可用降级 Tika，Tika 再内部回退多模态。**即 MINERU → 多模态 LLM 的两级兜底**。

### 5.4 解析主流程改造（`KnowledgeDocumentServiceImpl`）

把现有写死的 `parserSelector.select(ParserType.TIKA.getType())` 改为按解析引擎决策：

```java
// 决策解析器
ParseEngine kbEngine = kbDO.getParseEngine() == null
        ? ParseEngine.AUTO : ParseEngine.normalize(kbDO.getParseEngine());
ParseEngine docEngine = documentDO.getParseEngine() == null
        ? null : ParseEngine.normalize(documentDO.getParseEngine());

boolean mineruAvailable = mineruClient.hasUsableEndpoint();
DocumentParser parser = parseEngineResolver.resolveParser(
        kbEngine, docEngine, mimeType, mineruAvailable);

String text = parser.extractAsMarkdown(is, documentDO.getDocName());
```

**兜底逻辑（在 `extractAsMarkdown` 内部或外层）：**

```
MineruDocumentParser.extractAsMarkdown:
  1) 调 MinerU → md_content
  2) 若 md_content 为空 或 长度 < MIN_TEXT_LENGTH(50) 或 抛异常
       → 降级调用 TikaDocumentParser.extractAsMarkdown（内部自动走多模态 LLM 兜底）
  3) 返回结果
```

> 这样「MinerU 效果差 → 回退多模态 LLM」的兜底在**解析器内部自洽完成**，业务层无需感知。

### 5.5 流水线 `ParserNode` 改造（可选，二期）

`ParserNode` 目前固定用 `parserSelector.select(ParserType.TIKA.getType())`。后续让 Pipeline 的 parser 节点配置支持 `parseEngine`（存入 `ParserSettings.ParserRule.options`），复用 `ParseEngineResolver`。**本期可先只改 CHUNK 流程（`KnowledgeDocumentServiceImpl`），Pipeline 流程二期接入。**

---

## 6. 前端设计

### 6.1 知识库创建/编辑弹窗（`CreateKnowledgeBaseDialog.tsx`）

在「Embedding 模型」区块下方新增「**解析引擎**」单选组（当所选 Embedding 为文本型模型时显示）：

```
解析引擎（PDF/复杂文档）：
  (•) 自动（推荐）—— 优先本地MinerU，不可用回退多模态LLM
  ( ) 本地 MinerU
  ( ) 远程 MinerU
  ( ) 多模态 LLM
```

- 使用 **RadioGroup**（而非 Select），语义更清晰。
- 默认选中 `AUTO`。
- 辅助文案随选项变化：
  - `AUTO`：优先本地/远程 MinerU 识别公式、表格，失败自动回退多模态 LLM
  - `LOCAL_MINERU`：使用本机部署的 MinerU 服务（需在系统设置配置端点）
  - `REMOTE_MINERU`：使用远程 MinerU API 服务
  - `MULTIMODAL_LLM`：强制使用多模态大模型（`doc_image`），适合 MinerU 识别不佳的复杂版面
- 提交时携带 `parseEngine` 字段到 `createKnowledgeBase`。

### 6.2 知识库列表展示（`KnowledgeListPage.tsx`）

- 在表格中新增「解析引擎」列，展示枚举中文标签。
- 支持编辑（复用编辑弹窗，含解析引擎选项）。

### 6.3 文档上传（可选，文档级覆盖）

`KnowledgeDocumentsPage` 上传弹窗中，新增可选「**本片文档解析引擎**」下拉，默认「跟随知识库」，可临时指定覆盖。

### 6.4 系统设置 MinerU 端点配置（`SystemSettingsPage.tsx`）

新增「MinerU 解析服务」配置区块：

```
本地 MinerU：
  [x] 启用         [http://127.0.0.1:8000]   base URL
  [pipeline ▾]    backend 引擎（pipeline / vlm / hybrid）
  状态：[探测连通性]

远程 MinerU：
  [x] 启用         [https://...]            base URL
  [ ] API Key      [****]                   （可选）
  [pipeline ▾]    backend 引擎
  状态：[探测连通性]
```

- 增加「**探测连通性**」按钮，调用后端 `ping` 接口实时反馈可达性，避免用户配错。
- 对应后端 `MineruConfigController`（GET/PUT `t_mineru_config`）。

### 6.5 前端服务层

- `knowledgeService.createKnowledgeBase / updateKnowledgeBase`：请求体加 `parseEngine`。
- 新增 `mineruService`：`getMineruConfig()` / `updateMineruConfig()` / `pingMineru(endpoint)`。
- 新增 `ParseEngine` 类型与中文标签映射。

---

## 7. 数据库改动汇总

| 表 | 改动 | 说明 |
|----|------|------|
| `t_knowledge_base` | +列 `parse_engine VARCHAR(32) DEFAULT 'AUTO'` | 知识库级解析引擎 |
| `t_knowledge_document` | +列 `parse_engine VARCHAR(32)`（可空） | 文档级覆盖 |
| `t_mineru_config`（新） | 本地/远程端点、backend、启停、apiKey | MinerU 服务配置 |

**初始化脚本**：上述 DDL 已并入 `resources/database/schema_all.sql`（全新部署单文件初始化）。

---

## 8. 配置优先级与决策矩阵

```
有效解析引擎 = 文档级 parse_engine ?? 知识库级 parse_engine ?? AUTO
```

| 有效引擎 | MinerU 可用 | 实际路径 |
|----------|------------|----------|
| `AUTO`（默认） | 是 | **MinerU → 失败回退多模态 LLM** |
| `AUTO` | 否 | Tika（内部对复杂 PDF 走多模态 LLM） |
| `LOCAL_MINERU` | 是 | MinerU（本地端点） |
| `LOCAL_MINERU` | 否 | 告警 → Tika（多模态兜底） |
| `REMOTE_MINERU` | 是 | MinerU（远程端点） |
| `REMOTE_MINERU` | 否 | 告警 → Tika（多模态兜底） |
| `MULTIMODAL_LLM` | 任意 | 多模态 LLM（显式强制，忽略 MinerU） |

---

## 9. 与现有链路的兼容性

| 现有能力 | 影响 |
|----------|------|
| `StructureAwareTextChunker` | **不受影响**，MinerU 输出的 Markdown 直接喂入，分块逻辑零改动 |
| 多模态知识库（`supports_image_embedding=1`） | **不受影响**，仍走 `runMultimodalProcess`；`parse_engine` 仅文本型 KB 生效 |
| `TikaDocumentParser` | 保留为默认回退，`extractAsMarkdown` 内部多模态兜底逻辑不变 |
| 纯图片文件（png/jpg） | 不受影响，仍走视觉提取 |
| Office（docx/pptx/xlsx） | MinerU 也能处理，但本期 `supports()` 只对 PDF 及可识别复杂格式返回 true，其余交回 Tika，避免行为变化 |
| `DocumentParserSelector` | 自动收集新 `MineruDocumentParser`，无需改动选择逻辑 |

---

## 10. 实施计划（分阶段）

### Phase 1：基础设施与 MinerU 客户端
- [ ] 部署 MinerU（Docker：本地用 slim 镜像 + `mineru[core]`，`backend=pipeline`；或远程 SaaS/私有云）
- [ ] 用项目自己的 PDF 评测集跑通 MinerU → Markdown，验证公式/表格/中文效果
- [ ] 新增 `MineruEndpoint` / `MineruClient` / `MineruProperties`（REST `/file_parse`，超时 300s）
- [ ] 新增 `ParseEngine` 枚举 + `t_mineru_config` 表 + 配置读写
- 验收：可用程序调用 MinerU 解析 PDF 返回 Markdown

### Phase 2：解析器接入 + 知识库级配置
- [ ] 新增 `MineruDocumentParser`（`ParserType.MINERU`，`supports` 限 PDF/复杂文档）
- [ ] 新增 `ParseEngineResolver` 决策器 + 兜底降级（MinerU → 多模态 LLM）
- [ ] 改造 `KnowledgeDocumentServiceImpl` 解析入口，接入 `parse_engine`
- [ ] 新增 `t_knowledge_base.parse_engine` 列 + `KnowledgeBaseCreateRequest/UpdateRequest` 字段
- 验收：知识库创建时选「本地MinerU」/「多模态LLM」，文档入库走对应引擎；MinerU 失败自动回退多模态

### Phase 3：前端配置
- [ ] `CreateKnowledgeBaseDialog` 增加「解析引擎」RadioGroup
- [ ] `KnowledgeListPage` 展示/编辑解析引擎列
- [ ] `SystemSettingsPage` 增加 MinerU 端点配置 + 连通性探测
- [ ] 新增 `mineruService` 前端服务
- 验收：前端三选一配置生效，端点探测反馈可达性

### Phase 4：文档级覆盖 + 流水线（可选）
- [ ] `t_knowledge_document.parse_engine` 文档级覆盖
- [ ] `ParserNode` 支持 pipeline 场景的 parseEngine（`ParserSettings.ParserRule.options`）
- 验收：单篇文档可临时指定解析引擎

---

## 11. 风险与对策

| 风险 | 对策 |
|------|------|
| MinerU 首次加载模型慢（CPU 60–120s） | 超时放宽到 300s；daemon 线程池隔离；可预热 |
| 本地 MinerU 未部署 | 健康检查探测，未配置自动走 AUTO 回退，不阻塞线上 |
| MinerU 对个别复杂版面识别差 | 内置「结果不达标 → 回退多模态 LLM」两级兜底 |
| 引入 Python 服务增加运维负担 | 封装为独立 Docker 服务/HTTP 端点，与 Java 主服务解耦 |
| AGPL-3.0 许可证约束 | 独立服务部署隔离，不内嵌进 Java 进程；合规评估后再定 |
| `parse_engine` 配置漂移 | 默认 `AUTO`，文档级为可空覆盖，决策矩阵集中管理 |

---

## 12. 待确认事项

1. **许可证合规**：AGPL-3.0 独立部署的服务与项目如何隔离，是否需法务确认（独立进程 + 网络调用通常视为隔离）。
2. **MinerU 引擎默认值**：默认 `pipeline`（CPU 快）还是按文档复杂度动态 `hybrid`。
3. **MinerU 是否只处理 PDF**：是否要扩展支持 DOCX/PPTX/XLSX（MinerU 支持，但会改变现有 Office 处理行为）。
4. **配置文件落点**：`t_mineru_config` 独立表 vs 并入现有系统设置键值表。
5. **远程 MinerU 形态**：是否已有可用的远程 SaaS/私有云端点，还是本期只做本地。

---

## 附：MinerU 关键参考

- 官方仓库：`github.com/opendatalab/MinerU`　|　文档：`opendatalab.github.io/MinerU/`
- REST API：`POST /file_parse`（multipart，`files` + `backend` + `lang_list` + `return_md`），OpenAI 兼容
- 官方 Docker 镜像 GPU-only；CPU 用 `python:3.12-slim` + `pip install "mineru[core]"`，`backend=pipeline`
- 引擎：`pipeline`（原子模型，快）/ `vlm`（视觉理解，精）/ `hybrid`（混合，低幻觉）
- 生态：已集成 LangChain / LlamaIndex / Dify / FastGPT / Cursor / Claude，支持 MCP Server

---

## 附录 B：远程 mineru.net 官方 API 接入（已落地）

> 无需本机部署。官方提供两种远程 API，项目当前接入 **Agent 轻量 API（免费免 Token）**。

### B.1 两种远程 API 对比

| 对比 | 🎯 精准解析 API（v4） | ⚡ Agent 轻量 API（v1）【当前采用】 |
|------|----------------------|----------------------------------|
| Token | 需要（API 管理页创建） | **无需**（IP 限频防滥用） |
| 文件限制 | ≤200MB、≤200 页 | **≤10MB、≤20 页** |
| 批量 | 支持（≤200 个） | 单文件 |
| 模型 | pipeline / vlm / MinerU-HTML | 固定 pipeline 轻量模型 |
| 输出 | Zip 包（full.md + JSON） | 仅 Markdown（CDN 直链 full.md） |

### B.2 协议适配设计

端点协议由 `MineruEndpointType` 区分，`MineruEndpoint` 按 baseUrl 自动推断
（含 `mineru.net` → `CLOUD_AGENT`；其他 → `LOCAL`，兼容自建远程 mineru-api）：

```
CLOUD_AGENT（mineru.net Agent API，异步任务流）
  ① POST {base}/parse/file   {file_name, language, enable_table, enable_formula}
       → data.task_id + data.file_url（OSS 签名上传地址）
  ② PUT  {file_url}          （文件字节，无须 Content-Type）
  ③ GET  {base}/parse/{task_id}   轮询 state：
       waiting-file/uploading/pending/running → 继续等（间隔 3s，总超时 timeoutSeconds）
       done     → data.markdown_url
       failed   → err_code/err_msg 记日志，返回空串触发回退
  ④ GET  markdown_url         下载 full.md 文本

LOCAL（本地/自建 mineru-api，同步）
  POST {base}/file_parse（multipart：files/backend/lang_list/return_md）
       → results.<file>.md_content
```

### B.3 启用步骤

1. `.env` 设置 `MINERU_ENABLED=true`；
2. 前端「系统设置 → MinerU 解析服务」勾选启用「远程 MinerU」并保存
   （默认地址即官方免费接口 `https://mineru.net/api/v1/agent`），可用「探测连通性」验证；
3. 知识库解析引擎选「自动」或「远程 MinerU」，上传 PDF 即可走云端解析；
4. 超限（>10MB 或 >20 页）时云端返回 failed，自动回退 Tika + 多模态 LLM 兜底，
   大文件如需高精度可后续扩展精准 v4 API（预留 `apiKey` 字段与 Bearer 鉴权头）。