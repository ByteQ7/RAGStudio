package com.byteq.ai.ragstudio.rag.core.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * SKILL 目录与哈希工具
 * <p>
 * 目录解析规则与全目录树 SHA-256（treeHash）同时服务于两处：
 * 运行时的 {@link SkillLoader}（磁盘目录）与管理面的存储层（DB 文件集），
 * 两端算法必须一致，物化完整性校验与漂移检测才成立。
 */
public final class SkillDirs {

    private SkillDirs() {}

    /**
     * 解析 SKILL 工作区目录路径
     * <p>
     * 优先使用配置的路径；如果不存在，尝试从当前工作目录的父目录查找
     * （适配 mvn spring-boot:run 在 bootstrap/ 下运行的情况）。
     */
    public static Path resolve(String configured) {
        Path path = Path.of(configured);
        if (Files.exists(path) && Files.isDirectory(path)) {
            return path.toAbsolutePath().normalize();
        }
        Path userDir = Path.of(System.getProperty("user.dir"));
        if (userDir.getParent() != null) {
            Path parentPath = userDir.getParent().resolve(configured);
            if (Files.exists(parentPath) && Files.isDirectory(parentPath)) {
                return parentPath.normalize();
            }
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * 全目录树 SHA-256：对文件集按路径排序后，
     * 依次摘要 path + '\n' + sha256(content) 的十六进制串 + '\n'。
     */
    public static String treeHash(Map<String, byte[]> files) {
        MessageDigest md = newDigest();
        for (String path : files.keySet().stream().sorted().toList()) {
            md.update(path.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            md.update(HexFormat.of().formatHex(sha256(files.get(path))).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * 计算磁盘目录的全树哈希（递归所有常规文件），算法与 {@link #treeHash(Map)} 一致。
     * 目录不存在或遍历失败返回 null。
     */
    public static String treeHashOfDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            return null;
        }
        MessageDigest md = newDigest();
        for (Path file : files.stream().sorted().toList()) {
            String relPath = dir.relativize(file).toString().replace('\\', '/');
            md.update(relPath.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            md.update(sha256Of(file).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static byte[] sha256(byte[] content) {
        return newDigest().digest(content);
    }

    private static String sha256Of(Path file) {
        try {
            return HexFormat.of().formatHex(newDigest().digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + file, e);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
