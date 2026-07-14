#!/bin/bash
# RAGStudio 对话测试套件 - 100 用例
# 用法: ./scripts/test-chat.sh [BASE_URL]
# 默认 BASE_URL=http://localhost:9090/api/ragstudio

set -euo pipefail

# ===== 配置 =====
BASE_URL="${1:-http://localhost:9090/api/ragstudio}"
USERNAME="admin"
PASSWORD="admin"
TIMEOUT_SEC=30
CURL_OPTS="-s --max-time $TIMEOUT_SEC"

# KB IDs（从数据库查询得到）
KB_HR="2077043298647863296"
KB_IT="2077044595442774016"
KB_INVOICE="2077051696852430848"
KB_FINANCE="2077051966990774272"
KB_OA="2077052306691649536"

# 统计
TOTAL=0
PASSED=0
FAILED=0
TIMEOUTS=0
declare -a RESULTS

# ===== 颜色 =====
GRN='\033[0;32m'
RED='\033[0;31m'
YLW='\033[1;33m'
BLU='\033[0;34m'
CYN='\033[0;36m'
NC='\033[0m'

# ===== 工具函数 =====

# 登录获取 TOKEN
do_login() {
  echo -e "${CYN}▶ 登录: $USERNAME${NC}" >&2
  local resp
  resp=$(curl $CURL_OPTS -X POST "$BASE_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
  TOKEN=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null || true)
  if [ -z "$TOKEN" ]; then
    echo -e "${RED}✗ 登录失败: $resp${NC}" >&2
    exit 1
  fi
  echo -e "${GRN}✓ TOKEN 获取成功${NC}" >&2
}

