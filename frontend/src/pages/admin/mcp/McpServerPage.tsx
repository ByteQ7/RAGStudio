import { useCallback, useEffect, useState } from "react";
import {
  AlertCircle,
  CheckCircle2,
  Loader2,
  Pencil,
  Plug,
  Plus,
  Power,
  PowerOff,
  RefreshCw,
  Server,
  Trash2,
  XCircle
} from "lucide-react";
import { toast } from "sonner";

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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import {
  createMcpServer,
  deleteMcpServer,
  listMcpServers,
  reloadMcpServer,
  testMcpConnection,
  toggleMcpServer,
  updateMcpServer,
  type McpServer,
  type McpServerPayload
} from "@/services/mcpServerService";
import { getErrorMessage } from "@/utils/error";

const emptyForm = (): McpServerPayload => ({
  name: "",
  url: "",
  description: "",
  enabled: 1,
  transportType: "streamable_http",
  headers: ""
});

const statusBadge = (status?: string | null) => {
  if (!status) return <Badge variant="outline" className="text-gray-400">未连接</Badge>;
  switch (status) {
    case "connected":
      return (
        <Badge className="bg-emerald-50 text-emerald-600 border-emerald-200 hover:bg-emerald-50">
          <CheckCircle2 className="mr-1 h-3 w-3" />
          已连接
        </Badge>
      );
    case "error":
      return (
        <Badge className="bg-red-50 text-red-500 border-red-200 hover:bg-red-50">
          <XCircle className="mr-1 h-3 w-3" />
          异常
        </Badge>
      );
    default:
      return (
        <Badge variant="outline" className="text-gray-400">
          {status}
        </Badge>
      );
  }
};

