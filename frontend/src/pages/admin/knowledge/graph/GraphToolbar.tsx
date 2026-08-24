import { Camera, Lock, Maximize, Minimize2, RotateCcw, Tag, Unlock, ZoomIn, ZoomOut } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";

interface GraphToolbarProps {
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitView: () => void;
  onExportPng: () => void;
  onToggleFullscreen: () => void;
  layoutLocked: boolean;
  onToggleLayoutLocked: () => void;
  showEdgeLabels: boolean;
  onToggleEdgeLabels: () => void;
}

function ToolbarButton({
  label,
  onClick,
  active,
  children
}: {
  label: string;
  onClick: () => void;
  active?: boolean;
  children: React.ReactNode;
}) {
  return (
    <TooltipProvider delayDuration={100}>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8"
            onClick={onClick}
            style={active ? { color: "hsl(var(--primary))" } : undefined}
          >
            {children}
          </Button>
        </TooltipTrigger>
        <TooltipContent side="bottom">
          <span className="text-xs">{label}</span>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

export function GraphToolbar({
  onZoomIn,
  onZoomOut,
  onFitView,
  onExportPng,
  onToggleFullscreen,
  layoutLocked,
  onToggleLayoutLocked,
  showEdgeLabels,
  onToggleEdgeLabels
}: GraphToolbarProps) {
  const [isFullscreen, setIsFullscreen] = useState(false);

  return (
    <div
      className="absolute right-3 top-3 z-10 flex items-center gap-0.5 rounded-xl border bg-white/90 p-1 shadow-sm backdrop-blur dark:bg-[#161b22]/90"
      style={{ borderColor: "var(--color-border-secondary)" }}
    >
      <ToolbarButton label="放大" onClick={onZoomIn}><ZoomIn className="h-4 w-4" /></ToolbarButton>
      <ToolbarButton label="缩小" onClick={onZoomOut}><ZoomOut className="h-4 w-4" /></ToolbarButton>
      <ToolbarButton label="重置视图" onClick={onFitView}><RotateCcw className="h-4 w-4" /></ToolbarButton>
      <span className="mx-0.5 h-5 w-px" style={{ background: "var(--color-border-secondary)" }} />
      <ToolbarButton
        label={layoutLocked ? "解锁布局（可拖拽节点）" : "锁定布局"}
        onClick={onToggleLayoutLocked}
        active={layoutLocked}
      >
        {layoutLocked ? <Lock className="h-4 w-4" /> : <Unlock className="h-4 w-4" />}
      </ToolbarButton>
      <ToolbarButton label="边标签" onClick={onToggleEdgeLabels} active={showEdgeLabels}>
        <Tag className="h-4 w-4" />
      </ToolbarButton>
      <ToolbarButton label="导出 PNG" onClick={onExportPng}><Camera className="h-4 w-4" /></ToolbarButton>
      <ToolbarButton
        label={isFullscreen ? "退出全屏" : "全屏"}
        onClick={() => {
          onToggleFullscreen();
          setIsFullscreen((v) => !v);
        }}
      >
        {isFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize className="h-4 w-4" />}
      </ToolbarButton>
    </div>
  );
}