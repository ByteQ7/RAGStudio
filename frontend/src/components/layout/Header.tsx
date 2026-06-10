import * as React from "react";
import { Menu } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useChatStore } from "@/stores/chatStore";

interface HeaderProps {
  onToggleSidebar: () => void;
}

export function Header({ onToggleSidebar }: HeaderProps) {
  const { currentSessionId, sessions } = useChatStore();
  const currentSession = React.useMemo(
    () => sessions.find((session) => session.id === currentSessionId),
    [sessions, currentSessionId]
  );

  return (
    <header className="sticky top-0 z-20 border-b border-gray-100 bg-white/90 backdrop-blur-md">
      <div className="flex h-14 items-center gap-3 px-5">
        <Button
          variant="ghost"
          size="icon"
          onClick={onToggleSidebar}
          aria-label="切换侧边栏"
          className="h-9 w-9 rounded-lg text-gray-400 hover:bg-gray-50 hover:text-gray-600 lg:hidden"
        >
          <Menu className="h-[18px] w-[18px]" />
        </Button>
        <h2 className="text-[14px] font-semibold text-gray-800 tracking-tight truncate max-w-[280px]">
          {currentSession?.title || "新会话"}
        </h2>
      </div>
    </header>
  );
}
