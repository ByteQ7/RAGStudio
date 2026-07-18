import * as React from "react";
import { useChatStore } from "@/stores/chatStore";

type LocalLocationStatus =
  | "requesting"
  | "success"
  | "error"
  | "denied"
  | "sent"
  | "unsupported";

/**
 * 位置请求组件
 *
 * 两步策略：
 * 1. 组件挂载后立即发起浏览器定位
 * 2. 定位成功后，等待 AI 流式结束（isStreaming = false），再发送位置消息
 *
 * 完全使用本地 state + ref，不依赖 store 中的 message 对象，
 * 避免消息 ID 在流式结束后被后端替换导致的状态丢失。
 */
export function LocationRequest() {
  const sendMessage = useChatStore((s) => s.sendMessage);
  const isStreaming = useChatStore((s) => s.isStreaming);

  const [status, setStatus] = React.useState<LocalLocationStatus>("requesting");
  const [manualCity, setManualCity] = React.useState("");
  const [showManualInput, setShowManualInput] = React.useState(false);

  // 用 ref 保存坐标和是否已触发/已发送的状态
  const coordsRef = React.useRef<{ lat: number; lng: number } | null>(null);
  const triggeredRef = React.useRef(false);
  const sentRef = React.useRef(false);

  // ---------- 效应 1：发起定位（仅挂载时一次） ----------
  React.useEffect(() => {
    if (triggeredRef.current) return;
    triggeredRef.current = true;

    if (!navigator.geolocation) {
      setStatus("unsupported");
      setShowManualInput(true);
      return;
    }

    setStatus("requesting");

    // 使用 watchPosition 替代 getCurrentPosition：
    // watchPosition 持续监听位置变化，多一次机会拿到更精确的位置。
    //
    // 策略：首次回调无论快慢都接受为备选位置（保存到 coordsRef）；
    // 如果后续有新回调且精度更好则更新；同时设一个兜底超时，
    // 超时后只要有位置就标记 success，否则 fallback 到手动输入。
    // 注意：不能直接丢弃 < 3s 的首次回调——桌面浏览器无 GPS，
    // watchPosition 只有一次基于 IP 的回调，丢弃后会永远卡在 requesting 状态。
    const FALLBACK_TIMEOUT_MS = 8000;
    const startTs = Date.now();
    let bestAccuracy = Infinity;
    let watchId: number;

    const fallbackTimer = window.setTimeout(() => {
      // 兜底超时：有位置就用，没有则 fallback 到手动输入
      if (coordsRef.current) {
        navigator.geolocation.clearWatch(watchId);
        setStatus("success");
      } else {
        // 经过足够长时间仍无任何位置 → 降级到手动输入
        navigator.geolocation.clearWatch(watchId);
        setStatus("error");
        setShowManualInput(true);
      }
    }, FALLBACK_TIMEOUT_MS);

    watchId = navigator.geolocation.watchPosition(
      (pos) => {
        const accuracy = pos.coords.accuracy;
        // 保存坐标：首次获取或精度比之前更好时更新
        if (!coordsRef.current || accuracy < bestAccuracy) {
          bestAccuracy = accuracy;
          coordsRef.current = {
            lat: pos.coords.latitude,
            lng: pos.coords.longitude,
          };
        }

        // 如果已拿到足够精确的位置（< 100m），立即使用，不等超时
        // 或者如果已经过去至少 3 秒，也可以用了（避免无限等更精确位置）
        const elapsed = Date.now() - startTs;
        if (accuracy < 100 || elapsed >= 3000) {
          window.clearTimeout(fallbackTimer);
          navigator.geolocation.clearWatch(watchId);
          setStatus("success");
        }
        // 否则继续等更好的位置（兜底计时器会处理超时）
      },
      (err) => {
        window.clearTimeout(fallbackTimer);
        navigator.geolocation.clearWatch(watchId);
        if (err.code === err.PERMISSION_DENIED) {
          setStatus("denied");
        } else {
          setStatus("error");
        }
        setShowManualInput(true);
      },
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }
    );
  }, []);

  // ---------- 效应 2：定位成功后，等流式结束再发送 ----------
  React.useEffect(() => {
    if (status !== "success" || !coordsRef.current) return;
    if (sentRef.current) return;
    // 流式还在进行中 → 等待下一次 isStreaming 变 false
    if (isStreaming) return;

    sentRef.current = true;
    const { lat, lng } = coordsRef.current;
    sendMessage(`我的位置：纬度 ${lat.toFixed(4)}, 经度 ${lng.toFixed(4)}`);
    setStatus("sent");
  }, [status, isStreaming, sendMessage]);

  // ==================== 手动输入 ====================

  const handleManualSubmit = async () => {
    const city = manualCity.trim();
    if (!city) return;
    setManualCity("");
    setShowManualInput(false);
    setStatus("sent");
    await new Promise((r) => setTimeout(r, 300));
    await sendMessage(`我的位置：${city}`);
  };

  const renderManualInput = () => (
    <div className="flex gap-2">
      <input
        type="text"
        value={manualCity}
        onChange={(e) => setManualCity(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") handleManualSubmit();
        }}
        placeholder="输入城市名，如：北京"
        className="min-w-0 flex-1 rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm
                   text-gray-700 placeholder:text-gray-400 focus:border-indigo-300 focus:outline-none
                   focus:ring-2 focus:ring-indigo-100"
      />
      <button
        type="button"
        onClick={handleManualSubmit}
        disabled={!manualCity.trim()}
        className="inline-flex shrink-0 items-center rounded-lg bg-indigo-600 px-3.5 py-2 text-sm
                   font-medium text-white shadow-sm transition-all hover:bg-indigo-700
                   active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-50"
      >
        确定
      </button>
    </div>
  );

  // ==================== 渲染 ====================

  if (status === "sent") {
    return (
      <div className="mt-2 flex items-center gap-1.5 text-xs text-green-600">
        <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
          <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0118 0z" />
          <circle cx="12" cy="10" r="3" />
        </svg>
        <span>位置已获取</span>
      </div>
    );
  }

  if (status === "requesting") {
    return (
      <div className="mt-2 flex items-center gap-2 text-xs text-indigo-600">
        <svg className="h-3.5 w-3.5 animate-pulse" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
          <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0118 0z" />
          <circle cx="12" cy="10" r="3" />
        </svg>
        <span>正在获取您的位置...</span>
      </div>
    );
  }

  if (status === "success") {
    return (
      <div className="mt-2 flex items-center gap-1.5 text-xs text-green-600">
        <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
          <polyline points="20 6 9 17 4 12" />
        </svg>
        <span>位置已获取，正在发送...</span>
      </div>
    );
  }

  if (status === "unsupported") {
    return (
      <div className="mt-2 space-y-2">
        <p className="text-xs text-amber-600">⚠ 浏览器不支持定位功能，请手动输入</p>
        {renderManualInput()}
      </div>
    );
  }

  if (showManualInput) {
    return (
      <div className="mt-2 space-y-2">
        {status === "denied" && (
          <p className="text-xs text-amber-600">⚠ 定位权限被拒绝，请手动输入位置</p>
        )}
        {status === "error" && (
          <p className="text-xs text-amber-600">⚠ 无法自动获取位置</p>
        )}
        {renderManualInput()}
      </div>
    );
  }

  return null;
}
