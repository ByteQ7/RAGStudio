#!/usr/bin/env python3
"""RAGStudio LLM 评测工具

流程：
1. 读取 scripts/eval-cases.md（每个用例：知识库 + 用户问题 + 参考资料原文）
2. 逐条调用 RAG 系统 /rag/v3/chat 获取 AI 回答
3. 将「参考资料 + 用户问题 + AI回答」交给另一个 LLM 打分（1 / 0.5 / 0）
4. 输出逐条报告 + 汇总统计

用法：
  python3 scripts/llm-eval.py                    # 完整流程
  python3 scripts/llm-eval.py --limit 5          # 只跑前 5 条
  python3 scripts/llm-eval.py --reuse logs/llm-eval-xxx  # 复用已有 SSE 日志，只重打分
  python3 scripts/llm-eval.py --concurrent 4     # 并发执行对话

打分 LLM 配置（环境变量，默认取百炼 qwen3.5-plus）：
  GRADER_BASE_URL / GRADER_API_KEY / GRADER_MODEL
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CASES_FILE = ROOT / "scripts" / "eval-cases.md"
LOG_ROOT = ROOT / "logs"
TIMEOUT = 90

GRADER_RUBRIC = """你是一个严谨的 RAG 系统质量评估员。下面给出【参考答案】（来自知识库文档的原文片段）、【用户问题】、【AI回答】。

【重要】参考答案只是知识库文档中与问题最相关的一段片段，是评分的基准，但不是文档全文。AI回答基于完整知识库文档回答，可能包含参考答案片段之外的、同样来自知识库的合理补充信息。因此：

1. 只有当 AI 回答与参考答案【矛盾】时才判错（关键数字、名称、结论等不一致）；
2. 参考答案中没提到、但 AI 回答给出的额外细节：若与参考答案不冲突且看起来来自文档内容（如相关制度、条款、补充说明），不视为编造，不扣分；
3. 若 AI 回答包含参考答案之外的合理补充信息，且不影响核心结论的正确性，评分不应因此降级；
4. AI 回答是否完整覆盖了问题的要点；
5. 若 AI 回答声称"未检索到相关信息"，而参考答案中明明包含答案，判 0 分。
6. 只有明显不属于企业知识文档范畴、纯属虚构的信息（如凭空捏造的数字、不存在的制度），才算编造。

评分：
- 1 分：回答准确、完整，与参考答案不矛盾，核心要点覆盖完整。
- 0.5 分：回答部分正确（缺关键要点、核心结论对但不完整、或包含与参考答案矛盾的信息）。
- 0 分：回答错误、与参考答案矛盾、凭空捏造核心信息、或"未检索到"而参考答案有答案、或回答为空。

