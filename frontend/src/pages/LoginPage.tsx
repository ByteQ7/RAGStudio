import * as React from "react";
import { Eye, EyeOff, Lock, User } from "lucide-react";
import { useNavigate } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { useAuthStore } from "@/stores/authStore";
import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";

export function LoginPage() {
  const navigate = useNavigate();
  const { login, isLoading } = useAuthStore();
  const [showPassword, setShowPassword] = React.useState(false);
  const [remember, setRemember] = React.useState(true);
  const [form, setForm] = React.useState({ username: "", password: "" });
  const [error, setError] = React.useState<string | null>(null);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    if (!form.username.trim() || !form.password.trim()) {
      setError("请输入用户名和密码。");
      return;
    }
    try {
      await login(form.username.trim(), form.password.trim());
      if (!remember) {
        // 如需仅在内存中保存登录态，可在此扩展。
      }
      navigate("/chat");
    } catch (err) {
      setError((err as Error).message || "登录失败，请稍后重试。");
    }
  };

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4">
      <div className="absolute inset-0 bg-gradient-to-br from-slate-50 via-white to-indigo-50/30" />
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-40 right-[-60px] h-80 w-80 rounded-full bg-gradient-radial from-indigo-100/50 via-transparent to-transparent blur-3xl"
      />
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -bottom-40 left-[-80px] h-80 w-80 rounded-full bg-gradient-radial from-slate-200/40 via-transparent to-transparent blur-3xl"
      />
      <div className="relative z-10 w-full max-w-md rounded-xl border border-slate-200/60 bg-white/90 p-8 shadow-[0_8px_30px_rgba(15,23,42,0.08)] backdrop-blur-xl">
        <div className="mb-6 text-center">
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-xl bg-indigo-600 text-white shadow-sm">
            <RAGStudioLogo className="h-7 w-7" />
          </div>
          <p className="font-display text-2xl font-semibold text-slate-900">欢迎回来</p>
          <p className="mt-1.5 text-sm text-slate-500">
            请输入您的凭据以继续访问
          </p>
        </div>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-wide text-slate-500">
              用户名
            </label>
            <div className="relative">
              <User className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
              <Input
                placeholder="请输入用户名"
                value={form.username}
                onChange={(event) => setForm((prev) => ({ ...prev, username: event.target.value }))}
                className="pl-10 border-slate-200 focus:border-indigo-400 focus:ring-indigo-400/20"
                autoComplete="username"
              />
            </div>
          </div>
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-wide text-slate-500">
              密码
            </label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
              <Input
                type={showPassword ? "text" : "password"}
                placeholder="请输入密码"
                value={form.password}
                onChange={(event) => setForm((prev) => ({ ...prev, password: event.target.value }))}
                className="pl-10 pr-10 border-slate-200 focus:border-indigo-400 focus:ring-indigo-400/20"
                autoComplete="current-password"
              />
              <button
                type="button"
                onClick={() => setShowPassword((prev) => !prev)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                aria-label="显示或隐藏密码"
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>
          <div className="flex items-center justify-between text-sm">
            <label className="flex items-center gap-2 text-slate-500">
              <Checkbox checked={remember} onCheckedChange={(value) => setRemember(Boolean(value))} />
              记住我
            </label>
            <span className="text-xs text-slate-400">账号由管理员初始化</span>
          </div>
          {error ? <p className="text-sm text-red-500">{error}</p> : null}
          <Button
            type="submit"
            className="w-full bg-indigo-600 text-white shadow-sm hover:bg-indigo-700"
            disabled={isLoading}
          >
            {isLoading ? "正在登录..." : "登录"}
          </Button>
        </form>
      </div>
    </div>
  );
}
