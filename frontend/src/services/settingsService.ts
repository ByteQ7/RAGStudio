import { api } from "@/services/api";

export interface SystemSettings {
  upload: {
    maxFileSize: number;
    maxRequestSize: number;
  };
  rag: {
    default: {
      collectionName: string;
      dimension: number;
      metricType: string;
    };
    queryRewrite: {
      enabled: boolean;
    };
    rateLimit: {
      global: {
        enabled: boolean;
        maxConcurrent: number;
        maxWaitSeconds: number;
        leaseSeconds: number;
        pollIntervalMs: number;
      };
    };
    memory: {
      historyKeepTurns: number;
      summaryStartTurns: number;
      summaryEnabled: boolean;
      summaryMaxChars: number;
      titleMaxLength: number;
    };
  };
  ai: {
    providers: Record<
      string,
      {
        url: string;
        apiKey?: string | null;
        endpoints: Record<string, string>;
      }
    >;
    selection: {
      failureThreshold: number;
      openDurationMs: number;
      toolRoutingModel?: string | null;
    };
    stream: {
      messageChunkSize: number;
    };
    chat: ModelGroup;
    embedding: ModelGroup;
    rerank: ModelGroup;
  };
}

export interface ModelGroup {
  defaultModel?: string | null;
  deepThinkingModel?: string | null;
  candidates: ModelCandidate[];
}

export interface ModelCandidate {
  id: string;
  provider: string;
  model: string;
  url?: string | null;
  dimension?: number | null;
  dimensions?: number[] | null;
  priority?: number | null;
  enabled?: boolean | null;
  supportsThinking?: boolean | null;
  supportsMultimodal?: boolean | null;
}

export async function getSystemSettings(): Promise<SystemSettings> {
  return api.get<SystemSettings, SystemSettings>("/rag/settings");
}

/**
 * 设置语义选择嵌入模型
 * 该模型同时承担「工具语义筛选」与「知识库语义选择」两类职责。
 */
export async function setSelectionEmbeddingModel(
  modelId: string | null
): Promise<void> {
  return api.post<void, void>("/rag/settings/selection-embedding-model", {
    toolRoutingModel: modelId
  });
}
