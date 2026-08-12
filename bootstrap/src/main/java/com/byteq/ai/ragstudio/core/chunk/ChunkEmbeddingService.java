package com.byteq.ai.ragstudio.core.chunk;

import com.byteq.ai.ragstudio.core.enums.CoreErrorCode;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块嵌入服务
 * 职责单一：为已切分的文本块/图像块调用嵌入 API 生成向量
 */
@Service
@RequiredArgsConstructor
public class ChunkEmbeddingService {

    private final EmbeddingService embeddingService;
    private final MultimodalEmbeddingService multimodalEmbeddingService;

    /**
     * 为分块列表计算嵌入向量
     * <p>自动根据 contentType 分流：TEXT 块走文本嵌入，IMAGE 块走图像嵌入。</p>
     *
     * @param chunks         已切分的文本块/图像块（embedding 字段将被原地填充）
     * @param embeddingModel 嵌入模型 ID，null 时使用系统默认模型
     * @param dimension      KB 配置的向量维度，用于请求模型输出指定维度（null 时使用模型默认）
     */
    public void embed(List<VectorChunk> chunks, String embeddingModel, Integer dimension) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<VectorChunk> textChunks = new ArrayList<>();
        List<VectorChunk> imageChunks = new ArrayList<>();
        for (VectorChunk chunk : chunks) {
            if (chunk.isImage()) {
                imageChunks.add(chunk);
            } else {
                textChunks.add(chunk);
            }
        }

        if (!textChunks.isEmpty()) {
            embedTextChunks(textChunks, embeddingModel, dimension);
        }
        if (!imageChunks.isEmpty()) {
            embedImageChunks(imageChunks, embeddingModel, dimension);
        }
    }

    public void embed(List<VectorChunk> chunks, String embeddingModel) {
        embed(chunks, embeddingModel, null);
    }

    private void embedTextChunks(List<VectorChunk> chunks, String embeddingModel, Integer dimension) {
        if (chunks.stream().allMatch(c -> c.getEmbedding() != null && c.getEmbedding().length > 0)) {
            return;
        }
        List<String> texts = chunks.stream()
                .map(c -> c.getContent() == null ? "" : c.getContent())
                .toList();
        List<List<Float>> vectors;
        if (StringUtils.hasText(embeddingModel)) {
            vectors = dimension != null && dimension > 0
                    ? embeddingService.embedBatch(texts, embeddingModel, dimension)
                    : embeddingService.embedBatch(texts, embeddingModel);
        } else {
            vectors = embeddingService.embedBatch(texts);
        }
        applyEmbeddings(chunks, vectors);
    }

    private void embedTextChunks(List<VectorChunk> chunks, String embeddingModel) {
        embedTextChunks(chunks, embeddingModel, null);
    }

    private void embedImageChunks(List<VectorChunk> chunks, String embeddingModel, Integer dimension) {
        if (chunks.stream().allMatch(c -> c.getEmbedding() != null && c.getEmbedding().length > 0)) {
            return;
        }
        List<VectorChunk> validChunks = chunks.stream()
                .filter(c -> c.getMetadata().get("image_base64") instanceof String)
                .toList();
        if (validChunks.isEmpty()) {
            return;
        }
        List<String> imageBase64List = validChunks.stream()
                .map(c -> (String) c.getMetadata().get("image_base64"))
                .toList();
        List<List<Float>> vectors = StringUtils.hasText(embeddingModel)
                ? multimodalEmbeddingService.embedImages(imageBase64List, embeddingModel, dimension)
                : multimodalEmbeddingService.embedImages(imageBase64List, null, dimension);
        applyEmbeddings(validChunks, vectors);

        // 嵌入完成后清除 image_base64，避免存入数据库（检索时通过 S3 URL 重新下载编码）
        for (VectorChunk chunk : chunks) {
            chunk.getMetadata().remove("image_base64");
        }
    }

    private void applyEmbeddings(List<VectorChunk> chunks, List<List<Float>> vectors) {
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new ServiceException("Embedding result size mismatch", CoreErrorCode.EMBEDDING_RESULT_MISMATCH);
        }
        for (int i = 0; i < chunks.size(); i++) {
            List<Float> row = vectors.get(i);
            if (row == null) {
                throw new ServiceException("Embedding result missing, index: " + i, CoreErrorCode.EMBEDDING_RESULT_MISMATCH);
            }
            float[] vec = new float[row.size()];
            for (int j = 0; j < row.size(); j++) {
                vec[j] = row.get(j);
            }
            chunks.get(i).setEmbedding(vec);
        }
    }
}
