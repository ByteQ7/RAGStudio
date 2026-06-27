# 内部开发规范

## 一、代码管理

### 版本控制
- 使用 Git 进行版本管理，代码仓库统一托管在公司 GitLab。
- 分支策略：main（生产）/ develop（开发）/ feature/*（功能）/ hotfix/*（紧急修复）。
- 严禁直接向 main 和 develop 分支推送代码，必须通过 Merge Request 合并。

### 代码评审
- 所有代码提交必须经过至少 1 人 Code Review。
- 核心模块和架构变更需经过 2 人以上评审。
- 评审标准：代码正确性、可读性、性能、安全、测试覆盖率。

### 提交规范
```
格式：[类型] 简短的描述
类型：feat / fix / refactor / docs / test / chore
示例：feat: 新增用户登录接口
```

## 二、编码规范

### Java
- 遵循阿里巴巴 Java 开发手册（泰山版）。
- 使用 Lombok 简化代码，但不过度使用。
- 接口定义需提供完整的 JavaDoc 注释。
- 单元测试覆盖率不低于 80%。

### 前端
- 使用 TypeScript 编写，禁用 any 类型。
- 组件采用函数式组件 + Hooks。
- 样式优先使用 Tailwind CSS 类名。

### SQL
- 所有 SQL 必须经过执行计划分析，严禁全表扫描。
- 表名使用小写加下划线，如 `t_user_info`。
- 字段使用 `@TableField` 显式声明映射关系。

## 三、API 规范

- RESTful 风格，使用名词复数表示资源。
- 统一返回格式：`{ code: "0", data: T, message: "" }`。
- 分页接口统一使用 `current` / `size` 参数。
- 敏感接口需加 `@SaCheckRole("admin")` 权限注解。

---

*版本：V1.5*  
*生效日期：2026-02-01*
