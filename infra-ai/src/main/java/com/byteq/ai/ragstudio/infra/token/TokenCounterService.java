package com.byteq.ai.ragstudio.infra.token;

/**
 * Token 计数服务接口
 * <p>
 * 定义了 AI 模型 Token 计数的统一契约，用于估算或精确计算文本内容所消耗的 Token 数量。
 * Token 计数在以下场景中至关重要：
 * </p>
 * <ul>
 *   <li>估算模型调用的成本消耗</li>
 *   <li>判断输入文本是否超过模型的上下文窗口限制</li>
 *   <li>对长文本进行分片处理时的 Token 预算控制</li>
 *   <li>用量统计和监控</li>
 * </ul>
 *
 * <p>不同的实现可以采用不同的计数策略：</p>
 * <ul>
 *   <li>启发式估算：基于字符类型和比例进行近似计算，速度快但精度较低</li>
 *   <li>精确计数：使用模型对应的 Tokenizer 进行精确计算，精度高但性能开销较大</li>
 * </ul>
 */
public interface TokenCounterService {

    /**
     * 统计给定文本的 Token 数量
     * <p>
     * 根据实现策略的不同，返回的结果可能是精确值或估算值。
     * 建议调用方根据精度需求选择合适的实现。
     * </p>
     *
     * @param text 待统计的文本内容，不能为 null
     * @return Token 数量（如果无法计算或文本为空则返回 0 或 null，具体取决于实现）
     */
    Integer countTokens(String text);
}
