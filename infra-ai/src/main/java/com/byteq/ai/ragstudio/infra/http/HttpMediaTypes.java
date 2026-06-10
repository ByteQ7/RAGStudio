package com.byteq.ai.ragstudio.infra.http;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import okhttp3.MediaType;

/**
 * HTTP 媒体类型常量类
 * <p>
 * 集中管理 HTTP 请求中使用的 Content-Type 媒体类型定义，
 * 避免在业务代码中散落重复的字符串常量。
 * 当前提供 JSON 媒体类型常量，用于 OkHttp 请求体内容的类型声明。
 * </p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HttpMediaTypes {

    /**
     * JSON 媒体类型（UTF-8 编码）
     * <p>
     * 对应 HTTP Content-Type 头部的 "application/json; charset=utf-8"。
     * 使用 OkHttp 的 {@link MediaType#get(String)} 方法创建，
     * 用于在 POST/PUT 等请求中声明请求体为 JSON 格式。
     * </p>
     */
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
}
