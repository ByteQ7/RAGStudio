import { useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  Eye,
  GitCompare,
  History,
  Loader2,
  RefreshCw,
  RotateCcw,
  Save,
  Search,
  Terminal
} from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { DiffView } from "@/components/shared/DiffView";
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { Switch } from "@/components/ui/switch";
import { Badge } from "@/components/ui/badge";
import type { PromptConfig, PromptHistory } from "@/services/promptService";
import {
  getPrompt,
  getPromptHistory,
  getPrompts,
  previewPrompt,
  resetPrompt,
  rollbackPrompt,
  updatePrompt
} from "@/services/promptService";
import { getErrorMessage } from "@/utils/error";
import { formatDateTime as formatDate } from "@/utils/datetime";

const CATEGORY_LABELS: Record<string, string> = {
  chat: "对话问答",
  query: "查询理解",
  memory: "记忆处理",
  graph: "知识图谱",
  ingestion: "文档处理",
  tool: "工具/其他"
};

const CATEGORY_TABS = [
  { value: "all", label: "全部" },
  { value: "chat", label: "对话问答" },
  { value: "query", label: "查询理解" },
  { value: "memory", label: "记忆处理" },
  { value: "graph", label: "知识图谱" },
  { value: "ingestion", label: "文档处理" },
  { value: "tool", label: "工具/其他" }
];

const CATEGORY_BADGE_COLORS: Record<string, string> = {
  chat: "bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300",
  query: "bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300",
  memory: "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300",
  graph: "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300",
  ingestion: "bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300",
  tool: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300"
};

