package com.byteq.ai.ragstudio.core.parser.mineru;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MinerU 文档解析 REST 客户端
 * <p>
 * 统一封装两类协议的文档解析调用：
 * <ul>
 *   <li><b>本地/自建 mineru-api</b>（{@code LOCAL}）：multipart 上传到 {@code POST /file_parse}，
 *       同步返回 {@code results.<file>.md_content}。</li>
 *   <li><b>mineru.net 官方 Agent 轻量 API</b>（{@code CLOUD_AGENT}，免费免 Token）：
 *       ① {@code POST /parse/file} 创建任务并获取 OSS 签名上传地址 →
 *       ② {@code PUT} 上传文件字节 →
 *       ③ 轮询 {@code GET /parse/{taskId}} 至 done →
 *       ④ 下载 {@code markdown_url}（full.md）。
 *       限制：单文件 ≤10MB、≤20 页、IP 限频。</li>
 * </ul>
 * 解析失败 / 超时 / 结果为空时返回空字符串，由上层 {@link MineruDocumentParser}
 * 决定是否回退 Tika / 多模态 LLM 兜底。
 * </p>
 */
@Slf4j
@Component
public class MineruClient {

    /**
     * 文件上传的 multipart 字段名（与 mineru-api 约定一致）
     */
    private static final String FILE_FIELD = "files";

    /**
     * 云端轮询间隔（毫秒）
     */
    private static final long CLOUD_POLL_INTERVAL_MS = 3000;

    private final ObjectMapper objectMapper;

