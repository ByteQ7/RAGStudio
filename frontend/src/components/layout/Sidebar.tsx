import * as React from "react";
import { differenceInCalendarDays, isValid } from "date-fns";
import {
  BookOpen,
  Check,
  ChevronDown,
  Folder,
  FolderMinus,
  FolderPlus,
  LogOut,
  MessageSquare,
  MessageSquareText,
  MoreHorizontal,
  Pencil,
  Pin,
  PinOff,
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
import { ThemeToggle } from "@/components/common/ThemeToggle";

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
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Loading } from "@/components/common/Loading";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";
import { batchDeleteSessions } from "@/services/sessionService";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";
import type { ConversationGroup, Session } from "@/types";

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
}

export function Sidebar({ isOpen, onClose }: SidebarProps) {
  const sessions = useChatStore((s) => s.sessions);
  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const isLoading = useChatStore((s) => s.isLoading);
  const sessionsLoaded = useChatStore((s) => s.sessionsLoaded);
  const groups = useChatStore((s) => s.groups);
  const fetchGroups = useChatStore((s) => s.fetchGroups);
  const createGroup = useChatStore((s) => s.createGroup);
  const updateGroup = useChatStore((s) => s.updateGroup);
  const deleteGroup = useChatStore((s) => s.deleteGroup);
  const moveSessionsToGroup = useChatStore((s) => s.moveSessionsToGroup);
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
  const [batchMoving, setBatchMoving] = React.useState(false);
  const [collapsedGroups, setCollapsedGroups] = React.useState<Set<string>>(new Set());
  // 创建分组：仅设置名称
  const [groupCreateOpen, setGroupCreateOpen] = React.useState(false);
  const [groupCreateName, setGroupCreateName] = React.useState("");
  const [groupCreating, setGroupCreating] = React.useState(false);
  // 编辑名称
  const [groupNameDialog, setGroupNameDialog] = React.useState<{ id: string; name: string } | null>(null);
  const [groupRenaming, setGroupRenaming] = React.useState(false);
  // 添加/编辑指令
  const [groupInstructionDialog, setGroupInstructionDialog] = React.useState<{
    id: string;
    name: string;
    instruction: string;
    hasInstruction: boolean;
  } | null>(null);
  const [groupInstructionSaving, setGroupInstructionSaving] = React.useState(false);
  // 添加知识库
  const [groupKbDialog, setGroupKbDialog] = React.useState<{ id: string; name: string } | null>(null);
  const [kbOptions, setKbOptions] = React.useState<KnowledgeBase[]>([]);
  const [kbLoading, setKbLoading] = React.useState(false);
  const [kbSearch, setKbSearch] = React.useState("");
  const [kbSelected, setKbSelected] = React.useState<Set<string>>(new Set());
  const [kbSaving, setKbSaving] = React.useState(false);
  // 删除分组（级联删除组内对话）
  const [groupDeleteTarget, setGroupDeleteTarget] = React.useState<ConversationGroup | null>(null);
  const [groupDeleting, setGroupDeleting] = React.useState(false);
  const renameInputRef = React.useRef<HTMLInputElement | null>(null);

  React.useEffect(() => {
    if (sessions.length === 0) {
      fetchSessions().catch(() => null);
    }
  }, [fetchSessions, sessions.length]);

  React.useEffect(() => {
    fetchGroups().catch(() => null);
  }, [fetchGroups]);

  const filteredSessions = React.useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return sessions;
    return sessions.filter((session) => {
      const title = (session.title || "新对话").toLowerCase();
      return title.includes(keyword) || session.id.toLowerCase().includes(keyword);
    });
  }, [query, sessions]);

  // 分组内会话（已按搜索关键词过滤）
  const sessionsByGroup = React.useMemo(() => {
    const map = new Map<string, Session[]>();
    filteredSessions.forEach((session) => {
      if (!session.groupId) return;
      const list = map.get(session.groupId);
      if (list) {
        list.push(session);
      } else {
        map.set(session.groupId, [session]);
      }
    });
    return map;
  }, [filteredSessions]);

  // 未分组会话继续按时间分桶
  const ungroupedSessions = React.useMemo(
    () => filteredSessions.filter((session) => !session.groupId),
    [filteredSessions]
  );

  const groupedSessions = React.useMemo(() => {
    const now = new Date();
    const groups = new Map<string, typeof ungroupedSessions>();
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

    ungroupedSessions.forEach((session) => {
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
  }, [ungroupedSessions]);

  const searchKeyword = query.trim().toLowerCase();
  const searchActive = Boolean(searchKeyword);

  // 搜索时显示名称命中或组内有命中会话的分组，并忽略折叠状态
  const visibleGroups = React.useMemo(() => {
    if (!searchKeyword) return groups;
    return groups.filter((group) => {
      if (group.name.toLowerCase().includes(searchKeyword)) return true;
      return (sessionsByGroup.get(group.id) ?? []).length > 0;
    });
  }, [groups, searchKeyword, sessionsByGroup]);

  const isGroupExpanded = (groupId: string) =>
    searchActive || !collapsedGroups.has(groupId);

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

  const handleBatchMove = async (groupId: string | null) => {
    if (selectedIds.size === 0) return;
    setBatchMoving(true);
    try {
      await moveSessionsToGroup(Array.from(selectedIds), groupId);
      exitSelectMode();
    } finally {
      setBatchMoving(false);
    }
  };

  const toggleGroupCollapsed = (groupId: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(groupId)) {
        next.delete(groupId);
      } else {
        next.add(groupId);
      }
      return next;
    });
  };

  const handleCreateChatInGroup = (groupId: string) => {
    createSession(groupId).catch(() => null);
    navigate("/chat");
    onClose();
  };

  // ==================== 分组：创建 / 设置菜单 ====================

  const openCreateGroupDialog = () => {
    setGroupCreateName("");
    setGroupCreateOpen(true);
  };

  const submitCreateGroup = async () => {
    const name = groupCreateName.trim();
    if (!name) return;
    setGroupCreating(true);
    try {
      await createGroup(name);
      setGroupCreateOpen(false);
    } finally {
      setGroupCreating(false);
    }
  };

  const toggleGroupPin = (group: ConversationGroup) => {
    updateGroup(group.id, { pinned: !group.pinned }).catch(() => null);
  };

  const openGroupNameDialog = (group: ConversationGroup) => {
    setGroupNameDialog({ id: group.id, name: group.name });
  };

  const submitGroupName = async () => {
    if (!groupNameDialog) return;
    const name = groupNameDialog.name.trim();
    if (!name) return;
    setGroupRenaming(true);
    try {
      await updateGroup(groupNameDialog.id, { name });
      setGroupNameDialog(null);
    } finally {
      setGroupRenaming(false);
    }
  };

  const openGroupInstructionDialog = (group: ConversationGroup) => {
    setGroupInstructionDialog({
      id: group.id,
      name: group.name,
      instruction: group.instruction || "",
      hasInstruction: Boolean(group.instruction)
    });
  };

  const submitGroupInstruction = async () => {
    if (!groupInstructionDialog) return;
    setGroupInstructionSaving(true);
    try {
      // 空串 = 清除指令
      await updateGroup(groupInstructionDialog.id, {
        instruction: groupInstructionDialog.instruction
      });
      setGroupInstructionDialog(null);
    } finally {
      setGroupInstructionSaving(false);
    }
  };

  const openGroupKbDialog = (group: ConversationGroup) => {
    setGroupKbDialog({ id: group.id, name: group.name });
    setKbSearch("");
    setKbSelected(new Set(group.knowledgeBaseIds ?? []));
    setKbLoading(true);
    getKnowledgeBases()
      .then((data) => setKbOptions(data || []))
      .catch(() => setKbOptions([]))
      .finally(() => setKbLoading(false));
  };

  const toggleKbSelected = (kbId: string) => {
    setKbSelected((prev) => {
      const next = new Set(prev);
      if (next.has(kbId)) {
        next.delete(kbId);
      } else {
        next.add(kbId);
      }
      return next;
    });
  };

  const submitGroupKbs = async () => {
    if (!groupKbDialog) return;
    setKbSaving(true);
    try {
      await updateGroup(groupKbDialog.id, { knowledgeBaseIds: Array.from(kbSelected) });
      setGroupKbDialog(null);
    } finally {
      setKbSaving(false);
    }
  };

  const clearGroupKbs = async () => {
    if (!groupKbDialog) return;
    setKbSaving(true);
    try {
      await updateGroup(groupKbDialog.id, { knowledgeBaseIds: [] });
      setKbSelected(new Set());
      setGroupKbDialog(null);
    } finally {
      setKbSaving(false);
    }
  };

  const handleGroupDelete = () => {
    if (!groupDeleteTarget) return;
    const target = groupDeleteTarget;
    setGroupDeleteTarget(null);
    setGroupDeleting(true);
    deleteGroup(target.id)
      .then((removedCurrent) => {
        // 当前会话随分组级联删除时回到新对话页
        if (removedCurrent) {
          navigate("/chat");
        }
      })
      .catch(() => null)
      .finally(() => setGroupDeleting(false));
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

  // 会话条目渲染（分组内与未分组区域共用）
  const renderSessionRow = (session: Session) => {
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
              ? "var(--color-fill-secondary)"
              : "transparent"
            : currentSessionId === session.id
              ? "var(--color-fill-quaternary)"
              : "transparent",
          color:
            selectMode && isSelected
              ? "var(--color-text)"
              : currentSessionId === session.id
                ? "var(--color-text)"
                : "var(--color-text-secondary)",
          borderColor:
            currentSessionId === session.id && !selectMode
              ? "var(--color-border-secondary)"
              : "transparent",
          borderWidth: 1,
          borderStyle: "solid"
        }}
        onMouseEnter={(e) => {
          if (!selectMode && currentSessionId !== session.id) {
            e.currentTarget.style.background = "var(--color-fill-quaternary)";
          }
        }}
        onMouseLeave={(e) => {
          if (!selectMode && currentSessionId !== session.id) {
            e.currentTarget.style.background = "transparent";
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
              isSelected ? "text-white" : ""
            )}
            style={{
              background: isSelected ? "hsl(var(--primary))" : "transparent",
              borderColor: isSelected
                ? "hsl(var(--primary))"
                : "var(--color-border)"
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
              borderColor: "hsl(var(--ring))",
              background: "var(--color-bg-container)",
              color: "var(--color-text)"
            }}
          />
        ) : (
          <span
            className={cn("min-w-0 flex-1 truncate", isSelected && "font-medium")}
          >
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
                <MoreHorizontal
                  className="h-3.5 w-3.5"
                  style={{ color: "var(--color-text-tertiary)" }}
                />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="min-w-[130px]">
              <DropdownMenuItem
                onClick={(event) => {
                  event.stopPropagation();
                  startRename(session.id, session.title || "新对话");
                }}
              >
                <Pencil className="mr-2 h-3.5 w-3.5" />
                重命名
              </DropdownMenuItem>
              <DropdownMenuSub>
                <DropdownMenuSubTrigger>
                  <Folder className="mr-2 h-3.5 w-3.5" />
                  移动到分组
                </DropdownMenuSubTrigger>
                <DropdownMenuSubContent className="max-h-64 min-w-[150px] overflow-y-auto">
                  {groups.length === 0 ? (
                    <div
                      className="px-3 py-2 text-[13px]"
                      style={{ color: "var(--color-text-tertiary)" }}
                    >
                      暂无分组
                    </div>
                  ) : (
                    groups.map((group) => (
                      <DropdownMenuItem
                        key={group.id}
                        disabled={session.groupId === group.id}
                        onClick={(event) => {
                          event.stopPropagation();
                          moveSessionsToGroup([session.id], group.id).catch(() => null);
                        }}
                      >
                        {group.name}
                      </DropdownMenuItem>
                    ))
                  )}
                  {session.groupId ? (
                    <>
                      <div
                        className="my-1 border-t"
                        style={{ borderColor: "var(--color-border-secondary)" }}
                      />
                      <DropdownMenuItem
                        onClick={(event) => {
                          event.stopPropagation();
                          moveSessionsToGroup([session.id], null).catch(() => null);
                        }}
                      >
                        <FolderMinus className="mr-2 h-3.5 w-3.5" />
                        移出分组
                      </DropdownMenuItem>
                    </>
                  ) : null}
                </DropdownMenuSubContent>
              </DropdownMenuSub>
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
              <div
                className="my-1 border-t"
                style={{ borderColor: "var(--color-border-secondary)" }}
              />
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
            style={{
              background: "var(--color-fill-quaternary)",
              color: "var(--color-text-secondary)"
            }}
          >
            <RAGStudioLogo className="h-6 w-6" />
          </div>
          <span className="flex-1 text-[14px] font-bold" style={{ color: "var(--color-text)" }}>
            知识助手
          </span>
          {user?.role === "admin" ? (
            <div className="relative group">
              <button
                type="button"
                className="flex h-9 w-9 items-center justify-center rounded-lg transition-all hover:bg-[var(--color-fill-tertiary)]"
                style={{ color: "var(--color-text-secondary)" }}
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
            style={{ background: "hsl(var(--primary))" }}
            onClick={() => {
              createSession().catch(() => null);
              navigate("/chat");
              onClose();
            }}
          >
            <Plus className="h-4 w-4" />
            新建对话
          </button>
          <button
            type="button"
            className="mt-2 flex w-full items-center justify-center gap-2 rounded-xl border px-3 py-2.5 text-[14px] font-semibold transition-all duration-200 hover:opacity-85 active:scale-[0.97]"
            style={{
              borderColor: "hsl(var(--primary))",
              color: "hsl(var(--primary))"
            }}
            onClick={openCreateGroupDialog}
          >
            <FolderPlus className="h-4 w-4" />
            新建分组
          </button>
        </div>

        {/* Search / Select Mode */}
        <div className="px-3 pb-1">
          {selectMode ? (
            <div
              className="flex items-center justify-between rounded-lg border px-3 py-1.5"
              style={{
                borderColor: "var(--color-text-quaternary)",
                background: "var(--color-fill-quaternary)"
              }}
            >
              <span
                className="text-[14px] font-medium"
                style={{ color: "var(--color-text-secondary)" }}
              >
                已选 {selectedIds.size} 项
              </span>
              <div className="flex items-center gap-1">
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <button
                      type="button"
                      disabled={selectedIds.size === 0 || batchMoving}
                      className="inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-[13px] font-semibold transition-colors hover:opacity-90 disabled:opacity-40"
                      style={{
                        background: "var(--color-fill-secondary)",
                        color: "var(--color-text)"
                      }}
                    >
                      <FolderPlus className="h-3 w-3" />
                      {batchMoving ? "移动中..." : "移动"}
                    </button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" className="min-w-[150px]">
                    {groups.length === 0 ? (
                      <div
                        className="px-3 py-2 text-[13px]"
                        style={{ color: "var(--color-text-tertiary)" }}
                      >
                        暂无分组，请先新建
                      </div>
                    ) : (
                      groups.map((group) => (
                        <DropdownMenuItem key={group.id} onClick={() => handleBatchMove(group.id).catch(() => null)}>
                          <Folder className="mr-2 h-3.5 w-3.5" />
                          {group.name}
                        </DropdownMenuItem>
                      ))
                    )}
                    <div
                      className="my-1 border-t"
                      style={{ borderColor: "var(--color-border-secondary)" }}
                    />
                    <DropdownMenuItem onClick={() => handleBatchMove(null).catch(() => null)}>
                      <FolderMinus className="mr-2 h-3.5 w-3.5" />
                      移出分组
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
                <button
                  type="button"
                  onClick={handleBatchDelete}
                  disabled={selectedIds.size === 0 || batchDeleting}
                  className="inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-[13px] font-semibold text-white transition-colors hover:opacity-90 disabled:opacity-40"
                  style={{ background: "hsl(var(--destructive))" }}
                >
                  <Trash2 className="h-3 w-3" />
                  {batchDeleting ? "删除中..." : "删除"}
                </button>
                <button
                  type="button"
                  onClick={exitSelectMode}
                  className="flex h-6 w-6 items-center justify-center rounded-md transition-colors"
                  style={{ color: "var(--color-text-tertiary)" }}
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          ) : (
            <div className="relative">
              <Search
                className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2"
                style={{ color: "var(--color-text-tertiary)" }}
              />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索对话..."
                className="h-9 w-full rounded-xl border pl-9 pr-3 text-[14px] transition-all duration-200 focus:outline-none"
                style={{
                  borderColor: "var(--color-border-secondary)",
                  background: "var(--color-bg-container-secondary)",
                  color: "var(--color-text)"
                }}
              />
            </div>
          )}
        </div>

        {/* Session list */}
        <div className="flex-1 min-h-0 overflow-y-auto px-2 py-1 sidebar-scroll">
          {sessions.length === 0 && (!sessionsLoaded || isLoading) ? (
            <div
              className="flex h-full items-center justify-center"
              style={{ color: "var(--color-text-tertiary)" }}
            >
              <Loading label="加载会话中" />
            </div>
          ) : filteredSessions.length === 0 && groups.length === 0 ? (
            <div
              className="flex h-full flex-col items-center justify-center"
              style={{ color: "var(--color-text-tertiary)" }}
            >
              <MessageSquare className="h-10 w-10" strokeWidth={1.5} />
              <p className="mt-2 text-[14px]">暂无对话记录</p>
            </div>
          ) : (
            <div className="py-1">
              {/* 对话分组（元宝式） */}
              {visibleGroups.map((group) => {
                const items = sessionsByGroup.get(group.id) ?? [];
                const expanded = isGroupExpanded(group.id);
                return (
                  <div key={group.id} className="mt-3">
                    {/* 分组头：整行统一容器，名称与操作按钮一体 */}
                    <div className="flex min-h-[44px] items-center gap-1 rounded-xl px-1.5 py-1 transition-colors hover:bg-[var(--color-fill-quaternary)]">
                      <button
                        type="button"
                        className="flex min-w-0 flex-1 items-center gap-2 rounded-md py-1 text-left"
                        onClick={() => toggleGroupCollapsed(group.id)}
                        aria-label={expanded ? "折叠分组" : "展开分组"}
                      >
                        <ChevronDown
                          className={cn("h-4 w-4 shrink-0 transition-transform", !expanded && "-rotate-90")}
                          style={{ color: "var(--color-text-tertiary)" }}
                        />
                        <Folder className="h-4 w-4 shrink-0" style={{ color: "var(--color-text-tertiary)" }} />
                        {group.pinned ? (
                          <Pin
                            className="h-3.5 w-3.5 shrink-0"
                            style={{ color: "hsl(var(--primary))" }}
                            fill="hsl(var(--primary))"
                          />
                        ) : null}
                        <span
                          className="min-w-0 flex-1 truncate text-[14px] font-semibold"
                          style={{ color: "var(--color-text-secondary)" }}
                        >
                          {group.name}
                        </span>
                        <span className="shrink-0 text-[12px]" style={{ color: "var(--color-text-quaternary)" }}>
                          {items.length}
                        </span>
                      </button>
                      <button
                        type="button"
                        className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg transition-colors hover:bg-[var(--color-fill-tertiary)]"
                        onClick={() => handleCreateChatInGroup(group.id)}
                        title="在分组内新建对话"
                        aria-label="在分组内新建对话"
                      >
                        <Plus className="h-4 w-4" style={{ color: "var(--color-text-tertiary)" }} />
                      </button>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <button
                            type="button"
                            className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg transition-colors hover:bg-[var(--color-fill-tertiary)]"
                            title="分组设置"
                            aria-label="分组设置"
                          >
                            <Settings className="h-4 w-4" style={{ color: "var(--color-text-tertiary)" }} />
                          </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start" className="min-w-[140px]">
                          <DropdownMenuItem onClick={() => toggleGroupPin(group)}>
                            {group.pinned ? (
                              <PinOff className="mr-2 h-3.5 w-3.5" />
                            ) : (
                              <Pin className="mr-2 h-3.5 w-3.5" />
                            )}
                            {group.pinned ? "取消置顶" : "置顶"}
                          </DropdownMenuItem>
                          <DropdownMenuItem onClick={() => openGroupNameDialog(group)}>
                            <Pencil className="mr-2 h-3.5 w-3.5" />
                            编辑名称
                          </DropdownMenuItem>
                          <DropdownMenuItem onClick={() => openGroupInstructionDialog(group)}>
                            <MessageSquareText className="mr-2 h-3.5 w-3.5" />
                            {group.instruction ? "编辑指令" : "添加指令"}
                          </DropdownMenuItem>
                          <DropdownMenuItem onClick={() => openGroupKbDialog(group)}>
                            <BookOpen className="mr-2 h-3.5 w-3.5" />
                            添加知识库
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onClick={() => setGroupDeleteTarget(group)}
                            className="text-destructive focus:text-destructive"
                          >
                            <Trash2 className="mr-2 h-3.5 w-3.5" />
                            删除分组
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </div>
                    {expanded ? (
                      <div className="mt-0.5">
                        {items.map((session) => renderSessionRow(session))}
                        {items.length === 0 ? (
                          <p className="px-3 py-1.5 text-[12px]" style={{ color: "var(--color-text-quaternary)" }}>
                            {searchActive ? "无匹配对话" : "暂无对话"}
                          </p>
                        ) : null}
                      </div>
                    ) : null}
                  </div>
                );
              })}
              {/* 未分组会话：按时间分桶 */}
              {groupedSessions.map((group, index) => (
                <div key={group.label} className={cn(index === 0 && visibleGroups.length === 0 ? "" : "mt-3")}>
                  <p
                    className="mb-1 px-3 text-[12px] font-semibold uppercase tracking-wider"
                    style={{ color: "var(--color-text-tertiary)" }}
                  >
                    {group.label}
                  </p>
                  {group.items.map((session) => renderSessionRow(session))}
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Bottom: user menu + theme toggle */}
        <div
          className="border-t px-3 py-3"
          style={{ borderColor: "var(--color-border-secondary)" }}
        >
          <div className="flex w-full items-center gap-1">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex min-w-0 flex-1 items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition-colors"
                  style={{ color: "var(--color-text)" }}
                  aria-label="用户菜单"
                >
                  <div
                    className="flex h-8 w-8 items-center justify-center overflow-hidden rounded-full"
                    style={{
                      background: "var(--color-fill-quaternary)",
                      color: "hsl(var(--primary))"
                    }}
                  >
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
                  <MoreHorizontal
                    className="h-3.5 w-3.5 shrink-0"
                    style={{ color: "var(--color-text-tertiary)" }}
                  />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" side="top" sideOffset={8} className="w-44">
                <DropdownMenuItem
                  onClick={() => logout()}
                  className="text-destructive focus:text-destructive"
                >
                  <LogOut className="mr-2 h-4 w-4" />
                  退出登录
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            <ThemeToggle className="rounded-lg" />
          </div>
        </div>
      </aside>
      <AlertDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => {
          if (!open) {
            setDeleteTarget(null);
          }
        }}
      >
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
      {/* 删除分组确认（级联删除组内对话） */}
      <AlertDialog
        open={Boolean(groupDeleteTarget)}
        onOpenChange={(open) => {
          if (!open) {
            setGroupDeleteTarget(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除该分组？</AlertDialogTitle>
            <AlertDialogDescription>
              「{groupDeleteTarget?.name || "该分组"}
              」及组内 {sessionsByGroup.get(groupDeleteTarget?.id ?? "")?.length ?? 0}
              个对话、全部消息记录将被永久删除，无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              disabled={groupDeleting}
              onClick={(event) => {
                event.preventDefault();
                handleGroupDelete();
              }}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {groupDeleting ? "删除中..." : "删除"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {/* 新建分组：仅设置名称 */}
      <Dialog
        open={groupCreateOpen}
        onOpenChange={(open) => {
          if (!open) setGroupCreateOpen(false);
        }}
      >
        <DialogContent className="max-w-[420px]">
          <DialogHeader>
            <DialogTitle>新建分组</DialogTitle>
            <DialogDescription>创建分组来归类整理对话</DialogDescription>
          </DialogHeader>
          <div className="py-1">
            <Label htmlFor="group-create-name">分组名称</Label>
            <Input
              id="group-create-name"
              value={groupCreateName}
              onChange={(event) => setGroupCreateName(event.target.value)}
              placeholder="如：灵感库 / 工作清单"
              maxLength={64}
              className="mt-1.5"
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  submitCreateGroup().catch(() => null);
                }
              }}
            />
          </div>
          <DialogFooter>
            <button
              type="button"
              className="rounded-lg border px-4 py-2 text-[13px] font-medium transition-colors hover:bg-[var(--color-fill-quaternary)]"
              style={{ borderColor: "var(--color-border-secondary)", color: "var(--color-text-secondary)" }}
              onClick={() => setGroupCreateOpen(false)}
            >
              取消
            </button>
            <button
              type="button"
              disabled={!groupCreateName.trim() || groupCreating}
              className="rounded-lg px-4 py-2 text-[13px] font-semibold text-white transition-all hover:opacity-90 disabled:opacity-40"
              style={{ background: "hsl(var(--primary))" }}
              onClick={() => submitCreateGroup().catch(() => null)}
            >
              {groupCreating ? "创建中..." : "创建"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      {/* 编辑分组名称 */}
      <Dialog
        open={Boolean(groupNameDialog)}
        onOpenChange={(open) => {
          if (!open) setGroupNameDialog(null);
        }}
      >
        <DialogContent className="max-w-[420px]">
          <DialogHeader>
            <DialogTitle>编辑名称</DialogTitle>
          </DialogHeader>
          <div className="py-1">
            <Label htmlFor="group-rename-input">分组名称</Label>
            <Input
              id="group-rename-input"
              value={groupNameDialog?.name || ""}
              onChange={(event) =>
                setGroupNameDialog((prev) => (prev ? { ...prev, name: event.target.value } : prev))
              }
              maxLength={64}
              className="mt-1.5"
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  submitGroupName().catch(() => null);
                }
              }}
            />
          </div>
          <DialogFooter>
            <button
              type="button"
              className="rounded-lg border px-4 py-2 text-[13px] font-medium transition-colors hover:bg-[var(--color-fill-quaternary)]"
              style={{ borderColor: "var(--color-border-secondary)", color: "var(--color-text-secondary)" }}
              onClick={() => setGroupNameDialog(null)}
            >
              取消
            </button>
            <button
              type="button"
              disabled={!groupNameDialog?.name.trim() || groupRenaming}
              className="rounded-lg px-4 py-2 text-[13px] font-semibold text-white transition-all hover:opacity-90 disabled:opacity-40"
              style={{ background: "hsl(var(--primary))" }}
              onClick={() => submitGroupName().catch(() => null)}
            >
              {groupRenaming ? "保存中..." : "保存"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      {/* 添加/编辑分组指令 */}
      <Dialog
        open={Boolean(groupInstructionDialog)}
        onOpenChange={(open) => {
          if (!open) setGroupInstructionDialog(null);
        }}
      >
        <DialogContent className="max-w-[460px]">
          <DialogHeader>
            <DialogTitle>
              {groupInstructionDialog?.hasInstruction ? "编辑指令" : "添加指令"}
            </DialogTitle>
            <DialogDescription>
              为「{groupInstructionDialog?.name || "该分组"}」设定回答风格，组内对话将自动遵循该指令
            </DialogDescription>
          </DialogHeader>
          <div className="py-1">
            <Label htmlFor="group-instruction-input">分组指令</Label>
            <Textarea
              id="group-instruction-input"
              value={groupInstructionDialog?.instruction || ""}
              onChange={(event) =>
                setGroupInstructionDialog((prev) =>
                  prev ? { ...prev, instruction: event.target.value } : prev
                )
              }
              placeholder="如：全程使用英文回复 / 以周报格式输出"
              rows={5}
              maxLength={2000}
              className="mt-1.5"
            />
            <p className="mt-1.5 text-[12px]" style={{ color: "var(--color-text-quaternary)" }}>
              清空并保存可移除指令
            </p>
          </div>
          <DialogFooter>
            <button
              type="button"
              className="rounded-lg border px-4 py-2 text-[13px] font-medium transition-colors hover:bg-[var(--color-fill-quaternary)]"
              style={{ borderColor: "var(--color-border-secondary)", color: "var(--color-text-secondary)" }}
              onClick={() => setGroupInstructionDialog(null)}
            >
              取消
            </button>
            <button
              type="button"
              disabled={groupInstructionSaving}
              className="rounded-lg px-4 py-2 text-[13px] font-semibold text-white transition-all hover:opacity-90 disabled:opacity-40"
              style={{ background: "hsl(var(--primary))" }}
              onClick={() => submitGroupInstruction().catch(() => null)}
            >
              {groupInstructionSaving ? "保存中..." : "保存"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      {/* 添加知识库（分组内对话默认选中） */}
      <Dialog
        open={Boolean(groupKbDialog)}
        onOpenChange={(open) => {
          if (!open) setGroupKbDialog(null);
        }}
      >
        <DialogContent className="max-w-[460px] overflow-hidden">
          <DialogHeader className="min-w-0">
            <DialogTitle>添加知识库</DialogTitle>
            <DialogDescription>
              「{groupKbDialog?.name || "该分组"}」内的对话将默认选中勾选的知识库，可在对话中手动增删
            </DialogDescription>
          </DialogHeader>
          {/* min-w-0 解除 grid item 最小宽度约束，防止长描述把对话框撑开 */}
          <div className="min-w-0 py-1">
            <div className="relative">
              <Search
                className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2"
                style={{ color: "var(--color-text-tertiary)" }}
              />
              <input
                value={kbSearch}
                onChange={(event) => setKbSearch(event.target.value)}
                placeholder="搜索知识库..."
                className="h-9 w-full rounded-xl border pl-9 pr-3 text-[14px] transition-all duration-200 focus:outline-none"
                style={{
                  borderColor: "var(--color-border-secondary)",
                  background: "var(--color-bg-container-secondary)",
                  color: "var(--color-text)"
                }}
              />
            </div>
            <div className="mt-2 max-h-64 space-y-1 overflow-x-hidden overflow-y-auto">
              {kbLoading ? (
                <div className="flex h-20 items-center justify-center" style={{ color: "var(--color-text-tertiary)" }}>
                  <Loading label="加载知识库" />
                </div>
              ) : kbOptions.length === 0 ? (
                <p className="py-6 text-center text-[13px]" style={{ color: "var(--color-text-tertiary)" }}>
                  暂无知识库
                </p>
              ) : (
                kbOptions
                  .filter((kb) => {
                    const q = kbSearch.trim().toLowerCase();
                    return !q || kb.name.toLowerCase().includes(q);
                  })
                  .map((kb) => {
                    const checked = kbSelected.has(kb.id);
                    return (
                      <label
                        key={kb.id}
                        className="flex min-w-0 cursor-pointer items-start gap-2.5 rounded-lg px-2.5 py-2 transition-colors hover:bg-[var(--color-fill-quaternary)]"
                      >
                        <Checkbox
                          className="mt-0.5 shrink-0 rounded-[4px]"
                          checked={checked}
                          onCheckedChange={() => toggleKbSelected(kb.id)}
                        />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-[14px]" style={{ color: "var(--color-text)" }}>
                            {kb.name}
                          </span>
                          {kb.description ? (
                            <span
                              className="mt-0.5 block truncate text-[12px]"
                              style={{ color: "var(--color-text-tertiary)" }}
                            >
                              {kb.description}
                            </span>
                          ) : null}
                        </span>
                      </label>
                    );
                  })
              )}
            </div>
          </div>
          <DialogFooter>
            <button
              type="button"
              disabled={kbSaving || kbSelected.size === 0}
              className="mr-auto rounded-lg border px-4 py-2 text-[13px] font-medium transition-colors hover:bg-[var(--color-fill-quaternary)] disabled:opacity-40"
              style={{ borderColor: "var(--color-border-secondary)", color: "var(--color-text-secondary)" }}
              onClick={() => clearGroupKbs().catch(() => null)}
            >
              清除全部
            </button>
            <button
              type="button"
              className="rounded-lg border px-4 py-2 text-[13px] font-medium transition-colors hover:bg-[var(--color-fill-quaternary)]"
              style={{ borderColor: "var(--color-border-secondary)", color: "var(--color-text-secondary)" }}
              onClick={() => setGroupKbDialog(null)}
            >
              取消
            </button>
            <button
              type="button"
              disabled={kbSaving}
              className="rounded-lg px-4 py-2 text-[13px] font-semibold text-white transition-all hover:opacity-90 disabled:opacity-40"
              style={{ background: "hsl(var(--primary))" }}
              onClick={() => submitGroupKbs().catch(() => null)}
            >
              {kbSaving ? "保存中..." : `保存${kbSelected.size > 0 ? `（已选 ${kbSelected.size}）` : ""}`}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
