package com.byteq.ai.ragstudio.ingestion.util;

import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 请求工具类，用于获取网络资源
 */
@Component
@RequiredArgsConstructor
public class HttpClientHelper {

    @Qualifier("syncHttpClient")
    private final OkHttpClient client;

    /**
     * 发起 HTTP GET 请求获取资源内容（无大小限制）
     *
     * @param url     请求 URL
     * @param headers 自定义请求头（可为 null）
     * @return HTTP 响应结果，包含字节内容、Content-Type、文件名等元信息
     */
    public HttpFetchResponse get(String url, Map<String, String> headers) {
        return doGet(url, headers, -1);
    }

    /**
     * 发起 HTTP GET 请求获取资源内容，带文件大小限制
     * <p>
     * 当响应内容超过 maxBytes 限制时抛出异常，防止 OOM。
     * </p>
     *
     * @param url      请求 URL
     * @param headers  自定义请求头（可为 null）
     * @param maxBytes 最大允许字节数，小于等于 0 表示不限制
     * @return HTTP 响应结果
     */
    public HttpFetchResponse getWithLimit(String url, Map<String, String> headers, long maxBytes) {
        return doGet(url, headers, maxBytes);
    }

    /**
     * 打开HTTP流式响应。
     * <p><b>重要：</b>调用者必须在使用完毕后关闭返回的HttpFetchStream（推荐使用try-with-resources），否则会导致资源泄漏。</p>
     *
     * @param url     请求URL
     * @param headers 请求头
     * @param maxBytes 最大字节数限制
     * @return HttpFetchStream对象，调用者负责关闭
     */
    public HttpFetchStream openStream(String url, Map<String, String> headers, long maxBytes) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        Response response = null;
        try {
            response = client.newCall(builder.get().build()).execute();
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                response.close();
                throw new ServiceException("网络请求失败: " + response.code() + " " + body);
            }
            ResponseBody responseBody = response.body();
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                response.close();
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }
            InputStream bodyStream = responseBody == null
                    ? InputStream.nullInputStream()
                    : wrapWithLimit(responseBody.byteStream(), maxBytes);
            return new HttpFetchStream(response, bodyStream, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            if (response != null) {
                response.close();
            }
            throw new ServiceException("网络请求失败: " + e.getMessage());
        } catch (Exception e) {
            if (response != null) {
                response.close();
            }
            throw e;
        }
    }

    // 执行 HTTP GET 请求的核心方法，支持可选的字节数限制:
    // 1. 构建请求并添加自定义头
    // 2. 执行请求并校验响应状态码
    // 3. 提取响应元信息（Content-Type、文件名、ETag 等）
    // 4. 根据 maxBytes 参数决定是否限制读取大小
    // 5. 读取响应体并封装为 HttpFetchResponse 返回
    private HttpFetchResponse doGet(String url, Map<String, String> headers, long maxBytes) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        try (Response response = client.newCall(builder.get().build()).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new ServiceException("网络请求失败: " + response.code() + " " + body);
            }
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }

            byte[] bytes;
            if (response.body() == null) {
                bytes = new byte[0];
            } else if (maxBytes > 0) {
                bytes = readWithLimit(response.body().byteStream(), maxBytes);
            } else {
                bytes = response.body().bytes();
            }
            return new HttpFetchResponse(bytes, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    /**
     * 发起 HTTP HEAD 请求获取资源元信息（不下载内容体）
     * <p>
     * 用于预检查资源的 Content-Type、Content-Length、ETag 等信息，
     * 可在实际下载前判断资源是否满足条件。
     * </p>
     *
     * @param url     请求 URL
     * @param headers 自定义请求头（可为 null）
     * @return HEAD 响应结果，包含 ETag、Last-Modified、Content-Type 等元信息
     */
    public HttpHeadResponse head(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        try (Response response = client.newCall(builder.head().build()).execute()) {
            if (!response.isSuccessful()) {
                throw new ServiceException("网络请求失败: " + response.code());
            }
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            return new HttpHeadResponse(etag, lastModified, contentType, contentLength, fileName);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    // 从 Content-Disposition 响应头或 URL 路径中解析文件名:
    // 1. 优先从 Content-Disposition 的 filename 参数中提取
    // 2. 兜底从 URL 路径的最后一段提取
    private String resolveFileName(String disposition, String url) {
        if (disposition != null) {
            String[] parts = disposition.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.startsWith("filename=")) {
                    String raw = trimmed.substring("filename=".length()).trim();
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
                        raw = raw.substring(1, raw.length() - 1);
                    }
                    return decode(raw);
                }
            }
        }
        try {
            URL parsed = new URL(url);
            String path = parsed.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            int idx = path.lastIndexOf('/');
            String fileName = idx >= 0 ? path.substring(idx + 1) : path;
            if (fileName.isEmpty()) {
                return null;
            }
            return fileName;
        } catch (Exception e) {
            return null;
        }
    }

    // 对 URL 编码的字符串进行 UTF-8 解码，解码失败时返回原始字符串
    private String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    // 将 Content-Length 响应头字符串解析为 Long 值，格式异常时返回 null
    private Long parseContentLength(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    // 从输入流中读取全部数据到字节数组，累计字节数超过 maxBytes 时抛出异常防止 OOM
    private byte[] readWithLimit(InputStream inputStream, long maxBytes) throws IOException {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int len;
            while ((len = in.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
                out.write(buffer, 0, len);
            }
            return out.toByteArray();
        }
    }

    // 将输入流包装为带大小限制的代理流，读取过程中累计字节数超过 maxBytes 时抛出异常
    private InputStream wrapWithLimit(InputStream inputStream, long maxBytes) {
        if (maxBytes <= 0) {
            return inputStream;
        }
        return new InputStream() {
            private long total;

            @Override
            public int read() throws IOException {
                int value = inputStream.read();
                if (value != -1) {
                    ensureWithinLimit(1);
                }
                return value;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int count = inputStream.read(b, off, len);
                if (count > 0) {
                    ensureWithinLimit(count);
                }
                return count;
            }

            @Override
            public void close() throws IOException {
                inputStream.close();
            }

            private void ensureWithinLimit(int delta) {
                total += delta;
                if (total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
            }
        };
    }

    public record HttpFetchResponse(byte[] body,
                                    String contentType,
                                    String fileName,
                                    String etag,
                                    String lastModified,
                                    Long contentLength) {
    }

    public record HttpFetchStream(Response response,
                                  InputStream bodyStream,
                                  String contentType,
                                  String fileName,
                                  String etag,
                                  String lastModified,
                                  Long contentLength) implements AutoCloseable {

        @Override
        public void close() {
            response.close();
        }
    }

    public record HttpHeadResponse(String etag, String lastModified, String contentType, Long contentLength,
                                   String fileName) {
    }
}
