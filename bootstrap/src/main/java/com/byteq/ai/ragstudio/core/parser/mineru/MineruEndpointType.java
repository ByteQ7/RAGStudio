package com.byteq.ai.ragstudio.core.parser.mineru;

/**
 * MinerU 服务端点协议类型
 * <ul>
 *   <li>{@link #LOCAL}：本地/自建 mineru-api，同步 multipart {@code POST /file_parse}</li>
 *   <li>{@link #CLOUD_AGENT}：mineru.net 官方 Agent 轻量 API（免费免 Token），
 *       异步任务流：签名上传 → PUT → 轮询 → 下载 Markdown</li>
 * </ul>
 */
public enum MineruEndpointType {

    /** 本地 / 自建 mineru-api 协议（POST /file_parse） */
    LOCAL,

    /** mineru.net 官方 Agent 轻量 API（异步任务 + 签名上传） */
    CLOUD_AGENT
}
