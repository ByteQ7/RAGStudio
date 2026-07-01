// @ts-nocheck
/* eslint-disable */

import * as React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeSanitize from "rehype-sanitize";
import rehypeKatex from "rehype-katex";
import { Check, Copy, Download, ImageIcon, ExternalLink } from "lucide-react";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneDark, oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";

import { Button } from "@/components/ui/button";
import { MermaidBlock } from "@/components/chat/MermaidBlock";
import { cn } from "@/lib/utils";
import { useThemeStore } from "@/stores/themeStore";

/* ─── Language alias map ───────────────────────────────────────────────
 * Prism only recognises canonical names (e.g. "javascript", "python").
 * LLMs frequently emit short aliases (js, ts, py, sh, …).
 * This map normalises them so syntax highlighting actually kicks in.
 * ───────────────────────────────────────────────────────────────────── */
const LANGUAGE_ALIASES: Record<string, string> = {
  // JavaScript family
  js: "javascript",
  mjs: "javascript",
  cjs: "javascript",
  es6: "javascript",
  ts: "typescript",
  tsx: "tsx",
  jsx: "jsx",
  node: "javascript",
  deno: "typescript",

  // Python family
  py: "python",
  py3: "python",
  python3: "python",
  pytorch: "python",

  // Shell
  sh: "bash",
  shell: "bash",
  zsh: "bash",
  fish: "bash",
  ksh: "bash",
  "shell-session": "shell-session",
  console: "shell-session",

  // C family
  "c++": "cpp",
  "c#": "csharp",
  cs: "csharp",
  "c-plus-plus": "cpp",
  "c-sharp": "csharp",
  objc: "objectivec",
  "objective-c": "objectivec",

  // Web
  html: "markup",
  htm: "markup",
  xml: "markup",
  svg: "markup",
  mathml: "markup",
  ssml: "markup",
  vue: "markup",
  svelte: "markup",
  pug: "pug",
  jade: "pug",
  hbs: "handlebars",
  ejs: "ejs",

  // Config / data formats
  yml: "yaml",
  toml: "toml",
  ini: "ini",
  conf: "ini",
  cfg: "ini",
  env: "bash",
  dockerfile: "docker",
  docker: "docker",
  "docker-compose": "yaml",
  nginx: "nginx",
  apache: "apacheconf",

  // JVM
  kt: "kotlin",
  kts: "kotlin",
  groovy: "groovy",
  gradle: "groovy",
  scala: "scala",
  sbt: "scala",

  // Go
  go: "go",
  golang: "go",

  // Rust
  rs: "rust",

  // Ruby
  rb: "ruby",
  erb: "erb",

  // .NET
  fs: "fsharp",
  "f#": "fsharp",
  vb: "visual-basic",
  vbnet: "vbnet",

  // Database
  psql: "sql",
  mysql: "sql",
  postgresql: "sql",
  postgres: "sql",
  plsql: "plsql",
  tsql: "sql",

  // DevOps / Infra
  tf: "hcl",
  terraform: "hcl",

  // Mobile
  swift: "swift",
  dart: "dart",
  flutter: "dart",

  // Misc
  md: "markdown",
  mdx: "markdown",
  tex: "latex",
  regex: "regex",
  diff: "diff",
  patch: "diff",
  protobuf: "protobuf",
  proto: "protobuf",
  graphql: "graphql",
  gql: "graphql",
  wasm: "wasm",
  wat: "wasm",
  asm: "nasm",
  nasm: "nasm",
  makefile: "makefile",
  make: "makefile",
  cmake: "cmake",
  powershell: "powershell",
  ps1: "powershell",
  pwsh: "powershell",
  bat: "batch",
  cmd: "batch",
  vim: "vim",
  vimscript: "vim",
  lua: "lua",
  pl: "perl",
  perl: "perl",
  php: "php",
  r: "r",
  R: "r",
  julia: "julia",
  jl: "julia",
  elixir: "elixir",
  ex: "elixir",
  exs: "elixir",
  erlang: "erlang",
  erl: "erlang",
  haskell: "haskell",
  hs: "haskell",
  clojure: "clojure",
  clj: "clojure",
  lisp: "lisp",
  scheme: "scheme",
  scm: "scheme",
  ocaml: "ocaml",
  ml: "ocaml",
  zig: "zig",
  nim: "nim",
  nix: "nix",
  solidity: "solidity",
  sol: "solidity",
  v: "v",
  vlang: "v",
};

/* ─── Known Prism language names ─────────────────────────────────────
 * Used to validate and recover when the code-fence info string has
 * extra characters glued onto the language name (e.g. "pythondef"
 * instead of "python", or "javascriptconst" instead of "javascript").
 * ───────────────────────────────────────────────────────────────────── */
