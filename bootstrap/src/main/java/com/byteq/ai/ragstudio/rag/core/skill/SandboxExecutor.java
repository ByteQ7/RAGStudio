package com.byteq.ai.ragstudio.rag.core.skill;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Docker 沙箱执行器
 * <p>
 * 支持两种运行模式（{@code poolSize > 0} 时为池化模式）：
 * <ul>
 *   <li><b>池化模式（默认推荐）</b>：启动时预热 N 个常驻容器（集群），执行用 {@code docker exec}，
 *       执行后在容器内清场（kill 除 PID1 外的残留进程 + 清空可写的 tmpfs /tmp），容器保留复用；
 *       池耗尽时兜底走一次性容器（legacy 路径），保证不阻塞。</li>
 *   <li><b>传统模式（poolSize = 0）</b>：每次执行 {@code docker run --rm} 用时开、用完删。</li>
 * </ul>
 * 容器配置了多层安全限制（两种模式一致）：
 * <ul>
 *   <li>--read-only：根文件系统只读（可写面仅 noexec 的 tmpfs /tmp，执行后清场）</li>
 *   <li>--user 1000:1000：非 root 运行</li>
 *   <li>--cap-drop=ALL：剔除所有权限</li>
 *   <li>--memory / --cpus / --pids-limit：资源限制</li>
 * </ul>
 * 池化模式下技能工作区以只读 bind mount（/skills:ro）预挂载进常驻容器，
 * bind mount 实时反映宿主机文件变化，新物化的技能脚本无需重建容器即可执行。
 */
@Slf4j
public class SandboxExecutor {

    /** 常驻池容器名前缀（warmup 时按前缀清理残留） */
    private static final String POOL_NAME_PREFIX = "ragstudio-sandbox-pool-";

    /** 常驻容器 PID1 保活命令 */
    private static final String POOL_KEEPALIVE = "while :; do sleep 3600; done";

    /**
     * 执行后清场脚本：kill 除 PID1（保活进程）与当前清场 shell 外的所有残留进程，
     * 并清空可写的 tmpfs /tmp，避免上一次执行的脏数据/后台进程影响下一次执行。
     */
    private static final String POOL_CLEANUP_SCRIPT =
            "for d in /proc/[0-9]*; do p=${d#/proc/}; "
                    + "[ \"$p\" != \"1\" ] && [ \"$p\" != \"$$\" ] && kill -9 \"$p\" 2>/dev/null; "
                    + "done; rm -rf /tmp/* /tmp/.[!.]* /tmp/..?* 2>/dev/null; true";

    /** 清场 exec 的超时（毫秒） */
    private static final long CLEANUP_TIMEOUT_MS = 5000;

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

    /** 常驻池大小；0 = 传统按次创建模式 */
    private final int poolSize;

    /** 池化容器的网络策略（创建时固定）：false 时以 --network none 创建 */
    private final boolean networkEnabled;

    /** 技能工作区宿主机路径（池化模式挂载为 /skills:ro），null 表示不挂载 */
    private final String workspaceMount;

    /** 沙箱容器指定的 DNS 服务器（空 = 跟随宿主机 resolv.conf；建议显式指定，避免容器创建时固化宿主机临时 DNS 配置） */
    private final List<String> dnsServers;

    /** 空闲常驻容器（队首借出） */
    private final ConcurrentLinkedDeque<String> freeContainers = new ConcurrentLinkedDeque<>();

    /** 当前池内容器名集合（含借出中的） */
    private final java.util.Set<String> poolContainers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 池是否已完成首次初始化（失败时保持 false，下次执行重试） */
    private volatile boolean poolInitialized = false;

    /** 池创建/销毁操作的锁 */
    private final Object poolLock = new Object();

