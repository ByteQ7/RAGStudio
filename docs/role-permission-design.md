# RAGStudio 角色与权限设计规范

> 状态：已落地（2026-09-19）
> 适用范围：`bootstrap` 后端、`frontend` 前端、`resources/database/schema_all.sql`
> 相关实现：`RoleConstant` / `UserRole` / `SaTokenStpInterfaceImpl` / `SaTokenConfig` / `utils/role.ts`

## 1. 设计目标与原则

1. **两类角色，简单可控**：系统只保留管理员（`admin`）与普通用户（`user`）两类角色，不引入中间态。
2. **后端强制，前端仅体验**：所有权限判定以服务端为准；前端角色控制只用于界面显隐与路由跳转，不能作为安全边界。
3. **默认拒绝，最小权限**：除白名单路径外一律要求登录；管理接口显式声明管理员角色；未知角色一律按 `user` 处理。
4. **角色字符串集中管理**：后端统一使用 `RoleConstant.ADMIN` / `RoleConstant.USER`，禁止业务代码硬编码；前端统一使用 `UserRole` 类型与 `normalizeUserRole`。
5. **私有数据按用户隔离**：会话、会话分组、消息反馈等个人数据按 `userId` 过滤；知识库按创建人（`createdBy`）判定归属。

## 2. 角色定义

| 角色编码 | 名称 | 说明 | 账号来源 |
|---|---|---|---|
| `admin` | 管理员 | 拥有全部对话能力与系统管理后台权限 | 默认种子账号 `admin`（受保护）；可由管理员创建 |
| `user` | 普通用户 | 仅可使用对话、会话、个人资料等能力 | 仅可由管理员在后台创建 |

- 角色枚举：`bootstrap/.../user/enums/UserRole.java`（`ADMIN` / `USER`）
- 角色常量：`bootstrap/.../user/constant/RoleConstant.java`（`ADMIN = "admin"` / `USER = "user"`，供注解使用）
- 数据库：`t_user.role VARCHAR(32) NOT NULL`，服务层 `UserServiceImpl#normalizeRole` 做白名单校验（空值默认 `user`，非法值报“角色类型不合法”）
- 前端类型：`frontend/src/types/index.ts` 中 `UserRole = "admin" | "user"`

系统**不引入权限点（permission）与 RBAC 关联表**：`SaTokenStpInterfaceImpl#getPermissionList` 固定返回空列表，全库不使用 `@SaCheckPermission`。两类角色的区分完全由角色校验承担。

## 3. 能力边界

### 3.1 登录即可（`admin` + `user`）

| 能力 | 接口 | 约束 |
|---|---|---|
| 认证 | `POST /auth/login`、`POST /auth/logout` | `/auth/**` 免登录 |
| 个人信息 | `GET /user/me`、`PUT /user/password`、`POST /user/avatar` | 仅操作当前用户 |
| 对话 | `POST /rag/v3/chat`、`POST /rag/v3/upload-image`、`POST /rag/v3/stop` | 按 `userId` 限流/停止 |
| 图片预签名 | `GET /presign` | 仅登录；仅允许白名单 bucket；见 3.3 |
| 会话 | `/conversations/**` | Service 层按 `userId` 隔离 |
| 会话分组 | `/conversation-groups/**` | 按 `userId` 隔离 |
| 消息反馈 | `/message-feedback` | 消费端按 `userId` 校验归属 |
| 欢迎页示例问题 | `GET /rag/sample-questions` | 随机返回 |
| 知识库只读 | `GET /knowledge-base`（分页）、`GET /knowledge-base/{kb-id}`、`GET /knowledge-base/chunk-strategies` | 供对话页选择知识库 |
| 文档预览 | `POST /knowledge-base/docs/{docId}/preview`、`GET .../preview/file` | admin 可预览全部；user 仅可预览自己创建的知识库下的文档 |

### 3.2 仅管理员（`@SaCheckRole(RoleConstant.ADMIN)`）

| 模块 | 接口前缀 / 控制器 |
|---|---|
| 用户管理 | `/users/**`（`UserController`，分页/创建/更新/删除） |
| 知识库写操作 | `POST/PUT/DELETE /knowledge-base`（`KnowledgeBaseController`） |
| 文档管理 | `/knowledge-base/{kb-id}/docs/**`（`KnowledgeDocumentController`，含上传/删除/启用/分块触发/检索） |
| 分块管理 | `/knowledge-base/docs/{docId}/chunks/**`（`KnowledgeChunkController`） |
| 摄入流水线/任务 | `/ingestion/pipelines/**`、`/ingestion/tasks/**` |
| Graph RAG 管理 | `/admin/graph/**` |
| Skill 管理 | `/admin/skills/**` |
| Prompt 管理 | `/admin/prompts/**` |
| 模型配置 | `/ai-model-config/**`、`/ai-model-config/defaults/**` |
| MCP Server | `/mcp-server/**` |
| RAG 设置 | `/rag/settings/**` |
| 查询词映射 | `/mappings/**` |
| 示例问题管理 | `GET /sample-questions`、`GET /sample-questions/{id}`、`POST/PUT/DELETE /sample-questions/**` |
| MinerU 配置 | `/rag/mineru/config`、`/rag/mineru/config/ping` |
| 告警配置 | `/rag/alert/config`、`/rag/alert/test` |
| Dashboard | `/admin/dashboard/**` |
| 链路追踪 | `/rag/traces/**`（查询、删除、标记失败） |

### 3.3 特殊边界规则

