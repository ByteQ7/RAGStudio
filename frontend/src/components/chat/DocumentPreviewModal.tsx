import { useEffect, useRef, useState } from "react";
import { Download, Eye, FileIcon, Loader2, X } from "lucide-react";

import { getDocumentPreviewUrl, getDocumentBinaryContent, type PreviewData } from "@/services/previewService";

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

  useEffect(() => {
    if (!open) {
      setPreviewData(null);
      setHtmlContent(null);
      setLoading(false);
      setError(null);
      return;
    }

    const isImage = ["png", "jpg", "jpeg", "gif", "webp"].includes(fileType);
    const isPdf = fileType === "pdf";
    const isText = ["txt", "markdown", "md", "csv", "json", "xml", "yaml", "yml", "log"].includes(fileType);
    const isOffice = ["docx", "xlsx", "pptx", "odt", "ods", "odp", "xls", "ppt"].includes(fileType);

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
        }
      })
      .catch((err) => setError(err?.message || "获取预览失败"))
      .finally(() => setLoading(false));
  }, [docId, fileType, open]);

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
  const isText = ["txt", "markdown", "md", "csv", "json", "xml", "yaml", "yml", "log"].includes(fileType);
  const isOffice = ["docx", "xlsx", "pptx", "odt", "ods", "odp", "xls", "ppt"].includes(fileType);
  const canPreview = isImage || isPdf || isText || isOffice;

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

        {!loading && !error && isOffice && htmlContent && (
          <div className="h-full w-full overflow-auto rounded-lg border p-6" style={{ borderColor: 'var(--color-border-secondary)', background: 'white' }}
            dangerouslySetInnerHTML={{ __html: htmlContent }}
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
