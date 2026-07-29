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
