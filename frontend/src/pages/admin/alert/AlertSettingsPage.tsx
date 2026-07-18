import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { AlertConfig } from "@/services/alertService";
import { getAlertConfig, saveAlertConfig, sendTestEmail } from "@/services/alertService";
import { getErrorMessage } from "@/utils/error";

export function AlertSettingsPage() {
  const [alertConfig, setAlertConfig] = useState<AlertConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    loadConfig();
  }, []);

  const loadConfig = async () => {
    try {
      setLoading(true);
      const data = await getAlertConfig();
      setAlertConfig(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载告警配置失败"));
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async () => {
    if (!alertConfig) return;
    try {
      setSaving(true);
      await saveAlertConfig(alertConfig);
      toast.success("告警配置已保存");
    } catch (error) {
      toast.error(getErrorMessage(error, "保存告警配置失败"));
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    try {
      await sendTestEmail();
      toast.success("测试邮件已发送，请检查收件箱");
    } catch (error) {
      toast.error(getErrorMessage(error, "发送测试邮件失败"));
    }
  };

  const update = <K extends keyof AlertConfig>(key: K, value: AlertConfig[K]) => {
    setAlertConfig((prev) => (prev ? { ...prev, [key]: value } : prev));
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h2 className="admin-page-title">邮件告警配置</h2>
        <p className="admin-page-description">
          模型调用全部失败或频繁熔断时发送邮件通知管理员
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>告警设置</CardTitle>
          <CardDescription>配置 SMTP 和告警触发条件</CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          {loading ? (
            <div className="text-sm text-muted-foreground">加载中...</div>
          ) : !alertConfig ? (
            <div className="text-sm text-red-500">加载失败</div>
          ) : (
            <>
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm font-medium">启用告警</div>
                  <div className="text-xs text-gray-500">开启后模型异常时自动发送通知邮件</div>
                </div>
                <label className="relative inline-flex h-6 w-11 cursor-pointer items-center">
                  <input
                    type="checkbox"
                    className="peer sr-only"
                    checked={alertConfig.enabled === 1}
                    onChange={(e) => update("enabled", e.target.checked ? 1 : 0)}
                  />
                  <span className="absolute inset-0 rounded-full bg-gray-300 transition peer-checked:bg-blue-600" />
                  <span className="absolute left-0.5 h-5 w-5 rounded-full bg-white transition peer-checked:translate-x-5" />
                </label>
              </div>

              <div className="border-t border-gray-100 pt-4">
                <h4 className="text-sm font-medium text-gray-700 mb-3">SMTP 配置</h4>
                <div className="grid gap-4 md:grid-cols-2">
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">SMTP 服务器</label>
                    <input
                      type="text"
                      className="rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-200"
                      placeholder="smtp.example.com"
                      value={alertConfig.smtpHost || ""}
                      onChange={(e) => update("smtpHost", e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">端口</label>
                    <input
                      type="number"
                      className="rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-200"
                      value={alertConfig.smtpPort}
                      onChange={(e) => update("smtpPort", Number(e.target.value))}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">SMTP 用户名</label>
                    <input
                      type="text"
                      className="rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-200"
                      value={alertConfig.smtpUsername || ""}
                      onChange={(e) => update("smtpUsername", e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">SMTP 密码</label>
                    <input
                      type="password"
                      className="rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-200"
                      value={alertConfig.smtpPassword || ""}
                      onChange={(e) => update("smtpPassword", e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">发件人邮箱</label>
                    <input
                      type="text"
                      className="rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-200"
                      placeholder="RAGStudio &lt;noreply@example.com&gt;"
                      value={alertConfig.fromAddress || ""}
                      onChange={(e) => update("fromAddress", e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">收件人邮箱</label>
                    <input
                      type="email"
                      className="rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-200"
                      placeholder="admin@example.com"
                      value={alertConfig.toAddress || ""}
                      onChange={(e) => update("toAddress", e.target.value)}
                    />
                  </div>
                </div>
              </div>

              <div className="border-t border-gray-100 pt-4">
                <h4 className="text-sm font-medium text-gray-700 mb-3">告警触发条件</h4>
                <div className="grid gap-6 md:grid-cols-2">
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">
                      统计时间窗口：<span className="font-semibold text-gray-800">{alertConfig.timeWindowHours} 小时</span>
                    </label>
                    <input
                      type="range"
                      min={1}
                      max={24}
                      className="w-full accent-blue-600"
                      value={alertConfig.timeWindowHours}
                      onChange={(e) => update("timeWindowHours", Number(e.target.value))}
                    />
                    <div className="flex justify-between text-xs text-gray-400">
                      <span>1 小时</span><span>24 小时</span>
                    </div>
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">
                      熔断次数阈值：<span className="font-semibold text-gray-800">{alertConfig.failureThreshold} 次</span>
                    </label>
                    <input
                      type="range"
                      min={1}
                      max={10}
                      className="w-full accent-blue-600"
                      value={alertConfig.failureThreshold}
                      onChange={(e) => update("failureThreshold", Number(e.target.value))}
                    />
                    <div className="flex justify-between text-xs text-gray-400">
                      <span>1 次</span><span>10 次</span>
                    </div>
                  </div>
                </div>
              </div>

              <div className="flex gap-3 pt-2">
                <button
                  className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
                  disabled={saving}
                  onClick={handleSave}
                >
                  {saving ? "保存中..." : "保存配置"}
                </button>
                <button
                  className="rounded-lg border border-gray-300 px-5 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors"
                  onClick={handleTest}
                >
                  发送测试邮件
                </button>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