    public MineruClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析文档字节为 Markdown（按端点类型自动分派协议）
     *
     * @param bytes          文档二进制内容
     * @param fileName       文件名（用于推断 MIME 与响应中的结果键）
     * @param endpoint       目标端点（本地/远程）
     * @param timeoutSeconds 解析总超时（秒）
     * @return 提取的 Markdown 内容，失败时为空字符串
     */
    public String parse(byte[] bytes, String fileName, MineruEndpoint endpoint, long timeoutSeconds) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (endpoint == null || !endpoint.isConfigured()) {
            log.warn("MinerU 端点未配置，跳过解析");
            return "";
        }
        return endpoint.isCloud()
                ? parseViaCloudAgent(bytes, fileName, endpoint, timeoutSeconds)
                : parseViaLocalApi(bytes, fileName, endpoint, timeoutSeconds);
    }

    // ==================== 本地/自建 mineru-api 协议 ====================

    /**
     * 本地 mineru-api 同步解析：multipart POST /file_parse
     */
    private String parseViaLocalApi(byte[] bytes, String fileName, MineruEndpoint endpoint, long timeoutSeconds) {
        OkHttpClient client = newClient(timeoutSeconds);
        String mime = guessMime(fileName);

        // 构造 multipart 请求体
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(FILE_FIELD, fileName, RequestBody.create(bytes, MediaType.get(mime)))
                .addFormDataPart("backend", defaultIfBlank(endpoint.backend(), "pipeline"))
                .addFormDataPart("lang_list", defaultIfBlank(endpoint.lang(), "ch"))
                .addFormDataPart("return_md", "true")
                .build();

        Request request = newRequestBuilder(endpoint)
                .url(endpoint.parseUrl())
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("MinerU 解析失败: HTTP {}, endpoint={}, file={}",
                        response.code(), endpoint.baseUrl(), fileName);
                return "";
            }
            String respBody = response.body() == null ? "" : response.body().string();
            return extractMdContent(respBody, fileName);
        } catch (IOException e) {
            log.warn("MinerU 解析异常: file={}, endpoint={}, msg={}",
                    fileName, endpoint.baseUrl(), e.getMessage());
            return "";
        }
    }

    // ==================== mineru.net 官方 Agent 轻量 API 协议 ====================

    /**
     * 云端 Agent 轻量 API 异步解析：创建任务 → PUT 签名上传 → 轮询 → 下载 Markdown
     * <p>限制：≤10MB、≤20 页；免费免 Token，IP 限频。失败返回空串触发上层兜底。</p>
     */
    private String parseViaCloudAgent(byte[] bytes, String fileName, MineruEndpoint endpoint, long timeoutSeconds) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutSeconds, 30) * 1000L;
        OkHttpClient client = cloudClient();

        // 1) 创建任务：获取 taskId + OSS 签名上传 URL
        ObjectNode createBody = objectMapper.createObjectNode();
        createBody.put("file_name", fileName);
        createBody.put("language", defaultIfBlank(endpoint.lang(), "ch"));
        createBody.put("enable_table", true);
        createBody.put("enable_formula", true);
        JsonNode created = postJson(client, endpoint.joinUrl("/parse/file"), createBody);
        if (created == null || jsonCode(created) != 0) {
            log.warn("MinerU 云端创建任务失败: file={}, resp={}", fileName, created == null ? "null" : created.toString());
            return "";
        }
        String taskId = created.path("data").path("task_id").asText(null);
        String uploadUrl = created.path("data").path("file_url").asText(null);
        if (taskId == null || uploadUrl == null) {
            log.warn("MinerU 云端响应缺少 task_id/file_url: file={}", fileName);
            return "";
        }

        // 2) PUT 上传文件字节到签名地址（无须 Content-Type）
        if (!putBytes(client, uploadUrl, bytes)) {
            log.warn("MinerU 云端文件上传失败: file={}, taskId={}", fileName, taskId);
            return "";
        }

        // 3) 轮询任务状态至 done / failed
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(CLOUD_POLL_INTERVAL_MS);
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            JsonNode status = getJson(client, endpoint.joinUrl("/parse/" + taskId));
            if (status == null) {
                continue;
            }
            int code = jsonCode(status);
            if (code != 0) {
                log.warn("MinerU 云端查询失败: taskId={}, code={}, msg={}",
                        taskId, code, status.path("msg").asText(""));
                return "";
            }
            JsonNode data = status.path("data");
            String state = data.path("state").asText("");
            switch (state) {
                case "done" -> {
                    String markdownUrl = data.path("markdown_url").asText(null);
                    if (markdownUrl == null || markdownUrl.isBlank()) {
                        log.warn("MinerU 云端完成但缺少 markdown_url: taskId={}", taskId);
                        return "";
                    }
                    String md = downloadText(client, markdownUrl);
                    log.info("MinerU 云端解析成功: file={}, taskId={}, chars={}",
                            fileName, taskId, md == null ? 0 : md.trim().length());
                    return md == null ? "" : md;
                }
                case "failed" -> {
                    log.warn("MinerU 云端解析失败: taskId={}, errCode={}, errMsg={}",
                            taskId, data.path("err_code").asInt(), data.path("err_msg").asText(""));
                    return "";
                }
                // waiting-file / uploading / pending / running：继续等待
                default -> log.debug("MinerU 云端解析中: taskId={}, state={}", taskId, state);
            }
        }
        log.warn("MinerU 云端解析超时({}s): file={}, taskId={}", timeoutSeconds, fileName, taskId);
        return "";
    }

    // ==================== 连通性探测 ====================

    /**
     * 探测端点连通性（实际发一次 GET 请求，根据响应码判断服务是否可达）
     */
    public boolean ping(MineruEndpoint endpoint) {
        if (endpoint == null || !endpoint.isConfigured()) {
            return false;
        }
        OkHttpClient client = newClient(10);
        // 云端 baseUrl 指向 API 前缀（…/api/v1/agent），探测其站点根域更准确
        String url = endpoint.isCloud() ? rootOf(endpoint.baseUrl()) : endpoint.baseUrl();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful() || response.code() == 404 || response.code() == 405;
        } catch (IOException e) {
            log.debug("MinerU 连通性探测失败: endpoint={}, msg={}", url, e.getMessage());
            return false;
        }
    }

    // ==================== HTTP 辅助方法 ====================

    /**
     * JSON POST，失败返回 null
     */
    private JsonNode postJson(OkHttpClient client, String url, ObjectNode body) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                    .build();
            return readJson(client, request);
        } catch (Exception e) {
            log.debug("MinerU JSON POST 失败: url={}, msg={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * JSON GET，失败返回 null
     */
    private JsonNode getJson(OkHttpClient client, String url) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            return readJson(client, request);
        } catch (Exception e) {
            log.debug("MinerU JSON GET 失败: url={}, msg={}", url, e.getMessage());
            return null;
        }
    }

    private JsonNode readJson(OkHttpClient client, Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String body = response.body().string();
            if (body.isBlank()) {
                return null;
            }
            return objectMapper.readTree(body);
        }
    }

    /**
     * PUT 二进制到预签名地址（OSS），无须 Content-Type
     */
    private boolean putBytes(OkHttpClient client, String url, byte[] bytes) {
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(bytes, MediaType.parse("application/octet-stream")))
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            log.debug("MinerU PUT 上传失败: msg={}", e.getMessage());
            return false;
        }
    }

    /**
     * 下载文本内容（Markdown 结果），失败返回 null
     */
    private String downloadText(OkHttpClient client, String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            return response.body().string();
        } catch (IOException e) {
            log.debug("MinerU Markdown 下载失败: url={}, msg={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 提取响应 JSON 中的业务状态码 code
     */
    private static int jsonCode(JsonNode node) {
        return node.path("code").asInt(Integer.MIN_VALUE);
    }

    /**
     * 中断安全的休眠
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 提取站点根域（scheme://host[:port]）
     */
    private static String rootOf(String url) {
        int idx = url.indexOf("://");
        if (idx < 0) {
            return url;
        }
        int from = idx + 3;
        int slash = url.indexOf('/', from);
        return slash > 0 ? url.substring(0, slash) : url;
    }

    // ==================== 从本地协议响应提取 md_content ====================

    /**
     * 从 mineru-api 响应 JSON 中提取 md_content
     * <p>
     * 结构：{ "results": { "<fileName>": { "md_content": "..." }, ... } }
     * 文件名字段因服务端处理可能变化（含路径/大小写），故遍历 results 所有值取第一个非空 md_content。
     * </p>
     */
    private String extractMdContent(String respBody, String fileName) {
        if (respBody == null || respBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(respBody);
            JsonNode results = root.path("results");
            if (results.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = results.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    JsonNode md = entry.getValue().path("md_content");
                    if (md.isTextual() && !md.asText().isBlank()) {
                        return md.asText();
                    }
                }
            }
            // 兜底：直接根级 md_content（某些版本结构不同）
            JsonNode rootMd = root.path("md_content");
            if (rootMd.isTextual() && !rootMd.asText().isBlank()) {
                return rootMd.asText();
            }
        } catch (IOException e) {
            log.warn("MinerU 响应解析失败: file={}, msg={}", fileName, e.getMessage());
        }
        log.warn("MinerU 响应中未找到 md_content: file={}", fileName);
        return "";
    }

    // ==================== 客户端构造 ====================

    /**
     * 构造带超时的 OkHttpClient（本地协议：单个长请求承载全部解析耗时）
     */
    private static OkHttpClient newClient(long timeoutSeconds) {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(Math.max(timeoutSeconds, 30), TimeUnit.SECONDS)
                .callTimeout(Math.max(timeoutSeconds, 30), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 云端协议专用客户端（每个子请求独立短超时，总时长由轮询 deadline 控制）
     */
    private static OkHttpClient cloudClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 追加可选 Bearer 鉴权头（Agent 免费接口无需；自建鉴权网关或未来精准 v4 接口使用）
     */
    private Request.Builder newRequestBuilder(MineruEndpoint endpoint) {
        Request.Builder builder = new Request.Builder();
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + endpoint.apiKey());
        }
        return builder;
    }

    /**
     * 按文件名推断 MIME 类型（仅需支持 MinerU 可处理的类型）
     */
    private static String guessMime(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }

    private static String defaultIfBlank(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }
}