const KNOWN_LANGUAGES = new Set([
  "abap", "actionscript", "ada", "apacheconf", "apex", "applescript",
  "arduino", "asciidoc", "asm6502", "autohotkey", "awk",
  "bash", "basic", "batch", "bicep", "bnf", "brainfuck",
  "c", "clike", "clojure", "cmake", "cobol", "coffeescript", "coq",
  "cpp", "crystal", "csharp", "cshtml", "csp", "css", "csv", "cypher",
  "d", "dart", "dax", "dhall", "diff", "django", "docker", "dot",
  "ebnf", "editorconfig", "eiffel", "ejs", "elixir", "elm", "erb",
  "erlang", "excel-formula", "f", "factor", "fortran", "fsharp",
  "gcode", "gdscript", "gedcom", "gherkin", "git", "glsl", "gml",
  "go", "go-module", "gradle", "graphql", "groovy",
  "haml", "handlebars", "haskell", "haxe", "hcl", "hlsl", "http",
  "ichigojam", "icon", "idris", "iecst", "ignore", "ini",
  "java", "javadoc", "javascript", "jolie", "jq", "json", "json5",
  "jsonp", "jsx", "julia",
  "keyman", "kotlin", "kumir", "kusto",
  "latex", "less", "lilypond", "liquid", "lisp", "livescript", "llvm",
  "log", "lolcode", "lua",
  "magma", "makefile", "markdown", "markup", "markup-templating",
  "matlab", "maxscript", "mel", "mermaid", "mizar", "mongodb",
  "monkey", "moonscript",
  "n1ql", "n4js", "nasm", "neon", "nginx", "nim", "nix", "nsis",
  "objectivec", "ocaml", "opencl", "openqasm", "oz",
  "parigp", "parser", "pascal", "pascaligo", "pcaxis", "peoplecode",
  "perl", "php", "php-extras", "plsql", "powerquery", "powershell",
  "processing", "prolog", "promql", "properties", "protobuf", "pug",
  "puppet", "pure", "purebasic", "purescript", "python",
  "q", "qml", "qore", "qsharp",
  "r", "racket", "reason", "regex", "rego", "renpy", "rescript",
  "rest", "rip", "robotframework", "ruby", "rust",
  "sas", "sass", "scala", "scheme", "scss", "shell-session",
  "smali", "smalltalk", "smarty", "sml", "solidity", "solution-file",
  "soy", "sparql", "splunk-spl", "sqf", "sql", "squirrel", "stan",
  "stata", "stylus", "supercollider", "swift", "systemd",
  "tcl", "textile", "toml", "tremor", "tsx", "turtle", "twig",
  "typescript", "typoscript",
  "unrealscript", "uorazor", "uri",
  "v", "vala", "vbnet", "velocity", "verilog", "vhdl", "vim",
  "visual-basic",
  "warpscript", "wasm", "web-idl", "wgsl", "wiki", "wolfram", "wren",
  "xeora", "xml-doc", "xojo", "xquery",
  "yaml", "yang",
  "zig",
]);

/**
 * Normalise a raw language string from a code-fence to a canonical
 * Prism language name.  Returns `null` when the input is empty/absent.
 *
 * Handles the common LLM quirk where the info string glues code onto
 * the language name, e.g. `` ```pythondef `` → resolves to "python".
 */
function resolveLanguage(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const key = raw.toLowerCase().trim();
  if (!key) return null;

  // 1. Exact alias hit (js → javascript, py → python, …)
  if (key in LANGUAGE_ALIASES) return LANGUAGE_ALIASES[key];

  // 2. Already a known Prism language
  if (KNOWN_LANGUAGES.has(key)) return key;

  // 3. Fuzzy prefix: the fence info-string swallowed code tokens.
  //    Try progressively shorter prefixes until one matches a known
  //    language (minimum 2 chars to avoid false positives).
  for (let i = key.length - 1; i >= 2; i--) {
    const prefix = key.slice(0, i);
    if (KNOWN_LANGUAGES.has(prefix)) return prefix;
    if (prefix in LANGUAGE_ALIASES) return LANGUAGE_ALIASES[prefix];
  }

  // 4. Nothing matched — pass through as-is, SyntaxHighlighter will
  //    fall back to plain text.
  return key;
}

/**
 * Safely extract plain text from React children.
 * `String(children)` breaks when children contain React elements
 * (returns "[object Object]"), so we recurse through arrays/objects.
 */
