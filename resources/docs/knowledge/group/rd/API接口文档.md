# RAGStudio API 接口文档

## 一、概述

### 1.1 基础信息

| 项目 | 说明 |
|------|------|
| 协议 | HTTPS |
| 格式 | JSON |
| 编码 | UTF-8 |
| 认证 | Sa-Token (Bearer Token) |
| 基础路径 | `/api/ragstudio` |

### 1.2 通用响应格式

```json
{
  "code": 0,
  "message": "success",
  "data": { ... },
  "timestamp": 1751193600000
}
```

### 1.3 错误码

| 错误码 | 说明 |
|--------|------|
| 0 | 成功 |
| 1001 | 参数错误 |
| 1002 | 未登录 |
| 1003 | 权限不足 |
| 1004 | 资源不存在 |
| 2001 | 业务异常 |
| 5000 | 系统异常 |

## 二、认证接口

### 2.1 登录

```
POST /auth/login
Content-Type: application/json

请求:
{
  "username": "admin",
  "password": "admin"
}

响应:
{
  "code": 0,
  "data": {
    "token": "sa-token-xxx",
    "userInfo": {
      "id": "1",
      "username": "admin",
      "role": "ADMIN"
    }
  }
}
```

### 2.2 退出登录

```
POST /auth/logout
Authorization: Bearer <token>

响应:
{
  "code": 0,
  "message": "已退出登录"
}
```

## 三、知识库接口

### 3.1 创建知识库

```
POST /knowledge-base/create
Authorization: Bearer <token>

请求:
{
  "name": "HR部门知识库",
  "description": "HR相关文档",
  "embeddingProvider": "siliconflow",
  "embeddingModel": "Qwen/Qwen3-Embedding-8B",
  "dimension": 1536,
  "collectionName": "hr-group-document"
}
```

### 3.2 查询知识库列表

```
GET /knowledge-base/list?page=1&size=20
Authorization: Bearer <token>

响应:
{
  "code": 0,
  "data": {
    "records": [
      {
        "id": "2077043298647863296",
        "name": "HR部门知识库",
        "description": "HR documents",
        "collectionName": "hr-group-document",
        "documentCount": 13,
        "chunkCount": 842,
        "status": "ACTIVE",
        "createTime": "2026-03-15T10:30:00"
      }
    ],
    "total": 5,
    "page": 1,
    "size": 20
  }
}
```

### 3.3 更新知识库

```
PUT /knowledge-base/update
Authorization: Bearer <token>

请求:
{
  "id": "2077043298647863296",
  "name": "HR部门知识库(2026新版)",
  "description": "更新后的描述"
}
```

### 3.4 删除知识库

```
DELETE /knowledge-base/{kbId}
Authorization: Bearer <token>

响应:
{
  "code": 0,
  "message": "删除成功"
}
```

## 四、文档管理接口

### 4.1 上传文档

```
POST /knowledge-document/upload
Authorization: Bearer <token>
Content-Type: multipart/form-data

参数:
- kbId: 知识库ID
- file: 文件(支持 .md/.pdf/.doc/.docx/.xlsx/.pptx/.odt 等)

响应:
{
  "code": 0,
  "data": {
    "documentId": "2082763859064864700",
    "fileName": "员工手册.pdf",
    "status": "PROCESSING"
  }
}
```

### 4.2 查询文档列表

```
GET /knowledge-document/list?kbId={kbId}&page=1&size=20
Authorization: Bearer <token>

响应:
{
  "code": 0,
  "data": {
    "records": [
      {
        "id": "2082763859064864700",
        "fileName": "员工手册.pdf",
        "fileType": "pdf",
        "fileSize": 2048576,
        "status": "COMPLETED",
        "chunkCount": 156,
        "createTime": "2026-07-15T14:00:00"
      }
    ],
    "total": 13
  }
}
```

### 4.3 删除文档

```
DELETE /knowledge-document/{docId}
Authorization: Bearer <token>
```

## 五、对话接口

### 5.1 流式对话 (SSE)

