import { useCallback, useEffect, useRef, useState } from "react";
import { Database, FileBarChart, FolderOpen, Layers, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { useNavigate, useSearchParams } from "react-router-dom";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";

import type { KnowledgeBase, PageResult } from "@/services/knowledgeService";
import { deleteKnowledgeBase, getKnowledgeBasesPage, updateKnowledgeBase } from "@/services/knowledgeService";
import { CreateKnowledgeBaseDialog } from "@/components/admin/CreateKnowledgeBaseDialog";
import { getErrorMessage } from "@/utils/error";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 10;
const STATS_PAGE_SIZE = 200;

export function KnowledgeListPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const nameFromQuery = searchParams.get("name") || "";
  const [pageData, setPageData] = useState<PageResult<KnowledgeBase> | null>(null);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<KnowledgeBase | null>(null);
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const [searchName, setSearchName] = useState(nameFromQuery);
  const [keyword, setKeyword] = useState(nameFromQuery);
  const [pageNo, setPageNo] = useState(1);
  const [renameDialog, setRenameDialog] = useState<{ open: boolean; kb: KnowledgeBase | null }>({
    open: false,
    kb: null
  });
  const [renameValue, setRenameValue] = useState("");
  const [editDescription, setEditDescription] = useState("");
  const [stats, setStats] = useState({
    totalCount: 0,
    documentCount: 0,
    activeCount: 0,
    creatorCount: 0
  });
  const [statsLoading, setStatsLoading] = useState(true);
  const statsRequestId = useRef(0);

  const knowledgeBases = pageData?.records || [];

  const loadKnowledgeBases = async (current = pageNo, name = keyword) => {
    try {
      setLoading(true);
      const data = await getKnowledgeBasesPage(current, PAGE_SIZE, name || undefined);
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载知识库列表失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const loadStats = useCallback(async (name = keyword) => {
    const requestId = ++statsRequestId.current;
    const normalized = name.trim();
    setStatsLoading(true);
    try {
      const firstPage = await getKnowledgeBasesPage(1, STATS_PAGE_SIZE, normalized || undefined);
      if (statsRequestId.current !== requestId) return;

      let documentTotal = 0;
      let activeTotal = 0;
      const creatorNames = new Set<string>();
      const addRecords = (records: KnowledgeBase[] = []) => {
        records.forEach((kb) => {
          const docCount = kb.documentCount ?? 0;
          documentTotal += docCount;
          if (docCount > 0) {
            activeTotal += 1;
          }
          if (kb.createdBy) {
            creatorNames.add(kb.createdBy);
          }
        });
      };

      addRecords(firstPage.records || []);

      const totalCount = firstPage.total ?? (firstPage.records?.length || 0);
      const totalPages =
        firstPage.pages || Math.max(1, Math.ceil(totalCount / STATS_PAGE_SIZE));

      for (let page = 2; page <= totalPages; page += 1) {
        const nextPage = await getKnowledgeBasesPage(page, STATS_PAGE_SIZE, normalized || undefined);
        if (statsRequestId.current !== requestId) return;
        addRecords(nextPage.records || []);
      }

      if (statsRequestId.current !== requestId) return;
      setStats({
        totalCount,
        documentCount: documentTotal,
        activeCount: activeTotal,
        creatorCount: creatorNames.size
      });
    } catch (error) {
      if (statsRequestId.current !== requestId) return;
      console.error(error);
      setStats({
        totalCount: 0,
        documentCount: 0,
        activeCount: 0,
        creatorCount: 0
      });
    } finally {
      if (statsRequestId.current === requestId) {
        setStatsLoading(false);
      }
    }
  }, [keyword]);

  useEffect(() => {
    loadKnowledgeBases();
  }, [pageNo, keyword]);

  useEffect(() => {
    loadStats(keyword);
  }, [keyword, loadStats]);

  useEffect(() => {
    const trimmed = nameFromQuery.trim();
    if (trimmed !== keyword) {
      setSearchName(trimmed);
      setKeyword(trimmed);
      setPageNo(1);
    }
  }, [nameFromQuery, keyword]);

  useEffect(() => {
    if (renameDialog.open) {
      setRenameValue(renameDialog.kb?.name || "");
      setEditDescription(renameDialog.kb?.description || "");
    }
  }, [renameDialog]);

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchName.trim());
  };

  const handleRefresh = () => {
    setPageNo(1);
    loadKnowledgeBases(1, keyword);
    loadStats(keyword);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;

    try {
      await deleteKnowledgeBase(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      setPageNo(1);
      await loadKnowledgeBases(1, keyword);
      await loadStats(keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    } finally {
      setDeleteTarget(null);
    }
  };

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return "-";
    return new Date(dateStr).toLocaleString("zh-CN");
  };

  const formatStatValue = (value: number) => {
    if (statsLoading) return "--";
    return value.toLocaleString("zh-CN");
  };

  const renderEmbeddingModel = (model?: string) => {
    if (!model) return "-";
    const parts = model.split("-");
    if (parts.length < 2) {
      return <span className="text-sm text-gray-700">{model}</span>;
    }
    const head = parts.slice(0, -1).join("-");
    const tail = parts[parts.length - 1];
    return (
      <div className="flex flex-col text-xs text-gray-500">
        <span className="font-medium text-gray-700">{head}</span>
        <span>{tail}</span>
      </div>
    );
  };

  const getCollectionBadgeClass = () => {
    return "border-blue-200 bg-blue-50 text-blue-700";
  };

  const handleEditSave = async () => {
    if (!renameDialog.kb) return;
    const nextName = renameValue.trim();
    if (!nextName) {
      toast.error("请输入知识库名称");
      return;
    }
    const nextDesc = editDescription.trim();
    try {
      await updateKnowledgeBase(renameDialog.kb.id, {
        name: nextName,
        description: nextDesc
      });
      toast.success("保存成功");
      setRenameDialog({ open: false, kb: null });
      await loadKnowledgeBases(pageNo, keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
      console.error(error);
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1 className="admin-page-title">知识库管理</h1>
        <div className="admin-page-actions">
          <Input value={searchName} onChange={(e) => setSearchName(e.target.value)} placeholder="搜索知识库名称" className="w-[260px]" />
          <Button variant="outline" onClick={handleSearch}>搜索</Button>
          <Button variant="outline" onClick={handleRefresh}><RefreshCw className="h-4 w-4 mr-1.5" />刷新</Button>
          <Button className="admin-primary-gradient" onClick={() => setCreateDialogOpen(true)}><Plus className="h-4 w-4 mr-1.5" />新建</Button>
        </div>
      </div>

      <div className="admin-stat-grid">
        {[
          { label: "知识库", value: stats.totalCount, icon: Database },
          { label: "文档数", value: stats.documentCount, icon: FileBarChart },
          { label: "含文档", value: stats.activeCount, icon: FolderOpen },
          { label: "创建者", value: stats.creatorCount, icon: Layers }
        ].map((item) => {
          const Icon = item.icon;
          return (
            <div key={item.label} className="admin-stat-card">
              <div className="flex items-center gap-2.5">
                <div className="admin-stat-icon"><Icon className="h-4 w-4" /></div>
                <div>
                  <div className="admin-stat-label">{item.label}</div>
                  <div className="admin-stat-value">{formatStatValue(item.value)}</div>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <Card>
        <CardContent className="p-0">
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : knowledgeBases.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              暂无知识库，点击上方按钮创建
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>名称</TableHead>
                  <TableHead>Embedding模型</TableHead>
                  <TableHead>Collection</TableHead>
                  <TableHead className="text-center w-[70px] whitespace-nowrap">文档数</TableHead>
                  <TableHead className="w-[90px] whitespace-nowrap">负责人</TableHead>
                  <TableHead className="w-[150px]">创建时间</TableHead>
                  <TableHead className="w-[50px] text-center">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {knowledgeBases.map((kb) => (
                  <TableRow key={kb.id}>
                    <TableCell className="font-medium">
                      <button
                        type="button"
                        className="admin-link max-w-[200px] truncate"
                        onClick={() => navigate(`/admin/knowledge/${kb.id}`)}
                      >
                        {kb.name}
                      </button>
                    </TableCell>
                    <TableCell>
                      {renderEmbeddingModel(kb.embeddingModel)}
                    </TableCell>
                    <TableCell>
                      {kb.collectionName ? (
                        <Badge
                          variant="outline"
                          className={cn("px-3 py-1 whitespace-nowrap", getCollectionBadgeClass(kb.collectionName))}
                        >
                          {kb.collectionName}
                        </Badge>
                      ) : (
                        "-"
                      )}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">{kb.documentCount ?? "-"}</TableCell>
                    <TableCell className="whitespace-nowrap">{kb.createdBy || "-"}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDate(kb.createTime)}
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        <Button variant="outline" size="sm" onClick={() => setRenameDialog({ open: true, kb })}>
                          <Pencil className="h-3.5 w-3.5" />
                        </Button>
                        <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" onClick={() => setDeleteTarget(kb)}>
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

      {/* 删除确认对话框 */}
      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              知识库删除后当前不提供恢复入口。确定要继续吗？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Dialog open={renameDialog.open} onOpenChange={(open) => setRenameDialog({ open, kb: open ? renameDialog.kb : null })}>
        <DialogContent className="sm:max-w-[460px]" onOpenAutoFocus={(e) => e.preventDefault()} onCloseAutoFocus={(e) => { e.preventDefault(); requestAnimationFrame(() => (document.activeElement as HTMLElement)?.blur()); }}>
          <DialogHeader>
            <DialogTitle>编辑知识库</DialogTitle>
            <DialogDescription>修改知识库名称和描述</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1.5">
              <label className="text-sm font-medium">名称</label>
              <Input value={renameValue} onChange={(event) => setRenameValue(event.target.value)} placeholder="知识库名称" />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium">描述</label>
              <Textarea
                value={editDescription}
                onChange={(event) => setEditDescription(event.target.value)}
                placeholder="用于帮助 AI 判断问题是否与知识库相关（可选）"
                className="resize-none"
                rows={3}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenameDialog({ open: false, kb: null })}>
              取消
            </Button>
            <Button onClick={handleEditSave}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {pageData ? (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-gray-500">
          <span>共 {pageData.total} 条</span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}
              disabled={pageData.current <= 1}
            >
              上一页
            </Button>
            <span>
              {pageData.current} / {pageData.pages}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.min(pageData.pages || 1, prev + 1))}
              disabled={pageData.current >= pageData.pages}
            >
              下一页
            </Button>
          </div>
        </div>
      ) : null}

      {/* 创建知识库对话框 */}
      <CreateKnowledgeBaseDialog
        open={createDialogOpen}
        onOpenChange={setCreateDialogOpen}
        onSuccess={() => {
          setPageNo(1);
          loadKnowledgeBases(1, keyword);
          loadStats(keyword);
        }}
      />
    </div>
  );
}
