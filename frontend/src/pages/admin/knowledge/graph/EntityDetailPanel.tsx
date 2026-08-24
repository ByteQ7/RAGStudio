import { ArrowRight, Crosshair, ExternalLink, Loader2, X } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { GraphEntityDetail, GraphSubgraph } from "@/services/graphService";
import { getGraphEntity } from "@/services/graphService";
import { getErrorMessage } from "@/utils/error";
import { typeColor } from "./graphVisual";

interface EntityRelation {
  entityId: string;
  entityName: string;
  predicate: string;
  direction: "out" | "in";
}

/** 从当前子图数据中筛选该实体的关联关系（与视图一致，无需额外后端接口） */
function collectRelations(subgraph: GraphSubgraph | null, entityId: string): EntityRelation[] {
  if (!subgraph) {
    return [];
  }
  const nameById = new Map(subgraph.nodes.map((n) => [n.id, n.name]));
  const relations: EntityRelation[] = [];
  for (const link of subgraph.links) {
    if (link.source === entityId) {
      relations.push({
        entityId: link.target,
        entityName: nameById.get(link.target) ?? link.target,
        predicate: link.predicate,
        direction: "out"
      });
    } else if (link.target === entityId) {
      relations.push({
        entityId: link.source,
        entityName: nameById.get(link.source) ?? link.source,
        predicate: link.predicate,
        direction: "in"
      });
    }
  }
  return relations;
}

interface EntityDetailPanelProps {
  entityId: string | null;
  subgraph: GraphSubgraph | null;
  onClose: () => void;
  onFocusExpand: (entityId: string) => void;
  onLocate: (entityId: string) => void;
  onNavigateToEntities: (keyword: string) => void;
}

export function EntityDetailPanel({
  entityId,
  subgraph,
  onClose,
  onFocusExpand,
  onLocate,
  onNavigateToEntities
}: EntityDetailPanelProps) {
  const [detail, setDetail] = useState<GraphEntityDetail | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!entityId) {
      setDetail(null);
      return;
    }
    setLoading(true);
    getGraphEntity(entityId)
      .then(setDetail)
      .catch((err) => toast.error(getErrorMessage(err, "加载实体详情失败")))
      .finally(() => setLoading(false));
  }, [entityId]);

  if (!entityId) {
    return null;
  }

  const relations = collectRelations(subgraph, entityId);

  return (
    <div className="w-[320px] shrink-0">
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <CardTitle className="truncate text-base">{detail?.displayName ?? "实体详情"}</CardTitle>
              {detail && (
                <CardDescription className="mt-1 flex items-center gap-1.5">
                  <Badge variant="outline" style={{ color: typeColor(detail.entityType) }}>
                    {detail.entityType}
                  </Badge>
                  <span className="text-[10px]">关系 {detail.relationCount ?? 0}</span>
                </CardDescription>
              )}
            </div>
            <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onClose}>
              <X className="h-4 w-4" />
            </Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-3 pt-0">
          {loading && (
            <div className="flex items-center gap-2 py-4 text-xs" style={{ color: "var(--color-text-tertiary)" }}>
              <Loader2 className="h-3.5 w-3.5 animate-spin" />加载中...
            </div>
          )}
          {!loading && detail && (
            <>
              {detail.description && (
                <p className="text-xs leading-relaxed" style={{ color: "var(--color-text-secondary)" }}>
                  {detail.description}
                </p>
              )}
              {detail.aliases && detail.aliases.length > 0 && (
                <div className="flex flex-wrap gap-1">
                  {detail.aliases.map((alias) => (
                    <Badge key={alias} variant="secondary" className="text-[10px]">{alias}</Badge>
                  ))}
                </div>
              )}
              <div className="flex gap-2">
                <Button size="sm" className="flex-1" onClick={() => onFocusExpand(entityId)}>
                  <Crosshair className="mr-1.5 h-3.5 w-3.5" />聚焦展开
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => onNavigateToEntities(detail.displayName)}
                >
                  <ExternalLink className="mr-1.5 h-3.5 w-3.5" />实体管理
                </Button>
              </div>
            </>
          )}

          <div>
            <p className="mb-1.5 text-[11px] font-medium" style={{ color: "var(--color-text-tertiary)" }}>
              当前视图内关系（{relations.length}）
            </p>
            {relations.length === 0 && (
              <p className="text-xs" style={{ color: "var(--color-text-tertiary)" }}>当前子图中无关联关系</p>
            )}
            <div className="max-h-[220px] space-y-1 overflow-y-auto pr-1">
              {relations.map((rel, i) => (
                <button
                  key={i}
                  type="button"
                  onClick={() => onLocate(rel.entityId)}
                  className="flex w-full items-center gap-1.5 rounded-lg border px-2 py-1.5 text-left text-xs transition-colors hover:bg-[var(--color-fill-quaternary)]"
                  style={{ borderColor: "var(--color-border-secondary)" }}
                >
                  {rel.direction === "out" ? (
                    <ArrowRight className="h-3 w-3 shrink-0" style={{ color: "var(--color-text-tertiary)" }} />
                  ) : (
                    <ArrowRight className="h-3 w-3 shrink-0 rotate-180" style={{ color: "var(--color-text-tertiary)" }} />
                  )}
                  <span className="flex-1 truncate" style={{ color: "var(--color-text)" }}>{rel.entityName}</span>
                  <span className="max-w-[120px] truncate text-[10px]" style={{ color: "var(--color-text-tertiary)" }}>
                    {rel.predicate}
                  </span>
                </button>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}