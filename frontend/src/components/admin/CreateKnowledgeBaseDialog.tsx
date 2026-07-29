import { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import * as z from "zod";
import { toast } from "sonner";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";

import { createKnowledgeBase } from "@/services/knowledgeService";
import { getSystemSettings, type ModelCandidate } from "@/services/settingsService";
import { checkModelConnectivity, type ConnectivityResult } from "@/services/aiModelConfigService";
import { getErrorMessage } from "@/utils/error";
import { Loader2, Wifi, WifiOff } from "lucide-react";

const formSchema = z.object({
  name: z.string().min(1, "请输入知识库名称").max(50, "名称不能超过50个字符"),
  description: z.string().max(500, "描述不能超过500个字符").optional(),
  embeddingModel: z.string().min(1, "请选择Embedding模型"),
  dimension: z.number().positive("请选择向量维度"),
  collectionName: z
    .string()
    .min(1, "请输入Collection名称")
    .max(128, "名称不能超过128个字符")
    .regex(/^[a-zA-Z0-9_\-.]+$/, "只能包含英文字母、数字、下划线、短横线和点"),
});

type FormValues = z.infer<typeof formSchema>;

interface CreateKnowledgeBaseDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export function CreateKnowledgeBaseDialog({
  open,
  onOpenChange,
  onSuccess,
}: CreateKnowledgeBaseDialogProps) {
  const [loading, setLoading] = useState(false);
  const [modelLoading, setModelLoading] = useState(false);
  const [embeddingModels, setEmbeddingModels] = useState<ModelCandidate[]>([]);
  const [probeResult, setProbeResult] = useState<ConnectivityResult | null>(null);
  const [probing, setProbing] = useState(false);

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: "",
      description: "",
      embeddingModel: "",
      dimension: undefined as unknown as number,
      collectionName: "",
    },
  });

  const selectedModelId = form.watch("embeddingModel");
  const selectedModel = useMemo(
    () => embeddingModels.find((m) => m.id === selectedModelId),
    [embeddingModels, selectedModelId]
  );

  const isMultimodalModel = selectedModel?.supportsMultimodal === true;

  const availableDimensions = useMemo(() => {
    const dims = selectedModel?.dimensions;
    if (dims && dims.length > 0) return dims;
    const single = selectedModel?.dimension;
    if (single && single > 0) return [single];
    return [1536];
  }, [selectedModel]);

  useEffect(() => {
    if (!open) return;
    let active = true;
    setModelLoading(true);
    getSystemSettings()
      .then((settings) => {
        if (!active) return;
        const candidates = settings.ai?.embedding?.candidates || [];
        const enabledModels = candidates.filter((item) => item.enabled !== false);
        setEmbeddingModels(enabledModels);
      })
      .catch(() => {
        if (active) {
          setEmbeddingModels([]);
        }
      })
      .finally(() => {
        if (active) {
          setModelLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [open, form]);

  const selectOptions = useMemo(() => {
    if (embeddingModels.length === 0) return [];
    const uniqueMap = new Map<string, ModelCandidate>();
    embeddingModels.forEach((item) => {
      if (item.id) {
        uniqueMap.set(item.id, item);
      }
    });
    return Array.from(uniqueMap.values());
  }, [embeddingModels]);

  useEffect(() => {
    if (selectedModel && availableDimensions.length > 0) {
      const current = form.getValues("dimension");
      if (!current || !availableDimensions.includes(current)) {
        form.setValue("dimension", availableDimensions[availableDimensions.length - 1]);
      }
    }
  }, [selectedModel, availableDimensions, form]);

  // 选择 Embedding 模型后自动发送探测请求
  useEffect(() => {
    if (!selectedModelId) {
      setProbeResult(null);
      return;
    }
    let active = true;
    setProbing(true);
    setProbeResult(null);
    checkModelConnectivity(selectedModelId)
      .then((result) => {
        if (active) setProbeResult(result);
      })
      .catch((error) => {
        if (active) setProbeResult({ success: false, error: getErrorMessage(error, "探测失败") });
      })
      .finally(() => {
        if (active) setProbing(false);
      });
    return () => { active = false; };
  }, [selectedModelId]);

  const onSubmit = async (values: FormValues) => {
    try {
      setLoading(true);
      await createKnowledgeBase({
        ...values,
        embeddingProvider: selectedModel?.provider,
      });
      toast.success("创建成功");
      form.reset();
      onOpenChange(false);
      onSuccess();
    } catch (error) {
      toast.error(getErrorMessage(error, "创建失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const handleDialogOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      form.reset({
        name: "",
        description: "",
        embeddingModel: "",
        dimension: undefined as unknown as number,
        collectionName: "",
      });
    }
    onOpenChange(nextOpen);
  };

  return (
    <Dialog open={open} onOpenChange={handleDialogOpenChange}>
      <DialogContent
        className="sm:max-w-[500px] max-h-[85vh] overflow-y-auto"
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>创建知识库</DialogTitle>
          <DialogDescription>
            创建一个新的知识库，用于存储和检索文档
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>知识库名称</FormLabel>
                  <FormControl>
                    <Input placeholder="例如：产品文档库" {...field} />
                  </FormControl>
                  <FormDescription>为知识库起一个易于识别的名称</FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="description"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>知识库描述</FormLabel>
                  <FormControl>
                    <Textarea
                      placeholder="例如：Java相关技术文档，包括Spring、MyBatis等框架"
                      className="resize-none"
                      rows={2}
                      {...field}
                    />
                  </FormControl>
                  <FormDescription>
                    用于帮助 AI 判断问题是否与此知识库相关（可选）
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="embeddingModel"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Embedding模型</FormLabel>
                  <Select
                    value={field.value}
                    onValueChange={(v) => {
                      field.onChange(v);
                      form.setValue("dimension", undefined as unknown as number);
                    }}
                  >
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder="选择Embedding模型" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {modelLoading ? (
                        <SelectItem value="loading" disabled>
                          加载中...
                        </SelectItem>
                      ) : selectOptions.length === 0 ? (
                        <SelectItem value="empty" disabled>
                          暂无可用模型
                        </SelectItem>
                      ) : (
                        selectOptions.map((item) => {
                          const label =
                            item.provider && item.model
                              ? `${item.provider} · ${item.model}`
                              : item.model || item.id;
                          return (
                            <SelectItem key={item.id} value={item.id}>
                              {label}
                            </SelectItem>
                          );
                        })
                      )}
                    </SelectContent>
                  </Select>
                  <FormDescription>选择用于向量化文档的模型</FormDescription>
                  {isMultimodalModel && (
                    <div className="mt-1.5 rounded-lg bg-purple-50 px-3 py-2 text-xs text-purple-700">
                      该模型为多模态 Embedding 模型，图片将以视觉向量方式入库，PDF 将按页面智能处理
                    </div>
                  )}
                  {selectedModelId && (
                    <div className="mt-1 flex items-center gap-1.5">
                      {probing ? (
                        <>
                          <Loader2 className="h-3 w-3 animate-spin text-gray-400" />
                          <span className="text-[11px] text-gray-400">正在探测模型可用性...</span>
                        </>
                      ) : probeResult ? (
                        probeResult.success ? (
                          <span className="inline-flex items-center gap-1 rounded-lg bg-emerald-50 px-2 py-0.5 text-[11px] font-medium text-emerald-700">
                            <Wifi className="h-3 w-3" />
                            {probeResult.latencyMs ?? "?"}ms
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 rounded-lg bg-red-50 px-2 py-0.5 text-[11px] font-medium text-red-600">
                            <WifiOff className="h-3 w-3" />
                            {probeResult.error || "不可用"}
                          </span>
                        )
                      ) : null}
                    </div>
                  )}
                  <FormMessage />
                </FormItem>
              )}
            />

            {selectedModel && availableDimensions.length > 0 && (
              <FormField
                control={form.control}
                name="dimension"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>向量维度</FormLabel>
                    <Select
                      value={String(field.value ?? "")}
                      onValueChange={(v) => field.onChange(Number(v))}
                    >
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue placeholder="选择向量维度" />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {availableDimensions.map((dim) => {
                          const overLimit = dim > 2000;
                          return (
                            <SelectItem key={dim} value={String(dim)}>
                              {dim}维 {overLimit ? "(超过2000维，需确认 pgvector 支持)" : ""}
                            </SelectItem>
                          );
                        })}
                      </SelectContent>
                    </Select>
                    <FormDescription>维度超过 2000 需在 pgvector 启用 max_dim 参数，并重启数据库</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            <FormField
              control={form.control}
              name="collectionName"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Collection名称</FormLabel>
                  <FormControl>
                    <Input placeholder="例如：productdocs" {...field} />
                  </FormControl>
                  <FormDescription>
                    只能包含英文字母、数字、下划线、短横线和点
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => handleDialogOpenChange(false)}
                disabled={loading}
              >
                取消
              </Button>
              <Button type="submit" disabled={loading}>
                {loading ? "创建中..." : "创建"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
