#!/bin/sh
# 系统信息查询脚本（兼容 Alpine Linux）
echo "主机名: $(hostname 2>/dev/null || cat /etc/hostname)"
echo "操作系统: $(uname -a)"
echo "CPU 核心数: $(nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo)"
echo "内存:"
free -h 2>/dev/null || cat /proc/meminfo | grep -E '^(MemTotal|MemFree|MemAvailable)' | awk -F: '{print $1": "$2}'
echo "磁盘:"
df -h / | tail -1
