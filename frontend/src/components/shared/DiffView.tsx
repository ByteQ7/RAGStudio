import { useEffect, useMemo, useState } from "react";
import { diffLines } from "diff";

/**
 * 通用并排 Diff 视图（提示词版本对比与 SKILL 文件对比共用）
 * <p>
 * 基于 jsdiff 的行级差异做并排对齐：removed+added 相邻块水平配对，
 * 行号两侧独立计数、短侧补空行；连续未变更行超过阈值折叠，点击展开。
 */

type OpKind = "same" | "del" | "add";
type RowKind = "same" | "mod" | "del" | "add";

interface Op {
  kind: OpKind;
  lines: string[];
}

interface Cell {
  no: number;
  text: string;
}

interface Row {
  left: Cell | null;
  right: Cell | null;
  kind: RowKind;
}

interface FoldRow {
  kind: "fold";
  foldId: number;
  count: number;
  rows: Row[];
}

type RenderRow = Row | FoldRow;

const FOLD_THRESHOLD = 8;
const FOLD_CONTEXT = 3;

function splitLines(value: string): string[] {
  const normalized = value.replace(/\r\n/g, "\n");
  const lines = normalized.split("\n");
  // diffLines 的 value 以 \n 结尾，split 会多出一个尾部空串
  if (lines.length > 0 && lines[lines.length - 1] === "") {
    lines.pop();
  }
  return lines;
}

function alignRows(oldText: string, newText: string): Row[] {
  const oldLines = splitLines(oldText);
  const newLines = splitLines(newText);
  if (oldLines.length === 0 && newLines.length === 0) {
    return [];
  }
  const ops: Op[] = [];
  // 借用 jsdiff 计算块级差异，再映射到行
  const changes = diffLines(oldText, newText);
  for (const change of changes) {
    const kind: OpKind = change.added ? "add" : change.removed ? "del" : "same";
    ops.push({ kind, lines: splitLines(change.value) });
  }

  const rows: Row[] = [];
  let oldNo = 0;
  let newNo = 0;
  for (let i = 0; i < ops.length; i++) {
    const op = ops[i];
    if (op.kind === "same") {
      for (const line of op.lines) {
        rows.push({
          left: { no: ++oldNo, text: line },
          right: { no: ++newNo, text: line },
          kind: "same"
        });
      }
    } else if (op.kind === "del") {
      const add = ops[i + 1]?.kind === "add" ? ops[++i] : null;
      const k = op.lines.length;
      const m = add ? add.lines.length : 0;
      for (let j = 0; j < Math.max(k, m); j++) {
        const hasLeft = j < k;
        const hasRight = j < m;
        rows.push({
          left: hasLeft ? { no: ++oldNo, text: op.lines[j] } : null,
          right: hasRight ? { no: ++newNo, text: add.lines[j] } : null,
          kind: hasLeft && hasRight ? "mod" : hasLeft ? "del" : "add"
        });
      }
    } else {
      for (const line of op.lines) {
        rows.push({ left: null, right: { no: ++newNo, text: line }, kind: "add" });
      }
    }
  }
  return rows;
}

function foldRows(rows: Row[]): FoldRow[] {
  const folds: FoldRow[] = [];
  let i = 0;
  let foldId = 0;
  while (i < rows.length) {
    if (rows[i].kind !== "same") {
      i++;
      continue;
    }
    let j = i;
    while (j < rows.length && rows[j].kind === "same") {
      j++;
    }
    const runLength = j - i;
    if (i > 0 && j < rows.length && runLength > FOLD_THRESHOLD) {
      const start = i + FOLD_CONTEXT;
      const end = j - FOLD_CONTEXT;
      if (end > start) {
        folds.push({
          kind: "fold",
          foldId: foldId++,
          count: end - start,
          rows: rows.slice(start, end)
        });
      }
    }
    i = j;
  }
  return folds;
}

const KIND_BG: Record<RowKind, string> = {
  same: "",
  mod: "bg-red-50 dark:bg-red-950/40",
  del: "bg-red-50 dark:bg-red-950/40",
  add: "bg-emerald-50 dark:bg-emerald-950/40"
};

const KIND_BG_RIGHT: Record<RowKind, string> = {
  same: "",
  mod: "bg-emerald-50 dark:bg-emerald-950/40",
  del: "bg-[repeating-linear-gradient(45deg,transparent,transparent_6px,hsl(var(--muted))_6px,hsl(var(--muted))_12px)]",
  add: "bg-emerald-50 dark:bg-emerald-950/40"
};

export interface DiffViewProps {
  oldText: string;
  newText: string;
  leftTitle?: string;
  rightTitle?: string;
  maxHeight?: string;
}