export function McpServerPage() {
  const [servers, setServers] = useState<McpServer[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<McpServer | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogMode, setDialogMode] = useState<"create" | "edit">("create");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<McpServerPayload>(emptyForm());
  const [submitting, setSubmitting] = useState(false);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [reloadingId, setReloadingId] = useState<string | null>(null);
  const [togglingId, setTogglingId] = useState<string | null>(null);

  const loadServers = useCallback(async () => {
    try {
      setLoading(true);
      const data = await listMcpServers();
      setServers(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载 MCP Server 列表失败"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadServers();
  }, [loadServers]);

  const openCreate = () => {
    setForm(emptyForm());
    setDialogMode("create");
    setEditingId(null);
    setDialogOpen(true);
  };

  const openEdit = (server: McpServer) => {
    setForm({
      name: server.name,
      url: server.url,
      description: server.description || "",
      enabled: server.enabled,
      transportType: server.transportType,
      headers: server.headers || ""
    });
    setDialogMode("edit");
    setEditingId(server.id);
    setDialogOpen(true);
  };

  const handleSubmit = async () => {
    if (!form.name?.trim()) {
      toast.error("请输入服务名称");
      return;
    }
    if (!form.url?.trim()) {
      toast.error("请输入服务地址");
      return;
    }

    const payload: McpServerPayload = {
      name: form.name.trim(),
      url: form.url.trim(),
      description: form.description?.trim() || null,
      enabled: form.enabled ?? 1,
      transportType: form.transportType || "streamable_http",
      headers: form.headers?.trim() || null
    };

    try {
      setSubmitting(true);
      if (dialogMode === "create") {
        await createMcpServer(payload);
        toast.success("MCP Server 创建成功");
      } else if (editingId) {
        await updateMcpServer(editingId, payload);
        toast.success("MCP Server 更新成功");
      }
      setDialogOpen(false);
      await loadServers();
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteMcpServer(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      await loadServers();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    } finally {
      setDeleteTarget(null);
    }
  };

  const handleToggle = async (server: McpServer) => {
    const nextEnabled = server.enabled !== 1;
    try {
      setTogglingId(server.id);
      await toggleMcpServer(server.id, nextEnabled);
      toast.success(nextEnabled ? "已启用" : "已禁用");
      await loadServers();
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    } finally {
      setTogglingId(null);
    }
  };

  const handleTest = async (server: McpServer) => {
    try {
      setTestingId(server.id);
      const result = await testMcpConnection(server.id);
      if (result.status === "connected") {
        toast.success(`连接正常，发现 ${result.toolCount} 个工具`);
      } else {
        toast.error(`连接异常: ${result.error || "未知错误"}`);
      }
      await loadServers();
    } catch (error) {
      toast.error(getErrorMessage(error, "测试连接失败"));
    } finally {
      setTestingId(null);
    }
  };

  const handleReload = async (server: McpServer) => {
    try {
      setReloadingId(server.id);
      const result = await reloadMcpServer(server.id);
      if (result.success) {
        toast.success(`重新连接成功，注册 ${result.toolCount} 个工具`);
      } else {
        toast.error(`重新连接失败: ${result.error || "未知错误"}`);
      }
      await loadServers();
    } catch (error) {
      toast.error(getErrorMessage(error, "重新加载失败"));
    } finally {
      setReloadingId(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-900">MCP 服务管理</h1>
          <p className="mt-1 text-sm text-gray-500">
            管理外部 MCP Server 连接，支持运行时动态增删改
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={loadServers} disabled={loading}>
            <RefreshCw className={cn("mr-1.5 h-4 w-4", loading && "animate-spin")} />
            刷新
          </Button>
          <Button size="sm" onClick={openCreate}>
            <Plus className="mr-1.5 h-4 w-4" />
            新增 MCP Server
          </Button>
        </div>
      </div>

      {/* Table */}
      <Card>
        <CardContent className="px-4 py-0">
          {loading && servers.length === 0 ? (
            <div className="flex items-center justify-center py-20 text-gray-400">
              <Loader2 className="mr-2 h-5 w-5 animate-spin" />
              加载中...
            </div>
          ) : servers.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 text-gray-400">
              <Server className="h-10 w-10" strokeWidth={1.5} />
              <p className="mt-3 text-sm">暂无 MCP Server 配置</p>
              <Button variant="outline" size="sm" className="mt-4" onClick={openCreate}>
                <Plus className="mr-1.5 h-4 w-4" />
                添加第一个
              </Button>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[220px] px-5">名称</TableHead>
                  <TableHead className="px-5">服务地址</TableHead>
                  <TableHead className="w-[110px] px-5 text-center">状态</TableHead>
                  <TableHead className="w-[90px] px-5 text-center">工具数</TableHead>
                  <TableHead className="w-[100px] px-5 text-center">启用</TableHead>
                  <TableHead className="w-[280px] px-5 text-center">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {servers.map((server) => (
                  <TableRow key={server.id}>
                    <TableCell className="px-5 py-3.5">
                      <div>
                        <span className="font-medium text-gray-900">{server.name}</span>
                        {server.description && (
                          <p className="mt-0.5 truncate text-xs text-gray-400 max-w-[200px]">
                            {server.description}
                          </p>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="px-5 py-3.5">
                      <code className="rounded bg-gray-50 px-1.5 py-0.5 text-xs text-gray-600">
                        {server.url}
                      </code>
                    </TableCell>
                    <TableCell className="px-5 py-3.5 text-center">
                      {statusBadge(server.lastStatus)}
                    </TableCell>
                    <TableCell className="px-5 py-3.5 text-center">
                      <span className="text-sm font-medium text-gray-700">
                        {server.toolCount ?? 0}
                      </span>
                    </TableCell>
                    <TableCell className="px-5 py-3.5 text-center">
                      <button
                        onClick={() => handleToggle(server)}
                        disabled={togglingId === server.id}
                        className={cn(
                          "inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium transition-colors",
                          server.enabled === 1
                            ? "bg-emerald-50 text-emerald-600 hover:bg-emerald-100"
                            : "bg-gray-100 text-gray-500 hover:bg-gray-200"
                        )}
                      >
                        {togglingId === server.id ? (
                          <Loader2 className="h-3 w-3 animate-spin" />
                        ) : server.enabled === 1 ? (
                          <Power className="h-3 w-3" />
                        ) : (
                          <PowerOff className="h-3 w-3" />
                        )}
                        {server.enabled === 1 ? "启用" : "禁用"}
                      </button>
                    </TableCell>
                    <TableCell className="px-5 py-3.5 text-center">
                      <div className="flex items-center justify-center gap-2">
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-8 px-2 text-xs"
                          onClick={() => handleTest(server)}
                          disabled={testingId === server.id}
                          title="测试连接"
                        >
                          {testingId === server.id ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : (
                            <Plug className="h-3.5 w-3.5" />
                          )}
                          <span className="ml-1">测试</span>
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-8 px-2 text-xs"
                          onClick={() => handleReload(server)}
                          disabled={reloadingId === server.id}
                          title="重新加载"
                        >
                          {reloadingId === server.id ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : (
                            <RefreshCw className="h-3.5 w-3.5" />
                          )}
                          <span className="ml-1">重载</span>
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-8 px-2 text-xs"
                          onClick={() => openEdit(server)}
                          title="编辑"
                        >
                          <Pencil className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-8 px-2 text-xs text-red-500 hover:text-red-600 hover:bg-red-50"
                          onClick={() => setDeleteTarget(server)}
                          title="删除"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Error hint for lastError */}
      {servers.some((s) => s.lastError) && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 p-4">
          <div className="flex items-start gap-2">
            <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-500" />
            <div className="text-sm text-amber-700">
              <p className="font-medium">部分服务连接异常：</p>
              {servers
                .filter((s) => s.lastError)
                .map((s) => (
                  <p key={s.id} className="mt-1">
                    <span className="font-medium">{s.name}</span>：{s.lastError}
                  </p>
                ))}
            </div>
          </div>
        </div>
      )}

      {/* Create / Edit Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-[640px]">
          <DialogHeader>
            <DialogTitle>
              {dialogMode === "create" ? "新增 MCP Server" : "编辑 MCP Server"}
            </DialogTitle>
            <DialogDescription>
              {dialogMode === "create"
                ? "添加一个外部 MCP Server，启用后将自动连接并注册远程工具。"
                : "修改配置后会自动重新连接。"}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-2">
            <div className="grid gap-2">
              <label className="text-sm font-medium text-gray-700">
                服务名称 <span className="text-red-400">*</span>
              </label>
              <Input
                placeholder="如：weather-service"
                value={form.name || ""}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
              />
            </div>
            <div className="grid gap-2">
              <label className="text-sm font-medium text-gray-700">
                服务地址 <span className="text-red-400">*</span>
              </label>
              <Input
                placeholder="如：http://localhost:9099/mcp"
                value={form.url || ""}
                onChange={(e) => setForm({ ...form, url: e.target.value })}
              />
              <p className="text-xs text-gray-400">
                MCP Server 的 HTTP 端点地址，系统会自动追加 /mcp 后缀
              </p>
            </div>
            <div className="grid gap-2">
              <label className="text-sm font-medium text-gray-700">描述</label>
              <Textarea
                placeholder="可选，简要说明该服务的用途"
                rows={3}
                value={form.description || ""}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
              />
            </div>
            <div className="grid gap-2">
              <label className="text-sm font-medium text-gray-700">自定义请求头</label>
              <Textarea
                placeholder='可选，JSON 格式，如：{"Authorization": "Bearer xxx"}'
                rows={3}
                value={form.headers || ""}
                onChange={(e) => setForm({ ...form, headers: e.target.value })}
              />
              <p className="text-xs text-gray-400">
                用于需要鉴权的外部 MCP Server，留空表示无需自定义请求头
              </p>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleSubmit} disabled={submitting}>
              {submitting && <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />}
              {dialogMode === "create" ? "创建" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation */}
      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => {
        if (!open) setDeleteTarget(null);
      }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除？</AlertDialogTitle>
            <AlertDialogDescription>
              将删除 MCP Server [{deleteTarget?.name}]，同时断开连接并注销其所有工具。此操作不可恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700"
              onClick={handleDelete}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
