package com.byteq.ai.ragstudio.framework.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.exception.AbstractException;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Optional;

/**
 * 全局异常处理器
 *
 * <p>基于 {@code @RestControllerAdvice} 统一拦截并处理系统中抛出的各类异常，
 * 以统一格式 {@link Result} 返回给客户端，避免异常信息直接暴露给前端。</p>
 *
 * <p>拦截的异常类型包括：</p>
 * <ul>
 *   <li>{@link MethodArgumentNotValidException}：参数校验失败异常</li>
 *   <li>{@link AbstractException} 及其子类：应用自定义异常</li>
 *   <li>{@link NotLoginException}：未登录或登录过期（Sa-Token）</li>
 *   <li>{@link NotRoleException}：无权限访问（Sa-Token）</li>
 *   <li>{@link MaxUploadSizeExceededException}：文件上传大小超限</li>
 *   <li>{@link Throwable}：其他所有未捕获异常（兜底处理）</li>
 * </ul>
 *
 * @see Result
 * @see Results
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 单个文件上传大小限制，从配置文件读取，默认 50MB
     */
    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String maxFileSize;

    /**
     * 单次请求总大小限制，从配置文件读取，默认 100MB
     */
    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private String maxRequestSize;

    /**
     * 拦截参数校验异常
     * <p>当请求参数不满足 {@code @Valid} 或 {@code @Validated} 校验规则时触发，
     * 提取第一个校验失败字段的错误消息返回给客户端。</p>
     *
     * @param request 当前 HTTP 请求
     * @param ex      {@link MethodArgumentNotValidException} 参数校验异常
     * @return 统一格式的失败响应，包含具体的参数错误信息
     */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result<Void> validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        // 提取第一个字段校验失败的错误消息
        FieldError firstFieldError = CollectionUtil.getFirst(bindingResult.getFieldErrors());
        String exceptionStr = Optional.ofNullable(firstFieldError)
                .map(FieldError::getDefaultMessage)
                .orElse(StrUtil.EMPTY);
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr);
    }

    /**
     * 拦截应用内抛出的自定义异常
     * <p>处理 {@link AbstractException} 及其子类（{@link ClientException}、
     * {@link ServiceException}、
     * {@link RemoteException}），
     * 记录异常堆栈前 5 行以帮助定位问题，同时保护敏感信息不暴露给客户端。</p>
     *
     * @param request 当前 HTTP 请求
     * @param ex      {@link AbstractException} 应用自定义异常
     * @return 统一格式的失败响应，包含错误码和错误消息
     */
    @ExceptionHandler(value = {AbstractException.class})
    public Result<Void> abstractException(HttpServletRequest request, AbstractException ex) {
        if (ex.getCause() != null) {
            log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex, ex.getCause());
            return Results.failure(ex);
        }
        // 记录前 5 行堆栈信息用于问题排查
        StringBuilder stackTraceBuilder = new StringBuilder();
        stackTraceBuilder.append(ex.getClass().getName()).append(": ").append(ex.getErrorMessage()).append("\n");
        StackTraceElement[] stackTrace = ex.getStackTrace();
        for (int i = 0; i < Math.min(5, stackTrace.length); i++) {
            stackTraceBuilder.append("\tat ").append(stackTrace[i]).append("\n");
        }
        log.error("[{}] {} [ex] {} \n\n{}", request.getMethod(), request.getRequestURL().toString(), ex, stackTraceBuilder);
        return Results.failure(ex);
    }

    /**
     * 拦截 Sa-Token 未登录异常
     * <p>当用户未登录或登录 Token 已过期时触发，返回明确的提示信息。</p>
     *
     * @param request 当前 HTTP 请求
     * @param ex      {@link NotLoginException} 未登录异常
     * @return 统一格式的失败响应，提示用户未登录或登录已过期
     */
    @ExceptionHandler(value = NotLoginException.class)
    public Result<Void> notLoginException(HttpServletRequest request, NotLoginException ex) {
        log.warn("[{}] {} [auth] not-login: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "未登录或登录已过期");
    }

    /**
     * 拦截 Sa-Token 无角色权限异常
     * <p>当用户不具备访问某接口所需的角色权限时触发。</p>
     *
     * @param request 当前 HTTP 请求
     * @param ex      {@link NotRoleException} 无角色权限异常
     * @return 统一格式的失败响应，提示权限不足
     */
    @ExceptionHandler(value = NotRoleException.class)
    public Result<Void> notRoleException(HttpServletRequest request, NotRoleException ex) {
        log.warn("[{}] {} [auth] no-role: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "权限不足");
    }

    /**
     * 拦截文件上传大小超限异常
     * <p>当上传的文件大小超过 {@code spring.servlet.multipart.max-file-size} 或
     * 单次请求总大小超过 {@code spring.servlet.multipart.max-request-size} 时触发。
     * 根据异常原因区分是单个文件超限还是请求总大小超限，并给出具体的限制说明。</p>
     *
     * @param request 当前 HTTP 请求
     * @param ex      {@link MaxUploadSizeExceededException} 上传大小超限异常
     * @return 统一格式的失败响应，包含具体的文件大小限制说明
     */
    @ExceptionHandler(value = MaxUploadSizeExceededException.class)
    public Result<Void> maxUploadSizeExceededException(HttpServletRequest request, MaxUploadSizeExceededException ex) {
        log.warn("[{}] {} [upload] 文件上传大小超限: {}", request.getMethod(), getUrl(request), ex.getMessage());
        String message;
        // 判断是单个文件超限还是总请求大小超限
        if (ex.getCause() instanceof IllegalStateException
                && ex.getCause().getCause() instanceof FileSizeLimitExceededException) {
            message = "上传文件大小超过限制，单个文件最大允许 " + maxFileSize;
        } else {
            message = "上传请求大小超过限制，单次请求最大允许 " + maxRequestSize;
        }
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message);
    }

    /**
     * 兜底异常处理
     * <p>拦截所有未被上述异常处理器捕获的异常，作为最后一道防线，
     * 确保任何情况下都能返回统一的响应格式，避免直接将 500 错误堆栈暴露给客户端。</p>
     *
     * @param request   当前 HTTP 请求
     * @param throwable 未捕获的异常
     * @return 统一格式的失败响应，使用默认服务错误码
     */
    @ExceptionHandler(value = Throwable.class)
    public Result<Void> defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        return Results.failure();
    }

    /**
     * 获取完整请求 URL（含查询参数）
     *
     * @param request HTTP 请求
     * @return 完整的请求 URL 字符串
     */
    private String getUrl(HttpServletRequest request) {
        if (StrUtil.isBlank(request.getQueryString())) {
            return request.getRequestURL().toString();
        }
        return request.getRequestURL().toString() + "?" + request.getQueryString();
    }
}