注意：
- 若 AI 回答的表述/单位与参考答案不同但实质一致（如 "5000元" vs "5,000 元"），视为正确。
- 若 AI 回答包含参考答案之外的合理补充信息（不矛盾、不捏造），视为正确，不得降分。
只输出一个 JSON 对象，不要输出其他任何内容，格式：{"score": 0.5, "reason": "简短的中文理由"}"""


def log(msg):
    print(msg, flush=True)


def parse_cases(path):
    cases = []
    cur = None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("## "):
            if cur:
                cases.append(cur)
            title = line[3:].strip()
            m = re.match(r"(\d+)\.\s*(.*)", title)
            num = int(m.group(1)) if m else (len(cases) + 1)
            label = m.group(2) if m else title
            cur = {"num": num, "label": label, "kb": None, "q": "", "ref": ""}
        elif line.startswith("KB:") and cur:
            cur["kb"] = line[3:].strip()
        elif line.startswith("Q:") and cur:
            cur["q"] = line[2:].strip()
        elif line.startswith("REF:") and cur:
            cur["ref"] = line[4:].strip()
    if cur:
        cases.append(cur)
    for c in cases:
        if not c["kb"] or not c["q"] or not c["ref"]:
            raise ValueError(f"用例 {c.get('num')} {c.get('label')} 缺少 KB/Q/REF 字段")
    return cases


def api_request(url, data, headers=None, timeout=30):
    req = urllib.request.Request(url, data=json.dumps(data).encode("utf-8"),
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def login(base_url, user, password):
    resp = api_request(f"{base_url}/auth/login", {"username": user, "password": password})
    return resp["data"]["token"]


def parse_sse(raw):
    answer = ""
    msgs = []
    citations = 0
    in_msg = False
    for line in raw.split("\n"):
        line = line.strip()
        if line.startswith("event:"):
            e = line[6:].strip()
            in_msg = e == "message"
            if e == "citation":
                citations += 1
        elif line.startswith("data:"):
            try:
                p = json.loads(line[5:])
            except Exception:
                continue
            if not isinstance(p, dict):
                continue
            if p.get("action") == "FINISH" and p.get("finalAnswer"):
                answer = p["finalAnswer"]
            if in_msg and p.get("type") == "response" and p.get("delta"):
                msgs.append(p["delta"])
    return (answer or "".join(msgs) or "").strip(), citations


def chat(base_url, token, case, out_dir):
    tag = f"{case['num']:03d}-{re.sub(r'[^a-zA-Z0-9_-]', '', case['label'])[:30]}"
    req = {"question": case["q"], "deepThinkingLevel": 0}
    if case["kb"]:
        req["knowledgeBaseIds"] = [x for x in case["kb"].split(",") if x]
    (out_dir / f"{tag}.req.json").write_text(json.dumps(req, ensure_ascii=False), encoding="utf-8")
    body = json.dumps(req, ensure_ascii=False).encode("utf-8")
    req_http = urllib.request.Request(f"{base_url}/rag/v3/chat", data=body,
                                      headers={"Content-Type": "application/json",
                                               "Authorization": token})
    try:
        with urllib.request.urlopen(req_http, timeout=TIMEOUT) as resp:
            raw = resp.read().decode("utf-8")
    except Exception as e:
        raw = f"__ERROR__ {e}"
    (out_dir / f"{tag}.sse").write_text(raw, encoding="utf-8")
    if raw.startswith("__ERROR__"):
        return None, 0
    return parse_sse(raw)


def load_saved(case, out_dir):
    tag = f"{case['num']:03d}-{re.sub(r'[^a-zA-Z0-9_-]', '', case['label'])[:30]}"
    sse = out_dir / f"{tag}.sse"
    if not sse.exists():
        cands = sorted(out_dir.glob(f"{case['num']:03d}-*.sse"))
        if not cands:
            return None, 0, f"{tag}.sse 不存在"
        sse = cands[0]
    raw = sse.read_text(encoding="utf-8", errors="ignore")
    if raw.startswith("__ERROR__"):
        return None, 0, raw
    ans, cites = parse_sse(raw)
    return ans, cites, ""


def grade_llm(cfg, case, answer):
    prompt = (
        "【参考资料】\n" + case["ref"] + "\n\n"
        "【用户问题】\n" + case["q"] + "\n\n"
        "【AI回答】\n" + (answer if answer else "（AI 未给出任何回答）")
    )
    data = {
        "model": cfg["model"],
        "messages": [
            {"role": "system", "content": GRADER_RUBRIC},
            {"role": "user", "content": prompt},
        ],
        "max_tokens": 800,
        "temperature": 0,
        # 打分任务只需要结果 JSON，关闭思考可大幅降低每次调用的推理耗时；
        # 非思考模型不支持该参数时由上游兼容处理（不支持则忽略）
        "enable_thinking": False,
    }
    url = cfg["base_url"].rstrip("/") + "/v1/chat/completions"
    req = urllib.request.Request(url, data=json.dumps(data).encode("utf-8"),
                                 headers={"Content-Type": "application/json",
                                          "Authorization": f"Bearer {cfg['api_key']}"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            content = body["choices"][0]["message"].get("content") or ""
            content = content.strip()
            if not content and body["choices"][0]["message"].get("reasoning_content"):
                content = "(thinking only)"
            m = re.search(r"\{.*\}", content, re.S)
            if not m:
                raise ValueError(f"非 JSON 输出: {content[:100]}")
            parsed = json.loads(m.group(0))
            score = float(parsed.get("score", 0))
            score = 1.0 if score >= 1 else (0.5 if score >= 0.5 else 0.0)
            return score, parsed.get("reason", "")[:300]
        except Exception as e:
            if attempt == 2:
                return 0.0, f"打分 LLM 调用失败: {e}"
            time.sleep(2 * (attempt + 1))
    return 0.0, "打分失败"


def load_grader_config():
    base_url = os.environ.get("GRADER_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode")
    model = os.environ.get("GRADER_MODEL", "qwen3.5-plus")
    api_key = os.environ.get("GRADER_API_KEY", "")
    if not api_key:
        env = {}
        for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines() if (ROOT / ".env").exists() else []:
            if "=" in line and not line.strip().startswith("#"):
                k, _, v = line.partition("=")
                env[k.strip()] = v.strip()
        db_user = env.get("DB_USERNAME", "postgres")
        db_pass = env.get("DB_PASSWORD", "postgres")
        try:
            out = subprocess.run(
                ["psql", "-h", "localhost", "-U", db_user, "-d", "ragstudio", "-t", "-A",
                 "-c", "SELECT api_key FROM t_ai_provider WHERE name='bailian'"],
                env={**os.environ, "PGPASSWORD": db_pass}, capture_output=True, text=True, timeout=15)
            api_key = out.stdout.strip()
        except Exception:
            pass
    if not api_key:
        sys.exit("❌ 未找到打分 LLM 的 API Key：请设置 GRADER_API_KEY，或保证本地可访问 DB 中的 bailian 供应商配置")
    return {"base_url": base_url, "model": model, "api_key": api_key}


def truncate(text, n=400):
    text = text.replace("\n", " ")
    return text if len(text) <= n else text[:n] + " …"


def main():
    ap = argparse.ArgumentParser(description="RAGStudio LLM 评测")
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:9090/api/ragstudio"))
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="password", default="admin")
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 条用例（0=全部）")
    ap.add_argument("--concurrent", type=int, default=1, help="并发对话数")
    ap.add_argument("--grade-concurrent", type=int, default=0,
                    help="打分阶段并发数（0=跟随 --concurrent）")
    ap.add_argument("--reuse", metavar="DIR", help="复用已有 SSE 日志目录，跳过对话阶段")
    ap.add_argument("--no-grade", action="store_true", help="只跑对话不打分")
    args = ap.parse_args()

    cases = parse_cases(CASES_FILE)
    if args.limit > 0:
        cases = cases[: args.limit]

    if args.reuse:
        out_dir = Path(args.reuse)
        log(f"▶ 复用日志目录: {out_dir}")
    else:
        out_dir = LOG_ROOT / f"llm-eval-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
        out_dir.mkdir(parents=True, exist_ok=True)
        log(f"▶ 登录 {args.base_url} ...")
        try:
            token = login(args.base_url, args.user, args.password)
        except Exception as e:
            sys.exit(f"❌ 登录失败: {e}")
        log(f"  ✓ 已获取 TOKEN\n▶ 开始对话测试（{len(cases)} 条）...")

        def run_one(case):
            return case, chat(args.base_url, token, case, out_dir)

        answers = {}
        if args.concurrent > 1:
            with ThreadPoolExecutor(max_workers=args.concurrent) as ex:
                for case, res in ex.map(run_one, cases):
                    answers[case["num"]] = res
        else:
            for i, case in enumerate(cases, 1):
                t0 = time.time()
                res = chat(args.base_url, token, case, out_dir)
                ans = res[0]
                answers[case["num"]] = res
                status = "✓" if ans else "✗ 无回答"
                log(f"  [{i:03d}/{len(cases)}] {case['label']:<14} {status}  {time.time()-t0:.1f}s")
        log(f"  ✓ 对话完成，日志: {out_dir}\n")

    all_results = []
    for case in cases:
        if args.reuse:
            ans, cites, err = load_saved(case, out_dir)
        else:
            ans, cites = answers[case["num"]]
        all_results.append({"case": case, "answer": ans, "cites": cites, "err": err if args.reuse else ""})

    if args.no_grade:
        log("（--no-grade，跳过打分阶段）")

    grader = load_grader_config()
    gradeWorkers = args.grade_concurrent if args.grade_concurrent > 0 else args.concurrent
    log(f"▶ LLM 打分（{grader['model']} @ {grader['base_url']}，并发 {gradeWorkers}）...")
    graded = {}
    if gradeWorkers > 1:
        with ThreadPoolExecutor(max_workers=gradeWorkers) as ex:
            futs = {ex.submit(grade_llm, grader, r["case"], r["answer"]): r for r in all_results}
            for fut in as_completed(futs):
                r = futs[fut]
                graded[r["case"]["num"]] = fut.result()
    else:
        for i, r in enumerate(all_results, 1):
            graded[r["case"]["num"]] = grade_llm(grader, r["case"], r["answer"])
            log(f"  [{i:03d}/{len(all_results)}] 已打分")
    log("")

    # ─── 报告 ───
    lines = []
    stats = {"1": 0, "0.5": 0, "0": 0}
    dept = {}
    for r in all_results:
        c = r["case"]
        score, reason = graded[c["num"]]
        stats[f"{score:g}"] = stats.get(f"{score:g}", 0) + 1
        d = c["label"].split("·")[0].strip()
        dept.setdefault(d, {"n": 0, "s": 0.0, "f": 0})
        dept[d]["n"] += 1
        dept[d]["s"] += score
        if score >= 1:
            dept[d]["f"] += 1
        icon = "✓" if score >= 1 else ("△" if score == 0.5 else "✗")
        lines.append(f"{c['num']}.")
        lines.append(f"参考资料：{c['ref']}")
        lines.append(f"用户问题：{c['q']}")
        lines.append(f"AI回答：{truncate(r['answer']) if r['answer'] else '（无回答）'}")
        lines.append(f"评分：{score:g}/1.0 [{icon}] 理由：{reason}")
        lines.append("")

    report = "\n".join(lines)
    print(report)

    n = len(all_results)
    full = stats.get("1", 0)
    half = stats.get("0.5", 0)
    zero = stats.get("0", 0)
    avg = sum(v[0] for v in graded.values()) / n if n else 0
    header = [
        "╔══════════════════════════════════════════════╗",
        "║            RAGStudio LLM 评测报告            ║",
        "╚══════════════════════════════════════════════╝",
        "",
        f"用例总数        : {n}",
        f"满分 1.0        : {full}  ({full * 100 // n}%)",
        f"部分 0.5        : {half}  ({half * 100 // n}%)",
        f"错误 0          : {zero}  ({zero * 100 // n}%)",
        f"平均分          : {avg:.3f}",
        "",
        "─── 分部门 ───",
        f"{'部门':<12}{'用例':>5}{'平均分':>9}{'满分率':>9}",
    ]
    for d in sorted(dept):
        s = dept[d]
        header.append(f"{d:<12}{s['n']:>5}{s['s'] / s['n']:>9.3f}{s['f'] * 100 // s['n']:>8}%")
    header.append("")
    header.append(f"SSE 日志目录: {out_dir}")

    print("\n".join(header))
    (out_dir / "report.md").write_text("\n".join(header + ["", *lines]), encoding="utf-8")
    log(f"\n报告已写入: {out_dir / 'report.md'}")


if __name__ == "__main__":
    main()