1. **默认管理员保护**：用户名 `admin` 的账号禁止被修改或删除（`UserServiceImpl#ensureNotDefaultAdmin`），保证系统始终存在管理员入口；其他管理员账号之间可正常增删改。
2. **凭据变更强制下线**：
   - 管理员修改他人角色或密码、删除用户 → `StpUtil.logout(userId)` 注销其全部会话；
   - 用户修改自己的密码 → 仅注销其他会话，保留当前会话。
3. **知识库可见性**：普通用户可读知识库列表/详情用于对话选择；一切写操作仅管理员。文档预览的普通用户分支按知识库创建人判定，随未来“用户自建知识库”能力启用，当前实际由管理员使用。
4. **`/presign` 边界（已知限制）**：仅校验登录与 bucket 白名单（`S3FileStorageService#parseS3Url`）。对话引用图使用 `document/` 前缀的 S3 Key，普通用户必须可换取预签名 URL，因此不做前缀封锁；对象 Key 为 UUID，无枚举风险。知识库文档本身对所有登录用户均可经对话检索，未构成额外的角色越权。若未来引入知识库级 ACL，需同步实现对象级校验。
5. **链路追踪属运维数据**：查询、删除、标记失败全部仅管理员可用，与前端 `/admin/traces` 路由保持一致。

## 4. 鉴权实现规范

1. **全局登录拦截**：`SaTokenConfig` 对所有路径执行 `StpUtil.checkLogin()`，仅白名单 `/auth/**`、`/error` 免登录；OPTIONS 预检与 SSE 异步调度放行。
2. **注解式角色校验**：管理接口必须使用 `@SaCheckRole(RoleConstant.ADMIN)`（类级或方法级），禁止使用 `StpUtil.checkRole("admin")` 命令式写法；禁止出现裸字符串 `"admin"`。
3. **角色解析**：`SaTokenStpInterfaceImpl#getRoleList` 每次校验实时查询 `t_user.role`，角色变更即时生效（无需缓存刷新）。
4. **异常响应**：`NotRoleException` 由 `GlobalExceptionHandler` 统一转换为 `"权限不足"`；前端按普通错误提示。
5. **接口新增检查清单**：
   - 管理接口 → 类/方法级 `@SaCheckRole(RoleConstant.ADMIN)`；
   - 用户数据接口 → 必须按 `UserContext.requireUser().getUserId()` 隔离；
   - 前端管理页面 → 挂载在 `/admin` 路由下（受 `RequireAdmin` 保护）并仅在 `isAdminRole(role)` 时渲染入口。

## 5. 前后端约定

- 后端登录/当前用户接口返回标准角色编码（`admin` / `user`），不返回展示名。
- 前端 `normalizeUserRole(role)` 将未知/缺失角色统一降级为 `user`；`RoleBadge`、用户管理页展示文案统一为“管理员 / 普通用户”。
- `RequireAdmin` 依据 `isAdminRole(user?.role)` 判定；侧边栏“后台管理”入口、管理员徽标均使用同一判定函数。
- 前端守卫读取的是本地缓存角色，存在短暂过期窗口；所有请求仍由后端强制校验，越权请求会被拒绝。

## 6. 数据库与种子数据

- `t_user` 为唯一用户/角色存储表，结构见 `resources/database/schema_all.sql`（`role VARCHAR(32) NOT NULL`，注释 `角色：admin/user`）。
- 种子账号：`admin / admin`（role=`admin`），仅用于全新部署初始化，**首次登录后必须修改密码**。
- 系统不存在 `sys_role` / `sys_permission` / `user_role` 等 RBAC 表；角色合法性由服务层白名单保证，无需数据库约束变更。

## 7. 已知限制与后续演进

| 项 | 说明 | 演进方向 |
|---|---|---|
| 前端角色缓存 | 降权后未刷新页面仍可见后台 UI，但请求会被后端拒绝 | 管理接口 401/权限不足时主动刷新用户信息 |
| 密码策略 | 无复杂度校验与首登强制改密 | 增加密码强度校验与过期策略 |
| 操作审计 | 无管理操作日志 | 引入审计日志表记录角色/权限变更 |
| 细粒度权限 | 无 permission 体系 | 如需按资源授权：新增权限表并实现 `getPermissionList`，保持两类角色不变 |
| `/presign` 对象级 ACL | 仅 bucket 白名单 | 引入知识库/用户 ACL 后补齐对象归属校验 |

## 8. 代码索引

| 职责 | 位置 |
|---|---|
| 角色枚举 | `bootstrap/src/main/java/com/byteq/ai/ragstudio/user/enums/UserRole.java` |
| 角色常量 | `bootstrap/src/main/java/com/byteq/ai/ragstudio/user/constant/RoleConstant.java` |
| 角色数据源 | `bootstrap/src/main/java/com/byteq/ai/ragstudio/user/config/SaTokenStpInterfaceImpl.java` |
| 全局鉴权配置 | `bootstrap/src/main/java/com/byteq/ai/ragstudio/user/config/SaTokenConfig.java` |
| 用户/角色管理 | `bootstrap/src/main/java/com/byteq/ai/ragstudio/user/service/impl/UserServiceImpl.java` |
| 文档预览归属 | `bootstrap/src/main/java/com/byteq/ai/ragstudio/knowledge/controller/DocumentPreviewController.java` |
| 前端角色工具 | `frontend/src/utils/role.ts` |
| 前端角色类型 | `frontend/src/types/index.ts` |
| 前端路由守卫 | `frontend/src/router.tsx` |
| 角色徽标 | `frontend/src/components/common/RoleBadge.tsx` |
