type Role = "admin" | "user";

interface RoleBadgeProps {
  role: Role;
  className?: string;
}

const roleConfig: Record<Role, { label: string; className: string }> = {
  admin: {
    label: "管理员",
    className: "bg-primary/10 text-primary border border-primary/20"
  },
  user: {
    label: "成员",
    className: "bg-[var(--color-fill-quaternary)] text-[var(--color-text-secondary)] border border-[var(--color-border-secondary)]"
  }
};

export function RoleBadge({ role, className = "" }: RoleBadgeProps) {
  const config = roleConfig[role];
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium leading-4 ${config.className} ${className}`}
    >
      {config.label}
    </span>
  );
}
