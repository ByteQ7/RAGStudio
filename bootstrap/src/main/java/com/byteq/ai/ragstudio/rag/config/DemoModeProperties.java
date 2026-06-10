package com.byteq.ai.ragstudio.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 体验环境只读模式配置
 * <p>
 * 用于控制演示/体验环境是否开启只读模式。
 * 开启后，用户仅能查看数据而无法进行写入操作（如创建、更新、删除等），
 * 适用于对外开放的体验环境，防止数据被篡改。
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class DemoModeProperties {

    /**
     * 是否开启体验环境只读模式
     * <p>
     * 默认关闭（false），设置为 true 后系统进入只读状态。
     * </p>
     */
    private Boolean demoMode = false;
}
