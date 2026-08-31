package com.byteq.ai.ragstudio.infra.data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 数据目录环境后置处理器
 * <p>
 * 在配置加载后、容器刷新前：
 * <ul>
 *   <li>解析数据根目录并写入属性 {@code ragstudio.data-dir}（供 application.yaml 占位符引用）</li>
 *   <li>若 {@code <数据目录>/.env} 存在则加载（JAR 部署的推荐配置位置），
 *       优先级高于 cwd 相对的 .env 导入，但不覆盖 OS 环境变量/系统属性</li>
 * </ul>
 * 说明：解析只依赖 OS 环境变量/系统属性与 cwd/JAR 位置，不依赖 .env 内容（无先有鸡还是先有蛋问题）；
 * .env 中也可配置 {@code RAGSTUDIO_DATA_DIR}（开发态 .env 可被定位时生效）。
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class DataDirEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty(DataDirs.DATA_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = environment.getProperty(DataDirs.DATA_DIR_SPRING_PROPERTY);
        }
        Path dataDir = DataDirs.resolve(configured);
        DataDirs.initialize(dataDir);

        Map<String, Object> source = new HashMap<>();
        source.put(DataDirs.DATA_DIR_SPRING_PROPERTY, dataDir.toString());
        environment.getPropertySources().addFirst(new MapPropertySource("ragstudioDataDir", source));

        loadDotEnv(environment, dataDir.resolve(".env"));

        System.out.println("[DataDirs] 运行时数据目录: " + dataDir
                + "（来源：" + (configured != null && !configured.isBlank() ? "显式配置" : "自动定位") + "）");
    }

    /** 加载 <数据目录>/.env；跳过 OS 环境变量/系统属性中已存在的键（OS 优先），避免 JAR 部署读不到配置 */
    private void loadDotEnv(ConfigurableEnvironment environment, Path envFile) {
        if (!Files.isRegularFile(envFile)) {
            return;
        }
        try (InputStream in = Files.newInputStream(envFile)) {
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            StandardEnvironment systemOnly = new StandardEnvironment();
            Map<String, Object> source = new HashMap<>();
            for (String key : props.stringPropertyNames()) {
                String trimmedKey = key.trim();
                if (trimmedKey.isEmpty() || DataDirs.DATA_DIR_PROPERTY.equals(trimmedKey)) {
                    continue;
                }
                // 系统 properties / OS 环境变量已显式提供的键不覆盖
                if (systemOnly.getProperty(trimmedKey) != null) {
                    continue;
                }
                source.put(trimmedKey, props.getProperty(key).trim());
            }
            if (!source.isEmpty()) {
                environment.getPropertySources().addFirst(new MapPropertySource("ragstudioDataDotEnv", source));
            }
            System.out.println("[DataDirs] 已加载数据目录配置文件: " + envFile + "（" + source.size() + " 项）");
        } catch (Exception e) {
            System.out.println("[DataDirs] 加载 " + envFile + " 失败（忽略）: " + e.getMessage());
        }
    }
}
