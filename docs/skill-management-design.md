# SKILL 管理与版本管理体系设计（DB 化 · 后管托管 · 版本 Diff）

> 状态：设计稿（待评审）
> 关联：`docs/graph-rag-design.md`（同类"DB 动态配置 + 降级"先例）、提示词管理（`t_prompt_config` 体系，本设计的直接参照物）

---

## 1. 背景与现状

### 1.1 现状

当前 SKILL 完全是**文件态**的：

- 位置硬编码在项目根目录 `skills/`（`application.yaml` 中 `rag.skills.dir: skills`，仅支持 `@Value` 静态配置，未接环境变量）；
- `SkillLoader`（`bootstrap/.../rag/core/skill/SkillLoader.java`）启动时扫描目录，解析 `SKILL.md` frontmatter（name/description/license/compatibility/metadata）+ 可选 `skill.yaml`（type=http/script/command + config + parameters），装入内存 `ConcurrentHashMap` 并写 Redis catalog；
- 管理端只有两个只读接口：`GET /admin/skills`（摘要列表）、`POST /admin/skills/reload`（重扫目录），前端 `SkillListPage.tsx` 仅展示列表 + 刷新按钮；
- **没有版本管理**：改坏了只能靠 git 或手工备份；`contentHash` 只覆盖 SKILL.md + skill.yaml（不含 scripts/），无法可靠检测目录漂移。

### 1.2 目标（来自需求）

1. **SKILL 位置可配置**：不再放项目目录下，改由 `.env` 中的变量指定存放位置；
2. **后管托管**：像提示词管理一样，在后管界面完成 SKILL 的新建、编辑、启停、删除；
3. **版本管理**：与提示词一致的版本化体验，且优化为**可查看版本间差异**：
   - 提示词（单文件）：左侧旧版本、右侧新版本的并排对比，标出差异行；
   - SKILL（多文件多目录）：需要一套多文件版本 diff 方案（本设计重点）。

---

## 2. 总体架构

### 2.1 架构总览

核心决策一句话：**数据库是 SKILL 的唯一事实源（Source of Truth），磁盘目录降级为"运行时工作区"，由后端按当前生效版本自动物化（materialize）出来。**

```
                         ┌─────────────────────────────────────────────┐
                         │                PostgreSQL                   │
                         │  t_skill（主表：当前版本指针/启停/同步水位）    │
                         │  t_skill_version（版本表：全量版本快照元数据） │
                         │  t_skill_file（版本文件表：路径+sha256）      │
                         │  t_skill_blob（内容寻址存储：hash→内容，去重） │
                         └──────────────┬──────────────────────────────┘
                                        │ ① 写路径：管理端操作 → 事务提交新版本
                                        │ ② 读路径：物化当前版本 → 磁盘
                                        ▼
┌──────────────┐   ③ 管理端 API    ┌─────────────────────┐   ④ 物化（原子替换）
│  后管前端      │ ───────────────▶ │  SkillAdminService   │ ────────────────────▶ SKILLS_DIR/
│ SkillListPage │                  │  SkillStorageService │                     ├── web-search/
│ 文件树/Diff    │ ◀─────────────── │  SkillWorkspaceService│                    ├── geo-reverse/
└──────────────┘   JSON (Result)   │  SkillDiffService    │    ⑤ 重扫内存缓存    │   └── scripts/...
                                    └─────────┬───────────┘ ──────────────────▶ ┌──────────────┘
                                              │                                 ▼
                                              │                      ┌─────────────────────┐
                                              └────────────────────▶ │ SkillLoader（基本不动）│
                                                                     │ 内存缓存 + Redis      │
                                                                     └─────────┬───────────┘
                                                                               ▼
                                                         Agent 运行链路（完全不动）：
                                                         ToolRetriever / ToolReaderTool /
                                                         AgentScopeReActExecutor → SkillTool
                                                         → SandboxExecutor（Docker 挂载 scripts/:ro）
```

**为什么必须保留磁盘目录（而不是全内存/全 DB 运行时）**：script/command 类型技能由 `SandboxExecutor` 在 Docker 中执行，`SkillTool.executeScript` 把 `scripts/` 目录以只读卷挂载进容器（`SkillTool.java:173-174`），`ToolReaderTool` 也从磁盘读 references/ 文件——**沙箱执行强依赖真实文件系统**。因此运行时必须有磁盘目录，DB 版本管理与之通过"物化"衔接。

### 2.2 核心设计决策

| # | 决策 | 理由 |
|---|------|------|
| D1 | **DB 为唯一事实源，磁盘为运行时工作区** | 与提示词管理哲学一致（DB 快照优先、classpath 兜底）；DB 挂了工作区还在，技能运行不受影响；多实例扩展时各节点从同一 DB 物化即可 |
| D2 | **版本语义与提示词对齐：每次保存产生新版本，回滚 = 以旧版本内容追加新版本**，不移动指针 | 用户心智一致（提示词已是此语义，回滚提示"当前内容将写入历史，并立即热重载生效"）；版本号单调递增，审计清晰 |
| D3 | **文件内容按 sha256 内容寻址存 `t_skill_blob`，版本只存文件清单+hash** | geo-reverse 含 10MB geojson，若每版本全量重复存储，改 7KB 脚本也要付 16MB 存储；内容寻址后未变更文件跨版本零冗余 |
| D4 | **运行时加载代码（SkillLoader/SkillTool/SandboxExecutor/ToolReaderTool/ToolRetriever）接口零改动** | 运行链路已稳定且经过安全加固（路径越界防护、shell 转义、安全审计），重构风险大收益小；只让"谁来喂内容"从人肉放目录变成系统物化 |
| D5 | **写路径顺序：DB 事务提交 → 物化磁盘 → SkillLoader.scanAndLoad() 热重载**；物化失败不回滚 DB，落 `need_sync` 标记 | DB 是事实源，物化可重试（下次启动/手动同步兜底）；反向顺序会产生"磁盘比 DB 新"的更难处理的漂移 |
| D6 | **存量 `skills/` 目录一次性自动收编**（DB 为空且旧目录存在时播种），此后目录改动视为"漂移"，以 DB 为准修复 | 平滑迁移，老部署升级零手工操作；语义上完全对应提示词的 `seedFromClasspath()` |
| D7 | **在线编辑仅支持文本文件；二进制文件只能经 ZIP 包上传** | 职责清晰：文本走 diff/编辑器，二进制走包管理；避免 Base64 大 payload 与前端大文件编辑的性能坑 |

