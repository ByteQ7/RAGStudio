import { useState, useMemo } from "react";
import {
  Bot,
  Loader2,
  Pencil,
  Star,
  Trash2,
  MessageSquare,
  FileSearch,
  ArrowUpDown
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import type { AiModel } from "@/services/aiModelConfigService";

// ==================== Constants ====================

const CAPABILITY_META: Record<string, { label: string; icon: any; color: string; dotColor: string }> = {
  CHAT: {
    label: "对话",
    icon: MessageSquare,
    color: "text-violet-600",
    dotColor: "bg-violet-500"
  },
  EMBEDDING: {
    label: "向量化",
    icon: FileSearch,
    color: "text-emerald-600",
    dotColor: "bg-emerald-500"
  },
  RERANK: {
    label: "重排序",
    icon: ArrowUpDown,
    color: "text-amber-600",
    dotColor: "bg-amber-500"
  }
};

// ==================== Props ====================

interface ModelGroupListProps {
  models: AiModel[];
  providerEnabled: boolean;
  togglingEnabledId: string | null;
  settingDefaultId: string | null;
  onToggle: (model: AiModel) => void;
  onSetDefault: (model: AiModel) => void;
  onEdit: (model: AiModel) => void;
  onDelete: (model: AiModel) => void;
  onPriorityChange: (model: AiModel, priority: number) => void;
}

// ==================== Component ====================

export function ModelGroupList({
  models,
  providerEnabled,
  togglingEnabledId,
  settingDefaultId,
  onToggle,
  onSetDefault,
  onEdit,
  onDelete,
  onPriorityChange
}: ModelGroupListProps) {
  // 找出有模型的 capability
  const availableCaps = useMemo(() => {
    const caps = new Set(models.map((m) => m.capability));
    return ["CHAT", "EMBEDDING", "RERANK"].filter((c) => caps.has(c));
  }, [models]);

  // 默认选中第一个有模型的分类
  const [activeCap, setActiveCap] = useState<string | null>(null);

  // 当 models 变化时自动选择第一个有模型的分类
  useMemo(() => {
    if (availableCaps.length > 0) {
      if (!activeCap || !availableCaps.includes(activeCap)) {
        setActiveCap(availableCaps[0]);
      }
    } else {
      setActiveCap(null);
    }
  }, [availableCaps.join(",")]);

  const currentModels = useMemo(
    () => models.filter((m) => m.capability === activeCap),
    [models, activeCap]
  );

  // 当没有模型时
  if (models.length === 0) {
    return (
      <div className="flex flex-col items-center gap-3 py-12 text-gray-400">
        <Bot className="h-10 w-10" strokeWidth={1.5} />
        <div className="text-center">
          <p className="text-sm font-medium text-gray-500">暂无模型</p>
          <p className="mt-1 text-xs text-gray-400">
            点击"从供应商获取"按钮拉取模型列表
          </p>
        </div>
      </div>
    );
  }

  return (
    <div>
      {/* Tab 导航 */}
      <Tabs
        value={activeCap ?? undefined}
        onValueChange={(val) => setActiveCap(val)}
        className="w-full"
      >
        <TabsList className="w-full justify-start gap-1 rounded-xl bg-gray-50/80 p-1">
          {availableCaps.map((cap) => {
            const meta = CAPABILITY_META[cap];
            if (!meta) return null;
            const count = models.filter((m) => m.capability === cap).length;
            return (
              <TabsTrigger
                key={cap}
                value={cap}
                className={cn(
                  "flex items-center gap-1.5 rounded-lg px-4 py-2 text-xs font-medium transition-all data-[state=active]:bg-white data-[state=active]:shadow-sm",
                  cap === "CHAT" && "data-[state=active]:text-violet-700",
                  cap === "EMBEDDING" && "data-[state=active]:text-emerald-700",
                  cap === "RERANK" && "data-[state=active]:text-amber-700"
                )}
              >
                <meta.icon className={cn("h-3.5 w-3.5", meta.color)} />
                {meta.label}
                <span className="ml-0.5 rounded-md bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500 tabular-nums">
                  {count}
                </span>
              </TabsTrigger>
            );
          })}
        </TabsList>

        {/* Tab 内容 */}
        {availableCaps.map((cap) => (
          <TabsContent key={cap} value={cap} className="mt-3 space-y-1.5">
            {currentModels.map((model) => {
              const isDisabled = model.enabled !== 1;
              const isModelDefault = model.isDefault === 1;

              return (
                <div
                  key={model.id}
                  className={cn(
                    "group flex items-center gap-3 rounded-xl border px-4 py-3.5 transition-all duration-150",
                    isDisabled
                      ? "border-gray-100 bg-gray-50/50"
                      : "border-gray-100 bg-white hover:border-gray-200 hover:shadow-sm",
                    !providerEnabled && "opacity-50"
                  )}
                >
                  {/* 模型信息 */}
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span
                        className={cn(
                          "truncate text-sm font-medium",
                          isDisabled ? "text-gray-400" : "text-gray-900"
                        )}
                      >
                        {model.modelName}
                      </span>
                      {isModelDefault && (
                        <Badge className="rounded-full border-amber-200 bg-amber-50 px-2 py-0 text-[10px] font-medium text-amber-700">
                          默认
                        </Badge>
                      )}
                      {model.supportsThinking === 1 && (
                        <Badge className="rounded-full border-violet-200 bg-violet-50 px-2 py-0 text-[10px] text-violet-600">
                          深度思考
                        </Badge>
                      )}
                      {model.supportsMultimodal === 1 && (
                        <Badge className="rounded-full border-emerald-200 bg-emerald-50 px-2 py-0 text-[10px] text-emerald-600">
                          多模态
                        </Badge>
                      )}
                    </div>
                    <span className="mt-0.5 block truncate text-xs text-gray-400">
                      {model.modelId}
                      {model.customUrl && (
                        <span className="ml-2 text-gray-300">· 自定义地址</span>
                      )}
                    </span>
                  </div>

                  {/* 优先级 */}
                  <div className="flex items-center gap-1.5">
                    <span className="text-[11px] text-gray-400">优先级</span>
                    <Input
                      type="number"
                      className={cn(
                        "h-7 w-16 text-center text-xs rounded-lg",
                        isDisabled && "bg-gray-100"
                      )}
                      defaultValue={model.priority}
                      key={`${model.id}-${model.priority}`}
                      disabled={!providerEnabled}
                      onBlur={(e) => {
                        const val = parseInt(e.target.value, 10);
                        if (!isNaN(val) && val !== model.priority) {
                          onPriorityChange(model, val);
                        }
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          (e.target as HTMLInputElement).blur();
                        }
                      }}
                    />
                  </div>

                  {/* 操作区 */}
                  <div className="flex items-center gap-0.5">
                    <TooltipProvider>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <button
                            className={cn(
                              "inline-flex items-center justify-center rounded-lg p-1.5 transition-colors",
                              isModelDefault
                                ? "text-amber-500"
                                : "text-gray-300 hover:text-amber-400"
                            )}
                            onClick={() => onSetDefault(model)}
                            disabled={settingDefaultId === model.id || !providerEnabled}
                          >
                            {settingDefaultId === model.id ? (
                              <Loader2 className="h-3.5 w-3.5 animate-spin" />
                            ) : (
                              <Star
                                className="h-3.5 w-3.5"
                                fill={isModelDefault ? "currentColor" : "none"}
                              />
                            )}
                          </button>
                        </TooltipTrigger>
                        <TooltipContent side="top" className="text-xs">
                          {isModelDefault ? "当前默认模型" : "设为默认"}
                        </TooltipContent>
                      </Tooltip>
                    </TooltipProvider>

                    <TooltipProvider>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <button
                            className="inline-flex items-center justify-center rounded-lg p-1.5 text-gray-300 transition-colors hover:text-gray-600"
                            onClick={() => onEdit(model)}
                            disabled={!providerEnabled}
                          >
                            <Pencil className="h-3.5 w-3.5" />
                          </button>
                        </TooltipTrigger>
                        <TooltipContent side="top" className="text-xs">
                          编辑
                        </TooltipContent>
                      </Tooltip>
                    </TooltipProvider>

                    <TooltipProvider>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <button
                            className="inline-flex items-center justify-center rounded-lg p-1.5 text-gray-300 transition-colors hover:text-red-500"
                            onClick={() => onDelete(model)}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        </TooltipTrigger>
                        <TooltipContent side="top" className="text-xs">
                          删除
                        </TooltipContent>
                      </Tooltip>
                    </TooltipProvider>

                    <div className="ml-1.5 pl-1.5 border-l border-gray-100">
                      {togglingEnabledId === model.id ? (
                        <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
                      ) : (
                        <Switch
                          checked={model.enabled === 1}
                          onCheckedChange={() => onToggle(model)}
                          disabled={!providerEnabled}
                          className={cn(!providerEnabled && "cursor-not-allowed opacity-40")}
                        />
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </TabsContent>
        ))}
      </Tabs>
    </div>
  );
}
