import type { ReactNode } from "react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import type { SystemSettings } from "@/services/settingsService";
import { getSystemSettings } from "@/services/settingsService";
import { getErrorMessage } from "@/utils/error";
import { Loader2 } from "lucide-react";
import type { MineruConfigVO } from "@/services/mineruService";
import { getMineruConfig, updateMineruConfig, pingMineru } from "@/services/mineruService";

const BoolBadge = ({ value }: { value: boolean }) => (
  <Badge variant={value ? "default" : "outline"}>{value ? "启用" : "禁用"}</Badge>
);

function maskApiKey(key?: string | null): string {
  if (!key) return "-";
  if (key.length <= 8) return "****";
  return `${key.slice(0, 4)}****${key.slice(-4)}`;
}

function InfoItem({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-gray-200/70 bg-white px-4 py-3">
      <span className="text-xs text-gray-500">{label}</span>
      <div className="text-sm font-medium text-gray-800">{value}</div>
    </div>
  );
}

export function SystemSettingsPage() {
  const [settings, setSettings] = useState<SystemSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [mineru, setMineru] = useState<MineruConfigVO | null>(null);
  const [mineruLoading, setMineruLoading] = useState(false);
  const [mineruSaving, setMineruSaving] = useState(false);
  const [mineruProbing, setMineruProbing] = useState(false);

  const loadSettings = async () => {
    try {
      setLoading(true);
      const data = await getSystemSettings();
      setSettings(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载系统配置失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadSettings();
  }, []);

  const loadMineru = async () => {
    setMineruLoading(true);
    try {
      const data = await getMineruConfig();
      setMineru(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载 MinerU 配置失败"));
      console.error(error);
    } finally {
      setMineruLoading(false);
    }
  };

  useEffect(() => {
    loadMineru();
  }, []);

  const handleMineruSave = async () => {
    if (!mineru) return;
    setMineruSaving(true);
    try {
      await updateMineruConfig(mineru);
      toast.success("MinerU 配置已保存");
    } catch (error) {
      toast.error(getErrorMessage(error, "保存 MinerU 配置失败"));
      console.error(error);
    } finally {
      setMineruSaving(false);
    }
  };

  const handleMineruProbe = async () => {
    setMineruProbing(true);
    try {
      const data = await pingMineru();
      setMineru(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "探测 MinerU 连通性失败"));
      console.error(error);
    } finally {
      setMineruProbing(false);
    }
  };

  if (loading) {
    return (
      <div className="admin-page">
        <div className="text-sm text-muted-foreground">加载中...</div>
      </div>
    );
  }

  if (!settings) {
    return (
      <div className="admin-page">
        <div className="text-sm text-muted-foreground">暂无可展示的配置</div>
      </div>
    );
  }

  const { rag, ai } = settings;
  const providers = Object.entries(ai.providers || {});

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">系统配置</h1>
          <p className="admin-page-subtitle">只读展示当前 application 配置</p>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>RAG 默认配置</CardTitle>
          <CardDescription>向量空间与检索基础参数</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="Collection" value={rag.default.collectionName} />
          <InfoItem label="Dimension" value={rag.default.dimension} />
          <InfoItem label="Metric Type" value={rag.default.metricType} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>MinerU 解析服务</CardTitle>
          <CardDescription>本地/远程 MinerU 文档解析端点配置</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {mineruLoading ? (
            <div className="text-sm text-muted-foreground">加载中...</div>
          ) : !mineru ? (
            <div className="text-sm text-muted-foreground">暂无 MinerU 配置</div>
          ) : (
            <>
              {[mineru.local, mineru.remote].map((ep, idx) => {
                const isLocal = idx === 0;
                const label = isLocal ? "本地 MinerU" : "远程 MinerU";
                return (
                  <div key={label} className="grid gap-4 rounded-lg border border-gray-200/70 bg-white p-4 md:grid-cols-2">
                    <div className="flex items-center gap-2 md:col-span-2">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-blue-600"
                        checked={ep?.enabled ?? false}
                        onChange={(e) => {
                          const next = { ...mineru };
                          if (isLocal) next.local = { ...mineru.local, enabled: e.target.checked };
                          else next.remote = { ...mineru.remote, enabled: e.target.checked };
                          setMineru(next);
                        }}
                      />
                      <span className="text-sm font-medium text-gray-800">{label}</span>
                      {!isLocal && (
                        <span className="text-xs text-gray-500">（官方 Agent API 免费免 Token，单文件 ≤10MB、≤20 页）</span>
                      )}
                      <span className="text-xs text-gray-500">（{mineru.timeoutSeconds ?? 300}s 解析超时）</span>
                    </div>
                    <div className="space-y-1">
                      <label className="text-xs text-gray-500">Base URL</label>
                      <input
                        className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm"
                        placeholder={isLocal ? "http://127.0.0.1:8000" : "https://mineru.net/api/v1/agent"}
                        value={ep?.baseUrl || ""}
                        onChange={(e) => {
                          const next = { ...mineru };
                          if (isLocal) next.local = { ...mineru.local, baseUrl: e.target.value };
                          else next.remote = { ...mineru.remote, baseUrl: e.target.value };
                          setMineru(next);
                        }}
                      />
                    </div>
                    <div className="space-y-1">
                      <label className="text-xs text-gray-500">Backend 引擎</label>
                      <select
                        className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm"
                        value={ep?.backend || "pipeline"}
                        onChange={(e) => {
                          const next = { ...mineru };
                          if (isLocal) next.local = { ...mineru.local, backend: e.target.value };
                          else next.remote = { ...mineru.remote, backend: e.target.value };
                          setMineru(next);
                        }}
                      >
                        {["pipeline", "vlm", "hybrid"].map((b) => (
                          <option key={b} value={b}>{b}</option>
                        ))}
                      </select>
                    </div>
                    {!isLocal && (
                      <div className="space-y-1 md:col-span-2">
                        <label className="text-xs text-gray-500">API Key（可选）</label>
                        <input
                          className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm"
                          type="password"
                          placeholder="远程鉴权密钥"
                          value={ep?.apiKey || ""}
                          onChange={(e) => {
                            const next = { ...mineru };
                            next.remote = { ...mineru.remote, apiKey: e.target.value };
                            setMineru(next);
                          }}
                        />
                      </div>
                    )}
                    <div className="md:col-span-2">
                      {ep?.reachable === true ? (
                        <Badge variant="default" className="bg-emerald-600">可达</Badge>
                      ) : ep?.reachable === false ? (
                        <Badge variant="outline" className="border-red-200 bg-red-50 text-red-600">不可达</Badge>
                      ) : (
                        <span className="text-xs text-gray-400">未探测</span>
                      )}
                    </div>
                  </div>
                );
              })}
              <div className="flex justify-end gap-2">
                <Button
                  variant="outline"
                  onClick={handleMineruProbe}
                  disabled={mineruProbing}
                >
                  {mineruProbing && <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />}
                  探测连通性
                </Button>
                <Button onClick={handleMineruSave} disabled={mineruSaving}>
                  {mineruSaving ? "保存中..." : "保存"}
                </Button>
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>查询改写</CardTitle>
          <CardDescription>历史上下文压缩与改写策略</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="Enabled" value={<BoolBadge value={rag.queryRewrite.enabled} />} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>全局限流</CardTitle>
          <CardDescription>并发与租约控制</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="Enabled" value={<BoolBadge value={rag.rateLimit.global.enabled} />} />
          <InfoItem label="Max Concurrent" value={rag.rateLimit.global.maxConcurrent} />
          <InfoItem label="Max Wait Seconds" value={rag.rateLimit.global.maxWaitSeconds} />
          <InfoItem label="Lease Seconds" value={rag.rateLimit.global.leaseSeconds} />
          <InfoItem label="Poll Interval (ms)" value={rag.rateLimit.global.pollIntervalMs} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>记忆管理</CardTitle>
          <CardDescription>摘要与上下文保留策略</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="History Keep Turns" value={rag.memory.historyKeepTurns} />
          <InfoItem label="Summary Start Turns" value={rag.memory.summaryStartTurns} />
          <InfoItem
            label="Summary Enabled"
            value={<BoolBadge value={rag.memory.summaryEnabled} />}
          />
          <InfoItem label="Summary Max Chars" value={rag.memory.summaryMaxChars} />
          <InfoItem label="Title Max Length" value={rag.memory.titleMaxLength} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>模型服务提供方</CardTitle>
          <CardDescription>接入地址与端点配置</CardDescription>
        </CardHeader>
        <CardContent>
          <Table className="min-w-[760px]">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[140px]">Provider</TableHead>
                <TableHead className="w-[240px]">URL</TableHead>
                <TableHead className="w-[200px]">API Key</TableHead>
                <TableHead>Endpoints</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {providers.map(([name, provider]) => (
                <TableRow key={name}>
                  <TableCell className="font-medium">{name}</TableCell>
                  <TableCell>{provider.url}</TableCell>
                  <TableCell>{maskApiKey(provider.apiKey)}</TableCell>
                  <TableCell>
                    <div className="space-y-1 text-xs text-muted-foreground">
                      {Object.entries(provider.endpoints).map(([key, value]) => (
                        <div key={key}>
                          {key}: {value}
                        </div>
                      ))}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>模型选择策略</CardTitle>
          <CardDescription>熔断与选择阈值</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <InfoItem label="Failure Threshold" value={ai.selection.failureThreshold} />
          <InfoItem label="Open Duration (ms)" value={ai.selection.openDurationMs} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>流式响应</CardTitle>
          <CardDescription>输出分片大小</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <InfoItem label="Message Chunk Size" value={ai.stream.messageChunkSize} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Chat 模型配置</CardTitle>
          <CardDescription>默认模型与候选列表</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <InfoItem label="Default Model" value={ai.chat.defaultModel} />
            <InfoItem label="Deep Thinking Model" value={ai.chat.deepThinkingModel} />
          </div>
          <Table className="min-w-[720px]">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[220px]">ID</TableHead>
                <TableHead className="w-[120px]">Provider</TableHead>
                <TableHead className="w-[200px]">Model</TableHead>
                <TableHead className="w-[100px]">Thinking</TableHead>
                <TableHead className="w-[90px]">Priority</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ai.chat.candidates.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="font-medium">{item.id}</TableCell>
                  <TableCell>{item.provider}</TableCell>
                  <TableCell>{item.model}</TableCell>
                  <TableCell>{item.supportsThinking ? "支持" : "-"}</TableCell>
                  <TableCell>{item.priority}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Embedding 模型配置</CardTitle>
          <CardDescription>向量化模型列表</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <InfoItem label="Default Model" value={ai.embedding.defaultModel} />
          </div>
          <Table className="min-w-[720px]">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[220px]">ID</TableHead>
                <TableHead className="w-[120px]">Provider</TableHead>
                <TableHead className="w-[200px]">Model</TableHead>
                <TableHead className="w-[110px]">Dimension</TableHead>
                <TableHead className="w-[90px]">Priority</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ai.embedding.candidates.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="font-medium">{item.id}</TableCell>
                  <TableCell>{item.provider}</TableCell>
                  <TableCell>{item.model}</TableCell>
                  <TableCell>{item.dimension}</TableCell>
                  <TableCell>{item.priority}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Rerank 模型配置</CardTitle>
          <CardDescription>重排模型列表</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <InfoItem label="Default Model" value={ai.rerank.defaultModel} />
          </div>
          <Table className="min-w-[640px]">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[220px]">ID</TableHead>
                <TableHead className="w-[120px]">Provider</TableHead>
                <TableHead className="w-[200px]">Model</TableHead>
                <TableHead className="w-[90px]">Priority</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ai.rerank.candidates.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="font-medium">{item.id}</TableCell>
                  <TableCell>{item.provider}</TableCell>
                  <TableCell>{item.model}</TableCell>
                  <TableCell>{item.priority}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
