import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import type { AlertConfig } from "@/services/alertService";
import { getAlertConfig, saveAlertConfig, sendTestEmail } from "@/services/alertService";
import { getErrorMessage } from "@/utils/error";

export function AlertSettingsPage() {
  const [alertConfig, setAlertConfig] = useState<AlertConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);

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
      setTesting(true);
      await sendTestEmail();
      toast.success("测试邮件已发送，请检查收件箱");
    } catch (error) {
      toast.error(getErrorMessage(error, "发送测试邮件失败"));
    } finally {
      setTesting(false);
    }
  };

  const update = <K extends keyof AlertConfig>(key: K, value: AlertConfig[K]) => {
    setAlertConfig((prev) => (prev ? { ...prev, [key]: value } : prev));
  };

  // 数字输入清空时保持原值，避免 Number("") === 0 被误存为有效配置
  const updateNumber = (key: "smtpPort", raw: string) => {
    if (raw.trim() === "") return;
    const value = Number(raw);
    if (!Number.isNaN(value)) {
      update(key, value);
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">告警设置</h1>
          <p className="admin-page-subtitle">
            模型调用全部失败或频繁熔断时发送邮件通知管理员
          </p>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>邮件告警配置</CardTitle>
          <CardDescription>配置 SMTP 和告警触发条件</CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          {loading ? (
            <div className="text-sm text-muted-foreground flex items-center justify-center gap-2 py-8">
              <Loader2 className="w-4 h-4 animate-spin" /> 加载中...
            </div>
          ) : !alertConfig ? (
            <div className="text-sm text-muted-foreground text-center py-8">
              配置加载失败，请刷新重试
            </div>
          ) : (
            <>
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm font-medium">启用告警</div>
                  <div className="text-xs text-gray-500">开启后模型异常时自动发送通知邮件</div>
                </div>
                <label
                  htmlFor="alert-enabled-switch"
                  className="flex items-center gap-2 text-xs cursor-pointer"
                >
                  启用
                  <Switch
                    id="alert-enabled-switch"
                    checked={alertConfig.enabled === 1}
                    onCheckedChange={(checked) => update("enabled", checked ? 1 : 0)}
                  />
                </label>
              </div>

              <div className="border-t border-gray-100 pt-4">
                <h4 className="text-sm font-medium text-gray-700 mb-3">SMTP 配置</h4>
                <div className="grid gap-4 md:grid-cols-2">
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">SMTP 服务器</label>
                    <Input
                      placeholder="smtp.example.com"
                      value={alertConfig.smtpHost || ""}
                      onChange={(e) => update("smtpHost", e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">端口</label>
                    <Input
                      type="number"
                      value={alertConfig.smtpPort}
                      onChange={(e) => updateNumber("smtpPort", e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">SMTP 用户名</label>
                    <Input
                      value={alertConfig.smtpUsername || ""}
                      onChange={(e) => update("smtpUsername", e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">SMTP 密码</label>
                    <Input
                      type="password"
                      value={alertConfig.smtpPassword || ""}
                      onChange={(e) => update("smtpPassword", e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">发件人邮箱</label>
                    <Input
                      placeholder="RAGStudio &lt;noreply@example.com&gt;"
                      value={alertConfig.fromAddress || ""}
                      onChange={(e) => update("fromAddress", e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-medium text-gray-500">收件人邮箱</label>
                    <Input
                      type="email"
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
                      className="w-full"
                      style={{ accentColor: "hsl(var(--primary))" }}
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
                      className="w-full"
                      style={{ accentColor: "hsl(var(--primary))" }}
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
                <Button onClick={handleSave} disabled={saving}>
                  {saving ? "保存中..." : "保存配置"}
                </Button>
                <Button variant="outline" onClick={handleTest} disabled={testing}>
                  {testing ? "发送中..." : "发送测试邮件"}
                </Button>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
