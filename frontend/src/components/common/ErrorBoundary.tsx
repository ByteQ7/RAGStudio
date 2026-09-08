import * as React from "react";

import { Button } from "@/components/ui/button";

interface ErrorBoundaryProps {
  children: React.ReactNode;
  /** 出错时渲染的兜底 UI；不传则使用默认的整页错误卡片 */
  fallback?: React.ReactNode;
  /** 出错时额外通知调用方（如父组件据此切换到降级视图），不影响默认兜底渲染 */
  onError?: (error: Error, info: React.ErrorInfo) => void;
}

interface ErrorBoundaryState {
  hasError: boolean;
  message?: string;
}

export class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, message: error.message };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error("App error", error, info);
    this.props.onError?.(error, info);
  }

  handleReload = () => {
    window.location.reload();
  };

  render() {
    if (!this.state.hasError) {
      return this.props.children;
    }

    if (this.props.fallback !== undefined) {
      return <>{this.props.fallback}</>;
    }

    return (
      <div className="flex min-h-screen items-center justify-center px-6">
        <div className="chat-surface max-w-md rounded-3xl p-8 text-center">
          <p className="font-display text-xl font-semibold">出现了一点问题</p>
          <p className="mt-3 text-sm text-muted-foreground">{this.state.message}</p>
          <Button className="mt-6" onClick={this.handleReload}>
            刷新
          </Button>
        </div>
      </div>
    );
  }
}
