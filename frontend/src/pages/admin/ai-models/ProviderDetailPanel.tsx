import { useState, useCallback } from "react";
import {
  ChevronDown,
  ChevronRight,
  Copy,
  Eye,
  EyeOff,
  Globe,
  KeyRound,
  List,
  Loader2,
  Pencil,
  Plug,
  RefreshCw,
  Trash2,
  Wifi,
  WifiOff,
  X
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import {
  type AiProvider,
  type AiProviderPayload,
  type AiModel,
  type AiModelPayload,
  type ConnectivityResult,
  checkConnectivity,
  updateProvider
} from "@/services/aiModelConfigService";
import { getErrorMessage } from "@/utils/error";
import { ModelGroupList } from "./ModelGroupList";

// ==================== Props ====================

interface ProviderDetailPanelProps {
  provider: AiProvider | null;
  models: AiModel[];
  loadingModels: boolean;
  onUpdate: (id: string, payload: AiProviderPayload) => Promise<void>;
  onDelete: (provider: AiProvider) => void;
  onToggleProvider: (provider: AiProvider) => void;
  togglingProviderId: string | null;
  // Model operations
  onModelToggle: (model: AiModel) => void;
  onModelEdit: (model: AiModel) => void;
  onModelDelete: (model: AiModel) => void;
  onModelPriorityChange: (model: AiModel, priority: number) => void;
  togglingModelEnabledId: string | null;
  // Fetch models
  onFetchModels: () => void;
  fetchingModels: boolean;
}

// ==================== Helpers ====================

function maskApiKey(key?: string | null): string {
  if (!key) return "";
  if (key.length <= 8) return "••••••••";
  return `${key.slice(0, 4)}••••••••${key.slice(-4)}`;
}

// ==================== Component ====================

export function ProviderDetailPanel({
  provider,
  models,
  loadingModels,
  onUpdate,
  onDelete,
  onToggleProvider,
  togglingProviderId,
  onModelToggle,
  onModelEdit,
  onModelDelete,
  onModelPriorityChange,
  togglingModelEnabledId,
  onFetchModels,
  fetchingModels
}: ProviderDetailPanelProps) {
  // ---------- Local form state ----------
  const [displayName, setDisplayName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [endpointsJson, setEndpointsJson] = useState("");
  const [showApiKey, setShowApiKey] = useState(false);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [showEndpoints, setShowEndpoints] = useState(false);

  // ---------- Connectivity state ----------
  const [connectivity, setConnectivity] = useState<ConnectivityResult | null>(null);
  const [checkingConnectivity, setCheckingConnectivity] = useState(false);

  // ---------- Sync form with provider ----------
  const resetForm = useCallback((p: AiProvider) => {
    setDisplayName(p.displayName || "");
    setBaseUrl(p.baseUrl);
    setApiKey(p.apiKey || "");
    setEndpointsJson(
      p.endpoints ? JSON.stringify(p.endpoints, null, 2) : ""
    );
    setConnectivity(null);
    setEditing(false);
    setShowApiKey(false);
  }, []);

  // Sync when provider changes
  const [prevProviderId, setPrevProviderId] = useState<string | null>(null);
  if (provider && provider.id !== prevProviderId) {
    resetForm(provider);
    setPrevProviderId(provider.id);
  }
  if (!provider && prevProviderId !== null) {
    setPrevProviderId(null);
    setConnectivity(null);
  }

  // ---------- Handlers ----------

  const handleSave = async () => {
    if (!provider) return;
    if (!baseUrl.trim()) {
      toast.error("API 地址不能为空");
      return;
    }

    let parsedEndpoints: Record<string, string> | undefined;
    if (endpointsJson.trim()) {
      try {
        parsedEndpoints = JSON.parse(endpointsJson);
        if (typeof parsedEndpoints !== "object" || parsedEndpoints === null || Array.isArray(parsedEndpoints)) {
          toast.error("Endpoints 必须是一个 JSON 对象");
          return;
        }
      } catch {
        toast.error("Endpoints JSON 格式错误");
        return;
      }
    }

    const payload: AiProviderPayload = {
      displayName: displayName.trim() || undefined,
      baseUrl: baseUrl.trim(),
      apiKey: apiKey.trim() || undefined,
      endpoints: parsedEndpoints || undefined
    };

    try {
      setSaving(true);
      await onUpdate(provider.id, payload);
      toast.success("供应商信息已更新");
      setEditing(false);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
    }
  };

  const handleCheckConnectivity = async () => {
    if (!provider) return;
    try {
      setCheckingConnectivity(true);
      setConnectivity(null);
      const result = await checkConnectivity(provider.id);
      setConnectivity(result);
      if (result.success) {
        toast.success(`连通性正常 (${result.latencyMs}ms)`);
      } else {
        toast.error(`连接失败: ${result.error || "未知错误"}`);
      }
    } catch (error) {
      setConnectivity({ success: false, error: getErrorMessage(error, "检查失败") });
      toast.error(getErrorMessage(error, "连通性检查失败"));
    } finally {
      setCheckingConnectivity(false);
    }
  };

  const handleCopyApiKey = async () => {
    if (!provider?.apiKey) return;
    try {
      await navigator.clipboard.writeText(provider.apiKey);
      toast.success("API Key 已复制");
    } catch {
      toast.error("复制失败");
    }
  };

  // ---------- Empty state ----------

  if (!provider) {
    return (
      <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-4 text-gray-400">
        <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-gray-50">
          <Plug className="h-8 w-8" strokeWidth={1.5} />
        </div>
        <div className="text-center">
          <p className="text-sm font-medium text-gray-500">选择一个供应商</p>
          <p className="mt-1 text-xs text-gray-400">
            从左侧列表中选择一个 AI 供应商查看详情
          </p>
        </div>
      </div>
    );
  }

  // ---------- Render ----------

  const isProviderEnabled = provider.enabled === 1;
  const isToggling = togglingProviderId === provider.id;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* ====== 头部 ====== */}
      <div className="flex shrink-0 items-start justify-between gap-4 border-b border-gray-100 bg-white px-6 py-4">
        <div className="flex items-center gap-4">
          {provider.iconUrl ? (
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-white shadow-[0_1px_4px_rgba(0,0,0,0.04)] ring-1 ring-gray-100">
              <img
                src={provider.iconUrl}
                alt={provider.name}
                className={cn(
                  "h-6 w-6 object-contain",
                  !isProviderEnabled && "opacity-40"
                )}
              />
            </div>
          ) : (
            <div
              className={cn(
                "flex h-10 w-10 shrink-0 items-center justify-center rounded-xl text-base font-bold shadow-[0_1px_4px_rgba(0,0,0,0.04)] ring-1 ring-gray-100",
                isProviderEnabled
                  ? "bg-indigo-50 text-indigo-600"
                  : "bg-gray-50 text-gray-400"
              )}
            >
              {provider.name.charAt(0).toUpperCase()}
            </div>
          )}
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-base font-semibold text-gray-900">
                {provider.displayName || provider.name}
              </h2>
              <Badge
                className={cn(
                  "rounded-full px-3 py-0.5 text-[11px] font-medium",
                  isProviderEnabled
                    ? "bg-emerald-50 text-emerald-700 border border-emerald-200"
                    : "bg-gray-100 text-gray-500 border border-gray-200"
                )}
              >
                <span
                  className={cn(
                    "mr-1.5 inline-block h-1.5 w-1.5 rounded-full",
                    isProviderEnabled ? "bg-emerald-500" : "bg-gray-400"
                  )}
                />
                {isProviderEnabled ? "已启用" : "已禁用"}
              </Badge>
            </div>
            <p className="mt-1 text-sm text-gray-400">{provider.name}</p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* 启用/禁用开关 */}
          <div className="flex items-center gap-2 rounded-lg border border-gray-200 bg-white px-3 py-1.5">
            <span className="text-[11px] font-medium text-gray-500">
              {isProviderEnabled ? "已启用" : "已禁用"}
            </span>
            {isToggling ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin text-gray-400" />
            ) : (
              <Switch
                checked={isProviderEnabled}
                onCheckedChange={() => onToggleProvider(provider)}
                className="scale-75"
              />
            )}
          </div>

          {/* 删除 */}
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-8 w-8 p-0 text-gray-400 hover:text-red-500 hover:border-red-200 hover:bg-red-50"
                  onClick={() => onDelete(provider)}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              </TooltipTrigger>
              <TooltipContent side="bottom" className="text-xs">
                删除供应商
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        </div>
      </div>

      {/* ====== 可滚动内容 ====== */}
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div>
          {/* ====== API 配置区 ====== */}
          <section className="rounded-xl border border-gray-100 bg-white p-5 shadow-sm">
            <div className="mb-4 flex items-center justify-between">
              <h3 className="flex items-center gap-1.5 text-xs font-semibold text-gray-700">
                <KeyRound className="h-3.5 w-3.5 text-indigo-500" />
                API 配置
              </h3>
              <Button
                variant="ghost"
                size="sm"
                className={cn(
                  "h-8 gap-1.5 text-xs font-medium rounded-lg",
                  editing
                    ? "text-gray-400 hover:text-gray-600"
                    : "text-indigo-600 hover:bg-indigo-50"
                )}
                onClick={() => {
                  if (editing) {
                    resetForm(provider);
                  } else {
                    setEditing(true);
                  }
                }}
              >
                {editing ? (
                  <>
                    <X className="h-3.5 w-3.5" />
                    取消编辑
                  </>
                ) : (
                  <>
                    <Pencil className="h-3.5 w-3.5" />
                    编辑
                  </>
                )}
              </Button>
            </div>

            <div className="space-y-4">
              {/* API 地址 */}
              <div className="space-y-1.5">
                <Label className="text-xs font-medium text-gray-500">API 地址</Label>
                <div className="relative">
                  <Globe className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
                  <Input
                    value={baseUrl}
                    onChange={(e) => setBaseUrl(e.target.value)}
                    disabled={!editing}
                    className={cn(
                      "h-10 pl-10 text-sm rounded-xl",
                      !editing && "bg-gray-50 text-gray-500 border-gray-100"
                    )}
                    placeholder="https://api.openai.com/v1"
                  />
                </div>
              </div>

              {/* API Key */}
              <div className="space-y-1.5">
                <Label className="text-xs font-medium text-gray-500">API Key</Label>
                <div className="relative">
                  <KeyRound className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
                  <Input
                    value={editing ? apiKey : maskApiKey(provider.apiKey)}
                    onChange={(e) => setApiKey(e.target.value)}
                    disabled={!editing}
                    type={showApiKey || editing ? "text" : "password"}
                    className={cn(
                      "h-10 pl-10 pr-20 text-sm font-mono rounded-xl",
                      !editing && "bg-gray-50 text-gray-500 border-gray-100"
                    )}
                    placeholder={editing ? "sk-..." : ""}
                  />
                  <div className="absolute right-3 top-1/2 flex -translate-y-1/2 gap-1">
                    {editing && (
                      <button
                        type="button"
                        className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-colors"
                        onClick={() => setShowApiKey(!showApiKey)}
                      >
                        {showApiKey ? (
                          <EyeOff className="h-3.5 w-3.5" />
                        ) : (
                          <Eye className="h-3.5 w-3.5" />
                        )}
                      </button>
                    )}
                    {!editing && provider.apiKey && (
                      <button
                        type="button"
                        className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-colors"
                        onClick={handleCopyApiKey}
                      >
                        <Copy className="h-3.5 w-3.5" />
                      </button>
                    )}
                  </div>
                </div>
              </div>

              {/* 显示名称 */}
              <div className="space-y-1.5">
                <Label className="text-xs font-medium text-gray-500">显示名称</Label>
                <Input
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  disabled={!editing}
                  className={cn(
                    "h-10 text-sm rounded-xl",
                    !editing && "bg-gray-50 text-gray-500 border-gray-100"
                  )}
                  placeholder={provider.name}
                />
              </div>

              {/* Endpoints（可折叠） */}
              <div>
                <button
                  type="button"
                  className="flex items-center gap-1.5 text-xs font-medium text-gray-400 hover:text-gray-600 transition-colors"
                  onClick={() => setShowEndpoints(!showEndpoints)}
                >
                  {showEndpoints ? (
                    <ChevronDown className="h-3.5 w-3.5" />
                  ) : (
                    <ChevronRight className="h-3.5 w-3.5" />
                  )}
                  端点配置 (Endpoints)
                </button>
                {showEndpoints && (
                  <div className="mt-2">
                    <Textarea
                      value={endpointsJson}
                      onChange={(e) => setEndpointsJson(e.target.value)}
                      disabled={!editing}
                      className={cn(
                        "min-h-[80px] font-mono text-xs rounded-xl",
                        !editing && "bg-gray-50 text-gray-500 border-gray-100"
                      )}
                      placeholder='{"chat":"/v1/chat/completions"}'
                    />
                  </div>
                )}
              </div>

              {/* 保存按钮 */}
              {editing && (
                <div className="flex justify-end gap-3 pt-2 border-t border-gray-50">
                  <Button
                    variant="outline"
                    size="sm"
                    className="rounded-xl h-9"
                    onClick={() => resetForm(provider)}
                  >
                    取消
                  </Button>
                  <Button size="sm" className="rounded-xl h-9" onClick={handleSave} disabled={saving}>
                    {saving ? (
                      <>
                        <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
                        保存中
                      </>
                    ) : (
                      "保存修改"
                    )}
                  </Button>
                </div>
              )}
            </div>
          </section>

          {/* ====== 连通性检查 ====== */}
          <section className="rounded-xl border border-gray-100 bg-white p-5 shadow-sm">
            <div className="flex items-center justify-between">
              <h3 className="flex items-center gap-1.5 text-xs font-semibold text-gray-700">
                <Wifi className="h-3.5 w-3.5 text-indigo-500" />
                连通性
              </h3>
              <Button
                variant="outline"
                size="sm"
                className="h-9 gap-1.5 text-xs rounded-xl"
                onClick={handleCheckConnectivity}
                disabled={checkingConnectivity}
              >
                {checkingConnectivity ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <RefreshCw className="h-3.5 w-3.5" />
                )}
                {checkingConnectivity ? "检测中..." : "连通性检查"}
              </Button>
            </div>

            {/* 连通性结果 */}
            {connectivity && (
              <div
                className={cn(
                  "mt-4 flex items-center gap-4 rounded-xl border px-5 py-4",
                  connectivity.success
                    ? "border-emerald-200 bg-emerald-50/50"
                    : "border-red-200 bg-red-50/50"
                )}
              >
                {connectivity.success ? (
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-100">
                    <Wifi className="h-5 w-5 text-emerald-600" />
                  </div>
                ) : (
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-red-100">
                    <WifiOff className="h-5 w-5 text-red-600" />
                  </div>
                )}
                <div className="flex-1">
                  <p
                    className={cn(
                      "text-sm font-medium",
                      connectivity.success ? "text-emerald-700" : "text-red-700"
                    )}
                  >
                    {connectivity.success ? "连接正常" : "连接失败"}
                  </p>
                  {connectivity.success && connectivity.latencyMs != null && (
                    <p className="mt-0.5 text-xs text-emerald-600">
                      延迟: {connectivity.latencyMs}ms
                    </p>
                  )}
                  {!connectivity.success && connectivity.error && (
                    <p className="mt-0.5 text-xs text-red-600">{connectivity.error}</p>
                  )}
                </div>
                {connectivity.success && (
                  <Badge className={cn(
                    "rounded-full px-3 py-0.5 text-[11px] font-medium",
                    connectivity.latencyMs != null && connectivity.latencyMs < 300
                      ? "bg-emerald-100 text-emerald-700 border border-emerald-200"
                      : "bg-amber-100 text-amber-700 border border-amber-200"
                  )}>
                    {connectivity.latencyMs != null && connectivity.latencyMs < 300
                      ? "低延迟"
                      : "中等"}
                  </Badge>
                )}
              </div>
            )}
          </section>

          {/* ====== 模型列表 ====== */}
          <section className="rounded-xl border border-gray-100 bg-white p-5 shadow-sm">
            <div className="mb-4 flex items-center justify-between">
              <h3 className="flex items-center gap-1.5 text-xs font-semibold text-gray-700">
                <List className="h-3.5 w-3.5 text-indigo-500" />
                模型列表
                <span className="text-xs font-normal text-gray-400 ml-1">
                  {models.length} 个
                </span>
              </h3>
              <Button
                variant="outline"
                size="sm"
                className="h-9 gap-1.5 text-xs rounded-xl"
                onClick={onFetchModels}
                disabled={fetchingModels}
              >
                {fetchingModels ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <RefreshCw className="h-3.5 w-3.5" />
                )}
                {fetchingModels ? "获取中..." : "从供应商获取"}
              </Button>
            </div>

            {loadingModels ? (
              <div className="flex items-center justify-center py-12 text-gray-400">
                <Loader2 className="mr-2 h-5 w-5 animate-spin" />
                加载模型列表...
              </div>
            ) : (
              <ModelGroupList
                models={models}
                providerEnabled={isProviderEnabled}
                togglingEnabledId={togglingModelEnabledId}
                onToggle={onModelToggle}

                onEdit={onModelEdit}
                onDelete={onModelDelete}
                onPriorityChange={onModelPriorityChange}
              />
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
