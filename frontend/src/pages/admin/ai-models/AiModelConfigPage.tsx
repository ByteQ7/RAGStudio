import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Bot,
  Cpu,
  Loader2,
  Pencil,
  Plus,
  RefreshCw,
  Star,
  Trash2
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
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
  updatePriorities
} from "@/services/aiModelConfigService";
import { getErrorMessage } from "@/utils/error";

// ==================== Constants ====================

const CAPABILITY_GROUPS = [
  { key: "CHAT", label: "Chat" },
  { key: "EMBEDDING", label: "Embedding" },
  { key: "RERANK", label: "Rerank" }
] as const;

const emptyProviderForm = (): AiProviderPayload & { endpointsJson: string } => ({
  name: "",
  displayName: "",
  baseUrl: "",
  apiKey: "",
  endpoints: {},
  enabled: 1,
  endpointsJson: ""
});

const emptyModelForm = (): AiModelPayload => ({
  providerId: "",
  modelId: "",
  modelName: "",
  capability: "CHAT",
  priority: 100,
  enabled: 1,
  supportsThinking: 0,
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
      apiKey: "",
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
      if (providerDialogMode === "create") {
        await createProvider(payload);
        toast.success("供应商创建成功");
      } else if (editingProviderId) {
        await updateProvider(editingProviderId, payload);
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

  const handleProviderDelete = async () => {
    if (!deleteProviderTarget) return;
    try {
      await deleteProvider(deleteProviderTarget.id);
      toast.success("供应商已删除");
      setDeleteProviderTarget(null);
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

  const openCreateModel = () => {
    setModelForm(emptyModelForm());
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

  const handlePriorityBlur = async (model: AiModel, newPriority: number) => {
    if (newPriority === model.priority) return;
    try {
      await updatePriorities([{ id: model.id, priority: newPriority }]);
      toast.success("优先级已更新");
      await loadModels();
    } catch (error) {
      toast.error(getErrorMessage(error, "更新优先级失败"));
    }
  };

  // ==================== Derived data ====================

  const modelsByCapability = useMemo(() => {
    const grouped: Record<string, AiModel[]> = {};
    for (const group of CAPABILITY_GROUPS) {
      grouped[group.key] = models.filter((m) => m.capability === group.key);
    }
    return grouped;
  }, [models]);

  const providerMap = useMemo(() => {
    const map = new Map<string, AiProvider>();
    for (const p of providers) {
      map.set(p.id, p);
    }
    return map;
  }, [providers]);

  // ==================== Render ====================

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-900">AI 模型配置</h1>
          <p className="mt-1 text-sm text-gray-500">
            管理 AI 模型供应商与模型，配置默认模型、优先级和启用状态
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={handleRefreshAll}
          disabled={loadingProviders || loadingModels}
        >
          <RefreshCw
            className={cn(
              "mr-1.5 h-4 w-4",
              (loadingProviders || loadingModels) && "animate-spin"
            )}
          />
          刷新
        </Button>
      </div>

      <Tabs defaultValue="providers" className="space-y-6">
        <TabsList>
          <TabsTrigger value="providers">供应商管理</TabsTrigger>
          <TabsTrigger value="models">模型管理</TabsTrigger>
        </TabsList>

        {/* ==================== Providers Tab ==================== */}
        <TabsContent value="providers">
          <div className="mb-4 flex items-center justify-between">
            <p className="text-sm text-gray-500">
              管理模型服务供应商的连接信息与认证凭据
            </p>
            <Button size="sm" onClick={openCreateProvider}>
              <Plus className="mr-1.5 h-4 w-4" />
              新增供应商
            </Button>
          </div>

          <Card>
            <CardContent className="px-4 py-0">
              {loadingProviders && providers.length === 0 ? (
                <div className="flex items-center justify-center py-20 text-gray-400">
                  <Loader2 className="mr-2 h-5 w-5 animate-spin" />
                  加载中...
                </div>
              ) : providers.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-20 text-gray-400">
                  <Cpu className="h-10 w-10" strokeWidth={1.5} />
                  <p className="mt-3 text-sm">暂无供应商配置</p>
                  <Button
                    variant="outline"
                    size="sm"
                    className="mt-4"
                    onClick={openCreateProvider}
                  >
                    <Plus className="mr-1.5 h-4 w-4" />
                    添加第一个
                  </Button>
                </div>
              ) : (
                <Table className="table-auto w-full">
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[140px] px-5">名称</TableHead>
                      <TableHead className="w-[150px] px-5">显示名称</TableHead>
                      <TableHead className="px-5">API 地址</TableHead>
                      <TableHead className="w-[160px] px-5">API 密钥</TableHead>
                      <TableHead className="w-[90px] px-5 text-center">启用</TableHead>
                      <TableHead className="w-[130px] px-5 text-center">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {providers.map((provider) => (
                      <TableRow key={provider.id}>
                        <TableCell className="px-5 py-3.5">
                          <span className="font-medium text-gray-900">
                            {provider.name}
                          </span>
                        </TableCell>
                        <TableCell className="px-5 py-3.5 text-sm text-gray-600">
                          {provider.displayName || "-"}
                        </TableCell>
                        <TableCell className="px-5 py-3.5">
                          <code className="rounded bg-gray-50 px-1.5 py-0.5 text-xs text-gray-600">
                            {provider.baseUrl}
                          </code>
                        </TableCell>
                        <TableCell className="px-5 py-3.5">
                          <code className="text-xs text-gray-500">
                            {maskApiKey(provider.apiKey)}
                          </code>
                        </TableCell>
                        <TableCell className="px-5 py-3.5 text-center">
                          <div className="flex items-center justify-center gap-2">
                            {togglingProviderId === provider.id ? (
                              <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
                            ) : (
                              <Switch
                                checked={provider.enabled === 1}
                                onCheckedChange={() => handleProviderToggle(provider)}
                              />
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="px-5 py-3.5 text-center">
                          <div className="flex items-center justify-center gap-1">
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-8 px-2 text-xs"
                              onClick={() => openEditProvider(provider)}
                              title="编辑"
                            >
                              <Pencil className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-8 px-2 text-xs text-red-500 hover:text-red-600 hover:bg-red-50"
                              onClick={() => setDeleteProviderTarget(provider)}
                              title="删除"
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </Button>
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ==================== Models Tab ==================== */}
        <TabsContent value="models">
          <div className="mb-4 flex items-center justify-between">
            <p className="text-sm text-gray-500">
              按能力分类管理模型，设置默认模型与优先级
            </p>
            <Button size="sm" onClick={openCreateModel}>
              <Plus className="mr-1.5 h-4 w-4" />
              新增模型
            </Button>
          </div>

          {loadingModels && models.length === 0 ? (
            <Card>
              <CardContent className="px-4 py-0">
                <div className="flex items-center justify-center py-20 text-gray-400">
                  <Loader2 className="mr-2 h-5 w-5 animate-spin" />
                  加载中...
                </div>
              </CardContent>
            </Card>
          ) : (
            <div className="space-y-8">
              {CAPABILITY_GROUPS.map((group) => {
                const groupModels = modelsByCapability[group.key] || [];
                return (
                  <div key={group.key}>
                    <div className="mb-3 flex items-center gap-2">
                      <Badge
                        variant="outline"
                        className="text-xs font-semibold uppercase tracking-wide"
                      >
                        {group.label}
                      </Badge>
                      <span className="text-xs text-gray-400">
                        {groupModels.length} 个模型
                      </span>
                    </div>

                    <Card>
                      <CardContent className="px-4 py-0">
                        {groupModels.length === 0 ? (
                          <div className="flex flex-col items-center justify-center py-12 text-gray-400">
                            <Bot className="h-8 w-8" strokeWidth={1.5} />
                            <p className="mt-2 text-sm">暂无数据</p>
                          </div>
                        ) : (
                          <Table className="table-auto w-full">
                            <TableHeader>
                              <TableRow>
                                <TableHead className="w-[160px] px-5">
                                  模型标识
                                </TableHead>
                                <TableHead className="w-[120px] px-5">
                                  供应商
                                </TableHead>
                                <TableHead className="w-[160px] px-5">
                                  实际模型名
                                </TableHead>
                                <TableHead className="w-[90px] px-5 text-center">
                                  优先级
                                </TableHead>
                                <TableHead className="w-[90px] px-5 text-center">
                                  默认模型
                                </TableHead>
                                <TableHead className="w-[90px] px-5 text-center">
                                  深度思考
                                </TableHead>
                                <TableHead className="w-[80px] px-5 text-center">
                                  启用
                                </TableHead>
                                <TableHead className="w-[130px] px-5 text-center">
                                  操作
                                </TableHead>
                              </TableRow>
                            </TableHeader>
                            <TableBody>
                              {groupModels.map((model) => (
                                <TableRow key={model.id}>
                                  <TableCell className="px-5 py-3.5">
                                    <span className="font-medium text-gray-900">
                                      {model.modelId}
                                    </span>
                                  </TableCell>
                                  <TableCell className="px-5 py-3.5 text-sm text-gray-600">
                                    {model.providerName ||
                                      providerMap.get(model.providerId)?.name ||
                                      "-"}
                                  </TableCell>
                                  <TableCell className="px-5 py-3.5">
                                    <code className="rounded bg-gray-50 px-1.5 py-0.5 text-xs text-gray-600">
                                      {model.modelName}
                                    </code>
                                  </TableCell>
                                  <TableCell className="px-5 py-3.5 text-center">
                                    <Input
                                      type="number"
                                      className="h-7 w-16 mx-auto text-center text-sm"
                                      defaultValue={model.priority}
                                      key={`${model.id}-${model.priority}`}
                                      onBlur={(e) => {
                                        const val = parseInt(e.target.value, 10);
                                        handlePriorityBlur(
                                          model,
                                          isNaN(val) ? 0 : val
                                        );
                                      }}
                                      onKeyDown={(e) => {
                                        if (e.key === "Enter") {
                                          (e.target as HTMLInputElement).blur();
                                        }
                                      }}
                                    />
                                  </TableCell>
                                  <TableCell className="px-5 py-3.5 text-center">
                                    <button
                                      className={cn(
                                        "inline-flex items-center justify-center rounded-md p-1.5 transition-colors",
                                        model.isDefault === 1
                                          ? "text-amber-500 hover:text-amber-600"
                                          : "text-gray-300 hover:text-amber-400"
                                      )}
                                      onClick={() => handleSetDefault(model)}
                                      disabled={settingDefaultId === model.id}
                                      title={
                                        model.isDefault === 1
                                          ? "当前为默认模型"
                                          : "设为默认模型"
                                      }
                                    >
                                      {settingDefaultId === model.id ? (
                                        <Loader2 className="h-4 w-4 animate-spin" />
                                      ) : (
                                        <Star
                                          className="h-4 w-4"
                                          fill={
                                            model.isDefault === 1
                                              ? "currentColor"
                                              : "none"
                                          }
                                        />
                                      )}
                                    </button>
                                  </TableCell>
                                  <TableCell className="px-5 py-3.5 text-center">
                                    {model.supportsThinking === 1 ? (
                                      <Badge className="bg-violet-50 text-violet-600 border-violet-200 hover:bg-violet-50 text-[11px]">
                                        支持
                                      </Badge>
                                    ) : (
                                      <span className="text-xs text-gray-400">
                                        -
                                      </span>
                                    )}
                                  </TableCell>
                                  <TableCell className="px-5 py-3.5 text-center">
                                    <div className="flex items-center justify-center gap-2">
                                      {togglingModelEnabledId === model.id ? (
                                        <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
                                      ) : (
                                        <Switch
                                          checked={model.enabled === 1}
                                          onCheckedChange={() =>
                                            handleModelToggle(model)
                                          }
                                        />
                                      )}
                                    </div>
                                  </TableCell>
                                  <TableCell className="px-5 py-3.5 text-center">
                                    <div className="flex items-center justify-center gap-1">
                                      <Button
                                        variant="ghost"
                                        size="sm"
                                        className="h-8 px-2 text-xs"
                                        onClick={() => openEditModel(model)}
                                        title="编辑"
                                      >
                                        <Pencil className="h-3.5 w-3.5" />
                                      </Button>
                                      <Button
                                        variant="ghost"
                                        size="sm"
                                        className="h-8 px-2 text-xs text-red-500 hover:text-red-600 hover:bg-red-50"
                                        onClick={() =>
                                          setDeleteModelTarget(model)
                                        }
                                        title="删除"
                                      >
                                        <Trash2 className="h-3.5 w-3.5" />
                                      </Button>
                                    </div>
                                  </TableCell>
                                </TableRow>
                              ))}
                            </TableBody>
                          </Table>
                        )}
                      </CardContent>
                    </Card>
                  </div>
                );
              })}
            </div>
          )}
        </TabsContent>
      </Tabs>

      {/* ==================== Provider Create/Edit Dialog ==================== */}
      <Dialog open={providerDialogOpen} onOpenChange={setProviderDialogOpen}>
        <DialogContent className="sm:max-w-[580px]">
          <DialogHeader>
            <DialogTitle>
              {providerDialogMode === "create"
                ? "新增供应商"
                : "编辑供应商"}
            </DialogTitle>
            <DialogDescription>
              {providerDialogMode === "create"
                ? "添加一个 AI 模型服务供应商，配置 API 地址与认证信息。"
                : "修改供应商的连接信息和配置。"}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="grid gap-2">
              <Label>
                名称 <span className="text-red-400">*</span>
              </Label>
              <Input
                placeholder="如：openai"
                value={providerForm.name || ""}
                disabled={providerDialogMode === "edit"}
                onChange={(e) =>
                  setProviderForm({ ...providerForm, name: e.target.value })
                }
              />
              <p className="text-xs text-gray-400">
                {providerDialogMode === "edit"
                  ? "供应商标识不可修改"
                  : "供应商标识，用于模型关联（英文）"}
              </p>
            </div>
            <div className="grid gap-2">
              <Label>显示名称</Label>
              <Input
                placeholder="如：OpenAI"
                value={providerForm.displayName || ""}
                onChange={(e) =>
                  setProviderForm({
                    ...providerForm,
                    displayName: e.target.value
                  })
                }
              />
            </div>
            <div className="grid gap-2">
              <Label>
                API 地址 <span className="text-red-400">*</span>
              </Label>
              <Input
                placeholder="如：https://api.openai.com"
                value={providerForm.baseUrl || ""}
                onChange={(e) =>
                  setProviderForm({
                    ...providerForm,
                    baseUrl: e.target.value
                  })
                }
              />
            </div>
            <div className="grid gap-2">
              <Label>API 密钥</Label>
              <Input
                type="password"
                placeholder="如：sk-..."
                value={providerForm.apiKey || ""}
                onChange={(e) =>
                  setProviderForm({ ...providerForm, apiKey: e.target.value })
                }
              />
              <p className="text-xs text-gray-400">
                编辑时留空表示不修改原有密钥
              </p>
            </div>
            <div className="grid gap-2">
              <Label>Endpoints</Label>
              <Textarea
                placeholder='JSON 格式，如：{"chat": "/v1/chat/completions", "embedding": "/v1/embeddings"}'
                rows={4}
                value={providerForm.endpointsJson || ""}
                onChange={(e) =>
                  setProviderForm({
                    ...providerForm,
                    endpointsJson: e.target.value
                  })
                }
              />
              <p className="text-xs text-gray-400">
                自定义 API 端点映射，JSON 对象格式，留空表示使用默认端点
              </p>
            </div>
            <div className="flex items-center justify-between rounded-md border border-gray-200 px-4 py-3">
              <div>
                <Label className="text-sm font-medium text-gray-700">
                  启用状态
                </Label>
                <p className="text-xs text-gray-400">
                  禁用后该供应商下所有模型将不可用
                </p>
              </div>
              <Switch
                checked={providerForm.enabled === 1}
                onCheckedChange={(checked) =>
                  setProviderForm({
                    ...providerForm,
                    enabled: checked ? 1 : 0
                  })
                }
              />
            </div>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setProviderDialogOpen(false)}
            >
              取消
            </Button>
            <Button
              onClick={handleProviderSubmit}
              disabled={providerSubmitting}
            >
              {providerSubmitting && (
                <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
              )}
              {providerDialogMode === "create" ? "创建" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ==================== Model Create/Edit Dialog ==================== */}
      <Dialog open={modelDialogOpen} onOpenChange={setModelDialogOpen}>
        <DialogContent className="sm:max-w-[580px]">
          <DialogHeader>
            <DialogTitle>
              {modelDialogMode === "create" ? "新增模型" : "编辑模型"}
            </DialogTitle>
            <DialogDescription>
              {modelDialogMode === "create"
                ? "在指定供应商下添加一个新模型。"
                : "修改模型的配置信息。"}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="grid gap-2">
              <Label>
                供应商 <span className="text-red-400">*</span>
              </Label>
              <Select
                value={modelForm.providerId || ""}
                onValueChange={(val) =>
                  setModelForm({ ...modelForm, providerId: val })
                }
              >
                <SelectTrigger>
                  <SelectValue placeholder="请选择供应商" />
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
            <div className="grid grid-cols-2 gap-4">
              <div className="grid gap-2">
                <Label>
                  模型标识 <span className="text-red-400">*</span>
                </Label>
                <Input
                  placeholder="如：gpt-4o"
                  value={modelForm.modelId || ""}
                  onChange={(e) =>
                    setModelForm({
                      ...modelForm,
                      modelId: e.target.value
                    })
                  }
                />
                <p className="text-xs text-gray-400">系统内唯一标识</p>
              </div>
              <div className="grid gap-2">
                <Label>
                  实际模型名 <span className="text-red-400">*</span>
                </Label>
                <Input
                  placeholder="如：gpt-4o-2024-05-13"
                  value={modelForm.modelName || ""}
                  onChange={(e) =>
                    setModelForm({
                      ...modelForm,
                      modelName: e.target.value
                    })
                  }
                />
                <p className="text-xs text-gray-400">API 调用时的模型参数</p>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="grid gap-2">
                <Label>能力类型</Label>
                <Select
                  value={modelForm.capability || "CHAT"}
                  onValueChange={(val) =>
                    setModelForm({ ...modelForm, capability: val })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {CAPABILITY_GROUPS.map((g) => (
                      <SelectItem key={g.key} value={g.key}>
                        {g.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-2">
                <Label>优先级</Label>
                <Input
                  type="number"
                  value={modelForm.priority ?? 0}
                  onChange={(e) => {
                    const val = parseInt(e.target.value, 10);
                    setModelForm({
                      ...modelForm,
                      priority: isNaN(val) ? 0 : val
                    });
                  }}
                />
                <p className="text-xs text-gray-400">数值越小优先级越高</p>
              </div>
            </div>
            {modelForm.capability === "EMBEDDING" && (
              <div className="grid gap-2">
                <Label>向量维度</Label>
                <Input
                  type="number"
                  placeholder="如：1536"
                  value={modelForm.dimension ?? ""}
                  onChange={(e) => {
                    const val = parseInt(e.target.value, 10);
                    setModelForm({
                      ...modelForm,
                      dimension: isNaN(val) ? undefined : val
                    });
                  }}
                />
                <p className="text-xs text-gray-400">
                  Embedding 模型的输出向量维度
                </p>
              </div>
            )}
            <div className="grid gap-2">
              <Label>自定义 URL</Label>
              <Input
                placeholder="可选，覆盖供应商的默认 API 地址"
                value={modelForm.customUrl || ""}
                onChange={(e) =>
                  setModelForm({ ...modelForm, customUrl: e.target.value })
                }
              />
              <p className="text-xs text-gray-400">
                留空则使用供应商的默认 Base URL
              </p>
            </div>
            <div className="flex items-center justify-between rounded-md border border-gray-200 px-4 py-3">
              <div>
                <Label className="text-sm font-medium text-gray-700">
                  深度思考
                </Label>
                <p className="text-xs text-gray-400">
                  该模型是否支持深度思考 / 推理模式
                </p>
              </div>
              <Switch
                checked={modelForm.supportsThinking === 1}
                onCheckedChange={(checked) =>
                  setModelForm({
                    ...modelForm,
                    supportsThinking: checked ? 1 : 0
                  })
                }
              />
            </div>
            <div className="flex items-center justify-between rounded-md border border-gray-200 px-4 py-3">
              <div>
                <Label className="text-sm font-medium text-gray-700">
                  启用状态
                </Label>
                <p className="text-xs text-gray-400">
                  禁用后该模型将不参与模型路由
                </p>
              </div>
              <Switch
                checked={modelForm.enabled === 1}
                onCheckedChange={(checked) =>
                  setModelForm({
                    ...modelForm,
                    enabled: checked ? 1 : 0
                  })
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
              {modelSubmitting && (
                <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
              )}
              {modelDialogMode === "create" ? "创建" : "保存"}
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
    </div>
  );
}
