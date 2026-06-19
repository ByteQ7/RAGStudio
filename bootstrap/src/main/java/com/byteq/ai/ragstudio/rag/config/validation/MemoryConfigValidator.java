package com.byteq.ai.ragstudio.rag.config.validation;

import com.byteq.ai.ragstudio.rag.config.MemoryProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 记忆配置校验器
 * 校验摘要相关配置的合理性
 */
public class MemoryConfigValidator implements ConstraintValidator<ValidMemoryConfig, MemoryProperties> {

    /**
     * 校验记忆配置的合理性
     * <p>
     * 当摘要功能启用时，验证 summaryStartTurns（摘要触发轮数）必须大于
     * historyKeepTurns（历史保留轮数），否则摘要永远不会被触发。
     * </p>
     *
     * @param config  记忆配置属性
     * @param context 校验上下文，用于构建自定义违规消息
     * @return true 配置合法，false 配置不合法
     */
    @Override
    public boolean isValid(MemoryProperties config, ConstraintValidatorContext context) {
        if (config == null) {
            return true;
        }

        // 如果启用了摘要功能，需要校验配置的合理性
        if (Boolean.TRUE.equals(config.getSummaryEnabled())) {
            Integer summaryStartTurns = config.getSummaryStartTurns();
            Integer historyKeepTurns = config.getHistoryKeepTurns();

            // 摘要触发轮数必须大于保留轮数
            if (summaryStartTurns <= historyKeepTurns) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        String.format(
                                "当启用摘要功能时，summaryStartTurns (%d) 必须大于 historyKeepTurns (%d)，" +
                                        "否则永远不会触发摘要。建议配置至少：summaryStartTurns = historyKeepTurns + 1",
                                summaryStartTurns, historyKeepTurns
                        )
                ).addConstraintViolation();
                return false;
            }
        }

        return true;
    }
}
