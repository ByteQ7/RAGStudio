package com.byteq.ai.ragstudio.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageChunkResolver {

    /** Redis 缓存 key 前缀（s3:// 图片 → base64 data URI，图片内容不可变，幂等可缓存） */
    private static final String CACHE_KEY_PREFIX = "rag:img:datauri:";

    /** 缓存 TTL（小时） */
    private static final int CACHE_TTL_HOURS = 6;

    private final FileStorageService fileStorageService;
    private final RedissonClient redissonClient;
    private final Executor ragRetrievalExecutor;

    /**
     * 为图片 Chunk 预解析 rerank 可用的图片地址（写入 metadata 的 {@code rerank_image_url}）：
     * <ul>
     *   <li>已是 http(s)/data URI 的直接沿用</li>
     *   <li>s3:// 内部 URI 并行下载并编码为 base64 data URI（百炼 rerank 无法访问内网/内部存储地址）</li>
     *   <li>下载失败的跳过（rerank 阶段将自动排除该 Chunk）</li>
     * </ul>
     *
     * @param chunks 待重排序的候选 Chunk 列表（原地修改 image chunk 的 metadata）
     */
    public void enrichRerankImageUrls(List<RetrievedChunk> chunks) {
        List<String> s3Urls = collectS3Urls(chunks);
        Map<String, String> resolved = resolveAllParallel(s3Urls);
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
            String dataUri = resolved.get(url);
            if (dataUri != null) {
                meta.put("rerank_image_url", dataUri);
            }
        }
    }

    public List<String> resolve(List<RetrievedChunk> chunks) {
        List<String> imageDataUris = new ArrayList<>();
        // 已 http(s)/data URI 的图片直接沿用
        for (RetrievedChunk chunk : chunks) {
            if (!chunk.isImage()) continue;
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;
            Object imageUrl = meta.get("image_url");
            if (!(imageUrl instanceof String url) || url.isBlank()) continue;
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
                imageDataUris.add(url);
            }
        }

        // s3:// 图片并行下载（含 Redis 缓存：rerank 阶段已下载过的直接命中）
        List<String> s3Urls = collectS3Urls(chunks);
        Map<String, String> resolved = resolveAllParallel(s3Urls);
        for (RetrievedChunk chunk : chunks) {
            if (!chunk.isImage()) continue;
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;
            Object imageUrl = meta.get("image_url");
            if (!(imageUrl instanceof String url) || url.isBlank()) continue;
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
                continue;
            }
            String dataUri = resolved.get(url);
            if (dataUri != null) {
                imageDataUris.add(dataUri);
            }
        }
        log.info("ImageChunkResolver: 共 {} 个 chunk, 解析到 {} 个图片 data URI",
                chunks.size(), imageDataUris.size());
        return imageDataUris;
    }

    /** 收集 chunk 列表中需要走 S3 下载的图片 URL（去重） */
    private List<String> collectS3Urls(List<RetrievedChunk> chunks) {
        List<String> urls = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (!chunk.isImage()) continue;
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;
            Object imageUrl = meta.get("image_url");
            if (!(imageUrl instanceof String url) || url.isBlank()) continue;
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
                continue;
            }
            if (!urls.contains(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    /**
     * 并行下载并编码一组 s3 图片为 base64 data URI：
     * <ul>
     *   <li>先查 Redis 缓存（rerank 与最终上下文两处使用不再重复下载）</li>
     *   <li>未命中部分并行下载，全部完成后返回</li>
     * </ul>
     */
    private Map<String, String> resolveAllParallel(List<String> s3Urls) {
        if (s3Urls.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new ConcurrentHashMap<>();
        List<String> misses = new ArrayList<>();
        for (String url : s3Urls) {
            String cached = readCache(url);
            if (cached != null) {
                result.put(url, cached);
            } else {
                misses.add(url);
            }
        }
        if (misses.isEmpty()) {
            return result;
        }
        List<CompletableFuture<Void>> futures = misses.stream()
                .map(url -> CompletableFuture.runAsync(() -> {
                    String dataUri = downloadAndEncode(url);
                    if (dataUri != null) {
                        result.put(url, dataUri);
                    }
                }, ragRetrievalExecutor))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("并行下载图片中断: {}", e.getMessage());
        }
        return result;
    }

    private String downloadAndEncode(String s3Url) {
        try (InputStream is = fileStorageService.openStream(s3Url)) {
            byte[] bytes = is.readAllBytes();
            String mime = detectMime(s3Url);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String dataUri = "data:" + mime + ";base64," + base64;
            writeCache(s3Url, dataUri);
            return dataUri;
        } catch (Exception e) {
            log.warn("从 S3 下载图片失败: {}", s3Url, e);
            return null;
        }
    }

    private String readCache(String s3Url) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(cacheKey(s3Url));
            return bucket.get();
        } catch (Exception e) {
            log.debug("图片 data URI 缓存读取失败，降级直下: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String s3Url, String dataUri) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(cacheKey(s3Url));
            bucket.set(dataUri, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("图片 data URI 缓存写入失败，忽略: {}", e.getMessage());
        }
    }

    private String cacheKey(String s3Url) {
        return CACHE_KEY_PREFIX + SecureUtil.sha1(s3Url);
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
