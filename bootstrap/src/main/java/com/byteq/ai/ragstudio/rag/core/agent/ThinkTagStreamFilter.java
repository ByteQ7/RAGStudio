package com.byteq.ai.ragstudio.rag.core.agent;

import java.util.function.Consumer;

/**
 * {@code <think>...</think>} 标签流式剥离器
 * <p>
 * 部分模型（OpenAI 兼容网关上的 Qwen3 / DeepSeek-R1-distill 等）不通过
 * reasoning_content 通道下发思维链，而是在 content 中内联输出
 * {@code <think>} 标签包裹的推理文本。若不剥离，思维链将原样进入用户可见正文。
 * </p>
 * <p>
 * 有状态设计：标签可能被切分到多个增量中（如 {@code "<th"} + {@code "ink>"}），
 * 内部缓冲悬挂的标签前缀片段直到可判定；think 区间内的增量改道 thinkOut，
 * 其余透传 contentOut。支持一段输出中出现多个独立 think 区间。
 * </p>
 */
public final class ThinkTagStreamFilter {

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    /** 是否处于 think 区间内 */
    private boolean insideThink;
    /** 悬挂缓冲：可能是"待判定正文/思考内容 + 不完整标签前缀"，跨增量持有 */
    private final StringBuilder pending = new StringBuilder();

    /**
     * 输入一个内容增量，拆分输出：
     *
     * @param delta      模型文本增量
     * @param contentOut 正文消费者（非 think 区间文本）
     * @param thinkOut   思考消费者（think 区间文本）
     */
    public void feed(String delta, Consumer<String> contentOut, Consumer<String> thinkOut) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        pending.append(delta);
        drain(contentOut, thinkOut);
    }

    /**
     * 流结束兜底：清空悬挂缓冲。未闭合的 think 区间按思考内容处理。
     */
    public void flush(Consumer<String> contentOut, Consumer<String> thinkOut) {
        if (pending.length() == 0) {
            return;
        }
        String rest = pending.toString();
        pending.setLength(0);
        if (insideThink) {
            thinkOut.accept(rest);
        } else {
            contentOut.accept(rest);
        }
    }

    /**
     * 复位到初始状态（丢弃一切悬挂内容）。
     * 用于模型调用边界：未闭合的 think 区间不应跨调用污染下一次迭代的正文路由。
     * 需保留悬挂内容时先 {@link #flush} 再调用本方法。
     */
    public void reset() {
        pending.setLength(0);
        insideThink = false;
    }

    /** 当前是否持有未输出的悬挂字节或处于 think 区间内 */
    public boolean hasPending() {
        return pending.length() > 0 || insideThink;
    }

    /** 非流式兜底：一次性剥除文本中所有完整 think 标签对，保留正文 */
    public static String strip(String text) {
        if (text == null || !text.contains("<")) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int idx = 0;
        while (idx < text.length()) {
            int open = text.indexOf(OPEN_TAG, idx);
            if (open < 0) {
                out.append(text.substring(idx));
                break;
            }
            out.append(text, idx, open);
            int close = text.indexOf(CLOSE_TAG, open + OPEN_TAG.length());
            if (close < 0) {
                // 未闭合：丢弃开标签及其后全部内容（与流式语义一致）
                break;
            }
            idx = close + CLOSE_TAG.length();
        }
        return out.toString();
    }

    /**
     * 处理悬挂缓冲：循环查找当前等待的标签；找不到时仅保留
     * 可能构成标签前缀的最短尾部悬挂，其余立即输出。
     */
    private void drain(Consumer<String> contentOut, Consumer<String> thinkOut) {
        String waitingTag = insideThink ? CLOSE_TAG : OPEN_TAG;
        Consumer<String> plainOut = insideThink ? thinkOut : contentOut;

        int searchFrom = 0;
        while (true) {
            String buf = pending.toString();
            int tagIdx = buf.indexOf(waitingTag, searchFrom);
            if (tagIdx >= 0) {
                // 标签前文本按当前区间归属输出
                if (tagIdx > 0) {
                    plainOut.accept(buf.substring(0, tagIdx));
                }
                pending.delete(0, tagIdx + waitingTag.length());
                insideThink = !insideThink;
                waitingTag = insideThink ? CLOSE_TAG : OPEN_TAG;
                plainOut = insideThink ? thinkOut : contentOut;
                searchFrom = 0;
                continue;
            }
            // 无完整标签：检查尾部是否悬挂着目标标签的前缀（跨增量切分保护）
            int hold = hangingPrefixLength(pending, waitingTag);
            int emitEnd = pending.length() - hold;
            if (emitEnd > 0) {
                plainOut.accept(pending.substring(0, emitEnd));
                pending.delete(0, emitEnd);
            }
            return;
        }
    }

    /**
     * 计算缓冲尾部与目标标签前缀重叠的最大长度。
     * 仅当重叠从缓冲末尾开始（即缓冲最后 hold 个字符 == 标签前 hold 个字符）时返回，
     * 否则返回 0——避免把正常正文误扣在缓冲里。
     */
    private static int hangingPrefixLength(StringBuilder buf, String tag) {
        int maxHold = Math.min(tag.length() - 1, buf.length());
        for (int hold = maxHold; hold > 0; hold--) {
            if (tag.startsWith(buf.substring(buf.length() - hold))) {
                return hold;
            }
        }
        return 0;
    }
}
