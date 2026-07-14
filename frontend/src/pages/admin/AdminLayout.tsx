import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import {
  ChevronDown,
  ChevronsLeft,
  ChevronsRight,
  ClipboardList,
  Database,
  FolderKanban,
  LayoutDashboard,
  Lightbulb,
  Image,
  KeyRound,
  LogOut,
  Menu,
  MessageSquare,
  Plug,
  Search,
  Settings,
  Upload,
  Users,
  Workflow,
  Zap,
  BrainCircuit,
  X
} from "lucide-react";
import { useAuthStore } from "@/stores/authStore";
import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import { changePassword, uploadAvatar } from "@/services/userService";
import {
  searchChunks,
  type KnowledgeChunk
} from "@/services/knowledgeService";

interface NavItem {
  path: string;
  label: string;
  icon: any;
  badge?: string;
}

const navItems: NavItem[] = [
  { path: "/admin/dashboard", label: "仪表盘", icon: LayoutDashboard },
  { path: "/admin/knowledge", label: "知识库", icon: Database },
  { path: "/admin/ingestion", label: "数据通道", icon: Upload },
  { path: "/admin/traces", label: "链路追踪", icon: Workflow },
  { path: "/admin/mappings", label: "关键词映射", icon: KeyRound },
  { path: "/admin/users", label: "用户管理", icon: Users },
  { path: "/admin/sample-questions", label: "示例问题", icon: Lightbulb },
  { path: "/admin/mcp-servers", label: "MCP 服务", icon: Plug },
  { path: "/admin/skills", label: "技能", icon: Zap },
  { path: "/admin/ai-models", label: "模型管理", icon: BrainCircuit },
  { path: "/admin/settings", label: "系统设置", icon: Settings },
];

