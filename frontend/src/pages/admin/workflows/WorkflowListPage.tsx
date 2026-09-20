import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  AlertTriangle,
  GitBranch,
  Loader2,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  Workflow as WorkflowIcon
} from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
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
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Switch } from "@/components/ui/switch";
import { Badge } from "@/components/ui/badge";
import {
  deleteWorkflow,
  getWorkflow,
  listWorkflows,
  rebuildWorkflowIndex,
  toggleWorkflow,
  updateWorkflow,
  type WorkflowDetail,
  type WorkflowListItem,
  type WorkflowStep
} from "@/services/workflowService";
import { getErrorMessage } from "@/utils/error";
import { formatDateTime as formatDate } from "@/utils/datetime";

/** 表单中的步骤草稿（when/tool 允许空串，提交前归一为 null） */
interface StepDraft {
  action: string;
  tool: string;
  when: string;
}

interface FormState {
  name: string;
  title: string;
  description: string;
  notes: string;
  steps: StepDraft[];
}

const EMPTY_FORM: FormState = {
  name: "",
  title: "",
  description: "",
  notes: "",
  steps: [{ action: "", tool: "", when: "" }]
};

export function WorkflowListPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<WorkflowListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<WorkflowDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingName, setEditingName] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [submitting, setSubmitting] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<WorkflowListItem | null>(null);
  const [rebuilding, setRebuilding] = useState(false);

  const loadList = useCallback(async (keepSelection = true) => {
    setLoading(true);
    try {
      const data = await listWorkflows();
      setItems(data);
      if (!keepSelection || (selected && !data.some((w) => w.name === selected))) {
        setSelected(data[0]?.name ?? null);
      }
    } catch (error) {
      toast.error(getErrorMessage(error));
    } finally {
      setLoading(false);
    }
  }, [selected]);

  useEffect(() => {
    void loadList(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadDetail = useCallback(async (name: string) => {
    setDetailLoading(true);
    try {
      setDetail(await getWorkflow(name));
    } catch (error) {
      toast.error(getErrorMessage(error));
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    if (selected) {
      void loadDetail(selected);
    } else {
      setDetail(null);
    }
  }, [selected, loadDetail]);

  const openEdit = () => {
    if (!detail) return;
    setEditingName(detail.name);
    setForm({
      name: detail.name,
      title: detail.title,
      description: detail.description,
      notes: detail.notes ?? "",
      steps:
        detail.steps.length > 0
          ? detail.steps.map((s) => ({ action: s.action, tool: s.tool ?? "", when: s.when ?? "" }))
          : [{ action: "", tool: "", when: "" }]
    });
    setDialogOpen(true);
  };

  const updateStep = (index: number, patch: Partial<StepDraft>) => {
    setForm((prev) => ({
      ...prev,
      steps: prev.steps.map((step, i) => (i === index ? { ...step, ...patch } : step))
    }));
  };

  const addStep = () => {
    setForm((prev) => ({ ...prev, steps: [...prev.steps, { action: "", tool: "", when: "" }] }));
  };

  const removeStep = (index: number) => {
    setForm((prev) => ({ ...prev, steps: prev.steps.filter((_, i) => i !== index) }));
  };

  const normalizeSteps = (steps: StepDraft[]): WorkflowStep[] =>
    steps
      .filter((s) => s.action.trim().length > 0)
      .map((s) => ({
        action: s.action.trim(),
        tool: s.tool.trim() || null,
        when: s.when.trim() || null
      }));

  const handleSubmit = async () => {
    const steps = normalizeSteps(form.steps);
    if (!form.title.trim() || !form.description.trim() || steps.length === 0) {
      toast.error("请填写标题、描述，并至少保留一个步骤");
      return;
    }
    setSubmitting(true);
    try {
      if (!editingName) {
        return;
      }
      await updateWorkflow(editingName, {
        title: form.title.trim(),
        description: form.description.trim(),
        steps,
        notes: form.notes.trim() || null,
        changeLog: "后管编辑"
      });
      toast.success("工作流已更新");
      setDialogOpen(false);
      await loadList();
    } catch (error) {
      toast.error(getErrorMessage(error));
    } finally {
      setSubmitting(false);
    }
  };

  const handleToggle = async (item: WorkflowListItem, enabled: boolean) => {
    try {
      await toggleWorkflow(item.name, enabled);
      toast.success(enabled ? "已启用" : "已停用（不再参与召回）");
      await loadList();
    } catch (error) {
      toast.error(getErrorMessage(error));
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteWorkflow(deleteTarget.name);
      toast.success("工作流已删除");
      setDeleteTarget(null);
      if (selected === deleteTarget.name) {
        setSelected(null);
      }
      await loadList(false);
    } catch (error) {
      toast.error(getErrorMessage(error));
    }
  };

  const handleRebuild = async () => {
    setRebuilding(true);
    try {
      const size = await rebuildWorkflowIndex();
      toast.success(`召回索引已重建（${size} 条）`);
      await loadList();
    } catch (error) {
      toast.error(getErrorMessage(error));
    } finally {
      setRebuilding(false);
    }
  };

  const enabledCount = useMemo(() => items.filter((w) => w.enabled).length, [items]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">工作流管理</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Agent 从对话中沉淀的可复用流程：相似问题召回后按固定步骤执行（共 {items.length} 条，启用 {enabledCount} 条）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={handleRebuild} disabled={rebuilding}>
            {rebuilding ? (
              <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="mr-1.5 h-4 w-4" />
            )}
            重建索引
          </Button>
          <Button size="sm" onClick={() => navigate("/admin/workflows/new")}>
            <Plus className="mr-1.5 h-4 w-4" />
            新建
          </Button>
        </div>
      </div>

      <div className="flex gap-4">
        {/* 左栏：列表 */}
        <Card className="w-[380px] shrink-0">
          <CardContent className="p-3">
            {loading ? (
              <div className="flex items-center justify-center py-10 text-sm text-muted-foreground">
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                加载中
              </div>
            ) : items.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <WorkflowIcon className="mb-2 h-8 w-8 text-muted-foreground/50" />
                <p className="text-sm text-muted-foreground">暂无工作流</p>
                <p className="mt-1 text-xs text-muted-foreground/70">
                  在对话中让 Agent 把解决流程固定为工作流，或点击右上角新建
                </p>
              </div>
            ) : (
              <div className="space-y-1.5">
                {items.map((item) => (
                  <button
                    key={item.name}
                    type="button"
                    onClick={() => setSelected(item.name)}
                    className={`w-full rounded-lg border px-3 py-2.5 text-left transition-colors ${
                      selected === item.name
                        ? "border-indigo-300 bg-indigo-50/60 dark:border-indigo-700 dark:bg-indigo-950/30"
                        : "border-transparent hover:bg-muted/60"
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-medium">{item.title}</span>
                      <Badge
                        variant="outline"
                        className={
                          item.enabled
                            ? "border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300"
                            : "text-muted-foreground"
                        }
                      >
                        {item.enabled ? "启用" : "停用"}
                      </Badge>
                    </div>
                    <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
                      <span className="truncate font-mono">{item.name}</span>
                      <span>·</span>
                      <span>{item.stepCount} 步</span>
                      <span>·</span>
                      <span>{item.source === "EXTRACTED" ? "对话提取" : "手工创建"}</span>
                    </div>
                    {!item.indexed ? (
                      <p className="mt-1 flex items-center gap-1 text-xs text-amber-600">
                        <AlertTriangle className="h-3 w-3" />
                        向量未就绪（不参与召回）
                      </p>
                    ) : null}
                  </button>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        {/* 右栏：详情 */}
        <Card className="min-w-0 flex-1">
          <CardContent className="p-4">
            {detailLoading ? (
              <div className="flex items-center justify-center py-20 text-sm text-muted-foreground">
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                加载详情
              </div>
            ) : !detail ? (
              <div className="flex items-center justify-center py-20 text-sm text-muted-foreground">
                请从左侧选择一个工作流
              </div>
            ) : (
              <div className="space-y-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <h2 className="truncate text-lg font-semibold">{detail.title}</h2>
                      <Badge variant="outline" className="font-mono">
                        {detail.name}
                      </Badge>
                    </div>
                    <p className="mt-1 text-sm text-muted-foreground">{detail.description}</p>
                    <p className="mt-1 text-xs text-muted-foreground/80">
                      来源：{detail.source === "EXTRACTED" ? "对话提取" : "手工创建"}
                      {detail.updatedBy ? ` · 更新人：${detail.updatedBy}` : ""}
                      {detail.updateTime ? ` · ${formatDate(detail.updateTime)}` : ""}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    <div className="flex items-center gap-1.5">
                      <Switch
                        checked={detail.enabled}
                        onCheckedChange={(checked) => {
                          const item = items.find((w) => w.name === detail.name);
                          if (item) void handleToggle(item, checked);
                        }}
                      />
                      <span className="text-xs text-muted-foreground">
                        {detail.enabled ? "启用" : "停用"}
                      </span>
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => navigate(`/admin/workflows/${encodeURIComponent(detail.name)}/edit`)}
                    >
                      <WorkflowIcon className="mr-1 h-3.5 w-3.5" />
                      画布编辑
                    </Button>
                    <Button variant="outline" size="sm" onClick={openEdit}>
                      编辑
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-rose-600 hover:text-rose-700"
                      onClick={() => {
                        const item = items.find((w) => w.name === detail.name);
                        if (item) setDeleteTarget(item);
                      }}
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                </div>

                {detail.notes ? (
                  <div className="rounded-lg bg-muted/50 px-3 py-2 text-sm text-muted-foreground">
                    <span className="font-medium">说明：</span>
                    {detail.notes}
                  </div>
                ) : null}

                <div>
                  <div className="mb-2 flex items-center gap-1.5 text-sm font-medium">
                    <GitBranch className="h-4 w-4" />
                    执行步骤（{detail.steps.length}）
                  </div>
                  <ol className="space-y-2">
                    {detail.steps.map((step, idx) => (
                      <li
                        key={idx}
                        className="flex gap-3 rounded-lg border bg-card px-3 py-2.5 text-sm"
                      >
                        <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-indigo-100 text-xs font-semibold text-indigo-700 dark:bg-indigo-900/50 dark:text-indigo-300">
                          {idx + 1}
                        </span>
                        <div className="min-w-0 space-y-1">
                          {step.when ? (
                            <div>
                              <Badge
                                variant="outline"
                                className="border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
                              >
                                条件：{step.when}
                              </Badge>
                            </div>
                          ) : null}
                          <p>{step.action}</p>
                          {step.tool ? (
                            <p className="text-xs text-muted-foreground">建议工具：{step.tool}</p>
                          ) : null}
                        </div>
                      </li>
                    ))}
                  </ol>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 新建 / 编辑 Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[85vh] max-w-2xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{editingName ? "编辑工作流" : "新建工作流"}</DialogTitle>
            <DialogDescription>
              工作流的名称与描述用于相似问题召回。此编辑器按线性步骤保存；如需拖拽编排分支，请使用列表中的「画布编辑」。
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="wf-name">标识（kebab-case）</Label>
                <Input
                  id="wf-name"
                  value={form.name}
                  disabled={Boolean(editingName)}
                  placeholder="refund-dispute"
                  onChange={(e) => setForm((prev) => ({ ...prev, name: e.target.value }))}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="wf-title">标题</Label>
                <Input
                  id="wf-title"
                  value={form.title}
                  placeholder="退款争议处理"
                  onChange={(e) => setForm((prev) => ({ ...prev, title: e.target.value }))}
                />
              </div>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="wf-desc">描述（何时使用）</Label>
              <Textarea
                id="wf-desc"
                value={form.description}
                rows={2}
                placeholder="用户投诉订单退款、要求退货时使用"
                onChange={(e) => setForm((prev) => ({ ...prev, description: e.target.value }))}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="wf-notes">说明（可选）</Label>
              <Input
                id="wf-notes"
                value={form.notes}
                placeholder="前置条件 / 边界说明"
                onChange={(e) => setForm((prev) => ({ ...prev, notes: e.target.value }))}
              />
            </div>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <Label>执行步骤</Label>
                <Button variant="outline" size="sm" onClick={addStep}>
                  <Plus className="mr-1 h-3.5 w-3.5" />
                  添加步骤
                </Button>
              </div>
              {form.steps.map((step, idx) => (
                <div key={idx} className="space-y-2 rounded-lg border p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-medium text-muted-foreground">步骤 {idx + 1}</span>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-6 px-2 text-rose-600 hover:text-rose-700"
                      disabled={form.steps.length <= 1}
                      onClick={() => removeStep(idx)}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                  <Textarea
                    value={step.action}
                    rows={2}
                    placeholder="本步骤要做什么（如：调用订单查询接口获取订单状态）"
                    onChange={(e) => updateStep(idx, { action: e.target.value })}
                  />
                  <div className="grid grid-cols-2 gap-2">
                    <Input
                      value={step.tool}
                      placeholder="建议工具（可选，如 rag_search）"
                      onChange={(e) => updateStep(idx, { tool: e.target.value })}
                    />
                    <Input
                      value={step.when}
                      placeholder="条件（可选，如：物流延误）"
                      onChange={(e) => updateStep(idx, { when: e.target.value })}
                    />
                  </div>
                </div>
              ))}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)} disabled={submitting}>
              取消
            </Button>
            <Button onClick={handleSubmit} disabled={submitting}>
              {submitting ? (
                <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
              ) : (
                <Save className="mr-1.5 h-4 w-4" />
              )}
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除确认 */}
      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除工作流</AlertDialogTitle>
            <AlertDialogDescription>
              确定删除「{deleteTarget?.title}」吗？删除后不再参与召回，此操作不可撤销。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete}>删除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
