import * as React from "react";

interface SpeechRecognitionResult {
  transcript: string;
  isFinal: boolean;
}

interface UseSpeechRecognitionReturn {
  isListening: boolean;
  isSupported: boolean;
  start: () => void;
  stop: () => void;
  toggle: () => void;
}

/**
 * 语音识别 Hook（基于浏览器 Web Speech API）
 *
 * 调用 `start()` 开始录音并实时转文字，通过 `onResult` 回调返回识别结果。
 * 浏览器原生支持中文，无需后端服务。
 *
 * @param onResult  每段识别结果的回调（isFinal=true 表示确定结果）
 * @param onEnd     语音识别结束时的回调
 * @param lang      识别语言（默认 zh-CN）
 */
export function useSpeechRecognition(
  onResult: (result: SpeechRecognitionResult) => void,
  onEnd?: () => void,
  lang: string = "zh-CN"
): UseSpeechRecognitionReturn {
  const [isListening, setIsListening] = React.useState(false);
  const [isSupported, setIsSupported] = React.useState(false);
  const recognitionRef = React.useRef<SpeechRecognition | null>(null);

  React.useEffect(() => {
    const SpeechRecognitionAPI =
      (window as unknown as { SpeechRecognition?: new () => SpeechRecognition }).SpeechRecognition ??
      (window as unknown as { webkitSpeechRecognition?: new () => SpeechRecognition }).webkitSpeechRecognition;

    setIsSupported(Boolean(SpeechRecognitionAPI));

    if (SpeechRecognitionAPI) {
      const recognition = new SpeechRecognitionAPI();
      recognition.lang = lang;
      recognition.interimResults = true;
      recognition.continuous = true;

      recognition.onresult = (event: SpeechRecognitionEvent) => {
        for (let i = event.resultIndex; i < event.results.length; i++) {
          const result = event.results[i];
          onResult({
            transcript: result[0].transcript,
            isFinal: result.isFinal,
          });
        }
      };

      recognition.onend = () => {
        setIsListening(false);
        onEnd?.();
      };

      recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
        console.warn("语音识别错误:", event.error);
        setIsListening(false);
        onEnd?.();
      };

      recognitionRef.current = recognition;
    }

    return () => {
      recognitionRef.current?.abort();
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const start = React.useCallback(() => {
    if (!recognitionRef.current) return;
    try {
      recognitionRef.current.start();
      setIsListening(true);
    } catch (e) {
      // 已经在录音中时调用 start 会抛异常
      console.warn("语音启动失败:", e);
    }
  }, []);

  const stop = React.useCallback(() => {
    if (!recognitionRef.current) return;
    recognitionRef.current.stop();
    setIsListening(false);
  }, []);

  const toggle = React.useCallback(() => {
    if (isListening) {
      stop();
    } else {
      start();
    }
  }, [isListening, start, stop]);

  return { isListening, isSupported, start, stop, toggle };
}
