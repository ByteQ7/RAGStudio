import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Image,
  Loader2,
  Plus
} from "lucide-react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { cn } from "@/lib/utils";
import {
  type AiProvider,
  type AiModel,
  type AiProviderPayload,
  type AiModelPayload,
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  listModels,
  createModel,
  updateModel,
  deleteModel,
  setDefaultModel,
  updatePriorities,
  uploadProviderIcon
} from "@/services/aiModelConfigService";
import { getErrorMessage } from "@/utils/error";

import { ProviderListPanel } from "./ProviderListPanel";
import { ProviderDetailPanel } from "./ProviderDetailPanel";
import { FetchModelsDialog } from "./FetchModelsDialog";

// ==================== Constants ====================

const CAPABILITIES = ["CHAT", "EMBEDDING", "RERANK"] as const;

const emptyProviderForm = (): AiProviderPayload & { endpointsJson: string; iconFile?: File | null } => ({
  name: "",
  displayName: "",
  baseUrl: "",
  apiKey: "",
  endpoints: {},
  enabled: 1,
  endpointsJson: "",
  iconFile: null
});

const emptyModelForm = (): AiModelPayload & { modelId_display?: string } => ({
  providerId: "",
  modelId: "",
  modelName: "",
  capability: "CHAT",
  priority: 100,
  enabled: 1,
  supportsThinking: 0,
  supportsMultimodal: 0,
  dimension: undefined,
  customUrl: ""
});

// ==================== Helpers ====================

function maskApiKey(key?: string | null): string {
  if (!key) return "-";
  if (key.length <= 8) return "****";
  return `${key.slice(0, 4)}****${key.slice(-4)}`;
}

// ==================== Component ====================

