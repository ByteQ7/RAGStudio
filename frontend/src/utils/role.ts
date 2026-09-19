import type { UserRole } from "@/types";

/** 角色展示文案（与后端 t_user.role 注释保持一致） */
export const USER_ROLE_LABELS: Record<UserRole, string> = {
  admin: "管理员",
  user: "普通用户"
};

/** 用户管理下拉选项 */
export const USER_ROLE_OPTIONS: { value: UserRole; label: string }[] = [
  { value: "admin", label: USER_ROLE_LABELS.admin },
  { value: "user", label: USER_ROLE_LABELS.user }
];

/**
 * 将后端返回的角色字符串规范化为两类角色。
 * <p>未知/缺失的角色按最小权限处理，一律视为普通用户，避免越权展示管理入口。</p>
 */
export function normalizeUserRole(role?: string | null): UserRole {
  return role === "admin" ? "admin" : "user";
}

/** 是否为管理员角色 */
export function isAdminRole(role?: string | null): boolean {
  return normalizeUserRole(role) === "admin";
}
