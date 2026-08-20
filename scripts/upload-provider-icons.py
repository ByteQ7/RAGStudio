#!/usr/bin/env python3
"""RAGStudio AI 供应商图标上传工具

从 lobehub (github.com/lobehub/lobe-icons) 下载的彩色图标位于
resources/provider-icons/ 目录，本脚本负责将其幂等上传到 S3 (RustFS/MinIO)。

S3 配置从项目根目录 .env 读取（与后端 application.yaml 一致）：
  RUSTFS_URL / RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY

上传规则：
  - bucket 固定为 ragstudio（对应后端 RAGConstant.S3_BUCKET_NAME）
  - 前缀 provider-icons（对应 RAGConstant.S3_AI_PROVIDER_ICON_PREFIX）
  - 对象 key 与种子 SQL 的 icon_url（s3://ragstudio/provider-icons/<name>.svg）一一对应
  - 幂等：目标对象已存在则跳过；--force 强制覆盖

用法：
  ./scripts/upload-provider-icons.sh                # 幂等上传
  ./scripts/upload-provider-icons.sh --force        # 强制覆盖已存在图标
"""
import argparse
import os
import sys
from pathlib import Path

import boto3
from botocore.exceptions import ClientError

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / ".env"
ICON_DIR = ROOT / "resources" / "provider-icons"

S3_BUCKET_NAME = "ragstudio"
S3_AI_PROVIDER_ICON_PREFIX = "provider-icons"
CONTENT_TYPE_SVG = "image/svg+xml"


def load_env():
    """解析 .env（KEY=VALUE，支持 # 注释与引号），已存在的环境变量优先。"""
    if not ENV_FILE.exists():
        print(f"[错误] 未找到 {ENV_FILE}，请先执行 cp .env-example .env 并填入 RUSTFS_* 配置")
        sys.exit(1)
    env = {}
    for raw in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip("\"'")
        if key and value:
            env[key] = value
    return env


def main():
    parser = argparse.ArgumentParser(description="上传 AI 供应商图标到 S3")
    parser.add_argument("--force", action="store_true", help="强制覆盖已存在的图标")
    args = parser.parse_args()

    if not ICON_DIR.is_dir():
        print(f"[错误] 图标目录不存在: {ICON_DIR}")
        sys.exit(1)

    env = load_env()
    endpoint = os.environ.get("RUSTFS_URL") or env.get("RUSTFS_URL")
    access_key = os.environ.get("RUSTFS_ACCESS_KEY") or env.get("RUSTFS_ACCESS_KEY")
    secret_key = os.environ.get("RUSTFS_SECRET_KEY") or env.get("RUSTFS_SECRET_KEY")
    if not endpoint or not access_key or not secret_key:
        print("[错误] .env 缺少 RUSTFS_URL / RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY 配置")
        sys.exit(1)

    print(f"S3 endpoint: {endpoint}")
    print(f"bucket: {S3_BUCKET_NAME}, prefix: {S3_AI_PROVIDER_ICON_PREFIX}")

    s3 = boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        region_name="us-east-1",
        use_ssl=endpoint.startswith("https://"),
        config=boto3.session.Config(signature_version="s3v4", s3={"addressing_style": "path"}),
    )

    try:
        s3.head_bucket(Bucket=S3_BUCKET_NAME)
    except ClientError:
        print(f"[初始化] bucket '{S3_BUCKET_NAME}' 不存在，创建中...")
        try:
            s3.create_bucket(Bucket=S3_BUCKET_NAME)
            print(f"[初始化] 已创建 bucket '{S3_BUCKET_NAME}'")
        except ClientError as e:
            print(f"[错误] 创建 bucket 失败: {e}")
            sys.exit(1)

    icons = sorted(ICON_DIR.glob("*.svg"))
    if not icons:
        print("[错误] 图标目录中没有 .svg 文件")
        sys.exit(1)

    uploaded = skipped = failed = 0
    for icon in icons:
        key = f"{S3_AI_PROVIDER_ICON_PREFIX}/{icon.name}"
        exists = False
        if not args.force:
            try:
                s3.head_object(Bucket=S3_BUCKET_NAME, Key=key)
                exists = True
            except ClientError as e:
                if e.response.get("ResponseMetadata", {}).get("HTTPStatusCode") != 404:
                    print(f"[错误] 检查 {key} 失败: {e}")
                    failed += 1
                    continue
        if exists:
            print(f"[跳过] {key} 已存在")
            skipped += 1
            continue
        try:
            s3.upload_file(
                str(icon),
                S3_BUCKET_NAME,
                key,
                ExtraArgs={"ContentType": CONTENT_TYPE_SVG},
            )
            print(f"[上传] {key}")
            uploaded += 1
        except ClientError as e:
            print(f"[错误] 上传 {key} 失败: {e}")
            failed += 1

    print(f"\n完成: 上传 {uploaded} / 跳过 {skipped} / 失败 {failed}")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()