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
 */
@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = {
        "com.byteq.ai.ragstudio.rag.dao.mapper",
        "com.byteq.ai.ragstudio.ingestion.dao.mapper",
        "com.byteq.ai.ragstudio.knowledge.dao.mapper",
        "com.byteq.ai.ragstudio.user.dao.mapper",
        "com.byteq.ai.ragstudio.mcp.dao.mapper"
})
public class RAGStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(RAGStudioApplication.class, args);
    }
}