```
POST /rag/v3/chat
Authorization: Bearer <token>
Accept: text/event-stream

请求:
{
  "conversationId": "2082763859064864768",    // 可选，不传则新建会话
  "question": "公司年假政策是什么？",
  "knowledgeBaseIds": ["2077043298647863296"],
  "deepThinkingLevel": 0,                     // 0-100，0=关闭
  "enableSkills": true,                       // 是否启用Skill工具
  "enableMcp": true                           // 是否启用MCP工具
}

SSE事件流:
event: step
data: {"thought":"需要搜索年假政策","action":"rag_search","action_input":{"query":"年假 政策"}}

event: step
data: {"thought":"已找到相关信息","action":"finish","final_answer":"公司实行带薪年假制度..."}

event: final
data: {"conversationId":"2082763859064864768","answer":"公司实行带薪年假制度..."}
```

### 5.2 获取对话历史

```
GET /conversation/messages/{conversationId}?page=1&size=50
Authorization: Bearer <token>

响应:
{
  "code": 0,
  "data": {
    "records": [
      {
        "id": "xxx",
        "role": "USER",
        "content": "公司年假政策是什么？",
        "createTime": "2026-07-30T10:30:00"
      },
      {
        "id": "yyy",
        "role": "ASSISTANT",
        "content": "公司实行带薪年假制度...",
        "citations": [
          {
            "id": "chunk_001",
            "kbName": "HR部门知识库",
            "docName": "薪资与福利政策.md",
            "text": "员工工作满1年不满10年的，年假5天..."
          }
        ],
        "thinkingContent": "需要检索年假相关信息",
        "createTime": "2026-07-30T10:30:05"
      }
    ]
  }
}
```

## 六、RAG溯源接口

### 6.1 查询RAG溯源记录

```
GET /rag-trace/records?conversationId={conversationId}&messageId={messageId}
Authorization: Bearer <token>

响应:
{
  "code": 0,
  "data": {
    "traceId": "trace-xxx",
    "totalDuration": 12580,
    "nodes": [
      {
        "name": "知识库相关性判断",
        "type": "JUDGE",
        "duration": 850,
        "status": "SUCCESS"
      },
      {
        "name": "Agent循环",
        "type": "AGENT",
        "duration": 8200,
        "status": "SUCCESS",
        "children": [
          {
            "name": "LLM调用",
            "type": "LLM",
            "duration": 3200,
            "modelName": "qwen3-plus",
            "tokenUsage": {"prompt": 1542, "completion": 256}
          },
          {
            "name": "RAG检索",
            "type": "RETRIEVAL",
            "duration": 450,
            "resultCount": 15
          }
        ]
      }
    ]
  }
}
```

## 七、模型管理接口

### 7.1 供应商列表

```
GET /ai-provider/list
Authorization: Bearer <token>

响应:
{
  "code": 0,
  "data": [
    {
      "id": "1",
      "name": "Bailian",
      "protocol": "dashscope",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode",
      "models": [
        {"model": "qwen3-plus", "type": "chat"},
        {"model": "qwen3-embedding-8b", "type": "embedding"}
      ]
    }
  ]
}
```

### 7.2 更新默认模型

```
PUT /default-model/update
Authorization: Bearer <token>

请求:
{
  "chatDefaultProvider": "siliconflow",
  "chatDefaultModel": "Qwen/Qwen3-235B",
  "embeddingDefaultProvider": "siliconflow",
  "embeddingDefaultModel": "Qwen/Qwen3-Embedding-8B",
  "rerankDefaultProvider": "bailian",
  "rerankDefaultModel": "gte-rerank"
}
```

## 八、系统设置接口

### 8.1 获取系统设置

```
GET /system/settings
Authorization: Bearer <token>

响应:
{
  "code": 0,
  "data": {
    "semanticHighlightEnabled": true,
    "semanticHighlightBaseUrl": "http://localhost:8001",
    "maxConversationHistory": 20,
    "chunkOverlap": 50,
    "chunkSize": 512
  }
}
```

### 8.2 更新系统设置

```
PUT /system/settings
Authorization: Bearer <token>

请求:
{
  "semanticHighlightEnabled": true,
  "maxConversationHistory": 30,
  "chunkSize": 800
}
```

---
*文档编号：RD-2026-003*
*版本：V2.0*