# 发送单个对话（流式 SSE），返回最终回答文本
# 参数: question [knowledgeBaseIds(逗号分隔)] [deepThinkingLevel]
do_chat() {
  local question="$1"
  local kb_ids="${2:-}"
  local thinking="${3:-0}"
  local data

  if [ -n "$kb_ids" ]; then
    IFS=',' read -ra ids <<< "$kb_ids"
    local kb_json="["
    local first=true
    for id in "${ids[@]}"; do
      $first && kb_json+="\"$id\"" || kb_json+=",\"$id\""
      first=false
    done
    kb_json+="]"
    data=$(python3 -c "
import json
d = {'question': '$question', 'deepThinkingLevel': $thinking, 'knowledgeBaseIds': $kb_json}
print(json.dumps(d, ensure_ascii=False))
")
  else
    data=$(python3 -c "
import json
d = {'question': '$question', 'deepThinkingLevel': $thinking}
print(json.dumps(d, ensure_ascii=False))
")
  fi

  local resp
  resp=$(curl $CURL_OPTS -X POST "$BASE_URL/rag/v3/chat" \
    -H "Content-Type: application/json" \
    -H "Authorization: $TOKEN" \
    -d "$data" 2>&1) || {
    echo "__TIMEOUT__"
    return
  }

  # 从 SSE 响应中提取 finish 事件的 data 字段
  local answer
  answer=$(echo "$resp" | python3 -c "
import sys
lines = sys.stdin.read()
result = ''
in_finish = False
for line in lines.split('\n'):
    if line.startswith('event: finish'):
        in_finish = True
    elif line.startswith('event: ') and not line.startswith('event: finish'):
        in_finish = False
    elif line.startswith('data: ') and in_finish:
        try:
            import json
            payload = json.loads(line[6:])
            if isinstance(payload, dict):
                result = payload.get('answer', payload.get('delta', ''))
            elif isinstance(payload, str):
                result = payload
        except:
            result = line[6:]
    elif line.startswith('event: error'):
        in_finish = False
if not result:
    # 回退：提取所有 message 事件的 delta
    parts = []
    for line in lines.split('\n'):
        if line.startswith('event: message'):
            in_finish = True
        elif line.startswith('event: done'):
            break
        elif line.startswith('data: ') and in_finish:
            try:
                import json
                payload = json.loads(line[6:])
                if isinstance(payload, dict) and payload.get('type') == 'response':
                    parts.append(payload.get('delta', ''))
            except:
                pass
    result = ''.join(parts)
print(result[:500] if result else '(empty)')
" 2>/dev/null) || echo "(parse error)"

  echo "$answer"
}

# 记录结果
record() {
  local num="$1" label="$2" result="$3" duration="$4"
  local status_text
  if [ "$result" = "__TIMEOUT__" ]; then
    status_text="${RED}⏱ 超时${NC}"
    TIMEOUTS=$((TIMEOUTS+1))
    FAILED=$((FAILED+1))
  elif [ "$result" = "__FAIL__" ]; then
    status_text="${RED}✗ 失败${NC}"
    FAILED=$((FAILED+1))
  elif [ -z "$result" ] || [ "$result" = "(empty)" ] || [ "$result" = "(parse error)" ]; then
    status_text="${RED}✗ 空回答${NC}"
    FAILED=$((FAILED+1))
  else
    status_text="${GRN}✓ 成功${NC}"
    PASSED=$((PASSED+1))
  fi
  RESULTS+=("$num|$label|$status_text|${duration}s")
  echo -e "  $status_text (${duration}s)"
}

# 显示分隔线
separator() {
  echo -e "${BLU}──────────────────────────────────────────────${NC}"
}

# ===== 主流程 =====

echo -e "${CYN}════════════════════════════════════════════════${NC}"
echo -e "${CYN}  RAGStudio 对话测试套件 - 100 用例${NC}"
echo -e "${CYN}  API: $BASE_URL${NC}"
echo -e "${CYN}  开始时间: $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo -e "${CYN}════════════════════════════════════════════════${NC}"
echo ""

do_login
echo ""

# ===========================================================================
# A. 问候与自我介绍（6 个）
# ===========================================================================
echo -e "\n${CYN}━━━ A. 问候与自我介绍 ━━━${NC}"

LABEL="A1. 你好（问候）"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "你好"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="A2. 你是谁（自我介绍）"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "你是谁？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="A3. 你能做什么"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "你能做什么？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="A4. 你是不是ChatGPT"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "你是ChatGPT吗？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="A5. 谁开发了你"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "谁开发了你？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="A6. 你们用的什么底层模型"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "你们用的什么底层模型？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# B. HR 知识库（10 个）
# ===========================================================================
echo -e "\n${CYN}━━━ B. HR 知识库 ━━━${NC}"

LABEL="B1. 年假计算规则"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "年假怎么计算？入职多久可以休年假？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="B2. 病假政策"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "病假怎么请？需要什么材料？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="B3. 考勤制度"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司的考勤制度是怎么样的？上下班时间？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="B4. 绩效考核"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "绩效考核周期是多久？怎么评定的？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="B5. 招聘信息"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司最近有什么招聘岗位？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="B6. 薪资福利"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司的薪资福利政策包括哪些？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="B7. 员工培训"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司有哪些培训项目？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="B8. 劳动合同"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "劳动合同的签订和续签流程是什么？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="B9. 加班制度"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "加班怎么计算加班费？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="B10. 转正流程"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "试用期多久？转正流程是什么？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# C. IT 知识库（8 个）
# ===========================================================================
echo -e "\n${CYN}━━━ C. IT 知识库 ━━━${NC}"

LABEL="C1. VPN连接"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "VPN怎么连接？" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="C2. 企业邮箱"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "企业邮箱怎么配置？" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="C3. 打印机连接"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "打印机怎么连接？" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="C4. 网络安全制度"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat("公司的网络安全管理制度有哪些要求？" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="C5. 安全事件响应"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "发生安全事件应该怎么处理？" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="C6. 账号问题"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "账号密码忘记了怎么办？" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="C7. IT支持范围"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "IT支持的范围包括哪些？" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="C8. 部署规范"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "部署规范有哪些要求？" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# D. 财务知识库（6 个）
# ===========================================================================
echo -e "\n${CYN}━━━ D. 财务知识库 ━━━${NC}"

LABEL="D1. 报销流程"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "报销流程是什么？" "$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="D2. 印章管理"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "印章管理制度是什么？" "$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="D3. 合同管理"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "合同管理的流程是什么？" "$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="D4. 预算管理"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "预算怎么申请？审批流程是什么？" "$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="D5. 报销标准"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "差旅报销标准是多少？" "$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="D6. 税务政策"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司适用的税务政策有哪些？" "$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# E. OA 知识库（6 个）
# ===========================================================================
echo -e "\n${CYN}━━━ E. OA 知识库 ━━━${NC}"

LABEL="E1. OA系统使用"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "OA系统怎么登录和使用？" "$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="E2. 办公用品申领"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "办公用品怎么申领？" "$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="E3. OA数据安全"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "OA系统的数据安全规范是什么？" "$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="E4. 客户服务"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "客户服务流程是什么？" "$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="E5. 商务接待"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "商务接待的流程和标准是什么？" "$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="E6. 销售管理"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "销售管理系统的功能有哪些？" "$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# F. 开票信息库（4 个）
# ===========================================================================
echo -e "\n${CYN}━━━ F. 开票信息 ━━━${NC}"

LABEL="F1. 开票信息查询"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司的开票信息是什么？" "$KB_INVOICE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="F2. 税号查询"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司的税号是多少？" "$KB_INVOICE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="F3. 银行账户"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司对公银行账户信息是什么？" "$KB_INVOICE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="F4. 发票抬头"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "发票抬头怎么写？" "$KB_INVOICE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# G. 跨 KB 综合（6 个）
# ===========================================================================
echo -e "\n${CYN}━━━ G. 跨知识库综合 ━━━${NC}"

LABEL="G1. HR+财务"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "年假期间的工资怎么算？" "$KB_HR,$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="G2. IT+OA"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "IT部门怎么在OA系统提交工单？" "$KB_IT,$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="G3. 财务+OA"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "OA系统的报销审批和财务复核流程是什么？" "$KB_FINANCE,$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="G4. 三个库"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "新员工入职的IT设备申请、办公用品领用和合同签订流程是什么？" "$KB_HR,$KB_IT,$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="G5. HR+OA"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "请假在OA系统上怎么操作？" "$KB_HR,$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="G6. IT+财务"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "IT设备采购的预算审批流程是什么？" "$KB_IT,$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# H. MCP 工具（8 个）
# ===========================================================================
echo -e "\n${CYN}━━━ H. MCP 工具 ━━━${NC}"

LABEL="H1. 天气-深圳"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "今天深圳的天气怎么样？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="H2. 天气-北京"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "北京明天天气如何？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="H3. 天气-上海"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "上海这周末天气怎么样？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="H4. 天气-广州"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "广州今天多少度？会不会下雨？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="H5. 图像-海滩"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "帮我生成一张夕阳海滩的图片"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="H6. 图像-城市"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "生成一张未来城市夜景的图片"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="H7. 图像-山水"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "画一幅水墨山水画"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="H8. 天气-非法参数"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "天气怎么样？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# I. SKILL 工具（6 个）
# ===========================================================================
echo -e "\n${CYN}━━━ I. SKILL 工具 ━━━${NC}"

LABEL="I1. 搜索-AI新闻"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "帮我搜索一下最近AI相关的新闻"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="I2. 搜索-技术"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "搜索一下Rust语言的最新发展"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="I3. 搜索-行业"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "帮我查一下最近云计算行业动态"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="I4. 系统信息"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "查询一下服务器的系统信息"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="I5. 搜索+知识库"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "搜索一下最新的员工培训方法，结合公司的培训制度" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="I6. 系统信息+"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "服务器的磁盘空间和内存使用情况怎么样？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# J. 多步推理（14 个）
# ===========================================================================
echo -e "\n${CYN}━━━ J. 多步推理 ━━━${NC}"

LABEL="J1. 今天+天气+建议"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "今天是什么日子？深圳天气怎么样？适合做什么？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J2. 节日+天气+通知"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "查一下最近有什么节日，再看看北京的天气，然后写个活动通知"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J3. 年假+天气+建议"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "我的年假有5天，想去三亚旅游，帮我查一下三亚最近天气怎么样，适合去吗？" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J4. 报销+出差+天气"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "我要去北京出差，先查一下北京的天气，再看看公司的差旅报销标准是什么？" "$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J5. 日期计算"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "今天是几号？这个月还有几天结束？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J6. 搜索+分析"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "搜索一下最近大模型行业的融资新闻，然后总结一下趋势"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J7. 三城市天气对比"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "北京、上海、广州今天天气怎么样？帮我对比一下"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J8. 年假+天气+请假"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "我想下周请年假去旅游，查一下我有哪些年假天数，再看看目的地的天气"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J9. 多步搜索"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "先搜索一下什么是Rust语言，再搜索一下Rust和Go的对比，最后总结一下"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J10. 制度+搜索+建议"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司有哪些培训课程？再搜索一下最新的在线学习平台，给我推荐几个"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J11. 跨知识库对比"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "对比一下公司的报销政策和预算管理制度" "$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J12. 入职流程全链"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "新员工入职需要办理哪些手续？包括IT账号、办公用品、合同签订" "$KB_HR,$KB_IT,$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J13. 安全+应急"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "如果发现公司网络被入侵了，应该按照什么流程处理？需要通知哪些部门？" "$KB_IT,$KB_OA"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="J14. 全链路"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "今天星期几？北京天气怎么样？公司有什么IT支持可以帮我？然后生成一张图片表达我的心情" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# K. 深度思考（8 个）
# ===========================================================================
echo -e "\n${CYN}━━━ K. 深度思考 ━━━${NC}"

LABEL="K1. 深度0-简单"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "年假有几天？" "$KB_HR" 0); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="K2. 深度30-中等"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "年假、病假、事假的区别和计算方式是什么？" "$KB_HR" 30); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="K3. 深度70-复杂"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "如果我请一周年假去日本旅游，需要提前多久申请？审批流程是什么？回来后需要做什么？" "$KB_HR" 70); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="K4. 深度100-分析"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "对比分析公司的网络安全管理制度和信息安全应急管理预案，找出其中的关联和互补之处" "$KB_IT" 100); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="K5. 深度0-天气"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "今天北京天气怎么样？" "" 0); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="K6. 深度50-混合"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "北京上海广州今天天气对比" "" 50); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="K7. 深度100-推理"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "如果公司所有部门都实行远程办公，对IT支持、网络安全、考勤制度、报销流程分别会产生什么影响？" "$KB_HR,$KB_IT,$KB_FINANCE" 100); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="K8. 深度30-搜索"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "帮我搜索一下2024年诺贝尔奖得主，然后总结他们的主要贡献" "" 30); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# L. 边界情况（8 个）
# ===========================================================================
echo -e "\n${CYN}━━━ L. 边界情况 ━━━${NC}"

