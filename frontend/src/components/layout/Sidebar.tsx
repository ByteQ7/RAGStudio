import * as React from "react";
import { differenceInCalendarDays, isValid } from "date-fns";
import {
  Check,
  LogOut,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
  Settings,
  Trash2,
  X
} from "lucide-react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";

import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";
import { RoleBadge } from "@/components/common/RoleBadge";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { Loading } from "@/components/common/Loading";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";
import { batchDeleteSessions } from "@/services/sessionService";

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
}

export function Sidebar({ isOpen, onClose }: SidebarProps) {
  const sessions = useChatStore((s) => s.sessions);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const isLoading = useChatStore((s) => s.isLoading);
  const sessionsLoaded = useChatStore((s) => s.sessionsLoaded);
  const createSession = useChatStore((s) => s.createSession);
  const deleteSession = useChatStore((s) => s.deleteSession);
  const renameSession = useChatStore((s) => s.renameSession);
  const selectSession = useChatStore((s) => s.selectSession);
  const fetchSessions = useChatStore((s) => s.fetchSessions);
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const [query, setQuery] = React.useState("");
  const [renamingId, setRenamingId] = React.useState<string | null>(null);
  const [renameValue, setRenameValue] = React.useState("");
  const [deleteTarget, setDeleteTarget] = React.useState<{
    id: string;
    title: string;
  } | null>(null);
  const [selectMode, setSelectMode] = React.useState(false);
  const [selectedIds, setSelectedIds] = React.useState<Set<string>>(new Set());
  const [avatarFailed, setAvatarFailed] = React.useState(false);
  const [batchDeleting, setBatchDeleting] = React.useState(false);
  const renameInputRef = React.useRef<HTMLInputElement | null>(null);

  React.useEffect(() => {
    if (sessions.length === 0) {
      fetchSessions().catch(() => null);
    }
  }, [fetchSessions, sessions.length]);

  const filteredSessions = React.useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return sessions;
    return sessions.filter((session) => {
      const title = (session.title || "新对话").toLowerCase();
      return title.includes(keyword) || session.id.toLowerCase().includes(keyword);
    });
  }, [query, sessions]);

  const groupedSessions = React.useMemo(() => {
    const now = new Date();
    const groups = new Map<string, typeof filteredSessions>();
    const order: string[] = [];

    const resolveLabel = (value?: string) => {
      const parsed = value ? new Date(value) : now;
      const date = isValid(parsed) ? parsed : now;
      const diff = Math.max(0, differenceInCalendarDays(now, date));
      if (diff === 0) return "今天";
      if (diff <= 7) return "7天内";
      if (diff <= 30) return "30天内";
      return "更早";
    };

    filteredSessions.forEach((session) => {
      const label = resolveLabel(session.lastTime);
      if (!groups.has(label)) {
        groups.set(label, []);
        order.push(label);
      }
      groups.get(label)?.push(session);
    });

    return order.map((label) => ({
      label,
      items: groups.get(label) || []
    }));
  }, [filteredSessions]);

  React.useEffect(() => {
    if (renamingId) {
      renameInputRef.current?.focus();
      renameInputRef.current?.select();
    }
  }, [renamingId]);

  React.useEffect(() => {
    setAvatarFailed(false);
  }, [user?.avatar, user?.userId]);

  React.useEffect(() => {
    if (!isOpen) exitSelectMode();
  }, [isOpen]);

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const exitSelectMode = () => {
    setSelectMode(false);
    setSelectedIds(new Set());
  };

  const handleBatchDelete = async () => {
    if (selectedIds.size === 0) return;
    setBatchDeleting(true);
    try {
      await batchDeleteSessions(Array.from(selectedIds));
      useChatStore.setState((state) => ({
        sessions: state.sessions.filter((s) => !selectedIds.has(s.id)),
        currentSessionId: selectedIds.has(state.currentSessionId || "")
          ? null
          : state.currentSessionId
      }));
      toast.success(`已删除 ${selectedIds.size} 个会话`);
      exitSelectMode();
    } catch (error) {
      toast.error("批量删除失败，请重试");
    } finally {
      setBatchDeleting(false);
    }
  };

  const avatarUrl = user?.avatar?.trim();
  const showAvatar = Boolean(avatarUrl) && !avatarFailed;
  const avatarFallback = (user?.username || user?.userId || "用户").slice(0, 1).toUpperCase();

  const startRename = (id: string, title: string) => {
    setRenamingId(id);
    setRenameValue(title || "新对话");
  };

  const cancelRename = () => {
    setRenamingId(null);
    setRenameValue("");
  };

  const commitRename = async () => {
    if (!renamingId) return;
    const nextTitle = renameValue.trim();
    if (!nextTitle) {
      cancelRename();
      return;
    }
    const currentTitle = sessions.find((session) => session.id === renamingId)?.title || "新对话";
    if (nextTitle === currentTitle) {
      cancelRename();
      return;
    }
    await renameSession(renamingId, nextTitle);
    cancelRename();
  };

  return (
    <>
      <div
        className={cn(
          "fixed inset-0 z-30 bg-black/20 backdrop-blur-sm transition-opacity lg:hidden",
          isOpen ? "opacity-100" : "pointer-events-none opacity-0"
        )}
        onClick={onClose}
      />
      <aside
        className={cn(
          "fixed left-0 top-0 z-40 flex h-screen w-[260px] flex-shrink-0 flex-col border-r transition-transform lg:static lg:h-screen lg:translate-x-0",
          "bg-[var(--sidebar-bg)] backdrop-blur-2xl backdrop-saturate-150",
          "shadow-tertiary",
          "border-[var(--color-border-secondary)]",
          isOpen ? "translate-x-0" : "-translate-x-full"
        )}
      >
        {/* Top: logo bar */}
        <div className="flex items-center gap-2.5 px-4 pt-5 pb-3">
          <div
            className="flex h-10 w-10 items-center justify-center rounded-xl"
            style={{ background: 'var(--color-fill-quaternary)', color: 'var(--color-text-secondary)' }}
          >
            <RAGStudioLogo className="h-6 w-6" />
          </div>
          <span className="flex-1 text-[14px] font-bold" style={{ color: 'var(--color-text)' }}>
            知识助手
          </span>
          {user?.role === "admin" ? (
            <div className="relative group">
              <button
                type="button"
                className="flex h-9 w-9 items-center justify-center rounded-lg transition-all hover:bg-[var(--color-fill-tertiary)]"
                style={{ color: 'var(--color-text-secondary)' }}
                onClick={() => {
                  window.open("/admin", "_blank");
                  onClose();
                }}
                title="后台管理"
                aria-label="管理后台"
              >
                <Settings className="h-5 w-5" />
              </button>
              <div className="pointer-events-none absolute top-full left-1/2 z-50 mt-2 -translate-x-1/2 whitespace-nowrap rounded-lg bg-[var(--color-bg-spotlight)] px-3 py-1.5 text-xs font-medium text-white opacity-0 shadow-lg transition-opacity duration-150 group-hover:opacity-100">
                后台管理
                <div className="absolute -top-1 left-1/2 -translate-x-1/2 border-4 border-transparent border-b-[var(--color-bg-spotlight)]" />
              </div>
            </div>
          ) : null}
        </div>

        {/* New chat */}
        <div className="px-3 pb-2">
          <button
            type="button"
            className="flex w-full items-center justify-center gap-2 rounded-xl px-3 py-2.5 text-[14px] font-semibold text-white transition-all duration-200 hover:opacity-90 active:scale-[0.97]"
            style={{ background: 'hsl(var(--primary))' }}
            onClick={() => {
              createSession().catch(() => null);
              navigate("/chat");
              onClose();
            }}
          >
            <Plus className="h-4 w-4" />
            新建对话
          </button>
        </div>

        {/* Search / Select Mode */}
        <div className="px-3 pb-1">
          {selectMode ? (
            <div className="flex items-center justify-between rounded-lg border px-3 py-1.5"
              style={{ borderColor: 'var(--color-text-quaternary)', background: 'var(--color-fill-quaternary)' }}
            >
              <span className="text-[14px] font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                已选 {selectedIds.size} 项
              </span>
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={handleBatchDelete}
                  disabled={selectedIds.size === 0 || batchDeleting}
                  className="inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-[13px] font-semibold text-white transition-colors hover:opacity-90 disabled:opacity-40"
                  style={{ background: 'hsl(var(--destructive))' }}
                >
                  <Trash2 className="h-3 w-3" />
                  {batchDeleting ? "删除中..." : "删除"}
                </button>
                <button
                  type="button"
                  onClick={exitSelectMode}
                  className="flex h-6 w-6 items-center justify-center rounded-md transition-colors"
                  style={{ color: 'var(--color-text-tertiary)' }}
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          ) : (
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2" style={{ color: 'var(--color-text-tertiary)' }} />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索对话..."
                className="h-9 w-full rounded-xl border pl-9 pr-3 text-[14px] transition-all duration-200 focus:outline-none"
                style={{
                  borderColor: 'var(--color-border-secondary)',
                  background: 'var(--color-bg-container-secondary)',
                  color: 'var(--color-text)'
                }}
              />
            </div>
          )}
        </div>

        {/* Session list */}
        <div className="flex-1 min-h-0 overflow-y-auto px-2 py-1 sidebar-scroll">
          {sessions.length === 0 && (!sessionsLoaded || isLoading) ? (
            <div className="flex h-full items-center justify-center" style={{ color: 'var(--color-text-tertiary)' }}>
              <Loading label="加载会话中" />
            </div>
          ) : filteredSessions.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center" style={{ color: 'var(--color-text-tertiary)' }}>
              <MessageSquare className="h-10 w-10" strokeWidth={1.5} />
              <p className="mt-2 text-[14px]">暂无对话记录</p>
            </div>
          ) : (
            <div className="py-1">
              {groupedSessions.map((group, index) => (
                <div key={group.label} className={cn(index === 0 ? "" : "mt-3")}>
                  <p className="mb-1 px-3 text-[12px] font-semibold uppercase tracking-wider" style={{ color: 'var(--color-text-tertiary)' }}>
                    {group.label}
                  </p>
                  {group.items.map((session) => {
                    const isSelected = selectedIds.has(session.id);
                    return (
                      <div
                        key={session.id}
                        className={cn(
                          "group flex min-h-[38px] cursor-pointer items-center gap-2 rounded-xl px-3 py-2 text-[14px] transition-all duration-100",
                          selectMode
                            ? isSelected
                              ? "font-medium"
                              : ""
                            : currentSessionId === session.id
                              ? "font-medium"
                              : ""
                        )}
                        style={{
                          background: selectMode
                            ? isSelected
                              ? 'var(--color-fill-secondary)'
                              : 'transparent'
                            : currentSessionId === session.id
                              ? 'var(--color-fill-quaternary)'
                              : 'transparent',
                          color: selectMode && isSelected
                            ? 'var(--color-text)'
                            : currentSessionId === session.id
                              ? 'var(--color-text)'
                              : 'var(--color-text-secondary)',
                          borderColor: currentSessionId === session.id && !selectMode
                            ? 'var(--color-border-secondary)'
                            : 'transparent',
                          borderWidth: 1,
                          borderStyle: 'solid'
                        }}
                        onMouseEnter={(e) => {
                          if (!selectMode && currentSessionId !== session.id) {
                            e.currentTarget.style.background = 'var(--color-fill-quaternary)';
                          }
                        }}
                        onMouseLeave={(e) => {
                          if (!selectMode && currentSessionId !== session.id) {
                            e.currentTarget.style.background = 'transparent';
                          }
                        }}
                        role="button"
                        tabIndex={0}
                        onClick={() => {
                          if (renamingId === session.id) return;
                          if (renamingId) cancelRename();

                          if (selectMode) {
                            toggleSelect(session.id);
                            return;
                          }
                          selectSession(session.id).catch(() => null);
                          navigate(`/chat/${session.id}`);
                          onClose();
                        }}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            if (selectMode) {
                              toggleSelect(session.id);
                              return;
                            }
                            selectSession(session.id).catch(() => null);
                            navigate(`/chat/${session.id}`);
                            onClose();
                          }
                        }}
                      >
                        {selectMode ? (
                          <div
                            className={cn(
                              "flex h-4 w-4 flex-shrink-0 items-center justify-center rounded border transition-colors",
                              isSelected
                                ? "text-white"
                                : ""
                            )}
                            style={{
                              background: isSelected ? 'hsl(var(--primary))' : 'transparent',
                              borderColor: isSelected ? 'hsl(var(--primary))' : 'var(--color-border)'
                            }}
                          >
                            {isSelected && <Check className="h-3 w-3" strokeWidth={3} />}
                          </div>
                        ) : null}
                        {renamingId === session.id ? (
                          <input
                            ref={renameInputRef}
                            value={renameValue}
                            onChange={(event) => setRenameValue(event.target.value)}
                            onClick={(event) => event.stopPropagation()}
                            onKeyDown={(event) => {
                              if (event.key === "Enter") {
                                event.preventDefault();
                                commitRename().catch(() => null);
                              }
                              if (event.key === "Escape") {
                                event.preventDefault();
                                cancelRename();
                              }
                            }}
                            onBlur={() => {
                              commitRename().catch(() => null);
                            }}
                            className="h-6 flex-1 rounded border px-2 text-[14px] focus:outline-none"
                            style={{
                              borderColor: 'hsl(var(--ring))',
                              background: 'var(--color-bg-container)',
                              color: 'var(--color-text)'
                            }}
                          />
                        ) : (
                          <span className={cn("min-w-0 flex-1 truncate", isSelected && "font-medium")}>
                            {session.title || "新对话"}
                          </span>
                        )}
                        {!selectMode && (
                          <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                              <button
                                type="button"
                                className={cn(
                                  "flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-md transition-opacity duration-100",
                                  "hover:bg-[var(--color-fill-quaternary)]",
                                  currentSessionId === session.id
                                    ? "pointer-events-auto opacity-100"
                                    : "pointer-events-none opacity-0 group-hover:pointer-events-auto group-hover:opacity-100"
                                )}
                                onClick={(event) => event.stopPropagation()}
                                aria-label="会话操作"
                              >
                                <MoreHorizontal className="h-3.5 w-3.5" style={{ color: 'var(--color-text-tertiary)' }} />
                              </button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent
                              align="start"
                              className="min-w-[130px]"
                            >
                              <DropdownMenuItem
                                onClick={(event) => {
                                  event.stopPropagation();
                                  startRename(session.id, session.title || "新对话");
                                }}
                              >
                                <Pencil className="mr-2 h-3.5 w-3.5" />
                                重命名
                              </DropdownMenuItem>
                              <DropdownMenuItem
                                onClick={(event) => {
                                  event.stopPropagation();
                                  setDeleteTarget({
                                    id: session.id,
                                    title: session.title || "新对话"
                                  });
                                }}
                                className="text-destructive focus:text-destructive"
                              >
                                <Trash2 className="mr-2 h-3.5 w-3.5" />
                                删除
                              </DropdownMenuItem>
                              <div className="my-1 border-t" style={{ borderColor: 'var(--color-border-secondary)' }} />
                              <DropdownMenuItem
                                onClick={(event) => {
                                  event.stopPropagation();
                                  setSelectMode(true);
                                }}
                              >
                                <Check className="mr-2 h-3.5 w-3.5" />
                                批量管理
                              </DropdownMenuItem>
                            </DropdownMenuContent>
                          </DropdownMenu>
                        )}
                      </div>
                    );
                  })}
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Bottom: user menu */}
        <div className="border-t px-3 py-3" style={{ borderColor: 'var(--color-border-secondary)' }}>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition-colors"
                style={{ color: 'var(--color-text)' }}
                aria-label="用户菜单"
              >
                <div className="flex h-8 w-8 items-center justify-center overflow-hidden rounded-full" style={{ background: 'var(--color-fill-quaternary)', color: 'hsl(var(--primary))' }}>
                  {showAvatar ? (
                    <img
                      src={avatarUrl}
                      alt={user?.username || user?.userId || "用户"}
                      className="h-full w-full object-cover"
                      onError={() => setAvatarFailed(true)}
                    />
                  ) : (
                    <span className="text-xs font-semibold">{avatarFallback}</span>
                  )}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="truncate text-[14px] font-medium">
                    {(() => {
                      const fallback = user?.username || user?.userId || "用户";
                      return /^\d+$/.test(fallback) ? "用户" : fallback;
                    })()}
                  </div>
                  <RoleBadge role={(user?.role as "admin" | "user") || "user"} />
                </div>
                <MoreHorizontal className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--color-text-tertiary)' }} />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" side="top" sideOffset={8} className="w-44">
              <DropdownMenuItem onClick={() => logout()} className="text-destructive focus:text-destructive">
                <LogOut className="mr-2 h-4 w-4" />
                退出登录
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </aside>
      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => {
        if (!open) {
          setDeleteTarget(null);
        }
      }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除该会话？</AlertDialogTitle>
            <AlertDialogDescription>
              [{deleteTarget?.title || "该会话"}] 将被永久删除，无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (!deleteTarget) return;
                const target = deleteTarget;
                const isCurrent = currentSessionId === target.id;
                setDeleteTarget(null);
                deleteSession(target.id)
                  .then(() => {
                    if (isCurrent) {
                      navigate("/chat");
                    }
                  })
                  .catch(() => null);
              }}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
