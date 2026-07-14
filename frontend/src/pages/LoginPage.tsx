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
    <div className="flex min-h-screen items-center justify-center px-4" style={{ background: 'linear-gradient(135deg, #f8f9fc 0%, #eef2ff 50%, #f0f1ff 100%)' }}>
      <div className="glass-card w-full max-w-[400px] rounded-2xl p-8">
        {/* Logo */}
        <div className="flex flex-col items-center">
          <div
            className="flex h-12 w-12 items-center justify-center rounded-xl shadow-lg"
            style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' }}
          >
            <RAGStudioLogo className="h-6 w-6" />
          </div>
          <h1 className="mt-4 text-xl font-bold text-gray-900">登录 RAGStudio</h1>
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
              className="h-10 w-full rounded-xl border border-gray-200 bg-white/80 px-3 text-sm text-gray-900 placeholder:text-gray-400 transition-all duration-200 focus:border-indigo-300/50 focus:shadow-[0_0_0_3px_rgba(99,102,241,0.06)] focus:outline-none"
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">密码</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入密码"
              className="h-10 w-full rounded-xl border border-gray-200 bg-white/80 px-3 text-sm text-gray-900 placeholder:text-gray-400 transition-all duration-200 focus:border-indigo-300/50 focus:shadow-[0_0_0_3px_rgba(99,102,241,0.06)] focus:outline-none"
            />
          </div>
          <button
            type="submit"
            disabled={isLoading || !username.trim() || !password.trim()}
            className="flex h-10 w-full items-center justify-center rounded-xl font-medium text-white shadow-sm transition-all duration-200 hover:shadow-md disabled:opacity-50"
            style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' }}
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
  );
}
