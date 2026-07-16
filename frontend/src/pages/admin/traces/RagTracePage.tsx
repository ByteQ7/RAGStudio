import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { Activity, Clock3, Layers, RefreshCw, Search, TrendingUp } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { deleteRagTraceRun, getRagTraceRuns, getRagTraceStats, type PageResult, type RagTraceRun, type RagTraceRunStats } from "@/services/ragTraceService";
import { getErrorMessage } from "@/utils/error";
import { RunsTable } from "@/pages/admin/traces/components/RunsTable";
import { StatCard, type StatCardTone } from "@/pages/admin/traces/components/StatCard";
import {
  PAGE_SIZE,
} from "@/pages/admin/traces/traceUtils";

type DurationMetric = {
  value: string;
  unit: string;
};

const formatDurationMetric = (durationMs: number): DurationMetric => {
  const duration = Number.isFinite(durationMs) && durationMs > 0 ? durationMs : 0;
  if (duration < 1000) {
    return { value: `${Math.round(duration)}`, unit: "ms" };
  }
  if (duration < 60_000) {
    return { value: (duration / 1000).toFixed(2), unit: "s" };
  }
  return { value: (duration / 1000).toFixed(1), unit: "s" };
};

export function RagTracePage() {
  const navigate = useNavigate();
  const runsRequestRef = useRef(0);
  const [traceIdFilter, setTraceIdFilter] = useState("");
  const [queryTraceId, setQueryTraceId] = useState("");
  const [pageNo, setPageNo] = useState(1);
  const [pageData, setPageData] = useState<PageResult<RagTraceRun> | null>(null);
  const [statsData, setStatsData] = useState<RagTraceRunStats | null>(null);
  const [loading, setLoading] = useState(false);

  const runs = pageData?.records || [];

  const loadRuns = async (current = pageNo, nextTraceId = queryTraceId) => {
    const requestId = ++runsRequestRef.current;
    setLoading(true);
    try {
      const [result, stats] = await Promise.all([
        getRagTraceRuns({
          current,
          size: PAGE_SIZE,
          traceId: nextTraceId.trim() || undefined
        }),
        getRagTraceStats({
          traceId: nextTraceId.trim() || undefined
        })
      ]);
      if (runsRequestRef.current !== requestId) return;
      setPageData(result);
      setStatsData(stats);
    } catch (error) {
      if (runsRequestRef.current !== requestId) return;
      toast.error(getErrorMessage(error, "加载链路运行列表失败"));
      console.error(error);
    } finally {
      if (runsRequestRef.current !== requestId) return;
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRuns();
  }, [pageNo, queryTraceId]);

  const handleSearch = () => {
    setPageNo(1);
    setQueryTraceId(traceIdFilter.trim());
  };

  const handleRefresh = () => {
    loadRuns(pageNo, queryTraceId);
  };

  const handleDeleteRun = async (traceId: string) => {
    if (!window.confirm(`确定删除链路 ${traceId}？此操作不可恢复。`)) return;
    try {
      await deleteRagTraceRun(traceId);
      toast.success("链路已删除");
      loadRuns(pageNo, queryTraceId);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

  // 统计信息来自后端全量数据（而非当前页 10 条）
  const traceStats = useMemo(() => {
    const s = statsData;
    return {
      totalRuns: s?.totalCount ?? pageData?.total ?? 0,
      successCount: s?.successCount ?? 0,
      failedCount: s?.failCount ?? 0,
      runningCount: Math.max(0, (s?.totalCount ?? 0) - (s?.successCount ?? 0) - (s?.failCount ?? 0)),
      avgDuration: s?.avgDurationMs ?? 0,
      p95Duration: s?.p95DurationMs ?? 0,
      successRate: s?.successRate ?? 0,
    };
  }, [statsData, pageData?.total]);

  const current = pageData?.current || pageNo;
  const pages = pageData?.pages || 1;
  const total = pageData?.total || 0;
  const avgDurationMetric = formatDurationMetric(traceStats.avgDuration);
  const p95DurationMetric = formatDurationMetric(traceStats.p95Duration);
  const statCards: {
    key: string;
    title: string;
    value: string;
    unit?: string;
    icon: ReactNode;
    tone: StatCardTone;
  }[] = [
    {
      key: "status",
      title: "成功 / 失败 / 运行中",
      value: `${traceStats.successCount} / ${traceStats.failedCount} / ${traceStats.runningCount}`,
      icon: <Activity className="h-4 w-4" />,
      tone: "blue"
    },
    {
      key: "successRate",
      title: "成功率",
      value: `${traceStats.successRate}%`,
      icon: <TrendingUp className="h-4 w-4" />,
      tone: "cyan"
    },
    {
      key: "avg",
      title: "平均耗时",
      value: avgDurationMetric.value,
      unit: avgDurationMetric.unit,
      icon: <Clock3 className="h-4 w-4" />,
      tone: "indigo"
    },
    {
      key: "p95",
      title: "P95 耗时",
      value: p95DurationMetric.value,
      unit: p95DurationMetric.unit,
      icon: <Layers className="h-4 w-4" />,
      tone: "amber"
    }
  ];

  return (
    <div className="admin-page">
      <div>
        <div className="admin-page-header">
          <div>
            <h1 className="admin-page-title">链路追踪</h1>
            <p className="admin-page-subtitle">
              独立列表页聚焦运行检索，点击任意运行记录进入详情页分析慢节点与失败节点
            </p>
          </div>
          <div className="admin-page-actions">
            <Input
              value={traceIdFilter}
              onChange={(event) => setTraceIdFilter(event.target.value)}
              placeholder="搜索 Trace Id"
              className="w-[300px]"
            />
            <Button className="admin-primary-gradient" onClick={handleSearch}>
              <Search className="h-4 w-4 mr-2" />
              查询
            </Button>
            <Button variant="outline" onClick={handleRefresh}>
              <RefreshCw className="h-4 w-4 mr-2" />
              刷新
            </Button>
          </div>
        </div>

        <section className="admin-stat-grid">
          {statCards.map((stat) => (
            <StatCard
              key={stat.key}
              title={stat.title}
              value={stat.value}
              unit={stat.unit}
              icon={stat.icon}
              tone={stat.tone}
            />
          ))}
        </section>

        <RunsTable
          runs={runs}
          loading={loading}
          current={current}
          pages={pages}
          total={total}
          onOpenRun={(traceId) => navigate(`/admin/traces/${encodeURIComponent(traceId)}`)}
          onDeleteRun={handleDeleteRun}
          onPrevPage={() => setPageNo((prev) => Math.max(1, prev - 1))}
          onNextPage={() => setPageNo((prev) => prev + 1)}
        />
      </div>
    </div>
  );
}