export function PromptsPage() {
  const [prompts, setPrompts] = useState<PromptConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [category, setCategory] = useState("all");
  const [keyword, setKeyword] = useState("");
  const [searchKeyword, setSearchKeyword] = useState("");

  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [detail, setDetail] = useState<PromptConfig | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const [content, setContent] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [saving, setSaving] = useState(false);
  const [rollingBack, setRollingBack] = useState(false);
  const detailSeq = useRef(0);
  const listSeq = useRef(0);
  const historySeq = useRef(0);

  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<PromptHistory[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [rollbackTarget, setRollbackTarget] = useState<PromptHistory | null>(null);
  const [diffTarget, setDiffTarget] = useState<PromptHistory | null>(null);

  const [previewOpen, setPreviewOpen] = useState(false);
  const [slotsText, setSlotsText] = useState("");
  const [previewResult, setPreviewResult] = useState("");
  const [previewLoading, setPreviewLoading] = useState(false);

  const [resetOpen, setResetOpen] = useState(false);
  const [resetLoading, setResetLoading] = useState(false);

  const loadPrompts = async () => {
    const seq = ++listSeq.current;
    try {
      setLoading(true);
      const data = await getPrompts({
        category: category === "all" ? undefined : category,
        keyword: keyword || undefined
      });
      if (seq !== listSeq.current) return;
      setPrompts(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载提示词列表失败"));
    } finally {
      if (seq === listSeq.current) setLoading(false);
    }
  };

  useEffect(() => {
    loadPrompts();
  }, [category, keyword]);

  const loadDetail = async (key: string) => {
    const seq = ++detailSeq.current;
    setDetailLoading(true);
    setDetailError(false);
    setDetail(null);
    try {
      const data = await getPrompt(key);
      if (seq !== detailSeq.current) return;
      setDetail(data);
      setContent(data.content || "");
      setEnabled(!!data.enabled);
    } catch (error) {
      if (seq === detailSeq.current) {
        setDetailError(true);
        toast.error(getErrorMessage(error, "加载提示词详情失败"));
      }
    } finally {
      if (seq === detailSeq.current) setDetailLoading(false);
    }
  };

  useEffect(() => {
    if (selectedKey) {
      loadDetail(selectedKey);
    }
  }, [selectedKey]);

  const customized = detail?.customized ?? false;
  const hasUnsaved =
    detail !== null && (content !== (detail.content || "") || enabled !== !!detail.enabled);

  const handleSearch = () => {
    setKeyword(searchKeyword.trim());
  };

  const handleRefresh = () => {
    setKeyword("");
    setSearchKeyword("");
    setCategory("all");
  };

  const handleSave = async () => {
    if (!selectedKey) return;
    if (!content.trim()) {
      toast.error("提示词内容不能为空");
      return;
    }
    try {
      setSaving(true);
      const updated = await updatePrompt(selectedKey, {
        content,
        enabled
      });
      setDetail(updated);
      setContent(updated.content || "");
      setEnabled(!!updated.enabled);
      toast.success(`已保存并热重载（v${updated.version}）`);
      await loadPrompts();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    if (!selectedKey) return;
    try {
      setResetLoading(true);
      const updated = await resetPrompt(selectedKey);
      setDetail(updated);
      setContent(updated.content || "");
      setEnabled(!!updated.enabled);
      toast.success(`已重置为默认并热重载（v${updated.version}）`);
      setResetOpen(false);
      await loadPrompts();
    } catch (error) {
      toast.error(getErrorMessage(error, "重置失败"));
    } finally {
      setResetLoading(false);
    }
  };

  const openHistory = async () => {
    if (!selectedKey) return;
    const seq = ++historySeq.current;
    setHistoryOpen(true);
    setHistoryLoading(true);
    setHistory([]);
    try {
      const list = await getPromptHistory(selectedKey);
      if (seq !== historySeq.current) return;
      setHistory(list);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载历史失败"));
    } finally {
      if (seq === historySeq.current) setHistoryLoading(false);
    }
  };

  const handleRollback = async () => {
    if (!selectedKey || !rollbackTarget) return;
    try {
      setRollingBack(true);
      const updated = await rollbackPrompt(selectedKey, rollbackTarget.version);
      setDetail(updated);
      setContent(updated.content || "");
      setEnabled(!!updated.enabled);
      toast.success(`已回滚到 v${rollbackTarget.version} 并热重载（当前 v${updated.version}）`);
      setRollbackTarget(null);
      setHistoryOpen(false);
      await loadPrompts();
    } catch (error) {
      toast.error(getErrorMessage(error, "回滚失败"));
    } finally {
      setRollingBack(false);
    }
  };

  const handlePreview = async () => {
    if (!selectedKey) return;
    try {
      setPreviewLoading(true);
      const slots: Record<string, string> = {};
      slotsText
        .split("\n")
        .map((line) => line.trim())
        .filter(Boolean)
        .forEach((line) => {
          const idx = line.indexOf("=");
          if (idx > 0) {
            slots[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
          }
        });
      const result = await previewPrompt(selectedKey, slots);
      setPreviewResult(result);
    } catch (error) {
      toast.error(getErrorMessage(error, "试渲染失败"));
    } finally {
      setPreviewLoading(false);
    }
  };

  const matchedCount = useMemo(() => prompts.length, [prompts]);

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">提示词管理</h1>
          <p className="admin-page-subtitle">
            统一管理 Agent 提示词，编辑后立即热重载，无需重启服务
          </p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchKeyword}
            onChange={(event) => setSearchKeyword(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && handleSearch()}
            placeholder="搜索名称 / key / 描述"
            className="w-[260px]"
          />
          <Button variant="outline" onClick={handleSearch}>
            <Search className="w-4 h-4 mr-2" />
            搜索
          </Button>
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
        </div>
      </div>

      <Tabs value={category} onValueChange={(value) => setCategory(value)} className="mb-4">
        <TabsList>
          {CATEGORY_TABS.map((tab) => (
            <TabsTrigger key={tab.value} value={tab.value}>
              {tab.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      <div className="flex gap-4 items-stretch">
        {/* 左侧列表 */}
        <Card className="w-[380px] shrink-0">
          <CardContent className="pt-4">
            {loading ? (
              <div className="text-center py-8 text-muted-foreground text-sm flex items-center justify-center gap-2">
                <Loader2 className="w-4 h-4 animate-spin" /> 加载中...
              </div>
            ) : matchedCount === 0 ? (
              <div className="text-center py-8 text-muted-foreground text-sm">
                {prompts.length === 0 ? "暂无提示词" : "无匹配的提示词"}
              </div>
            ) : (
              <div className="max-h-[calc(100vh-320px)] overflow-y-auto pr-1 space-y-1">
                {prompts.map((item) => (
                  <button
                    key={item.key}
                    type="button"
                    onClick={() => setSelectedKey(item.key)}
                    className={`w-full text-left rounded-lg border px-3 py-2.5 transition-colors ${
                      selectedKey === item.key
                        ? "border-primary bg-[var(--color-fill-quaternary)]"
                        : "hover:bg-[var(--color-fill-quaternary)]"
                    }`}
                    style={{
                      borderColor:
                        selectedKey === item.key
                          ? "hsl(var(--primary))"
                          : "var(--color-border-secondary)"
                    }}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span
                        className="text-sm font-medium truncate"
                        style={{ color: "var(--color-text)" }}
                      >
                        {item.name}
                      </span>
                      <Badge
                        className={`shrink-0 text-[10px] ${CATEGORY_BADGE_COLORS[item.category] || ""}`}
                      >
                        {CATEGORY_LABELS[item.category] || item.category}
                      </Badge>
                    </div>
                    <div className="mt-1 flex items-center justify-between gap-2">
                      <span
                        className="font-mono text-[11px] truncate"
                        style={{ color: "var(--color-text-tertiary)" }}
                      >
                        {item.key}
                      </span>
                      <div className="flex items-center gap-1.5 shrink-0">
                        {item.customized && (
                          <span className="text-[10px]" style={{ color: "hsl(var(--primary))" }}>
                            已自定义
                          </span>
                        )}
                        <span
                          className="text-[10px]"
                          style={{ color: "var(--color-text-tertiary)" }}
                        >
                          v{item.version}
                        </span>
                        <span
                          className={`h-1.5 w-1.5 rounded-full ${item.enabled ? "bg-emerald-500" : "bg-slate-400"}`}
                          title={item.enabled ? "已启用" : "已禁用（回退默认）"}
                        />
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        {/* 右侧编辑区 */}
        <Card className="flex-1 min-w-0">
          <CardContent className="pt-4">
            {!selectedKey ? (
              <div className="text-center py-16 text-muted-foreground text-sm">
                请选择左侧提示词进行编辑
              </div>
            ) : detailError ? (
              <div className="text-center py-16 space-y-3">
                <AlertTriangle className="w-8 h-8 mx-auto text-muted-foreground" />
                <div className="text-sm text-muted-foreground">
                  提示词详情加载失败，请检查服务状态后重试
                </div>
                <Button size="sm" variant="outline" onClick={() => loadDetail(selectedKey)}>
                  <RefreshCw className="w-3.5 h-3.5 mr-1" /> 重试
                </Button>
              </div>
            ) : detailLoading || !detail ? (
              <div className="text-center py-16 text-muted-foreground text-sm flex items-center justify-center gap-2">
                <Loader2 className="w-4 h-4 animate-spin" /> 加载中...
              </div>
            ) : (
              <div className="space-y-4">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <h2
                        className="text-base font-semibold"
                        style={{ color: "var(--color-text)" }}
                      >
                        {detail.name}
                      </h2>
                      <span
                        className="font-mono text-[11px]"
                        style={{ color: "var(--color-text-tertiary)" }}
                      >
                        {detail.key}
                      </span>
                      <Badge className={CATEGORY_BADGE_COLORS[detail.category] || ""}>
                        {CATEGORY_LABELS[detail.category] || detail.category}
                      </Badge>
                    </div>
                    {detail.description && (
                      <p className="mt-1 text-xs" style={{ color: "var(--color-text-secondary)" }}>
                        {detail.description}
                      </p>
                    )}
                    <p className="mt-1 text-[11px]" style={{ color: "var(--color-text-tertiary)" }}>
                      当前版本 v{detail.version} · 来源：
                      {detail.source === "db" ? "DB（自定义）" : "classpath（默认）"}
                      {detail.updatedBy ? ` · 修改人：${detail.updatedBy}` : ""}
                      {detail.updateTime ? ` · ${formatDate(detail.updateTime)}` : ""}
                    </p>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <label
                      htmlFor="prompt-enabled-switch"
                      className="flex items-center gap-2 text-xs cursor-pointer"
                      style={{ color: "var(--color-text-secondary)" }}
                    >
                      启用
                      <Switch id="prompt-enabled-switch" checked={enabled} onCheckedChange={setEnabled} />
                    </label>
                  </div>
                </div>

                {detail.variables && (
                  <div
                    className="rounded-lg border px-3 py-2 text-[11px]"
                    style={{
                      borderColor: "var(--color-border-secondary)",
                      background: "var(--color-fill-quaternary)"
                    }}
                  >
                    <span style={{ color: "var(--color-text-tertiary)" }}>可用占位符：</span>
                    <span className="font-mono" style={{ color: "hsl(var(--primary))" }}>
                      {detail.variables}
                    </span>
                  </div>
                )}

                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <label className="text-sm font-medium">提示词内容</label>
                    <span className="text-[11px]" style={{ color: "var(--color-text-tertiary)" }}>
                      {customized ? "与出厂默认不同" : "当前为出厂默认"} · {content.length} 字符
                    </span>
                  </div>
                  <Textarea
                    value={content}
                    onChange={(event) => setContent(event.target.value)}
                    placeholder="请输入提示词内容..."
                    className="min-h-[440px] font-mono text-xs leading-relaxed"
                  />
                </div>

                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <Button variant="outline" size="sm" onClick={openHistory}>
                      <History className="w-4 h-4 mr-1.5" />
                      变更历史
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setSlotsText("");
                        setPreviewResult("");
                        setPreviewOpen(true);
                      }}
                    >
                      <Eye className="w-4 h-4 mr-1.5" />
                      试渲染
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-destructive hover:text-destructive"
                      onClick={() => setResetOpen(true)}
                      disabled={!customized}
                    >
                      <RotateCcw className="w-4 h-4 mr-1.5" />
                      重置默认
                    </Button>
                  </div>
                  <Button
                    onClick={handleSave}
                    disabled={saving || !hasUnsaved}
                    className="admin-primary-gradient"
                  >
                    <Save className="w-4 h-4 mr-1.5" />
                    {saving ? "保存中..." : hasUnsaved ? "保存并热重载" : "已保存"}
                  </Button>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 变更历史 */}
      <Dialog open={historyOpen} onOpenChange={setHistoryOpen}>
        <DialogContent className="sm:max-w-[720px]">
          <DialogHeader>
            <DialogTitle>变更历史</DialogTitle>
            <DialogDescription>
              {detail?.name}（{detail?.key}）· 历史版本为「变更前」内容，回滚后生成新版本
            </DialogDescription>
          </DialogHeader>
          {historyLoading ? (
            <div className="text-center py-8 text-muted-foreground text-sm flex items-center justify-center gap-2">
              <Loader2 className="w-4 h-4 animate-spin" /> 加载中...
            </div>
          ) : history.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground text-sm">
              暂无历史记录（当前为出厂默认版本 v1）
            </div>
          ) : (
            <div className="max-h-[420px] overflow-y-auto space-y-2">
              {history.map((item) => (
                <div
                  key={item.version}
                  className="rounded-lg border p-3"
                  style={{ borderColor: "var(--color-border-secondary)" }}
                >
                  <div className="flex items-center justify-between gap-2 mb-1.5">
                    <div className="flex items-center gap-2">
                      <Badge variant="outline">v{item.version}</Badge>
                      <span className="text-[11px]" style={{ color: "var(--color-text-tertiary)" }}>
                        {item.updatedBy || "-"} · {formatDate(item.updateTime)}
                      </span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <Button variant="outline" size="sm" onClick={() => setDiffTarget(item)}>
                        <GitCompare className="w-3.5 h-3.5 mr-1" />
                        对比
                      </Button>
                      <Button variant="outline" size="sm" onClick={() => setRollbackTarget(item)}>
                        <RotateCcw className="w-3.5 h-3.5 mr-1" />
                        回滚到此版本
                      </Button>
                    </div>
                  </div>
                  <pre
                    className="whitespace-pre-wrap text-[11px] leading-relaxed max-h-[140px] overflow-y-auto font-mono"
                    style={{ color: "var(--color-text-secondary)" }}
                  >
                    {item.content}
                  </pre>
                </div>
              ))}
            </div>
          )}
        </DialogContent>
      </Dialog>

      {/* 回滚确认 */}
      <AlertDialog open={!!rollbackTarget} onOpenChange={() => setRollbackTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认回滚</AlertDialogTitle>
            <AlertDialogDescription>
              将提示词回滚到 v{rollbackTarget?.version}{" "}
              的内容，当前内容将写入历史，并立即热重载生效。是否继续？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleRollback} disabled={rollingBack}>
              {rollingBack ? "回滚中..." : "回滚"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* 版本对比：左侧历史版本（变更前内容） vs 右侧当前版本 */}
      <Dialog open={!!diffTarget} onOpenChange={() => setDiffTarget(null)}>
        <DialogContent className="sm:max-w-[960px]">
          <DialogHeader>
            <DialogTitle>版本对比：{detail?.name}</DialogTitle>
            <DialogDescription>
              左侧为 v{diffTarget?.version}（变更前快照），右侧为当前版本 v{detail?.version ?? "-"}
            </DialogDescription>
          </DialogHeader>
          <DiffView
            oldText={diffTarget?.content ?? ""}
            newText={detail?.content ?? ""}
            leftTitle={`v${diffTarget?.version ?? "-"}（历史）`}
            rightTitle={`v${detail?.version ?? "-"}（当前）`}
            maxHeight="420px"
          />
          <DialogFooter>
            <Button variant="outline" onClick={() => setDiffTarget(null)}>
              关闭
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 重置确认 */}
      <AlertDialog open={resetOpen} onOpenChange={setResetOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认重置为默认</AlertDialogTitle>
            <AlertDialogDescription>
              将 {detail?.name}（{detail?.key}
              ）重置为出厂默认内容，当前自定义内容将写入历史，并立即热重载生效。是否继续？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleReset} disabled={resetLoading}>
              {resetLoading ? "重置中..." : "重置"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* 试渲染 */}
      <Dialog open={previewOpen} onOpenChange={setPreviewOpen}>
        <DialogContent className="sm:max-w-[720px]">
          <DialogHeader>
            <DialogTitle>试渲染</DialogTitle>
            <DialogDescription>
              按 {`key=值`} 每行一个传入占位符，实时查看填充后的提示词效果
            </DialogDescription>
          </DialogHeader>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">占位符（可选）</label>
              <Textarea
                value={slotsText}
                onChange={(event) => setSlotsText(event.target.value)}
                placeholder={"tool_definitions=搜索、查询知识库\nkb_context=预检索文本片段"}
                className="min-h-[300px] font-mono text-xs"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">渲染结果</label>
              <pre
                className="min-h-[300px] whitespace-pre-wrap rounded-lg border p-3 text-xs leading-relaxed overflow-y-auto font-mono"
                style={{
                  borderColor: "var(--color-border-secondary)",
                  background: "var(--color-fill-quaternary)",
                  color: "var(--color-text)"
                }}
              >
                {previewLoading ? "渲染中..." : previewResult || "填写占位符后点击「渲染」"}
              </pre>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPreviewOpen(false)}>
              关闭
            </Button>
            <Button onClick={handlePreview} disabled={previewLoading}>
              <Terminal className="w-4 h-4 mr-1.5" />
              渲染
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
