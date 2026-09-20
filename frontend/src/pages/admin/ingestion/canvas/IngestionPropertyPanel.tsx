import * as React from "react";
import { Info, Trash2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

import { PipelineNodeFields, type EmbeddingModelOption } from "../pipeline/PipelineNodeFields";
import {
  CONDITION_FIELDS,
  CONDITION_OPERATORS,
  type PipelineNodeForm
} from "../pipeline/pipelineFormModel";
import type { IngestionFlowEdge, IngestionFlowNode } from "./pipelineGraphModel";

interface IngestionPropertyPanelProps {
  node: IngestionFlowNode | null;
  edge: IngestionFlowEdge | null;
  embeddingModels: EmbeddingModelOption[];
  onNodeFormChange: (id: string, patch: Partial<PipelineNodeForm>) => void;
  onNodeNoteChange: (id: string, note: string) => void;
  onEdgeChange: (id: string, patch: { label?: string; condition?: Record<string, unknown> | null }) => void;
  onDeleteNode: (id: string) => void;
  onDeleteEdge: (id: string) => void;
}

/** 画布右侧属性面板：编辑处理节点配置 / 条件网关备注 / 连线分支条件 */
export function IngestionPropertyPanel({
  node,
  edge,
  embeddingModels,
  onNodeFormChange,
  onNodeNoteChange,
  onEdgeChange,
  onDeleteNode,
  onDeleteEdge
}: IngestionPropertyPanelProps) {
  return (
    <aside className="flex w-[340px] shrink-0 flex-col border-l border-[var(--color-border-secondary)] bg-[var(--color-bg-container)]">
      <div className="border-b border-[var(--color-border-secondary)] px-3 py-2.5">
        <p className="text-xs font-medium text-[var(--color-text-tertiary)]">属性</p>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        {node ? (
          <NodeForm
            node={node}
            embeddingModels={embeddingModels}
            onFormChange={onNodeFormChange}
            onNoteChange={onNodeNoteChange}
            onDelete={onDeleteNode}
          />
        ) : edge ? (
          <EdgeForm edge={edge} onChange={onEdgeChange} onDelete={onDeleteEdge} />
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
            <Info className="h-6 w-6 text-[var(--color-text-quaternary)]" />
            <p className="text-xs text-[var(--color-text-tertiary)]">选中节点或连线后在此编辑</p>
          </div>
        )}
      </div>
    </aside>
  );
}

function NodeForm({
  node,
  embeddingModels,
  onFormChange,
  onNoteChange,
  onDelete
}: {
  node: IngestionFlowNode;
  embeddingModels: EmbeddingModelOption[];
  onFormChange: (id: string, patch: Partial<PipelineNodeForm>) => void;
  onNoteChange: (id: string, note: string) => void;
  onDelete: (id: string) => void;
}) {
  const isProcessor = node.type === "processor";
  const isEditable = node.type === "processor" || node.type === "condition";

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <span className="rounded-md bg-[var(--color-fill-tertiary)] px-2 py-1 font-mono text-[11px] text-[var(--color-text-secondary)]">
          {node.id}
        </span>
        {isEditable ? (
          <Button
            variant="ghost"
            size="sm"
            className="h-7 px-2 text-rose-600 hover:text-rose-700"
            onClick={() => onDelete(node.id)}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        ) : null}
      </div>

      {isProcessor && node.data.form ? (
        <PipelineNodeFields
          node={node.data.form as PipelineNodeForm}
          embeddingModels={embeddingModels}
          onPatch={(patch) => onFormChange(node.id, patch)}
        />
      ) : null}

      {node.type === "condition" ? (
        <div className="space-y-2">
          <Label htmlFor="condition-note">备注（仅展示）</Label>
          <Input
            id="condition-note"
            value={(node.data.note as string) ?? ""}
            placeholder="如：文档类型判断"
            onChange={(e) => onNoteChange(node.id, e.target.value)}
          />
          <p className="text-[11px] leading-relaxed text-[var(--color-text-tertiary)]">
            从本节点拉出的每条连线需要配置分支条件；最多允许一条无条件连线作为兜底分支。
          </p>
        </div>
      ) : null}

      {node.type === "start" ? (
        <p className="rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2 text-xs text-[var(--color-text-secondary)]">
          流水线入口，不能有入边；从右侧圆点拉出第一条连线。
        </p>
      ) : null}
      {node.type === "end" ? (
        <p className="rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2 text-xs text-[var(--color-text-secondary)]">
          流水线出口，不能有出边；所有路径最终应汇聚到这里。
        </p>
      ) : null}
    </div>
  );
}

