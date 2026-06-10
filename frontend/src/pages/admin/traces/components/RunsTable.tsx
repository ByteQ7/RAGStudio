import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ChevronRight, Eye } from "lucide-react";
import type { RagTraceRun } from "@/services/ragTraceService";
import {
  formatDateTime,
  formatDuration,
  statusBadgeVariant,
  statusLabel
} from "@/pages/admin/traces/traceUtils";

interface RunsTableProps {
  runs: RagTraceRun[];
  loading: boolean;
  current: number;
  pages: number;
  total: number;
  onOpenRun: (traceId: string) => void;
  onPrevPage: () => void;
  onNextPage: () => void;
}

export function RunsTable({
  runs,
  loading,
  current,
  pages,
  total,
  onOpenRun,
  onPrevPage,
  onNextPage
}: RunsTableProps) {
  return (
    <Card>
      <CardContent className="pt-6">
        {loading ? (
          <div className="py-10 text-center text-muted-foreground">加载中...</div>
        ) : runs.length === 0 ? (
          <div className="py-10 text-center text-muted-foreground">暂无链路数据</div>
        ) : (
          <div className="overflow-x-auto">
            <Table className="min-w-[980px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[160px]">Trace Name</TableHead>
                  <TableHead className="w-[200px]">Trace Id</TableHead>
                  <TableHead className="w-[160px]">会话ID / TaskID</TableHead>
                  <TableHead className="w-[90px]">用户名</TableHead>
                  <TableHead className="w-[70px]">耗时</TableHead>
                  <TableHead className="w-[90px]">状态</TableHead>
                  <TableHead className="w-[160px]">执行时间</TableHead>
                  <TableHead className="w-[130px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {runs.map((run) => (
                  <TableRow key={run.traceId}>
                    <TableCell>
                      <p className="line-clamp-1 text-sm font-medium" title={run.traceName || "-"}>
                        {run.traceName || "-"}
                      </p>
                    </TableCell>
                    <TableCell>
                      <span className="font-mono text-xs" title={run.traceId}>
                        {run.traceId}
                      </span>
                    </TableCell>
                    <TableCell>
                      <p className="text-xs text-muted-foreground" title={`会话ID: ${run.conversationId || "-"}`}>
                        {run.conversationId || "-"}
                      </p>
                      <p className="text-xs text-gray-400" title={`TaskID: ${run.taskId || "-"}`}>
                        {run.taskId || "-"}
                      </p>
                    </TableCell>
                    <TableCell>
                      <span className="text-sm" title={run.userName || run.username || run.userId || "-"}>
                        {run.userName || run.username || run.userId || "-"}
                      </span>
                    </TableCell>
                    <TableCell className="font-mono text-sm tabular-nums">
                      {formatDuration(run.durationMs ?? undefined)}
                    </TableCell>
                    <TableCell>
                      <Badge variant={statusBadgeVariant(run.status)} className="text-xs">
                        {statusLabel(run.status)}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {formatDateTime(run.startTime ?? undefined)}
                    </TableCell>
                    <TableCell>
                      <Button
                        size="sm"
                        variant="outline"
                        className="gap-1.5"
                        onClick={() => onOpenRun(run.traceId)}
                      >
                        <Eye className="h-3.5 w-3.5" />
                        查看链路
                        <ChevronRight className="h-3.5 w-3.5" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <div className="flex items-center justify-between pt-4">
          <span className="text-xs text-muted-foreground">
            第 {current} / {pages} 页，共 {total.toLocaleString("zh-CN")} 条
          </span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={current <= 1 || loading}
              onClick={onPrevPage}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={current >= pages || loading}
              onClick={onNextPage}
            >
              下一页
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
