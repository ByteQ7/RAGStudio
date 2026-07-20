FROM python:3.10-slim

WORKDIR /app

# 安装 PyTorch CPU-only 版本
RUN pip install --no-cache-dir torch>=2.2.0 --index-url https://download.pytorch.org/whl/cpu

# Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 下载 NLTK 分句数据（模型内部依赖）
RUN python -c "import nltk; nltk.download('punkt', quiet=True); nltk.download('punkt_tab', quiet=True)"

# 应用代码
COPY main.py .

# 构建时下载模型（避免运行时首次下载慢）
RUN python -c "from transformers import AutoModel; AutoModel.from_pretrained('zilliz/semantic-highlight-bilingual-v1', trust_remote_code=True)"

EXPOSE 8001

# CPU + INT8 量化
ENV QUANTIZE=int8

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8001/health')" || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
