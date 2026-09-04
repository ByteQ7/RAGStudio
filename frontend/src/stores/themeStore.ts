import { create } from "zustand";

import { storage } from "@/utils/storage";

export type ThemeMode = "light" | "dark";

/** 切换动画的圆心（通常为切换按钮的点击坐标） */
export interface ThemeToggleOrigin {
  x: number;
  y: number;
}

interface ThemeState {
  theme: ThemeMode;
  setTheme: (theme: ThemeMode) => void;
  toggleTheme: (origin?: ThemeToggleOrigin) => void;
  initialize: () => void;
}

function applyTheme(theme: ThemeMode) {
  document.documentElement.classList.toggle("dark", theme === "dark");
}

function getSystemTheme(): ThemeMode {
  try {
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  } catch {
    return "light";
  }
}

function supportsViewTransition(): boolean {
  return typeof document.startViewTransition === "function";
}

function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

/**
 * 以 origin 为圆心的圆形扩散过渡切换主题（View Transitions API）。
 * 旧主题截图作为底层，新主题通过 clip-path: circle() 从圆心扩散铺满全屏。
 * 不支持的浏览器或用户偏好减弱动效时回退为直接切换。
 */
function startThemeViewTransition(origin: ThemeToggleOrigin, mutate: () => void) {
  const { clientWidth: width, clientHeight: height } = document.documentElement;
  const endRadius = Math.hypot(
    Math.max(origin.x, width - origin.x),
    Math.max(origin.y, height - origin.y)
  );

  const transition = document.startViewTransition(mutate);

  transition.ready
    .then(() => {
      document.documentElement.animate(
        {
          clipPath: [
            `circle(0px at ${origin.x}px ${origin.y}px)`,
            `circle(${endRadius}px at ${origin.x}px ${origin.y}px)`
          ]
        },
        {
          duration: 500,
          easing: "ease-in-out",
          pseudoElement: "::view-transition-new(root)"
        }
      );
    })
    .catch(() => {
      // 过渡被跳过（如连续快速点击、页面隐藏）时静默忽略
    });
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  theme: "light",
  setTheme: (theme) => {
    storage.setTheme(theme);
    applyTheme(theme);
    set({ theme });
  },
  toggleTheme: (origin) => {
    const next: ThemeMode = get().theme === "light" ? "dark" : "light";
    const mutate = () => {
      get().setTheme(next);
    };

    // 回退路径：无 View Transitions 支持或用户偏好减弱动效时直接切换
    if (!supportsViewTransition() || prefersReducedMotion()) {
      mutate();
      return;
    }

    const clickPoint = origin ?? { x: window.innerWidth - 44, y: window.innerHeight - 44 };
    startThemeViewTransition(clickPoint, mutate);
  },
  initialize: () => {
    const stored = storage.getTheme();
    // 用户已手动选择过主题则沿用；首次访问跟随系统偏好
    const theme: ThemeMode = stored === "dark" || stored === "light" ? stored : getSystemTheme();
    applyTheme(theme);
    set({ theme });
  }
}));
