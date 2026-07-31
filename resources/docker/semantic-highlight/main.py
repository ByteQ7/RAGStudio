"""
语义高亮 / 重排序微服务
========================================
基于 zilliz/semantic-highlight-bilingual-v1 (0.6B)，
提供句子级语义高亮和 chunk 级重排序能力。

支持 FP16 / INT8 量化，INT8 模式下内存占用约 1-1.5GB。

环境变量:
    QUANTIZE=fp16|int8      量化模式 (默认: fp16 GPU / fp32 CPU)
    CUDA_VISIBLE_DEVICES=   GPU 选择
    HIGHLIGHT_BATCH_SIZE    单个 chunk 内句子批量大小 (默认: 2，CPU+INT8 下实测最优)
    HIGHLIGHT_WORKERS       chunk 级并发线程数 (默认: min(8, cpu核心数)，1 表示串行)
    HIGHLIGHT_TORCH_THREADS 每个并发任务的 torch intra-op 线程数
                            (默认: cpu核心数 // HIGHLIGHT_WORKERS，避免多线程超订)

启动:
    # GPU FP16 (默认)
    uvicorn main:app --host 0.0.0.0 --port 8001

    # GPU INT8 (最省显存)
    QUANTIZE=int8 uvicorn main:app --host 0.0.0.0 --port 8001

    # CPU (不需要 GPU)
    uvicorn main:app --host 0.0.0.0 --port 8001

Docker:
    docker build -t highlight-service .
    docker run -d --gpus all -p 8001:8001 -e QUANTIZE=int8 highlight-service:latest
"""

import logging
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from transformers import AutoModel

# ── 日志 ──────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("highlight")

# ── 可调参数（环境变量）──────────────────────────────
# CPU + INT8 下实测：batch_size=2 明显优于 16/32（qint8 内核在小 batch 下更快）
HIGHLIGHT_BATCH_SIZE = int(os.environ.get("HIGHLIGHT_BATCH_SIZE", "2"))
# chunk 级并发：多个 chunk 的模型前向在独立线程并行（torch 计算会释放 GIL）
_default_workers = max(1, min(8, os.cpu_count() or 4))
HIGHLIGHT_WORKERS = int(os.environ.get("HIGHLIGHT_WORKERS", str(_default_workers)))

