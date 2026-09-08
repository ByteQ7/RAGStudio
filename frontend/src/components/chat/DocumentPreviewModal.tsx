import { lazy, Suspense, useEffect, useRef, useState } from "react";
import { Download, Eye, FileIcon, ListTree, Loader2, X } from "lucide-react";
import DOMPurify from "dompurify";

import { ErrorBoundary } from "@/components/common/ErrorBoundary";
import { getDocumentPreviewUrl, getDocumentBinaryContent, type PreviewData } from "@/services/previewService";

// Markdown 富渲染组件较重（katex/mermaid/syntax-highlighter），随预览弹窗懒加载
const MarkdownRenderer = lazy(() =>
  import("@/components/chat/MarkdownRenderer").then((m) => ({ default: m.MarkdownRenderer }))
);

const MARKDOWN_FILE_TYPES = ["markdown", "md"];

// 超过该大小的 Markdown 不做富渲染，直接回退文本预览，防止大文档卡死页面
const MD_RENDER_SIZE_LIMIT = 2 * 1024 * 1024;

interface TocItem {
  id: string;
  text: string;
  level: number;
}

interface DocumentPreviewModalProps {
  docId: string;
  docName: string;
  fileType: string;
  open: boolean;
  onClose: () => void;
}

export function DocumentPreviewModal({ docId, docName, fileType, open, onClose }: DocumentPreviewModalProps) {
  const [previewData, setPreviewData] = useState<PreviewData | null>(null);
  const [htmlContent, setHtmlContent] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingLabel, setLoadingLabel] = useState("生成预览链接...");
  const [error, setError] = useState<string | null>(null);
  const officeContainerRef = useRef<HTMLDivElement>(null);
  const [mdContent, setMdContent] = useState<string | null>(null);
  const [mdFailed, setMdFailed] = useState(false);
  const [mdOversize, setMdOversize] = useState(false);
  const [viewMode, setViewMode] = useState<"rendered" | "source">("rendered");
  const [tocItems, setTocItems] = useState<TocItem[]>([]);
  const [activeTocId, setActiveTocId] = useState<string | null>(null);
  const mdScrollRef = useRef<HTMLDivElement>(null);
  const tocSignatureRef = useRef("");

  useEffect(() => {
    if (!open) {
      setPreviewData(null);
      setHtmlContent(null);
      setMdContent(null);
      setMdFailed(false);
      setMdOversize(false);
      setViewMode("rendered");
      setTocItems([]);
      setActiveTocId(null);
      setLoading(false);
      setError(null);
      return;
    }

    const isOffice = ["docx", "xlsx", "pptx", "odt", "ods", "odp", "xls", "ppt"].includes(fileType);

    setMdContent(null);
    setMdFailed(false);
    setMdOversize(false);
    setViewMode("rendered");
    setTocItems([]);
    setActiveTocId(null);
    setLoadingLabel("生成预览链接...");
    setLoading(true);
    setError(null);

    getDocumentPreviewUrl(docId)
      .then(async (data) => {
        setPreviewData(data);

        // Office 文件：通过后端代理获取内容，用客户端库转换
        if (isOffice) {
          setLoadingLabel("加载文档内容...");
          const buffer = await getDocumentBinaryContent(docId);
          setLoadingLabel("转换文档格式...");
          await renderOffice(fileType, buffer);
          return;
        }

        // Markdown 文件：通过后端代理获取文本，交给 MarkdownRenderer 富渲染
        if (MARKDOWN_FILE_TYPES.includes(fileType)) {
          if (data.fileSize > MD_RENDER_SIZE_LIMIT) {
            setMdOversize(true);
            return;
          }
          setLoadingLabel("加载 Markdown 内容...");
          try {
            const buffer = await getDocumentBinaryContent(docId);
            // fatal 模式下内容不是合法 UTF-8（如二进制冒充 .md）会抛错，走文本预览回退
            const text = new TextDecoder("utf-8", { fatal: true }).decode(buffer);
            setMdContent(text);
          } catch {
            setMdFailed(true);
          }
        }
      })
      .catch((err) => setError(err?.message || "获取预览失败"))
      .finally(() => setLoading(false));
  }, [docId, fileType, open]);

  // 从渲染后的 DOM 提取标题构建左侧目录。
  // 不从源码正则解析：MarkdownRenderer 会对畸形标题做预处理修正（**# x**、##x 等），
  // 只有真实 DOM 能保证目录与实际渲染出的标题一一对应。
  useEffect(() => {
    const isMd = MARKDOWN_FILE_TYPES.includes(fileType);
    if (!isMd || !mdContent || mdFailed || mdOversize || viewMode !== "rendered") {
      setTocItems([]);
      setActiveTocId(null);
      tocSignatureRef.current = "";
      return;
    }
    const container = mdScrollRef.current;
    if (!container) return;
    let cancelled = false;

    const collect = () => {
      const headings = Array.from(
        container.querySelectorAll<HTMLElement>("h1, h2, h3, h4, h5, h6")
      ).filter((el) => (el.textContent ?? "").trim());
      const signature = headings.map((el) => el.textContent).join("¦");
      if (signature === tocSignatureRef.current) return;
      tocSignatureRef.current = signature;
      if (!headings.length) {
        setTocItems([]);
        setActiveTocId(null);
        return;
      }
      headings.forEach((el, i) => {
        el.id = `md-toc-${i}`;
      });
      setTocItems(
        headings.map((el, i) => ({
          id: `md-toc-${i}`,
          text: (el.textContent ?? "").trim(),
          level: Number(el.tagName.slice(1)),
        }))
      );
    };

    // 首次 collect 可能落在 Suspense 懒加载完成前，MutationObserver 兜底补采
    collect();
    const observer = new MutationObserver(() => {
      if (!cancelled) collect();
    });
    observer.observe(container, { childList: true, subtree: true });
    return () => {
      cancelled = true;
      observer.disconnect();
    };
  }, [fileType, mdContent, mdFailed, mdOversize, viewMode]);

  // 滚动高亮：以内容区顶部 88px 为阈值，取"最后一个已滚过"的标题
  useEffect(() => {
    const container = mdScrollRef.current;
    if (!container || tocItems.length === 0) return;

    const updateActive = () => {
      if (container.scrollTop + container.clientHeight >= container.scrollHeight - 4) {
        setActiveTocId(tocItems[tocItems.length - 1].id);
        return;
      }
      const containerTop = container.getBoundingClientRect().top;
      let active = tocItems[0].id;
      for (const item of tocItems) {
        const el = document.getElementById(item.id);
        if (!el) continue;
        if (el.getBoundingClientRect().top - containerTop <= 88) {
          active = item.id;
        } else {
          break;
        }
      }
      setActiveTocId(active);
    };

    updateActive();
    container.addEventListener("scroll", updateActive, { passive: true });
    return () => container.removeEventListener("scroll", updateActive);
  }, [tocItems]);

  function scrollToTocHeading(id: string) {
    const el = document.getElementById(id);
    const container = mdScrollRef.current;
    if (!el || !container) return;
    const top =
      el.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop - 16;
    container.scrollTo({ top: Math.max(0, top), behavior: "smooth" });
  }

  async function renderOffice(ft: string, buffer: ArrayBuffer) {
    switch (ft) {
      case "docx": {
        const mammoth = await import("mammoth");
        const result = await mammoth.default.convertToHtml({ arrayBuffer: buffer });
        setHtmlContent(result.value);
        break;
      }
      case "xlsx":
      case "xls":
      case "ods": {
        const XLSX = await import("xlsx");
        const workbook = XLSX.read(buffer, { type: "array" });
        const sheets = workbook.SheetNames.map((name) => {
          const html = XLSX.utils.sheet_to_html(workbook.Sheets[name]);
          return { name, html };
        });
        const allHtml = sheets
          .map((s) => `<h3 style='margin-bottom:8px'>${s.name}</h3>${s.html}`)
          .join("<hr style='margin:16px 0'>");
        setHtmlContent(`<div class='xlsx-preview'>${allHtml}</div>`);
        break;
      }
      case "pptx":
      case "ppt": {
        const { renderPptx } = await import("@/utils/pptxPreview");
        if (officeContainerRef.current) {
          await renderPptx(buffer, officeContainerRef.current);
        }
        break;
      }
      case "odt":
      case "odp": {
        const { parseOffice, generate } = await import("officeparser/slim");
        console.log("[ODT] parsing with officeparser...", { fileType: ft });
        const ast = await parseOffice(buffer, { fileType: ft, extractAttachments: true });
        console.log("[ODT] parse done, content length:", ast?.content?.length);
        const htmlResult = await generate(ast, "html", { htmlConfig: { standalone: false } });
        console.log("[ODT] html generate done, value type:", typeof htmlResult?.value, "length:", htmlResult?.value?.length);
        const html = htmlResult?.value;
        if (html && typeof html === "string") {
          setHtmlContent(html);
        } else {
          console.warn("[ODT] HTML output empty, falling back to text");
          const textResult = await generate(ast, "text");
          const text = textResult?.value;
          if (!text || typeof text !== "string") throw new Error("未能从文档中提取到文本内容");
          setHtmlContent(`<pre style="white-space:pre-wrap;font-family:monospace;font-size:13px;line-height:1.6">${text.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}</pre>`);
        }
        break;
      }
    }
  }

  const isImage = ["png", "jpg", "jpeg", "gif", "webp"].includes(fileType);
  const isPdf = fileType === "pdf";
  const isText = ["txt", "csv", "json", "xml", "yaml", "yml", "log"].includes(fileType);
  const isMarkdown = MARKDOWN_FILE_TYPES.includes(fileType);
  const isOffice = ["docx", "xlsx", "pptx", "odt", "ods", "odp", "xls", "ppt"].includes(fileType);
  // markdown 也算可预览：富渲染 + 失败/源码回退两个分支已覆盖其全部状态，
  // 若不加入，"不支持预览"兜底会在渲染成功时与之同时出现
  const canPreview = isImage || isPdf || isText || isMarkdown || isOffice;

  function formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex flex-col" style={{ background: 'var(--color-bg-layout)' }}>
      {/* Header */}
      <div className="flex h-14 items-center gap-3 border-b px-4 lg:px-6 shrink-0" style={{ borderColor: 'var(--color-border-secondary)', background: 'var(--color-bg-elevated)' }}>
        <FileIcon className="h-4 w-4 shrink-0" style={{ color: 'var(--color-text-tertiary)' }} />
        <span className="text-sm font-medium truncate" style={{ color: 'var(--color-text)' }}>
          {docName}
        </span>
        <div className="flex-1" />
        {previewData && (
          <a
            href={previewData.previewUrl}
            download={docName}
            className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs transition-colors"
            style={{ color: 'var(--color-text-secondary)', background: 'var(--color-fill-quaternary)' }}
          >
            <Download className="h-3.5 w-3.5" />
            下载
          </a>
        )}
        {isMarkdown && mdContent && !mdFailed && !mdOversize && (
          <div className="flex items-center rounded-lg p-0.5" style={{ background: 'var(--color-fill-quaternary)' }}>
            {([["rendered", "渲染"], ["source", "源码"]] as const).map(([mode, label]) => (
              <button
                key={mode}
                type="button"
                onClick={() => setViewMode(mode)}
                className="rounded-md px-3 py-1 text-xs transition-colors"
                style={{
                  background: viewMode === mode ? 'var(--color-bg-elevated)' : 'transparent',
                  color: viewMode === mode ? 'var(--color-text)' : 'var(--color-text-secondary)',
                }}
              >
                {label}
              </button>
            ))}
          </div>
        )}
        <button
          type="button"
          onClick={onClose}
          className="flex h-8 w-8 items-center justify-center rounded-lg transition-colors"
          style={{ color: 'var(--color-text-tertiary)' }}
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 min-h-0 flex items-center justify-center p-4">
        {loading && (
          <div className="flex items-center gap-2 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            <Loader2 className="h-4 w-4 animate-spin" />
            {loadingLabel}
          </div>
        )}

        {error && (
          <div className="text-center">
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>{error}</p>
          </div>
        )}

        {!loading && !error && isImage && previewData && (
          <img
            src={previewData.previewUrl}
            alt={docName}
            className="max-h-full max-w-full rounded-lg object-contain"
          />
        )}

        {!loading && !error && isPdf && previewData && (
          <iframe
            src={previewData.previewUrl}
            className="h-full w-full rounded-lg border-0"
            title={docName}
          />
        )}

        {!loading && !error && isText && previewData && (
          <iframe
            src={previewData.previewUrl}
            className="h-full w-full rounded-lg border-0"
            title={docName}
            style={{ background: 'white' }}
          />
        )}

        {!loading && !error && isMarkdown && viewMode === "rendered" && mdContent && !mdFailed && !mdOversize && (
          <div
            className="flex h-full w-full overflow-hidden rounded-lg border"
            style={{ borderColor: 'var(--color-border-secondary)', background: 'var(--color-bg-elevated)' }}
          >
            {tocItems.length > 0 && (
              <aside
                className="hidden w-60 shrink-0 overflow-y-auto border-r py-3 md:block"
                style={{ borderColor: 'var(--color-border-secondary)' }}
              >
                <p
                  className="mb-1.5 flex items-center gap-1.5 px-3 text-xs font-medium"
                  style={{ color: 'var(--color-text-tertiary)' }}
                >
                  <ListTree className="h-3.5 w-3.5" />
                  目录
                </p>
                <nav className="flex flex-col gap-0.5 pr-2">
                  {tocItems.map((item) => {
                    const active = item.id === activeTocId;
                    return (
                      <button
                        key={item.id}
                        type="button"
                        title={item.text}
                        onClick={() => scrollToTocHeading(item.id)}
                        className="block w-full truncate rounded-md py-1 pr-2 text-left text-xs leading-5 transition-colors"
                        style={{
                          paddingLeft: (item.level - 1) * 12 + 12,
                          background: active ? 'var(--color-fill-quaternary)' : 'transparent',
                          color: active ? 'var(--color-text)' : 'var(--color-text-secondary)',
                          fontWeight: active ? 500 : 400,
                        }}
                      >
                        {item.text}
                      </button>
                    );
                  })}
                </nav>
              </aside>
            )}
            <div ref={mdScrollRef} className="min-w-0 flex-1 overflow-y-auto">
              <div className="mx-auto w-full max-w-5xl p-6">
                <ErrorBoundary onError={() => setMdFailed(true)} fallback={null}>
                  <Suspense
                    fallback={
                      <div className="flex items-center gap-2 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                        <Loader2 className="h-4 w-4 animate-spin" />
                        加载渲染组件...
                      </div>
                    }
                  >
                    <MarkdownRenderer content={mdContent} />
                  </Suspense>
                </ErrorBoundary>
              </div>
            </div>
          </div>
        )}

        {/* Markdown 渲染失败 / 文件过大 / 源码视图：回退文本预览 */}
        {!loading && !error && isMarkdown && previewData && (!mdContent || mdFailed || mdOversize || viewMode === "source") && (
          <div className="flex h-full w-full flex-col gap-2">
            {(mdFailed || mdOversize) && (
              <div className="flex justify-center">
                <span
                  className="rounded-full px-3 py-1 text-xs"
                  style={{ color: 'var(--color-text-tertiary)', background: 'var(--color-fill-quaternary)' }}
                >
                  {mdOversize ? "文件过大，已回退为纯文本预览" : "Markdown 渲染失败，已回退为纯文本预览"}
                </span>
              </div>
            )}
            <iframe
              src={previewData.previewUrl}
              className="min-h-0 w-full flex-1 rounded-lg border-0"
              title={docName}
              style={{ background: 'white' }}
            />
          </div>
        )}

        {!loading && !error && isOffice && htmlContent && (
          <div
            className="h-full w-full overflow-auto rounded-lg border p-6"
            style={{ borderColor: 'var(--color-border-secondary)', background: 'white' }}
            // 用户上传文档转换出的 HTML 未经信任，渲染前必须经 DOMPurify 白名单清洗，防止存储型 XSS
            dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(htmlContent) }}
          />
        )}

        {!loading && !error && isOffice && !htmlContent && !error && (
          <div className="flex items-center gap-2 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            <Loader2 className="h-4 w-4 animate-spin" />
            转换文档格式...
          </div>
        )}

        {!loading && !error && !canPreview && previewData && (
          <div className="text-center">
            <Eye className="mx-auto h-12 w-12" style={{ color: 'var(--color-text-tertiary)' }} />
            <p className="mt-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              此格式暂不支持在线预览，请下载后查看
            </p>
            <a
              href={previewData.previewUrl}
              download={docName}
              className="mt-4 inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-medium text-white"
              style={{ background: 'hsl(var(--primary))' }}
            >
              <Download className="h-4 w-4" />
              下载文件
            </a>
          </div>
        )}
      </div>

      {/* PPTX 容器 */}
      <div ref={officeContainerRef} className="hidden" />

      {/* Footer */}
      {previewData && (
        <div className="flex h-10 items-center gap-4 border-t px-4 lg:px-6 shrink-0" style={{ borderColor: 'var(--color-border-secondary)', background: 'var(--color-bg-elevated)' }}>
          <span className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>
            {fileType.toUpperCase()} · {formatSize(previewData.fileSize)}
          </span>
        </div>
      )}
    </div>
  );
}
