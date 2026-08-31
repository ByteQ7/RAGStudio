package com.byteq.ai.ragstudio.rag.skillstore;

import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * SKILL 存储启动初始化
 * <p>
 * 在 Spring 上下文就绪后执行：先以 DB 为准对账工作区（含存量 skills/ 目录的一次性自动收编），
 * 再触发 {@link SkillLoader} 重扫。DB 不可用时跳过对账，工作区保持现状继续服务
 * （与提示词 DB 异常回退 classpath 的降级思想一致）。{@code @Order(100)}：排在 DataDirMigrator（@Order(0)）
 * 之后，确保数据目录迁移先行完成。</p>
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class SkillStoreInitializer implements ApplicationRunner {

    private final SkillWorkspaceService workspaceService;
    private final SkillLoader skillLoader;

    @Override
    public void run(ApplicationArguments args) {
        try {
            workspaceService.reconcile();
        } catch (Exception e) {
            log.warn("SKILL 工作区对账失败，跳过（工作区保持现状，恢复后可手动同步）", e);
        }
        try {
            skillLoader.scanAndLoad();
        } catch (Exception e) {
            log.warn("SKILL 启动加载失败", e);
        }
    }
}
