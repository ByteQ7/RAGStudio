package com.byteq.ai.ragstudio.rag.skillstore;

import java.util.List;
import java.util.Map;

/**
 * SKILL 在线编辑提交输入（一次提交多文件 = 一个新版本）
 * <p>仅支持文本文件（UTF-8）；二进制文件变更请走 ZIP 整包上传。</p>
 *
 * @param changeLog 版本说明
 * @param upserts   新增/修改的文本文件：路径 → 内容
 * @param deletions 删除的文件路径
 */
public record SkillCommitInput(
        String changeLog,
        Map<String, String> upserts,
        List<String> deletions) {
}
