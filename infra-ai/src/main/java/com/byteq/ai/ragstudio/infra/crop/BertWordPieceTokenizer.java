package com.byteq.ai.ragstudio.infra.crop;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT WordPiece 分词器（适用于 bge-small-zh-v1.5 等 BERT 系模型）
 * <p>
 * 从模型目录读取 vocab.txt，按 BertTokenizer 语义完成
 * 基础分词（空白切分、中文按字切分、标点切分、WordPiece 子词切分）与
 * 编码（CLS/SEP/PAD + attention mask + token_type_ids）。
 * 配置与模型 tokenizer_config.json 对齐：do_lower_case=false、tokenize_chinese_chars=true、max_length=512。
 * </p>
 */
public class BertWordPieceTokenizer {

    private final Map<String, Integer> vocab;
    private final int clsId;
    private final int sepId;
    private final int padId;
    private final int unkId;
    private final int maxLength;

    private static final char[] PUNCT_CHARS = new char[]{
            '!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
            ':', ';', '<', '=', '>', '?', '@', '[', '\\', ']', '^', '_', '`', '{', '|', '}', '~'};

    public BertWordPieceTokenizer(Path vocabFile, int maxLength) {
        this.maxLength = maxLength;
        this.vocab = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(vocabFile, StandardCharsets.UTF_8)) {
            String line;
            int id = 0;
            while ((line = reader.readLine()) != null) {
                vocab.put(line.trim(), id++);
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载 vocab.txt 失败: " + vocabFile, e);
        }
        this.clsId = vocab.getOrDefault("[CLS]", 101);
        this.sepId = vocab.getOrDefault("[SEP]", 102);
        this.padId = vocab.getOrDefault("[PAD]", 0);
        this.unkId = vocab.getOrDefault("[UNK]", 100);
    }

    /**
     * 将一批文本编码为模型输入张量
     *
     * @param texts 待编码文本列表
     * @return 编码结果（inputIds、attentionMask、tokenTypeIds 的二维 long 数组，形状 [n, maxLength]）
     */
    public Encoding encodeBatch(List<String> texts) {
        int n = texts.size();
        long[][] inputIds = new long[n][maxLength];
        long[][] attentionMask = new long[n][maxLength];
        long[][] tokenTypeIds = new long[n][maxLength];

        for (int i = 0; i < n; i++) {
            int[] ids = encodeSingle(texts.get(i));
            int len = Math.min(ids.length, maxLength - 2);
            inputIds[i][0] = clsId;
            for (int j = 0; j < len; j++) {
                inputIds[i][j + 1] = ids[j];
            }
            inputIds[i][len + 1] = sepId;
            for (int j = 0; j <= len + 1; j++) {
                attentionMask[i][j] = 1;
            }
        }

        return new Encoding(inputIds, attentionMask, tokenTypeIds);
    }

    /** 单个文本编码为 token id 序列（不含 CLS/SEP/PAD） */
    private int[] encodeSingle(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : basicTokenize(text)) {
            for (String subword : wordPieceTokenize(token)) {
                tokens.add(subword);
            }
        }
        int[] ids = new int[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            ids[i] = vocab.getOrDefault(tokens.get(i), unkId);
        }
        return ids;
    }

    /** 基础分词：空白切分 + 中文按字切分 + 标点切分 */
    private List<String> basicTokenize(String text) {
        List<String> result = new ArrayList<>();
        for (String token : text.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (isChineseChar(c) || isPunct(c)) {
                    if (current.length() > 0) {
                        result.add(current.toString());
                        current.setLength(0);
                    }
                    result.add(String.valueOf(c));
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                result.add(current.toString());
            }
        }
        return result;
    }

    /** WordPiece 子词切分：最长匹配 + ## 前缀 */
    private List<String> wordPieceTokenize(String token) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < token.length()) {
            int end = token.length();
            String current = null;
            while (start < end) {
                String sub = (start == 0 ? token.substring(0, end) : "##" + token.substring(start, end));
                if (vocab.containsKey(sub)) {
                    current = sub;
                    break;
                }
                end--;
            }
            if (current == null) {
                result.add(token);
                return result; // 无法切分则整体 UNK
            }
            result.add(current);
            start = end;
        }
        return result;
    }

    private boolean isChineseChar(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private boolean isPunct(char c) {
        for (char p : PUNCT_CHARS) {
            if (p == c) {
                return true;
            }
        }
        return false;
    }

    /** 编码结果 */
    public record Encoding(long[][] inputIds, long[][] attentionMask, long[][] tokenTypeIds) {}
}