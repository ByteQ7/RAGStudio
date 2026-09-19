package com.byteq.ai.ragstudio.alert.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.byteq.ai.ragstudio.alert.dao.entity.AlertConfig;
import com.byteq.ai.ragstudio.alert.service.AlertConfigService;
import com.byteq.ai.ragstudio.alert.service.EmailService;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.user.constant.RoleConstant;
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
    @SaCheckRole(RoleConstant.ADMIN)
    public Result<AlertConfig> getConfig() {
        return Results.success(alertConfigService.getConfig());
    }

    @PutMapping("/config")
    @SaCheckRole(RoleConstant.ADMIN)
    public Result<Void> saveConfig(@RequestBody AlertConfig config) {
        alertConfigService.saveConfig(config);
        return Results.success();
    }

    @PostMapping("/test")
    @SaCheckRole(RoleConstant.ADMIN)
    public Result<Void> sendTest() {
        if (!alertConfigService.isReady()) {
            throw new ClientException("请先完善 SMTP 配置");
        }
        emailService.sendTestEmail();
        return Results.success();
    }
}