function EdgeForm({
  edge,
  onChange,
  onDelete
}: {
  edge: IngestionFlowEdge;
  onChange: (id: string, patch: { label?: string; condition?: Record<string, unknown> | null }) => void;
  onDelete: (id: string) => void;
}) {
  const condition = (edge.data?.condition as Record<string, unknown> | null) ?? null;
  const [advanced, setAdvanced] = React.useState(false);
  const [advancedText, setAdvancedText] = React.useState(
    condition ? JSON.stringify(condition, null, 2) : ""
  );

  // 切换选中连线时同步高级文本框
  React.useEffect(() => {
    setAdvancedText(condition ? JSON.stringify(condition, null, 2) : "");
  }, [edge.id, condition]);

  const field = typeof condition?.field === "string" ? condition.field : "";
  const operator =
    typeof condition?.operator === "string"
      ? condition.operator
      : typeof condition?.op === "string"
        ? condition.op
        : "eq";
  const value = condition?.value != null ? String(condition.value) : "";

  const setBuilder = (next: { field: string; operator: string; value: string }) => {
    if (!next.field && !next.value) {
      onChange(edge.id, { condition: null });
      return;
    }
    onChange(edge.id, {
      condition: { field: next.field, operator: next.operator, value: next.value }
    });
  };

  const applyAdvanced = (text: string) => {
    setAdvancedText(text);
    const trimmed = text.trim();
    if (!trimmed) {
      onChange(edge.id, { condition: null });
      return;
    }
    try {
      const parsed: unknown = JSON.parse(trimmed);
      if (parsed && typeof parsed === "object") {
        onChange(edge.id, { condition: parsed as Record<string, unknown> });
      }
    } catch {
      /* 输入过程中不合法，保持原值 */
    }
  };

  /** 无条件连线 = 兜底分支 */
  const isFallback = condition === null;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-[var(--color-text-secondary)]">连线</span>
        <Button
          variant="ghost"
          size="sm"
          className="h-7 px-2 text-rose-600 hover:text-rose-700"
          onClick={() => onDelete(edge.id)}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="edge-label">分支标签（展示用）</Label>
        <Input
          id="edge-label"
          value={typeof edge.label === "string" ? edge.label : ""}
          placeholder="如：是 / 否 / PDF"
          onChange={(e) => onChange(edge.id, { label: e.target.value })}
        />
      </div>

      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <Label>分支条件</Label>
          <label className="flex cursor-pointer items-center gap-1.5 text-xs text-muted-foreground">
            <Checkbox checked={advanced} onCheckedChange={(v) => setAdvanced(!!v)} />
            高级
          </label>
        </div>

        {isFallback && !advanced ? (
          <p className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700 dark:bg-amber-950/40 dark:text-amber-300">
            当前连线无条件：作为兜底分支（所有条件不命中时走这里）。同一节点最多一条兜底连线。
          </p>
        ) : null}

        {!advanced ? (
          <div className="space-y-2">
            <Select
              value={field || "none"}
              onValueChange={(v) =>
                setBuilder({ field: v === "none" ? "" : v, operator, value })
              }
            >
              <SelectTrigger>
                <SelectValue placeholder="选择字段" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="none">无条件（兜底）</SelectItem>
                {CONDITION_FIELDS.map((f) => (
                  <SelectItem key={f.value} value={f.value}>
                    {f.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <div className="grid grid-cols-2 gap-2">
              <Select
                value={operator}
                onValueChange={(v) => setBuilder({ field, operator: v, value })}
                disabled={!field}
              >
                <SelectTrigger>
                  <SelectValue placeholder="运算符" />
                </SelectTrigger>
                <SelectContent>
                  {CONDITION_OPERATORS.map((op) => (
                    <SelectItem key={op.value} value={op.value}>
                      {op.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Input
                value={value}
                placeholder="值，如 file"
                disabled={!field}
                onChange={(e) => setBuilder({ field, operator, value: e.target.value })}
              />
            </div>
          </div>
        ) : (
          <Textarea
            rows={5}
            value={advancedText}
            onChange={(e) => applyAdvanced(e.target.value)}
            placeholder='JSON，如 {"field":"mimeType","operator":"eq","value":"application/pdf"}'
          />
        )}
        <p className="text-[11px] leading-relaxed text-[var(--color-text-tertiary)]">
          条件对摄入上下文求值；字段说明：来源类型 file/url/s3/feishu，MIME 如 application/pdf。
        </p>
      </div>

      <div className="rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2 font-mono text-[11px] text-[var(--color-text-tertiary)]">
        {edge.source} → {edge.target}
      </div>
    </div>
  );
}