### 2.3 与提示词管理（t_prompt_config 体系）的对齐关系

| 概念 | 提示词 | SKILL（本设计） |
|------|--------|----------------|
| 事实源 | DB（`t_prompt_config`） | DB（`t_skill` 系四表） |
| 出厂默认/兜底 | classpath `resources/prompt/*.st` | 工作区磁盘（上次物化结果）+ 存量目录收编 |
| 当前生效内容 | 主表 `content` 列 | `t_skill.current_version` 指向的 `t_skill_version`（含文件清单） |
| 历史存储 | `t_prompt_config_history`（变更前快照） | `t_skill_version` 全量保留所有版本（含当前） |
| 更新 | 旧内容写历史 → 改 content → version+1 → reload | 校验 → 入库新版本 → current_version+1 → 物化 → scanAndLoad |
| 回滚 | 取历史内容 → 追加新版本 → reload | 取旧版本文件集 → 追加新版本 → 物化 → scanAndLoad |
| 热重载 | `PromptConfigService.reload()` volatile 快照 | `SkillLoader.scanAndLoad()`（已有，同步调用） |
| 定时兜底 | 60s 轮询 reload | 不需要（无内存快照，每次物化即时生效）；保留启动时 reconcile |
| Diff | 前端 jsdiff 双栏（本设计新增） | 树级 + 文件级两级 diff（本设计新增） |

---

## 3. 详细设计

### 3.1 存储位置与配置（.env）

**环境变量**（`.env-example` 新增段落）：

```bash
# ----------------------
# SKILL 技能目录
# ----------------------
# SKILL 的运行时工作区（DB 中版本内容的物化目标，script/command 类型技能的沙箱挂载源）。
# 默认 <RAGSTUDIO_DATA_DIR>/skills，无需显式配置（2026-08-30 更新：统一收敛到数据目录）
# SKILLS_DIR=
# 单文件上限（默认 20MB）、单版本总大小上限（默认 64MB）、单版本文件数上限（默认 200）
SKILL_MAX_FILE_SIZE=20MB
SKILL_MAX_TOTAL_SIZE=64MB
# 版本保留上限（0 = 不限制；超限时从最旧的非当前版本开始清理）
SKILL_MAX_VERSIONS=0
```

**application.yaml 映射**（沿用现有 `${VAR:default}` 模式）：

```yaml
rag:
  skills:
    dir: ${SKILLS_DIR:${ragstudio.data-dir}/skills}  # 原: skills（写死在项目根目录）
    max-file-size: ${SKILL_MAX_FILE_SIZE:20MB}
    max-total-size: ${SKILL_MAX_TOTAL_SIZE:64MB}
    max-versions: ${SKILL_MAX_VERSIONS:0}
    # allowed-commands / sandbox.* 原样保留
```

技术细节：

- `SkillLoader.resolveSkillsDir` 现有的"从 user.dir 父目录回退查找"逻辑保留（适配 `mvn spring-boot:run` 在 bootstrap/ 子模块运行），`.env` 传绝对路径时该回退自然不触发；
- `data/` 加入 `.gitignore`；
- 存量部署迁移：升级后 DB 为空且旧 `skills/` 目录存在 → 自动收编（见 3.3.5），收编完成后旧目录不再参与运行，可手工归档删除。

### 3.2 数据库设计

四张表：主表 + 版本表 + 版本文件表 + 内容寻址 blob 表。

