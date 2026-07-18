package com.byteq.ai.ragstudio.alert.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.byteq.ai.ragstudio.alert.dao.entity.AlertConfig;
import com.byteq.ai.ragstudio.alert.dao.mapper.AlertConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertConfigService {

    private static final String CONFIG_ID = "default";

    private final AlertConfigMapper alertConfigMapper;

    /**
     * 获取告警配置（不存在则返回默认配置）
     */
    public AlertConfig getConfig() {
        AlertConfig config = alertConfigMapper.selectById(CONFIG_ID);
        if (config == null) {
            return AlertConfig.builder()
                    .id(CONFIG_ID)
                    .enabled(0)
                    .smtpPort(465)
                    .timeWindowHours(5)
                    .failureThreshold(5)
                    .build();
        }
        return config;
    }

    /**
     * 保存告警配置
     */
    public void saveConfig(AlertConfig config) {
        config.setId(CONFIG_ID);
        config.setUpdateTime(LocalDateTime.now());

        AlertConfig existing = alertConfigMapper.selectById(CONFIG_ID);
        if (existing == null) {
            config.setCreateTime(LocalDateTime.now());
            config.setDeleted(0);
            alertConfigMapper.insert(config);
        } else {
            alertConfigMapper.updateById(config);
        }
        log.info("告警配置已保存");
    }

    /**
     * 检查告警功能是否已启用且配置完整
     */
    public boolean isReady() {
        AlertConfig config = getConfig();
        return config.getEnabled() == 1
                && StrUtil.isNotBlank(config.getSmtpHost())
                && StrUtil.isNotBlank(config.getToAddress());
    }

    /**
     * 获取收件人邮箱
     */
    public Optional<String> getToAddress() {
        return Optional.ofNullable(getConfig().getToAddress())
                .filter(StrUtil::isNotBlank);
    }

    /**
     * 获取时间窗口（小时）
     */
    public int getTimeWindowHours() {
        AlertConfig config = getConfig();
        return config.getTimeWindowHours() != null ? config.getTimeWindowHours() : 5;
    }

    /**
     * 获取熔断次数阈值
     */
    public int getFailureThreshold() {
        AlertConfig config = getConfig();
        return config.getFailureThreshold() != null ? config.getFailureThreshold() : 5;
    }
}
