# 轻量级部署方案

本目录提供内存受限环境下的 Docker Compose 配置，适用于本地开发机或低配服务器。

## 说明

本项目已移除 Milvus 向量数据库，统一使用 **PostgreSQL + pgvector** 作为向量存储。
如需部署 Milvus，请参考历史版本或自行配置。

## 可用组件

| 组件 | 文件 | 说明 |
|------|------|------|
| RocketMQ | `rocketmq-stack-5.2.0.compose.yaml` | 消息队列，用于文档摄取任务异步处理 |
