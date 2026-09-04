import * as React from "react";
import { Moon, Sun } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useThemeStore } from "@/stores/themeStore";
import { cn } from "@/lib/utils";

interface ThemeToggleProps {
  className?: string;
}

/**
 * 亮暗主题切换按钮。
 *
 * 基于 shadcn/ui Button（ghost variant）与 lucide-react 的 Sun/Moon 图标，
 * 采用双图标交叉淡入的过渡动画，状态由 themeStore（zustand）驱动并持久化。
 */
export function ThemeToggle({ className }: ThemeToggleProps) {
  const theme = useThemeStore((state) => state.theme);
  const toggleTheme = useThemeStore((state) => state.toggleTheme);
  const isDark = theme === "dark";

  const handleClick = (event: React.MouseEvent<HTMLButtonElement>) => {
    // 以点击点为圆心，从左下角按钮处向全屏扩散
    toggleTheme({ x: event.clientX, y: event.clientY });
  };

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      className={cn("relative h-8 w-8 shrink-0", className)}
      onClick={handleClick}
      title={isDark ? "切换到亮色模式" : "切换到暗色模式"}
      aria-label={isDark ? "切换到亮色模式" : "切换到暗色模式"}
    >
      <Sun
        className={cn(
          "absolute h-4 w-4 transition-all duration-200",
          isDark ? "scale-0 -rotate-90 opacity-0" : "scale-100 rotate-0 opacity-100"
        )}
        style={{ color: "var(--color-text-secondary)" }}
      />
      <Moon
        className={cn(
          "absolute h-4 w-4 transition-all duration-200",
          isDark ? "scale-100 rotate-0 opacity-100" : "scale-0 rotate-90 opacity-0"
        )}
        style={{ color: "var(--color-text-secondary)" }}
      />
    </Button>
  );
}
