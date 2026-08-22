import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import mermaid from "mermaid";
import { AlertTriangle, ArrowLeft, Check, Database, Link2, RefreshCw, Share2 } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";

import {
  getGraphBuildLogs,
  getGraphEntities,
  getGraphOverview,
  getGraphSubgraph,
  mergeGraphEntities,
  rebuildGraph,
  type GraphBuildLog,
  type GraphEntity,
  type GraphOverview,
  type GraphSubgraph
} from "@/services/graphService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 20;
const ENTITY_TYPES = ["PERSON", "ORG", "DEPT", "ROLE", "PRODUCT", "PROCESS", "SYSTEM", "DOC", "OTHER"];

let mermaidReady = false;
function ensureMermaid() {
  if (!mermaidReady) {
    mermaid.initialize({
      startOnLoad: false,
      theme: document.documentElement.classList.contains("dark") ? "dark" : "default",
      securityLevel: "sandbox",
      fontFamily: "inherit"
    });
    mermaidReady = true;
  }
}

const typeColor = (type: string): string => {
  switch (type) {
    case "PERSON": return "#f59e0b";
    case "ORG": return "#8b5cf6";
    case "DEPT": return "#3b82f6";
    case "ROLE": return "#10b981";
    case "PRODUCT": return "#ec4899";
    case "PROCESS": return "#06b6d4";
    case "SYSTEM": return "#6366f1";
    case "DOC": return "#84cc16";
    default: return "#94a3b8";
  }
};

