/**
 * SKILL 文本文件 → Prism 语言映射
 * <p>
 * 仅覆盖技能包里常见的轻量脚本 / 配置 / 文档类型，
 * 未识别或二进制扩展名返回 undefined（按纯文本处理）。
 */

const EXTENSION_LANGUAGES: Record<string, string> = {
  // Python
  py: "python",
  pyw: "python",
  // Shell
  sh: "bash",
  bash: "bash",
  zsh: "bash",
  ksh: "bash",
  fish: "bash",
  bat: "batch",
  cmd: "batch",
  ps1: "powershell",
  // C / C++
  c: "c",
  h: "c",
  cc: "cpp",
  cpp: "cpp",
  cxx: "cpp",
  hh: "cpp",
  hpp: "cpp",
  hxx: "cpp",
  // JVM / Go / Rust
  java: "java",
  kt: "kotlin",
  kts: "kotlin",
  scala: "scala",
  go: "go",
  rs: "rust",
  // JS / TS
  js: "javascript",
  mjs: "javascript",
  cjs: "javascript",
  jsx: "jsx",
  ts: "typescript",
  tsx: "tsx",
  // 配置 / 数据
  json: "json",
  json5: "json5",
  yaml: "yaml",
  yml: "yaml",
  toml: "toml",
  ini: "ini",
  cfg: "ini",
  conf: "ini",
  properties: "properties",
  env: "bash",
  // 文档 / 标记
  md: "markdown",
  markdown: "markdown",
  html: "markup",
  htm: "markup",
  xml: "markup",
  svg: "markup",
  css: "css",
  scss: "scss",
  less: "less",
  sql: "sql",
  // 其他常见脚本
  rb: "ruby",
  php: "php",
  pl: "perl",
  lua: "lua",
  r: "r",
  swift: "swift",
  dart: "dart",
  mk: "makefile"
};

const FILENAME_LANGUAGES: Record<string, string> = {
  dockerfile: "docker",
  makefile: "makefile",
  ".env": "bash",
  ".env.local": "bash",
  ".gitignore": "ignore"
};

/** 按文件路径（扩展名 / 特殊文件名）推断 Prism 语言，未识别时返回 undefined（纯文本） */
export function detectLanguage(filePath?: string | null): string | undefined {
  if (!filePath) return undefined;
  const name = filePath.split("/").pop()?.toLowerCase() ?? "";
  if (!name) return undefined;
  const byFilename = FILENAME_LANGUAGES[name];
  if (byFilename) return byFilename;
  const dot = name.lastIndexOf(".");
  if (dot <= 0) return undefined;
  return EXTENSION_LANGUAGES[name.slice(dot + 1)];
}
