import { Card, CardContent } from "@/components/ui/card";
import { RefreshCw, Search } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { STATUS_OPTIONS, type TraceFilters, type TraceStatus } from "@/pages/admin/traces/traceUtils";

interface FilterBarProps {
  filters: TraceFilters;
  onFiltersChange: (next: Partial<TraceFilters>) => void;
  onSearch: () => void;
  onRefresh: () => void;
  onReset: () => void;
}

export function FilterBar({ filters, onFiltersChange, onSearch, onRefresh, onReset }: FilterBarProps) {
  return (
    <Card>
      <CardContent className="py-4">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex-1 min-w-[180px]">
            <Input
              value={filters.traceId}
              onChange={(event) => onFiltersChange({ traceId: event.target.value })}
              placeholder="按 Trace Id 过滤"
            />
          </div>
          <div className="flex-1 min-w-[180px]">
            <Input
              value={filters.conversationId}
              onChange={(event) => onFiltersChange({ conversationId: event.target.value })}
              placeholder="按会话 ID 过滤"
            />
          </div>
          <div className="flex-1 min-w-[180px]">
            <Input
              value={filters.taskId}
              onChange={(event) => onFiltersChange({ taskId: event.target.value })}
              placeholder="按 Task ID 过滤"
            />
          </div>
          <div className="w-[150px]">
            <Select
              value={filters.status || "__all__"}
              onValueChange={(value) => {
                const nextStatus = value === "__all__" ? "" : (value as TraceStatus);
                onFiltersChange({ status: nextStatus });
              }}
            >
              <SelectTrigger>
                <SelectValue placeholder="全部状态" />
              </SelectTrigger>
              <SelectContent>
                {STATUS_OPTIONS.map((option) => (
                  <SelectItem key={option.value || "__all__"} value={option.value || "__all__"}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={onReset}>
              重置
            </Button>
            <Button variant="outline" onClick={onRefresh}>
              <RefreshCw className="h-4 w-4 mr-1.5" />
              刷新
            </Button>
            <Button onClick={onSearch}>
              <Search className="h-4 w-4 mr-1.5" />
              查询
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
