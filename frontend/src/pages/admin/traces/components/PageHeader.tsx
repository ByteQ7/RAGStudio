import type { ReactNode } from "react";

import { Card, CardContent } from "@/components/ui/card";

export type TraceHeaderKpi = {
  key: string;
  icon: ReactNode;
  label: string;
  value: string;
};

interface PageHeaderProps {
  tag: string;
  title: string;
  description: string;
  kpis?: TraceHeaderKpi[];
  actions?: ReactNode;
  meta?: ReactNode;
}

export function PageHeader({ tag, title, description, kpis = [], actions, meta }: PageHeaderProps) {
  return (
    <Card>
      <CardContent className="py-6">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
          <div className="space-y-1">
            <p className="text-xs font-medium text-muted-foreground">{tag}</p>
            <h1 className="text-2xl font-semibold text-gray-900">{title}</h1>
            <p className="text-sm text-gray-500">{description}</p>
            {meta && <div className="pt-1">{meta}</div>}
          </div>
          {actions ? (
            <div className="flex shrink-0 items-center gap-2">{actions}</div>
          ) : kpis.length > 0 ? (
            <div className="flex flex-wrap gap-3">
              {kpis.map((kpi) => (
                <div
                  key={kpi.key}
                  className="flex items-center gap-2 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2"
                >
                  <span className="text-muted-foreground">{kpi.icon}</span>
                  <div>
                    <p className="text-xs text-gray-500">{kpi.label}</p>
                    <p className="text-sm font-semibold text-gray-900">{kpi.value}</p>
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}
