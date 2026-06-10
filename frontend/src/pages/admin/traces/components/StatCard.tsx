import type { ReactNode } from "react";

export type StatCardTone = "cyan" | "blue" | "indigo" | "amber";

interface StatCardProps {
  title: string;
  value: string;
  unit?: string;
  icon: ReactNode;
  tone: StatCardTone;
}

const TONE_STYLES: Record<StatCardTone, string> = {
  cyan: "bg-cyan-50 text-cyan-600",
  blue: "bg-blue-50 text-blue-600",
  indigo: "bg-indigo-50 text-indigo-600",
  amber: "bg-amber-50 text-amber-600"
};

export function StatCard({ title, value, unit, icon, tone }: StatCardProps) {
  return (
    <article className="admin-stat-card">
      <div className="flex items-center gap-3">
        <div className={`flex h-10 w-10 items-center justify-center rounded-full ${TONE_STYLES[tone]}`}>
          {icon}
        </div>
        <div>
          <p className="admin-stat-label">{title}</p>
          <div className="flex items-baseline gap-1">
            <p className="admin-stat-value">{value}</p>
            {unit && <span className="text-xs text-gray-400">{unit}</span>}
          </div>
        </div>
      </div>
    </article>
  );
}
