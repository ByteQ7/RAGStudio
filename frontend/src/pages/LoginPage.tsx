import * as React from "react";
import { useNavigate } from "react-router-dom";
import { Loader2 } from "lucide-react";

import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";
import { useAuthStore } from "@/stores/authStore";

export function LoginPage() {
  const navigate = useNavigate();
  const { login, isAuthenticated, isLoading } = useAuthStore();
  const [username, setUsername] = React.useState("");
  const [password, setPassword] = React.useState("");

  React.useEffect(() => {
    if (isAuthenticated) {
      navigate("/chat", { replace: true });
    }
  }, [isAuthenticated, navigate]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password.trim()) return;
    try {
      await login(username.trim(), password);
      navigate("/chat", { replace: true });
    } catch {
      // toast handled by authStore
    }
  };

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden px-4">
      {/* 渐变背景层 */}
      <div className="absolute inset-0 bg-gradient-to-br from-indigo-50 via-purple-50/40 to-indigo-100/60" />
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top_right,rgba(99,102,241,0.06),transparent_50%)]" />
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_bottom_left,rgba(139,92,246,0.05),transparent_50%)]" />

      {/* 登录卡片 */}
      <div className="relative w-full max-w-[400px] animate-fade-up">
        <div className="rounded-2xl border border-white/60 bg-white/75 p-8 shadow-glass backdrop-blur-xl backdrop-saturate-150">
          {/* Logo */}
          <div className="flex flex-col items-center">
            <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-50 to-purple-50 shadow-sm">
              <RAGStudioLogo className="h-8 w-8 text-indigo-600" />
            </div>
            <h1 className="mt-4 text-xl font-bold text-gray-900">RAGStudio</h1>
            <p className="mt-1 text-sm text-gray-500">企业内部 AI 知识助手</p>
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="mt-8 space-y-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-gray-700">用户名</label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="请输入用户名"
                autoFocus
                className="h-10 w-full rounded-xl border border-gray-200/80 bg-white/70 px-3 text-sm text-gray-900 placeholder:text-gray-400 transition-all duration-200 focus:border-indigo-300 focus:shadow-[0_0_0_3px_rgba(99,102,241,0.08)] focus:outline-none"
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-gray-700">密码</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="请输入密码"
                className="h-10 w-full rounded-xl border border-gray-200/80 bg-white/70 px-3 text-sm text-gray-900 placeholder:text-gray-400 transition-all duration-200 focus:border-indigo-300 focus:shadow-[0_0_0_3px_rgba(99,102,241,0.08)] focus:outline-none"
              />
            </div>
            <button
              type="submit"
              disabled={isLoading || !username.trim() || !password.trim()}
              className="flex h-10 w-full items-center justify-center rounded-xl bg-gradient-to-r from-indigo-500 to-purple-500 text-sm font-medium text-white shadow-sm transition-all duration-200 hover:shadow-md hover:shadow-indigo-200/50 disabled:opacity-50"
            >
              {isLoading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                "登录"
              )}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
