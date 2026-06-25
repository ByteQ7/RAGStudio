package com.byteq.ai.ragstudio.rag.core.skill;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Docker 沙箱执行器
 * <p>
 * 通过调用 docker CLI 在隔离容器中执行命令，替代直接在宿主机上执行。
 * 容器配置了多层安全限制：
 * <ul>
 *   <li>--rm：退出自动删除</li>
 *   <li>--read-only：根文件系统只读</li>
 *   <li>--user 1000:1000：非 root 运行</li>
 *   <li>--cap-drop=ALL：剔除所有权限</li>
 *   <li>--memory / --cpus / --pids-limit：资源限制</li>
 * </ul>
 */
@Slf4j
public class SandboxExecutor {

    /** Docker 命令前缀（如 ["docker"] 或 ["sudo", "-n", "docker"]） */
    private final List<String> dockerCommand;

    /** 基础镜像名 */
    private final String image;

    /** 执行超时（毫秒） */
    private final long timeoutMs;

    /** 内存限制 */
    private final String memory;

    /** CPU 限制 */
    private final String cpus;

    private SandboxExecutor(List<String> dockerCommand, String image, long timeoutMs, String memory, String cpus) {
        this.dockerCommand = dockerCommand;
        this.image = image;
        this.timeoutMs = timeoutMs;
        this.memory = memory;
        this.cpus = cpus;
    }

    /**
     * 在 Docker 沙箱中执行命令
     *
     * @param command 要执行的命令
     * @return 执行结果
     */
    public SandboxResult execute(String command) {
        return execute(command, false, List.of());
    }

    /**
     * 在 Docker 沙箱中执行命令
     *
     * @param command   要执行的命令
     * @param enableNet 是否启用网络
     * @return 执行结果
     */
    public SandboxResult execute(String command, boolean enableNet) {
        return execute(command, enableNet, List.of());
    }

    /**
     * 在 Docker 沙箱中执行命令
     *
     * @param command     要执行的命令
     * @param enableNet   是否启用网络
     * @param volumes     宿主机:容器 的卷挂载列表，如 /host/path:/container/path
     * @return 执行结果
     */
    public SandboxResult execute(String command, boolean enableNet, List<String> volumes) {
        long start = System.currentTimeMillis();

        try {
            List<String> cmd = buildDockerRunCommand(command, enableNet, volumes);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);

            Process process = pb.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            long duration = System.currentTimeMillis() - start;

            if (!finished) {
                process.destroyForcibly();
                log.warn("Docker 沙箱执行超时: timeoutMs={}, command={}", timeoutMs, command);
                return SandboxResult.builder()
                        .success(false)
                        .output("执行超时（" + timeoutMs + "ms）")
                        .exitCode(-1)
                        .durationMs(duration)
                        .build();
            }

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            int exitCode = process.exitValue();

            String output = stdout;
            if (!stderr.isBlank()) {
                output = stdout + "\nstderr:\n" + stderr;
            }

            boolean success = exitCode == 0;
            log.info("Docker 沙箱执行完成: exitCode={}, durationMs={}, success={}", exitCode, duration, success);

            return SandboxResult.builder()
                    .success(success)
                    .output(output)
                    .exitCode(exitCode)
                    .durationMs(duration)
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Docker 沙箱执行异常: {}", e.getMessage());
            return SandboxResult.builder()
                    .success(false)
                    .output("沙箱执行失败: " + e.getMessage())
                    .exitCode(-1)
                    .durationMs(duration)
                    .build();
        }
    }

    /**
     * 测试 Docker 是否可用
     */
    public boolean isAvailable() {
        try {
            List<String> cmd = new ArrayList<>(dockerCommand);
            cmd.add("info");
            Process process = new ProcessBuilder(cmd).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 内部方法 ====================

    private List<String> buildDockerRunCommand(String command, boolean enableNet, List<String> volumes) {
        List<String> cmd = new ArrayList<>(dockerCommand);
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--read-only");
        cmd.add("--tmpfs"); cmd.add("/tmp:size=1G,noexec");
        cmd.add("--user"); cmd.add("1000:1000");
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt"); cmd.add("no-new-privileges:true");
        cmd.add("--memory"); cmd.add(memory);
        cmd.add("--cpus"); cmd.add(cpus);
        cmd.add("--pids-limit"); cmd.add("50");

        // 卷挂载
        if (volumes != null) {
            for (String vol : volumes) {
                cmd.add("-v"); cmd.add(vol);
            }
        }

        if (!enableNet) {
            cmd.add("--network"); cmd.add("none");
        }

        cmd.add(image);
        cmd.add("sh"); cmd.add("-c");
        cmd.add(command);

        return cmd;
    }

    private String readStream(java.io.InputStream stream) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[4096];
            int n;
            while ((n = stream.read(data)) != -1) {
                buffer.write(data, 0, n);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== Builder ====================

    public static SandboxExecutorBuilder builder() {
        return new SandboxExecutorBuilder();
    }

    public static class SandboxExecutorBuilder {
        private List<String> dockerCommand = List.of("docker");
        private String image = "ragstudio-sandbox:latest";
        private long timeoutMs = 30000;
        private String memory = "256m";
        private String cpus = "0.5";

        SandboxExecutorBuilder() {}

        public SandboxExecutorBuilder dockerCommand(String... parts) {
            this.dockerCommand = List.of(parts); return this;
        }

        public SandboxExecutorBuilder dockerCommand(List<String> parts) {
            this.dockerCommand = parts; return this;
        }

        public SandboxExecutorBuilder image(String image) {
            this.image = image; return this;
        }

        public SandboxExecutorBuilder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs; return this;
        }

        public SandboxExecutorBuilder memory(String memory) {
            this.memory = memory; return this;
        }

        public SandboxExecutorBuilder cpus(String cpus) {
            this.cpus = cpus; return this;
        }

        public SandboxExecutor build() {
            return new SandboxExecutor(dockerCommand, image, timeoutMs, memory, cpus);
        }
    }

    // ==================== 结果类 ====================

    @Data
    @Builder
    public static class SandboxResult {
        private final boolean success;
        private final String output;
        private final int exitCode;
        private final long durationMs;
    }
}
