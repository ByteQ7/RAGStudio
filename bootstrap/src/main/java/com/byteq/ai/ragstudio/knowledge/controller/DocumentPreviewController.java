package com.byteq.ai.ragstudio.knowledge.controller;

import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 文档预览控制器
 * <p>
 * 生成有时效性的 S3 预签名 URL 用于浏览器内联预览文档。
 * 权限规则：
 * <ul>
 *   <li>admin → 可预览任何文档</li>
 *   <li>普通用户 → 只能预览自己创建的知识库下的文档</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DocumentPreviewController {

    private static final Duration PREVIEW_TTL = Duration.ofMinutes(5);

    private static final Set<String> TEXT_FILE_TYPES = Set.of("txt", "markdown", "md", "csv", "json", "xml", "yaml", "yml", "log");
    private static final Set<String> OFFICE_FILE_TYPES = Set.of("docx", "xlsx", "pptx", "odt", "ods", "odp");

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;

    private void checkPermission(KnowledgeDocumentDO doc) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());
        if (kb == null) {
            throw new ClientException("所属知识库不存在");
        }
        String role = UserContext.getRole();
        String username = UserContext.getUsername();
        if (!"admin".equals(role)) {
            if (!kb.getCreatedBy().equals(username)) {
                throw new ServiceException("无权预览该文档");
            }
        }
    }

    /**
     * 生成文档预览 URL
     * <p>
     * 文本文件通过 S3 response-content-type 覆盖确保 UTF-8 编码，
     * 二进制文件（PDF/图片）使用默认方式。
     * </p>
     */
    @PostMapping("/knowledge-base/docs/{docId}/preview")
    public Result<Map<String, Object>> preview(@PathVariable("docId") String docId) {
        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        if (doc == null) throw new ClientException("文档不存在");
        checkPermission(doc);

        if (doc.getFileUrl() == null || doc.getFileUrl().isBlank()) {
            throw new ServiceException("文档文件不存在，无法预览");
        }

        boolean isText = doc.getFileType() != null && TEXT_FILE_TYPES.contains(doc.getFileType());
        boolean isOffice = doc.getFileType() != null && OFFICE_FILE_TYPES.contains(doc.getFileType());
        String previewUrl;
        if (isText) {
            previewUrl = fileStorageService.generatePresignedGetUrl(doc.getFileUrl(), PREVIEW_TTL, "text/plain; charset=utf-8");
        } else {
            previewUrl = fileStorageService.generatePresignedGetUrl(doc.getFileUrl(), PREVIEW_TTL);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("previewUrl", previewUrl);
        result.put("contentUrl", isOffice ? "/knowledge-base/docs/" + docId + "/preview/file" : null);
        result.put("expiresIn", PREVIEW_TTL.toSeconds());
        result.put("fileName", doc.getDocName());
        result.put("fileType", doc.getFileType());
        result.put("fileSize", doc.getFileSize() != null ? doc.getFileSize() : 0);
        result.put("isText", isText);
        return Results.success(result);
    }

    /**
     * 获取文档原始文件内容（用于 Office 等需要客户端转换的格式）
     * <p>
     * 绕过 S3 预签名 URL 的 CORS 限制，通过后端代理返回文件内容。
     * 前端拿到 ArrayBuffer 后通过 mammoth/xlsx 等库转换渲染。
     * </p>
     */
    @GetMapping("/knowledge-base/docs/{docId}/preview/file")
    public ResponseEntity<InputStreamResource> previewFile(@PathVariable("docId") String docId) {
        KnowledgeDocumentDO doc = documentMapper.selectById(docId);
        if (doc == null) return ResponseEntity.notFound().build();
        checkPermission(doc);

        if (doc.getFileUrl() == null || doc.getFileUrl().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            InputStream stream = fileStorageService.openStream(doc.getFileUrl());
            String contentType = resolveContentType(doc.getFileType());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            log.error("读取文档文件内容失败: docId={}", docId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private String resolveContentType(String fileType) {
        if (fileType == null) return "application/octet-stream";
        return switch (fileType) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "odt" -> "application/vnd.oasis.opendocument.text";
            case "ods" -> "application/vnd.oasis.opendocument.spreadsheet";
            case "odp" -> "application/vnd.oasis.opendocument.presentation";
            default -> "application/octet-stream";
        };
    }
}
