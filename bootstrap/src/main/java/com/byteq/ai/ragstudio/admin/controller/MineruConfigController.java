package com.byteq.ai.ragstudio.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.byteq.ai.ragstudio.core.parser.mineru.MineruConfigService;
import com.byteq.ai.ragstudio.core.parser.mineru.MineruConfigVO;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MinerU 解析服务配置接口
 * <p>
 * 提供本地/远程 MinerU 端点配置的读取、保存与连通性探测，供系统设置页使用。
 * </p>
 */
@RestController
@RequestMapping("/rag/mineru")
@RequiredArgsConstructor
public class MineruConfigController {

    private final MineruConfigService mineruConfigService;

    /**
     * 读取 MinerU 配置
     */
    @GetMapping("/config")
    public Result<MineruConfigVO> getConfig() {
        StpUtil.checkRole("admin");
        return Results.success(mineruConfigService.loadVO());
    }

    /**
     * 保存 MinerU 配置
     */
    @PutMapping("/config")
    public Result<Void> saveConfig(@RequestBody MineruConfigVO vo) {
        StpUtil.checkRole("admin");
        mineruConfigService.saveVO(vo);
        return Results.success();
    }

    /**
     * 连通性探测：实时反馈本地/远程端点的可达性
     */
    @PostMapping("/config/ping")
    public Result<MineruConfigVO> ping() {
        StpUtil.checkRole("admin");
        return Results.success(mineruConfigService.probe());
    }
}