LABEL="L1. 空问题"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat ""); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="L2. 超长问题"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); long_q=$(python3 -c "print('请详细说明' + '公司的'*50 + '规章制度'*50)"); r=$(do_chat "$long_q"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="L3. 特殊字符"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "@#$%^&*() ！@#￥%……&*（）"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="L4. 无知识库"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "1+1等于几？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="L5. 不相关问题"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "今天的股票行情怎么样？"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="L6. 纯数字"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "42"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="L7. 情绪表达"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "气死我了！！！！！为什么我的报销还没批下来？？？？！！"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="L8. 单字问题"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "好"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# M. 多轮对话（6 个）
# ===========================================================================
echo -e "\n${CYN}━━━ M. 多轮对话 ━━━${NC}"

LABEL="M1. 追问年假"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s)
# 第一轮
conv_id=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
r1=$(do_chat "年假怎么计算？" "$KB_HR")
# 第二轮：补充上下文追问
data=$(python3 -c "
import json
d = {'question': '那如果我入职2年，能休几天？', 'knowledgeBaseIds': ['$KB_HR'], 'conversationId': '$conv_id'}
print(json.dumps(d, ensure_ascii=False))
")
r2=$(curl $CURL_OPTS -X POST "$BASE_URL/rag/v3/chat" -H "Content-Type: application/json" -H "Authorization: $TOKEN" -d "$data" | python3 -c "
import sys
lines = sys.stdin.read()
for line in lines.split('\n'):
    if line.startswith('data:'):
        try:
            import json
            p = json.loads(line[5:])
            if isinstance(p, dict) and p.get('answer'):
                print(p['answer'][:200])
                break
        except:
            pass
" 2>/dev/null) || echo "(empty)"
echo "  Round1: ${r1:0:50}..."
echo "  Round2: ${r2:0:50}..."
record $((++TOTAL)) "$LABEL" "${r1}${r2}" $(( $(date +%s) - start ))

LABEL="M2. 追问天气"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s)
conv_id=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
r1=$(do_chat "今天天气怎么样？")
r2=$(curl $CURL_OPTS -X POST "$BASE_URL/rag/v3/chat" -H "Content-Type: application/json" -H "Authorization: $TOKEN" \
  -d "$(python3 -c "import json; print(json.dumps({'question': '那明天呢？', 'conversationId': '$conv_id'}))")" \
  | python3 -c "
import sys
for line in sys.stdin.read().split('\n'):
    if line.startswith('event: finish'):
        for l2 in sys.stdin.read().split('\n'):
            if l2.startswith('data:'):
                print(l2[5:][:200]); break
        break
" 2>/dev/null) || echo "(empty)"
echo "  Round1: ${r1:0:50}..."
echo "  Round2: ${r2:0:50}..."
record $((++TOTAL)) "$LABEL" "${r1}${r2}" $(( $(date +%s) - start ))

LABEL="M3. 修正需求"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s)
conv_id=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
r1=$(do_chat "北京天气怎么样？")
r2=$(curl $CURL_OPTS -X POST "$BASE_URL/rag/v3/chat" -H "Content-Type: application/json" -H "Authorization: $TOKEN" \
  -d "$(python3 -c "import json; print(json.dumps({'question': '说错了，我是想问上海的', 'conversationId': '$conv_id'}))")" \
  | python3 -c "
import sys
lines = sys.stdin.read().split('\n')
for line in lines:
    if line.startswith('event: finish'):
        for l2 in lines:
            if l2.startswith('data:'):
                print(l2[5:][:200]); break
        break
" 2>/dev/null) || echo "(empty)"
echo "  Round1: ${r1:0:50}..."
echo "  Round2: ${r2:0:50}..."
record $((++TOTAL)) "$LABEL" "${r1}${r2}" $(( $(date +%s) - start ))

# M4-M6 简化版
for idx in 4 5 6; do
  case $idx in
    4) q1="报销流程是什么？"; q2="那发票丢了怎么办？"; kb="$KB_FINANCE";;
    5) q1="OA系统怎么申请办公用品？"; q2="审批需要多久？"; kb="$KB_OA";;
    6) q1="VPN连不上怎么办？"; q2="有没有其他远程访问方式？"; kb="$KB_IT";;
  esac
  LABEL="M${idx}. 多轮对话"; echo "[$((TOTAL+1))/100] $LABEL"
  start=$(date +%s)
  conv_id=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
  r1=$(do_chat "$q1" "$kb")
  r2=$(curl $CURL_OPTS -X POST "$BASE_URL/rag/v3/chat" -H "Content-Type: application/json" -H "Authorization: $TOKEN" \
    -d "$(python3 -c "import json; print(json.dumps({'question': '$q2', 'conversationId': '$conv_id', 'knowledgeBaseIds': ['$kb']}))")" \
    | python3 -c "
import sys
lines = sys.stdin.read().split('\n')
for line in lines:
    if line.startswith('event: finish'):
        for l2 in lines:
            if l2.startswith('data:'):
                print(l2[5:][:200]); break
        break
" 2>/dev/null) || echo "(empty)"
  echo "  Round1: ${r1:0:50}..."
  echo "  Round2: ${r2:0:50}..."
  record $((++TOTAL)) "$LABEL" "${r1}${r2}" $(( $(date +%s) - start ))
done

# ===========================================================================
# N. 混合场景（4 个）
# ===========================================================================
echo -e "\n${CYN}━━━ N. 混合场景 ━━━${NC}"

LABEL="N1. KB+MCP"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司的IT支持有哪些？另外今天北京的天气怎么样？" "$KB_IT"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="N2. KB+SKILL"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "公司的招聘信息有哪些？顺便帮我搜索一下IT行业最新的招聘趋势" "$KB_HR"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="N3. MCP+SKILL"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "今天深圳天气怎么样？再帮我搜索一下深圳有什么好玩的景点"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

LABEL="N4. KB+MCP+SKILL"; echo "[$((TOTAL+1))/100] $LABEL"
start=$(date +%s); r=$(do_chat "我想去北京出差三天，查一下北京的天气，再看看公司的差旅报销标准，然后帮我搜索一下北京的酒店推荐" "$KB_FINANCE"); echo "$r" | head -3; record $((++TOTAL)) "$LABEL" "$r" $(( $(date +%s) - start ))

# ===========================================================================
# 报告
# ===========================================================================
echo ""
echo -e "${CYN}════════════════════════════════════════════════${NC}"
echo -e "${CYN}  测试完成${NC}"
echo -e "${CYN}  完成时间: $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo -e "${CYN}════════════════════════════════════════════════${NC}"
echo ""
echo -e "  总计: 100   通过: ${GRN}${PASSED}${NC}   失败: ${RED}${FAILED}${NC}   超时: ${YLW}${TIMEOUTS}${NC}"
echo ""

if [ $FAILED -gt 0 ]; then
  echo -e "${RED}━━ 失败/超时 用例 ━━${NC}"
  for result in "${RESULTS[@]}"; do
    IFS='|' read -r num label status duration <<< "$result"
    if echo "$status" | grep -qE "失败|超时|空回答"; then
      echo "  [$num] $label → $status"
    fi
  done
  echo ""
fi

echo -e "${CYN}详细结果已保存到运行日志中${NC}"
echo ""
