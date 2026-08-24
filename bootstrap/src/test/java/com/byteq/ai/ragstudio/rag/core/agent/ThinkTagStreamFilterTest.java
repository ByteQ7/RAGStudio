package com.byteq.ai.ragstudio.rag.core.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ThinkTagStreamFilter} 单元测试
 * <p>
 * 重点覆盖标签跨增量切分（如 "&lt;th" + "ink&gt;" 分两个 delta 到达）
 * 与多 think 区间场景——这是流式剥离器区别于一次性 strip 的核心价值。
 * </p>
 */
class ThinkTagStreamFilterTest {

    /** 逐字符喂入模拟最极端的增量切分，收集正文与思考输出 */
    private record FedResult(StringBuilder content, StringBuilder thinking) {}

    private static FedResult feedCharByChar(String text) {
        ThinkTagStreamFilter filter = new ThinkTagStreamFilter();
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            filter.feed(String.valueOf(text.charAt(i)),
                    s -> content.append(s), s -> thinking.append(s));
        }
        filter.flush(content::append, thinking::append);
        return new FedResult(content, thinking);
    }

    @Test
    void 无标签文本原样透传() {
        FedResult r = feedCharByChar("普通回答内容，没有标签。");
        assertEquals("普通回答内容，没有标签。", r.content().toString());
        assertEquals("", r.thinking().toString());
    }

    @Test
    void 完整think区间剥离到思考通道() {
        FedResult r = feedCharByChar("<think>内部推理过程</think>最终回答正文");
        assertEquals("最终回答正文", r.content().toString());
        assertEquals("内部推理过程", r.thinking().toString());
    }

    @Test
    void 标签跨增量切分_回归用例() {
        ThinkTagStreamFilter filter = new ThinkTagStreamFilter();
        List<String> content = new ArrayList<>();
        List<String> thinking = new ArrayList<>();
        // "<think>" 切成三段
        filter.feed("<th", content::add, thinking::add);
        filter.feed("ink>", content::add, thinking::add);
        filter.feed("推理片段", content::add, thinking::add);
        // "</think>" 切成两段
        filter.feed("</thin", content::add, thinking::add);
        filter.feed("k>答案", content::add, thinking::add);
        filter.flush(content::add, thinking::add);
        assertEquals("推理片段", String.join("", thinking));
        assertEquals("答案", String.join("", content));
    }

    @Test
    void 多个think区间() {
        FedResult r = feedCharByChar("<think>第一段</think>中间<think>第二段</think>结尾");
        assertEquals("中间结尾", r.content().toString());
        assertEquals("第一段第二段", r.thinking().toString());
    }

    @Test
    void 未闭合think按思考处理() {
        FedResult r = feedCharByChar("<think>只有开头没有闭合");
        assertEquals("", r.content().toString());
        assertEquals("只有开头没有闭合", r.thinking().toString());
    }

    @Test
    void 数学比较符不应误吞() {
        // "<" 后非 think 标签：悬挂缓冲应随后自愈放出
        FedResult r = feedCharByChar("当 a<b 且 b<c 时成立");
        assertEquals("当 a<b 且 b<c 时成立", r.content().toString());
    }

    @Test
    void 非流式strip兜底() {
        assertEquals("回答", ThinkTagStreamFilter.strip("<think>推理</think>回答"));
        assertNull(ThinkTagStreamFilter.strip(null));
        assertEquals("无标签", ThinkTagStreamFilter.strip("无标签"));
        // 未闭合：开标签后全部丢弃（与流式语义一致）
        assertEquals("", ThinkTagStreamFilter.strip("<think>未闭合"));
    }

    @Test
    void 大块增量一次喂入() {
        ThinkTagStreamFilter filter = new ThinkTagStreamFilter();
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        filter.feed("<think>短推理</think>\n\n**最终回答**\n\n- 要点1\n- 要点2",
                content::append, thinking::append);
        filter.flush(content::append, thinking::append);
        assertEquals("\n\n**最终回答**\n\n- 要点1\n- 要点2", content.toString());
        assertEquals("短推理", thinking.toString());
    }

    @Test
    void flush应放行悬挂的标签前缀尾字_防丢失() {
        ThinkTagStreamFilter filter = new ThinkTagStreamFilter();
        List<String> content = new ArrayList<>();
        // 回答以 "<" 结尾："<" 被当作潜在标签前缀扣住，flush 必须归还，不能丢字符
        filter.feed("检索完成，详见附录<", content::add, s -> {});
        assertEquals("检索完成，详见附录", String.join("", content));
        filter.flush(content::add, s -> {});
        assertEquals("检索完成，详见附录<", String.join("", content));
    }

    @Test
    void reset应清除未闭合的think状态_防跨迭代污染_回归用例() {
        ThinkTagStreamFilter filter = new ThinkTagStreamFilter();
        List<String> content = new ArrayList<>();
        List<String> thinking = new ArrayList<>();
        // 第一次调用：未闭合 think → 状态滞留
        filter.feed("<think>未闭合的推理", content::add, thinking::add);
        assertTrue(filter.hasPending());
        filter.flush(content::add, thinking::add);
        // 边界复位：下一次调用的正文不得再被路由进思考通道
        filter.reset();
        assertFalse(filter.hasPending());
        filter.feed("第二次调用的正常回答", content::add, thinking::add);
        assertEquals("第二次调用的正常回答", String.join("", content));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}
