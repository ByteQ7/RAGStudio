import { useState, useEffect, useMemo } from "react";
import {
  CheckCircle2,
  Circle,
  Loader2,
  MessageSquare,
  FileSearch,
  ArrowUpDown,
  AlertCircle
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
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
import { cn } from "@/lib/utils";
import {
  type AiProvider,
  type RemoteModelInfo,
  type AiModel,
  type AiModelPayload,
  fetchRemoteModels,
  batchCreateModels
} from "@/services/aiModelConfigService";
import { getErrorMessage } from "@/utils/error";

// ==================== Constants ====================

const CAPABILITY_META: Record<string, { label: string; icon: any; color: string }> = {
  CHAT: { label: "对话", icon: MessageSquare, color: "text-violet-500" },
  EMBEDDING: { label: "向量化", icon: FileSearch, color: "text-emerald-500" },
  RERANK: { label: "重排序", icon: ArrowUpDown, color: "text-amber-500" }
};

// ==================== Props ====================

interface FetchModelsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  provider: AiProvider | null;
  existingModels: AiModel[];
  onImported?: () => void;
}

// ==================== Component ====================

export function FetchModelsDialog({
  open,
  onOpenChange,
  provider,
  existingModels
}: FetchModelsDialogProps) {
  const [loading, setLoading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [remoteModels, setRemoteModels] = useState<RemoteModelInfo[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [importNameOverrides, setImportNameOverrides] = useState<Record<string, string>>({});

  // 已有的 modelId 集合
  const existingModelIds = useMemo(
    () => new Set(existingModels.map((m) => m.modelId)),
    [existingModels]
  );

  // 打开时自动拉取
  useEffect(() => {
    if (!open || !provider) return;

    const doFetch = async () => {
      setLoading(true);
      setError(null);
      setRemoteModels([]);
      setSelectedIds(new Set());
      setImportNameOverrides({});

      try {
        const result = await fetchRemoteModels(provider.id);
        setRemoteModels(result.models);
      } catch (err) {
        setError(getErrorMessage(err, "获取模型列表失败"));
      } finally {
        setLoading(false);
      }
    };

    doFetch();
  }, [open, provider]);

  // 按 capability 分组
  const groupedByCapability = useMemo(() => {
    const groups: Record<string, RemoteModelInfo[]> = {};
    for (const model of remoteModels) {
      for (const cap of model.capabilities) {
        if (!groups[cap]) groups[cap] = [];
        groups[cap].push(model);
      }
    }
    return groups;
  }, [remoteModels]);

  // 判断是否已存在
  const isExisting = (modelId: string) => existingModelIds.has(modelId);

  // 全选/取消
  const toggleAll = () => {
    const selectable = remoteModels.filter((m) => !isExisting(m.modelId));
    if (selectedIds.size === selectable.length && selectable.length > 0) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(selectable.map((m) => m.modelId)));
    }
  };

  const toggleModel = (modelId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(modelId)) {
        next.delete(modelId);
      } else {
        next.add(modelId);
      }
      return next;
    });
  };

  // 批量导入
  const handleImport = async () => {
    if (!provider) return;
    if (selectedIds.size === 0) {
      toast.error("请至少选择一个模型");
      return;
    }

    const payloads: AiModelPayload[] = [];
    for (const model of remoteModels) {
      if (!selectedIds.has(model.modelId)) continue;

      const displayName = importNameOverrides[model.modelId]?.trim() || model.modelName;

      for (const cap of model.capabilities) {
        payloads.push({
          providerId: provider.id,
          modelId: model.modelId,
          modelName: displayName,
          capability: cap,
          enabled: 1,
          priority: 100,
          supportsThinking: model.supportsThinking ? 1 : 0,
          supportsMultimodal: model.supportsMultimodal ? 1 : 0,
          dimension: model.dimension
        });
      }
    }

    try {
      setImporting(true);
      await batchCreateModels(payloads);
      toast.success(`成功导入 ${selectedIds.size} 个模型`);
      onImported?.();
      onOpenChange(false);
    } catch (err) {
      toast.error(getErrorMessage(err, "导入失败"));
    } finally {
      setImporting(false);
    }
  };

  const selectableCount = remoteModels.filter((m) => !isExisting(m.modelId)).length;
  const availableCaps = Object.keys(CAPABILITY_META).filter((cap) => groupedByCapability[cap]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[640px] max-h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            从供应商获取模型
            {provider && (
              <Badge variant="outline" className="text-xs font-normal">
                {provider.displayName || provider.name}
              </Badge>
            )}
          </DialogTitle>
          <DialogDescription>
            拉取该供应商支持的模型列表，勾选后批量导入到系统
          </DialogDescription>
        </DialogHeader>

        {/* 内容区 */}
        <div className="flex-1 overflow-y-auto min-h-0">
          {/* 加载中 */}
          {loading && (
            <div className="flex flex-col items-center justify-center py-16 text-gray-400">
              <Loader2 className="mb-3 h-8 w-8 animate-spin" />
              <p className="text-sm">正在获取模型列表...</p>
            </div>
          )}

          {/* 错误 */}
          {!loading && error && (
            <div className="flex flex-col items-center justify-center py-16 text-red-400">
              <AlertCircle className="mb-3 h-8 w-8" />
              <p className="text-sm font-medium text-red-500">获取失败</p>
              <p className="mt-1 text-xs text-red-400">{error}</p>
              <Button
                variant="outline"
                size="sm"
                className="mt-4"
                onClick={() => {
                  // Re-trigger the fetch
                  setLoading(true);
                  setError(null);
                  if (provider) {
                    fetchRemoteModels(provider.id)
                      .then((r) => setRemoteModels(r.models))
                      .catch((e) => setError(getErrorMessage(e, "获取失败")))
                      .finally(() => setLoading(false));
                  }
                }}
              >
                重试
              </Button>
            </div>
          )}

          {/* 模型列表 */}
          {!loading && !error && remoteModels.length === 0 && (
            <div className="flex flex-col items-center justify-center py-16 text-gray-400">
              <AlertCircle className="mb-3 h-8 w-8" strokeWidth={1.5} />
              <p className="text-sm">该供应商暂无可获取的模型列表</p>
            </div>
          )}

          {!loading && !error && remoteModels.length > 0 && (
            <div className="space-y-4">
              {/* 操作栏 */}
              <div className="flex items-center justify-between rounded-lg bg-gray-50 px-4 py-2.5">
                <button
                  type="button"
                  className="flex items-center gap-2 text-xs font-medium text-gray-500 hover:text-indigo-600"
                  onClick={toggleAll}
                >
                  {selectedIds.size === selectableCount && selectableCount > 0 ? (
                    <CheckCircle2 className="h-4 w-4 text-indigo-500" />
                  ) : (
                    <Circle className="h-4 w-4" />
                  )}
                  {selectedIds.size === selectableCount && selectableCount > 0
                    ? "取消全选"
                    : "全选"}
                  <span className="text-gray-300">
                    ({selectableCount} 个可选)
                  </span>
                </button>
                <span className="text-xs text-gray-400">
                  已选 {selectedIds.size} 个
                </span>
              </div>

              {/* 按能力分组 */}
              {availableCaps.map((cap) => {
                const meta = CAPABILITY_META[cap];
                const models = groupedByCapability[cap] || [];
                // 去重（同一个模型可能有多个 capability）
                const unique = models.filter(
                  (m, i, arr) => arr.findIndex((x) => x.modelId === m.modelId) === i
                );

                return (
                  <div key={cap}>
                    <div className="mb-2 flex items-center gap-2 px-1">
                      <meta.icon className={cn("h-4 w-4", meta.color)} />
                      <span className="text-xs font-semibold text-gray-600">
                        {meta.label}
                      </span>
                      <span className="text-xs text-gray-400">
                        ({unique.length})
                      </span>
                    </div>

                    <div className="space-y-1">
                      {unique.map((model) => {
                        const exists = isExisting(model.modelId);
                        const selected = selectedIds.has(model.modelId);

                        return (
                          <div
                            key={`${cap}-${model.modelId}`}
                            className={cn(
                              "flex items-center gap-3 rounded-xl border px-4 py-3 transition-colors",
                              exists
                                ? "border-gray-100 bg-gray-50/50"
                                : selected
                                ? "border-indigo-200 bg-indigo-50/50"
                                : "border-gray-100 bg-white hover:border-gray-200"
                            )}
                          >
                            {/* 勾选框 */}
                            <button
                              type="button"
                              onClick={() => !exists && toggleModel(model.modelId)}
                              disabled={exists}
                              className="shrink-0"
                            >
                              {exists ? (
                                <CheckCircle2 className="h-5 w-5 text-gray-300" />
                              ) : selected ? (
                                <CheckCircle2 className="h-5 w-5 text-indigo-500" />
                              ) : (
                                <Circle className="h-5 w-5 text-gray-300 hover:text-gray-400" />
                              )}
                            </button>

                            {/* 信息 */}
                            <div className="min-w-0 flex-1">
                              <div className="flex items-center gap-2">
                                <span
                                  className={cn(
                                    "text-sm font-medium",
                                    exists ? "text-gray-400" : "text-gray-900"
                                  )}
                                >
                                  {model.modelId}
                                </span>
                                {model.supportsThinking && (
                                  <Badge className="rounded-full border-violet-200 bg-violet-50 px-2 py-0 text-[10px] text-violet-600">
                                    思考
                                  </Badge>
                                )}
                                {model.supportsMultimodal && (
                                  <Badge className="rounded-full border-emerald-200 bg-emerald-50 px-2 py-0 text-[10px] text-emerald-600">
                                    多模态
                                  </Badge>
                                )}
                                {exists && (
                                  <Badge className="rounded-full border-gray-200 bg-gray-100 px-2 py-0 text-[10px] text-gray-500">
                                    已存在
                                  </Badge>
                                )}
                              </div>

                              {/* 导入后显示名 */}
                              {!exists && (
                                <div className="mt-1.5 flex items-center gap-2">
                                  <span className="text-[11px] text-gray-400">
                                    显示名称:
                                  </span>
                                  <Input
                                    value={
                                      importNameOverrides[model.modelId] ?? model.modelName
                                    }
                                    onChange={(e) => {
                                      setImportNameOverrides((prev) => ({
                                        ...prev,
                                        [model.modelId]: e.target.value
                                      }));
                                    }}
                                    placeholder={model.modelName}
                                    className="h-7 w-48 text-xs"
                                    onClick={(e) => e.stopPropagation()}
                                  />
                                </div>
                              )}
                            </div>

                            {/* 能力标签 */}
                            <div className="flex shrink-0 gap-1">
                              {model.capabilities.map((c) => {
                                const m = CAPABILITY_META[c];
                                if (!m) return null;
                                const Icon = m.icon;
                                return (
                                  <Badge
                                    key={c}
                                    variant="outline"
                                    className={cn(
                                      "rounded-full px-2 py-0 text-[10px]",
                                      exists
                                        ? "border-gray-200 text-gray-400"
                                        : m.color
                                    )}
                                  >
                                    <Icon className="mr-0.5 h-3 w-3" />
                                    {m.label}
                                  </Badge>
                                );
                              })}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* 底部 */}
        <DialogFooter className="border-t border-gray-100 pt-4">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button
            onClick={handleImport}
            disabled={importing || selectedIds.size === 0}
          >
            {importing ? (
              <>
                <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
                导入中...
              </>
            ) : (
              `导入选中 (${selectedIds.size})`
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
