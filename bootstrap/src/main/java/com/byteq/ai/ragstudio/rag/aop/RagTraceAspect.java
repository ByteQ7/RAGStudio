package com.byteq.ai.ragstudio.rag.aop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.trace.RagTraceContext;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.framework.trace.RagTraceRoot;
import com.byteq.ai.ragstudio.rag.config.RagTraceProperties;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceRunDO;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Date;

/**
 * 注解式 RAG 链路追踪采集切面
 * <p>
 * 通过 AOP 切面拦截 {@link RagTraceRoot} 和 {@link RagTraceNode} 注解，
 * 自动记录 RAG 对话过程中各环节的链路追踪数据。
 * </p>
 * <p>
 * 功能说明：
 * <ul>
 *   <li><b>@RagTraceRoot</b>：标记链路根节点，自动创建运行记录（Run），记录入口方法、会话 ID、任务 ID 等信息</li>
 *   <li><b>@RagTraceNode</b>：标记链路子节点，自动创建节点记录（Node），记录类名、方法名、耗时等信息</li>
 *   <li>支持嵌套链路，通过节点栈维护父子关系</li>
 *   <li>自动处理异常状态下的链路记录</li>
 * </ul>
 * </p>
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RagTraceAspect {

    /** 运行中状态 */
    private static final String STATUS_RUNNING = "RUNNING";

    /** 成功状态 */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /** 错误状态 */
    private static final String STATUS_ERROR = "ERROR";

    /** 链路追踪记录服务 */
    private final RagTraceRecordService traceRecordService;

    /** 链路追踪配置 */
    private final RagTraceProperties traceProperties;

    /**
     * 环绕通知：处理 @RagTraceRoot 注解
     * <p>
     * 在方法执行前后创建和完成链路运行记录。
     * 如果当前线程已在链路中（traceId 已存在），则跳过避免重复创建根节点。
     * </p>
     *
     * @param joinPoint 连接点
     * @param traceRoot RagTraceRoot 注解
     * @return 方法执行结果
     * @throws Throwable 方法执行中的异常
     */
    @Around("@annotation(traceRoot)")
    public Object aroundRoot(ProceedingJoinPoint joinPoint, RagTraceRoot traceRoot) throws Throwable {
        // 如果链路追踪功能未开启，直接跳过切面逻辑，执行原方法
        if (!traceProperties.isEnabled()) {
            return joinPoint.proceed();
        }

        // 检查当前线程是否已经在一个链路追踪上下文中
        String existingTraceId = RagTraceContext.getTraceId();
        if (StrUtil.isNotBlank(existingTraceId)) {
            // 当前线程已在链路中，避免重复创建 root
            return joinPoint.proceed();
        }

        // 解析方法签名，获取方法元信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 生成全局唯一的链路追踪 ID（雪花算法）
        String traceId = IdUtil.getSnowflakeNextIdStr();

        // 从方法参数中提取配置指定的会话 ID 和任务 ID
        String conversationId = resolveStringArg(signature, joinPoint.getArgs(), traceRoot.conversationIdArg());
        String taskId = resolveStringArg(signature, joinPoint.getArgs(), traceRoot.taskIdArg());

        // 链路名称：优先使用注解指定的 name，否则使用方法名
        String traceName = StrUtil.blankToDefault(traceRoot.name(), method.getName());
        Date startTime = new Date();
        long startMillis = System.currentTimeMillis();

        // 向数据库写入一条 RUNNING 状态的链路运行记录
        traceRecordService.startRun(RagTraceRunDO.builder()
                .traceId(traceId)
                .traceName(traceName)
                .entryMethod(method.getDeclaringClass().getName() + "#" + method.getName())
                .conversationId(conversationId)
                .taskId(taskId)
                .userId(UserContext.getUserId())
                .status(STATUS_RUNNING)
                .startTime(startTime)
                .build());

        // 将 traceId 绑定到当前线程，后续 @RagTraceNode 切面可获取
        RagTraceContext.setTraceId(traceId);
        try {
            // 执行原方法
            Object result = joinPoint.proceed();

            // 方法执行成功，更新链路状态为 SUCCESS，记录结束时间和总耗时（毫秒）
            traceRecordService.finishRun(
                    traceId,
                    STATUS_SUCCESS,
                    null,
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            return result;
        } catch (Throwable ex) {
            // 方法抛出异常，更新链路状态为 ERROR，记录异常信息和结束时间
            traceRecordService.finishRun(
                    traceId,
                    STATUS_ERROR,
                    truncateError(ex),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            // 继续向上抛出异常，不影响业务方的异常处理
            throw ex;
        } finally {
            // 无论成功还是异常，最终都清理当前线程的追踪上下文，防止内存泄漏
            RagTraceContext.clear();
        }
    }

    /**
     * 环绕通知：处理 @RagTraceNode 注解
     * <p>
     * 在方法执行前后创建和完成链路节点记录。
     * 通过节点栈维护父子关系，支持嵌套调用。
     * </p>
     *
     * @param joinPoint 连接点
     * @param traceNode RagTraceNode 注解
     * @return 方法执行结果
     * @throws Throwable 方法执行中的异常
     */
    @Around("@annotation(traceNode)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
        // 链路追踪未开启时跳过，不采集节点数据
        if (!traceProperties.isEnabled()) {
            return joinPoint.proceed();
        }

        // 从当前线程上下文获取 traceId，如果没有 traceId 说明不在任何链路中，跳过
        String traceId = RagTraceContext.getTraceId();
        if (StrUtil.isBlank(traceId)) {
            return joinPoint.proceed();
        }

        // 解析方法签名，获取方法元信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 为当前节点生成唯一 ID，并获取父节点 ID 和当前深度（节点栈顶元素）
        String nodeId = IdUtil.getSnowflakeNextIdStr();
        String parentNodeId = RagTraceContext.currentNodeId();
        int depth = RagTraceContext.depth();
        Date startTime = new Date();
        long startMillis = System.currentTimeMillis();

        // 向数据库写入一条 RUNNING 状态的节点记录，包含节点类型、名称、类名、方法名和层级信息
        traceRecordService.startNode(RagTraceNodeDO.builder()
                .traceId(traceId)
                .nodeId(nodeId)
                .parentNodeId(parentNodeId)
                .depth(depth)
                .nodeType(StrUtil.blankToDefault(traceNode.type(), "METHOD"))
                .nodeName(StrUtil.blankToDefault(traceNode.name(), method.getName()))
                .className(method.getDeclaringClass().getName())
                .methodName(method.getName())
                .status(STATUS_RUNNING)
                .startTime(startTime)
                .build());

        // 将当前节点 ID 压入节点栈，后续同层节点的父节点指向它
        RagTraceContext.pushNode(nodeId);
        try {
            // 执行原方法
            Object result = joinPoint.proceed();

            // 节点执行成功，更新状态为 SUCCESS，记录结束时间和耗时
            traceRecordService.finishNode(
                    traceId,
                    nodeId,
                    STATUS_SUCCESS,
                    null,
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            return result;
        } catch (Throwable ex) {
            // 节点执行异常，更新状态为 ERROR，记录异常信息
            traceRecordService.finishNode(
                    traceId,
                    nodeId,
                    STATUS_ERROR,
                    truncateError(ex),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            // 继续向上抛出异常
            throw ex;
        } finally {
            // 无论成功或异常，当前节点出栈，回到父节点上下文
            RagTraceContext.popNode();
        }
    }

    /**
     * 解析方法参数中的字符串参数值
     * <p>
     * 通过参数名匹配从方法签名和参数列表中获取指定参数的值。
     * 用于从方法参数中提取 conversationId 和 taskId 等关键信息。
     * </p>
     *
     * @param signature 方法签名
     * @param args      方法参数值数组
     * @param argName   要解析的参数名
     * @return 参数值的字符串表示，如果未找到或参数为空则返回 null
     */
    private String resolveStringArg(MethodSignature signature, Object[] args, String argName) {
        if (StrUtil.isBlank(argName) || args == null || args.length == 0) {
            return null;
        }
        String[] parameterNames = signature.getParameterNames();
        if (parameterNames == null || parameterNames.length != args.length) {
            return null;
        }
        for (int i = 0; i < parameterNames.length; i++) {
            if (!argName.equals(parameterNames[i])) {
                continue;
            }
            Object arg = args[i];
            if (arg == null) {
                return null;
            }
            return String.valueOf(arg);
        }
        return null;
    }

    /**
     * 截断异常信息
     * <p>
     * 将异常信息格式化为"异常类名: 异常消息"的形式，按配置的最大长度截断。
     * </p>
     *
     * @param throwable 异常对象
     * @return 截断后的异常信息字符串
     */
    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        if (message.length() <= traceProperties.getMaxErrorLength()) {
            return message;
        }
        return message.substring(0, traceProperties.getMaxErrorLength());
    }
}
