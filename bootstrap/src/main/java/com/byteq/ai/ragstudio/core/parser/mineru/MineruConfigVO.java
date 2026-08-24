package com.byteq.ai.ragstudio.core.parser.mineru;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MinerU 配置视图对象（返回给前端展示/编辑）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MineruConfigVO {

    /**
     * 本地端点配置
     */
    private EndpointVO local;

    /**
     * 远程端点配置
     */
    private EndpointVO remote;

    /**
     * 单次解析超时（秒）
     */
    private Long timeoutSeconds;

    /**
     * MinerU 文本结果最小有效长度阈值
     */
    private Integer minTextLength;

    /**
     * 单端点配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointVO {
        private Boolean enabled;
        private String baseUrl;
        private String backend;
        private String lang;
        private String apiKey;
        /** 连通性探测结果（仅探测接口返回） */
        private Boolean reachable;
    }
}