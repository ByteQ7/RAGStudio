import * as React from "react";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneDark, oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";

import { detectLanguage } from "@/lib/codeLanguage";
import { cn } from "@/lib/utils";
import { useThemeStore } from "@/stores/themeStore";

/**
 * 轻量代码编辑器（SKILL 脚本/命令等文本文件编辑用）
 * <p>
 * 透明 textarea 覆盖在 Prism 高亮层之上：两者字体、字号、行高、内边距完全一致，
 * 输入时由透明文本层的 textarea 承载光标与输入，底层高亮实时跟随，
 * 从而在不引入 Monaco/CodeMirror 等重型依赖的前提下获得语法高亮 + 行号的编辑体验。
 */

const FONT_SIZE_PX = 12;
const LINE_HEIGHT_PX = 20;
const PADDING_X_PX = 12;
const PADDING_Y_PX = 8;
const GUTTER_WIDTH_PX = 44;
/** 超过该字符数的高亮开销过大，降级为纯文本编辑（仍保留行号） */
const MAX_HIGHLIGHT_CHARS = 100_000;
/** 超过该行数不渲染行号，避免超长文件的 DOM 开销 */
const MAX_GUTTER_LINES = 3_000;

class HighlightBoundary extends React.Component<
  { children: React.ReactNode; onError: () => void },
  { failed: boolean }
> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch() {
    this.props.onError();
  }

  render() {
    return this.state.failed ? null : this.props.children;
  }
}

export interface CodeEditorProps {
  value: string;
  onChange?: (value: string) => void;
  /** 文件路径，用于自动识别语言；也可用 language 显式指定 */
  filePath?: string | null;
  language?: string;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
}

