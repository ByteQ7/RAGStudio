import { useEffect, useMemo, useState } from "react";
import { File, FileCode, FileJson, FileText, Loader2 } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { DiffView } from "@/components/shared/DiffView";
import {
  getSkillDiff,
  getSkillVersionFile,
  listSkillVersions,
  type SkillDiffFile,
  type SkillDiffResult,
  type SkillVersionInfo
} from "@/services/skillService";
import { getErrorMessage } from "@/utils/error";

const MAX_DIFF_BYTES = 1024 * 1024;

const STATUS_META: Record<string, { label: string; dot: string; text: string }> = {
  added: { label: "新增", dot: "bg-emerald-500", text: "text-emerald-600 dark:text-emerald-400" },
  deleted: { label: "删除", dot: "bg-red-500", text: "text-red-600 dark:text-red-400" },
  modified: { label: "修改", dot: "bg-amber-500", text: "text-amber-600 dark:text-amber-400" },
  unchanged: { label: "未变更", dot: "bg-slate-400", text: "text-muted-foreground" }
};

function formatSize(size: number | null): string {
  if (size === null) return "-";
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

export interface SkillDiffDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  name: string;
  versions: SkillVersionInfo[];
  defaultFrom: number;
  defaultTo: number;
}

/** SKILL 版本对比：顶部版本选择 + 变更摘要，左侧文件树（按状态着色），右侧单文件并排 diff */
export function SkillDiffDialog({
  open,
  onOpenChange,
  name,
  versions,
  defaultFrom,
  defaultTo
}: SkillDiffDialogProps) {
  const [from, setFrom] = useState(defaultFrom);
  const [to, setTo] = useState(defaultTo);
  const [diff, setDiff] = useState<SkillDiffResult | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffError, setDiffError] = useState(false);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [filePair, setFilePair] = useState<{
    oldText: string;
    newText: string;
    info: string | null;
  } | null>(null);
  const [fileLoading, setFileLoading] = useState(false);

  const versionNumbers = useMemo(
    () => versions.map((v) => v.version).sort((a, b) => a - b),
    [versions]
  );

  useEffect(() => {
    if (!open) return;
    setFrom(defaultFrom);
    setTo(defaultTo);
  }, [open, defaultFrom, defaultTo]);

  useEffect(() => {
    if (!open) return;
    // 两侧选了同一版本：清空旧结果，让"请选择两个不同的版本"提示可见
    if (from === to) {
      setDiff(null);
      setDiffError(false);
      setDiffLoading(false);
      setSelectedPath(null);
      setFilePair(null);
      return;
    }
    let active = true;
    setDiffLoading(true);
    setDiffError(false);
    // 先清空上一组版本的树与文件内容，避免加载失败时残留旧数据
    setDiff(null);
    setSelectedPath(null);
    setFilePair(null);
    getSkillDiff(name, from, to)
      .then((result) => {
        if (!active) return;
        setDiff(result);
        const firstComparable = result.files.find(
          (f) =>
            f.status !== "unchanged" &&
            !f.isBinary &&
            (f.newSize ?? 0) <= MAX_DIFF_BYTES &&
            (f.oldSize ?? 0) <= MAX_DIFF_BYTES
        );
        if (firstComparable) {
          setSelectedPath(firstComparable.path);
        }
      })
      .catch((error) => {
        if (active) {
          setDiffError(true);
          toast.error(getErrorMessage(error, "加载版本对比失败"));
        }
      })
      .finally(() => {
        if (active) setDiffLoading(false);
      });
    return () => {
      active = false;
    };
  }, [open, name, from, to]);

  useEffect(() => {
    if (!open || !diff || !selectedPath || !from || !to) return;
    const meta = diff.files.find((f) => f.path === selectedPath);
    if (!meta || meta.status === "unchanged") {
      setFilePair(null);
      return;
    }
    let active = true;
    setFileLoading(true);
    (async () => {
      try {
        let oldText = "";
        let newText = "";
        if (meta.status === "added") {
          newText = (await getSkillVersionFile(name, to, selectedPath)).content;
        } else if (meta.status === "deleted") {
          oldText = (await getSkillVersionFile(name, from, selectedPath)).content;
        } else {
          [oldText, newText] = await Promise.all([
            getSkillVersionFile(name, from, selectedPath).then((c) => c.content),
            getSkillVersionFile(name, to, selectedPath).then((c) => c.content)
          ]);
        }
        if (!active) return;
        setFilePair({ oldText, newText, info: null });
      } catch (error) {
        if (active) {
          setFilePair({
            oldText: "",
            newText: "",
            info: getErrorMessage(error, "文件内容加载失败")
          });
        }
      } finally {
        if (active) setFileLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [open, diff, selectedPath, name, from, to]);

  const selectedMeta = diff?.files.find((f) => f.path === selectedPath) ?? null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[1100px]">
        <DialogHeader>
          <DialogTitle>版本对比：{name}</DialogTitle>
          <DialogDescription>
            左侧文件树点击文本文件查看行级差异；二进制/超大文件仅显示元信息
          </DialogDescription>
        </DialogHeader>

        <div className="flex items-center gap-2 flex-wrap">
          <Select value={String(from)} onValueChange={(v) => setFrom(Number(v))}>
            <SelectTrigger className="w-[130px] h-8 text-xs">
              <SelectValue placeholder="旧版本" />
            </SelectTrigger>
            <SelectContent>
              {versionNumbers.map((v) => (
                <SelectItem key={v} value={String(v)}>
                  v{v}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <span className="text-xs text-muted-foreground">→</span>
          <Select value={String(to)} onValueChange={(v) => setTo(Number(v))}>
            <SelectTrigger className="w-[130px] h-8 text-xs">
              <SelectValue placeholder="新版本" />
            </SelectTrigger>
            <SelectContent>
              {versionNumbers.map((v) => (
                <SelectItem key={v} value={String(v)}>
                  v{v}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {diff && (
            <div className="flex items-center gap-2 text-[11px] ml-1">
              <span className="text-emerald-600 dark:text-emerald-400">+{diff.added} 新增</span>
              <span className="text-red-600 dark:text-red-400">−{diff.deleted} 删除</span>
              <span className="text-amber-600 dark:text-amber-400">~{diff.modified} 修改</span>
              <span className="text-muted-foreground">{diff.unchanged} 未变更</span>
            </div>
          )}
        </div>

        {diff && diff.manifestChanges.length > 0 && (
          <div
            className="rounded-lg border px-3 py-2 text-[11px] flex items-center gap-2 flex-wrap"
            style={{
              borderColor: "var(--color-border-secondary)",
              background: "var(--color-fill-quaternary)"
            }}
          >
            <span className="text-muted-foreground shrink-0">元信息：</span>
            {diff.manifestChanges.map((change) => (
              <Badge key={change} variant="outline" className="text-[10px] font-normal">
                {change}
              </Badge>
            ))}
          </div>
        )}

        {diffLoading ? (
          <div className="py-16 text-center text-sm text-muted-foreground flex items-center justify-center gap-2">
            <Loader2 className="w-4 h-4 animate-spin" /> 计算差异中...
          </div>
        ) : diffError ? (
          <div className="py-16 text-center text-sm text-muted-foreground">
            版本对比加载失败，请调整版本后重试
          </div>
        ) : diff ? (
          <div className="flex gap-3" style={{ height: "460px" }}>
            <div
              className="w-[280px] shrink-0 rounded-lg border overflow-y-auto"
              style={{ borderColor: "var(--color-border-secondary)" }}
            >
              {diff.files.map((file) => {
                const meta = STATUS_META[file.status];
                const disabled =
                  file.status === "unchanged" ||
                  file.isBinary ||
                  (file.oldSize ?? 0) > MAX_DIFF_BYTES ||
                  (file.newSize ?? 0) > MAX_DIFF_BYTES;
                return (
                  <button
                    key={file.path}
                    type="button"
                    disabled={disabled}
                    onClick={() => setSelectedPath(file.path)}
                    className={`w-full text-left px-2.5 py-1.5 text-[11px] font-mono flex items-center gap-1.5 border-b transition-colors ${
                      selectedPath === file.path ? "bg-[var(--color-fill-quaternary)]" : ""
                    } ${disabled ? "opacity-50 cursor-default" : "hover:bg-[var(--color-fill-quaternary)]"}`}
                    style={{ borderColor: "var(--color-border-secondary)" }}
                  >
                    <span className={`h-1.5 w-1.5 rounded-full shrink-0 ${meta.dot}`} />
                    {fileIcon(file.path)}
                    <span className="truncate" style={{ color: "var(--color-text)" }}>
                      {file.path}
                    </span>
                    <span className={`ml-auto shrink-0 text-[10px] ${meta.text}`}>
                      {meta.label}
                    </span>
                  </button>
                );
              })}
            </div>
            <div className="flex-1 min-w-0 overflow-y-auto">
              {fileLoading ? (
                <div className="py-16 text-center text-sm text-muted-foreground flex items-center justify-center gap-2">
                  <Loader2 className="w-4 h-4 animate-spin" /> 加载文件内容...
                </div>
              ) : selectedMeta && selectedMeta.isBinary ? (
                <div className="py-16 text-center text-sm text-muted-foreground">
                  二进制文件，不展示内容 diff
                  <div className="mt-2 text-xs">
                    {selectedMeta.path}：{formatSize(selectedMeta.oldSize)} →{" "}
                    {formatSize(selectedMeta.newSize)}，内容已变更
                  </div>
                </div>
              ) : selectedMeta &&
                ((selectedMeta.oldSize ?? 0) > MAX_DIFF_BYTES ||
                  (selectedMeta.newSize ?? 0) > MAX_DIFF_BYTES) ? (
                <div className="py-16 text-center text-sm text-muted-foreground">
                  文件超过 1MB，不展示行级 diff
                  <div className="mt-2 text-xs">
                    {selectedMeta.path}：{formatSize(selectedMeta.oldSize)} →{" "}
                    {formatSize(selectedMeta.newSize)}
                  </div>
                </div>
              ) : filePair ? (
                <DiffView
                  oldText={filePair.oldText}
                  newText={filePair.newText}
                  leftTitle={`v${from} · ${selectedPath}`}
                  rightTitle={`v${to} · ${selectedPath}`}
                  maxHeight="none"
                />
              ) : selectedPath ? (
                <div className="py-16 text-center text-sm text-muted-foreground">
                  该文件无可对比的内容差异
                </div>
              ) : (
                <div className="py-16 text-center text-sm text-muted-foreground">
                  从左侧选择文件查看差异
                </div>
              )}
            </div>
          </div>
        ) : from === to ? (
          <div className="py-16 text-center text-sm text-muted-foreground">
            请选择两个不同的版本
          </div>
        ) : null}

        <div className="flex justify-end">
          <Button variant="outline" size="sm" onClick={() => onOpenChange(false)}>
            关闭
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
