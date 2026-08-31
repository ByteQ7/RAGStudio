import { useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  File,
  FileCode,
  FileJson,
  FileText,
  GitCompare,
  History,
  Inbox,
  Loader2,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  Trash2,
  Upload
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
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { Switch } from "@/components/ui/switch";
import { Badge } from "@/components/ui/badge";
import {
  commitSkill,
  createSkillBlank,
  createSkillZip,
  deleteSkill,
  disableSkill,
  enableSkill,
  getSkill,
  getSkillVersionFile,
  importSkill,
  listSkills,
  listSkillVersions,
  previewSkillVersionZip,
  reloadSkills,
  rollbackSkill,
  syncSkill,
  uploadSkillVersion,
  type SkillDetail,
  type SkillDiffFile,
  type SkillDiffResult,
  type SkillListItem,
  type SkillVersionInfo
} from "@/services/skillService";
import { SkillDiffDialog } from "@/pages/admin/skills/SkillDiffDialog";
import { getErrorMessage } from "@/utils/error";
import { formatDateTime as formatDate } from "@/utils/datetime";

const TYPE_LABELS: Record<string, string> = {
  http: "HTTP",
  script: "脚本",
  command: "命令",
  doc: "知识型"
};

const TYPE_BADGE_COLORS: Record<string, string> = {
  http: "bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300",
  script: "bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300",
  command: "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300",
  doc: "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
};

const TYPE_FALLBACK_BADGE =
  "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300";

const SYNC_BADGES: Record<string, { label: string; className: string }> = {
  SYNCED: {
    label: "已同步",
    className: "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
  },
  PENDING_SYNC: {
    label: "待同步",
    className: "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
  },
  DRIFTED: {
    label: "已漂移",
    className: "bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300"
  },
  UNMANAGED: {
    label: "未入库",
    className: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300"
  },
  RUNTIME_ONLY: {
    label: "仅运行时",
    className: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300"
  }
};

function syncBadgeOf(state: string | null | undefined) {
  return (
    SYNC_BADGES[state ?? ""] ?? {
      label: state || "未知",
      className: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300"
    }
  );
}

const MAX_EDIT_BYTES = 1024 * 1024;

function formatSize(size: number | null | undefined): string {
  if (size === null || size === undefined) return "-";
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function fileIcon(path: string) {
  if (path.startsWith("scripts/")) return <FileCode className="w-3.5 h-3.5 shrink-0" />;
  if (path.endsWith(".json") || path.endsWith(".yaml") || path.endsWith(".yml"))
    return <FileJson className="w-3.5 h-3.5 shrink-0" />;
  if (path.endsWith(".md")) return <FileText className="w-3.5 h-3.5 shrink-0" />;
  return <File className="w-3.5 h-3.5 shrink-0" />;
}

function diffFileMeta(file: SkillDiffFile): { label: string; dot: string; text: string } {
  switch (file.status) {
    case "added":
      return {
        label: "新增",
        dot: "bg-emerald-500",
        text: "text-emerald-600 dark:text-emerald-400"
      };
    case "deleted":
      return { label: "删除", dot: "bg-red-500", text: "text-red-600 dark:text-red-400" };
    case "modified":
      return { label: "修改", dot: "bg-amber-500", text: "text-amber-600 dark:text-amber-400" };
    default:
      return { label: "未变更", dot: "bg-slate-400", text: "text-muted-foreground" };
  }
}

export function SkillListPage() {
  const [skills, setSkills] = useState<SkillListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState("");

  const [selectedName, setSelectedName] = useState<string | null>(null);
  const [detail, setDetail] = useState<SkillDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [tab, setTab] = useState("files");

  // 文件 Tab：文件内容缓存 + 草稿变更集（保存时合并为一个新版本）
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [fileContents, setFileContents] = useState<Record<string, string>>({});
  const [fileLoading, setFileLoading] = useState(false);
  const [upserts, setUpserts] = useState<Record<string, string>>({});
  const [deletes, setDeletes] = useState<Record<string, boolean>>({});

  // 提交新版本
  const [commitOpen, setCommitOpen] = useState(false);
  const [changeLog, setChangeLog] = useState("");
  const [committing, setCommitting] = useState(false);

  // 版本历史
  const [versions, setVersions] = useState<SkillVersionInfo[]>([]);
  const [rollbackTarget, setRollbackTarget] = useState<SkillVersionInfo | null>(null);
  const [rollingBack, setRollingBack] = useState(false);
  const [diffOpen, setDiffOpen] = useState(false);
  const [diffFrom, setDiffFrom] = useState(1);
  const [diffTo, setDiffTo] = useState(2);

  // ZIP 上传新版本
  const [upgradeOpen, setUpgradeOpen] = useState(false);
  const [upgradeFile, setUpgradeFile] = useState<File | null>(null);
  const [previewResult, setPreviewResult] = useState<SkillDiffResult | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const upgradeInputRef = useRef<HTMLInputElement>(null);

  // 新建技能
  const [newOpen, setNewOpen] = useState(false);
  const [newMode, setNewMode] = useState("zip");
  const [newFile, setNewFile] = useState<File | null>(null);
  const [blankName, setBlankName] = useState("");
  const [blankDescription, setBlankDescription] = useState("");
  const [creating, setCreating] = useState(false);
  const newFileInputRef = useRef<HTMLInputElement>(null);

  // 删除与通用忙碌标记
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const detailSeq = useRef(0);
  const listSeq = useRef(0);

  const loadList = async () => {
    const seq = ++listSeq.current;
    try {
      setLoading(true);
      const data = await listSkills();
      if (seq !== listSeq.current) return;
      setSkills(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载技能列表失败"));
    } finally {
      if (seq === listSeq.current) setLoading(false);
    }
  };

  useEffect(() => {
    loadList();
  }, []);

  const resetEditorState = () => {
    setSelectedFile(null);
    setFileContents({});
    setUpserts({});
    setDeletes({});
    setVersions([]);
    setTab("files");
  };

  const loadDetail = async (name: string) => {
    const seq = ++detailSeq.current;
    setDetailLoading(true);
    setDetailError(false);
    setDetail(null);
    resetEditorState();
    try {
      const data = await getSkill(name);
      if (seq !== detailSeq.current) return;
      setDetail(data);
      try {
        const list = await listSkillVersions(name);
        if (seq !== detailSeq.current) return;
        setVersions(list);
      } catch {
        // 版本列表加载失败不阻塞详情展示
      }
    } catch (error) {
      if (seq === detailSeq.current) {
        setDetailError(true);
        toast.error(getErrorMessage(error, "加载技能详情失败"));
      }
    } finally {
      if (seq === detailSeq.current) setDetailLoading(false);
    }
  };

  const handleSelect = (name: string) => {
    setSelectedName(name);
    // 未入库技能无 DB 详情，直接显示收编引导，避免详情接口报错
    const item = skills.find((s) => s.name === name);
    if (item?.syncState === "UNMANAGED") {
      setDetailLoading(false);
      setDetailError(false);
      setDetail(null);
      resetEditorState();
      return;
    }
    loadDetail(name);
  };

  const afterWrite = async (name: string) => {
    await loadList();
    if (selectedName === name) {
      await loadDetail(name);
    }
  };

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return skills;
    return skills.filter(
      (s) => s.name.toLowerCase().includes(kw) || (s.description || "").toLowerCase().includes(kw)
    );
  }, [skills, keyword]);

  const selectedItem = skills.find((s) => s.name === selectedName) ?? null;

  // 合并草稿后的文件清单
  const allFiles = useMemo(() => {
    if (!detail) return [];
    const base = detail.files.map((f) => ({
      path: f.path,
      isBinary: f.isBinary,
      size: f.size,
      isNew: false
    }));
    for (const path of Object.keys(upserts)) {
      if (!base.some((f) => f.path === path)) {
        base.push({ path, isBinary: false, size: new Blob([upserts[path]]).size, isNew: true });
      }
    }
    return base.sort((a, b) => a.path.localeCompare(b.path));
  }, [detail, upserts]);

  const hasDraft = Object.keys(upserts).length > 0 || Object.keys(deletes).length > 0;

  // 待保存变更（编辑后又删除的文件只计一次，避免"3 个修改 + 1 个删除"实为 3 个文件的误导）
  const pendingUpsertCount = useMemo(
    () => Object.keys(upserts).filter((path) => !deletes[path]).length,
    [upserts, deletes]
  );
  const pendingDeleteCount = useMemo(() => Object.keys(deletes).length, [deletes]);
  const pendingChangeCount = useMemo(
    () => new Set([...Object.keys(upserts), ...Object.keys(deletes)]).size,
    [upserts, deletes]
  );

  // 选中文件内容：草稿优先
  const currentFileContent = selectedFile
    ? (upserts[selectedFile] ?? fileContents[selectedFile] ?? "")
    : "";

  useEffect(() => {
    if (
      !selectedFile ||
      !detail ||
      upserts[selectedFile] !== undefined ||
      fileContents[selectedFile] !== undefined
    ) {
      return;
    }
    const meta = detail.files.find((f) => f.path === selectedFile);
    if (!meta || meta.isBinary || meta.size > MAX_EDIT_BYTES) return;
    let active = true;
    setFileLoading(true);
    getSkillVersionFile(detail.name, detail.currentVersion ?? 1, selectedFile)
      .then((c) => {
        if (active) setFileContents((prev) => ({ ...prev, [selectedFile]: c.content }));
      })
      .catch((error) => toast.error(getErrorMessage(error, "加载文件内容失败")))
      .finally(() => {
        if (active) setFileLoading(false);
      });
    return () => {
      active = false;
    };
  }, [selectedFile, detail, upserts, fileContents]);

  const handleCommit = async () => {
    if (!detail) return;
    try {
      setCommitting(true);
      const saved = await commitSkill(detail.name, {
        changeLog: changeLog.trim(),
        upserts,
        deletions: Object.keys(deletes)
      });
      toast.success(`已保存为新版本 v${saved.currentVersion} 并生效`);
      setCommitOpen(false);
      setChangeLog("");
      setUpserts({});
      setDeletes({});
      await afterWrite(detail.name);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存新版本失败"));
    } finally {
      setCommitting(false);
    }
  };

  const handleRollback = async () => {
    if (!detail || !rollbackTarget) return;
    try {
      setRollingBack(true);
      const saved = await rollbackSkill(detail.name, rollbackTarget.version);
      toast.success(`已回滚到 v${rollbackTarget.version}（生成当前版本 v${saved.currentVersion}）`);
      setRollbackTarget(null);
      await afterWrite(detail.name);
    } catch (error) {
      toast.error(getErrorMessage(error, "回滚失败"));
    } finally {
      setRollingBack(false);
    }
  };

  const handlePreviewUpgrade = async () => {
    if (!detail || !upgradeFile) return;
    try {
      setPreviewLoading(true);
      setPreviewResult(await previewSkillVersionZip(detail.name, upgradeFile));
    } catch (error) {
      toast.error(getErrorMessage(error, "预览差异失败"));
    } finally {
      setPreviewLoading(false);
    }
  };

  const handleUploadUpgrade = async () => {
    if (!detail || !upgradeFile) return;
    try {
      setUploading(true);
      const saved = await uploadSkillVersion(detail.name, upgradeFile);
      toast.success(`已上传为新版本 v${saved.currentVersion} 并生效`);
      setUpgradeOpen(false);
      setUpgradeFile(null);
      setPreviewResult(null);
      await afterWrite(detail.name);
    } catch (error) {
      toast.error(getErrorMessage(error, "上传新版本失败"));
    } finally {
      setUploading(false);
    }
  };

  const handleCreate = async () => {
    try {
      setCreating(true);
      if (newMode === "zip") {
        if (!newFile) {
          toast.error("请选择 ZIP 包");
          return;
        }
        const saved = await createSkillZip(newFile);
        toast.success(`技能 ${saved.name} 已创建（v${saved.currentVersion}）`);
      } else {
        const saved = await createSkillBlank({
          name: blankName.trim(),
          description: blankDescription.trim()
        });
        toast.success(`技能 ${saved.name} 已创建（v${saved.currentVersion}）`);
      }
      setNewOpen(false);
      setNewFile(null);
      setBlankName("");
      setBlankDescription("");
      await loadList();
    } catch (error) {
      toast.error(getErrorMessage(error, "创建技能失败"));
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async () => {
    if (!detail) return;
    try {
      setDeleting(true);
      await deleteSkill(detail.name);
      toast.success(`技能 ${detail.name} 已删除`);
      setDeleteOpen(false);
      setSelectedName(null);
      setDetail(null);
      await loadList();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    } finally {
      setDeleting(false);
    }
  };

  const handleToggleEnabled = async (checked: boolean) => {
    if (!detail) return;
    try {
      setBusy(true);
      await (checked ? enableSkill(detail.name) : disableSkill(detail.name));
      toast.success(checked ? "已启用并加载" : "已停用并从运行时卸载");
      await afterWrite(detail.name);
    } catch (error) {
      toast.error(getErrorMessage(error, checked ? "启用失败" : "停用失败"));
    } finally {
      setBusy(false);
    }
  };

  const handleSync = async () => {
    if (!detail) return;
    try {
      setBusy(true);
      await syncSkill(detail.name);
      toast.success("已按 DB 当前版本重新同步工作区");
      await afterWrite(detail.name);
    } catch (error) {
      toast.error(getErrorMessage(error, "同步失败"));
    } finally {
      setBusy(false);
    }
  };

  const handleImport = async (name: string) => {
    try {
      setBusy(true);
      const saved = await importSkill(name);
      toast.success(`技能 ${saved.name} 已收编入库（v${saved.currentVersion}）`);
      await loadList();
      if (selectedName === name) {
        await loadDetail(name);
      }
    } catch (error) {
      toast.error(getErrorMessage(error, "收编失败"));
    } finally {
      setBusy(false);
    }
  };

  const handleReloadRuntime = async () => {
    try {
      setBusy(true);
      await reloadSkills();
      toast.success("运行时缓存已刷新");
      await loadList();
    } catch (error) {
      toast.error(getErrorMessage(error, "刷新失败"));
    } finally {
      setBusy(false);
    }
  };

  const openDiff = (from: number, to: number) => {
    setDiffFrom(from);
    setDiffTo(to);
    setDiffOpen(true);
  };

  const selectedFileMeta =
    detail && selectedFile ? detail.files.find((f) => f.path === selectedFile) : null;
  const selectedFileDeleted = selectedFile ? !!deletes[selectedFile] : false;
  const selectedFileIsDraftNew = selectedFile
    ? upserts[selectedFile] !== undefined && !selectedFileMeta
    : false;
  // 文本但超过 1MB（如大型 geojson）：不支持在线编辑/对比，避免空编辑器误导用户提交
  const selectedFileOversized =
    !!selectedFileMeta && !selectedFileMeta.isBinary && selectedFileMeta.size > MAX_EDIT_BYTES;

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">SKILL 管理</h1>
          <p className="admin-page-subtitle">
            技能入库版本化管理：编辑保存即产生新版本并自动同步到运行时，支持版本对比与回滚
          </p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜索名称 / 描述"
            className="w-[260px]"
          />
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button className="admin-primary-gradient">
                <Plus className="w-4 h-4 mr-1.5" />
                新建技能
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem
                onClick={() => {
                  setNewMode("zip");
                  setNewFile(null);
                  setNewOpen(true);
                }}
              >
                <Upload className="w-4 h-4 mr-2" /> 上传 ZIP 包
              </DropdownMenuItem>
              <DropdownMenuItem
                onClick={() => {
                  setNewMode("blank");
                  setBlankName("");
                  setBlankDescription("");
                  setNewOpen(true);
                }}
              >
                <FileText className="w-4 h-4 mr-2" /> 空白模板
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <Button variant="outline" onClick={handleReloadRuntime} disabled={busy}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
        </div>
      </div>

      <div className="flex gap-4 items-stretch">
        {/* 左侧列表 */}
        <Card className="w-[380px] shrink-0">
          <CardContent className="pt-4">
            {loading ? (
              <div className="text-center py-8 text-muted-foreground text-sm flex items-center justify-center gap-2">
                <Loader2 className="w-4 h-4 animate-spin" /> 加载中...
              </div>
            ) : filtered.length === 0 ? (
              <div className="text-center py-8 text-muted-foreground text-sm">
                {skills.length === 0 ? "暂无技能，点击右上角「新建技能」开始" : "无匹配的技能"}
              </div>
            ) : (
              <div className="max-h-[calc(100vh-300px)] overflow-y-auto pr-1 space-y-1">
                {filtered.map((item) => {
                  const syncBadge = syncBadgeOf(item.syncState);
                  const typeLabel = TYPE_LABELS[item.skillType || "doc"] || "知识型";
                  return (
                    <div key={item.name} className="relative">
                      <button
                        type="button"
                        onClick={() => handleSelect(item.name)}
                        className={`w-full text-left rounded-lg border px-3 py-2.5 transition-colors ${
                          selectedName === item.name
                            ? "border-primary bg-[var(--color-fill-quaternary)]"
                            : "hover:bg-[var(--color-fill-quaternary)]"
                        }`}
                        style={{
                          borderColor:
                            selectedName === item.name
                              ? "hsl(var(--primary))"
                              : "var(--color-border-secondary)"
                        }}
                      >
                        <div className="flex items-center justify-between gap-2 pr-14">
                          <span
                            className="text-sm font-medium truncate font-mono"
                            style={{ color: "var(--color-text)" }}
                          >
                            {item.name}
                          </span>
                          <Badge
                            className={`shrink-0 text-[10px] ${TYPE_BADGE_COLORS[item.skillType || ""] || TYPE_FALLBACK_BADGE}`}
                          >
                            {typeLabel}
                          </Badge>
                        </div>
                        <div className="mt-1 flex items-center gap-1.5 flex-wrap">
                          {item.currentVersion !== null && (
                            <>
                              <span
                                className="text-[10px]"
                                style={{ color: "var(--color-text-tertiary)" }}
                              >
                                v{item.currentVersion}
                              </span>
                              <span
                                className={`h-1.5 w-1.5 rounded-full ${item.enabled ? "bg-emerald-500" : "bg-slate-400"}`}
                                title={item.enabled ? "已启用" : "已停用"}
                              />
                            </>
                          )}
                          <span
                            className={`text-[10px] px-1.5 py-px rounded-full ${syncBadge.className}`}
                          >
                            {syncBadge.label}
                          </span>
                          {item.loaded === false && item.errors && (
                            <span className="text-[10px] text-red-500">加载失败</span>
                          )}
                        </div>
                        {item.description && (
                          <p
                            className="mt-1 text-[11px] truncate"
                            style={{ color: "var(--color-text-tertiary)" }}
                          >
                            {item.description}
                          </p>
                        )}
                      </button>
                      {item.syncState === "UNMANAGED" && (
                        <Button
                          variant="outline"
                          size="sm"
                          className="absolute right-2 top-2 h-6 px-2 text-[10px]"
                          disabled={busy}
                          onClick={() => handleImport(item.name)}
                        >
                          收编
                        </Button>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </CardContent>
        </Card>

        {/* 右侧详情 */}
        <Card className="flex-1 min-w-0">
          <CardContent className="pt-4">
            {!selectedName ? (
              <div className="text-center py-16 text-muted-foreground text-sm">
                请选择左侧技能进行管理
              </div>
            ) : selectedItem?.syncState === "UNMANAGED" ? (
              <div className="text-center py-16 space-y-3">
                <Inbox className="w-8 h-8 mx-auto text-muted-foreground" />
                <div className="text-sm text-muted-foreground">
                  技能 <span className="font-mono">{selectedName}</span>{" "}
                  尚未入库（工作区目录存在但无版本记录）
                </div>
                <Button size="sm" disabled={busy} onClick={() => handleImport(selectedName)}>
                  收编入库（当前内容建档为 v1）
                </Button>
              </div>
            ) : detailError ? (
              <div className="text-center py-16 space-y-3">
                <AlertTriangle className="w-8 h-8 mx-auto text-muted-foreground" />
                <div className="text-sm text-muted-foreground">
                  技能详情加载失败，请检查服务状态后重试
                </div>
                <Button size="sm" variant="outline" onClick={() => loadDetail(selectedName)}>
                  <RefreshCw className="w-3.5 h-3.5 mr-1" /> 重试
                </Button>
              </div>
            ) : detailLoading || !detail ? (
              <div className="text-center py-16 text-muted-foreground text-sm flex items-center justify-center gap-2">
                <Loader2 className="w-4 h-4 animate-spin" /> 加载中...
              </div>
            ) : (
              <div className="space-y-4">
                {/* 详情头 */}
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <h2
                        className="text-base font-semibold font-mono"
                        style={{ color: "var(--color-text)" }}
                      >
                        {detail.name}
                      </h2>
                      <Badge
                        className={`text-[10px] ${TYPE_BADGE_COLORS[detail.skillType || ""] || TYPE_FALLBACK_BADGE}`}
                      >
                        {TYPE_LABELS[detail.skillType || "doc"] || "知识型"}
                      </Badge>
                      {detail.currentVersion !== null && (
                        <Badge variant="outline" className="text-[10px]">
                          当前 v{detail.currentVersion}
                        </Badge>
                      )}
                      {detail.declaredVersion && (
                        <span
                          className="text-[10px]"
                          style={{ color: "var(--color-text-tertiary)" }}
                        >
                          声明版本 {detail.declaredVersion}
                        </span>
                      )}
                      <span
                        className={`text-[10px] px-1.5 py-px rounded-full ${syncBadgeOf(detail.syncState).className}`}
                      >
                        {syncBadgeOf(detail.syncState).label}
                      </span>
                    </div>
                    {detail.description && (
                      <p className="mt-1 text-xs" style={{ color: "var(--color-text-secondary)" }}>
                        {detail.description}
                      </p>
                    )}
                    <p className="mt-1 text-[11px]" style={{ color: "var(--color-text-tertiary)" }}>
                      {detail.changeLog ? `${detail.changeLog} · ` : ""}
                      {detail.updatedBy ? `${detail.updatedBy} · ` : ""}
                      {formatDate(detail.updateTime)}
                    </p>
                    {(detail.errors || detail.warnings) && (
                      <p
                        className={`mt-1 text-[11px] flex items-center gap-1 ${detail.errors ? "text-red-500" : "text-amber-500"}`}
                      >
                        <AlertTriangle className="w-3 h-3" />
                        {detail.errors || detail.warnings}
                      </p>
                    )}
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {(detail.syncState === "PENDING_SYNC" || detail.syncState === "DRIFTED") && (
                      <Button variant="outline" size="sm" disabled={busy} onClick={handleSync}>
                        <RefreshCw className="w-3.5 h-3.5 mr-1" /> 同步
                      </Button>
                    )}
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy}
                      onClick={() => {
                        setUpgradeFile(null);
                        setPreviewResult(null);
                        setUpgradeOpen(true);
                      }}
                    >
                      <Upload className="w-3.5 h-3.5 mr-1" /> 上传新版本
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-destructive hover:text-destructive"
                      disabled={busy}
                      onClick={() => setDeleteOpen(true)}
                    >
                      <Trash2 className="w-3.5 h-3.5 mr-1" /> 删除
                    </Button>
                    <label
                      htmlFor="skill-enabled-switch"
                      className="flex items-center gap-2 text-xs cursor-pointer"
                      style={{ color: "var(--color-text-secondary)" }}
                    >
                      启用
                      <Switch
                        id="skill-enabled-switch"
                        checked={!!detail.enabled}
                        onCheckedChange={handleToggleEnabled}
                        disabled={busy}
                      />
                    </label>
                  </div>
                </div>

                <Tabs value={tab} onValueChange={setTab}>
                  <TabsList>
                    <TabsTrigger value="files">文件（{allFiles.length}）</TabsTrigger>
                    <TabsTrigger value="versions">
                      <History className="w-3.5 h-3.5 mr-1" />
                      版本历史
                    </TabsTrigger>
                  </TabsList>

                  {/* 文件 Tab */}
                  <TabsContent value="files" className="mt-3">
                    <div
                      className="flex gap-3"
                      style={{ height: "calc(100vh - 430px)", minHeight: "360px" }}
                    >
                      <div
                        className="w-[260px] shrink-0 rounded-lg border overflow-y-auto"
                        style={{ borderColor: "var(--color-border-secondary)" }}
                      >
                        {allFiles.map((file) => {
                          const isDeleted = !!deletes[file.path];
                          const isModified =
                            upserts[file.path] !== undefined && !file.isNew && !isDeleted;
                          const depth = file.path.split("/").length - 1;
                          return (
                            <div
                              key={file.path}
                              className={`flex items-center gap-1.5 px-2 py-1.5 text-[11px] font-mono border-b ${
                                selectedFile === file.path
                                  ? "bg-[var(--color-fill-quaternary)]"
                                  : "hover:bg-[var(--color-fill-quaternary)]"
                              } ${isDeleted ? "opacity-50" : "cursor-pointer"}`}
                              style={{
                                borderColor: "var(--color-border-secondary)",
                                paddingLeft: `${8 + depth * 12}px`
                              }}
                              onClick={() => !isDeleted && setSelectedFile(file.path)}
                            >
                              {fileIcon(file.path)}
                              <span
                                className={`truncate ${isDeleted ? "line-through" : ""}`}
                                style={{
                                  color:
                                    file.isNew && !isDeleted
                                      ? "hsl(var(--primary))"
                                      : "var(--color-text)"
                                }}
                              >
                                {file.path.split("/").pop()}
                              </span>
                              {file.isNew && !isDeleted && (
                                <span className="text-[10px] text-emerald-600 dark:text-emerald-400 shrink-0">
                                  新增
                                </span>
                              )}
                              {isModified && (
                                <span
                                  className="h-1.5 w-1.5 rounded-full bg-amber-500 shrink-0"
                                  title="已修改（待保存）"
                                />
                              )}
                              {isDeleted && (
                                <button
                                  type="button"
                                  className="ml-auto text-[10px] text-muted-foreground hover:text-foreground shrink-0"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setDeletes((prev) => {
                                      const next = { ...prev };
                                      delete next[file.path];
                                      return next;
                                    });
                                  }}
                                >
                                  撤销
                                </button>
                              )}
                            </div>
                          );
                        })}
                      </div>
                      <div className="flex-1 min-w-0 flex flex-col gap-2">
                        {!selectedFile ? (
                          <div className="flex-1 flex items-center justify-center text-sm text-muted-foreground">
                            从左侧选择文件查看 /
                            编辑；二进制文件（如数据集）请通过「上传新版本」整包替换
                          </div>
                        ) : (
                          <>
                            <div className="flex items-center justify-between gap-2">
                              <div className="flex items-center gap-2 min-w-0">
                                <span
                                  className="font-mono text-xs truncate"
                                  style={{ color: "var(--color-text)" }}
                                >
                                  {selectedFile}
                                </span>
                                {selectedFileIsDraftNew && (
                                  <Badge className="text-[10px]">新增文件</Badge>
                                )}
                                {upserts[selectedFile] !== undefined && !selectedFileIsDraftNew && (
                                  <Badge className="text-[10px] bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300">
                                    已修改
                                  </Badge>
                                )}
                                {selectedFileDeleted && (
                                  <Badge className="text-[10px] bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300">
                                    将删除
                                  </Badge>
                                )}
                              </div>
                              <div className="flex items-center gap-2 shrink-0">
                                {selectedFileMeta && selectedFileMeta.isBinary && (
                                  <span className="text-[11px] text-muted-foreground">
                                    二进制文件 · {formatSize(selectedFileMeta.size)}
                                  </span>
                                )}
                                {!selectedFileDeleted && (
                                  <Button
                                    variant="ghost"
                                    size="sm"
                                    className="text-destructive hover:text-destructive h-7 px-2 text-xs"
                                    onClick={() =>
                                      setDeletes((prev) => ({ ...prev, [selectedFile]: true }))
                                    }
                                  >
                                    <Trash2 className="w-3 h-3 mr-1" /> 删除文件
                                  </Button>
                                )}
                              </div>
                            </div>
                            {selectedFileMeta?.isBinary ? (
                              <div
                                className="flex-1 flex items-center justify-center rounded-lg border text-sm text-muted-foreground"
                                style={{ borderColor: "var(--color-border-secondary)" }}
                              >
                                二进制文件不支持在线编辑；如需替换请使用「上传新版本」（ZIP 包）
                              </div>
                            ) : selectedFileOversized ? (
                              <div
                                className="flex-1 flex flex-col items-center justify-center gap-1 rounded-lg border text-sm text-muted-foreground"
                                style={{ borderColor: "var(--color-border-secondary)" }}
                              >
                                <span>
                                  文本文件超过 1MB（{formatSize(selectedFileMeta?.size)}
                                  ），不支持在线编辑与行级对比
                                </span>
                                <span className="text-xs">
                                  如需变更请使用「上传新版本」（ZIP 包）
                                </span>
                              </div>
                            ) : (
                              <Textarea
                                value={currentFileContent}
                                onChange={(event) =>
                                  setUpserts((prev) => ({
                                    ...prev,
                                    [selectedFile]: event.target.value
                                  }))
                                }
                                disabled={selectedFileDeleted || fileLoading}
                                placeholder={fileLoading ? "加载中..." : "输入文件内容..."}
                                className="flex-1 font-mono text-xs leading-relaxed resize-none"
                              />
                            )}
                          </>
                        )}
                        {hasDraft && (
                          <div
                            className="flex items-center justify-between rounded-lg border px-3 py-2"
                            style={{
                              borderColor: "hsl(var(--primary) / 0.4)",
                              background: "var(--color-fill-quaternary)"
                            }}
                          >
                            <span
                              className="text-xs"
                              style={{ color: "var(--color-text-secondary)" }}
                            >
                              待保存变更：{pendingUpsertCount} 个文件修改/新增 ·{" "}
                              {pendingDeleteCount} 个文件删除（保存时生成一个新版本）
                            </span>
                            <div className="flex items-center gap-2">
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => {
                                  setUpserts({});
                                  setDeletes({});
                                }}
                              >
                                放弃
                              </Button>
                              <Button
                                size="sm"
                                className="admin-primary-gradient"
                                onClick={() => setCommitOpen(true)}
                              >
                                <Save className="w-3.5 h-3.5 mr-1" /> 保存为新版本
                              </Button>
                            </div>
                          </div>
                        )}
                      </div>
                    </div>
                  </TabsContent>

                  {/* 版本历史 Tab */}
                  <TabsContent value="versions" className="mt-3">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-xs text-muted-foreground">
                        全部版本按时间倒序；回滚以旧版本内容生成新版本，版本号继续递增
                      </span>
                      {versions.length > 1 && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() =>
                            openDiff(
                              Math.max(1, (detail.currentVersion ?? 2) - 1),
                              detail.currentVersion ?? 1
                            )
                          }
                        >
                          <GitCompare className="w-3.5 h-3.5 mr-1" /> 任意版本对比
                        </Button>
                      )}
                    </div>
                    {versions.length === 0 ? (
                      <div className="text-center py-8 text-muted-foreground text-sm">
                        暂无版本记录
                      </div>
                    ) : (
                      <div className="max-h-[calc(100vh-400px)] overflow-y-auto space-y-2">
                        {versions.map((item) => (
                          <div
                            key={item.version}
                            className="rounded-lg border p-3"
                            style={{ borderColor: "var(--color-border-secondary)" }}
                          >
                            <div className="flex items-center justify-between gap-2">
                              <div className="flex items-center gap-2 flex-wrap">
                                <Badge variant="outline" className="font-mono">
                                  v{item.version}
                                </Badge>
                                {item.current && (
                                  <Badge className="text-[10px] bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300">
                                    当前
                                  </Badge>
                                )}
                                <span
                                  className="text-[11px]"
                                  style={{ color: "var(--color-text-tertiary)" }}
                                >
                                  {item.fileCount} 个文件 · {formatSize(item.totalSize)} ·{" "}
                                  {item.createdBy || "-"} · {formatDate(item.createTime)}
                                </span>
                              </div>
                              <div className="flex items-center gap-1.5 shrink-0">
                                <Button
                                  variant="outline"
                                  size="sm"
                                  disabled={item.version <= 1}
                                  onClick={() => openDiff(item.version - 1, item.version)}
                                >
                                  <GitCompare className="w-3.5 h-3.5 mr-1" />
                                  {item.current ? "与上一版对比" : "查看差异"}
                                </Button>
                                <Button
                                  variant="outline"
                                  size="sm"
                                  disabled={item.current}
                                  onClick={() => setRollbackTarget(item)}
                                >
                                  <RotateCcw className="w-3.5 h-3.5 mr-1" /> 回滚到此版本
                                </Button>
                              </div>
                            </div>
                            {item.changeLog && (
                              <p
                                className="mt-1.5 text-xs"
                                style={{ color: "var(--color-text-secondary)" }}
                              >
                                {item.changeLog}
                              </p>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </TabsContent>
                </Tabs>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 保存为新版本 */}
      <Dialog open={commitOpen} onOpenChange={setCommitOpen}>
        <DialogContent className="sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>保存为新版本</DialogTitle>
            <DialogDescription>
              {pendingChangeCount} 个文件变更将提交为 v
              {(detail?.currentVersion ?? 0) + 1}， 保存后自动物化到工作区并热重载生效
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label>版本说明（可选）</Label>
            <Textarea
              value={changeLog}
              onChange={(event) => setChangeLog(event.target.value)}
              placeholder="例如：修正参数说明并更新脚本"
              className="min-h-[80px]"
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCommitOpen(false)}>
              取消
            </Button>
            <Button onClick={handleCommit} disabled={committing} className="admin-primary-gradient">
              {committing ? "保存中..." : "保存并生效"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 上传新版本（ZIP） */}
      <Dialog open={upgradeOpen} onOpenChange={setUpgradeOpen}>
        <DialogContent className="sm:max-w-[640px]">
          <DialogHeader>
            <DialogTitle>上传新版本：{detail?.name}</DialogTitle>
            <DialogDescription>
              ZIP 包内为技能目录结构（SKILL.md 必填），可先预览与当前版本的差异再上传
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <Input
                type="file"
                accept=".zip"
                ref={upgradeInputRef}
                onChange={(event) => {
                  setUpgradeFile(event.target.files?.[0] ?? null);
                  setPreviewResult(null);
                }}
                className="flex-1"
              />
              <Button
                variant="outline"
                size="sm"
                disabled={!upgradeFile || previewLoading}
                onClick={handlePreviewUpgrade}
              >
                {previewLoading ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <GitCompare className="w-3.5 h-3.5 mr-1" />
                )}
                预览差异
              </Button>
            </div>
            {previewResult && (
              <div
                className="rounded-lg border p-3 space-y-2"
                style={{ borderColor: "var(--color-border-secondary)" }}
              >
                <div className="flex items-center gap-2 text-[11px]">
                  <span className="text-emerald-600 dark:text-emerald-400">
                    +{previewResult.added} 新增
                  </span>
                  <span className="text-red-600 dark:text-red-400">
                    −{previewResult.deleted} 删除
                  </span>
                  <span className="text-amber-600 dark:text-amber-400">
                    ~{previewResult.modified} 修改
                  </span>
                  <span className="text-muted-foreground">{previewResult.unchanged} 未变更</span>
                </div>
                <div className="text-[11px] text-muted-foreground">
                  元信息：{previewResult.manifestChanges.join("；")}
                </div>
                <div className="max-h-[200px] overflow-y-auto space-y-0.5">
                  {previewResult.files
                    .filter((f) => f.status !== "unchanged")
                    .map((file) => {
                      const meta = diffFileMeta(file);
                      return (
                        <div
                          key={file.path}
                          className="flex items-center gap-1.5 text-[11px] font-mono"
                        >
                          <span className={`h-1.5 w-1.5 rounded-full ${meta.dot}`} />
                          <span className="truncate" style={{ color: "var(--color-text)" }}>
                            {file.path}
                          </span>
                          <span className={`ml-auto shrink-0 ${meta.text}`}>{meta.label}</span>
                        </div>
                      );
                    })}
                </div>
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setUpgradeOpen(false)}>
              取消
            </Button>
            <Button
              onClick={handleUploadUpgrade}
              disabled={!upgradeFile || uploading}
              className="admin-primary-gradient"
            >
              {uploading ? "上传中..." : "上传并生效"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 新建技能 */}
      <Dialog open={newOpen} onOpenChange={setNewOpen}>
        <DialogContent className="sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>新建技能</DialogTitle>
            <DialogDescription>
              ZIP 包内为技能目录结构（SKILL.md 必填，可选 skill.yaml、scripts/、references/）；
              空白模板将生成 SKILL.md 骨架
            </DialogDescription>
          </DialogHeader>
          <Tabs value={newMode} onValueChange={setNewMode}>
            <TabsList>
              <TabsTrigger value="zip">上传 ZIP 包</TabsTrigger>
              <TabsTrigger value="blank">空白模板</TabsTrigger>
            </TabsList>
            <TabsContent value="zip" className="mt-3">
              <Input
                type="file"
                accept=".zip"
                ref={newFileInputRef}
                onChange={(event) => setNewFile(event.target.files?.[0] ?? null)}
              />
            </TabsContent>
            <TabsContent value="blank" className="mt-3 space-y-3">
              <div className="space-y-1.5">
                <Label>技能标识（name）</Label>
                <Input
                  value={blankName}
                  onChange={(event) => setBlankName(event.target.value)}
                  placeholder="例如：weather-query（小写字母/数字/连字符）"
                  className="font-mono"
                />
              </div>
              <div className="space-y-1.5">
                <Label>描述（description，注入 System Prompt）</Label>
                <Textarea
                  value={blankDescription}
                  onChange={(event) => setBlankDescription(event.target.value)}
                  placeholder="这个技能做什么、什么场景下触发"
                  className="min-h-[72px]"
                />
              </div>
            </TabsContent>
          </Tabs>
          <DialogFooter>
            <Button variant="outline" onClick={() => setNewOpen(false)}>
              取消
            </Button>
            <Button
              onClick={handleCreate}
              disabled={
                creating ||
                (newMode === "zip" ? !newFile : !blankName.trim() || !blankDescription.trim())
              }
              className="admin-primary-gradient"
            >
              {creating ? "创建中..." : "创建"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 回滚确认 */}
      <AlertDialog open={!!rollbackTarget} onOpenChange={() => setRollbackTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认回滚</AlertDialogTitle>
            <AlertDialogDescription>
              将以 v{rollbackTarget?.version} 的内容生成新版本 v{(detail?.currentVersion ?? 0) + 1}{" "}
              并立即生效 （当前内容仍保留在版本历史中，可再次回滚）。是否继续？
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

      {/* 删除确认 */}
      <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除技能</AlertDialogTitle>
            <AlertDialogDescription>
              将删除 {detail?.name} 的全部版本（v1 ~ v{detail?.currentVersion}
              ）、工作区目录，并从运行时卸载。该操作不可恢复。是否继续？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              disabled={deleting}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleting ? "删除中..." : "删除"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* 版本对比 */}
      {detail && (
        <SkillDiffDialog
          open={diffOpen}
          onOpenChange={setDiffOpen}
          name={detail.name}
          versions={versions}
          defaultFrom={diffFrom}
          defaultTo={diffTo}
        />
      )}
    </div>
  );
}
