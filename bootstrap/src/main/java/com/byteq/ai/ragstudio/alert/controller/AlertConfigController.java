package com.byteq.ai.ragstudio.alert.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.byteq.ai.ragstudio.alert.dao.entity.AlertConfig;
import com.byteq.ai.ragstudio.alert.service.AlertConfigService;
import com.byteq.ai.ragstudio.alert.service.EmailService;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag/alert")
@RequiredArgsConstructor
public class AlertConfigController {

    private final AlertConfigService alertConfigService;
    private final EmailService emailService;

    @GetMapping("/config")
    public Result<AlertConfig> getConfig() {
        StpUtil.checkRole("admin");
        return Results.success(alertConfigService.getConfig());
    }

    @PutMapping("/config")
    public Result<Void> saveConfig(@RequestBody AlertConfig config) {
        StpUtil.checkRole("admin");
        alertConfigService.saveConfig(config);
        return Results.success();
    }

    @PostMapping("/test")
    public Result<Void> sendTest() {
        StpUtil.checkRole("admin");
        if (!alertConfigService.isReady()) {
            throw new ClientException("请先完善 SMTP 配置");
        }
        emailService.sendTestEmail();
        return Results.success();
    }
}
