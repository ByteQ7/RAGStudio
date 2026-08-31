package com.byteq.ai.ragstudio.rag.skillstore;

import com.byteq.ai.ragstudio.framework.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * SKILL 包读取服务：ZIP 包 / 磁盘目录 → 规范化文件集（相对路径 → 内容）
 * <p>
 * ZIP 是管理面的主要新增攻击面，此处集中实现安全读取：
 * <ul>
 *   <li>zip-slip：条目路径规范化，拒绝绝对路径、{@code ..} 越界、反斜杠与非法字符</li>
 *   <li>zip bomb：解压总量按 max-total-size 硬顶，文件数与单文件大小均有限制</li>
   <li>统一剥离包内唯一的根目录层（兼容"整个技能打一层同名文件夹"的常见打包方式）</li>
 *   <li>目录读取时跳过 {@code .} 开头的隐藏文件/目录</li>
 * </ul>
 */
@Slf4j
@Service
public class SkillPackageService {

    private static final int MAX_FILE_COUNT = 200;

    private final long maxFileSize;
    private final long maxTotalSize;

    public SkillPackageService(
            @Value("${rag.skills.max-file-size:20MB}") String maxFileSize,
            @Value("${rag.skills.max-total-size:64MB}") String maxTotalSize) {
        this.maxFileSize = parseBytes(maxFileSize);
        this.maxTotalSize = parseBytes(maxTotalSize);
    }

    /** 读取上传的 ZIP 包为规范化文件集 */
    public LinkedHashMap<String, byte[]> readZip(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ClientException("上传文件为空");
        }
        if (file.getSize() > maxTotalSize) {
            throw new ClientException("ZIP 包大小超过上限 " + maxTotalSize / 1024 / 1024 + "MB");
        }
        try (InputStream in = file.getInputStream()) {
            return readZipStream(in);
        } catch (IOException e) {
            throw new ClientException("读取 ZIP 包失败: " + e.getMessage());
        }
    }

    public LinkedHashMap<String, byte[]> readZipStream(InputStream in) throws IOException {
        LinkedHashMap<String, byte[]> raw = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = normalizeEntryPath(entry.getName());
                if (path == null) {
                    throw new ClientException("ZIP 包含非法条目路径，已拒绝: " + entry.getName());
                }
                if (isHidden(path)) {
                    log.info("跳过 ZIP 包中的隐藏条目: {}", path);
                    continue;
                }
                if (raw.containsKey(path)) {
                    throw new ClientException("ZIP 包含重复条目路径: " + path);
                }
                // 有界读取：单条目解压内容超限立即拒绝，避免超大压缩条目先把整个内容读进内存
                byte[] content = readBounded(zis, maxFileSize, path);
                total += content.length;
                if (total > maxTotalSize) {
                    throw new ClientException("解压总大小超过上限 " + maxTotalSize / 1024 / 1024 + "MB");
                }
                if (raw.size() >= MAX_FILE_COUNT) {
                    throw new ClientException("文件数超过上限 " + MAX_FILE_COUNT);
                }
                raw.put(path, content);
            }
        }
        if (raw.isEmpty()) {
            throw new ClientException("ZIP 包中没有可导入的文件");
        }
        return stripCommonRoot(raw);
    }

    /** 读取磁盘目录为规范化文件集（收编/迁移用），跳过隐藏文件与子目录下的隐藏条目 */
    public LinkedHashMap<String, byte[]> readDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new ClientException("目录不存在: " + dir);
        }
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                if (isHidden(rel)) {
                    continue;
                }
                byte[] content;
                try (InputStream in = Files.newInputStream(file)) {
                    content = readBounded(in, maxFileSize, rel);
                }
                total += content.length;
                if (total > maxTotalSize) {
                    throw new ClientException("目录总大小超过上限 " + maxTotalSize / 1024 / 1024 + "MB");
                }
                if (files.size() >= MAX_FILE_COUNT) {
                    throw new ClientException("文件数超过上限 " + MAX_FILE_COUNT);
                }
                files.put(rel, content);
            }
        } catch (IOException e) {
            throw new ClientException("读取目录失败: " + e.getMessage());
        }
        if (files.isEmpty()) {
            throw new ClientException("目录中没有可导入的文件");
        }
        return files;
    }

    /** 对在线编辑提交的文件集做限额校验（来源可信路径，无需再规范化） */
    public void enforceLimits(Map<String, byte[]> files) {
        if (files.size() > MAX_FILE_COUNT) {
            throw new ClientException("文件数超过上限 " + MAX_FILE_COUNT);
        }
        long total = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (e.getValue().length > maxFileSize) {
                throw new ClientException("文件 " + e.getKey() + " 大小超过单文件上限 " + maxFileSize / 1024 / 1024 + "MB");
            }
            total += e.getValue().length;
        }
        if (total > maxTotalSize) {
            throw new ClientException("总大小超过上限 " + maxTotalSize / 1024 / 1024 + "MB");
        }
    }

    /**
     * ZIP 条目路径规范化：非法返回 null。
     * 拒绝绝对路径、{@code ..} 与空路径段、反斜杠、冒号与控制字符。
     * 注意：{@code .} 开头的隐藏文件不在此拒绝（由 readZipStream/readDir 按需跳过），
     * 否则 macOS 打包的 ZIP（.DS_Store、__MACOSX/._x）会被整体误拒。
     */
    public static String normalizeEntryPath(String rawName) {
        if (rawName == null) {
            return null;
        }
        String path = rawName.replace('\\', '/');
        if (path.isBlank() || path.startsWith("/") || path.indexOf(':') >= 0) {
            return null;
        }
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                return null;
            }
        }
        return path;
    }

    /**
     * 有界读取：累计超过 limit 立即抛出，防止超大条目（如解压炸弹）先整体读入内存。
     */
    private static byte[] readBounded(InputStream in, long limit, String path) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > limit) {
                throw new ClientException("文件 " + path + " 大小超过单文件上限 " + limit / 1024 / 1024 + "MB");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /** 所有条目都在同一个根目录下时剥离该层（zip 常见打包方式） */
    private static LinkedHashMap<String, byte[]> stripCommonRoot(LinkedHashMap<String, byte[]> raw) {
        Set<String> firstSegments = new HashSet<>();
        boolean allNested = true;
        for (String path : raw.keySet()) {
            int slash = path.indexOf('/');
            if (slash < 0) {
                allNested = false;
                break;
            }
            firstSegments.add(path.substring(0, slash));
        }
        if (!allNested || firstSegments.size() != 1) {
            return raw;
        }
        String root = firstSegments.iterator().next() + "/";
        LinkedHashMap<String, byte[]> stripped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : raw.entrySet()) {
            stripped.put(e.getKey().substring(root.length()), e.getValue());
        }
        return stripped;
    }

    private static boolean isHidden(String relPath) {
        for (String seg : relPath.split("/")) {
            if (seg.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static long parseBytes(String value) {
        String v = value.trim().toUpperCase();
        long multiplier = 1;
        if (v.endsWith("KB")) {
            multiplier = 1024L;
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("MB")) {
            multiplier = 1024L * 1024;
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("GB")) {
            multiplier = 1024L * 1024 * 1024;
            v = v.substring(0, v.length() - 2);
        }
        return Long.parseLong(v.trim()) * multiplier;
    }
}
