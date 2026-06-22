package com.byteq.ai.ragstudio.framework.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码哈希工具类
 * <p>
 * 使用 BCrypt 算法对密码进行哈希和校验，
 * 替代原有的明文存储方案。
 */
@Slf4j
public final class PasswordHasher {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private PasswordHasher() {
        // 工具类，禁止实例化
    }

    /**
     * 对明文密码进行 BCrypt 哈希
     *
     * @param rawPassword 明文密码
     * @return 哈希后的密码字符串
     */
    public static String hash(String rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return ENCODER.encode(rawPassword);
    }

    /**
     * 校验明文密码是否与哈希值匹配
     * <p>
     * 兼容旧版明文密码：如果 stored 不是 BCrypt 格式（不以 $2 开头），
     * 则退化为明文比较，确保存量用户可以正常登录。
     *
     * @param rawPassword 用户输入的明文密码
     * @param stored      数据库中存储的密码（可能是 BCrypt 哈希或旧版明文）
     * @return 是否匹配
     */
    public static boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        // BCrypt 哈希以 $2a$、$2b$ 或 $2y$ 开头
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return ENCODER.matches(rawPassword, stored);
        }
        // 兼容旧版明文密码（迁移过渡期）
        log.warn("用户密码为明文存储，建议尽快迁移至 BCrypt 加密");
        return stored.equals(rawPassword);
    }
}
