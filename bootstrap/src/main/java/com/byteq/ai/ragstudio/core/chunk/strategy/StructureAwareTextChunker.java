package com.byteq.ai.ragstudio.core.chunk.strategy;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.core.chunk.ChunkingMode;
import com.byteq.ai.ragstudio.core.chunk.ChunkingOptions;
import com.byteq.ai.ragstudio.core.chunk.ChunkingStrategy;
import com.byteq.ai.ragstudio.core.chunk.TextBoundaryOptions;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构感知分块器（Markdown 友好版）
 * <p>
 * 基于文档结构的分块策略，在保留文档原有结构的前提下进行智能分块。
 * 与 {@link FixedSizeTextChunker} 不同，此分块器不会在任意位置截断文本，
 * 而是识别文本中的结构块边界，在"块"的自然边界处进行切分。
 * </p>
 * <p>
 * 块类型识别：
 * <ul>
 *   <li>Heading（标题）：以 # 开头的 Markdown 标题行，作为独立块</li>
 *   <li>Paragraph（段落）：空行分隔的连续文本，自动合并段落内多行</li>
 *   <li>CodeFence（代码围栏）：``` 包围的代码块，保持完整性</li>
 *   <li>Atomic（原子行）：图片 ![]() 和链接 []() 行，作为独立块</li>
 * </ul>
 * </p>
 * <p>
 * 分块算法：
 * <ol>
 *   <li>线性扫描文本，将文本按结构类型分割为块（Block）序列</li>
 *   <li>使用 min/target/max 三级预算控制，将块打包为合适的文本块</li>
 *   <li>仅在块边界处切分，绝不破坏块内完整性</li>
 *   <li>支持可选的 block-level overlap（复制上一个文本块的尾部内容）</li>
 *   <li>末尾小文本块自动向前合并，避免产生过短的孤立文本块</li>
 * </ol>
 * </p>
 * <p>
 * 适用场景：Markdown 格式文档、结构化文本、需要保留章节完整性的文档。
 * </p>
 *
 * @see FixedSizeTextChunker 固定大小分块器
 */
@Component
public class StructureAwareTextChunker implements ChunkingStrategy {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^```.*$");
    private static final Pattern ATOMIC_IMAGE = Pattern.compile("^!\\[[^]]*]\\([^)]+\\)(?:\\s*\"[^\"]*\")?\\s*$");
    private static final Pattern ATOMIC_LINK = Pattern.compile("^\\[[^]]+]\\([^)]+\\)\\s*$");

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.STRUCTURE_AWARE;
    }

    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions config) {
        if (StrUtil.isBlank(text)) return List.of();

        // 统一行尾：Windows \r\n → \n，老 Mac \r → \n，避免 \r 残留导致空行/标题识别失败
        text = text.replace("\r\n", "\n").replace("\r", "\n");

        TextBoundaryOptions opts = (TextBoundaryOptions) config;
        int effectiveTarget = opts.targetChars();
        int effectiveMax = opts.maxChars();
        int effectiveMin = opts.minChars();
        int effectiveOverlap = opts.overlapChars();

        // 1) 扫描成“块”（记录原文的 start/end 下标，确保输出 substring 完全等于原文）
        List<Block> blocks = segmentToBlocks(text);

        if (blocks.isEmpty()) {
            VectorChunk chunk = VectorChunk.builder()
                    .content(text)
                    .index(0)
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build();
            return List.of(chunk); // 极端兜底：整体作为一个块
        }

        // 2) 依据 min/target/max 打包成 chunk（只在块边界切分）
        // 当 overlap > 0 时，为 overlap 预留容量，避免拼接后超出 maxChars
        int packMax = effectiveOverlap > 0 ? effectiveMax - effectiveOverlap : effectiveMax;
        int packTarget = effectiveOverlap > 0 ? effectiveTarget - effectiveOverlap : effectiveTarget;
        List<int[]> ranges = packBlocksToChunks(blocks, text.length(), effectiveMin, packTarget, packMax);

        // 3)（可选）加入重叠：为保持“只在块边界切分”，这里不在中间加重叠，若开启 overlap，仅复制“上一 chunk 的尾部全文子串”到下一 chunk 的开头
        List<VectorChunk> out = materialize(text, ranges, effectiveOverlap);

        // 编号从 0 递增
        for (int i = 0; i < out.size(); i++) {
            VectorChunk chunk = VectorChunk.builder()
                    .content(out.get(i).getContent())
                    .index(i)
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build();
            out.set(i, chunk);
        }
        return out;
    }

    // ----------- 块模型 -----------
    @Getter
    @ToString
    @AllArgsConstructor
    private static class Block {
        enum Kind {HEADING, CODE, ATOMIC, PARA}

        final Block.Kind kind;
        final int start;   // 在原文中的起始（含）
        final int end;     // 在原文中的结束（不含）
    }

    // ----------- 1) 线性扫描生成块 -----------
    // 块扫描流程:
    // 1. 逐行扫描文本，识别行类型（代码围栏、标题、原子行、空行、普通文本）
    // 2. 代码围栏（```）进入/退出 CODE 状态，整块保持完整
    // 3. 标题行和原子行（图片/链接）作为独立块
    // 4. 连续非空行合并为段落块，空行作为段落边界
    // 5. 最后合并尾部空白，避免产生空白块
    private List<Block> segmentToBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        int n = text.length();
        int pos = 0;

        boolean inFence = false;
        int fenceStart = -1;

        boolean inPara = false;
        int paraStart = -1;

        while (pos < n) {
            int lineEnd = indexOfNl(text, pos);
            // [pos, lineEnd) 不含换行字符；lineEndNl = 包含换行（若有）
            int lineEndNl = lineEnd < n && text.charAt(lineEnd) == '\n' ? lineEnd + 1 : lineEnd;
            String line = text.substring(pos, lineEnd);

            String trimmed = trimRightKeepLeft(line); // 不改左侧空白，保留原貌；右侧空白不影响判断

            if (!inFence && CODE_FENCE.matcher(trimmed).matches()) {
                // 先把正在积累的段落收尾
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                // 进入代码围栏
                inFence = true;
                fenceStart = pos;
                pos = lineEndNl;
                continue;
            }

            if (inFence) {
                // 直到遇到 fence 结束行
                if (CODE_FENCE.matcher(trimmed).matches()) {
                    // 包含结束 fence 行
                    blocks.add(new Block(Block.Kind.CODE, fenceStart, lineEndNl));
                    inFence = false;
                }
                pos = lineEndNl;
                continue;
            }

            // 空行 => 段落边界
            if (trimmed.isEmpty()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                // 空行本身并入前一块或下一块？——保持原貌：把空行并入前一块（若无前一块，则作为 0 长度过渡）
                pos = lineEndNl;
                continue;
            }

            // 标题/原子行（图片/链接）都作为独立块
            if (HEADING.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                blocks.add(new Block(Block.Kind.HEADING, pos, lineEndNl));
                pos = lineEndNl;
                continue;
            }
            if (ATOMIC_IMAGE.matcher(trimmed).matches() || ATOMIC_LINK.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                blocks.add(new Block(Block.Kind.ATOMIC, pos, lineEndNl));
                pos = lineEndNl;
                continue;
            }

            // 其他：并入当前段落
            if (!inPara) {
                inPara = true;
                paraStart = pos;
            }
            pos = lineEndNl;
        }

        // 收尾
        if (inFence) {
            // 未闭合 fence：将剩余部分作为 CODE（保持原样）
            blocks.add(new Block(Block.Kind.CODE, fenceStart, n));
        } else if (inPara) {
            blocks.add(new Block(Block.Kind.PARA, paraStart, n));
        }
        return coalesceTrailingBlanks(blocks, text);
    }

    // 合并“块尾部的若干空行”到块内部，避免单独产生空白块（保持原文不变，只是归属到前块）
    private List<Block> coalesceTrailingBlanks(List<Block> blocks, String text) {
        if (blocks.isEmpty()) return blocks;
        List<Block> out = new ArrayList<>();
        Block prev = blocks.get(0);
        for (int i = 1; i < blocks.size(); i++) {
            Block cur = blocks.get(i);
            if (isAllBlank(text, prev.end, cur.start)) {
                // 把中间空白并入 prev，但别丢掉 cur
                prev = new Block(prev.kind, prev.start, cur.start);
            }
            // 无论是否并入空白，prev 都该进结果，然后向前推进
            out.add(prev);
            prev = cur;
        }
        out.add(prev);
        return out;
    }

    // ----------- 2) 打包成 chunk（仅在块边界切） -----------
    // 块打包流程:
    // 1. 贪心地将连续的块累加到当前 chunk，直到超过 max 上限
    // 2. 若当前 chunk 小于 min 下限，则强制吸收下一个块（允许超限）
    // 3. 末尾 chunk 过小时尝试向前合并，避免产生孤立短块
    private List<int[]> packBlocksToChunks(List<Block> blocks, int textLen, int min, int target, int max) {
        List<int[]> ranges = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            int chunkStart = blocks.get(i).start;
            int chunkEnd = blocks.get(i).end; // 不含
            int size = chunkEnd - chunkStart;

            int j = i + 1;
            while (j < blocks.size()) {
                Block b = blocks.get(j);
                int afterAdd = (b.end - chunkStart); // 等同于 size + nextSize + 中间空白（已包含）

                if (afterAdd <= max) {
                    // 还能加
                    chunkEnd = b.end;
                    size = afterAdd;
                    j++;
                } else {
                    // 超过 max：若当前 size < min，则“忍一次超限”，把这个块也吸进去（保证不要太小）
                    if (size < min) {
                        chunkEnd = b.end;
                        size = afterAdd;
                        j++;
                    }
                    break;
                }
            }

            ranges.add(new int[]{chunkStart, chunkEnd});
            i = j;
        }

        // 若最后一个 chunk 明显过小，尝试与前一个合并（仍不跨越 max 过多）
        if (ranges.size() >= 2) {
            int[] last = ranges.get(ranges.size() - 1);
            if (last[1] - last[0] < Math.min(min, target / 2)) {
                int[] prev = ranges.get(ranges.size() - 2);
                if (last[1] - prev[0] <= max * 2) { // 放宽一下，尽量合并到可接受大小
                    prev[1] = last[1];
                    ranges.remove(ranges.size() - 1);
                }
            }
        }
        return ranges;
    }

    // ----------- 3) 物化为 Chunk，必要时追加 overlap（复制原文尾部） -----------
    // 将范围列表转为 VectorChunk 对象，若开启 overlap 则将上一块尾部文本拼接到当前块开头
    private List<VectorChunk> materialize(String text, List<int[]> ranges, int overlap) {
        if (ranges.isEmpty()) return List.of();
        List<VectorChunk> out = new ArrayList<>();
        String prevTail = null;

        for (int k = 0; k < ranges.size(); k++) {
            int s = ranges.get(k)[0];
            int e = ranges.get(k)[1];
            String body = text.substring(s, e);
            if (overlap > 0 && prevTail != null && !prevTail.isEmpty()) {
                body = prevTail + body;
            }

            VectorChunk chunk = VectorChunk.builder()
                    .content(body)
                    .index(k)
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build();
            out.add(chunk);

            // 计算下一块的 overlap 尾部（完全来自本 chunk 原文结尾）
            if (overlap > 0) {
                prevTail = tailByChars(text.substring(s, e), overlap);
            }
        }
        return out;
    }

    // ----------- 小工具 -----------
    // 从指定位置查找下一个换行符的位置，未找到则返回字符串长度
    private int indexOfNl(String s, int from) {
        int p = s.indexOf('\n', from);
        return p < 0 ? s.length() : p;
    }

    // 去除字符串右侧空白字符，保留左侧空白（避免改变段落缩进结构）
    private String trimRightKeepLeft(String s) {
        int r = s.length();
        while (r > 0 && Character.isWhitespace(s.charAt(r - 1)) && s.charAt(r - 1) != '\n' && s.charAt(r - 1) != '\r') {
            r--;
        }
        return s.substring(0, r);
    }

    // 判断指定范围 [from, to) 内是否全部为空白字符（空格、制表符、换行）
    private boolean isAllBlank(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!(c == ' ' || c == '\t' || c == '\r' || c == '\n')) return false;
        }
        return true;
    }

    // 取字符串末尾指定字符数的子串，用于生成 overlap 尾部
    private String tailByChars(String s, int n) {
        if (n <= 0) return "";
        int len = s.length();
        return len <= n ? s : s.substring(len - n);
    }
}