export function AiModelConfigPage() {
  // ---------- Data state ----------
  const [providers, setProviders] = useState<AiProvider[]>([]);
  const [models, setModels] = useState<AiModel[]>([]);
  const [loadingProviders, setLoadingProviders] = useState(true);
  const [loadingModels, setLoadingModels] = useState(true);
  const [selectedProviderId, setSelectedProviderId] = useState<string | null>(null);

  // ---------- Provider dialog state ----------
  const [providerDialogOpen, setProviderDialogOpen] = useState(false);
  const [providerDialogMode, setProviderDialogMode] = useState<"create" | "edit">("create");
  const [editingProviderId, setEditingProviderId] = useState<string | null>(null);
  const [providerForm, setProviderForm] = useState(emptyProviderForm());
  const [providerSubmitting, setProviderSubmitting] = useState(false);

  // ---------- Model dialog state ----------
  const [modelDialogOpen, setModelDialogOpen] = useState(false);
  const [modelDialogMode, setModelDialogMode] = useState<"create" | "edit">("create");
  const [editingModelId, setEditingModelId] = useState<string | null>(null);
  const [modelForm, setModelForm] = useState(emptyModelForm());
  const [modelSubmitting, setModelSubmitting] = useState(false);

  // ---------- Delete state ----------
  const [deleteProviderTarget, setDeleteProviderTarget] = useState<AiProvider | null>(null);
  const [deleteModelTarget, setDeleteModelTarget] = useState<AiModel | null>(null);

  // ---------- Action state ----------
  const [togglingProviderId, setTogglingProviderId] = useState<string | null>(null);
  const [settingDefaultId, setSettingDefaultId] = useState<string | null>(null);
  const [togglingModelEnabledId, setTogglingModelEnabledId] = useState<string | null>(null);
  const [fetchingModels, setFetchingModels] = useState(false);
  const [fetchDialogOpen, setFetchDialogOpen] = useState(false);

  // ==================== Loaders ====================

  const loadProviders = useCallback(async () => {
    try {
      setLoadingProviders(true);
      const data = await listProviders();
      setProviders(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载供应商列表失败"));
    } finally {
      setLoadingProviders(false);
    }
  }, []);

  const loadModels = useCallback(async () => {
    try {
      setLoadingModels(true);
      const data = await listModels();
      setModels(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载模型列表失败"));
    } finally {
      setLoadingModels(false);
    }
  }, []);

  useEffect(() => {
    loadProviders();
    loadModels();
  }, [loadProviders, loadModels]);

  const handleRefreshAll = () => {
    loadProviders();
    loadModels();
  };

  // ==================== Provider CRUD ====================

  const openCreateProvider = () => {
    setProviderForm(emptyProviderForm());
    setProviderDialogMode("create");
    setEditingProviderId(null);
    setProviderDialogOpen(true);
  };

  const openEditProvider = (provider: AiProvider) => {
    const endpointsJson = provider.endpoints
      ? JSON.stringify(provider.endpoints, null, 2)
      : "";
    setProviderForm({
      name: provider.name,
      displayName: provider.displayName || "",
      baseUrl: provider.baseUrl,
      apiKey: provider.apiKey || "",
      endpoints: provider.endpoints || {},
      enabled: provider.enabled,
      endpointsJson
    });
    setProviderDialogMode("edit");
    setEditingProviderId(provider.id);
    setProviderDialogOpen(true);
  };

  const handleProviderSubmit = async () => {
    if (!providerForm.name?.trim()) {
      toast.error("请输入供应商名称");
      return;
    }
    if (!providerForm.baseUrl?.trim()) {
      toast.error("请输入 API 地址");
      return;
    }

    let endpoints: Record<string, string> | undefined;
    if (providerForm.endpointsJson?.trim()) {
      try {
        endpoints = JSON.parse(providerForm.endpointsJson);
        if (typeof endpoints !== "object" || endpoints === null || Array.isArray(endpoints)) {
          toast.error("Endpoints 必须是一个 JSON 对象");
          return;
        }
      } catch {
        toast.error("Endpoints JSON 格式错误");
        return;
      }
    }

    const payload: AiProviderPayload = {
      name: providerForm.name.trim(),
      displayName: providerForm.displayName?.trim() || undefined,
      baseUrl: providerForm.baseUrl.trim(),
      apiKey: providerForm.apiKey?.trim() || undefined,
      endpoints: endpoints || undefined,
      enabled: providerForm.enabled ?? 1
    };

    try {
      setProviderSubmitting(true);
      let providerId: string | null = null;
      if (providerDialogMode === "create") {
        providerId = await createProvider(payload);
        // 创建成功后上传图标
        if (providerForm.iconFile && providerId) {
          try {
            await uploadProviderIcon(providerId, providerForm.iconFile);
          } catch {
            toast.warning("供应商已创建，但图标上传失败");
          }
        }
        toast.success("供应商创建成功");
      } else if (editingProviderId) {
        await updateProvider(editingProviderId, payload);
        // 编辑模式下上传图标
        if (providerForm.iconFile) {
          try {
            await uploadProviderIcon(editingProviderId, providerForm.iconFile);
          } catch {
            toast.warning("供应商已更新，但图标上传失败");
          }
        }
        toast.success("供应商更新成功");
      }
      setProviderDialogOpen(false);
      await loadProviders();
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    } finally {
      setProviderSubmitting(false);
    }
  };

  const handleProviderUpdate = async (id: string, payload: AiProviderPayload) => {
    await updateProvider(id, payload);
    await loadProviders();
    await loadModels();
  };

  const handleProviderDelete = async () => {
    if (!deleteProviderTarget) return;
    try {
      await deleteProvider(deleteProviderTarget.id);
      toast.success("供应商已删除");
      setDeleteProviderTarget(null);
      if (selectedProviderId === deleteProviderTarget.id) {
        setSelectedProviderId(null);
      }
      await loadProviders();
      await loadModels();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    } finally {
      setDeleteProviderTarget(null);
    }
  };

  const handleProviderToggle = async (provider: AiProvider) => {
    const nextEnabled = provider.enabled === 1 ? 0 : 1;
    try {
      setTogglingProviderId(provider.id);
      await updateProvider(provider.id, { enabled: nextEnabled });
      toast.success(nextEnabled === 1 ? "已启用" : "已禁用");
      await loadProviders();
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    } finally {
      setTogglingProviderId(null);
    }
  };

  // ==================== Model CRUD ====================

  const openCreateModel = (preferredProviderId?: string) => {
    setModelForm({
      ...emptyModelForm(),
      providerId: preferredProviderId || ""
    });
    setModelDialogMode("create");
    setEditingModelId(null);
    setModelDialogOpen(true);
  };

  const openEditModel = (model: AiModel) => {
    setModelForm({
      providerId: model.providerId,
      modelId: model.modelId,
      modelName: model.modelName,
      capability: model.capability,
      priority: model.priority,
      enabled: model.enabled,
      supportsThinking: model.supportsThinking,
      supportsMultimodal: model.supportsMultimodal ?? 0,
      dimension: model.dimension ?? undefined,
      customUrl: model.customUrl || ""
    });
    setModelDialogMode("edit");
    setEditingModelId(model.id);
    setModelDialogOpen(true);
  };

  const handleModelSubmit = async () => {
    if (!modelForm.providerId) {
      toast.error("请选择供应商");
      return;
    }
    if (!modelForm.modelId?.trim()) {
      toast.error("请输入模型标识");
      return;
    }
    if (!modelForm.modelName?.trim()) {
      toast.error("请输入实际模型名");
      return;
    }

    const payload: AiModelPayload = {
      providerId: modelForm.providerId,
      modelId: modelForm.modelId.trim(),
      modelName: modelForm.modelName.trim(),
      capability: modelForm.capability || "CHAT",
      priority: modelForm.priority ?? 0,
      enabled: modelForm.enabled ?? 1,
      supportsThinking: modelForm.supportsThinking ?? 0,
      supportsMultimodal: modelForm.supportsMultimodal ?? 0,
      dimension: modelForm.dimension,
      customUrl: modelForm.customUrl?.trim() || undefined
    };

    try {
      setModelSubmitting(true);
      if (modelDialogMode === "create") {
        await createModel(payload);
        toast.success("模型创建成功");
      } else if (editingModelId) {
        await updateModel(editingModelId, payload);
        toast.success("模型更新成功");
      }
      setModelDialogOpen(false);
      await loadModels();
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    } finally {
      setModelSubmitting(false);
    }
  };

  const handleModelDelete = async () => {
    if (!deleteModelTarget) return;
    try {
      await deleteModel(deleteModelTarget.id);
      toast.success("模型已删除");
      setDeleteModelTarget(null);
      await loadModels();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    } finally {
      setDeleteModelTarget(null);
    }
  };

  const handleSetDefault = async (model: AiModel) => {
    try {
      setSettingDefaultId(model.id);
      await setDefaultModel(model.id);
      toast.success(`已将 ${model.modelName} 设为默认模型`);
      await loadModels();
    } catch (error) {
      toast.error(getErrorMessage(error, "设置默认模型失败"));
    } finally {
      setSettingDefaultId(null);
    }
  };

  const handleModelToggle = async (model: AiModel) => {
    const nextEnabled = model.enabled === 1 ? 0 : 1;
    try {
      setTogglingModelEnabledId(model.id);
      await updateModel(model.id, { enabled: nextEnabled });
      toast.success(nextEnabled === 1 ? "已启用" : "已禁用");
      await loadModels();
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    } finally {
      setTogglingModelEnabledId(null);
    }
  };

  const handlePriorityChange = async (model: AiModel, newPriority: number) => {
    if (newPriority === model.priority) return;
    try {
      await updatePriorities([{ id: model.id, priority: newPriority }]);
      await loadModels();
    } catch (error) {
      toast.error(getErrorMessage(error, "更新优先级失败"));
    }
  };

  // ==================== Fetch models ====================

  const handleFetchModels = () => {
    if (!selectedProviderId) return;
    setFetchDialogOpen(true);
  };

  // ==================== Derived data ====================

  // 当前选中的供应商
  const selectedProvider = useMemo(
    () => providers.find((p) => p.id === selectedProviderId) ?? null,
    [providers, selectedProviderId]
  );

  // 当前选中供应商的模型
  const filteredModels = useMemo(
    () => models.filter((m) => m.providerId === selectedProviderId),
    [models, selectedProviderId]
  );

  // 获取选中供应商时自动选中第一个
  useEffect(() => {
    if (selectedProviderId && !selectedProvider) {
      // 如果选中的供应商被删除了，重置
      setSelectedProviderId(null);
    }
  }, [selectedProvider, selectedProviderId]);

  // ==================== Render ====================

  return (
    <div className="flex h-full gap-0">
      {/* ====== 左栏：供应商列表 ====== */}
      <div className="flex w-56 shrink-0 flex-col overflow-hidden border-r border-gray-100 bg-white">
        {/* 标题区 */}
        <div className="flex items-center justify-between border-b border-gray-100 px-4 py-3">
          <div>
            <h1 className="text-xs font-semibold text-gray-900">供应商</h1>
            <p className="text-[10px] text-gray-400 mt-0.5">{providers.length} 个</p>
          </div>
          <Button
            variant="ghost"
            size="sm"
            className="h-8 gap-1.5 text-xs font-medium text-indigo-600 px-3 hover:bg-indigo-50 hover:text-indigo-700 -mr-1.5"
            onClick={openCreateProvider}
          >
            <Plus className="h-3.5 w-3.5" />
            新增
          </Button>
        </div>

        {/* 列表 */}
        <div className="flex-1 overflow-y-auto px-2 py-2">
          <ProviderListPanel
            providers={providers}
            loading={loadingProviders}
            selectedId={selectedProviderId}
            onSelect={setSelectedProviderId}
          />
        </div>
      </div>

      {/* ====== 右栏：供应商详情 ====== */}
      <div className="flex-1 min-h-0 bg-gray-50/50 flex flex-col">
        {loadingModels && filteredModels.length === 0 && selectedProviderId ? (
          <div className="flex h-full items-center justify-center text-gray-400">
            <Loader2 className="mr-2 h-5 w-5 animate-spin" />
            加载中...
          </div>
        ) : (
          <ProviderDetailPanel
            provider={selectedProvider}
            models={filteredModels}
            loadingModels={loadingModels}
            onUpdate={handleProviderUpdate}
            onDelete={setDeleteProviderTarget}
            onToggleProvider={handleProviderToggle}
            togglingProviderId={togglingProviderId}
            // Model operations
            onModelToggle={handleModelToggle}
            onModelSetDefault={handleSetDefault}
            onModelEdit={openEditModel}
            onModelDelete={setDeleteModelTarget}
            onModelPriorityChange={handlePriorityChange}
            togglingModelEnabledId={togglingModelEnabledId}
            settingDefaultId={settingDefaultId}
            // Fetch models
            onFetchModels={handleFetchModels}
            fetchingModels={fetchingModels}
          />
        )}
      </div>

      {/* ==================== Provider Create/Edit Dialog ==================== */}
      <Dialog open={providerDialogOpen} onOpenChange={setProviderDialogOpen}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>
              {providerDialogMode === "create" ? "新增供应商" : "编辑供应商"}
            </DialogTitle>
            <DialogDescription>
              {providerDialogMode === "create"
                ? "添加一个 AI 模型服务供应商"
                : "修改供应商的配置信息"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">
                供应商标识 <span className="text-red-400">*</span>
              </Label>
              <Input
                value={providerForm.name}
                onChange={(e) =>
                  setProviderForm((prev) => ({ ...prev, name: e.target.value }))
                }
                disabled={providerDialogMode === "edit"}
                placeholder="siliconflow / deepseek / bailian"
                className={providerDialogMode === "edit" ? "bg-gray-50" : ""}
              />
              {providerDialogMode === "edit" && (
                <p className="text-xs text-gray-400">供应商标识创建后不可修改</p>
              )}
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">显示名称</Label>
              <Input
                value={providerForm.displayName}
                onChange={(e) =>
                  setProviderForm((prev) => ({
                    ...prev,
                    displayName: e.target.value
                  }))
                }
                placeholder="硅基流动"
              />
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">
                API 地址 <span className="text-red-400">*</span>
              </Label>
              <Input
                value={providerForm.baseUrl}
                onChange={(e) =>
                  setProviderForm((prev) => ({ ...prev, baseUrl: e.target.value }))
                }
                placeholder="https://api.siliconflow.cn/v1"
              />
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">API Key</Label>
              <Input
                value={providerForm.apiKey}
                onChange={(e) =>
                  setProviderForm((prev) => ({ ...prev, apiKey: e.target.value }))
                }
                type="password"
                placeholder="sk-..."
              />
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">供应商图标</Label>
              <div className="flex items-center gap-3">
                {providerForm.iconFile ? (
                  <div className="relative h-10 w-10 shrink-0 rounded-lg border border-gray-200 overflow-hidden">
                    <img src={URL.createObjectURL(providerForm.iconFile)} alt="预览" className="h-full w-full object-contain" />
                  </div>
                ) : providerDialogMode === "edit" && editingProviderId ? (
                  <div className="h-10 w-10 shrink-0 rounded-lg border border-gray-200 bg-gray-50 flex items-center justify-center text-xs text-gray-400">
                    无
                  </div>
                ) : null}
                <label className="cursor-pointer inline-flex items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50 transition-colors">
                  <Image className="h-3.5 w-3.5" />
                  {providerForm.iconFile ? "更换图标" : "选择图标"}
                  <input
                    type="file"
                    accept=".svg,.png,.jpg,.jpeg,.webp"
                    className="hidden"
                    onChange={(e) => {
                      const file = e.target.files?.[0];
                      if (file) {
                        setProviderForm((prev) => ({ ...prev, iconFile: file }));
                      }
                    }}
                  />
                </label>
                {providerForm.iconFile && (
                  <button
                    type="button"
                    className="text-xs text-gray-400 hover:text-red-500 transition-colors"
                    onClick={() => setProviderForm((prev) => ({ ...prev, iconFile: null }))}
                  >
                    清除
                  </button>
                )}
              </div>
              <p className="text-xs text-gray-400">支持 SVG、PNG、JPG，推荐使用 SVG</p>
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">启用状态</Label>
              <div className="flex items-center gap-3">
                <Switch
                  checked={providerForm.enabled === 1}
                  onCheckedChange={(checked) =>
                    setProviderForm((prev) => ({
                      ...prev,
                      enabled: checked ? 1 : 0
                    }))
                  }
                />
                <span className="text-sm text-gray-500">
                  {providerForm.enabled === 1 ? "已启用" : "已禁用"}
                </span>
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setProviderDialogOpen(false)}
            >
              取消
            </Button>
            <Button onClick={handleProviderSubmit} disabled={providerSubmitting}>
              {providerSubmitting ? (
                <>
                  <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
                  提交中
                </>
              ) : providerDialogMode === "create" ? (
                "创建"
              ) : (
                "保存"
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ==================== Model Create/Edit Dialog ==================== */}
      <Dialog open={modelDialogOpen} onOpenChange={setModelDialogOpen}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>
              {modelDialogMode === "create" ? "新增模型" : "编辑模型"}
            </DialogTitle>
            <DialogDescription>
              {modelDialogMode === "create"
                ? "添加一个新的 AI 模型配置"
                : "修改模型配置信息"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">
                所属供应商 <span className="text-red-400">*</span>
              </Label>
              <Select
                value={modelForm.providerId}
                onValueChange={(v) =>
                  setModelForm((prev) => ({ ...prev, providerId: v }))
                }
              >
                <SelectTrigger>
                  <SelectValue placeholder="选择供应商" />
                </SelectTrigger>
                <SelectContent>
                  {providers.map((p) => (
                    <SelectItem key={p.id} value={p.id}>
                      {p.displayName || p.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">
                模型标识 <span className="text-red-400">*</span>
              </Label>
              <Input
                value={modelForm.modelId}
                onChange={(e) =>
                  setModelForm((prev) => ({ ...prev, modelId: e.target.value }))
                }
                disabled={modelDialogMode === "edit"}
                placeholder="deepseek-chat"
                className={modelDialogMode === "edit" ? "bg-gray-50" : ""}
              />
              {modelDialogMode === "edit" && (
                <p className="text-xs text-gray-400">模型标识创建后不可修改</p>
              )}
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">
                实际模型名 <span className="text-red-400">*</span>
              </Label>
              <Input
                value={modelForm.modelName}
                onChange={(e) =>
                  setModelForm((prev) => ({ ...prev, modelName: e.target.value }))
                }
                placeholder="DeepSeek Chat"
              />
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">能力类型</Label>
              <Select
                value={modelForm.capability}
                onValueChange={(v) =>
                  setModelForm((prev) => ({
                    ...prev,
                    capability: v,
                    dimension: v === "EMBEDDING" ? prev.dimension : undefined
                  }))
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="CHAT">对话 (CHAT)</SelectItem>
                  <SelectItem value="EMBEDDING">向量化 (EMBEDDING)</SelectItem>
                  <SelectItem value="RERANK">重排序 (RERANK)</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {modelForm.capability === "EMBEDDING" && (
              <div className="space-y-2">
                <Label className="text-sm font-medium text-gray-700">
                  向量维度
                </Label>
                <Input
                  type="number"
                  value={modelForm.dimension ?? ""}
                  onChange={(e) =>
                    setModelForm((prev) => ({
                      ...prev,
                      dimension: e.target.value
                        ? parseInt(e.target.value, 10)
                        : undefined
                    }))
                  }
                  placeholder="1024"
                />
              </div>
            )}

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">优先级</Label>
              <Input
                type="number"
                value={modelForm.priority}
                onChange={(e) =>
                  setModelForm((prev) => ({
                    ...prev,
                    priority: parseInt(e.target.value, 10) || 0
                  }))
                }
              />
            </div>

            <div className="space-y-2">
              <Label className="text-sm font-medium text-gray-700">自定义地址</Label>
              <Input
                value={modelForm.customUrl}
                onChange={(e) =>
                  setModelForm((prev) => ({ ...prev, customUrl: e.target.value }))
                }
                placeholder="可选，覆盖供应商的 API 地址"
              />
            </div>

            <div className="flex items-center justify-between rounded-lg border border-gray-100 bg-gray-50/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium text-gray-700">启用状态</p>
                <p className="text-xs text-gray-400">开启后该模型可用于推理</p>
              </div>
              <Switch
                checked={modelForm.enabled === 1}
                onCheckedChange={(checked) =>
                  setModelForm((prev) => ({
                    ...prev,
                    enabled: checked ? 1 : 0
                  }))
                }
              />
            </div>

            <div className="flex items-center justify-between rounded-lg border border-gray-100 bg-gray-50/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium text-gray-700">支持深度思考</p>
                <p className="text-xs text-gray-400">如 DeepSeek-R1 的思考链</p>
              </div>
              <Switch
                checked={modelForm.supportsThinking === 1}
                onCheckedChange={(checked) =>
                  setModelForm((prev) => ({
                    ...prev,
                    supportsThinking: checked ? 1 : 0
                  }))
                }
              />
            </div>

            <div className="flex items-center justify-between rounded-lg border border-gray-100 bg-gray-50/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium text-gray-700">支持多模态</p>
                <p className="text-xs text-gray-400">支持图片输入</p>
              </div>
              <Switch
                checked={modelForm.supportsMultimodal === 1}
                onCheckedChange={(checked) =>
                  setModelForm((prev) => ({
                    ...prev,
                    supportsMultimodal: checked ? 1 : 0
                  }))
                }
              />
            </div>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setModelDialogOpen(false)}
            >
              取消
            </Button>
            <Button onClick={handleModelSubmit} disabled={modelSubmitting}>
              {modelSubmitting ? (
                <>
                  <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
                  提交中
                </>
              ) : modelDialogMode === "create" ? (
                "创建"
              ) : (
                "保存"
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ==================== Delete Provider Confirmation ==================== */}
      <AlertDialog
        open={Boolean(deleteProviderTarget)}
        onOpenChange={(open) => {
          if (!open) setDeleteProviderTarget(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除供应商？</AlertDialogTitle>
            <AlertDialogDescription>
              将删除供应商 [{deleteProviderTarget?.name}
              ]，其关联的模型可能受到影响。此操作不可恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700"
              onClick={handleProviderDelete}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* ==================== Delete Model Confirmation ==================== */}
      <AlertDialog
        open={Boolean(deleteModelTarget)}
        onOpenChange={(open) => {
          if (!open) setDeleteModelTarget(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除模型？</AlertDialogTitle>
            <AlertDialogDescription>
              将删除模型 [{deleteModelTarget?.modelId}
              ]（{deleteModelTarget?.modelName}
              ）。如果该模型是默认模型，删除后需要重新设置默认模型。此操作不可恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700"
              onClick={handleModelDelete}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* ==================== Fetch Models Dialog ==================== */}
      <FetchModelsDialog
        open={fetchDialogOpen}
        onOpenChange={setFetchDialogOpen}
        provider={selectedProvider}
        existingModels={filteredModels}
        onImported={loadModels}
      />
    </div>
  );
}