    private SandboxExecutor(List<String> dockerCommand, String image, long timeoutMs, String memory, String cpus,
                            int poolSize, boolean networkEnabled, String workspaceMount, List<String> dnsServers) {
        this.dockerCommand = dockerCommand;
        this.image = image;
        this.timeoutMs = timeoutMs;
        this.memory = memory;
        this.cpus = cpus;
        this.poolSize = Math.max(0, poolSize);
        this.networkEnabled = networkEnabled;
        this.workspaceMount = workspaceMount;
        this.dnsServers = dnsServers != null ? dnsServers : List.of();
        if (isPooled()) {
            // JVM 退出时销毁常驻容器（宿主机残留由下次 warmup 按前缀兜底清理）
            Runtime.getRuntime().addShutdownHook(new Thread(this::destroyAllPoolContainers, "sandbox-pool-shutdown"));
        }
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
     * @param enableNet 是否启用网络（池化模式下网络策略在容器创建时固定，本参数仅传统模式/兜底容器生效）
     * @return 执行结果
     */
    public SandboxResult execute(String command, boolean enableNet) {
        return execute(command, enableNet, List.of());
    }

    /**
     * 在 Docker 沙箱中执行命令
     *
     * @param command   要执行的命令
     * @param enableNet 是否启用网络
     * @param volumes   宿主机:容器 的卷挂载列表（传统模式生效；池化模式由工作区预挂载替代）
     * @return 执行结果
     */
    public SandboxResult execute(String command, boolean enableNet, List<String> volumes) {
        if (isPooled()) {
            return executePooled(command, enableNet, volumes);
        }
        return executeLegacy(command, enableNet, volumes);
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
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 池化模式 ====================

    /** 是否为池化模式 */
    public boolean isPooled() {
        return poolSize > 0;
    }

    /**
     * 脚本在容器内的基础路径
     * <p>池化模式：/skills/&lt;skillName&gt;/scripts（工作区整体预挂载）；
     * 传统模式：/scripts（执行前按技能单独挂载）。</p>
     */
    public String scriptBasePath(String skillName) {
        return isPooled() ? "/skills/" + skillName + "/scripts" : "/scripts";
    }

    /** 预热沙箱池（启动时调用；Docker 不可用时跳过，首次执行时重试） */
    public void warmup() {
        if (!isPooled()) {
            return;
        }
        ensurePool(true);
    }

    /** 确保池已初始化（懒加载 + 失败重试） */
    private void ensurePool(boolean logResult) {
        if (poolInitialized) {
            return;
        }
        synchronized (poolLock) {
            if (poolInitialized) {
                return;
            }
            if (!isAvailable()) {
                log.warn("Docker 不可用，沙箱池预热跳过（首次执行时重试）");
                return;
            }
            destroyStalePoolContainers();
            int created = 0;
            for (int i = 0; i < poolSize; i++) {
                String name = poolName(i);
                if (createPoolContainer(name)) {
                    poolContainers.add(name);
                    freeContainers.addLast(name);
                    created++;
                }
            }
            if (created > 0) {
                poolInitialized = true;
            }
            if (logResult) {
                log.info("SKILL 沙箱池预热完成: {}/{} 个常驻容器就绪", created, poolSize);
            } else if (created > 0) {
                log.info("SKILL 沙箱池延迟初始化完成: {}/{} 个常驻容器就绪", created, poolSize);
            }
        }
    }

    /** 池化执行：借出常驻容器 docker exec，用毕清场归还；池耗尽时兜底一次性容器 */
    private SandboxResult executePooled(String command, boolean enableNet, List<String> volumes) {
        ensurePool(false);
        long start = System.currentTimeMillis();

        String container = freeContainers.pollFirst();
        if (container == null) {
            container = createSparePoolContainer();
        }
        if (container == null) {
            // 池耗尽（全部借出且容量已满）：兜底一次性容器，保证不阻塞
            log.info("SKILL 沙箱池已耗尽，本次降级为一次性容器执行");
            return executeLegacy(command, enableNet, autoWorkspaceVolumes(volumes));
        }
        try {
            SandboxResult result = execInContainer(container, command);
            if (!result.isSuccess() && isContainerStateError(result.getOutput())) {
                // 容器可能已死亡：销毁重建后重试一次
                log.warn("SKILL 沙箱容器异常，销毁重建后重试: container={}", container);
                recreatePoolContainer(container);
                result = execInContainer(container, command);
            }
            return result;
        } finally {
            releaseContainer(container);
        }
    }

    /** 在指定常驻容器内 exec 命令 */
    private SandboxResult execInContainer(String container, String command) {
        List<String> cmd = new ArrayList<>(dockerCommand);
        cmd.add("exec");
        cmd.add(container);
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(command);
        return runProcess(cmd, timeoutMs);
    }

    /**
     * 执行后清场并归还/销毁容器
     * <p>清场内容：kill 除保活进程外的所有残留进程（含超时被 CLI 中断后遗留的进程）、清空 tmpfs /tmp。</p>
     */
    private void releaseContainer(String container) {
        List<String> cmd = new ArrayList<>(dockerCommand);
        cmd.add("exec");
        cmd.add(container);
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(POOL_CLEANUP_SCRIPT);
        SandboxResult cleanup = runProcess(cmd, CLEANUP_TIMEOUT_MS);
        if (!cleanup.isSuccess()) {
            log.warn("SKILL 沙箱清场失败（容器可能已异常）: container={}, exit={}", container, cleanup.getExitCode());
        }
        if (!isRunning(container)) {
            log.warn("SKILL 沙箱容器已死亡，移出池内等待重建: container={}", container);
            synchronized (poolLock) {
                poolContainers.remove(container);
            }
            removeContainerForce(container);
            return;
        }
        freeContainers.addLast(container);
    }

    /** 容量未满时补建一个常驻容器，返回容器名；容量已满或创建失败返回 null */
    private String createSparePoolContainer() {
        synchronized (poolLock) {
            for (int i = 0; i < poolSize; i++) {
                String name = poolName(i);
                if (poolContainers.add(name)) {
                    if (createPoolContainer(name)) {
                        log.info("SKILL 沙箱池动态补建容器: {}", name);
                        return name;
                    }
                    poolContainers.remove(name);
                }
            }
        }
        return null;
    }

    /** 销毁并重建池内容器（同名） */
    private void recreatePoolContainer(String container) {
        synchronized (poolLock) {
            removeContainerForce(container);
            if (!createPoolContainer(container)) {
                poolContainers.remove(container);
            }
        }
    }

    /** 创建常驻池容器（安全限制与传统模式一致 + 工作区只读挂载 + 保活进程） */
    private boolean createPoolContainer(String name) {
        try {
            List<String> cmd = new ArrayList<>(dockerCommand);
            cmd.add("run");
            cmd.add("-d");
            cmd.add("--name");
            cmd.add(name);
            cmd.add("--read-only");
            cmd.add("--tmpfs");
            cmd.add("/tmp:size=1G,noexec");
            cmd.add("--user");
            cmd.add("1000:1000");
            cmd.add("--cap-drop=ALL");
            cmd.add("--security-opt");
            cmd.add("no-new-privileges:true");
            cmd.add("--memory");
            cmd.add(memory);
            cmd.add("--cpus");
            cmd.add(cpus);
            cmd.add("--pids-limit");
            cmd.add("50");
            appendDnsFlags(cmd);
            if (workspaceMount != null && !workspaceMount.isBlank()) {
                cmd.add("-v");
                cmd.add(workspaceMount + ":/skills:ro");
            }
            if (!networkEnabled) {
                cmd.add("--network");
                cmd.add("none");
            }
            cmd.add(image);
            cmd.add("sh");
            cmd.add("-c");
            cmd.add(POOL_KEEPALIVE);
            SandboxResult result = runProcess(cmd, timeoutMs);
            if (!result.isSuccess()) {
                log.warn("SKILL 沙箱常驻容器创建失败: name={}, output={}", name, result.getOutput());
                return false;
            }
            log.info("SKILL 沙箱常驻容器已启动: name={}", name);
            return true;
        } catch (Exception e) {
            log.warn("SKILL 沙箱常驻容器创建异常: name={}, error={}", name, e.getMessage());
            return false;
        }
    }

    /** 启动预热前按前缀清理残留容器（上次运行遗留，含异常残留的一次性容器） */
    private void destroyStalePoolContainers() {
        List<String> cmd = new ArrayList<>(dockerCommand);
        cmd.add("ps");
        cmd.add("-aq");
        cmd.add("--filter");
        cmd.add("name=" + POOL_NAME_PREFIX);
        SandboxResult result = runProcess(cmd, CLEANUP_TIMEOUT_MS);
        if (!result.isSuccess() || result.getOutput().isBlank()) {
            return;
        }
        for (String id : result.getOutput().split("\\s+")) {
            if (!id.isBlank()) {
                removeContainerForce(id.trim());
            }
        }
        log.info("已清理上批残留 SKILL 沙箱容器");
    }

    /** JVM 退出时销毁全部常驻容器 */
    private void destroyAllPoolContainers() {
        for (String container : poolContainers) {
            removeContainerForce(container);
        }
    }

    /** docker rm -f（容器不存在时静默） */
    private void removeContainerForce(String nameOrId) {
        try {
            List<String> cmd = new ArrayList<>(dockerCommand);
            cmd.add("rm");
            cmd.add("-f");
            cmd.add(nameOrId);
            runProcess(cmd, CLEANUP_TIMEOUT_MS);
        } catch (Exception e) {
            log.debug("SKILL 沙箱容器强制删除失败（忽略）: {}, error={}", nameOrId, e.getMessage());
        }
    }

    /** 容器是否处于运行状态 */
    private boolean isRunning(String container) {
        List<String> cmd = new ArrayList<>(dockerCommand);
        cmd.add("inspect");
        cmd.add("-f");
        cmd.add("{{.State.Running}}");
        cmd.add(container);
        SandboxResult result = runProcess(cmd, CLEANUP_TIMEOUT_MS);
        return result.isSuccess() && result.getOutput().trim().equals("true");
    }

    /** 容器状态类错误（No such container / is not running），触发重建重试 */
    private boolean isContainerStateError(String output) {
        return output != null && (output.contains("No such container") || output.contains("is not running"));
    }

    private String poolName(int index) {
        return POOL_NAME_PREFIX + index;
    }

    // ==================== 传统模式（poolSize=0 及池耗尽兜底） ====================

    private SandboxResult executeLegacy(String command, boolean enableNet, List<String> volumes) {
        List<String> cmd = buildDockerRunCommand(command, enableNet, autoWorkspaceVolumes(volumes));
        return runProcess(cmd, timeoutMs);
    }

    /**
     * 池化配置下的兜底/传统执行：命令按 /skills/&lt;skill&gt;/scripts 布局构建时，
     * 自动补挂工作区只读卷（调用方未显式传卷的场景）
     */
    private List<String> autoWorkspaceVolumes(List<String> volumes) {
        if (volumes != null && !volumes.isEmpty()) {
            return volumes;
        }
        if (workspaceMount != null && !workspaceMount.isBlank()) {
            return List.of(workspaceMount + ":/skills:ro");
        }
        return List.of();
    }

    // ==================== 通用 ====================

    /**
     * 运行外部命令并采集结果（并发排空 stdout/stderr，超时强制销毁 CLI 进程）
     * <p>注意：池化模式下超时只会杀死 docker exec 的宿主侧 CLI，
     * 容器内遗留进程由清场脚本兜底回收。</p>
     */
    private SandboxResult runProcess(List<String> cmd, long timeout) {
        long start = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);

            Process process = pb.start();
            // 并发排空 stdout/stderr：若先 waitFor 再读流，子进程输出超过管道缓冲（约 64KB）
            // 时会阻塞写管道而永不退出，导致必然超时且输出全部丢失
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);

            long duration = System.currentTimeMillis() - start;

            if (!finished) {
                process.destroyForcibly();
                log.warn("Docker 沙箱执行超时: timeoutMs={}, command={}", timeout, cmd);
                return SandboxResult.builder()
                        .success(false)
                        .output("执行超时（" + timeout + "ms）")
                        .exitCode(-1)
                        .durationMs(duration)
                        .build();
            }

            // 进程已退出，管道已关闭，读流必然结束
            String stdout = stdoutFuture.join();
            String stderr = stderrFuture.join();
            int exitCode = process.exitValue();

            String output = stdout;
            if (!stderr.isBlank()) {
                output = stdout + "\nstderr:\n" + stderr;
            }

            boolean success = exitCode == 0;
            log.debug("Docker 沙箱执行完成: exitCode={}, durationMs={}, success={}", exitCode, duration, success);

            return SandboxResult.builder()
                    .success(success)
                    .output(output)
                    .exitCode(exitCode)
                    .durationMs(duration)
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long duration = System.currentTimeMillis() - start;
            return SandboxResult.builder()
                    .success(false)
                    .output("沙箱执行被中断")
                    .exitCode(-1)
                    .durationMs(duration)
                    .build();
        } catch (IOException e) {
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

    // ==================== 传统模式 docker run 命令构建 ====================

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
        appendDnsFlags(cmd);

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

    /**
     * 追加容器 DNS 配置（--dns）。
     * <p>显式指定可避免容器创建时把宿主机当时的 /etc/resolv.conf 固化进容器——
     * 宿主机若在跑代理（TUN/系统代理切换）时 resolv.conf 可能被临时改写，
     * 固化后容器会长期携带失效/异常的 DNS 配置。</p>
     */
    private void appendDnsFlags(List<String> cmd) {
        for (String dns : dnsServers) {
            cmd.add("--dns");
            cmd.add(dns);
        }
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
        private int poolSize = 0;
        private boolean networkEnabled = true;
        private String workspaceMount;
        private List<String> dnsServers = List.of();

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

        /** 常驻池大小；0 = 传统按次创建模式（默认） */
        public SandboxExecutorBuilder poolSize(int poolSize) {
            this.poolSize = poolSize; return this;
        }

        /** 池化容器的网络策略（创建时固定），默认开放 */
        public SandboxExecutorBuilder networkEnabled(boolean networkEnabled) {
            this.networkEnabled = networkEnabled; return this;
        }

        /** 技能工作区宿主机路径（池化模式挂载为 /skills:ro） */
        public SandboxExecutorBuilder workspaceMount(String workspaceMount) {
            this.workspaceMount = workspaceMount; return this;
        }

        /** 沙箱容器 DNS 服务器列表（空 = 跟随宿主机 resolv.conf） */
        public SandboxExecutorBuilder dnsServers(List<String> dnsServers) {
            this.dnsServers = dnsServers; return this;
        }

        public SandboxExecutor build() {
            return new SandboxExecutor(dockerCommand, image, timeoutMs, memory, cpus,
                    poolSize, networkEnabled, workspaceMount, dnsServers);
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
