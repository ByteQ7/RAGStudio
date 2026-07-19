import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, CheckCircle, ExternalLink, Loader2, Save } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import type { AiModel } from "@/services/aiModelConfigService";
import { listModels } from "@/services/aiModelConfigService";
import {
  type DefaultModelConfig,
  listDefaults,
  updateDefault
} from "@/services/defaultModelConfigService";
import { listProviders } from "@/services/aiModelConfigService";
import { getErrorMessage } from "@/utils/error";

// 配置键 → 中文描述
const CONFIG_KEY_LABELS: Record<string, string> = {
  chat: "对话默认模型",
  summary: "摘要默认模型",
  title: "话题命名默认模型",
  multimodal: "图片多模态默认模型",
  doc_image: "文档图片解析默认模型"
};

// 配置键 → 说明文字
const CONFIG_KEY_DESCRIPTIONS: Record<string, string> = {
  chat: "用户发起对话时使用的模型",
  summary: "对话历史摘要压缩时使用的模型",
  title: "自动生成对话标题时使用的模型",
  multimodal: "聊天中用户上传图片时使用的模型（需支持多模态）",
  doc_image: "文档入库时，提取嵌入图片中的文字（需支持多模态）"
};

export function DefaultModelConfigPage() {
  const [configs, setConfigs] = useState<DefaultModelConfig[]>([]);
  const [chatModels, setChatModels] = useState<AiModel[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingKey, setSavingKey] = useState<string | null>(null);
  const [dirty, setDirty] = useState<Record<string, string>>({});

  // ==================== 加载数据 ====================

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [defaults, models, providers] = await Promise.all([
        listDefaults(),
        listModels("CHAT"),
        listProviders()
      ]);
      setConfigs(defaults);
      setChatModels(models);
      setAllProviders(providers.map((p) => ({ name: p.name, apiKey: p.apiKey ?? null })));
    } catch (err) {
      toast.error(getErrorMessage(err, "加载默认模型配置失败"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData().catch(() => null);
  }, [loadData]);

  // ==================== 供应商 API Key 状态 ====================

  const [allProviders, setAllProviders] = useState<Array<{ name: string; apiKey: string | null }>>([]);

  const providerApiKeyMap = useMemo(() => {
    const map = new Map<string, boolean>();
    for (const p of allProviders) {
      map.set(p.name, p.apiKey !== null && p.apiKey !== "");
    }
    return map;
  }, [allProviders]);

  // ==================== 模型选择变更 ====================

  const handleChange = (configKey: string, modelId: string) => {
    setDirty((prev) => ({ ...prev, [configKey]: modelId }));
  };

  const handleSave = async (configKey: string) => {
    const modelId = dirty[configKey];
    if (!modelId) return;

    setSavingKey(configKey);
    try {
      await updateDefault(configKey, modelId);
      setDirty((prev) => {
        const next = { ...prev };
        delete next[configKey];
        return next;
      });
      // 刷新列表获取最新状态（含 hasApiKey）
      const defaults = await listDefaults();
      setConfigs(defaults);
      toast.success("更新成功");
    } catch (err) {
      toast.error(getErrorMessage(err, "更新失败"));
    } finally {
      setSavingKey(null);
    }
  };

  // ==================== 获取当前选中值 ====================

  const getSelectedModelId = (config: DefaultModelConfig): string => {
    return dirty[config.configKey] ?? config.modelId;
  };

  // ==================== 检查未配置 API Key 的项 ====================

  const missingApiKeyItems = useMemo(() => {
    return configs.filter(
      (c) => {
        const modelId = dirty[c.configKey] || c.modelId;
        const model = chatModels.find((m) => m.modelId === modelId);
        const providerName = model?.providerName || c.providerName;
        return !providerApiKeyMap.get(providerName);
      }
    );
  }, [configs, dirty, chatModels, providerApiKeyMap]);

  // ==================== 渲染 ====================

  if (loading) {
    return (
      <div className="admin-page">
        <div className="admin-page-header">
          <div>
            <h1 className="admin-page-title">默认模型设置</h1>
            <p className="admin-page-subtitle">管理各功能场景使用的默认 AI 模型</p>
          </div>
        </div>
        <div className="flex items-center justify-center py-20 text-gray-400">
          <Loader2 className="h-6 w-6 animate-spin" />
        </div>
      </div>
    );
  }

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">默认模型设置</h1>
          <p className="admin-page-subtitle">
            管理各功能场景使用的默认 AI 模型。未配置 API Key 的模型在对应场景中不可用。
          </p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" size="sm" onClick={() => loadData()}>
            刷新
          </Button>
        </div>
      </div>

      {/* API Key 缺失警告 */}
      {missingApiKeyItems.length > 0 && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-5 py-3.5">
          <div className="flex items-center gap-2 text-sm font-medium text-amber-700">
            <AlertTriangle className="h-4 w-4" />
            <span>以下场景的默认模型所在供应商未配置 API Key</span>
          </div>
          <ul className="mt-1.5 space-y-1">
            {missingApiKeyItems.map((item) => {
              const modelId = dirty[item.configKey] || item.modelId;
              const model = chatModels.find((m) => m.modelId === modelId);
              const providerName = model?.providerName || item.providerName;
              return (
                <li key={item.configKey} className="flex items-center gap-2 text-xs text-amber-600">
                  <span className="font-medium">{CONFIG_KEY_LABELS[item.configKey] || item.configKey}</span>
                  <span>→ {providerName}</span>
                  <a
                    href="/admin/ai-models"
                    className="inline-flex items-center gap-0.5 text-indigo-500 hover:text-indigo-700 underline"
                  >
                    去配置 <ExternalLink className="h-3 w-3" />
                  </a>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      {/* 配置列表 */}
      <div className="space-y-4">
        {configs.map((config) => {
          const selectedModelId = getSelectedModelId(config);
          const selectedModel = chatModels.find((m) => m.modelId === selectedModelId);
          const isDirty = config.configKey in dirty;
          const isSaving = savingKey === config.configKey;
          const apiKeyOk = selectedModel
            ? providerApiKeyMap.get(selectedModel.providerName)
            : config.hasApiKey;

          return (
            <div
              key={config.configKey}
              className="ui-card"
            >
              <div className="ui-card-content">
                <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0 flex-1">
                    <h3 className="ui-card-title">
                      {CONFIG_KEY_LABELS[config.configKey] || config.configKey}
                    </h3>
                    <p className="mt-0.5 text-sm text-gray-500">
                      {CONFIG_KEY_DESCRIPTIONS[config.configKey] || ""}
                    </p>
                  </div>

                  <div className="flex items-center gap-3">
                    {/* 模型选择器 */}
                    <div className="w-64">
                      <Select
                        value={selectedModelId}
                        onValueChange={(val) => handleChange(config.configKey, val)}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder="选择模型" />
                        </SelectTrigger>
                        <SelectContent>
                          {chatModels
                            .filter((m) => m.enabled === 1)
                            .sort((a, b) => a.priority - b.priority)
                            .map((model) => {
                              // 多模态模型过滤提示：doc_image 和 multimodal 建议选 supportsMultimodal=1
                              const isMultimodalScene =
                                config.configKey === "multimodal" || config.configKey === "doc_image";
                              return (
                                <SelectItem
                                  key={model.modelId}
                                  value={model.modelId}
                                  disabled={
                                    isMultimodalScene &&
                                    model.supportsMultimodal !== 1
                                  }
                                >
                                  <span className="font-mono text-sm">{model.modelId}</span>
                                  <span className="ml-2 text-xs text-gray-400">
                                    {model.providerName}
                                  </span>
                                  {isMultimodalScene && model.supportsMultimodal !== 1 && (
                                    <span className="ml-2 text-[10px] text-amber-400">
                                      不支持多模态
                                    </span>
                                  )}
                                </SelectItem>
                              );
                            })}
                        </SelectContent>
                      </Select>
                    </div>

                    {/* API Key 状态 */}
                    <div className="flex items-center gap-1.5 whitespace-nowrap text-xs">
                      {apiKeyOk ? (
                        <span className="inline-flex items-center gap-1 text-green-600">
                          <CheckCircle className="h-3.5 w-3.5" />
                          API Key 已配置
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-amber-500">
                          <AlertTriangle className="h-3.5 w-3.5" />
                          未配置 API Key
                        </span>
                      )}
                    </div>

                    {/* 保存按钮 */}
                    {isDirty && (
                      <Button
                        size="sm"
                        onClick={() => handleSave(config.configKey)}
                        disabled={isSaving}
                        className="shrink-0"
                      >
                        {isSaving ? (
                          <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />
                        ) : (
                          <Save className="mr-1 h-3.5 w-3.5" />
                        )}
                        保存
                      </Button>
                    )}
                  </div>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