```sql
-- ============================================================
-- SKILL 管理（版本化、DB 事实源）—— schema_all.sql 增量部分
-- ============================================================

-- 主表：一个 SKILL 一行，"当前行 = 当前生效版本"
CREATE TABLE IF NOT EXISTS t_skill (
    id              BIGSERIAL     PRIMARY KEY,
    name            VARCHAR(64)   NOT NULL UNIQUE,      -- 唯一标识 = SKILL.md frontmatter name = 目录名（^[a-z0-9]+(-[a-z0-9]+)*$）
    description     VARCHAR(1024),                      -- 冗余自 frontmatter（列表/工具卡片展示，免去重复解析）
    skill_type      VARCHAR(16),                        -- http / script / command / NULL(纯知识型)，冗余自 skill.yaml
    current_version INT           NOT NULL DEFAULT 1,   -- 当前生效版本号（指向 t_skill_version.version）
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,-- 停用 = 从工作区移除，Agent 不可见
    change_log      VARCHAR(512),                       -- 当前版本的变更说明
    synced_version  INT,                                -- 最近一次成功物化的版本号（与 current_version 不等 = 待同步/漂移）
    updated_by      VARCHAR(64),
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 版本表：每次保存/回滚/导入产生一行，全量保留（含当前版本）
CREATE TABLE IF NOT EXISTS t_skill_version (
    id           BIGSERIAL   PRIMARY KEY,
    skill_id     BIGINT      NOT NULL,
    version      INT         NOT NULL,                  -- 从 1 开始单调递增
    change_log   VARCHAR(512),                          -- 版本说明（回滚自动填"回滚自 vN"）
    file_count   INT         NOT NULL DEFAULT 0,
    total_size   BIGINT      NOT NULL DEFAULT 0,
    manifest     TEXT,                                  -- 解析后的元数据快照 JSON（实施调整：TEXT 代替 JSONB，免 TypeHandler；写入后只读不参与 SQL 谓词）
    tree_hash    VARCHAR(64),                           -- 全目录树 SHA-256（物化完整性校验、漂移检测）
    created_by   VARCHAR(64),
    create_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (skill_id, version)
);
CREATE INDEX IF NOT EXISTS idx_skill_version_skill ON t_skill_version (skill_id, version DESC);

-- 版本文件表：版本的文件清单（内容在 blob 表）
CREATE TABLE IF NOT EXISTS t_skill_file (
    id         BIGSERIAL    PRIMARY KEY,
    version_id BIGINT       NOT NULL,
    skill_id   BIGINT       NOT NULL,                   -- 冗余列，便于按 skill 级联清理与 blob GC
    file_path  VARCHAR(512) NOT NULL,                   -- 相对路径，POSIX '/' 分隔，如 scripts/geo_reverse.py
    binary     BOOLEAN      NOT NULL DEFAULT FALSE,     -- 文本/二进制分类
    size       BIGINT       NOT NULL,
    blob_hash  VARCHAR(64)  NOT NULL,                   -- → t_skill_blob.sha256
    UNIQUE (version_id, file_path)
);
CREATE INDEX IF NOT EXISTS idx_skill_file_version ON t_skill_file (version_id);

-- 内容寻址 blob 表：跨版本去重的文件内容
CREATE TABLE IF NOT EXISTS t_skill_blob (
    sha256      VARCHAR(64) PRIMARY KEY,
    size        BIGINT      NOT NULL,
    binary      BOOLEAN     NOT NULL,
    content     BYTEA       NOT NULL,                   -- 统一按字节存，文本即 UTF-8 编码
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**关键设计点：**

1. **为什么 SKILL 用"全量版本表"而提示词用"主表存当前 + 历史存变更前"**：提示词只有一段 TEXT，主表存当前值最直观；SKILL 一个版本是整棵文件树，若在主表再存一份"当前文件"会引入两种存储形态。统一为"版本表含当前版本、主表只持指针"，diff/回滚/列表逻辑全部单一形态，代码更简单。
2. **`manifest`（JSON 字符串）**：物化解析产物（name/description/license/compatibility/metadata/type/config/parameters/scriptFiles/referenceFiles）。详情页、树级 diff 摘要、工具卡片均直接读它，避免每次反解 SKILL.md/skill.yaml；同时它也是"该版本能被成功解析"的凭证——解析失败的内容不允许成为版本。
3. **blob 表与事务性**：版本创建 = 同一事务内 insert blob（`ON CONFLICT (sha256) DO NOTHING`）+ insert version + insert files + update 主表指针。内容寻址天然幂等，重复导入同内容不膨胀。
4. **blob GC**：删除版本（超限清理/删除技能）后，执行反连接清理无引用 blob：`DELETE FROM t_skill_blob b WHERE NOT EXISTS (SELECT 1 FROM t_skill_file f WHERE f.blob_hash = b.sha256)`。随删除操作同事务执行（技能规模小，开销可忽略）。
5. **版本清理策略**：`max-versions > 0` 时，写路径提交后清理最旧版本（永不清理 `current_version` 及其引用），保留下限 1。
6. **DB 变更按仓库约定落地**：全量脚本追加进 `resources/database/schema_all.sql`（`IF NOT EXISTS` 可重放）；存量库升级另出 `resources/database/upgrade/2026xxxx_skill_management.sql`。
7. **DB 版本号 vs frontmatter 版本号**：`metadata.version` 是作者声明字段，与 DB `current_version` 是两回事。UI 中 DB 版本为主（`v3`），frontmatter 版本展示为"声明版本：1.2.0"仅作参考；不做自动同步，差异通过 SKILL.md diff 自然可见。

### 3.3 后端模块设计

新增包 `com.byteq.ai.ragstudio.rag.skillstore`（管理面）。
> 实施调整：原设计放 `rag/core/skill/store`，但 ArchUnit 规则 R3 禁止 `..dao.mapper..` 依赖 `..core..`（mapper 的实体泛型参数会命中），故参照 `rag.prompt` 先例整体迁移到 `rag.skillstore`（`rag` 的直接子包，脱离引擎层约束）：

```
rag/skillstore/
├── SkillStorageService.java      # 版本管理核心（纯 DB，无 IO 副作用）
├── SkillWorkspaceService.java    # 物化（DB → 磁盘），原子替换、漂移修复
├── SkillPackageService.java      # ZIP 包/目录读取 + 限额（导出下载为 P4 可选）
├── SkillDiffService.java         # 树级 diff（纯内存集合运算，支持 dry-run 复用）
├── SkillValidator.java           # 入库校验器（复用 SkillMetadata.parse + SecurityAuditor 静态审计）
├── SkillAdminService.java        # 编排：校验 → 存储 → 物化 → 热重载
├── SkillStoreInitializer.java    # ApplicationRunner：启动对账 + 存量收编
├── SyncState / SkillListItem / SkillDetail / SkillVersionInfo / SkillCommitInput / SkillBlankInput / SkillFileContent
└── dao/
    ├── entity/ SkillDO / SkillVersionDO / SkillFileDO / SkillBlobDO
    └── mapper/ 四个 MyBatis-Plus BaseMapper 空接口