# 并发时的 torch intra-op 线程数：默认均分到各 worker，避免线程超订；显式设置了
# OMP_NUM_THREADS 时尊重外部配置。仅在并行模式（workers>1）生效。
if HIGHLIGHT_WORKERS > 1 and not os.environ.get("OMP_NUM_THREADS"):
    _per_worker = max(1, (os.cpu_count() or 4) // HIGHLIGHT_WORKERS)
    torch.set_num_threads(int(os.environ.get("HIGHLIGHT_TORCH_THREADS", str(_per_worker))))

# ── FastAPI ───────────────────────────────────────────
app = FastAPI(
    title="Semantic Highlight Service",
    version="1.1.0",
    description="基于 zilliz/semantic-highlight-bilingual-v1 的语义高亮与重排序服务",
)

# ── 全局模型实例（启动时加载）────────────────────────
_model: Optional[AutoModel] = None
_device: str = "cpu"
_quantize_mode: str = "fp16"  # fp16 / int8
_executor: Optional[ThreadPoolExecutor] = None


def _get_executor() -> Optional[ThreadPoolExecutor]:
    """惰性创建 chunk 级并发线程池（避免启动时无谓创建）"""
    global _executor
    if HIGHLIGHT_WORKERS <= 1:
        return None
    if _executor is None:
        _executor = ThreadPoolExecutor(max_workers=HIGHLIGHT_WORKERS)
    return _executor


def load_model() -> None:
    """启动时加载模型，支持 INT8 量化"""
    global _model, _device, _quantize_mode

    model_name = "zilliz/semantic-highlight-bilingual-v1"
    _quantize_mode = os.environ.get("QUANTIZE", "fp16").lower()

    if torch.cuda.is_available() and _quantize_mode != "cpu":
        _device = "cuda"
        logger.info("检测到 CUDA，模型加载到 GPU")
    else:
        _device = "cpu"
        logger.info("未检测到 CUDA 或 QUANTIZE=cpu，模型加载到 CPU")

    logger.info(
        "正在加载模型 %s (0.6B)，量化模式=%s ...",
        model_name, _quantize_mode,
    )
    t0 = time.time()

    if _quantize_mode == "int8":
        if _device == "cuda":
            try:
                import bitsandbytes  # noqa: F401
                _model = AutoModel.from_pretrained(
                    model_name,
                    trust_remote_code=True,
                    load_in_8bit=True,
                    device_map="auto",
                    local_files_only=True,
                )
                _model.eval()
                logger.info("INT8 量化 (bitsandbytes) 加载完成")
            except ImportError:
                logger.warning("bitsandbytes 未安装，回退到 FP16")
                _model = AutoModel.from_pretrained(
                    model_name,
                    trust_remote_code=True,
                    torch_dtype=torch.float16,
                    local_files_only=True,
                ).to(_device)
                _model.eval()
        else:
            _model = AutoModel.from_pretrained(
                model_name,
                trust_remote_code=True,
                torch_dtype=torch.float32,
                local_files_only=True,
            )
            _model.eval()
            _model = torch.quantization.quantize_dynamic(
                _model,
                {torch.nn.Linear},
                dtype=torch.qint8,
            )
            logger.info("INT8 动态量化 (torch.quantization) 加载完成")
    else:
        dtype = torch.float16 if _device == "cuda" else torch.float32
        _model = AutoModel.from_pretrained(
            model_name,
            trust_remote_code=True,
            torch_dtype=dtype,
            local_files_only=True,
        ).to(_device)
        _model.eval()

    elapsed = time.time() - t0
    logger.info(
        "模型加载完成，耗时 %.2fs，设备=%s，量化=%s，batch_size=%s，workers=%s",
        elapsed, _device, _quantize_mode, HIGHLIGHT_BATCH_SIZE, HIGHLIGHT_WORKERS,
    )


@app.on_event("startup")
def startup() -> None:
    load_model()


# ── 请求/响应模型 ────────────────────────────────────


class ChunkItem(BaseModel):
    id: str = Field(..., description="Chunk 唯一标识")
    text: str = Field(..., description="Chunk 文本内容")


class HighlightRequest(BaseModel):
    question: str = Field(..., description="用户问题 / 查询文本")
    chunks: list[ChunkItem] = Field(
        ..., description="待高亮的 Chunk 列表", max_length=50
    )
    threshold: float = Field(
        0.5, description="相关性阈值 (0-1)，高于此值的句子被高亮", ge=0.0, le=1.0
    )


class ChunkHighlightResult(BaseModel):
    chunk_id: str
    sentences: list[str] = Field(..., description="所有句子")
    scores: list[float] = Field(..., description="每个句子的相关性分数")
    highlighted_indices: list[int] = Field(
        ..., description="被高亮的句子索引（score >= threshold）"
    )
    highlighted_sentences: list[str] = Field(
        ..., description="被高亮的句子原文"
    )


class HighlightResponse(BaseModel):
    results: list[ChunkHighlightResult]


class RerankRequest(BaseModel):
    question: str = Field(..., description="用户问题")
    chunks: list[ChunkItem] = Field(
        ..., description="待排序的 Chunk 列表", max_length=100
    )


class RerankItem(BaseModel):
    id: str
    score: float = Field(..., description="相关性分数 (0-1)")
    index: int = Field(..., description="原始顺序索引")


class RerankResponse(BaseModel):
    results: list[RerankItem]


# ── 自定义分句器 ─────────────────────────────────────


def _custom_splitter(text: str) -> list[str]:
    """自定义分句器，同时支持中文和英文"""
    if not text:
        return []
    parts = re.split(r"([。！？!?.\n])", text)
    result: list[str] = []
    for i in range(0, len(parts), 2):
        if i + 1 < len(parts):
            ending = parts[i + 1]
            if ending == "\n":
                sentence = parts[i].strip()
            else:
                sentence = parts[i].strip() + ending
            if sentence:
                result.append(sentence)
        else:
            if parts[i].strip():
                result.append(parts[i].strip())
    return result if result else ([text] if text else [])


def _merge_sentences(text: str, kept: list[str], removed: list[str]) -> list[str]:
    """将 kept/removed 句子按原文位置交错合并"""
    kq = list(kept)
    rq = list(removed)
    result: list[str] = []
    search_pos = 0
    while kq or rq:
        kp = text.find(kq[0], search_pos) if kq else -1
        rp = text.find(rq[0], search_pos) if rq else -1
        if kp >= 0 and (rp < 0 or kp <= rp):
            result.append(kq.pop(0))
            search_pos = kp + len(result[-1])
        elif rp >= 0:
            result.append(rq.pop(0))
            search_pos = rp + len(result[-1])
        else:
            break
    return result


# ── 核心处理函数 ─────────────────────────────────────


def _process_chunk(question: str, text: str, threshold: float) -> dict:
    """对单个 chunk 执行语义高亮"""
    if _model is None:
        raise RuntimeError("模型尚未加载")

    try:
        result = _model.process(
            question=question,
            context=text,
            threshold=threshold,
            return_sentence_metrics=True,
            return_sentence_texts=True,
            batch_size=HIGHLIGHT_BATCH_SIZE,
        )
    except Exception:
        result = _model.process(
            question=question,
            context=text,
            threshold=threshold,
            return_sentence_metrics=True,
            return_sentence_texts=True,
            batch_size=HIGHLIGHT_BATCH_SIZE,
            sentence_splitter=_custom_splitter,
        )

    probs: list[float] = result.get("sentence_probabilities", [])
    kept: list[str] = result.get("kept_sentences", [])
    removed: list[str] = result.get("removed_sentences", [])

    all_sentences = _merge_sentences(text, kept, removed)

    if len(all_sentences) != len(probs) or not all_sentences:
        parts = re.split(r"(?<=[。！？!?\n])", text)
        all_sentences = [p.strip() for p in parts if p.strip()]

    highlighted_indices = [i for i, p in enumerate(probs) if p >= threshold]
    highlighted_sentences = [
        all_sentences[i] for i in highlighted_indices
        if i < len(all_sentences)
    ]

    return {
        "sentences": all_sentences,
        "scores": probs,
        "highlighted_indices": highlighted_indices,
        "highlighted_sentences": highlighted_sentences,
    }


def _process_chunk_safe(question: str, text: str, threshold: float) -> dict:
    """带异常兜底的 chunk 处理（并发时调用）"""
    try:
        return _process_chunk(question, text, threshold)
    except Exception as e:
        logger.error("Chunk 处理失败: %s", e)
        return {"sentences": [], "scores": [],
                "highlighted_indices": [], "highlighted_sentences": []}


def _run_highlight_parallel(question: str, pending: list[tuple[str, str]],
                            threshold: float) -> dict[str, dict]:
    """并发处理多个 chunk，返回 chunk_id -> 处理结果 的映射"""
    executor = _get_executor()
    if executor is None or len(pending) <= 1:
        return {cid: _process_chunk_safe(question, text, threshold)
                for cid, text in pending}
    future_map = {
        executor.submit(_process_chunk_safe, question, text, threshold): cid
        for cid, text in pending
    }
    result: dict[str, dict] = {}
    for future, cid in future_map.items():
        result[cid] = future.result()
    return result


# ── 端点 ─────────────────────────────────────────────


@app.get("/health")
def health():
    return {
        "status": "ok",
        "model_loaded": _model is not None,
        "device": _device,
        "batch_size": HIGHLIGHT_BATCH_SIZE,
        "workers": HIGHLIGHT_WORKERS,
    }


@app.post("/highlight", response_model=HighlightResponse)
def highlight(req: HighlightRequest):
    if _model is None:
        raise HTTPException(status_code=503, detail="模型尚未加载")

    t0 = time.time()
    results: list[ChunkHighlightResult] = []
    question = req.question[:3000] if req.question else ""

    # 过滤空文本 chunk（空 chunk 直接返回空结果，保持与输入一一对应）
    pending = [(chunk.id, chunk.text[:5000]) for chunk in req.chunks]
    pending = [(cid, text) for cid, text in pending if text.strip()]

    proc_by_id = _run_highlight_parallel(question, pending, req.threshold)

    for chunk in req.chunks:
        if not chunk.text.strip():
            results.append(ChunkHighlightResult(
                chunk_id=chunk.id, sentences=[], scores=[],
                highlighted_indices=[], highlighted_sentences=[],
            ))
            continue
        proc = proc_by_id.get(chunk.id) or {}
        results.append(ChunkHighlightResult(
            chunk_id=chunk.id,
            sentences=proc.get("sentences", []),
            scores=proc.get("scores", []),
            highlighted_indices=proc.get("highlighted_indices", []),
            highlighted_sentences=proc.get("highlighted_sentences", []),
        ))

    elapsed = time.time() - t0
    logger.info("/highlight: question=%s, chunks=%d, 耗时=%.0fms",
                req.question[:50], len(req.chunks), elapsed * 1000)
    return HighlightResponse(results=results)


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    if _model is None:
        raise HTTPException(status_code=503, detail="模型尚未加载")

    t0 = time.time()
    scored: list[RerankItem] = []

    pending = [(chunk.id, chunk.text) for chunk in req.chunks if chunk.text.strip()]
    proc_by_id = _run_highlight_parallel(req.question, pending, 0.0)

    for idx, chunk in enumerate(req.chunks):
        if not chunk.text.strip():
            scored.append(RerankItem(id=chunk.id, score=0.0, index=idx))
            continue
        proc = proc_by_id.get(chunk.id) or {}
        scores = proc.get("scores") or []
        max_score = max(scores) if scores else 0.0
        scored.append(RerankItem(id=chunk.id, score=round(max_score, 6), index=idx))

    scored.sort(key=lambda x: x.score, reverse=True)
    elapsed = time.time() - t0
    logger.info("/rerank: chunks=%d, 耗时=%.0fms", len(req.chunks), elapsed * 1000)
    return RerankResponse(results=scored)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001)
