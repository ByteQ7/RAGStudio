import * as React from "react";
import mermaid from "mermaid";
import { Check, Copy } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

let initialized = false;

function ensureMermaidInit() {
  if (!initialized) {
    mermaid.initialize({
      startOnLoad: false,
      theme: document.documentElement.classList.contains("dark") ? "dark" : "default",
      securityLevel: "loose",
      fontFamily: "inherit",
    });
    initialized = true;
  }
}

export function MermaidBlock({ children }: { children: React.ReactNode }) {
  const containerRef = React.useRef<HTMLDivElement>(null);
  const [svg, setSvg] = React.useState<string>("");
  const [error, setError] = React.useState<string>("");
  const code = React.useMemo(
    () => String(children).replace(/\n$/, ""),
    [children]
  );

  React.useEffect(() => {
    let cancelled = false;
    ensureMermaidInit();

    const id = `mermaid-${Math.random().toString(36).slice(2, 10)}`;

    mermaid
      .render(id, code)
      .then(({ svg: renderedSvg }) => {
        if (!cancelled) setSvg(renderedSvg);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          console.error("Mermaid render error:", err);
          setError(err instanceof Error ? err.message : "Mermaid 渲染失败");
        }
        // Clean up error container that mermaid may leave in DOM
        const errorEl = document.getElementById(`d${id}`);
        if (errorEl) errorEl.remove();
      });

    return () => {
      cancelled = true;
    };
  }, [code]);

  const [copied, setCopied] = React.useState(false);
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  if (error) {
    return (
      <div className="my-3 overflow-hidden rounded-md border border-amber-300 bg-amber-50 dark:border-amber-700 dark:bg-amber-950/30">
        <div className="flex items-center justify-between border-b border-amber-300 bg-amber-100 px-3 py-1.5 dark:border-amber-700 dark:bg-amber-900/40">
          <span className="font-mono text-[11px] font-semibold uppercase tracking-wider text-amber-600 dark:text-amber-400">
            mermaid (error)
          </span>
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7"
            onClick={handleCopy}
          >
            {copied ? (
              <Check className="h-3.5 w-3.5 text-green-600 dark:text-green-400" />
            ) : (
              <Copy className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400" />
            )}
          </Button>
        </div>
        <pre className="overflow-x-auto p-3 text-xs text-amber-800 dark:text-amber-300">
          <code>{code}</code>
        </pre>
        <p className="border-t border-amber-300 px-3 py-1.5 text-xs text-amber-600 dark:border-amber-700 dark:text-amber-400">
          {error}
        </p>
      </div>
    );
  }

  if (!svg) {
    return (
      <div
        ref={containerRef}
        className="my-3 flex items-center gap-2 rounded-md border border-[#d0d7de] bg-[#f6f8fa] px-4 py-6 text-sm text-[#57606a] dark:border-[#30363d] dark:bg-[#161b22] dark:text-[#8b949e]"
      >
        <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
        正在渲染图表…
      </div>
    );
  }

  return (
    <div className="my-3 overflow-hidden rounded-md border border-[#d0d7de] bg-white dark:border-[#30363d] dark:bg-[#161b22]">
      <div className="flex items-center justify-between border-b border-[#d0d7de] bg-[#f6f8fa] px-3 py-1.5 dark:border-[#30363d] dark:bg-[#161b22]">
        <span className="font-mono text-[11px] font-semibold uppercase tracking-wider text-[#57606a] dark:text-[#8b949e]">
          mermaid
        </span>
        <Button
          variant="ghost"
          size="icon"
          className="h-7 w-7 hover:bg-[#eaeef2] dark:hover:bg-[#30363d] transition-colors"
          onClick={handleCopy}
          aria-label="复制源码"
        >
          {copied ? (
            <Check className="h-3.5 w-3.5 text-green-600 dark:text-green-400" />
          ) : (
            <Copy className="h-3.5 w-3.5 text-[#57606a] dark:text-[#8b949e]" />
          )}
        </Button>
      </div>
      <div
        className="flex justify-center overflow-x-auto p-4 [&_svg]:max-w-full"
        dangerouslySetInnerHTML={{ __html: svg }}
      />
    </div>
  );
}
