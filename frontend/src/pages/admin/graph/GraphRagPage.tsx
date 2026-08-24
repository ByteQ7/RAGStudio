import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { ExternalLink, Loader2, RefreshCw, Save } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { AiModel } from "@/services/aiModelConfigService";
import { listModels } from "@/services/aiModelConfigService";
import {
  getGraphConfig,
  getKbGraphStatuses,
  saveGraphConfig,
  type GraphConfig,
  type GraphKbStatus
} from "@/services/graphService";
import { getErrorMessage } from "@/utils/error";

const FOLLOW_CHAT_DEFAULT = "__follow_chat__";

export function GraphRagPage() {
  const [config, setConfig] = useState<GraphConfig | null>(null);
  const [chatModels, setChatModels] = useState<AiModel[]>([]);
  const [kbStatuses, setKbStatuses] = useState<GraphKbStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [statusLoading, setStatusLoading] = useState(false);
  const [initialModel, setInitialModel] = useState<{ extractModelId: string | null; followsChatDefault: boolean }>({
    extractModelId: null,
    followsChatDefault: true
  });

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [cfg, models] = await Promise.all([
        getGraphConfig(),
        listModels("CHAT", true)
      ]);
      setConfig(cfg);
      setChatModels(models);
      setInitialModel({ extractModelId: cfg.extractModelId ?? null, followsChatDefault: !!cfg.followsChatDefault });
    } catch (err) {
      toast.error(getErrorMessage(err, "加载 Graph RAG 配置失败"));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadStatuses = useCallback(async () => {
    setStatusLoading(true);
    try {
      setKbStatuses(await getKbGraphStatuses());
    } catch (err) {
      toast.error(getErrorMessage(err, "加载知识库图谱状态失败"));
    } finally {
      setStatusLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData().catch(() => null);
    loadStatuses().catch(() => null);
  }, [loadData, loadStatuses]);

  const handleSave = async () => {
    if (!config) return;
    const modelChanged =
      !!config.followsChatDefault !== initialModel.followsChatDefault ||
      (config.followsChatDefault ? "" : config.extractModelId ?? "") !== (initialModel.followsChatDefault ? "" : initialModel.extractModelId ?? "");
    setSaving(true);
    try {
      const payload: Partial<GraphConfig> = {
        enabled: config.enabled,
        retrievalEnabled: config.retrievalEnabled
      };
      if (modelChanged) {
        payload.extractModelId = config.followsChatDefault ? "" : (config.extractModelId ?? "");
      }
      await saveGraphConfig(payload);
      toast.success("Graph RAG 配置已保存");
      await loadData();
    } catch (err) {
      toast.error(getErrorMessage(err, "保存失败"));
    } finally {
      setSaving(false);
    }
  };

  const selectedModelName = useMemo(() => {
    if (!config) return "";
    if (config.followsChatDefault) return config.chatModelName ?? "";
    const model = chatModels.find((m) => m.modelId === config.extractModelId);
    return model ? model.modelName : (config.extractModelName ?? "");
  }, [config, chatModels]);

  if (loading || !config) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Loader2 className="h-5 w-5 animate-spin" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-lg font-semibold">知识图谱（Graph RAG）</h2>
        <p className="text-sm" style={{ color: "var(--color-text-tertiary)" }}>
          实体-关系图谱增强检索：文档入库时增量抽取实体关系，问答时以实体锚定做 K 跳子图检索并 RRF 融合进多通道链路
        </p>
      </div>

      {/* 启用设置 */}
      <Card>
        <CardHeader>
          <CardTitle>启用设置</CardTitle>
          <CardDescription>总开关关闭后，实体关系构建与图谱检索全部停用，不影响既有向量/关键词检索链路</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center justify-between rounded-lg border p-4" style={{ borderColor: "var(--color-border-secondary)" }}>
            <div>
              <p className="text-sm font-medium">启用 Graph RAG</p>
              <p className="mt-0.5 text-xs" style={{ color: "var(--color-text-tertiary)" }}>
                开启后文档入库/变更时自动增量抽取实体关系（chunk 哈希缓存，未变更零 LLM 成本）
              </p>
            </div>
            <Switch
              checked={config.enabled}
              onCheckedChange={(checked) => setConfig((c) => (c ? { ...c, enabled: checked } : c))}
            />
          </div>
          <div className="flex items-center justify-between rounded-lg border p-4" style={{ borderColor: "var(--color-border-secondary)" }}>
            <div>
              <p className="text-sm font-medium">图谱检索通道</p>
              <p className="mt-0.5 text-xs" style={{ color: "var(--color-text-tertiary)" }}>
                问答时启用图谱通道检索（依赖知识库图谱已构建）；关闭时仅保留构建，不参与检索融合
              </p>
            </div>
            <Switch
              checked={config.retrievalEnabled}
              disabled={!config.enabled}
              onCheckedChange={(checked) => setConfig((c) => (c ? { ...c, retrievalEnabled: checked } : c))}
            />
          </div>
          <div className="flex justify-end">
            <Button onClick={handleSave} disabled={saving}>
              {saving ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Save className="mr-2 h-4 w-4" />}
              保存
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* 抽取模型 */}
      <Card>
        <CardHeader>
          <CardTitle>抽取模型</CardTitle>
          <CardDescription>图谱构建的实体关系抽取与问答时的查询实体识别共用该模型，建议选择 JSON 输出稳定的模型</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-4">
            <Select
              value={config.followsChatDefault ? FOLLOW_CHAT_DEFAULT : (config.extractModelId ?? "")}
              onValueChange={(value) =>
                setConfig((c) =>
                  c
                    ? value === FOLLOW_CHAT_DEFAULT
                      ? { ...c, followsChatDefault: true, extractModelId: "" }
                      : { ...c, followsChatDefault: false, extractModelId: value }
                    : c
                )
              }
            >
              <SelectTrigger className="w-full md:w-[360px]">
                <SelectValue placeholder="选择模型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={FOLLOW_CHAT_DEFAULT}>
                  跟随对话默认模型
                  {config.chatModelName ? `（${config.chatModelName}）` : ""}
                </SelectItem>
                {chatModels.map((m) => (
                  <SelectItem key={m.modelId} value={m.modelId} disabled={m.enabled !== 1}>
                    {m.modelName}（{m.providerName ?? "未知供应商"}）
                    {m.enabled !== 1 ? "（已禁用）" : ""}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {!config.followsChatDefault && selectedModelName && (
              <Badge variant="outline">{selectedModelName}</Badge>
            )}
          </div>
          {config.followsChatDefault && config.chatModelId && (
            <p className="text-xs" style={{ color: "var(--color-text-tertiary)" }}>
              当前跟随对话默认模型（{config.chatModelName}）；可在「默认模型」页调整对话默认模型，或在「模型管理」页配置
            </p>
          )}
          <div className="flex justify-end">
            <Button onClick={handleSave} disabled={saving}>
              {saving ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Save className="mr-2 h-4 w-4" />}
              保存
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* 知识库图谱状态 */}
      <Card>
        <CardHeader>
          <CardTitle>知识库图谱状态</CardTitle>
          <CardDescription>各知识库的图谱构建情况，点击「进入管理」可查看实体关系可视化、合并实体与构建日志</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="mb-3 flex justify-end">
            <Button variant="outline" size="sm" onClick={loadStatuses} disabled={statusLoading}>
              <RefreshCw className={`mr-2 h-3.5 w-3.5 ${statusLoading ? "animate-spin" : ""}`} />
              刷新
            </Button>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>知识库</TableHead>
                <TableHead className="text-right">实体</TableHead>
                <TableHead className="text-right">关系</TableHead>
                <TableHead className="text-right">已抽取 Chunk</TableHead>
                <TableHead className="text-right">失败</TableHead>
                <TableHead>最后构建</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {kbStatuses.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-center text-xs" style={{ color: "var(--color-text-tertiary)" }}>
                    暂无知识库，或图谱表尚未初始化（未执行 V3 SQL）
                  </TableCell>
                </TableRow>
              )}
              {kbStatuses.map((kb) => (
                <TableRow key={kb.kbId}>
                  <TableCell className="max-w-[240px] truncate">{kb.kbName}</TableCell>
                  <TableCell className="text-right tabular-nums">{kb.entityCount}</TableCell>
                  <TableCell className="text-right tabular-nums">{kb.relationCount}</TableCell>
                  <TableCell className="text-right tabular-nums">{kb.extractionCount}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {kb.failedCount > 0 ? (
                      <span style={{ color: "hsl(var(--destructive))" }}>{kb.failedCount}</span>
                    ) : (
                      kb.failedCount
                    )}
                  </TableCell>
                  <TableCell className="text-xs" style={{ color: "var(--color-text-tertiary)" }}>
                    {kb.lastBuildTime || "-"}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button variant="ghost" size="sm" asChild>
                      <Link to={`/admin/knowledge/${kb.kbId}/graph`}>
                        进入管理
                        <ExternalLink className="ml-1.5 h-3.5 w-3.5" />
                      </Link>
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}