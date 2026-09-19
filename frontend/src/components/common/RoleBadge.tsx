import type { UserRole } from "@/types";
import { normalizeUserRole, USER_ROLE_LABELS } from "@/utils/role";

interface RoleBadgeProps {
  role: UserRole;
  className?: string;
}

const roleStyles: Record<UserRole, string> = {
  admin: "bg-primary/10 text-primary border border-primary/20",
  user: "bg-[var(--color-fill-quaternary)] text-[var(--color-text-secondary)] border border-[var(--color-border-secondary)]"
};

export function RoleBadge({ role, className = "" }: RoleBadgeProps) {
  const normalized = normalizeUserRole(role);
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium leading-4 ${roleStyles[normalized]} ${className}`}
    >
      {USER_ROLE_LABELS[normalized]}
    </span>
  );
}