admin/controller/SkillController.java   # 扩展（见 3.4）
```

#### 3.3.1 SkillStorageService（版本管理核心）

职责：版本 CRUD 的**事务性 DB 操作**，不含磁盘/Redis 副作用。关键方法与语义：

```java
// 创建技能（内容来源：ZIP 包 / 在线提交 / 目录收编 / 空白模板），统一入口
@Transactional SkillDO create(String name, List<FileInput> files, String changeLog, String operator);
// 提交新版本：files 为全量最终文件集（在线编辑的 upserts+deletions 已在内存中折算为全量集）
@Transactional SkillDO commit(Long skillId, List<FileInput> files, String changeLog, String operator);
// 回滚：读 targetVersion 的文件集 → commit 一份新版本（changeLog = "回滚自 vN"）
@Transactional SkillDO rollback(Long skillId, int targetVersion, String operator);
// 启停 / 删除 / 版本超限清理（含 blob GC）
```

`FileInput = {path, binary, bytes}`，进入存储层前已过校验管线（3.3.4）。存储层内做的事：

1. 用 `SkillValidator` 做最终校验（内存态校验，见 3.3.4）→ ERROR 拒绝入库；
2. 逐文件 sha256 → `ON CONFLICT DO NOTHING` 写 blob；**无变更检测**：全量文件集 hash 与当前版本完全一致时拒绝提交（"无变更"）；
3. insert `t_skill_version`（version = current_version + 1）+ 批量 insert `t_skill_file`；
4. update 主表：`current_version`、`description`、`skill_type`、`change_log`、`updated_by`（`synced_version` 不动，留给物化成功后更新）；
5. 版本超限清理（可选配置）。

#### 3.3.2 SkillWorkspaceService（物化）

职责：把"某技能某版本"写成磁盘目录，**这是 DB 与运行时之间唯一的桥**。

```java
Path materialize(SkillDO skill, SkillVersionDO version);  // 返回目标目录
void remove(String skillName);                            // 停用/删除时移除目录
void reconcile();                                         // 启动时全量对账（见 3.3.5）
```

**安全写入流程（原子替换）**——目标目录可能正被 Agent 沙箱挂载读取，绝不允许"边删边写"的中间态暴露给运行时：

```
target   = SKILLS_DIR/<name>
staging  = SKILLS_DIR/.staging/<name>.<version>.<ts>
trash    = SKILLS_DIR/.trash/<name>.<ts>

1. 逐文件从 t_skill_blob 流式写出至 staging（保持相对路径，自动建父目录）
2. 完整性校验：staging 全树 SHA-256 == version.tree_hash，文件数一致；不符 → 清理 staging，抛物化失败
3. 若 target 存在 → Files.move(target, trash)
4. Files.move(staging, target, ATOMIC_MOVE)   # 同一文件系统内原子生效
5. 递归删除 trash
6. UPDATE t_skill SET synced_version = #{version}   # 成功水位
```

技术细节与失败处理：

- **步骤 3/4 之间的窗口**：target 已移走、staging 未就位，此时 `scanAndLoad` 会视为"技能已删除"而卸载。规避方式：物化由 `SkillAdminService` 编排——先物化后统一触发 `scanAndLoad()`，窗口仅存在于物化内部，而物化期间不会有并发 scanAndLoad（编排方法加 per-skill 锁 / 全局 synchronized，与 `SkillLoader.scanAndLoad` 的 synchronized 一致量级）。
- **任何一步失败**：尽力恢复（trash 还原回 target），清理 staging，主表落 `synced_version` 不更新 → 列表页显示"待同步"徽标，可手动重试；下次启动 reconcile 也会修复。**DB 不回滚**（D5）。
- **目录约定**：`.staging/`、`.trash/` 是工作区保留目录，`SkillLoader` 扫描时须跳过 `.` 开头的子目录（现实现会把它们当失败技能记录，需加一行过滤：`filter(p -> !p.getFileName().toString().startsWith("."))`）。
- **权限**：写出文件用默认权限即可——沙箱以解释器显式执行脚本（`interpreter /scripts/xxx.py`），不依赖可执行位。
- **大文件**：blob 流式读写（`pgblob` → `Files.copy`），单文件上限 20MB 内无需分块。

#### 3.3.3 编排层 SkillAdminService 与写路径时序

所有管理端操作入口，编排 校验 → 存储 → 物化 → 热重载 → Redis catalog 刷新：

```
在线编辑提交 / ZIP 上传 / 回滚 / 收编：
  1. SkillPackageService / SkillValidator   # 解析 + 校验（可含 dryRun 预览分支）
  2. SkillStorageService.commit             # DB 事务
  3. SkillWorkspaceService.materialize      # 磁盘原子替换
  4. skillLoader.scanAndLoad()              # 热重载（复用现有方法，等价提示词 reload()）
  5. 写 Redis catalog                       # SkillLoader.scanAndLoad 内已含，无需额外处理
