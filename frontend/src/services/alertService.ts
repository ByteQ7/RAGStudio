import { api } from "@/services/api";

export interface AlertConfig {
  id: string;
  enabled: number;
  smtpHost: string;
  smtpPort: number;
  smtpUsername: string;
  smtpPassword: string;
  fromAddress: string;
  toAddress: string;
  timeWindowHours: number;
  failureThreshold: number;
}

export async function getAlertConfig(): Promise<AlertConfig> {
  return api.get<AlertConfig, AlertConfig>("/rag/alert/config");
}

export async function saveAlertConfig(config: AlertConfig): Promise<void> {
  return api.put("/rag/alert/config", config);
}

export async function sendTestEmail(): Promise<void> {
  return api.post("/rag/alert/test");
}