const escapeLabel = (text: string): string => text.replace(/["\\]/g, "\\$&").replace(/\n/g, " ");

function buildMermaidCode(subgraph: GraphSubgraph): string {
  const nodeById = new Map(subgraph.nodes.map((n) => [n.id, n]));
  const lines = ["flowchart LR"];
  for (const node of subgraph.nodes) {
    lines.push(`  ${node.id}["${escapeLabel(node.name)}"]:::${node.type || "OTHER"}`);
  }
  for (const link of subgraph.links) {
    if (!nodeById.has(link.source) || !nodeById.has(link.target)) continue;
    lines.push(`  ${link.source} -->|${escapeLabel(link.predicate)}| ${link.target}`);
  }
  const colorMap: Record<string, string> = {};
  for (const node of subgraph.nodes) {
    colorMap[node.type || "OTHER"] = typeColor(node.type || "OTHER");
  }
  for (const [type, color] of Object.entries(colorMap)) {
    lines.push(`  classDef ${type} fill:${color},color:#fff,stroke:${color}`);
  }
  return lines.join("\n");
}

export function KnowledgeGraphPage() {
  const { kbId } = useParams<{ kbId: string }>();
  const navigate = useNavigate();
  const [overview, setOverview] = useState<GraphOverview | null>(null);
  const [subgraph, setSubgraph] = useState<GraphSubgraph | null>(null);
  const [mermaidSvg, setMermaidSvg] = useState("");
  const [mermaidError, setMermaidError] = useState("");
  const [loadingGraph, setLoadingGraph] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);

  const [entities, setEntities] = useState<GraphEntity[]>([]);
  const [entityTotal, setEntityTotal] = useState(0);
  const [entityPage, setEntityPage] = useState(1);
  const [entityKeyword, setEntityKeyword] = useState("");
  const [entityTypeFilter, setEntityTypeFilter] = useState("");
  const [entityLoading, setEntityLoading] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [merging, setMerging] = useState(false);

  const [buildLogs, setBuildLogs] = useState<GraphBuildLog[]>([]);
  const [logsTotal, setLogsTotal] = useState(0);
  const [logsPage, setLogsPage] = useState(1);
  const [logsLoading, setLogsLoading] = useState(false);

  const loadOverview = useCallback(async () => {
    if (!kbId) return;
    try {
      setOverview(await getGraphOverview(kbId));
    } catch (err) {
      toast.error(getErrorMessage(err, "加载图谱概览失败"));
    }
  }, [kbId]);

  const loadSubgraph = useCallback(async () => {
    if (!kbId) return;
    setLoadingGraph(true);
    setMermaidSvg("");
    try {
      const data = await getGraphSubgraph(kbId);
      setSubgraph(data);
      if (data.nodes.length === 0) {
        setMermaidError("");
      } else {
        const code = buildMermaidCode(data);
        ensureMermaid();
        try {
          const { svg } = await mermaid.render(`graph-${Date.now()}`, code);
          setMermaidSvg(svg);
          setMermaidError("");
        } catch (err) {
          setMermaidError(err instanceof Error ? err.message : "Mermaid 渲染失败");
        }
      }
    } catch (err) {
      toast.error(getErrorMessage(err, "加载图谱视图失败"));
    } finally {
      setLoadingGraph(false);
    }
  }, [kbId]);

  const loadEntities = useCallback(async () => {
    if (!kbId) return;
    setEntityLoading(true);
    try {
      const result = await getGraphEntities(kbId, {
        keyword: entityKeyword || undefined,
        entityType: entityTypeFilter || undefined,
        current: entityPage,
        size: PAGE_SIZE
      });
      setEntities(result.records);
      setEntityTotal(result.total);
    } catch (err) {
      toast.error(getErrorMessage(err, "加载实体列表失败"));
    } finally {
      setEntityLoading(false);
    }
  }, [kbId, entityKeyword, entityTypeFilter, entityPage]);

  const loadBuildLogs = useCallback(async () => {
    if (!kbId) return;
    setLogsLoading(true);
    try {
      const result = await getGraphBuildLogs(kbId, logsPage, PAGE_SIZE);
      setBuildLogs(result.records);
      setLogsTotal(result.total);
    } catch (err) {
      toast.error(getErrorMessage(err, "加载构建日志失败"));
    } finally {
      setLogsLoading(false);
    }
  }, [kbId, logsPage]);

  useEffect(() => {
    loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    loadEntities();
  }, [loadEntities]);

  useEffect(() => {
    loadBuildLogs();
  }, [loadBuildLogs]);

  const handleRebuild = async () => {
    if (!kbId) return;
    setRebuilding(true);
    try {
      const message = await rebuildGraph(kbId);
      toast.success(message);
      setTimeout(() => loadOverview(), 3000);
    } catch (err) {
      toast.error(getErrorMessage(err, "重建图谱失败"));
    } finally {
      setRebuilding(false);
    }
  };

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
    );
  };

  const handleMerge = async () => {
    if (!kbId || selectedIds.length < 2) return;
    setMerging(true);
    try {
      const keep = selectedIds[0];
      const mergeIds = selectedIds.slice(1);
      await mergeGraphEntities(kbId, keep, mergeIds);
      toast.success(`已合并 ${mergeIds.length} 个实体到「${entities.find((e) => e.id === keep)?.displayName ?? keep}」`);
      setMergeOpen(false);
      setSelectedIds([]);
      loadEntities();
      loadOverview();
      loadSubgraph();
    } catch (err) {
      toast.error(getErrorMessage(err, "合并实体失败"));
    } finally {
      setMerging(false);
    }
  };

  const entityTotalPages = Math.max(1, Math.ceil(entityTotal / PAGE_SIZE));
  const logsTotalPages = Math.max(1, Math.ceil(logsTotal / PAGE_SIZE));

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">知识图谱</h1>
          <p className="admin-page-subtitle">实体-关系图谱：管理构建、可视化与实体合并（Graph RAG）</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => navigate(`/admin/knowledge/${kbId}`)}>
            <ArrowLeft className="mr-2 h-4 w-4" />
            返回文档
          </Button>
          <Button
            variant="outline"
            onClick={handleRebuild}
            disabled={rebuilding || !overview?.graphEnabled}
            title={overview?.graphEnabled ? undefined : "图谱总开关未开启（rag.graph.enabled=false）"}
          >
            <RefreshCw className={`mr-2 h-4 w-4 ${rebuilding ? "animate-spin" : ""}`} />
            {rebuilding ? "重建中..." : "重建图谱"}
          </Button>
          <Button variant="outline" onClick={loadSubgraph}>
            <Share2 className="mr-2 h-4 w-4" />
            刷新视图
          </Button>
        </div>
      </div>

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs text-muted-foreground">图谱开关</p>
            <p className="mt-1 flex items-center gap-2 text-lg font-semibold">
              {overview?.graphEnabled ? (
                <><Check className="h-4 w-4 text-green-500" />已开启</>
              ) : (
                <><AlertTriangle className="h-4 w-4 text-amber-500" />未开启</>
              )}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs text-muted-foreground">实体数</p>
            <p className="mt-1 text-lg font-semibold">{overview?.entityCount ?? 0}</p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs text-muted-foreground">关系数</p>
            <p className="mt-1 text-lg font-semibold">{overview?.relationCount ?? 0}</p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs text-muted-foreground">已抽取 Chunk</p>
            <p className="mt-1 text-lg font-semibold">
              {overview?.extractionCount ?? 0}
              {(overview?.failedCount ?? 0) > 0 && (
                <span className="ml-2 text-sm font-normal text-red-500">失败 {overview?.failedCount}</span>
              )}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs text-muted-foreground">最后构建</p>
            <p className="mt-1 truncate text-sm font-medium">{overview?.lastBuildTime ?? "—"}</p>
          </CardContent>
        </Card>
      </div>

      <Tabs defaultValue="graph" className="w-full">
        <TabsList className="rounded-xl bg-gray-50/80 p-1">
          <TabsTrigger value="graph"><Database className="mr-1 h-4 w-4" />图谱视图</TabsTrigger>
          <TabsTrigger value="entities"><Link2 className="mr-1 h-4 w-4" />实体管理</TabsTrigger>
          <TabsTrigger value="logs"><RefreshCw className="mr-1 h-4 w-4" />构建日志</TabsTrigger>
        </TabsList>

        <TabsContent value="graph">
          <Card>
            <CardHeader>
              <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <CardTitle>图谱可视化</CardTitle>
                  <CardDescription>
                    最多渲染 200 节点，按实体类型着色；展示实体间关系链（mermaid flowchart）
                  </CardDescription>
                </div>
                {subgraph?.truncated && <Badge variant="outline">已截断（超渲染上限）</Badge>}
              </div>
            </CardHeader>
            <CardContent>
              {loadingGraph ? (
                <div className="py-10 text-center text-muted-foreground">加载中...</div>
              ) : subgraph && subgraph.nodes.length > 0 ? (
                mermaidSvg ? (
                  <div
                    className="flex justify-center overflow-auto rounded-lg border bg-white p-4 dark:bg-[#161b22] [&_svg]:max-w-full"
                    dangerouslySetInnerHTML={{ __html: mermaidSvg }}
                  />
                ) : mermaidError ? (
                  <div className="py-6 text-center text-sm text-red-500">Mermaid 渲染失败：{mermaidError}</div>
                ) : (
                  <div className="py-10 text-center text-muted-foreground">渲染中...</div>
                )
              ) : (
                <div className="py-10 text-center">
                  <p className="text-muted-foreground">
                    {overview?.graphEnabled
                      ? "图谱暂无数据，请先对文档执行分块（入库时自动增量抽取），或点击右上角「重建图谱」"
                      : "图谱总开关未开启（rag.graph.enabled=false），开启后文档入库将自动抽取实体关系"}
                  </p>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="entities">
          <Card>
            <CardHeader>
              <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <CardTitle>实体管理</CardTitle>
                  <CardDescription>
                    勾选多个实体可合并（第一个为保留实体，其余合并入它）；同名/别名实体建议先查询后合并
                  </CardDescription>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Input
                    value={entityKeyword}
                    onChange={(e) => setEntityKeyword(e.target.value)}
                    placeholder="搜索实体名"
                    className="w-[180px]"
                  />
                  <Select value={entityTypeFilter || "all"} onValueChange={(v) => { setEntityPage(1); setEntityTypeFilter(v === "all" ? "" : v); }}>
                    <SelectTrigger className="w-[130px]">
                      <SelectValue placeholder="类型" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">全部类型</SelectItem>
                      {ENTITY_TYPES.map((t) => (
                        <SelectItem key={t} value={t}>{t}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button
                    variant="outline"
                    disabled={selectedIds.length < 2}
                    onClick={() => setMergeOpen(true)}
                  >
                    <Link2 className="mr-2 h-4 w-4" />
                    合并实体
                  </Button>
                </div>
              </div>
            </CardHeader>
            <CardContent>
              {entityLoading ? (
                <div className="py-8 text-center text-muted-foreground">加载中...</div>
              ) : entities.length === 0 ? (
                <div className="py-8 text-center text-muted-foreground">暂无实体</div>
              ) : (
                <>
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-10"></TableHead>
                        <TableHead>实体名</TableHead>
                        <TableHead>类型</TableHead>
                        <TableHead>别名</TableHead>
                        <TableHead>关系数</TableHead>
                        <TableHead>创建时间</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {entities.map((entity) => (
                        <TableRow key={entity.id}>
                          <TableCell>
                            <Checkbox
                              checked={selectedIds.includes(entity.id)}
                              onCheckedChange={() => toggleSelect(entity.id)}
                            />
                          </TableCell>
                          <TableCell>
                            <div className="font-medium">{entity.displayName}</div>
                            {entity.description && (
                              <div className="line-clamp-1 max-w-[320px] text-xs text-muted-foreground">
                                {entity.description}
                              </div>
                            )}
                          </TableCell>
                          <TableCell>
                            <Badge variant="outline">{entity.entityType}</Badge>
                          </TableCell>
                          <TableCell className="max-w-[240px]">
                            <div className="line-clamp-2 text-xs text-muted-foreground">
                              {entity.aliases?.length ? entity.aliases.join("、") : "—"}
                            </div>
                          </TableCell>
                          <TableCell>{entity.relationCount ?? 0}</TableCell>
                          <TableCell className="text-xs text-muted-foreground">{entity.createTime ?? "—"}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                  <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
                    <span>共 {entityTotal} 条</span>
                    <div className="flex items-center gap-2">
                      <Button variant="outline" size="sm" disabled={entityPage <= 1} onClick={() => setEntityPage(entityPage - 1)}>
                        上一页
                      </Button>
                      <span>{entityPage} / {entityTotalPages}</span>
                      <Button variant="outline" size="sm" disabled={entityPage >= entityTotalPages} onClick={() => setEntityPage(entityPage + 1)}>
                        下一页
                      </Button>
                    </div>
                  </div>
                </>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="logs">
          <Card>
            <CardHeader>
              <CardTitle>构建日志</CardTitle>
              <CardDescription>每次图谱构建/增量更新的统计与状态（含 LLM 调用次数成本统计）</CardDescription>
            </CardHeader>
            <CardContent>
              {logsLoading ? (
                <div className="py-8 text-center text-muted-foreground">加载中...</div>
              ) : buildLogs.length === 0 ? (
                <div className="py-8 text-center text-muted-foreground">暂无构建记录</div>
              ) : (
                <>
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>时间</TableHead>
                        <TableHead>触发</TableHead>
                        <TableHead>状态</TableHead>
                        <TableHead>实体 +/合并</TableHead>
                        <TableHead>关系 +/移除</TableHead>
                        <TableHead>LLM 调用</TableHead>
                        <TableHead>耗时</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {buildLogs.map((log) => (
                        <TableRow key={log.id}>
                          <TableCell className="text-xs">{log.createTime ?? "—"}</TableCell>
                          <TableCell>
                            <Badge variant="secondary">{log.triggerType ?? "—"}</Badge>
                          </TableCell>
                          <TableCell>
                            <Badge variant={log.status === "SUCCESS" ? "default" : "destructive"}>
                              {log.status ?? "—"}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-xs">
                            +{log.entityAdded ?? 0} / {log.entityMerged ?? 0}
                          </TableCell>
                          <TableCell className="text-xs">
                            +{log.relationAdded ?? 0} / -{log.relationRemoved ?? 0}
                          </TableCell>
                          <TableCell>{log.llmCalls ?? 0}</TableCell>
                          <TableCell className="text-xs">{log.durationMs ? `${(log.durationMs / 1000).toFixed(1)}s` : "—"}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                  <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
                    <span>共 {logsTotal} 条</span>
                    <div className="flex items-center gap-2">
                      <Button variant="outline" size="sm" disabled={logsPage <= 1} onClick={() => setLogsPage(logsPage - 1)}>
                        上一页
                      </Button>
                      <span>{logsPage} / {logsTotalPages}</span>
                      <Button variant="outline" size="sm" disabled={logsPage >= logsTotalPages} onClick={() => setLogsPage(logsPage + 1)}>
                        下一页
                      </Button>
                    </div>
                  </div>
                </>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <AlertDialog open={mergeOpen} onOpenChange={setMergeOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认合并实体</AlertDialogTitle>
            <AlertDialogDescription>
              将保留「{entities.find((e) => e.id === selectedIds[0])?.displayName ?? "—"}」，
              合并以下 {Math.max(0, selectedIds.length - 1)} 个实体（其关系将迁移到保留实体，原实体删除）：
              <ul className="mt-2 list-inside list-disc space-y-1">
                {selectedIds.slice(1).map((id) => {
                  const entity = entities.find((e) => e.id === id);
                  return <li key={id}>{entity?.displayName ?? id}</li>;
                })}
              </ul>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={merging}>取消</AlertDialogCancel>
            <AlertDialogAction disabled={merging || selectedIds.length < 2} onClick={handleMerge}>
              {merging ? "合并中..." : "确认合并"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}