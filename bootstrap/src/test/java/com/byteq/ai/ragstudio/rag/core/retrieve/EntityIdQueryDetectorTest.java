package com.byteq.ai.ragstudio.rag.core.retrieve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityIdQueryDetector} 单元测试
 * <p>
 * 覆盖本次缺陷的回归场景：尾部全角标点曾导致整串匹配失败，
 * 快速通道（跳过改写/跳过语义选库）失效，开票知识库检索被误杀。
 * </p>
 */
class EntityIdQueryDetectorTest {

    @Test
    void 纯ID查询应命中() {
        assertTrue(EntityIdQueryDetector.isEntityIdQuery("91330108MA1K2L3M4N"));
        assertTrue(EntityIdQueryDetector.containsStrongEntityId("91330108MA1K2L3M4N"));
    }

    @Test
    void 尾部全角问号不应破坏判定_回归用例() {
        String q = "91330108MA1K2L3M4N？";
        assertTrue(EntityIdQueryDetector.isEntityIdQuery(q), "strip 标点后应仍判为纯 ID 查询");
        assertTrue(EntityIdQueryDetector.containsStrongEntityId(q));
    }

    @Test
    void 自然语言包裹的税号应识别为强ID() {
        assertTrue(EntityIdQueryDetector.containsStrongEntityId("帮我查下91330108MA1K2L3M4N的开票抬头"));
        assertTrue(EntityIdQueryDetector.containsStrongEntityId("税号91330108MA1K2L3M4N是多少"));
        assertFalse(EntityIdQueryDetector.isEntityIdQuery("帮我查下91330108MA1K2L3M4N的开票抬头"));
    }

    @Test
    void token提取应返回原始ID() {
        List<String> tokens = EntityIdQueryDetector.extractStrongIdTokens(
                "帮我查下91330108MA1K2L3M4N的开票抬头");
        assertEquals(List.of("91330108MA1K2L3M4N"), tokens);
    }

    @Test
    void 长纯数字单号应识别为强ID() {
        assertTrue(EntityIdQueryDetector.containsStrongEntityId("订单20260822001状态如何"));
        assertEquals(List.of("20260822001"),
                EntityIdQueryDetector.extractStrongIdTokens("订单20260822001状态如何"));
    }

    @Test
    void 普通业务问题不应误判() {
        assertFalse(EntityIdQueryDetector.containsStrongEntityId("2026年公司年假政策有没有变化？"));
        assertFalse(EntityIdQueryDetector.containsStrongEntityId("市场份额是多少"));
        assertFalse(EntityIdQueryDetector.containsStrongEntityId("hello world"));
        assertFalse(EntityIdQueryDetector.containsStrongEntityId(""));
        assertFalse(EntityIdQueryDetector.containsStrongEntityId(null));
    }

    @Test
    void 短于8位的串不应误判为强ID() {
        // 7 位混合串低于 8 位强 token 阈值，区分度不足
        assertFalse(EntityIdQueryDetector.containsStrongEntityId("abc1234 是什么"));
        assertFalse(EntityIdQueryDetector.containsStrongEntityId("订单12345状态"));
    }

    @Test
    void 八位错误码视为强ID_与工具描述的精确标识符场景一致() {
        // "错误码/编号/代码" 是 rag_search 工具描述明确列出的精确标识符场景：
        // 跳过改写防篡改 + 关键词精确匹配是期望行为
        assertTrue(EntityIdQueryDetector.containsStrongEntityId("error404 页面"));
        assertTrue(EntityIdQueryDetector.containsStrongEntityId("报错码A1B2C3D4E5怎么处理"));
    }

    @Test
    void 多token去重且按出现顺序返回() {
        List<String> tokens = EntityIdQueryDetector.extractStrongIdTokens(
                "对比91330108MA1K2L3M4N与91330108MA9A1B2C3X两个税号");
        assertEquals(List.of("91330108MA1K2L3M4N", "91330108MA9A1B2C3X"), tokens);
    }

    @Test
    void 纯数字年份日期不应误判() {
        assertFalse(EntityIdQueryDetector.containsStrongEntityId("2026-08-22 发布的通知内容"));
        assertFalse(EntityIdQueryDetector.containsStrongEntityId("2026年8月22日的会议纪要"));
    }

    @Test
    void 长纯数字号码视为实体ID_语义检索无区分度场景() {
        // 手机号/工号等长纯数字串与随机 ID 同理：向量检索无区分度，应走关键词精确匹配
        assertTrue(EntityIdQueryDetector.containsStrongEntityId("电话号码13812345678是谁的"));
        assertEquals(List.of("13812345678"),
                EntityIdQueryDetector.extractStrongIdTokens("电话号码13812345678是谁的"));
    }
}