```

第 4 步直接复用现有 `SkillLoader.scanAndLoad()`（它本身会重建内存缓存、清理陈旧条目、重写 Redis），**SkillLoader 对外接口零改动**（D4）。

#### 3.3.4 校验管线 SkillValidator 与 SkillPackageService

**SkillValidator**——从 `SkillLoader.loadSkill()`（`SkillLoader.java:264-319`）与 `loadExecutionConfig()` 提炼为独立校验器，供"目录加载"与"入库前校验"两端共用，避免两处规则漂移。入库场景在目录校验之上增加：

| 校验项 | 规则 | 级别 |
|--------|------|------|
| ZIP 条目路径 | 拒绝绝对路径、`..` 越界、符号链接条目（zip-slip）；路径规范化后必须落在技能根内 | ERROR |
| 大小限制 | 单文件 ≤ max-file-size；总大小 ≤ max-total-size；文件数 ≤ 200 | ERROR |
| SKILL.md 必须存在且可解析 | 复用 `SkillMetadata.parse()`（name 正则 `^[a-z0-9]+(-[a-z0-9]+)*$`、长度限制） | ERROR |
| skill.yaml 可解析 | 复用现有 SnakeYAML 解析与 type/config/parameters 结构校验；type 必须是 http/script/command | ERROR |
| name 与包目录名/已有 name 一致 | 更新场景：包内 frontmatter name 必须等于 URL 中的 `{name}` | ERROR |
| 脚本静态审计 | 对 scripts/ 下文本逐文件跑 `SecurityAuditor` 风险模式扫描（rm -rf、curl\|sh、eval 等），命中记 WARN 并在 UI 提示；运行期 Docker 沙箱 + 命令审计防线不变 | WARN |
| 文本/二进制分类 | 扩展名白名单（md/yaml/yml/json/txt/py/sh/js/ts/rb/php/xml/html/css/csv/…）判文本，另扫描首 8KB 是否含 NUL 字节兜底；`binary=true` 的文件不参与内容 diff | AUTO |

**SkillPackageService**：ZIP ↔ 文件集互转。导入：`ZipInputStream` 逐条目读取（防 zip bomb：解压总量按 max-total-size 硬顶）、根目录归一（允许整个包包一层同名根目录，自动剥离）。导出：按版本文件清单从 blob 拼包（用于下载备份，P1 可选）。

#### 3.3.5 启动流程与存量收编（迁移）

启动顺序（通过依赖注入保证：`SkillLoader` 构造注入 `SkillWorkspaceService`，其 `@PostConstruct` 先行）：

```
1. SkillWorkspaceService.reconcile()
   a. DB 有技能：逐个校验工作区目录（存在性 + tree_hash 比对当前版本）
      ├─ 一致 → 跳过
      ├─ 不一致/缺失 → 重新物化当前启用版本（DB 为准，修复漂移）
      └─ 停用技能目录残留 → 移除
   b. DB 无技能 且 旧项目目录 skills/ 存在 → 自动收编（一次性播种，语义同提示词 seedFromClasspath）：
      逐目录 → SkillValidator 校验（ERROR 级问题记录日志并跳过，不阻断启动）→ create()（v1，
      created_by=system，change_log="从目录自动导入"）→ materialize 到 SKILLS_DIR
   c. 收编完成后旧目录不再参与运行（不影响运行，可手工归档）
