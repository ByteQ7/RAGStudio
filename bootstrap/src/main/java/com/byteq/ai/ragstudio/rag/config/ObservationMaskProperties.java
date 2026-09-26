package com.byteq.ai.ragstudio.rag.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 观察掩码配置属性（Observation Mask）
 * <p>
 * ReAct 循环中工具结果（Observation）在 Agent 完整读取一轮后被压缩为
 * 「结论 + 句柄（指针）」，完整原文留在请求内存中，Agent 可凭句柄按需回读，
 * 从而降低每轮模型调用的输入 token。外部实践参考：
 * <ul>
 *   <li>SWE-agent / JetBrains《The Complexity Trap》：滚动窗口 + 旧观察占位；</li>
 *   <li>Anthropic context editing clear_tool_uses：保留最近 N 次工具结果 + 清理统计；</li>
 *   <li>Manus / DeepAgents：可恢复压缩（结论 + 指针 + 溢出物回读工具）。</li>
 * </ul>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.observation-mask")
@Validated
public class ObservationMaskProperties {

    /** 总开关：关闭时行为与旧版完全一致（不注册工具、不建存储、不挂中间件） */
    private Boolean enabled = true;

    /** 保留最近 N 轮工具批次的完整结果（0=立即压缩，1=Agent 至少完整读过一轮） */
    @Min(0)
    @Max(10)
    private Integer keepRecentRounds = 1;

    /** 低于该字符数的工具结果不登记、不压缩（短结果无收益） */
    @Min(100)
    private Integer minCharsToMask = 600;

    /** 结论文本长度上限（字符），同时写入提示词约束 */
    @Min(100)
    private Integer conclusionMaxChars = 500;

    /** 结论提取使用的默认模型场景键（缺省回退 summary，再回退默认路由） */
    private String extractModelScene = "observation_extract";

    /** 结论提取超时（毫秒）：超时后走头尾摘要兜底，不阻塞主循环 */
    @Min(500)
    private Long extractTimeoutMs = 8000L;

    /** 单请求最多登记观察数（超限的旧结果保持原文，不压缩） */
    @Min(1)
    private Integer maxObservations = 32;

    /** 单请求观察原文总字符上限（超限不再登记新观察） */
    @Min(1024)
    private Integer maxStoredChars = 262144;

    /** observation_reader 单次返回默认字符数 */
    @Min(500)
    private Integer readerDefaultChars = 4000;

    /** observation_reader 单次返回字符上限（防御大结果回读） */
    @Min(500)
    private Integer readerMaxChars = 8000;

    /** 是否压缩失败的工具结果（默认保留原文，便于模型感知错误） */
    private Boolean maskErrors = false;

    /** 不参与压缩的工具名（对齐 Anthropic exclude_tools；如 web-search 可保持原文） */
    private List<String> excludeTools = new ArrayList<>();
}
