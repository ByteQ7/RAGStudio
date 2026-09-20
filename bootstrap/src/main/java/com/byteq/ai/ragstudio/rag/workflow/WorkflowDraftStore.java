package com.byteq.ai.ragstudio.rag.workflow;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流草稿存储（Redis，按会话维度）
 * <p>
 * "提取 → 展示 → 用户确认" 跨对话轮次完成，草稿需要跨轮存活：
 * <ul>
 *   <li>key：{@code RAGStudio:workflow:draft:<conversationId>}（同会话只保留最新草稿）</li>
 *   <li>TTL：{@code rag.workflow.draft-ttl-minutes}（默认 30 分钟）</li>
 *   <li>确认保存 / 取消后删除；Redis 不可用时降级为"无草稿"（功能不可用但不影响对话）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowDraftStore {

    private static final String KEY_PREFIX = "RAGStudio:workflow:draft:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedissonClient redissonClient;

    @Value("${rag.workflow.draft-ttl-minutes:30}")
    private long draftTtlMinutes;

    /**
     * 草稿内容
     *
     * @param draftId       草稿 ID
     * @param conversationId 会话 ID
     * @param userId        用户 ID
     * @param name          唯一标识
     * @param title         展示名
     * @param description   描述
     * @param stepsJson     结构化步骤 JSON
     * @param createdAt     创建时间
     */
    public record Draft(String draftId, String conversationId, String userId, String name,
                        String title, String description, String stepsJson, Date createdAt,
                        boolean overwrite) {
    }

    /** 保存（覆盖同会话旧草稿），返回草稿 */
    public Draft save(String conversationId, String userId, String name, String title,
                      String description, String stepsJson) {
        return save(conversationId, userId, name, title, description, stepsJson, false);
    }

    /**
     * 保存草稿
     *
     * @param overwrite 是否与已有工作流同名（确认后为更新覆盖，需在卡片上显式提示用户）
     */
    public Draft save(String conversationId, String userId, String name, String title,
                      String description, String stepsJson, boolean overwrite) {
        Draft draft = new Draft(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                conversationId, userId, name, title, description, stepsJson, new Date(), overwrite);
        if (StrUtil.isBlank(conversationId)) {
            return draft;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("draftId", draft.draftId());
            payload.put("conversationId", conversationId);
            payload.put("userId", userId);
            payload.put("name", name);
            payload.put("title", title);
            payload.put("description", description);
            payload.put("stepsJson", stepsJson);
            payload.put("overwrite", overwrite);
            payload.put("createdAt", draft.createdAt().getTime());
            redissonClient.getBucket(KEY_PREFIX + conversationId)
                    .set(MAPPER.writeValueAsString(payload), Duration.ofMinutes(Math.max(1, draftTtlMinutes)));
        } catch (Exception e) {
            log.warn("工作流草稿写入 Redis 失败（本次草稿无法跨轮确认）: {}", e.getMessage());
        }
        return draft;
    }

    /** 读取会话最新草稿；不存在返回 null */
    public Draft getLatest(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + conversationId);
            String json = bucket.get();
            if (StrUtil.isBlank(json)) {
                return null;
            }
            Map<?, ?> payload = MAPPER.readValue(json, Map.class);
            Object created = payload.get("createdAt");
            return new Draft(
                    str(payload.get("draftId")),
                    str(payload.get("conversationId")),
                    str(payload.get("userId")),
                    str(payload.get("name")),
                    str(payload.get("title")),
                    str(payload.get("description")),
                    str(payload.get("stepsJson")),
                    created instanceof Number n ? new Date(n.longValue()) : new Date(),
                    Boolean.TRUE.equals(payload.get("overwrite")));
        } catch (Exception e) {
            log.warn("工作流草稿读取失败: {}", e.getMessage());
            return null;
        }
    }

    /** 按 draftId 读取（校验一致性）；不存在返回 null */
    public Draft get(String conversationId, String draftId) {
        Draft draft = getLatest(conversationId);
        if (draft == null) {
            return null;
        }
        if (StrUtil.isNotBlank(draftId) && !draftId.equals(draft.draftId())) {
            return null;
        }
        return draft;
    }

    /** 删除会话草稿 */
    public void remove(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return;
        }
        try {
            redissonClient.getBucket(KEY_PREFIX + conversationId).delete();
        } catch (Exception e) {
            log.warn("工作流草稿删除失败: {}", e.getMessage());
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
