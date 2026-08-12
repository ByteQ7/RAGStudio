import { lazy, Suspense } from "react";
import { Navigate, createBrowserRouter } from "react-router-dom";

import { LoginPage } from "@/pages/LoginPage";
import { ChatPage } from "@/pages/ChatPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { AdminLayout } from "@/pages/admin/AdminLayout";
import { useAuthStore } from "@/stores/authStore";
import { Loading } from "@/components/common/Loading";

const lazyPage = <T extends Record<string, unknown>, K extends keyof T>(importer: () => Promise<T>, namedExport: K) =>
  lazy(() => importer().then((m) => ({ default: m[namedExport] as unknown as React.ComponentType<unknown> })));

const DashboardPage = lazyPage(() => import("@/pages/admin/dashboard/DashboardPage"), "DashboardPage");
const KnowledgeListPage = lazyPage(() => import("@/pages/admin/knowledge/KnowledgeListPage"), "KnowledgeListPage");
const KnowledgeDocumentsPage = lazyPage(() => import("@/pages/admin/knowledge/KnowledgeDocumentsPage"), "KnowledgeDocumentsPage");
const KnowledgeChunksPage = lazyPage(() => import("@/pages/admin/knowledge/KnowledgeChunksPage"), "KnowledgeChunksPage");
const IngestionPage = lazyPage(() => import("@/pages/admin/ingestion/IngestionPage"), "IngestionPage");
const RagTracePage = lazyPage(() => import("@/pages/admin/traces/RagTracePage"), "RagTracePage");
const RagTraceDetailPage = lazyPage(() => import("@/pages/admin/traces/RagTraceDetailPage"), "RagTraceDetailPage");
const SystemSettingsPage = lazyPage(() => import("@/pages/admin/settings/SystemSettingsPage"), "SystemSettingsPage");
const AlertSettingsPage = lazyPage(() => import("@/pages/admin/alert/AlertSettingsPage"), "AlertSettingsPage");
const SampleQuestionPage = lazyPage(() => import("@/pages/admin/sample-questions/SampleQuestionPage"), "SampleQuestionPage");
const QueryTermMappingPage = lazyPage(() => import("@/pages/admin/query-term-mapping/QueryTermMappingPage"), "QueryTermMappingPage");
const UserListPage = lazyPage(() => import("@/pages/admin/users/UserListPage"), "UserListPage");
const McpServerPage = lazyPage(() => import("@/pages/admin/mcp/McpServerPage"), "McpServerPage");
const AiModelConfigPage = lazyPage(() => import("@/pages/admin/ai-models/AiModelConfigPage"), "AiModelConfigPage");
const DefaultModelConfigPage = lazyPage(() => import("@/pages/admin/defaults/DefaultModelConfigPage"), "DefaultModelConfigPage");
const SkillListPage = lazyPage(() => import("@/pages/admin/skills/SkillListPage"), "SkillListPage");

function SuspenseFallback() {
  return (
    <div className="flex h-full items-center justify-center">
      <Loading label="加载中" />
    </div>
  );
}

function LazyPage({ Component }: { Component: React.LazyExoticComponent<React.ComponentType<unknown>> }) {
  return (
    <Suspense fallback={<SuspenseFallback />}>
      <Component />
    </Suspense>
  );
}

function RequireAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

function RequireAdmin({ children }: { children: JSX.Element }) {
  const user = useAuthStore((state) => state.user);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (user?.role !== "admin") {
    return <Navigate to="/chat" replace />;
  }

  return children;
}

function RedirectIfAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (isAuthenticated) {
    return <Navigate to="/chat" replace />;
  }
  return children;
}

function HomeRedirect() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return <Navigate to={isAuthenticated ? "/chat" : "/login"} replace />;
}

export const router = createBrowserRouter([
  {
    path: "/",
    element: <HomeRedirect />
  },
  {
    path: "/login",
    element: (
      <RedirectIfAuth>
        <LoginPage />
      </RedirectIfAuth>
    )
  },
  {
    path: "/chat",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/chat/:sessionId",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/admin",
    element: (
      <RequireAdmin>
        <AdminLayout />
      </RequireAdmin>
    ),
    children: [
      {
        index: true,
        element: <Navigate to="/admin/dashboard" replace />
      },
      {
        path: "dashboard",
        element: <LazyPage Component={DashboardPage} />
      },
      {
        path: "knowledge",
        element: <LazyPage Component={KnowledgeListPage} />
      },
      {
        path: "knowledge/:kbId",
        element: <LazyPage Component={KnowledgeDocumentsPage} />
      },
      {
        path: "knowledge/:kbId/docs/:docId",
        element: <LazyPage Component={KnowledgeChunksPage} />
      },
      {
        path: "ingestion",
        element: <LazyPage Component={IngestionPage} />
      },
      {
        path: "traces",
        element: <LazyPage Component={RagTracePage} />
      },
      {
        path: "traces/:traceId",
        element: <LazyPage Component={RagTraceDetailPage} />
      },
      {
        path: "settings",
        element: <LazyPage Component={SystemSettingsPage} />
      },
      {
        path: "alert",
        element: <LazyPage Component={AlertSettingsPage} />
      },
      {
        path: "sample-questions",
        element: <LazyPage Component={SampleQuestionPage} />
      },
      {
        path: "mappings",
        element: <LazyPage Component={QueryTermMappingPage} />
      },
      {
        path: "users",
        element: <LazyPage Component={UserListPage} />
      },
      {
        path: "mcp-servers",
        element: <LazyPage Component={McpServerPage} />
      },
      {
        path: "ai-models",
        element: <LazyPage Component={AiModelConfigPage} />
      },
      {
        path: "defaults",
        element: <LazyPage Component={DefaultModelConfigPage} />
      },
      {
        path: "skills",
        element: <LazyPage Component={SkillListPage} />
      }
    ]
  },
  {
    path: "*",
    element: <NotFoundPage />
  }
]);