function extractText(node: React.ReactNode): string {
  if (node == null || typeof node === "boolean") return "";
  if (typeof node === "string") return node;
  if (typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(extractText).join("");
  if (typeof node === "object" && "props" in node) {
    return extractText((node as React.ReactElement).props.children);
  }
  return String(node);
}

/* ─── Content preprocessing ──────────────────────────────────────────
 * LLMs sometimes emit malformed code fences where the info string
 * swallows the first line of code, e.g.:
 *
 *   ```pythondef bubble_sort(arr):
 *       ...
 *   ```
 *
 * This loses "def bubble_sort(arr):" from the rendered code and
 * produces a garbled language label like "PYTHONDEF".
 *
 * The regex below detects such fences, extracts the known language,
 * and moves the swallowed code back onto the first line of the block.
 * ───────────────────────────────────────────────────────────────────── */

// Build a regex that matches all known languages + aliases as prefixes
// of the info string.  Sorted longest-first so "javascript" matches
// before "java", "typescript" before "type", etc.
const ALL_LANG_KEYS = [
  ...Object.keys(LANGUAGE_ALIASES),
  ...KNOWN_LANGUAGES,
].sort((a, b) => b.length - a.length);

const FENCE_RE = new RegExp(
  "(```+)(" + ALL_LANG_KEYS.map(escapeRegExp).join("|") + ")(\\S[^\\n]*)\\n",
  "g"
);

function escapeRegExp(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Bare URL pattern for known image hosts — catches URLs not already in markdown link/image syntax */
const BARE_IMAGE_URL_RE = /(?<!\(|\[)https?:\/\/(?:dashscope[^/]*\.aliyuncs\.com|[^/\s]*\.oss[-\w]*\.aliyuncs\.com|cdn\.openai\.com|oaidallestorageprod[^/\s]*|replicate\.delivery[^/\s]*|huggingface\.co[^/\s]*|[^/\s]*\.imgix\.net|[^/\s]*\.cloudinary\.com)[^\s)\]>'"]*/gi;

function preprocessContent(md: string): string {
  // 1. Fix malformed code fences (language + code glued together)
  md = md.replace(FENCE_RE, (_match, ticks, _lang, swallowed) => {
    return `${ticks}${_lang}\n${swallowed}\n`;
  });

  // 2. Fix headings wrapped in bold markers, e.g. "**# 一、标题**" or "**#一、标题**"
  //    LLMs sometimes combine bold and heading syntax, producing literal # in output.
  //    Convert to a proper heading (strip the bold, keep the # markers).
  md = md.replace(/^(\*{1,2})(#{1,6})\s?(.+?)\1/gm, (_match, _stars, hashes, text) => {
    return `${hashes} ${text}`;
  });

  // 3. Fix headings without space after # markers.
  //    CommonMark requires "## text", but LLMs often emit "##text".
  //    e.g. "##1.基本快速排序" → "## 1.基本快速排序"
  //    Uses negative lookahead (?![\s#]) instead of (\S) to avoid the
  //    greedy-backtrack bug where (#{1,6}) under-matches by one and
  //    (\S) swallows the last #, corrupting valid headings like "### text".
  md = md.replace(/^(#{1,6})(?![\s#])/gm, "$1 ");

  // 4. Convert bare image-hosting URLs into markdown images so they
  //    render inline instead of as plain text links.
  md = md.replace(BARE_IMAGE_URL_RE, (url) => `![图片](${url})`);

  return md;
}

/* ─── Main component ──────────────────────────────────────────────── */

interface MarkdownRendererProps {
  content: string;
  /**
   * Compact rendering mode for user messages and thinking content.
   * Uses smaller fonts, tighter spacing, while still supporting
   * full Markdown with syntax-highlighted code blocks.
   */
  compact?: boolean;
  /**
   * 知识库引用列表，用于将 [^chunk_{id}] 标记渲染为可点击的引用上标
   */
  citations?: Array<{ id: string; text: string; score?: number }>;
}

export function MarkdownRenderer({ content, compact = false, citations }: MarkdownRendererProps) {
  const theme = useThemeStore((state) => state.theme);
  const containerRef = React.useRef<HTMLDivElement>(null);
  const gramCacheRef = React.useRef<Set<string> | null>(null);
  const highlightedRef = React.useRef<Set<string>>(new Set());

  // ====== 构建回答文本的 n-gram 索引（K=15） ======
  const getAnswerGramSet = React.useCallback(() => {
    if (gramCacheRef.current) return gramCacheRef.current;
    const set = new Set<string>();
    const K = 15;
    for (let i = 0; i <= content.length - K; i++) {
      set.add(content.slice(i, i + K));
    }
    gramCacheRef.current = set;
    return set;
  }, [content]);

  // ====== 渲染后将 [^chunk_{id}] 文本替换为可点击的引用 span ======
  // 使用 DOM TreeWalker 绕过 react-markdown 组件系统的版本兼容问题
  React.useEffect(() => {
    const container = containerRef.current;
    if (!container || !citations || citations.length === 0) return;

    const walker = document.createTreeWalker(
      container,
      NodeFilter.SHOW_TEXT,
      null
    );

    const toReplace: Array<{ node: Text; id: string; fullMatch: string }> = [];
    const regex = /\[\^chunk_(\w+)\]/g;
    while (walker.nextNode()) {
      const node = walker.currentNode as Text;
      const text = node.textContent || '';
      regex.lastIndex = 0;
      let m;
      while ((m = regex.exec(text)) !== null) {
        toReplace.push({ node, id: m[1], fullMatch: m[0] });
      }
    }

    // ====== 预先计算编号映射 ======
    // 扫描 content 中 [^chunk_{id}] 出现的顺序 → [1], [2], ...
    const numMap: Record<string, number> = {};
    let numIdx = 0;
    const scanRe = /\[\^chunk_(\w+)\]/g;
    let sm;
    while ((sm = scanRe.exec(content)) !== null) {
      if (!(sm[1] in numMap)) {
        numMap[sm[1]] = ++numIdx;
      }
    }

    for (const { node, id } of toReplace) {
      const text = node.textContent || '';
      const parent = node.parentNode;
      if (!parent) continue;

      const frag = document.createDocumentFragment();
      let lastIdx = 0;
      const re = /\[\^chunk_(\w+)\]/g;
      re.lastIndex = 0;
      let match;
      while ((match = re.exec(text)) !== null) {
        // 匹配前的文本
        if (match.index > lastIdx) {
          frag.appendChild(document.createTextNode(text.slice(lastIdx, match.index)));
        }
        // 引用 span — 显示为 [N] 编号
        const chunkId = match[1];
        const num = numMap[chunkId] || '?';
        const citation = citations?.find((c) => c.id === chunkId);
        const span = document.createElement('span');
        span.className = 'citation-ref cursor-pointer text-[#0969da] dark:text-[#58a6ff] font-semibold text-xs select-none hover:underline';
        span.setAttribute('data-citation-id', chunkId);
        span.textContent = `[${num}]`;
        span.title = citation?.text ?? '';
        frag.appendChild(span);
        lastIdx = match.index + match[0].length;
      }
      // 剩余文本
      if (lastIdx < text.length) {
        frag.appendChild(document.createTextNode(text.slice(lastIdx)));
      }

      parent.replaceChild(frag, node);
    }
  }, [content, citations]);

  // ====== 引用点击事件委托 → 派发事件让 CitationList 展开 + 滚动 ======
  React.useEffect(() => {
    const container = containerRef.current;
    if (!container || !citations || citations.length === 0) return;

    const handler = (e: MouseEvent) => {
      const target = e.target as HTMLElement;
      const ref = target.closest('[data-citation-id]') as HTMLElement | null;
      if (ref) {
        const chunkId = ref.getAttribute('data-citation-id');
        if (chunkId) {
          e.stopPropagation();
          e.preventDefault();
          window.dispatchEvent(new CustomEvent('expand-citation', { detail: chunkId }));
        }
      }
    };

    container.addEventListener('click', handler);
    return () => container.removeEventListener('click', handler);
  }, [content, citations]);

  // ====== 监听展开/折叠事件 → 对 chunk 原文做文本匹配高亮 ======
  React.useEffect(() => {
    const container = containerRef.current;
    if (!container || !citations || citations.length === 0) return;

    const handler = (e: Event) => {
      const chunkId = (e as CustomEvent).detail as string;
      if (!chunkId) return;
      const citation = citations.find((c) => c.id === chunkId);
      if (!citation || !citation.text) return;

      const hSet = highlightedRef.current;

      if (hSet.has(chunkId)) {
        // 折叠 → 移除高亮
        container.querySelectorAll(`mark.chunk-highlight[data-citation-id="${chunkId}"]`).forEach((el) => {
          const parent = el.parentNode;
          if (parent) {
            parent.replaceChild(document.createTextNode(el.textContent || ''), el);
            parent.normalize();
          }
        });
        hSet.delete(chunkId);
      } else {
        // 展开 → 应用高亮
        const gramSet = getAnswerGramSet();
        const chunkText = citation.text;

        // 在 chunk text 中找到匹配的片段
        const matches: Array<[number, number]> = [];
        const MIN_MATCH = 15;
        for (let i = 0; i <= chunkText.length - MIN_MATCH; i++) {
          const gram = chunkText.slice(i, i + MIN_MATCH);
          if (gramSet.has(gram)) {
            if (matches.length > 0 && matches[matches.length - 1][1] >= i) {
              matches[matches.length - 1][1] = i + MIN_MATCH;
            } else {
              matches.push([i, i + MIN_MATCH]);
            }
          }
        }

        // 扩展匹配区域 ±5 字符
        const EXTEND = 5;
        const spans = matches.map(([s, e]) => [
          Math.max(0, s - EXTEND),
          Math.min(chunkText.length, e + EXTEND),
        ] as [number, number]);

        // 合并重叠区域
        const merged: Array<[number, number]> = [];
        for (const [s, e] of spans) {
          if (merged.length > 0 && merged[merged.length - 1][1] >= s) {
            merged[merged.length - 1][1] = Math.max(merged[merged.length - 1][1], e);
          } else {
            merged.push([s, e]);
          }
        }

        if (merged.length === 0) return;

        // 匹配比例 ≥ 30% 才生效
        const totalMatched = merged.reduce((acc, [s, e]) => acc + (e - s), 0);
        if (totalMatched < chunkText.length * 0.3) return;

        // DOM TreeWalker 查找并包裹 <mark>
        const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
        const textSegments: Array<{ node: Text; start: number; end: number }> = [];

        while (walker.nextNode()) {
          const node = walker.currentNode as Text;
          const text = node.textContent || '';

          for (const [s, e] of merged) {
            const span = chunkText.slice(s, e);
            const idx = text.indexOf(span);
            if (idx !== -1) {
              textSegments.push({ node, start: idx, end: idx + span.length });
            }
          }
        }

        const seen = new Set<Text>();
        for (const seg of textSegments) {
          if (seen.has(seg.node)) continue;
          seen.add(seg.node);

          const text = seg.node.textContent || '';
          const parent = seg.node.parentNode;
          if (!parent) continue;

          const frag = document.createDocumentFragment();
          if (seg.start > 0) {
            frag.appendChild(document.createTextNode(text.slice(0, seg.start)));
          }
          const mark = document.createElement('mark');
          mark.className = 'chunk-highlight';
          mark.setAttribute('data-citation-id', chunkId);
          mark.textContent = text.slice(seg.start, seg.end);
          frag.appendChild(mark);
          if (seg.end < text.length) {
            frag.appendChild(document.createTextNode(text.slice(seg.end)));
          }
          parent.replaceChild(frag, seg.node);
        }

        hSet.add(chunkId);
      }
    };

    window.addEventListener('expand-citation', handler);
    return () => window.removeEventListener('expand-citation', handler);
  }, [content, citations, getAnswerGramSet]);

  const components = React.useMemo(() => {
    const comps: Record<string, any> = {};

    /* In full mode, override <pre> so the typography plugin's default
     * background, padding, border-radius, etc. don't double-wrap our
     * custom code block containers. In compact mode, keep <pre> as-is
     * for plain code blocks without language tags. */
    if (!compact) {
      comps.pre = ({ children }: any) => <>{children}</>;
    }

    comps.code = ({ className, children, node, ...props }: any) => {
      const rawLang = /language-(\w[\w+-]*)/.exec(className || "")?.[1] ?? null;
      const language = resolveLanguage(rawLang);
      const value = extractText(children).replace(/\n$/, "");

      /* ── Mermaid diagram ── */
      if (language === "mermaid") {
        return <MermaidBlock>{children}</MermaidBlock>;
      }

      /* ── Block code ──
       * Detected when: explicit language tag exists, OR content has newlines. */
      const isBlockCode = language !== null || value.includes("\n");

      if (isBlockCode) {
        const displayLang = language ?? rawLang ?? "code";

        return (
          <div className={cn(
            "overflow-hidden rounded-md border",
            "border-[#d0d7de] bg-[#f6f8fa]",
            "dark:border-[#30363d] dark:bg-[#161b22]",
            compact ? "my-2" : "my-3",
          )}>
            <div className={cn(
              "flex items-center justify-between border-b px-3 py-1.5",
              "border-[#d0d7de] bg-[#f6f8fa]",
              "dark:border-[#30363d] dark:bg-[#161b22]",
            )}>
              <span className={cn(
                "font-mono font-semibold uppercase tracking-wider",
                "text-[#57606a] dark:text-[#8b949e]",
                compact ? "text-[10px]" : "text-[11px]",
              )}>
                {displayLang}
              </span>
              <CopyButton value={value} />
            </div>
            <div className="overflow-x-auto">
              <SafeHighlighter
                language={language ?? "text"}
                theme={theme}
                value={value}
                compact={compact}
              />
            </div>
          </div>
        );
      }

      /* ── Inline code ── */
      return (
        <code
          className={cn(
            "rounded font-mono bg-[#f6f8fa] text-[#24292f]",
            "dark:bg-[#161b22] dark:text-[#c9d1d9]",
            compact ? "px-1 py-0.5 text-[12px]" : "px-1.5 py-0.5 text-[13px]",
            className,
          )}
          {...props}
        >
          {children}
        </code>
      );
    };

    /* Full-mode-only overrides for richer layout elements */
    if (!compact) {
      comps.img = ({ src, alt, ...props }: any) => {
        if (!src) return null;
        return <ImageLinkCard href={src} alt={alt} />;
      };

      comps.a = ({ children, href, ...props }: any) => {
        const hrefStr = href || "";
        // 如果链接目标是图片 URL，渲染为图片链接卡片 + 下载按钮
        if (isImageUrl(hrefStr)) {
          const linkText = extractText(children);
          return <ImageLinkCard href={hrefStr} alt={linkText} />;
        }
        return (
          <a
            className="text-[#0969da] underline-offset-4 hover:underline dark:text-[#58a6ff]"
            href={href}
            target="_blank"
            rel="noreferrer"
            {...props}
          >
            {children}
          </a>
        );
      };

      comps.table = ({ children, ...props }: any) => (
        <div className="my-3 overflow-x-auto">
          <table
            className="w-full border-collapse border border-[#d0d7de] rounded-md dark:border-[#30363d]"
            {...props}
          >
            {children}
          </table>
        </div>
      );

      comps.thead = ({ children, ...props }: any) => (
        <thead className="bg-[#f6f8fa] dark:bg-[#161b22]" {...props}>
          {children}
        </thead>
      );

      comps.th = ({ children, ...props }: any) => (
        <th
          className="border-b border-[#d0d7de] border-r border-r-[#d0d7de] px-3 py-2 text-left text-sm font-semibold text-[#24292f] last:border-r-0 dark:border-[#30363d] dark:border-r-[#30363d] dark:text-[#c9d1d9]"
          {...props}
        >
          {children}
        </th>
      );

      comps.td = ({ children, ...props }: any) => (
        <td
          className="border-b border-[#d0d7de] border-r border-r-[#d0d7de] px-3 py-2.5 text-sm text-[#24292f] last:border-r-0 dark:border-[#30363d] dark:border-r-[#30363d] dark:text-[#c9d1d9]"
          {...props}
        >
          {children}
        </td>
      );

      comps.blockquote = ({ children, ...props }: any) => (
        <blockquote
          className="my-3 border-l-4 border-[#2563EB] bg-[#EEF2FF] pl-3 pr-3 py-2 italic text-[#333333] dark:border-[#60A5FA] dark:bg-[#1A2332] dark:text-[#CCCCCC]"
          {...props}
        >
          {children}
        </blockquote>
      );

      comps.ul = ({ children, ...props }: any) => (
        <ul className="my-2 ml-6 list-disc space-y-1" {...props}>
          {children}
        </ul>
      );

      comps.ol = ({ children, ...props }: any) => (
        <ol className="my-2 ml-6 list-decimal space-y-1" {...props}>
          {children}
        </ol>
      );
    }

    return comps;
  }, [compact, theme]);

  const markdownClassName = compact
    ? cn(
        "prose prose-sm max-w-none",
        "[&_h1]:text-lg [&_h2]:text-base [&_h3]:text-sm [&_h4]:text-sm [&_h5]:text-xs [&_h6]:text-xs",
        "[&_h1]:my-2 [&_h2]:my-1.5 [&_h3]:my-1 [&_h4]:my-1 [&_h5]:my-1 [&_h6]:my-1",
        "[&_p]:my-1 [&_li]:my-0 [&_blockquote]:my-1.5 [&_pre]:my-1.5",
        "[&_ul]:my-1 [&_ol]:my-1 [&_table]:my-1.5",
        "dark:prose-invert",
      )
    : cn(
        "prose prose-gray max-w-none dark:prose-invert",
        "prose-headings:font-semibold prose-headings:text-[#1A1A1A] dark:prose-headings:text-[#EEEEEE]",
        "prose-p:text-[#333333] dark:prose-p:text-[#CCCCCC] prose-p:leading-relaxed",
        "prose-li:text-[#333333] dark:prose-li:text-[#CCCCCC]",
        "prose-strong:text-[#1A1A1A] dark:prose-strong:text-[#EEEEEE]",
      );

  return (
    <div ref={containerRef}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeSanitize, rehypeKatex]}
        components={components}
        className={markdownClassName}
      >
        {preprocessContent(content)}
      </ReactMarkdown>
    </div>
  );
}

/* ─── Safe syntax highlighter with fallback ──────────────────────────
 * Wraps SyntaxHighlighter in an error boundary so an unsupported or
 * misbehaving language never crashes the whole chat view.
 * Falls back to a plain <pre><code> block.                               */

interface HighlighterProps {
  language: string;
  theme: "dark" | "light";
  value: string;
  compact?: boolean;
}

class SafeHighlighter extends React.Component<
  HighlighterProps & { children?: never },
  { hasError: boolean }
> {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  render() {
    const { language, theme, value, compact } = this.props;

    if (this.state.hasError) {
      return <FallbackCode value={value} compact={compact} />;
    }

    return (
      <ErrorBoundaryFallback
        onError={() => this.setState({ hasError: true })}
        language={language}
        theme={theme}
        value={value}
        compact={compact}
      />
    );
  }
}

function ErrorBoundaryFallback({
  language,
  theme,
  value,
  compact,
  onError,
}: HighlighterProps & { onError: () => void }) {
  try {
    return (
      <SyntaxHighlighter
        language={language}
        style={theme === "dark" ? oneDark : oneLight}
        PreTag="div"
        customStyle={{
          margin: 0,
          padding: compact ? "0.5rem 0.75rem" : "0.75rem 1rem",
          background: "transparent",
          fontSize: compact ? "12px" : "13px",
          lineHeight: compact ? "1.45" : "1.5",
          whiteSpace: "pre",
          tabSize: 4,
        }}
        showLineNumbers={false}
        wrapLines={true}
      >
        {value}
      </SyntaxHighlighter>
    );
  } catch {
    // Trigger error boundary on next render
    React.useEffect(() => {
      onError();
    }, []);
    return <FallbackCode value={value} compact={compact} />;
  }
}

function FallbackCode({ value, compact }: { value: string; compact?: boolean }) {
  return (
    <pre
      className={cn(
        "m-0 overflow-x-auto whitespace-pre font-mono",
        "text-[#24292f] dark:text-[#c9d1d9]",
        compact ? "p-2.5 text-[12px] leading-[1.45]" : "p-3 text-[13px] leading-relaxed",
      )}
      style={{ tabSize: 4 }}
    >
      <code>{value}</code>
    </pre>
  );
}

/* ─── Image URL detection ─────────────────────────────────────────── */

const IMAGE_EXT_RE = /\.(png|jpe?g|gif|webp|bmp|svg|ico|tiff?)(\?.*)?$/i;

/**
 * Known cloud image hosting URL patterns.
 * These services generate extension-less URLs that are actually images
 * (e.g. DashScope image generation, Alibaba Cloud OSS signed URLs).
 */
const IMAGE_HOST_PATTERNS: RegExp[] = [
  /dashscope.*\.oss[-\w]*\.aliyuncs\.com/i,     // DashScope image gen (百炼)
  /\.oss[-\w]*\.aliyuncs\.com/i,                 // Alibaba Cloud OSS
  /dashscope[^/]*\.aliyuncs\.com/i,              // DashScope CDN variants
  /cdn\.openai\.com/i,                            // OpenAI CDN
  /oaidallestorageprod/i,                         // DALL-E storage
  /replicate\.delivery/i,                         // Replicate
  /huggingface\.co/i,                             // Hugging Face
  /imgix\.net/i,                                  // imgix
  /cloudinary\.com/i,                             // Cloudinary
];

function isImageUrl(url: string): boolean {
  if (!url) return false;
  try {
    const parsed = new URL(url, window.location.origin);
    if (IMAGE_EXT_RE.test(parsed.pathname)) return true;
    // Check known cloud image hosting patterns against the full hostname
    const host = parsed.hostname;
    return IMAGE_HOST_PATTERNS.some((re) => re.test(host));
  } catch {
    if (IMAGE_EXT_RE.test(url)) return true;
    return IMAGE_HOST_PATTERNS.some((re) => re.test(url));
  }
}

/* ─── Inline image card with preview and download ────────────────── */

function ImageLinkCard({ href, alt }: { href: string; alt?: string }) {
  const [status, setStatus] = React.useState<"loading" | "loaded" | "error">("loading");
  const [showOriginal, setShowOriginal] = React.useState(false);
  const displayText = alt && alt !== href ? alt : "查看图片";

  const handleDownload = async (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    try {
      const resp = await fetch(href);
      const blob = await resp.blob();
      const blobUrl = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = blobUrl;
      a.download = href.split("/").pop()?.split("?")[0] || "image.png";
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(blobUrl);
    } catch {
      window.open(href, "_blank");
    }
  };

  // If image failed to load, fall back to a plain link card
  if (status === "error") {
    return (
      <div
        className={cn(
          "my-3 flex items-center gap-3 rounded-lg border px-4 py-3",
          "border-[#d0d7de] bg-[#f6f8fa]",
          "dark:border-[#30363d] dark:bg-[#161b22]",
        )}
      >
        <ImageIcon className="h-5 w-5 flex-shrink-0 text-[#57606a] dark:text-[#8b949e]" />
        <div className="min-w-0 flex-1">
          <a
            href={href}
            target="_blank"
            rel="noreferrer"
            className="block truncate text-sm text-[#0969da] hover:underline dark:text-[#58a6ff]"
            title={href}
          >
            {displayText}
          </a>
          <p className="mt-0.5 truncate text-xs text-[#57606a] dark:text-[#8b949e]" title={href}>
            {href}
          </p>
        </div>
        <div className="flex flex-shrink-0 items-center gap-1.5">
          <Button
            variant="ghost"
            size="sm"
            className="h-8 gap-1.5 text-xs text-[#57606a] hover:bg-[#eaeef2] dark:text-[#8b949e] dark:hover:bg-[#30363d]"
            onClick={() => window.open(href, "_blank")}
            title="在新窗口打开"
          >
            <ExternalLink className="h-3.5 w-3.5" />
            <span>打开</span>
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="h-8 gap-1.5 text-xs text-[#0969da] hover:bg-[#eaeef2] dark:text-[#58a6ff] dark:hover:bg-[#30363d]"
            onClick={handleDownload}
            title="下载图片"
          >
            <Download className="h-3.5 w-3.5" />
            <span>下载</span>
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        "my-3 overflow-hidden rounded-lg border",
        "border-[#d0d7de] bg-white",
        "dark:border-[#30363d] dark:bg-[#161b22]",
      )}
    >
      {/* Image preview area */}
      <div className="relative">
        {status === "loading" && (
          <div className="flex h-48 items-center justify-center bg-[#f6f8fa] dark:bg-[#0d1117]">
            <div className="flex items-center gap-2 text-sm text-[#57606a] dark:text-[#8b949e]">
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-[#57606a] border-t-transparent dark:border-[#8b949e] dark:border-t-transparent" />
              <span>图片加载中...</span>
            </div>
          </div>
        )}
        <img
          src={href}
          alt={displayText}
          onLoad={() => setStatus("loaded")}
          onError={() => setStatus("error")}
          className={cn(
            "w-full cursor-zoom-in object-contain transition-opacity duration-200",
            status === "loaded" ? "opacity-100" : "opacity-0 absolute inset-0",
          )}
          onClick={() => setShowOriginal(true)}
          style={{ maxHeight: status === "loaded" ? "512px" : undefined }}
        />
      </div>

      {/* Toolbar: alt text + action buttons */}
      <div
        className={cn(
          "flex items-center gap-3 border-t px-4 py-2.5",
          "border-[#d0d7de] bg-[#f6f8fa]",
          "dark:border-[#30363d] dark:bg-[#0d1117]",
        )}
      >
        {alt && alt !== href ? (
          <span className="min-w-0 flex-1 truncate text-sm text-[#57606a] dark:text-[#8b949e]">
            {alt}
          </span>
        ) : (
          <span className="min-w-0 flex-1" />
        )}
        <div className="flex flex-shrink-0 items-center gap-1">
          <Button
            variant="ghost"
            size="sm"
            className="h-7 gap-1.5 text-xs text-[#57606a] hover:bg-[#eaeef2] dark:text-[#8b949e] dark:hover:bg-[#30363d]"
            onClick={() => setShowOriginal(true)}
            title="查看原图"
          >
            <ExternalLink className="h-3.5 w-3.5" />
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 gap-1.5 text-xs text-[#0969da] hover:bg-[#eaeef2] dark:text-[#58a6ff] dark:hover:bg-[#30363d]"
            onClick={handleDownload}
            title="下载图片"
          >
            <Download className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      {/* Full-screen overlay */}
      {showOriginal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-8"
          onClick={() => setShowOriginal(false)}
        >
          <img
            src={href}
            alt={displayText}
            className="max-h-full max-w-full cursor-zoom-out object-contain"
            onClick={(e) => e.stopPropagation()}
          />
          <Button
            variant="ghost"
            size="sm"
            className="absolute right-4 top-4 h-8 w-8 rounded-full bg-white/20 text-white hover:bg-white/30"
            onClick={() => setShowOriginal(false)}
          >
            ✕
          </Button>
        </div>
      )}
    </div>
  );
}

/* ─── Copy button ─────────────────────────────────────────────────── */

function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = React.useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={handleCopy}
      aria-label="复制代码"
      className="h-7 w-7 hover:bg-[#eaeef2] dark:hover:bg-[#30363d] transition-colors"
    >
      {copied ? (
        <Check className="h-3.5 w-3.5 text-green-600 dark:text-green-400" />
      ) : (
        <Copy className="h-3.5 w-3.5 text-[#57606a] dark:text-[#8b949e]" />
      )}
    </Button>
  );
}