2. SkillLoader.scanAndLoad()   # 现有 @PostConstruct 逻辑不变
```

漂移语义：运行期间有人手工改了工作区目录 → `synced_version == current_version` 但磁盘 hash 与 `tree_hash` 不符 → 列表页"漂移"徽标 + `POST /admin/skills/{name}/sync` 一键以 DB 覆盖修复。（不为"手工改动"提供反向入库，保持 DB 单一事实源；确需保留的手工改动应走收编/上传流程。）

#### 3.3.6 多实例说明

当前为单实例部署（提示词 60s 轮询的设计前提相同）。DB 是共享事实源，未来扩多实例时只需：写路径成功后向 Redis topic `RAGStudio:skill:sync` 发事件，各节点订阅后对本实例执行 `reconcile() + scanAndLoad()`。本期仅预留常量与注释，不实现。

### 3.4 API 设计

统一响应 `Result<T>`（`framework/.../convention/Result.java`），类级 `@SaCheckRole("admin")` + `@RequestMapping("/admin/skills")` 沿用现状；操作人一律 `UserContext.getUsername()`。

| Method | Path | 说明 | 备注 |
|--------|------|------|------|
| GET | `/admin/skills` | 技能列表（合并 DB 与运行时状态） | 改造现有接口，响应结构升级（见下） |
| POST | `/admin/skills/blank` | 空白模板新建（服务端生成 SKILL.md 骨架） | body: `{name, description}` |
| POST | `/admin/skills` | ZIP 包新建技能 → v1 | multipart: `file` |
| GET | `/admin/skills/{name}` | 详情：manifest + 当前版本文件树 + 运行时状态 | |
| DELETE | `/admin/skills/{name}` | 删除（版本、文件、blob GC、工作区目录、Redis） | |
| POST | `/admin/skills/{name}/enable` | 启用：物化当前版本 + scanAndLoad | |
| POST | `/admin/skills/{name}/disable` | 停用：移除工作区目录 + scanAndLoad | |
| POST | `/admin/skills/{name}/versions` | ZIP 上传新版本 → vN+1 | `dryRun=true` 时只校验+返回树级 diff 预览，不入库 |
| POST | `/admin/skills/{name}/commit` | 在线编辑提交 → vN+1 | body: `{changeLog, upserts:[{path, content}], deletions:[path]}`，仅文本文件 |
| GET | `/admin/skills/{name}/versions` | 版本列表（含 change_log/created_by/time/当前标记） | |
| GET | `/admin/skills/{name}/versions/{v}/tree` | 指定版本文件树 | |
| GET | `/admin/skills/{name}/versions/{v}/file?path=` | 文件内容（仅文本且 ≤ 1MB，供编辑/diff 取内容） | 超限/二进制返回 422 + 元信息 |
| GET | `/admin/skills/{name}/diff?from={a}&to={b}` | 树级 diff（集合运算结果，见 3.5.2） | |
| POST | `/admin/skills/{name}/rollback/{v}` | 回滚到 v → 产生新版本 | 与提示词语义一致 |
| POST | `/admin/skills/{name}/import` | 收编工作区未入库目录（按 `{name}` 对应目录建档） | 迁移期/漂移救急 |
| POST | `/admin/skills/{name}/sync` | 以 DB 当前版本重新物化（修复漂移/待同步） | |
| POST | `/admin/skills/reload` | 保留：重扫工作区（兼容现状，管理端"刷新"按钮） | |

**列表 VO（升级后）**：

```json
{
  "name": "geo-reverse",
  "description": "经纬度逆地理编码",
  "skillType": "script",
  "currentVersion": 3,
  "declaredVersion": "1.2.0",
  "enabled": true,
  "changeLog": "新增区县数据",
  "updatedBy": "byteq",
  "updateTime": "2026-08-30 12:00:00",
  "syncState": "SYNCED | PENDING_SYNC | DRIFTED",
  "runtime": { "loaded": true, "errors": "", "warnings": "SCRIPT_FILE_MISSING: ..." }
}
```

`runtime` 来自 `SkillLoader.listSkillSummaries()`（现有诊断能力保留）；未入库但工作区存在的目录以 `currentVersion: null, syncState: "UNMANAGED"` 出现，前端给"收编"按钮。

**commit 请求示例**（在线编辑，一次提交多文件 = 一个版本）：

```json
{
  "changeLog": "修正参数说明并更新脚本",
  "upserts": [
    { "path": "SKILL.md", "content": "---\nname: geo-reverse\n...\n---\n..." },
    { "path": "scripts/geo_reverse.py", "content": "#!/usr/bin/env python3\n..." }
  ],
  "deletions": ["references/old-notes.md"]
}
```

### 3.5 版本 Diff 设计（重点）

#### 3.5.1 提示词 Diff（单文件，轻量）

**零后端改动**。数据已齐：当前版本内容在 `GET /admin/prompts/{key}` 的 `content`；历史版本内容在 `GET /admin/prompts/{key}/history` 的每条 `content`（注意：历史表存的是"变更前"快照，当前版本没有历史行——对比"任意两版"时当前版本从详情接口取、历史版本从 history 取，前端拼装即可）。

前端交互：`PromptsPage` 的"变更历史" Dialog 中，每条历史记录增加"**对比**"按钮 → 打开 `DiffView` Dialog，默认 `旧版本=选中历史版本`、`新版本=当前版本`（即选中版本的下一版语义）；Dialog 顶部两个下拉可任意改选两端版本（历史版本 ∪ 当前版本）。所有版本内容已在内存中，切换即时渲染。

#### 3.5.2 SKILL Diff（多文件，两级结构）

**第一级：树级 diff（服务端计算）**。`t_skill_file` 存有每个版本每个文件的 sha256/size/binary，diff 是纯集合运算（`SkillDiffService`）：

```
fromFiles = {path → (sha256, size, binary)}  @ from 版本
toFiles   = {path → (sha256, size, binary)}  @ to 版本
added     = toFiles.keys - fromFiles.keys
deleted   = fromFiles.keys - toFiles.keys
modified  = 交集 && sha256 不同
unchanged = 交集 && sha256 相同
```

响应：

```json
{
  "fromVersion": 3, "toVersion": 5,
  "summary": { "added": 2, "deleted": 1, "modified": 3, "unchanged": 8 },
  "manifestChanges": [ "description 变更", "type: http → script", "parameters 新增 lat" ],
  "files": [
    { "path": "SKILL.md",                "status": "modified", "binary": false, "oldSize": 812,  "newSize": 903 },
    { "path": "scripts/geo_reverse.py",  "status": "modified", "binary": false, "oldSize": 6852, "newSize": 7001 },
    { "path": "scripts/china_district.geojson", "status": "unchanged", "binary": false, "oldSize": 10006687, "newSize": 10006687 },
    { "path": "references/cities.md",    "status": "added",    "binary": false, "newSize": 2044 }
  ]
}
```

`manifestChanges` 由两版 `manifest` JSON 逐字段比对生成（description/type/config/parameters 的人类可读摘要），让用户不打开文件也能知道"这次改了什么语义"。

**第二级：单文件内容 diff（前端计算）**。用户在文件树中点击文本文件：

- `modified` → 拉取两版该文件内容（`GET .../versions/{from}/file?path=` × 2）→ `DiffView` 双栏对比；
- `added` / `deleted` → 单侧内容展示（整块绿色/红色）；
- `binary=true` 或 size > 1MB（如 10MB geojson——它是文本但不适合整页 diff）→ 不取内容，渲染元信息卡片：`china_district.geojson（二进制/大文件）10.0MB → 10.1MB，内容已变更`。

**前端交互线框**（Dialog，宽 90vw）：

```
┌─ 版本对比：geo-reverse  v3 → v5 ──────────────────────────────────┐
│ [v3 ▾] → [v5 ▾]        +2 新增 · −1 删除 · ~3 修改 · 8 未变更      │
│ manifestChanges: description 变更；parameters 新增 lat            │
│ ┌──────────────┐  ┌────────────────────────────────────────────┐ │
│ │ 文件树        │  │  scripts/geo_reverse.py                    │ │
│ │ 📁 scripts/   │  │ ┌────┬─────────────────┬────┬────────────┐ │ │
│ │  ● geo_re…py  │→ │ │ 12 │ lat = float(...)│ 12 │ lat = float│ │ │
│ │  ● district…  │  │ │ 13 │ lng = float(...)│ 13 │ lng = float│ │ │
│ │ 📄 SKILL.md ● │  │ │ 14 │ + 坐标校验      │ 14 │            │ │ │
│ │ ⊖ old-notes…  │  │ │ 15 │                 │ 15 │ + if not…  │ │ │
│ │ ⊕ cities.md   │  │ └────┴─────────────────┴────┴────────────┘ │ │
│ └──────────────┘  └────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────┘
（● modified 红/黄 · ⊕ added 绿 · ⊖ deleted 红；未变更文件默认折叠为"8 个未变更文件"分组）
```

两个版本的选取规则：默认 `from = 上一版本、to = 当前版本`；两个下拉可在全部版本中任选。ZIP 上传的 `dryRun=true` 复用同一 DiffView 做"上传前预览差异"。

#### 3.5.3 共享 DiffView 组件（必须的技术细节）

新增 `frontend/src/components/shared/DiffView.tsx`，提示词与 SKILL 共用；依赖 `diff`（jsdiff）+ `@types/diff`（package.json 新增）。

**并排（side-by-side）对齐算法**——jsdiff 的 `diffLines` 输出的是线性 op 序列（removed/added/unchanged），并排渲染需要把 removed 与 added 水平配对，两侧行号独立计数、长度不等时短侧补空行：

```ts
type Row = {
  left:  { no: number; text: string } | null;   // null = 补空行
  right: { no: number; text: string } | null;
  kind: "same" | "mod" | "del" | "add";
};