export function AdminLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const [collapsed, setCollapsed] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordSubmitting, setPasswordSubmitting] = useState(false);
  const fileInputRefAvatar = useRef<HTMLInputElement | null>(null);
  const [passwordForm, setPasswordForm] = useState({
    currentPassword: "",
    newPassword: "",
    confirmPassword: ""
  });
  const [kbQuery, setKbQuery] = useState("");
  const [chunkOptions, setChunkOptions] = useState<KnowledgeChunk[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchFocused, setSearchFocused] = useState(false);
  const blurTimeoutRef = useRef<number | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const isAiModelsRoute = location.pathname.startsWith("/admin/ai-models");
  const [mobileSidebar, setMobileSidebar] = useState(false);

  useEffect(() => {
    setMobileSidebar(false);
  }, [location.pathname]);

  const handleLogout = async () => {
    await logout();
    navigate("/login");
  };

  useEffect(() => {
    if (!searchFocused) return;
    const keyword = kbQuery.trim();
    if (!keyword) {
      setChunkOptions([]);
      setSearchLoading(false);
      return;
    }
    let active = true;
    const handle = window.setTimeout(() => {
      setSearchLoading(true);
      searchChunks(keyword, 1, 10)
        .then((data) => { if (!active) return; setChunkOptions(data?.records || []); })
        .catch(() => { if (active) setChunkOptions([]); })
        .finally(() => { if (active) setSearchLoading(false); });
    }, 200);
    return () => { active = false; window.clearTimeout(handle); };
  }, [kbQuery, searchFocused]);

  const breadcrumbs = useMemo(() => {
    const segments = location.pathname.split("/").filter(Boolean);
    const items: { label: string; to?: string }[] = [{ label: "首页", to: "/admin/dashboard" }];
    if (segments[0] !== "admin") return items;
    const labels: Record<string, string> = {
      dashboard: "仪表盘", knowledge: "知识库", ingestion: "数据通道",
      traces: "链路追踪", mappings: "关键词映射", users: "用户管理",
      "sample-questions": "示例问题", "mcp-servers": "MCP 服务",
      "ai-models": "模型管理", skills: "技能", settings: "系统设置"
    };
    const section = segments[1];
    if (section) items.push({ label: labels[section] || section, to: `/admin/${section}` });
    if (section === "knowledge" && segments.length > 2) items.push({ label: "文档管理" });
    if (section === "knowledge" && segments.includes("docs")) items.push({ label: "切片管理" });
    if (section === "traces" && segments.length > 2) items.push({ label: "链路详情" });
    return items;
  }, [location.pathname]);

  const handleChunkSelect = (chunk: KnowledgeChunk) => {
    searchInputRef.current?.blur();
    navigate(`/admin/knowledge/${chunk.kbId}/docs/${chunk.docId}?highlight=${chunk.id}`);
    setSearchFocused(false);
    setKbQuery("");
    setChunkOptions([]);
  };

  const handleSearchKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      const keyword = kbQuery.trim();
      if (chunkOptions.length > 0) { handleChunkSelect(chunkOptions[0]); return; }
      if (keyword) {
        searchInputRef.current?.blur();
        navigate(`/admin/knowledge/chunks?keyword=${encodeURIComponent(keyword)}`);
        setSearchFocused(false);
      }
    }
    if (event.key === "Escape") { searchInputRef.current?.blur(); setSearchFocused(false); }
  };

  const currentPath = location.pathname;

  return (
    <div className="flex h-screen" style={{ background: '#f5f6f8' }}>
      {/* Mobile sidebar overlay */}
      {mobileSidebar && (
        <div className="fixed inset-0 z-40 bg-black/20 backdrop-blur-sm lg:hidden" onClick={() => setMobileSidebar(false)} />
      )}

      {/* Sidebar */}
      <aside className={cn(
        "fixed lg:static inset-y-0 left-0 z-50 flex flex-col border-r border-gray-200/60 transition-all duration-300",
        collapsed ? "w-[56px]" : "w-[220px]",
        mobileSidebar ? "translate-x-0" : "-translate-x-full lg:translate-x-0",
        "bg-white/90 backdrop-blur-xl"
      )}>
        {/* Logo */}
        <div className={cn("flex h-14 items-center border-b border-gray-100/80", collapsed ? "justify-center px-0" : "px-4")}>
          <div className="flex items-center gap-3 min-w-0">
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg shadow-sm" style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' }}>
              <RAGStudioLogo className="h-4 w-4" />
            </div>
            {!collapsed && (
              <div className="min-w-0">
                <h1 className="text-sm font-semibold text-gray-900 truncate">RAGStudio</h1>
                <p className="text-[10px] text-gray-400 truncate">管理控制台</p>
              </div>
            )}
          </div>
        </div>

        {/* Nav */}
        <nav className="flex-1 overflow-y-auto px-2 py-3 space-y-0.5">
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive = currentPath.startsWith(item.path) && (item.path === "/admin" || currentPath === item.path || currentPath.startsWith(item.path + "/") || (item.path === "/admin/dashboard" && currentPath === "/admin"));
            return (
              <Link
                key={item.path}
                to={item.path}
                onClick={() => setMobileSidebar(false)}
                className={cn(
                  "flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-all duration-150 group",
                  isActive
                    ? "font-medium shadow-sm"
                    : "text-gray-500 hover:text-gray-700",
                  collapsed && "justify-center px-2"
                )}
                style={isActive ? { background: 'linear-gradient(135deg, #eef2ff, #f0f1ff)', color: '#6366f1' } : {}}
                title={collapsed ? item.label : undefined}
              >
                <Icon className={cn("h-4 w-4 shrink-0", isActive ? "text-[#6366f1]" : "text-gray-400 group-hover:text-gray-600")} />
                {!collapsed && <span className="truncate">{item.label}</span>}
              </Link>
            );
          })}
        </nav>

        {/* Collapse button */}
        <div className="border-t border-gray-100/80 p-2">
          <button
            type="button"
            onClick={() => setCollapsed(!collapsed)}
            className="flex w-full items-center justify-center rounded-lg py-2 text-xs text-gray-400 hover:bg-gray-100/50 hover:text-gray-600 transition-all"
          >
            {collapsed ? <ChevronsRight className="h-4 w-4" /> : <ChevronsLeft className="h-4 w-4" />}
          </button>
        </div>
      </aside>

      {/* Main */}
      <div className={cn("flex flex-1 flex-col min-w-0", isAiModelsRoute ? "h-screen overflow-hidden" : "h-screen overflow-y-auto")}>
        {/* Top bar */}
        <header className="sticky top-0 z-30 border-b border-gray-200/50" style={{ background: 'rgba(255,255,255,0.85)', backdropFilter: 'blur(16px) saturate(1.3)' }}>
          <div className="flex h-14 items-center gap-3 px-4 lg:px-6">
            <Button variant="ghost" size="icon" className="lg:hidden -ml-2" onClick={() => setMobileSidebar(true)}>
              <Menu className="h-5 w-5" />
            </Button>

            {/* Breadcrumb */}
            <nav className="hidden sm:flex items-center gap-1.5 text-sm text-gray-400 min-w-0">
              {breadcrumbs.map((item, i) => (
                <span key={i} className="flex items-center gap-1.5">
                  {i > 0 && <span className="text-gray-300">/</span>}
                  {item.to && i < breadcrumbs.length - 1 ? (
                    <Link to={item.to} className="hover:text-gray-600 transition-colors">{item.label}</Link>
                  ) : (
                    <span className={i === breadcrumbs.length - 1 ? "text-gray-700 font-medium" : ""}>{item.label}</span>
                  )}
                </span>
              ))}
            </nav>

            <div className="flex-1" />

            {/* Search */}
            <div className="relative hidden md:block">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-gray-400" />
              <input
                ref={searchInputRef}
                value={kbQuery}
                onChange={(e) => setKbQuery(e.target.value)}
                onFocus={() => setSearchFocused(true)}
                onBlur={() => { blurTimeoutRef.current = window.setTimeout(() => setSearchFocused(false), 150); }}
                onKeyDown={handleSearchKeyDown}
                placeholder="搜索 Chunk..."
                className="h-8 w-[200px] rounded-lg border border-gray-200/60 bg-gray-50/50 pl-9 pr-3 text-xs text-gray-700 placeholder:text-gray-400 transition-all focus:w-[260px] focus:border-indigo-300/50 focus:bg-white focus:shadow-[0_0_0_2px_rgba(99,102,241,0.06)] focus:outline-none"
              />
              {searchFocused && kbQuery.trim() && (
                <div className="absolute left-0 right-0 top-full mt-1 z-50 rounded-xl border border-gray-100 bg-white py-1 shadow-lg" onMouseDown={(e) => e.preventDefault()}>
                  {searchLoading && <div className="px-3 py-2 text-xs text-gray-400">搜索中...</div>}
                  {chunkOptions.map((chunk) => (
                    <button key={chunk.id} type="button" onMouseDown={() => handleChunkSelect(chunk)}
                      className="w-full px-3 py-2 text-left text-xs hover:bg-gray-50 transition-colors">
                      <span className="font-mono text-gray-900">{chunk.id}</span>
                      <span className="ml-2 text-gray-400">{chunk.kbName || '知识库'} → {chunk.docName || '文档'}</span>
                    </button>
                  ))}
                  {!searchLoading && chunkOptions.length === 0 && <div className="px-3 py-2 text-xs text-gray-400">无结果</div>}
                </div>
              )}
            </div>

            {/* Right actions */}
            <div className="flex items-center gap-2">
              <Button variant="ghost" size="sm" className="hidden sm:inline-flex gap-1.5 text-xs" onClick={() => navigate("/chat")}>
                <MessageSquare className="h-3.5 w-3.5" />返回聊天
              </Button>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button type="button" className="flex items-center gap-2 rounded-lg border border-gray-200/60 bg-white px-2.5 py-1.5 text-xs text-gray-600 hover:bg-gray-50 transition-colors" aria-label="用户菜单">
                    <div className="flex h-6 w-6 items-center justify-center rounded-full bg-indigo-50 text-[10px] font-semibold text-indigo-600">
                      {(user?.username || "管").slice(0, 1).toUpperCase()}
                    </div>
                    <span className="hidden sm:inline">{user?.username || "管理员"}</span>
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" sideOffset={6} className="w-44 rounded-xl border-gray-100 p-1 shadow-lg">
                  <div className="px-3 py-1.5 text-xs text-gray-500">{user?.username} · {user?.role === "admin" ? "管理员" : "成员"}</div>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => fileInputRefAvatar.current?.click()} className="rounded-lg text-xs">
                    <Image className="mr-2 h-3.5 w-3.5" />更换头像
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setPasswordOpen(true)} className="rounded-lg text-xs">
                    <KeyRound className="mr-2 h-3.5 w-3.5" />修改密码
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={handleLogout} className="rounded-lg text-xs text-rose-500 focus:text-rose-500">
                    <LogOut className="mr-2 h-3.5 w-3.5" />退出登录
                  </DropdownMenuItem>
                </DropdownMenuContent>
                <input ref={fileInputRefAvatar} type="file" accept="image/*" className="hidden" onChange={async (e) => {
                  const file = e.target.files?.[0]; if (!file) return;
                  try { const r = await uploadAvatar(file); useAuthStore.getState().updateAvatar(r.avatarUrl); toast.success("头像已更新"); } catch { toast.error("头像上传失败"); }
                }} />
              </DropdownMenu>
            </div>
          </div>
        </header>

        {/* Content */}
        <div className={cn("flex-1", !isAiModelsRoute && "p-4 lg:p-6")}>
          <Outlet />
        </div>
      </div>

      {/* Password Dialog */}
      <Dialog open={passwordOpen} onOpenChange={(open) => { setPasswordOpen(open); if (!open) setPasswordForm({ currentPassword: "", newPassword: "", confirmPassword: "" }); }}>
        <DialogContent className="sm:max-w-[400px]">
          <DialogHeader><DialogTitle>修改密码</DialogTitle><DialogDescription>请输入当前密码与新密码</DialogDescription></DialogHeader>
          <div className="space-y-3">
            <Input type="password" value={passwordForm.currentPassword} onChange={(e) => setPasswordForm(p => ({ ...p, currentPassword: e.target.value }))} placeholder="当前密码" />
            <Input type="password" value={passwordForm.newPassword} onChange={(e) => setPasswordForm(p => ({ ...p, newPassword: e.target.value }))} placeholder="新密码" />
            <Input type="password" value={passwordForm.confirmPassword} onChange={(e) => setPasswordForm(p => ({ ...p, confirmPassword: e.target.value }))} placeholder="确认新密码" />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPasswordOpen(false)}>取消</Button>
            <Button onClick={async () => {
              if (!passwordForm.currentPassword || !passwordForm.newPassword) { toast.error("请填写完整"); return; }
              if (passwordForm.newPassword !== passwordForm.confirmPassword) { toast.error("两次密码不一致"); return; }
              try { setPasswordSubmitting(true); await changePassword({ currentPassword: passwordForm.currentPassword, newPassword: passwordForm.newPassword }); toast.success("密码已更新"); setPasswordOpen(false); setPasswordForm({ currentPassword: "", newPassword: "", confirmPassword: "" }); } catch (err) { toast.error((err as Error).message || "修改失败"); } finally { setPasswordSubmitting(false); }
            }} disabled={passwordSubmitting}>{passwordSubmitting ? "保存中..." : "保存"}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
