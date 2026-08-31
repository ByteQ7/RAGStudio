/**
 * 日期时间格式化工具
 * <p>
 * 后端返回 "yyyy-MM-dd HH:mm:ss" 格式，该格式在 Safari 上无法被
 * `new Date()` 解析（返回 Invalid Date），统一在此做空格 → "T" 归一化。
 */
export function formatDateTime(value?: string | null): string {
  if (!value) return "-";
  const normalized = value.includes("T") ? value : value.replace(" ", "T");
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN");
}