function alignRows(ops: DiffOp[]): Row[] {
  const rows: Row[] = [];
  let oldNo = 0, newNo = 0;
  for (let i = 0; i < ops.length; i++) {
    const op = ops[i];
    if (op.kind === "same") {
      for (const line of op.lines) rows.push({ left: {no:++oldNo, text:line}, right:{no:++newNo, text:line}, kind:"same" });
    } else if (op.kind === "removed") {
      const add = ops[i+1]?.kind === "added" ? ops[++i] : null;   // 相邻 removed+added 合并为 replace 块
      const k = op.lines.length, m = add?.lines.length ?? 0;
      for (let j = 0; j < Math.max(k, m); j++) {
        const hasL = j < k, hasR = j < m;
        rows.push({
          left:  hasL ? {no:++oldNo, text:op.lines[j]} : null,
          right: hasR ? {no:++newNo, text:add.lines[j]} : null,
          kind:  hasL && hasR ? "mod" : hasL ? "del" : "add",
        });
      }
    } else { // 孤立 added
      for (const line of op.lines) rows.push({ left:null, right:{no:++newNo, text:line}, kind:"add" });
    }
  }
  return rows;
}
```

- 渲染：CSS Grid 四列 `[oldNo 48px | oldText 1fr | newNo 48px | newText 1fr]`；配色 `del=红底、add=绿底、mod=黄底、same=透明`，补空行灰斜纹背景；行号右对齐 mono 字体；容器定高滚动（与编辑区 min-h-440px 风格一致）；
- **行折叠**：连续 `same` 行超过 `FOLD_THRESHOLD=8` 时，中间部分折叠为"⋯ 展开 N 行未变更"分隔条（点击展开），变更块首尾各保留 `CONTEXT=3` 行上下文；对齐后的 `Row[]` 上做一遍线性扫描生成折叠段即可；
- **行内高亮（可选增强）**：对 `mod` 行对做 `diffWords`，仅当两侧行长差 < 50% 时启用，防止退化成整行飘红；
- 空内容/全等快路径：`oldText === newText` 直接渲染"无差异"提示；文本按 `\n` split 前先统一 `\r\n → \n`；
- 大文本保护：单侧超过 5000 行时关闭行内高亮与折叠动画，保证渲染性能。

### 3.6 前端设计

#### 3.6.1 页面结构

重构 `SkillListPage.tsx`（现仅 124 行列表页），布局参照 `PromptsPage` 的左右双栏：

```
┌───────────────────────────────────────────────────────────────┐
│ SKILL 管理          [搜索] [从目录收编▾] [新建 ▾] [刷新]        │
│ 新建 ▾ = 上传 ZIP 包 / 空白模板                                 │
├──────────────┬────────────────────────────────────────────────┤
│ 技能列表(380) │ 详情区（Tabs）                                  │
│ ┌──────────┐ │ ┌────────────────────────────────────────────┐ │
│ │geo-reverse│ │ │ [文件] [版本历史]                           │ │
│ │script v3 │ │ ├────────────────────────────────────────────┤ │
│ │●启用 ⚠待同步│ │ │ 文件 Tab：                                  │ │
│ ├──────────┤ │ │  左：文件树（当前版本，目录树+状态图标+待存标记）│ │
│ │web-search│ │ │  右：文本编辑器（mono Textarea，选中文件）      │ │
│ │http  v1  │ │ │      / 二进制文件元信息卡 + 上传替换按钮       │ │
│ ├──────────┤ │ │  底部：待保存变更条（+2 文件修改 · 1 删除       │ │
│ │system-info│ │ │        [放弃] [保存为新版本（填变更说明）]）    │ │
│ │doc   v2  │ │ │ 版本历史 Tab：                               │ │
│ └──────────┘ │ │  v5 ● 当前  byteq  08-30 14:20  [回滚] [对比] │ │
│              │ │  v4        byteq  08-29 10:11  [回滚] [对比] │ │
│              │ │  ...                                        │ │
│              │ └────────────────────────────────────────────┘ │
└──────────────┴────────────────────────────────────────────────┘
```

要点：

- **文件 Tab 的"草稿"模型**：编辑/删除文件先记入前端本地待存变更集（`Map<path, content>` + `Set<deleted>`，草稿标记在文件树上显示），"保存为新版本"一次性 `POST /commit` 提交 → 单一新版本（避免逐文件保存产生版本碎片）。草稿仅存内存，离开页面提示未保存。二进制替换文件走"上传替换"（转 ZIP 语义受限，故 v1 二进制变更引导走整包上传，编辑器内仅支持删除二进制文件）；
- **版本历史 Tab**：每行 `[v{n}]` Badge + 变更说明 + 修改人 + 时间 + 当前版本标记 + 操作（回滚→确认 AlertDialog，文案"将以 v{n} 的内容生成新版本 v{m+1} 并立即生效"；对比→打开 3.5.2 Diff Dialog）；
- 列表项徽标：`待同步`（PENDING_SYNC）、`漂移`（DRIFTED）、`未入库`（UNMANAGED → 显示"收编"按钮）、加载失败红点（复用现有 errors 展示）；
- 新建 Dialog：上传 ZIP（校验错误逐条展示）或空白模板（输入 name/description，服务端生成含 frontmatter 骨架的 SKILL.md）。

#### 3.6.2 service 与基础设施

- `skillService.ts` 扩展：`SkillListItem / SkillDetail / SkillVersionItem / SkillTreeNode / SkillTreeDiff` 类型 + 上述 API 封装（`api.get/post/delete`，沿用 `api.ts` 自动解包 `Result`）；
- `package.json` 新增 `diff` 与 `@types/diff`；
- 路由/菜单无需新增页面（`/admin/skills` 已注册于 `router.tsx` 与 `AdminLayout.navItems`），Diff 用 Dialog 而非独立路由；
- 提示词页接入 DiffView（3.5.1）：`PromptsPage.tsx` 变更历史 Dialog 增加"对比"按钮 + 复用共享组件。

### 3.7 安全设计

1. **入库侧（新增攻击面，重点）**：zip-slip 路径校验（3.3.4）、解压总量硬顶、符号链接拒绝、大小/数量限制、文本编码强制 UTF-8；脚本静态审计（WARN 提示，不阻断——运行期另有防线）；
2. **运行侧（不变，既有防线继续生效）**：`SkillLoader.resolveInside` 路径越界防护、`SkillTool.shellEscape` 单引号转义、`SecurityAuditor` 命令审计、Docker 沙箱只读挂载 + 资源限额 + command 白名单；
3. **越权**：全部接口 `@SaCheckRole("admin")`；文件读取接口以 `versions/{v}/file?path=` 形式提供，path 校验复用 `resolveInside` 同等规则（规范化后必须匹配 `t_skill_file` 中登记的路径——**只允许读 DB 登记过的路径**，天然免疫路径穿越）。

### 3.8 异常处理与降级

| 场景 | 行为 |
|------|------|
| 管理端写操作时 DB 异常 | 事务回滚，接口报错，工作区与运行时不受影响 |
| 物化失败（磁盘满/权限） | DB 已提交（事实源成立），`synced_version` 不更新 → "待同步"徽标；手动 sync / 重启 reconcile 修复；期间运行时继续用旧目录内容（旧目录在步骤 3 才会被移走，失败会还原） |
| 启动时 DB 不可用 | reconcile 跳过，SkillLoader 直接扫现有工作区目录 → 技能照常运行（与提示词 DB 异常回退 classpath 的降级思想一致）；DB 恢复后下次操作自动对账 |
| 管理端读操作时 DB 不可用 | 列表接口降级返回纯 runtime 数据（`currentVersion: null, syncState: "RUNTIME_ONLY"`），仅查询不可管理 |
| Redis 异常 | `SkillLoader` 现有 try/catch 已兜底（catalog 写失败仅告警），不变 |
| 工作区被手工改动 | 漂移检测 + 一键 sync（3.3.5）；不做反向入库 |
| 上传包校验失败 | 逐条返回 ERROR/WARN 列表（code + message，复用 `SkillIssue` 结构），不入库不物化 |

### 3.9 实施计划

| 阶段 | 内容 | 交付物 |
|------|------|--------|
| P1 存储地基 | .env/配置、四表 SQL（schema_all + upgrade）、DO/Mapper、SkillStorageService、SkillWorkspaceService、SkillLoader 隐藏目录过滤、启动 reconcile + 存量自动收编、扩展 SkillController（列表/详情/版本列表/回滚/启停/删除/sync）、前端列表页双栏改造（列表+详情+版本历史+回滚） | 技能可入库、可回滚、可启停，运行链路零改动生效 |
| P2 编辑与包管理 | SkillPackageService（ZIP 导入）、blank 模板、在线编辑 commit、文件树 UI + 草稿变更集、收编/新建 Dialog | 后管完整 CRUD 闭环 |
| P3 版本 Diff | DiffView 组件（对齐算法+折叠）、提示词历史接入对比、SKILL 树级 diff 接口与 Diff Dialog、ZIP dryRun 预览 | 提示词与 SKILL 均可看版本差异 |
| P4 收尾 | 漂移徽标与 sync、版本超限清理 + blob GC、ZIP 导出下载、多实例预留注释、单元测试（validator/zip-slip/storage 版本语义/diff 集合运算）、`./mvnw -q compile` + `npx tsc --noEmit` 验证 | 全量交付 |

风险与缓解：ZIP 校验逻辑是最大新增攻击面 → P2 单测覆盖 zip-slip/超限/畸形包用例；10MB 级 blob 的事务写放大 → 内容寻址去重 + 单文件 20MB 上限；前端草稿丢失 → 离开页面拦截提示（后端草稿表明确不做，成本收益不匹配）。

---

## 4. 实施状态与部署说明（2026-08-30）

P1~P3 已实现并验证（`./mvnw -q compile`、`spotless:check`、`ArchitectureTest` 6/6、`npx tsc --noEmit`、prettier 全部通过）：

- **后端**：`rag/skillstore` 全套（存储/物化/校验/包读取/树级 diff/编排/启动对账）+ `SkillController` 16 个端点 + `SkillLoader` 隐藏目录过滤；表结构见 `schema_all.sql` 尾部与 `upgrade/20260830_skill_management.sql`。
- **前端**：`skillService.ts`（17 个 API 封装）、`SkillListPage.tsx`（双栏：列表 + 文件/版本历史 Tab、草稿变更集、ZIP 上传与 dry-run 预览、回滚/启停/删除/收编/同步）、`SkillDiffDialog.tsx`（树级对比 + 文件级并排 diff）、共享 `DiffView.tsx`；提示词「变更历史」Dialog 已接入「对比」。
- **版本清理 / blob GC / 多实例事件**：已实现超限清理与 GC（`max-versions>0` 时触发）；Redis sync 事件仍为多实例预留项。

部署注意：

1. 存量库执行 `resources/database/upgrade/20260830_skill_management.sql`（全新部署仅 `schema_all.sql`）；
2. `.env` 配置 `SKILLS_DIR`（建议项目外或数据盘；容器部署挂载为持久卷，与 DB 数据同级对待）；
3. 升级首次启动：DB 无技能记录且检测到旧项目 `skills/` 目录时自动收编为 v1 并物化到新工作区，收编结果在列表中以"从目录自动导入"版本说明呈现，确认无误后可归档旧目录；
4. 工作区内的 `.staging/`、`.trash/` 为系统保留目录（物化临时区/回收站），不要手工改动。

> **2026-08-30 更新**：SKILL 工作区默认位置已随"运行时数据目录"统一收敛到 `RAGSTUDIO_DATA_DIR`（默认与项目同级/JAR 旁的 `RAGStudioData/skills`），旧 `data/skills` 由启动迁移器自动搬移；见 `docs/deployment.md` 的"运行时数据目录"章节与 `infra/data/DataDirs`。
