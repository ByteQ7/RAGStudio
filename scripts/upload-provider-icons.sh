#!/bin/bash
# ============================================
# 上传 AI 供应商图标到 S3
# 使用方式: ./scripts/upload-provider-icons.sh
# 前提: 已配置 aws-cli 或 mc (MinIO Client)
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ICON_DIR="$SCRIPT_DIR/provider-icons"
BUCKET="ragstudio"
PREFIX="provider-icons"
ENDPOINT="${RUSTFS_URL:-http://localhost:9000}"
ACCESS_KEY="${RUSTFS_ACCESS_KEY:-}"
SECRET_KEY="${RUSTFS_SECRET_KEY:-}"

echo "🔧 上传 AI 供应商图标到 S3..."
echo "   Endpoint: $ENDPOINT"
echo "   Bucket:   $BUCKET"
echo "   Prefix:   $PREFIX"
echo ""

# 检查 mc 命令
if command -v mc &> /dev/null; then
  echo "使用 MinIO Client (mc)..."
  
  # 配置别名
  mc alias set ragstore "$ENDPOINT" "$ACCESS_KEY" "$SECRET_KEY" 2>/dev/null || true
  
  for svg in "$ICON_DIR"/*.svg; do
    filename=$(basename "$svg")
    echo "  📤 上传 $filename..."
    mc cp "$svg" "ragstore/$BUCKET/$PREFIX/$filename"
  done
  
  echo ""
  echo "✅ 上传完成！所有图标已上传到 s3://$BUCKET/$PREFIX/"
  
elif command -v aws &> /dev/null; then
  echo "使用 AWS CLI..."
  
  for svg in "$ICON_DIR"/*.svg; do
    filename=$(basename "$svg")
    echo "  📤 上传 $filename..."
    aws s3 cp "$svg" "s3://$BUCKET/$PREFIX/$filename" \
      --endpoint-url "$ENDPOINT" \
      --acl public-read 2>/dev/null || \
    aws s3 cp "$svg" "s3://$BUCKET/$PREFIX/$filename" \
      --endpoint-url "$ENDPOINT"
  done
  
  echo ""
  echo "✅ 上传完成！"
  
else
  echo "❌ 未找到 mc 或 aws 命令。请先安装:"
  echo "  brew install minio/stable/mc"
  echo "  或: pip install awscli"
  echo ""
  echo "或者手动上传以下文件到 S3 $BUCKET/$PREFIX/:"
  ls -1 "$ICON_DIR"/*.svg
  exit 1
fi
