package com.byteq.ai.ragstudio.rag.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.controller.request.ConversationGroupCreateRequest;
import com.byteq.ai.ragstudio.rag.controller.request.ConversationGroupUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.ConversationGroupVO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationGroupDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationMessageDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationSummaryDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationGroupMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMessageMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationSummaryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对话分组管理服务（元宝式对话分组）
 * <p>
 * 提供分组 CRUD（创建仅设名称，其余项创建后在设置菜单中配置）、会话移动（单条/批量/移出）、
 * 组内新会话归组、分组指令解析以及默认知识库管理能力。
 * 删除分组会级联删除组内全部会话及其消息、摘要。
 * 命名为 Manager 以区分内部聚合 DAO 服务 {@link ConversationGroupService}。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationGroupManager {

    private static final int MAX_GROUP_COUNT = 50;
    private static final int MAX_GROUP_KB_COUNT = 20;

    private final ConversationGroupMapper groupMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final ConversationGroupService conversationGroupService;

    /**
     * 查询用户的分组列表（置顶优先，同层级按创建时间正序），附带组内会话数量与默认知识库
     */
    public List<ConversationGroupVO> listGroups(String userId) {
        if (StrUtil.isBlank(userId)) {
            return List.of();
        }
        List<ConversationGroupDO> groups = groupMapper.selectList(
                Wrappers.lambdaQuery(ConversationGroupDO.class)
                        .eq(ConversationGroupDO::getUserId, userId)
                        .orderByDesc(ConversationGroupDO::getPinned)
                        .orderByAsc(ConversationGroupDO::getCreateTime)
                        .orderByAsc(ConversationGroupDO::getId)
        );
        if (groups.isEmpty()) {
            return List.of();
        }

        Map<String, Long> countByGroup = countConversationsByGroup(userId);
        return groups.stream()
                .map(group -> ConversationGroupVO.builder()
                        .groupId(group.getId())
                        .name(group.getName())
                        .instruction(group.getInstruction())
                        .pinned(Boolean.TRUE.equals(group.getPinned()))
                        .knowledgeBaseIds(parseKnowledgeBaseIds(group.getKnowledgeBaseIds()))
                        .conversationCount(countByGroup.getOrDefault(group.getId(), 0L))
                        .createTime(group.getCreateTime())
                        .build())
                .collect(Collectors.toList());
    }

    // 统计用户各分组下的会话数量（一次 group by 查询）
    private Map<String, Long> countConversationsByGroup(String userId) {
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("group_id", "COUNT(*) AS cnt")
                .eq("user_id", userId)
                .isNotNull("group_id")
                .groupBy("group_id");
        List<Map<String, Object>> rows = conversationMapper.selectMaps(wrapper);
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object groupId = row.get("group_id");
            Object cnt = row.get("cnt");
            if (groupId != null && cnt != null) {
                result.put(String.valueOf(groupId), ((Number) cnt).longValue());
            }
        }
        return result;
    }

    /**
     * 创建分组（仅设置名称，指令/置顶/知识库由创建后通过设置菜单配置）
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationGroupVO create(String userId, ConversationGroupCreateRequest request) {
        if (StrUtil.isBlank(userId)) {
            throw new ClientException("用户信息缺失");
        }
        Long count = groupMapper.selectCount(
                Wrappers.lambdaQuery(ConversationGroupDO.class)
                        .eq(ConversationGroupDO::getUserId, userId)
        );
        if (count != null && count >= MAX_GROUP_COUNT) {
            throw new ClientException("分组数量已达上限（" + MAX_GROUP_COUNT + " 个）");
        }

        ConversationGroupDO group = ConversationGroupDO.builder()
                .userId(userId)
                .name(request.getName().trim())
                .pinned(false)
                .build();
        groupMapper.insert(group);
        return ConversationGroupVO.builder()
                .groupId(group.getId())
                .name(group.getName())
                .pinned(false)
                .knowledgeBaseIds(List.of())
                .conversationCount(0L)
                .createTime(group.getCreateTime())
                .build();
    }

    /**
     * 更新分组（部分更新语义，字段为 null 表示不修改）
     * <p>
     * 使用 lambdaUpdate 显式 set：updateById 的默认 NOT_NULL 字段策略会忽略 null 字段，
     * 无法表达"不修改"与"清除"两种语义。
     * </p>
     *
     * @param userId  用户 ID
     * @param groupId 分组 ID
     * @param request 更新请求：name（置空串报错）、instruction（空串清除）、pinned、knowledgeBaseIds（空列表清除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(String userId, String groupId, ConversationGroupUpdateRequest request) {
        getOwnedGroup(userId, groupId);

        boolean hasChanges = false;
        var updater = Wrappers.lambdaUpdate(ConversationGroupDO.class)
                .eq(ConversationGroupDO::getId, groupId);
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new ClientException("分组名称不能为空");
            }
            updater.set(ConversationGroupDO::getName, name);
            hasChanges = true;
        }
        if (request.getInstruction() != null) {
            updater.set(ConversationGroupDO::getInstruction, StrUtil.blankToDefault(request.getInstruction(), null));
            hasChanges = true;
        }
        if (request.getPinned() != null) {
            updater.set(ConversationGroupDO::getPinned, request.getPinned());
            hasChanges = true;
        }
        if (request.getKnowledgeBaseIds() != null) {
            updater.set(ConversationGroupDO::getKnowledgeBaseIds, serializeKnowledgeBaseIds(request.getKnowledgeBaseIds()));
            hasChanges = true;
        }
        if (hasChanges) {
            groupMapper.update(null, updater);
        }
    }

    // 序列化分组默认知识库 ID 列表：去空白去重、限制数量，空列表返回 null（清除）
    private String serializeKnowledgeBaseIds(List<String> knowledgeBaseIds) {
        List<String> kbIds = knowledgeBaseIds.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .limit(MAX_GROUP_KB_COUNT)
                .collect(Collectors.toList());
        return kbIds.isEmpty() ? null : JSONUtil.toJsonStr(kbIds);
    }

    // 解析分组默认知识库 ID JSON，失败时降级为空列表
    private List<String> parseKnowledgeBaseIds(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            List<String> ids = JSONUtil.toList(json, String.class);
            return ids != null ? ids : List.of();
        } catch (Exception e) {
            log.warn("解析分组默认知识库失败，降级为空 - knowledgeBaseIds: {}", json, e);
            return List.of();
        }
    }

    /**
     * 删除分组
     * <p>
     * 级联删除组内全部会话及其消息、摘要（逻辑删除），操作不可恢复。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String userId, String groupId) {
        getOwnedGroup(userId, groupId);

        List<String> conversationIds = conversationMapper.selectList(
                        Wrappers.lambdaQuery(ConversationDO.class)
                                .select(ConversationDO::getConversationId)
                                .eq(ConversationDO::getUserId, userId)
                                .eq(ConversationDO::getGroupId, groupId))
                .stream()
                .map(ConversationDO::getConversationId)
                .collect(Collectors.toList());

        if (!conversationIds.isEmpty()) {
            messageMapper.delete(
                    Wrappers.lambdaQuery(ConversationMessageDO.class)
                            .eq(ConversationMessageDO::getUserId, userId)
                            .in(ConversationMessageDO::getConversationId, conversationIds));
            summaryMapper.delete(
                    Wrappers.lambdaQuery(ConversationSummaryDO.class)
                            .eq(ConversationSummaryDO::getUserId, userId)
                            .in(ConversationSummaryDO::getConversationId, conversationIds));
        }
        conversationMapper.delete(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getGroupId, groupId));
        groupMapper.deleteById(groupId);
    }

    /**
     * 批量移动会话到指定分组；groupId 为空表示移出分组
     *
     * @param userId          用户 ID
     * @param conversationIds 会话 ID 列表（业务会话 ID）
     * @param groupId         目标分组 ID，空表示移出分组
     */
    @Transactional(rollbackFor = Exception.class)
    public int moveConversations(String userId, Set<String> conversationIds, String groupId) {
        if (StrUtil.isBlank(userId) || conversationIds == null || conversationIds.isEmpty()) {
            return 0;
        }
        String targetGroupId = null;
        if (StrUtil.isNotBlank(groupId)) {
            // 目标分组必须存在且属于当前用户
            getOwnedGroup(userId, groupId);
            targetGroupId = groupId;
        }
        return conversationMapper.update(null,
                Wrappers.lambdaUpdate(ConversationDO.class)
                        .eq(ConversationDO::getUserId, userId)
                        .in(ConversationDO::getConversationId, conversationIds)
                        .set(ConversationDO::getGroupId, targetGroupId));
    }

    /**
     * 将新创建的会话归入指定分组（组内新建对话场景）
     * <p>
     * 仅当会话尚未归属任何分组时生效（group_id IS NULL 条件更新），
     * 分组无效或不属于当前用户时静默跳过，不影响对话主流程。
     * </p>
     */
    public void assignGroupToConversation(String conversationId, String userId, String groupId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || StrUtil.isBlank(groupId)) {
            return;
        }
        try {
            ConversationGroupDO group = groupMapper.selectById(groupId);
            if (group == null || !userId.equals(group.getUserId())) {
                log.warn("会话归组跳过：分组不存在或不属于当前用户 - groupId: {}, userId: {}", groupId, userId);
                return;
            }
            conversationMapper.update(null,
                    Wrappers.lambdaUpdate(ConversationDO.class)
                            .eq(ConversationDO::getConversationId, conversationId)
                            .eq(ConversationDO::getUserId, userId)
                            .isNull(ConversationDO::getGroupId)
                            .set(ConversationDO::getGroupId, groupId));
        } catch (Exception e) {
            // 归组失败不影响对话主流程
            log.warn("会话归组失败 - conversationId: {}, groupId: {}", conversationId, groupId, e);
        }
    }

    /**
     * 解析会话所属分组的专属指令（用于注入系统提示）
     * <p>
     * 会话行上的 group_id 是唯一事实来源：组内新会话在首次消息时已归组，
     * 历史会话移动分组后同样即时生效。
     * </p>
     *
     * @return 分组指令，会话未分组/分组无指令时返回 null
     */
    public String resolveGroupInstruction(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation == null || StrUtil.isBlank(conversation.getGroupId())) {
            return null;
        }
        ConversationGroupDO group = groupMapper.selectById(conversation.getGroupId());
        if (group == null || !userId.equals(group.getUserId())) {
            return null;
        }
        return StrUtil.blankToDefault(group.getInstruction(), null);
    }

    // 获取用户拥有的分组，不存在或无权访问时抛出业务异常
    private ConversationGroupDO getOwnedGroup(String userId, String groupId) {
        if (StrUtil.isBlank(userId) || StrUtil.isBlank(groupId)) {
            throw new ClientException("分组信息缺失");
        }
        ConversationGroupDO group = groupMapper.selectById(groupId);
        if (group == null || !userId.equals(group.getUserId())) {
            throw new ClientException("分组不存在");
        }
        return group;
    }
}
