package com.byteq.ai.ragstudio.aimodel.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 远程模型列表获取结果返回对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FetchModelsResultVO {
    /** 模型列表 */
    private List<RemoteModelInfoVO> models;
}
