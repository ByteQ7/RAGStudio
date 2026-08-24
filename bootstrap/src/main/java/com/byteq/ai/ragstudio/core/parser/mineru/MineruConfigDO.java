package com.byteq.ai.ragstudio.core.parser.mineru;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * MinerU 服务端点配置实体
 * <p>映射数据库表 t_mineru_config，保存本地/远程 MinerU 服务端点、引擎与启停开关。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_mineru_config")
public class MineruConfigDO {

    /**
     * 主键（固定单行，如 single）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 是否启用本地 MinerU
     */
    private Boolean localEnabled;

    /**
     * 本地 MinerU base URL
     */
    private String localBaseUrl;

    /**
     * 本地引擎：pipeline / vlm / hybrid
     */
    private String localBackend;

    /**
     * 本地语言分组：ch / en 等
     */
    private String localLang;

    /**
     * 本地扩展配置（预留 JSON）
     */
    private String localExtra;

    /**
     * 是否启用远程 MinerU
     */
    private Boolean remoteEnabled;

    /**
     * 远程 MinerU base URL
     */
    private String remoteBaseUrl;

    /**
     * 远程 API Key（可选）
     */
    private String remoteApiKey;

    /**
     * 远程引擎：pipeline / vlm / hybrid
     */
    private String remoteBackend;

    /**
     * 远程语言分组
     */
    private String remoteLang;

    /**
     * 远程扩展配置（预留 JSON）
     */
    private String remoteExtra;

    /**
     * 修改人
     */
    private String updatedBy;

    /**
     * 更新时间
     */
    private Date updateTime;
}