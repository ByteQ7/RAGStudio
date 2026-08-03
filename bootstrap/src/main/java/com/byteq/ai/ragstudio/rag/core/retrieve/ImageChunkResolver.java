package com.byteq.ai.ragstudio.rag.core.retrieve;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageChunkResolver {

    private final FileStorageService fileStorageService;

    /**
     * 为图片 Chunk 预解析 rerank 可用的图片地址（写入 metadata 的 {@code rerank_image_url}）：
     * <ul>
     *   <li>已是 http(s)/data URI 的直接沿用</li>
     *   <li>s3:// 内部 URI 下载并编码为 base64 data URI（百炼 rerank 无法访问内网/内部存储地址）</li>
     *   <li>下载失败的跳过（rerank 阶段将自动排除该 Chunk）</li>
     * </ul>
     *
     * @param chunks 待重排序的候选 Chunk 列表（原地修改 image chunk 的 metadata）
     */
    public void enrichRerankImageUrls(List<RetrievedChunk> chunks) {
        for (RetrievedChunk chunk : chunks) {
            if (!chunk.isImage()) continue;
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;

            Object imageUrl = meta.get("image_url");
            if (!(imageUrl instanceof String url) || url.isBlank()) continue;
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
                meta.put("rerank_image_url", url);
                continue;
            }
            try {
                String dataUri = downloadAndEncode(url);
                if (dataUri != null) {
                    meta.put("rerank_image_url", dataUri);
                }
            } catch (Exception e) {
                log.warn("解析 rerank 图像块失败: chunkId={}, url={}", chunk.getId(), url, e);
            }
        }
    }

    public List<String> resolve(List<RetrievedChunk> chunks) {
        List<String> imageDataUris = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (!chunk.isImage()) continue;
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;

            Object imageUrl = meta.get("image_url");
            if (!(imageUrl instanceof String url) || url.isBlank()) continue;

            try {
                String dataUri = downloadAndEncode(url);
                if (dataUri != null) {
                    imageDataUris.add(dataUri);
                }
            } catch (Exception e) {
                log.warn("解析图像块失败: chunkId={}, url={}", chunk.getId(), url, e);
            }
        }
        log.info("ImageChunkResolver: 共 {} 个 chunk, 解析到 {} 个图片 data URI",
                chunks.size(), imageDataUris.size());
        return imageDataUris;
    }

    private String downloadAndEncode(String s3Url) {
        try (InputStream is = fileStorageService.openStream(s3Url)) {
            byte[] bytes = is.readAllBytes();
            String mime = detectMime(s3Url);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + mime + ";base64," + base64;
        } catch (Exception e) {
            log.warn("从 S3 下载图片失败: {}", s3Url, e);
            return null;
        }
    }

    private String detectMime(String key) {
        if (key == null) return "image/jpeg";
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}
