package com.byteq.ai.ragstudio;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RAGStudio 核心应用启动类
 * <p>
 * RAG（检索增强生成）智能问答系统的 Spring Boot 启动入口。
 * 集成知识库管理、文档摄取管道、多轮对话、意图识别、向量检索等核心功能。
 * </p>
 *
 * <p>
 * <b>模块扫描说明：</b>
 * <ul>
 *   <li>启用定时任务调度（@EnableScheduling），用于知识库文档同步等后台任务</li>
 *   <li>自动扫描 rag、ingestion、knowledge、user 四个模块的 MyBatis Mapper 接口</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Spring AI 说明：</b>
 * 排除 Spring AI 的自动配置，由 SpringAiChatModelFactory 动态创建模型实例，
 * 以支持多提供商、多候选模型的路由切换架构。
 * </p>
 */
@SpringBootApplication(exclude = {
        // OpenAI 兼容模式 —— 排除全部自动配置，由 SpringAiChatModelFactory 动态创建
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
        // 排除 Spring Security 自动配置，应用使用 Sa-Token 进行认证
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
})
@EnableScheduling
@MapperScan(basePackages = {
        "com.byteq.ai.ragstudio.rag.dao.mapper",
        "com.byteq.ai.ragstudio.ingestion.dao.mapper",
        "com.byteq.ai.ragstudio.knowledge.dao.mapper",
        "com.byteq.ai.ragstudio.user.dao.mapper",
        "com.byteq.ai.ragstudio.mcp.dao.mapper",
        "com.byteq.ai.ragstudio.aimodel.dao.mapper"
})
public class RAGStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(RAGStudioApplication.class, args);
    }
}