export function CodeEditor({
  value,
  onChange,
  filePath,
  language,
  disabled = false,
  placeholder,
  className
}: CodeEditorProps) {
  const theme = useThemeStore((state) => state.theme);
  const textareaRef = React.useRef<HTMLTextAreaElement>(null);
  const highlightInnerRef = React.useRef<HTMLDivElement>(null);
  const gutterInnerRef = React.useRef<HTMLDivElement>(null);
  const [highlightFailed, setHighlightFailed] = React.useState(false);

  const resolvedLanguage = React.useMemo(
    () => language ?? detectLanguage(filePath),
    [language, filePath]
  );

  React.useEffect(() => {
    setHighlightFailed(false);
  }, [resolvedLanguage]);

  const lineCount = React.useMemo(() => value.split("\n").length, [value]);
  const showGutter = lineCount <= MAX_GUTTER_LINES;
  const indentSize = resolvedLanguage === "python" ? 4 : 2;
  const highlightable =
    !!resolvedLanguage && !highlightFailed && value.length <= MAX_HIGHLIGHT_CHARS;

  const gutterNumbers = React.useMemo(
    () => (showGutter ? Array.from({ length: lineCount }, (_, index) => index + 1) : null),
    [lineCount, showGutter]
  );

  const syncScroll = React.useCallback(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    const { scrollTop, scrollLeft } = textarea;
    if (highlightInnerRef.current) {
      highlightInnerRef.current.style.transform = `translate3d(-${scrollLeft}px, -${scrollTop}px, 0)`;
    }
    if (gutterInnerRef.current) {
      gutterInnerRef.current.style.transform = `translate3d(0, -${scrollTop}px, 0)`;
    }
  }, []);

  // 内容变化后（如切换文件、撤销）重新对齐滚动位置
  React.useEffect(() => {
    syncScroll();
  }, [value, syncScroll]);

  const handleChange = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    onChange?.(event.target.value);
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== "Tab" || event.shiftKey || disabled) return;
    event.preventDefault();
    const textarea = event.currentTarget;
    const indent = " ".repeat(indentSize);
    // execCommand 可保留原生撤销栈；失败时退回手动插入
    if (typeof document.execCommand === "function") {
      try {
        if (document.execCommand("insertText", false, indent)) return;
      } catch {
        // 忽略，走手动插入
      }
    }
    const start = textarea.selectionStart ?? 0;
    const end = textarea.selectionEnd ?? 0;
    onChange?.(`${value.slice(0, start)}${indent}${value.slice(end)}`);
    requestAnimationFrame(() => {
      textarea.selectionStart = start + indent.length;
      textarea.selectionEnd = start + indent.length;
    });
  };

  return (
    <div
      className={cn(
        "relative flex-1 min-h-0 overflow-hidden rounded-2xl border border-input bg-background/80 shadow-sm transition-colors focus-within:ring-2 focus-within:ring-ring",
        disabled && "opacity-60",
        className
      )}
    >
      {showGutter && (
        <div
          aria-hidden="true"
          className="absolute inset-y-0 left-0 overflow-hidden border-r select-none font-mono"
          style={{
            width: `${GUTTER_WIDTH_PX}px`,
            borderColor: "var(--color-border-secondary)",
            background: "var(--color-fill-quaternary)",
            color: "var(--color-text-tertiary)"
          }}
        >
          <div
            ref={gutterInnerRef}
            className="pr-2 text-right"
            style={{
              paddingTop: `${PADDING_Y_PX}px`,
              fontSize: "10px",
              lineHeight: `${LINE_HEIGHT_PX}px`
            }}
          >
            {gutterNumbers?.map((lineNo) => (
              <div key={lineNo}>{lineNo}</div>
            ))}
          </div>
        </div>
      )}
      {highlightable && (
        <HighlightBoundary onError={() => setHighlightFailed(true)}>
          <div
            aria-hidden="true"
            className="absolute inset-y-0 right-0 overflow-hidden pointer-events-none font-mono"
            style={{ left: showGutter ? `${GUTTER_WIDTH_PX}px` : 0 }}
          >
            <div ref={highlightInnerRef}>
              <SyntaxHighlighter
                language={resolvedLanguage}
                style={theme === "dark" ? oneDark : oneLight}
                PreTag="pre"
                codeTagProps={{
                  style: {
                    fontFamily: "inherit",
                    fontSize: `${FONT_SIZE_PX}px`,
                    lineHeight: `${LINE_HEIGHT_PX}px`,
                    whiteSpace: "pre",
                    background: "transparent",
                    textShadow: "none",
                    fontVariantLigatures: "none",
                    tabSize: indentSize
                  }
                }}
                customStyle={{
                  margin: 0,
                  padding: `${PADDING_Y_PX}px ${PADDING_X_PX}px`,
                  minWidth: "100%",
                  width: "max-content",
                  background: "transparent",
                  overflow: "visible",
                  fontFamily: "inherit",
                  fontSize: `${FONT_SIZE_PX}px`,
                  lineHeight: `${LINE_HEIGHT_PX}px`,
                  fontVariantLigatures: "none",
                  tabSize: indentSize
                }}
              >
                {value}
              </SyntaxHighlighter>
            </div>
          </div>
        </HighlightBoundary>
      )}
      <textarea
        ref={textareaRef}
        value={value}
        onChange={handleChange}
        onKeyDown={handleKeyDown}
        onScroll={syncScroll}
        disabled={disabled}
        placeholder={placeholder}
        spellCheck={false}
        autoCapitalize="off"
        autoCorrect="off"
        wrap="off"
        className={cn(
          "absolute inset-0 h-full w-full resize-none bg-transparent font-mono outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed",
          highlightable ? "text-transparent" : "text-[var(--color-text)]"
        )}
        style={{
          padding: `${PADDING_Y_PX}px ${PADDING_X_PX}px`,
          paddingLeft: `${(showGutter ? GUTTER_WIDTH_PX : 0) + PADDING_X_PX}px`,
          fontSize: `${FONT_SIZE_PX}px`,
          lineHeight: `${LINE_HEIGHT_PX}px`,
          caretColor: "var(--color-text)",
          fontVariantLigatures: "none",
          tabSize: indentSize,
          whiteSpace: "pre",
          overflow: "auto"
        }}
      />
    </div>
  );
}
