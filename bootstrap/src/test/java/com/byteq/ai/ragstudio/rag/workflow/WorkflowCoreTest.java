package com.byteq.ai.ragstudio.rag.workflow;

import com.byteq.ai.ragstudio.rag.core.harness.context.ContextBlock;
import com.byteq.ai.ragstudio.rag.core.harness.context.ContextBlockIds;
import com.byteq.ai.ragstudio.rag.core.harness.context.DynamicContextRegistry;
import com.byteq.ai.ragstudio.rag.core.harness.context.DynamicContextMiddleware;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import com.byteq.ai.ragstudio.rag.workflow.tool.WorkflowSaveTool;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流核心机制测试：
 * <ul>
 *   <li>JSON 编解码与校验（name 规范化 / 步骤校验 / 往返一致）</li>
 *   <li>动态上下文中间件：每轮重建 system message、失效块不再注入（"用了就清除"）</li>
 *   <li>确认防呆：用户肯定语义判定</li>
 * </ul>
 */
class WorkflowCoreTest {

    // ==================== WorkflowJson ====================

    @Test
    void jsonRoundTrip() {
        List<WorkflowDefinition.WorkflowStep> steps = List.of(
                new WorkflowDefinition.WorkflowStep("查询订单状态", "order_query", null),
                new WorkflowDefinition.WorkflowStep("物流延误则升级", null, "物流延误"),
                new WorkflowDefinition.WorkflowStep("质量问题则走质检", null, "商品质量"));
        String json = WorkflowJson.toJson(steps, "仅适用于已支付订单");
        WorkflowDefinition parsed = WorkflowJson.parse("refund-dispute", "退款争议处理",
                "用户投诉退款时使用", json);

        assertEquals(3, parsed.stepCount());
        assertEquals("查询订单状态", parsed.steps().get(0).action());
        assertEquals("order_query", parsed.steps().get(0).tool());
        assertNull(parsed.steps().get(0).when());
        assertEquals("物流延误", parsed.steps().get(1).when());
        assertEquals("仅适用于已支付订单", parsed.notes());
    }

    @Test
    void validateRejectsBadInput() {
        assertFalse(WorkflowJson.validate("Bad_Name", "标题", "描述",
                List.of(new WorkflowDefinition.WorkflowStep("a", null, null)), null).isEmpty());
        assertFalse(WorkflowJson.validate("ok-name", "", "描述",
                List.of(new WorkflowDefinition.WorkflowStep("a", null, null)), null).isEmpty());
        assertFalse(WorkflowJson.validate("ok-name", "标题", "描述", List.of(), null).isEmpty());
        assertFalse(WorkflowJson.validate("ok-name", "标题", "描述",
                List.of(new WorkflowDefinition.WorkflowStep("  ", null, null)), null).isEmpty());
        assertTrue(WorkflowJson.validate("ok-name", "标题", "描述",
                List.of(new WorkflowDefinition.WorkflowStep("做点什么", null, null)), null).isEmpty());
    }

    @Test
    void normalizeNameProducesKebabCase() {
        assertEquals("refund-dispute", WorkflowJson.normalizeName("Refund Dispute"));
        assertEquals("refund-dispute", WorkflowJson.normalizeName("refund__dispute"));
        assertEquals("a-b", WorkflowJson.normalizeName("  A B  "));
        assertTrue(WorkflowJson.normalizeName("中文名称").startsWith("wf-"));
        assertTrue(WorkflowJson.normalizeName("").startsWith("wf-"));
    }

    @Test
    void embeddingTextHashDetectsTitleOrDescriptionChange() {
        // 与 WorkflowService.sha256/embeddingStale 同源逻辑：文本指纹变化即向量失效
        String base = WorkflowService.embeddingText("退款争议处理", "用户投诉退款时使用");
        String titleChanged = WorkflowService.embeddingText("退款争议处理（新）", "用户投诉退款时使用");
        String descChanged = WorkflowService.embeddingText("退款争议处理", "用户投诉物流时使用");
        assertEquals(base, WorkflowService.embeddingText("退款争议处理", "用户投诉退款时使用"));
        assertNotEquals(base, titleChanged, "标题变更必须导致向量失效");
        assertNotEquals(base, descChanged, "描述变更必须导致向量失效");
    }

    @Test
    void confirmBlockCarriesOverwriteFlag() {
        WorkflowDefinition definition = new WorkflowDefinition("refund-dispute", "退款争议处理",
                "用户投诉退款时使用",
                List.of(new WorkflowDefinition.WorkflowStep("查订单", null, null)), null);
        String block = WorkflowRenderer.renderConfirmBlock("draft1", definition, true);
        assertTrue(block.startsWith(WorkflowRenderer.CONFIRM_START));
        assertTrue(block.contains("\"overwrite\":true"));
        String blockNo = WorkflowRenderer.renderConfirmBlock("draft1", definition, false);
        assertTrue(blockNo.contains("\"overwrite\":false"));
    }

