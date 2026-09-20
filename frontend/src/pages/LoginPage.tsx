import * as React from "react";
import { useNavigate } from "react-router-dom";
import {
  ArrowRight,
  BookOpenCheck,
  Eye,
  EyeOff,
  Lock,
  Loader2,
  MessagesSquare,
  Sparkles,
  User
} from "lucide-react";

import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";
import { ThemeToggle } from "@/components/common/ThemeToggle";
import { useAuthStore } from "@/stores/authStore";

const FEATURES = [
  {
    icon: BookOpenCheck,
    title: "企业知识库",
    desc: "多格式文档解析、智能切片与向量索引"
  },
  {
    icon: MessagesSquare,
    title: "智能问答",
    desc: "混合检索 + 重排，让回答有据可依"
  },
  {
    icon: Sparkles,
    title: "多模型协同",
    desc: "模型路由与故障转移，稳定可靠"
  }
];

export function LoginPage() {
  const navigate = useNavigate();
  const { login, isAuthenticated, isLoading } = useAuthStore();
  const [username, setUsername] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [showPassword, setShowPassword] = React.useState(false);

  React.useEffect(() => {
    if (isAuthenticated) {
      navigate("/chat", { replace: true });
    }
  }, [isAuthenticated, navigate]);

  const canSubmit = Boolean(username.trim() && password.trim()) && !isLoading;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    try {
      await login(username.trim(), password);
      navigate("/chat", { replace: true });
    } catch {
      // toast handled by authStore
    }
  };

  const inputClass =
    "h-11 w-full rounded-xl border border-border bg-transparent pl-11 pr-4 text-sm text-[var(--color-text)] outline-none transition-all duration-200 placeholder:text-[var(--color-text-quaternary)] focus:border-[hsl(var(--primary))] focus:shadow-[0_0_0_4px_hsl(var(--primary)/0.10)]";

  return (
    <div className="relative flex h-screen overflow-y-auto bg-[var(--color-bg-layout)]">
      {/* 背景光晕 */}
      <div
        className="pointer-events-none fixed inset-0"
        style={{
          background:
            "radial-gradient(900px circle at 78% 8%, hsl(var(--primary) / 0.10), transparent 60%), radial-gradient(700px circle at 62% 96%, hsl(var(--primary) / 0.07), transparent 60%)"
        }}
      />

      <ThemeToggle className="fixed right-5 top-5 z-20" />

      <div className="relative flex min-h-full w-full flex-col lg:flex-row">
        {/* 左侧品牌区 */}
        <aside
          className="relative hidden w-[46%] max-w-[680px] shrink-0 flex-col justify-between overflow-hidden px-14 py-12 lg:flex xl:px-16"
          style={{
            background: "linear-gradient(155deg, #071b36 0%, #0b2c57 52%, #06142a 100%)"
          }}
        >
          {/* 网格纹理 */}
          <div
            className="pointer-events-none absolute inset-0"
            style={{
              backgroundImage:
                "linear-gradient(rgba(255,255,255,0.045) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.045) 1px, transparent 1px)",
              backgroundSize: "44px 44px",
              maskImage: "radial-gradient(ellipse 90% 70% at 30% 18%, black, transparent 75%)",
              WebkitMaskImage: "radial-gradient(ellipse 90% 70% at 30% 18%, black, transparent 75%)"
            }}
          />
          {/* 光斑 */}
          <div
            className="pointer-events-none absolute -right-24 -top-24 h-[420px] w-[420px] animate-pulse-soft rounded-full blur-3xl"
            style={{ background: "radial-gradient(circle, rgba(56,132,255,0.35), transparent 65%)" }}
          />
          <div
            className="pointer-events-none absolute -bottom-32 -left-24 h-[400px] w-[400px] rounded-full blur-3xl"
            style={{ background: "radial-gradient(circle, rgba(34,211,238,0.20), transparent 65%)" }}
          />

          {/* 顶部 Logo */}
          <div className="relative flex items-center gap-3">
            <div
              className="flex h-11 w-11 items-center justify-center rounded-2xl"
              style={{
                background: "rgba(255,255,255,0.10)",
                border: "1px solid rgba(255,255,255,0.14)",
                boxShadow: "inset 0 1px 0 rgba(255,255,255,0.16)"
              }}
            >
              <RAGStudioLogo className="h-6 w-6 text-white" />
            </div>
            <div>
              <p className="text-base font-semibold tracking-wide text-white">RAGStudio</p>
              <p className="text-xs" style={{ color: "rgba(255,255,255,0.55)" }}>
                企业知识智能平台
              </p>
            </div>
          </div>

          {/* 中部文案 */}
          <div className="relative max-w-[520px]">
            <span
              className="inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs"
              style={{
                background: "rgba(255,255,255,0.08)",
                border: "1px solid rgba(255,255,255,0.12)",
                color: "rgba(255,255,255,0.75)"
              }}
            >
              <Sparkles className="h-3.5 w-3.5" />
              AI Knowledge Assistant
            </span>
            <h2 className="mt-6 text-[34px] font-semibold leading-[1.25] text-white">
              让企业知识
              <br />
              随时可得，有问必答
            </h2>
            <p className="mt-4 text-sm leading-6" style={{ color: "rgba(255,255,255,0.6)" }}>
              基于混合检索与多模型协同的企业级 RAG 问答平台，连接文档、知识库与大模型，让每一次回答都有据可依。
            </p>

            <div className="mt-10 space-y-5">
              {FEATURES.map(({ icon: Icon, title, desc }) => (
                <div key={title} className="flex items-start gap-3.5">
                  <div
                    className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl"
                    style={{
                      background: "rgba(255,255,255,0.08)",
                      border: "1px solid rgba(255,255,255,0.10)"
                    }}
                  >
                    <Icon className="h-[18px] w-[18px] text-[#7cc4ff]" />
                  </div>
                  <div>
                    <p className="text-sm font-medium text-white">{title}</p>
                    <p className="mt-0.5 text-[13px] leading-5" style={{ color: "rgba(255,255,255,0.5)" }}>
                      {desc}
                    </p>
                  </div>
                </div>
              ))}
            </div>
          </div>

          <p className="relative text-xs" style={{ color: "rgba(255,255,255,0.35)" }}>
            © {new Date().getFullYear()} RAGStudio · 安全 · 可靠 · 私有化部署
          </p>
        </aside>

        {/* 右侧登录区 */}
        <main className="relative flex flex-1 items-center justify-center px-4 py-10 sm:px-8">
          <div className="w-full max-w-[400px] animate-fade-up">
            {/* 移动端品牌 */}
            <div className="mb-8 flex flex-col items-center gap-3 lg:hidden">
              <div
                className="flex h-14 w-14 items-center justify-center rounded-2xl"
                style={{
                  background:
                    "linear-gradient(135deg, hsl(var(--primary) / 0.14), hsl(var(--primary) / 0.04))",
                  border: "1px solid hsl(var(--primary) / 0.18)"
                }}
              >
                <RAGStudioLogo className="h-7 w-7 text-[hsl(var(--primary))]" />
              </div>
              <div className="text-center">
                <p className="text-lg font-semibold text-[var(--color-text)]">RAGStudio</p>
                <p className="text-xs text-[var(--color-text-secondary)]">企业内部 AI 知识助手</p>
              </div>
            </div>

            {/* 登录卡片 */}
            <div
              className="rounded-2xl p-7 sm:p-9"
              style={{
                background: "var(--color-bg-container)",
                border: "1px solid var(--color-border)",
                boxShadow: "var(--shadow-lg)"
              }}
            >
              <div className="mb-7">
                <h1 className="text-xl font-semibold text-[var(--color-text)]">欢迎回来</h1>
                <p className="mt-1.5 text-sm text-[var(--color-text-secondary)]">
                  登录后继续与你的知识库对话
                </p>
              </div>

              <form onSubmit={handleSubmit} className="space-y-4">
                <div className="space-y-1.5">
                  <label htmlFor="username" className="text-sm font-medium text-[var(--color-text-secondary)]">
                    用户名
                  </label>
                  <div className="relative">
                    <User className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-[var(--color-text-quaternary)]" />
                    <input
                      id="username"
                      type="text"
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      placeholder="请输入用户名"
                      autoFocus
                      autoComplete="username"
                      className={inputClass}
                    />
                  </div>
                </div>

                <div className="space-y-1.5">
                  <label htmlFor="password" className="text-sm font-medium text-[var(--color-text-secondary)]">
                    密码
                  </label>
                  <div className="relative">
                    <Lock className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-[var(--color-text-quaternary)]" />
                    <input
                      id="password"
                      type={showPassword ? "text" : "password"}
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="请输入密码"
                      autoComplete="current-password"
                      className={`${inputClass} pr-11`}
                    />
                    <button
                      type="button"
                      tabIndex={-1}
                      onClick={() => setShowPassword((v) => !v)}
                      aria-label={showPassword ? "隐藏密码" : "显示密码"}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 rounded-lg p-1.5 text-[var(--color-text-tertiary)] transition-colors hover:bg-[var(--color-fill-quaternary)] hover:text-[var(--color-text-secondary)]"
                    >
                      {showPassword ? (
                        <EyeOff className="h-[18px] w-[18px]" />
                      ) : (
                        <Eye className="h-[18px] w-[18px]" />
                      )}
                    </button>
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={!canSubmit}
                  className="group mt-2 flex h-11 w-full items-center justify-center gap-2 rounded-xl text-sm font-semibold text-white transition-all duration-200 hover:brightness-110 active:scale-[0.985] disabled:cursor-not-allowed disabled:opacity-50"
                  style={{
                    background:
                      "linear-gradient(135deg, hsl(var(--primary)) 0%, hsl(203 100% 44%) 100%)",
                    boxShadow: "0 10px 24px -10px hsl(var(--primary) / 0.7)"
                  }}
                >
                  {isLoading ? (
                    <Loader2 className="h-[18px] w-[18px] animate-spin" />
                  ) : (
                    <>
                      登录
                      <ArrowRight className="h-4 w-4 transition-transform duration-200 group-hover:translate-x-0.5" />
                    </>
                  )}
                </button>
              </form>
            </div>

            <p className="mt-6 text-center text-xs text-[var(--color-text-tertiary)] lg:hidden">
              © {new Date().getFullYear()} RAGStudio · 企业内部系统
            </p>
          </div>
        </main>
      </div>
    </div>
  );
}
