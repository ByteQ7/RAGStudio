#!/usr/bin/env python3
"""RAGStudio RAG 性能基线脚本

对 /rag/v3/chat 的 SSE 流逐事件打点，输出各阶段耗时分位数：
- TTFT：请求开始 → 第一条 message 事件
- rag_search：agent_step 中 rag_search 工具执行耗时（durationMs / observation 文本）
- FINISH：请求开始 → finish 事件
- DONE：请求开始 → done 事件

用法：
  python3 scripts/rag-perf.py                      # 默认内置 5 个问题，串行
  python3 scripts/rag-perf.py --kb 2079550081669562368
  python3 scripts/rag-perf.py --questions file.txt --repeat 2 --concurrent 4
"""
import argparse
import json
import statistics
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE_URL = "http://localhost:9090/api/ragstudio"

DEFAULT_QUESTIONS = [
    "公司年假有多少天？",
    "员工请病假期间工资如何计算？",
    "休息日加班费按基本工资的多少计算？",
    "数据库全量备份保留多长时间？",
    "专业版产品月费是多少？",
]


def log(msg):
    print(msg, flush=True)


def api_request(url, data, headers=None, timeout=30):
    req = urllib.request.Request(url, data=json.dumps(data).encode("utf-8"),
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def login(base_url, user, password):
    resp = api_request(f"{base_url}/auth/login", {"username": user, "password": password})
    return resp["data"]["token"]


def run_one(base_url, token, question, kb_ids, timeout):
    """单次对话：逐 SSE 事件打点，返回阶段耗时字典"""
    started = time.monotonic()
    t_meta = t_first_message = t_rag_done = t_finish = t_done = None
    rag_durations = []
    body = json.dumps({"question": question, "deepThinkingLevel": 0,
                       "knowledgeBaseIds": kb_ids or []}).encode("utf-8")
    req = urllib.request.Request(f"{base_url}/rag/v3/chat", data=body,
                                 headers={"Content-Type": "application/json",
                                          "Authorization": token, "Accept": "text/event-stream"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        buf = b""
        while True:
            chunk = resp.read(4096)
            if not chunk:
                break
            buf += chunk
            # 按行解析已到达的 SSE 帧
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                if not line.strip():
                    continue
                if line.startswith(b"event:"):
                    continue
                if not line.startswith(b"data:"):
                    continue
                now = time.monotonic()
                try:
                    payload = json.loads(line[5:].decode("utf-8", errors="ignore"))
                except Exception:
                    continue
                # DONE 事件的 data 为纯字符串 "[DONE]"，需在 dict 判断前处理
                if payload == "[DONE]":
                    if t_done is None:
                        t_done = now - started
                    break
                if not isinstance(payload, dict):
                    continue
                if "conversationId" in payload and t_meta is None:
                    t_meta = now - started
                if payload.get("type") == "response" and payload.get("delta"):
                    if t_first_message is None:
                        t_first_message = now - started
                if payload.get("toolName") == "rag_search":
                    if payload.get("observation") is not None and t_rag_done is None:
                        t_rag_done = now - started
                        m = __import__("re").search(r"执行成功 \(([0-9]+)ms\)", payload["observation"])
                        if m:
                            rag_durations.append(int(m.group(1)))
                if payload.get("action") == "FINISH" and t_finish is None:
                    t_finish = now - started
            if t_done is not None:
                break

    return {
        "ttft": t_first_message,
        "rag_search_tool_ms": t_rag_done,
        "finish": t_finish,
        "done": t_done,
        "rag_search_durations": rag_durations,
    }


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    if p >= 1:
        return s[-1]
    # nearest-rank：取第 ceil(n*p) 个（1-based），保证小样本下 p95 也是真实高分位值
    idx = min(len(s) - 1, max(0, int(len(s) * p)))
    return s[idx]


def fmt(v, unit="ms"):
    if v is None:
        return "-"
    return f"{v:.0f}{unit}" if unit else f"{v:.2f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=BASE_URL)
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="password", default="admin")
    ap.add_argument("--kb", default="", help="逗号分隔的知识库 ID 列表")
    ap.add_argument("--questions", default="", help="问题文件（每行一个）；缺省用内置 5 个问题")
    ap.add_argument("--repeat", type=int, default=1, help="每个问题重复次数")
    ap.add_argument("--concurrent", type=int, default=1, help="并发数")
    ap.add_argument("--timeout", type=int, default=120, help="单请求超时秒数")
    args = ap.parse_args()

    if args.questions:
        questions = [l.strip() for l in Path(args.questions).read_text(encoding="utf-8").splitlines() if l.strip()]
    else:
        questions = DEFAULT_QUESTIONS
    kb_ids = [x.strip() for x in args.kb.split(",") if x.strip()]

    log(f"▶ 登录 {args.base_url} ...")
    token = login(args.base_url, args.user, args.password)
    log(f"  ✓ 已获取 TOKEN")

    tasks = [(q, i) for q in questions for i in range(args.repeat)]
    results = []
    total = len(tasks)
    done_count = 0

    def worker(task):
        q, _ = task
        return q, run_one(args.base_url, token, q, kb_ids, args.timeout)

    t0 = time.monotonic()
    with ThreadPoolExecutor(max_workers=args.concurrent) as ex:
        for q, r in ex.map(worker, tasks):
            done_count += 1
            results.append(r)
            elapsed = time.monotonic() - t0
            log(f"  [{done_count:03d}/{total}] ttft={fmt(r['ttft'], 's')} rag_search={fmt(r['rag_search_tool_ms'], 's')} "
                f"done={fmt(r['done'], 's')} ({elapsed:.1f}s elapsed)")

    log("")
    log(f"▶ 汇总（{total} 次请求）")

    def report(name, key):
        vals = [r[key] for r in results if r.get(key) is not None]
        if not vals:
            log(f"  {name:<20} 无数据")
            return
        log(f"  {name:<20} min={fmt(min(vals), 's')} p50={fmt(pct(vals, 0.5), 's')} "
            f"p90={fmt(pct(vals, 0.9), 's')} p95={fmt(pct(vals, 0.95), 's')} max={fmt(max(vals), 's')}")

    report("TTFT（首包）", "ttft")
    report("rag_search 完成", "rag_search_tool_ms")
    report("FINISH", "finish")
    report("DONE", "done")

    # rag_search 工具内部耗时（SSE observation 中的 durationMs）
    inner = [d for r in results for d in r["rag_search_durations"]]
    if inner:
        log(f"  {'rag_search 工具耗时':<20} count={len(inner)} min={fmt(min(inner))} "
            f"p50={fmt(pct(inner, 0.5))} p95={fmt(pct(inner, 0.95))} max={fmt(max(inner))}")

    out = ROOT / "logs" / f"rag-perf-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"\n原始数据已保存: {out}")


if __name__ == "__main__":
    sys.exit(main())
