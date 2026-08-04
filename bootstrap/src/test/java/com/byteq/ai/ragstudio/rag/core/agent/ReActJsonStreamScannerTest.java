package com.byteq.ai.ragstudio.rag.core.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ReAct JSON 流式扫描器单元测试：
 * 验证 thought / final_answer 的增量提取、转义还原、chunk 边界与字段顺序兜底
 */
class ReActJsonStreamScannerTest {

    /** 模拟按任意切分点流式投喂，返回投喂过程中逐步 drain 出的内容 */
    private record FeedResult(String thought, String answer) {}

    private FeedResult feed(String json, int chunkSize) {
        AgentLoop.ReActJsonStreamScanner scanner = new AgentLoop.ReActJsonStreamScanner();
        StringBuilder thought = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < json.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, json.length());
            scanner.onContent(json.substring(i, end));
            String t = scanner.drainThought();
            if (t != null) thought.append(t);
            String a = scanner.drainAnswer();
            if (a != null) answer.append(a);
        }
        scanner.onComplete();
        String t = scanner.drainThought();
        if (t != null) thought.append(t);
        String a = scanner.drainAnswer();
        if (a != null) answer.append(a);
        return new FeedResult(thought.toString(), answer.toString());
    }

    @Test
    void extractThoughtAndAnswer_standardOrder() {
        String json = "{\"thought\":\"用户询问年假天数\",\"plan\":\"检索\",\"action\":\"finish\",\"final_answer\":\"公司年假为 5 天\"}";
        FeedResult r = feed(json, 3);
        assertEquals("用户询问年假天数", r.thought());
        assertEquals("公司年假为 5 天", r.answer());
    }

    @Test
    void toolCallJson_shouldNotStreamAnswer() {
        String json = "{\"thought\":\"需要检索\",\"action\":\"rag_search\",\"action_input\":{\"query\":\"年假 天数\",\"topK\":5}}";
        FeedResult r = feed(json, 4);
        assertEquals("需要检索", r.thought());
        assertEquals("", r.answer());
    }

    @Test
    void answerBeforeAction_shouldFallbackToReplay() {
        // 字段顺序异常：final_answer 先于 action → 不透出（调用方回退为完成后回放）
        String json = "{\"thought\":\"t\",\"final_answer\":\"答案\",\"action\":\"finish\"}";
        FeedResult r = feed(json, 2);
        assertEquals("t", r.thought());
        assertEquals("", r.answer());
    }

    @Test
    void escapeHandling() {
        String json = "{\"thought\":\"引号 \\\"和换行\\n\",\"action\":\"finish\",\"final_answer\":\"a\\\"b\\nc\\td\\\\e\"}";
        FeedResult r = feed(json, 2);
        assertEquals("引号 \"和换行\n", r.thought());
        assertEquals("a\"b\nc\td\\e", r.answer());
    }

    @Test
    void unicodeEscapeHandling() {
        String json = "{\"thought\":\"t\",\"action\":\"finish\",\"final_answer\":\"\\u4e2d\\u6587\"}";
        FeedResult r = feed(json, 1);
        assertEquals("中文", r.answer());
    }

    @Test
    void chunkBoundary_splitsInsideTokens() {
        String json = "{\"thought\":\"思考内容一二三\",\"action\":\"finish\",\"final_answer\":\"回答内容四五六\"}";
        for (int chunk : new int[]{1, 2, 5, 17}) {
            FeedResult r = feed(json, chunk);
            assertEquals("思考内容一二三", r.thought(), "chunk=" + chunk);
            assertEquals("回答内容四五六", r.answer(), "chunk=" + chunk);
        }
    }

    @Test
    void emptyAnswer_shouldNotStream() {
        String json = "{\"thought\":\"t\",\"action\":\"finish\",\"final_answer\":\"\"}";
        FeedResult r = feed(json, 3);
        assertEquals("", r.answer());
    }

    @Test
    void answerContainingActionLikeText_shouldNotAffectExtraction() {
        String json = "{\"thought\":\"用户说 action 相关\",\"action\":\"finish\",\"final_answer\":\"包含 \\\"action\\\": \\\"finish\\\" 的文本\"}";
        FeedResult r = feed(json, 3);
        assertEquals("包含 \"action\": \"finish\" 的文本", r.answer());
    }

    @Test
    void uppercaseFinish_shouldConfirm() {
        String json = "{\"thought\":\"t\",\"action\":\"FINISH\",\"final_answer\":\"答\"}";
        FeedResult r = feed(json, 2);
        assertEquals("答", r.answer());
    }

    @Test
    void nonStringFinalAnswer_shouldNotStream() {
        String json = "{\"thought\":\"t\",\"action\":\"finish\",\"final_answer\":123}";
        FeedResult r = feed(json, 2);
        assertEquals("", r.answer());
    }

    @Test
    void nullStream_shouldProduceNothing() {
        AgentLoop.ReActJsonStreamScanner scanner = new AgentLoop.ReActJsonStreamScanner();
        assertNull(scanner.drainThought());
        assertNull(scanner.drainAnswer());
        scanner.onContent("");
        assertNull(scanner.drainThought());
    }

    @Test
    void multipleStreamedDrains_accumulateCorrectly() {
        String json = "{\"thought\":\"甲\",\"thought\":\"乙\",\"action\":\"finish\",\"final_answer\":\"丙丁\"}";
        // 重复 key：按顺序分别透出
        List<String> thoughtChunks = new ArrayList<>();
        AgentLoop.ReActJsonStreamScanner scanner = new AgentLoop.ReActJsonStreamScanner();
        for (int i = 0; i < json.length(); i += 2) {
            scanner.onContent(json.substring(i, Math.min(i + 2, json.length())));
            String t = scanner.drainThought();
            if (t != null) thoughtChunks.add(t);
        }
        assertEquals("甲乙", String.join("", thoughtChunks));
    }
}