export function DiffView({
  oldText,
  newText,
  leftTitle,
  rightTitle,
  maxHeight = "480px"
}: DiffViewProps) {
  const [expandedFolds, setExpandedFolds] = useState<Set<number>>(new Set());

  // 内容变化时重置折叠展开状态（foldId 按新内容重新编号）
  useEffect(() => {
    setExpandedFolds(new Set());
  }, [oldText, newText]);

  const rows = useMemo(() => {
    const raw = alignRows(oldText, newText);
    const folds = foldRows(raw);
    if (folds.length === 0) {
      return raw as RenderRow[];
    }
    const result: RenderRow[] = [];
    let cursor = 0;
    for (const fold of folds) {
      // fold.rows 与 raw 共享行对象引用，用引用比较定位：折叠区间之前的行（含前文上下文）自然保留
      while (cursor < raw.length && raw[cursor] !== fold.rows[0]) {
        result.push(raw[cursor]);
        cursor++;
      }
      result.push(fold);
      cursor += fold.count;
    }
    while (cursor < raw.length) {
      result.push(raw[cursor]);
      cursor++;
    }
    return result;
  }, [oldText, newText]);

  const identical = oldText === newText;

  const toggleFold = (foldId: number) => {
    setExpandedFolds((prev) => {
      const next = new Set(prev);
      if (next.has(foldId)) {
        next.delete(foldId);
      } else {
        next.add(foldId);
      }
      return next;
    });
  };

  return (
    <div
      className="rounded-lg border overflow-hidden"
      style={{ borderColor: "var(--color-border-secondary)" }}
    >
      <div
        className="grid grid-cols-[1fr_1fr] text-[11px] font-medium border-b px-3 py-1.5"
        style={{
          borderColor: "var(--color-border-secondary)",
          background: "var(--color-fill-quaternary)"
        }}
      >
        <span className="truncate" style={{ color: "var(--color-text-secondary)" }}>
          {leftTitle || "旧版本"}
        </span>
        <span
          className="truncate border-l pl-3"
          style={{
            borderColor: "var(--color-border-secondary)",
            color: "var(--color-text-secondary)"
          }}
        >
          {rightTitle || "新版本"}
        </span>
      </div>
      {identical ? (
        <div className="py-10 text-center text-sm text-muted-foreground">两个版本内容完全一致</div>
      ) : (
        <div className="overflow-auto font-mono text-[11px] leading-[1.6]" style={{ maxHeight }}>
          {rows.map((row, index) => {
            if (row.kind === "fold") {
              if (expandedFolds.has(row.foldId)) {
                return (
                  <div key={`fold-${row.foldId}`}>
                    {row.rows.map((r, i) => (
                      <div key={i} className="grid grid-cols-[44px_1fr_44px_1fr]">
                        {renderCells(r, true)}
                      </div>
                    ))}
                  </div>
                );
              }
              return (
                <button
                  key={`fold-${row.foldId}`}
                  type="button"
                  onClick={() => toggleFold(row.foldId)}
                  className="w-full grid grid-cols-[44px_1fr_44px_1fr] text-center py-0.5 hover:bg-[var(--color-fill-quaternary)]"
                  style={{ color: "var(--color-text-tertiary)" }}
                >
                  <span className="col-span-4 text-[10px]">⋯ 展开 {row.count} 行未变更 ⋯</span>
                </button>
              );
            }
            return (
              <div key={index} className="grid grid-cols-[44px_1fr_44px_1fr]">
                {renderCells(row, false)}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function renderCells(row: Row, expanded: boolean) {
  const leftBg = row.kind === "same" ? "" : KIND_BG[row.kind];
  const rightBg = row.kind === "same" ? "" : KIND_BG_RIGHT[row.kind];
  return (
    <>
      <div
        className={`pl-2 pr-1 text-right select-none border-r ${leftBg}`}
        style={{
          borderColor: "var(--color-border-secondary)",
          color: "var(--color-text-tertiary)"
        }}
      >
        {row.left?.no ?? ""}
      </div>
      <div
        className={`px-2 whitespace-pre-wrap break-all border-r ${leftBg}`}
        style={{ borderColor: "var(--color-border-secondary)", color: "var(--color-text)" }}
      >
        {row.left?.text ?? ""}
      </div>
      <div
        className={`pl-2 pr-1 text-right select-none border-r ${rightBg}`}
        style={{
          borderColor: "var(--color-border-secondary)",
          color: "var(--color-text-tertiary)"
        }}
      >
        {row.right?.no ?? ""}
      </div>
      <div
        className={`px-2 whitespace-pre-wrap break-all ${rightBg}`}
        style={{ color: "var(--color-text)" }}
      >
        {row.right?.text ?? ""}
      </div>
    </>
  );
}
