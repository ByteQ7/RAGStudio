package com.byteq.ai.ragstudio.infra.rerank;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.http.HttpResponseHelper;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.http.ModelUrlResolver;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 百炼重排序客户端实现
 * <p>
 * 对接阿里云百炼（BaiLian）平台的重排序（Rerank）服务，对向量检索返回的候选文档
 * 进行语义级别的重新排序，提升最终送入大模型的文档相关性。
 * </p>
 * <p>
 * <b>核心流程：</b>
 * <ol>
 *   <li>对候选文档进行 ID 去重，避免重复文档影响排序结果</li>
 *   <li>调用百炼 Rerank API 对去重后的文档进行相关性评分</li>
 *   <li>按相关性得分从高到低排序，截取 topN 条结果</li>
 *   <li>如果 API 返回的结果不足 topN，用剩余候选中未出现的文档补齐</li>
 * </ol>
 * </p>
 *
 * @author byteq
 * @see RerankClient
 * @see ModelProvider#BAI_LIAN
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BaiLianRerankClient implements RerankClient {

    @Qualifier("syncHttpClient")
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    /**
     * 执行重排序操作
     * <p>
     * 先对候选文档进行 ID 去重，然后根据 topN 判断是否需要调用远程服务。
     * 当去重后的候选数量不超过 topN 时，直接返回去重结果以节省 API 调用。
     * </p>
     *
     * @param query      用户查询文本
     * @param candidates 待排序的候选文档片段列表
     * @param topN       返回前 N 个最相关的结果
     * @param target     目标模型配置
     * @return 重排序后的文档片段列表
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 候选列表为空时直接返回空列表
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 对候选文档按 ID 去重，避免相同文档重复参与排序
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        // 如果去重后数量不超过 topN，无需调用远程 rerank 服务，直接返回
        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        // 去重后数量仍超过 topN，需要调用远程服务进行精确重排序
        return doRerank(query, dedup, topN, target);
    }

    /**
     * 执行百炼 Rerank API 调用
     * <p>
     * 构建符合百炼 API 规范的请求体，发送 HTTP POST 请求，
     * 解析响应结果，按相关性得分排序后返回 topN 条文档。
     * </p>
     *
     * @param query      用户查询文本
     * @param candidates 去重后的候选文档列表
     * @param topN       返回前 N 个结果
     * @param target     目标模型配置
     * @return 重排序后的文档列表，按相关性从高到低排列
     * @throws ModelClientException 当请求失败、响应异常或解析错误时抛出
     */
    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        DynamicModelConfig.ProviderEntry provider = HttpResponseHelper.requireProvider(target, provider());

        // 参数合法性校验，避免无效请求
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        // 构建百炼 rerank API 请求体
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", HttpResponseHelper.requireModel(target, provider()));

        // input 对象包含 query 和 documents 数组
        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        input.add("documents", documentsArray);

        // parameters 对象包含 top_n 和 return_documents 等控制参数
        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        // 构建 HTTP 请求，携带 Bearer Token 认证头
        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(reqBody.toString(), okhttp3.MediaType.get("application/json; charset=utf-8")))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            // 处理 HTTP 层错误响应
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            // 网络异常处理：连接超时、DNS 解析失败等
            throw new ModelClientException(provider() + " rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 解析响应结构，校验 output 和 results 字段
        JsonObject output = requireOutput(respJson);

        JsonArray results = output.getAsJsonArray("results");
        if (CollUtil.isEmpty(results)) {
            throw new ModelClientException(provider() + " rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        // 解析排序结果，按 relevance_score 从高到低排列
        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            // 通过 index 字段关联回原始的候选文档
            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();

            // 防御性校验：防止服务端返回的 index 越界
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            RetrievedChunk src = candidates.get(idx);

            // 提取相关性得分，如果服务端未返回则使用原始分数
            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            // 如果 API 返回了评分则创建新的 RetrievedChunk 带上评分，否则直接复用原始块
            RetrievedChunk hit = score != null ? new RetrievedChunk(src.getId(), src.getText(), score) : src;
            reranked.add(hit);
            addedIds.add(src.getId());

            // 达到目标数量后提前终止
            if (reranked.size() >= topN) {
                break;
            }
        }

        // 降级策略：如果 API 返回的结果不足 topN，用原始候选中未被返回的文档补齐
        // 这确保了即使 rerank 服务返回数量不足，也不会影响下游处理
        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
                if (addedIds.add(c.getId())) {
                    reranked.add(c);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }

    /**
     * 校验并提取百炼 Rerank 响应中的 output 对象
     * <p>
     * 百炼 Rerank API 的响应结构为：{ "output": { "results": [...] } }。
     * 该方法确保响应中包含合法的 output.results 路径，便于后续解析。
     * </p>
     *
     * @param respJson 百炼 API 返回的完整 JSON 响应
     * @return 校验通过的 output JSON 对象
     * @throws ModelClientException 当 output 或 results 字段缺失时抛出
     */
    private JsonObject requireOutput(JsonObject respJson) {
        // 校验 output 顶级字段是否存在
        if (respJson == null || !respJson.has("output")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 output", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject output = respJson.getAsJsonObject("output");
        // 校验 output 下的 results 字段是否存在
        if (output == null || !output.has("results")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return output;
    }
}