    @Test
    void renderCandidateListOnlyExposesNameAndDescription() {
        String catalog = WorkflowRenderer.renderCandidateList(List.of(
                new WorkflowRenderer.Card("refund-dispute", "退款争议处理", "用户投诉退款时使用", 0.4)));
        assertTrue(catalog.contains("refund-dispute"));
        assertTrue(catalog.contains("用户投诉退款时使用"));
        assertTrue(catalog.contains("workflow_use"));
        // 步骤正文不进入候选清单
        assertFalse(catalog.contains("steps"));
    }

    // ==================== 动态上下文中间件（上下文清除核心） ====================

    @Test
    void middlewareInjectsActiveBlocksAndDropsDeactivated() {
        DynamicContextRegistry registry = new DynamicContextRegistry();
        registry.register(new ContextBlock(ContextBlockIds.WORKFLOW_CANDIDATES,
                "【可复用工作流】候选A、候选B", 10));

        Msg systemMsg = Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text("BASE_PROMPT").build()).build();
        Msg userMsg = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("用户问题").build()).build();
        ReasoningInput input = new ReasoningInput(List.of(systemMsg, userMsg), List.of(), null);

        // 第 1 轮：候选注入
        Msg first = invoke(registry, input);
        assertTrue(first.getTextContent().contains("BASE_PROMPT"));
        assertTrue(first.getTextContent().contains("候选A"));

        // 模拟 workflow_use：失效候选块
        registry.deactivate(ContextBlockIds.WORKFLOW_CANDIDATES);

        // 第 2 轮：候选消失（用了就清除）
        Msg second = invoke(registry, input);
        assertTrue(second.getTextContent().contains("BASE_PROMPT"));
        assertFalse(second.getTextContent().contains("候选A"));

        // 原始消息对象不被修改（不污染记忆）
        assertEquals("BASE_PROMPT", systemMsg.getTextContent());
        assertEquals(2, input.messages().size());
    }

    @Test
    void middlewareIsNoOpWithoutActiveBlocks() {
        DynamicContextRegistry registry = new DynamicContextRegistry();
        Msg systemMsg = Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text("BASE").build()).build();
        ReasoningInput input = new ReasoningInput(List.of(systemMsg), List.of(), null);
        Msg result = invoke(registry, input);
        assertEquals("BASE", result.getTextContent());
    }

    @Test
    void registryKeepsInactiveBlockButNotReturned() {
        DynamicContextRegistry registry = new DynamicContextRegistry();
        registry.register(ContextBlock.of("b", "内容"));
        assertTrue(registry.isActive("b"));
        registry.deactivate("b");
        assertFalse(registry.isActive("b"));
        assertTrue(registry.contains("b"));
        assertTrue(registry.activeBlocks().isEmpty());
        registry.register(ContextBlock.of("b", "新内容"));
        assertTrue(registry.isActive("b"));
    }

    // ==================== 确认防呆 ====================

    @Test
    void affirmativeDetection() {
        assertTrue(WorkflowSaveTool.isAffirmative("确认保存"));
        assertTrue(WorkflowSaveTool.isAffirmative("确认"));
        assertTrue(WorkflowSaveTool.isAffirmative("好的，可以"));
        assertTrue(WorkflowSaveTool.isAffirmative("yes"));
        assertFalse(WorkflowSaveTool.isAffirmative("取消"));
        assertFalse(WorkflowSaveTool.isAffirmative("不要保存"));
        assertFalse(WorkflowSaveTool.isAffirmative("修改一下第2步"));
        assertFalse(WorkflowSaveTool.isAffirmative("这个流程有问题，请重新提取"));
        assertFalse(WorkflowSaveTool.isAffirmative(""));
        assertFalse(WorkflowSaveTool.isAffirmative(null));
        // 长消息（补充说明）不视为确认
        assertFalse(WorkflowSaveTool.isAffirmative(
                "确认是确认，但我还想补充说明一下第三步应该先检查库存再下单"));
    }

    @Test
    void draftCarriesFields() {
        WorkflowDraftStore.Draft draft = new WorkflowDraftStore.Draft("id1", "c1", "u1",
                "refund-dispute", "退款争议处理", "用户投诉退款时使用", "{\"steps\":[]}",
                new java.util.Date(), true);
        assertEquals("id1", draft.draftId());
        assertEquals("refund-dispute", draft.name());
        assertEquals("退款争议处理", draft.title());
        assertTrue(draft.overwrite());
    }

    // ==================== 辅助 ====================

    /** 用中间件处理一次推理输入，返回改写后的 system message */
    private Msg invoke(DynamicContextRegistry registry, ReasoningInput input) {
        AtomicReference<List<Msg>> captured = new AtomicReference<>();
        Function<ReasoningInput, Flux<AgentEvent>> next = ri -> {
            captured.set(ri.messages());
            return Flux.empty();
        };
        new DynamicContextMiddleware(registry)
                .onReasoning((Agent) null, RuntimeContext.builder().build(), input, next)
                .blockLast();
        List<Msg> messages = captured.get();
        assertNotNull(messages);
        return messages.get(0);
    }
}
