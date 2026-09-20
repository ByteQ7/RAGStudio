import { GitBranch, Info, Trash2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

import type { FlowEdge, FlowNode, FlowNodeData } from "./graphModel";
import { NODE_KIND_LABELS } from "./graphModel";

interface PropertyPanelProps {
  node: FlowNode | null;
  edge: FlowEdge | null;
  onNodeDataChange: (id: string, patch: Partial<FlowNodeData>) => void;
  onEdgeLabelChange: (id: string, label: string) => void;
  onDeleteNode: (id: string) => void;
  onDeleteEdge: (id: string) => void;
}

/** 右侧属性面板：编辑选中节点/连线的属性 */
export function PropertyPanel({
  node,
  edge,
  onNodeDataChange,
  onEdgeLabelChange,
  onDeleteNode,
  onDeleteEdge
}: PropertyPanelProps) {
  return (
    <aside className="flex w-72 shrink-0 flex-col border-l border-[var(--color-border-secondary)] bg-[var(--color-bg-container)]">
      <div className="border-b border-[var(--color-border-secondary)] px-3 py-2.5">
        <p className="text-xs font-medium text-[var(--color-text-tertiary)]">属性</p>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        {node ? (
          <NodeForm node={node} onChange={onNodeDataChange} onDelete={onDeleteNode} />
        ) : edge ? (
          <EdgeForm edge={edge} onChange={onEdgeLabelChange} onDelete={onDeleteEdge} />
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
            <Info className="h-6 w-6 text-[var(--color-text-quaternary)]" />
            <p className="text-xs text-[var(--color-text-tertiary)]">
              选中节点或连线后在此编辑
            </p>
          </div>
        )}
      </div>
    </aside>
  );
}

function NodeForm({
  node,
  onChange,
  onDelete
}: {
  node: FlowNode;
  onChange: (id: string, patch: Partial<FlowNodeData>) => void;
  onDelete: (id: string) => void;
}) {
  const kind = node.type ?? "step";
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <span className="inline-flex items-center gap-1.5 rounded-md bg-[var(--color-fill-tertiary)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)]">
          {kind === "condition" ? (
            <GitBranch className="h-3.5 w-3.5 text-amber-600" />
          ) : null}
          {NODE_KIND_LABELS[kind]}
        </span>
        <Button
          variant="ghost"
          size="sm"
          className="h-7 px-2 text-rose-600 hover:text-rose-700"
          onClick={() => onDelete(node.id)}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      </div>

      {kind === "step" ? (
        <>
          <div className="space-y-1.5">
            <Label htmlFor="node-action">动作描述（必填）</Label>
            <Textarea
              id="node-action"
              value={node.data.action ?? ""}
              rows={4}
              placeholder="描述这一步要做什么，例如：查询订单状态与支付信息"
              onChange={(e) => onChange(node.id, { action: e.target.value })}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="node-tool">建议工具（可选）</Label>
            <Input
              id="node-tool"
              value={node.data.tool ?? ""}
              placeholder="如 rag_search、web-search、MCP 工具名"
              onChange={(e) => onChange(node.id, { tool: e.target.value })}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="node-when">执行条件（可选）</Label>
            <Input
              id="node-when"
              value={node.data.when ?? ""}
              placeholder="仅当条件满足时执行，如：物流延误"
              onChange={(e) => onChange(node.id, { when: e.target.value })}
            />
            <p className="text-[11px] leading-relaxed text-[var(--color-text-tertiary)]">
              与连线上游的条件分支叠加生效（同时满足才执行）。
            </p>
          </div>
        </>
      ) : null}

      {kind === "condition" ? (
        <div className="space-y-1.5">
          <Label htmlFor="node-expression">条件表达式（必填）</Label>
          <Textarea
            id="node-expression"
            value={node.data.expression ?? ""}
            rows={3}
            placeholder="如：物流是否延误"
            onChange={(e) => onChange(node.id, { expression: e.target.value })}
          />
          <p className="text-[11px] leading-relaxed text-[var(--color-text-tertiary)]">
            从本节点拉出的每条连线需要填写分支标签（如「是」「否」），Agent 会按标签描述执行。
          </p>
        </div>
      ) : null}

      {kind === "start" ? (
        <p className="rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2 text-xs text-[var(--color-text-secondary)]">
          流程入口，不能有入边；从右侧圆点拉出第一条连线。
        </p>
      ) : null}
      {kind === "end" ? (
        <p className="rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2 text-xs text-[var(--color-text-secondary)]">
          流程出口，不能有出边；所有路径最终应汇聚到这里。
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
  edge: FlowEdge;
  onChange: (id: string, label: string) => void;
  onDelete: (id: string) => void;
}) {
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
        <Label htmlFor="edge-label">分支标签</Label>
        <Input
          id="edge-label"
          value={typeof edge.label === "string" ? edge.label : ""}
          placeholder="如：是 / 否，或具体条件"
          onChange={(e) => onChange(edge.id, e.target.value)}
        />
        <p className="text-[11px] leading-relaxed text-[var(--color-text-tertiary)]">
          从条件节点拉出的连线必须填写标签；普通连线的标签可留空。
        </p>
      </div>
      <div className="rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2 font-mono text-[11px] text-[var(--color-text-tertiary)]">
        {edge.source} → {edge.target}
      </div>
    </div>
  );
}
