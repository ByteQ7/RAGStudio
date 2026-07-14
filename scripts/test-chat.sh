#!/bin/bash
# RAGStudio 对话测试套件 - 100 用例（含 L1-L5 验证 + 完整 SSE Log）
# 用法: ./scripts/test-chat.sh [BASE_URL]
set -euo pipefail

BASE_URL="${1:-http://localhost:9090/api/ragstudio}"
USERNAME="admin"; PASSWORD="admin"
TIMEOUT_SEC=45
LOG_DIR="logs/chat-test-$(date +%Y%m%d-%H%M%S)"
CURL_OPTS="-s --max-time $TIMEOUT_SEC"

# KB IDs
KB_HR="2077043298647863296"
KB_IT="2077044595442774016"
KB_INVOICE="2077051696852430848"
KB_FINANCE="2077051966990774272"
KB_OA="2077052306691649536"

# 统计
declare -a RESULTS
declare -a FLAGGED
TOTAL=0; PASSED=0; FAILED=0; TIMEOUTS=0; LEVELS=(0 0 0 0 0)

# 颜色
GRN='\033[0;32m'; RED='\033[0;31m'; YLW='\033[1;33m'; BLU='\033[0;34m'; CYN='\033[0;36m'; MGT='\033[0;35m'; NC='\033[0m'

mkdir -p "$LOG_DIR"
echo "log_dir=$LOG_DIR"

# ===== 登录 =====
do_login() {
  local resp
  resp=$(curl $CURL_OPTS -X POST "$BASE_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
  TOKEN=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null || true)
  if [ -z "$TOKEN" ]; then echo "登录失败"; exit 1; fi
}

# ===== 发送对话 + 完整 SSE Log =====
# 返回: SSE_raw|answer_text
do_chat() {
  local question="$1" kb_ids="${2:-}" thinking="${3:-0}" label="${4:-}"
  local safe_label
  safe_label=$(echo "$label" | sed 's/[^a-zA-Z0-9_-]/_/g')
  local log_file="$LOG_DIR/${safe_label}.sse.log"
  local question_file="$LOG_DIR/${safe_label}.question.json"

  # 构建请求 JSON
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

  # 记录请求
  echo "$data" > "$question_file"

  # 发送请求，捕获完整响应
  local resp
  resp=$(curl $CURL_OPTS -X POST "$BASE_URL/rag/v3/chat" \
    -H "Content-Type: application/json" \
    -H "Authorization: $TOKEN" \
    -d "$data" 2>&1) || {
    echo "__TIMEOUT__|__TIMEOUT__" > "$log_file" 2>/dev/null
    echo "__TIMEOUT__|__TIMEOUT__"
    return
  }

  # 保存完整 SSE 响应
  echo "$resp" > "$log_file"

  # 从 SSE 解析最终回答
  local answer
  answer=$(echo "$resp" | python3 -c "
import sys, json
lines = sys.stdin.read().split('\n')
answer_parts = []
answer_text = ''
for line in lines:
    if line.startswith('event: finish'):
        pass
    elif line.startswith('data: '):
        try:
            p = json.loads(line[6:])
            if isinstance(p, dict):
                if p.get('type') == 'response' and p.get('delta'):
                    answer_parts.append(p['delta'])
                if p.get('answer'):
                    answer_text = p['answer']
        except:
            pass
result = answer_text or ''.join(answer_parts)
print(result if result else '(empty)')
" 2>/dev/null) || echo "(parse error)"

  echo "$resp|$answer"
}

# ===== 验证函数 =====

# L1: 存活检测 — 回答非空且不含特定失败关键词
check_l1() {
  local answer="$1"
  if [ -z "$answer" ] || [ "$answer" = "(empty)" ] || [ "$answer" = "(parse error)" ] || [ "$answer" = "__TIMEOUT__" ]; then
    return 1
  fi
  # 检查是否包含典型的"不知道"表达
  if echo "$answer" | grep -qiE "抱歉(，|,)?我(不|没)|无法回答|不清楚|不明白|not found|error"; then
    return 2
  fi
  LEVELS[0]=$((LEVELS[0]+1))
  return 0
}

# L2: 关键词命中
check_l2() {
  local answer="$1"; shift
  local keywords=("$@")
  for kw in "${keywords[@]}"; do
    if echo "$answer" | grep -qiF "$kw"; then
      LEVELS[1]=$((LEVELS[1]+1))
      return 0
    fi
  done
  return 1
}

# L3: 引用验证 — SSE 中有 citation 事件
check_l3() {
  local sse_raw="$1"
  if echo "$sse_raw" | grep -q "event: citation"; then
    LEVELS[2]=$((LEVELS[2]+1))
    return 0
  fi
  return 1
}

# L4: 工具调用验证
check_l4() {
  local sse_raw="$1"
  if echo "$sse_raw" | grep -q "event: agent_step"; then
    LEVELS[3]=$((LEVELS[3]+1))
    return 0
  fi
  return 1
}

# L4s: 特定工具调用验证
check_l4_tool() {
  local sse_raw="$1" tool_name="$2"
  if echo "$sse_raw" | grep -q "event: agent_step" && echo "$sse_raw" | python3 -c "
import sys, json
for line in sys.stdin:
    if line.startswith('data: ') and 'agent_step' in line:
        try:
            p = json.loads(line[6:])
            if isinstance(p, dict) and p.get('toolName','').lower() == '$tool_name'.lower():
                print('found')
        except:
            pass
" 2>/dev/null | grep -q found; then
    return 0
  fi
  # 宽松：检查 action 字段
  if echo "$sse_raw" | python3 -c "
import sys, json
for line in sys.stdin:
    if line.startswith('data: '):
        try:
            p = json.loads(line[6:])
            if isinstance(p, dict) and ('$tool_name' in str(p.get('action','')) or '$tool_name' in str(p.get('toolName',''))):
                print('found')
        except:
            pass
" 2>/dev/null | grep -q found; then
    return 0
  fi
  return 1
}

# L5: 多步推理验证 — 检查有 Plan 或多个 Action
check_l5() {
  local sse_raw="$1"
  # 检查 Plan 字段
  local has_plan=1; local action_count=0
  if echo "$sse_raw" | python3 -c "
import sys, json
for line in sys.stdin:
    if line.startswith('data: '):
        try:
            p = json.loads(line[6:])
            if isinstance(p, dict):
                if p.get('plan'):
                    print('plan')
                if p.get('action') and p['action'] != 'FINISH':
                    print('action')
        except:
            pass
" 2>/dev/null | grep -q plan; then
    has_plan=0
  fi
  action_count=$(echo "$sse_raw" | python3 -c "
import sys, json
c=0
for line in sys.stdin:
    if line.startswith('data: '):
        try:
            p = json.loads(line[6:])
            if isinstance(p, dict) and p.get('action') and p['action'] != 'FINISH':
                c+=1
        except:
            pass
print(c)
" 2>/dev/null)
  if [ "$has_plan" -eq 0 ] || [ "${action_count:-0}" -ge 2 ]; then
    LEVELS[4]=$((LEVELS[4]+1))
    return 0
  fi
  return 1
}

# ===== 运行一个测试用例 =====
run_test() {
  local num="$1" label="$2" question="$3" kb="${4:-}" thinking="${5:-0}"
  local category="${label:0:1}"
  local expected_kw="${6:-}"

  TOTAL=$((TOTAL+1))
  echo -e "\n${BLU}[$(printf "%03d" $num)/100]${NC} ${CYN}${label}${NC}"

  start=$(date +%s)
  local raw_result
  raw_result=$(do_chat "$question" "$kb" "$thinking" "test${num}")
  local duration=$(( $(date +%s) - start ))
  local sse_raw answer
  sse_raw="${raw_result%%|*}"
  answer="${raw_result#*|}"

  # 验证等级
  local l1_level=0 l2_pass=1 l3_pass=1 l4_pass=1 l5_pass=1
  local l1_status=""
  check_l1 "$answer"; l1_level=$?
  if [ "$l1_level" -eq 0 ]; then
    l1_status="L1✓"
    # L2
    if [ -n "$expected_kw" ]; then
      IFS=',' read -ra kws <<< "$expected_kw"
      if check_l2 "$answer" "${kws[@]}"; then
        l2_pass=0; l1_status+=" L2✓"
      else
        l1_status+=" ${YLW}L2✗${NC}"
      fi
    fi
    # L3: KB 类检查引用
    if [ -n "$kb" ]; then
      if check_l3 "$sse_raw"; then
        l3_pass=0; l1_status+=" L3✓"
      else
        l1_status+=" ${YLW}L3✗${NC}"
      fi
    fi
    # L4: MCP/SKILL 类检查工具调用
    if [[ "$category" == "H" || "$category" == "I" ]]; then
      if check_l4 "$sse_raw"; then
        l4_pass=0; l1_status+=" L4✓"
      else
        l1_status+=" ${YLW}L4✗${NC}"
      fi
    fi
    # L5: 多步推理
    if [[ "$category" == "J" || "$category" == "N" ]]; then
      if check_l5 "$sse_raw"; then
        l5_pass=0; l1_status+=" L5✓"
      else
        l1_status+=" ${YLW}L5✗${NC}"
      fi
    fi
    PASSED=$((PASSED+1))
    echo -e "  ${GRN}✓${NC} ${l1_status} (${duration}s)"
  elif [ "$l1_level" -eq 2 ]; then
    l1_status="${YLW}L1⚠(含拒绝词)${NC}"
    FLAGGED+=("$num|$label|含拒绝词|${duration}s|test${num}.sse.log")
    PASSED=$((PASSED+1))
    echo -e "  ${YLW}⚠${NC} ${l1_status} (${duration}s)"
  elif [ "$answer" = "__TIMEOUT__" ]; then
    TIMEOUTS=$((TIMEOUTS+1)); FAILED=$((FAILED+1))
    FLAGGED+=("$num|$label|超时|${duration}s|test${num}.sse.log")
    echo -e "  ${RED}⏱ 超时${NC}"
  else
    FAILED=$((FAILED+1))
    FLAGGED+=("$num|$label|空回答|${duration}s|test${num}.sse.log")
    echo -e "  ${RED}✗ 空回答${NC}"
  fi

  RESULTS+=("$num|$label|$answer|$duration")

  # 回答预览
  local preview
  preview=$(echo "$answer" | head -c 120)
  [ ${#answer} -gt 120 ] && preview+="…"
  echo -e "  ${MGT}预览:${NC} $preview"

  # 节流，避免过载
  sleep 0.3
}

# ===== 分隔线 =====
sep() { echo -e "\n${CYN}━━━ $1 ━━━${NC}"; }

# ===== 主流程 =====
echo -e "${CYN}════════════════════════════════════════════════${NC}"
echo -e "${CYN}  RAGStudio 对话测试套件${NC}"
echo -e "${CYN}  用例: 100  |  API: $BASE_URL${NC}"
echo -e "${CYN}  Logs: $LOG_DIR${NC}"
echo -e "${CYN}  时间: $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo -e "${CYN}════════════════════════════════════════════════${NC}"
do_login

# ===========================================================================
# A. 问候与自我介绍（6）
# ===========================================================================
sep "A. 问候与自我介绍"
run_test 1 "A1_你好" "你好"
run_test 2 "A2_你是谁" "你是谁？"
run_test 3 "A3_你能做什么" "你能做什么？"
run_test 4 "A4_是不是ChatGPT" "你是ChatGPT吗？"
run_test 5 "A5_谁开发了你" "谁开发了你？"
run_test 6 "A6_底层模型" "你们用的什么底层模型？"

# ===========================================================================
# B. HR 知识库（10）
# ===========================================================================
sep "B. HR 知识库"
run_test 7 "B1_年假计算" "年假怎么计算？入职多久可以休年假？" "$KB_HR" 0 "年假"
run_test 8 "B2_病假政策" "病假怎么请？需要什么材料？" "$KB_HR" 0 "病假"
run_test 9 "B3_考勤制度" "公司的考勤制度是怎么样的？上下班时间？" "$KB_HR" 0 "考勤"
run_test 10 "B4_绩效考核" "绩效考核周期是多久？怎么评定的？" "$KB_HR" 0 "绩效"
run_test 11 "B5_招聘信息" "公司最近有什么招聘岗位？" "$KB_HR" 0 "招聘"
run_test 12 "B6_薪资福利" "公司的薪资福利政策包括哪些？" "$KB_HR" 0 "薪资"
run_test 13 "B7_员工培训" "公司有哪些培训项目？" "$KB_HR" 0 "培训"
run_test 14 "B8_劳动合同" "劳动合同的签订和续签流程是什么？" "$KB_HR" 0 "合同"
run_test 15 "B9_加班制度" "加班怎么计算加班费？" "$KB_HR" 0 "加班"
run_test 16 "B10_转正流程" "试用期多久？转正流程是什么？" "$KB_HR" 0 "转正"

# ===========================================================================
# C. IT 知识库（8）
# ===========================================================================
sep "C. IT 知识库"
run_test 17 "C1_VPN连接" "VPN怎么连接？" "$KB_IT" 0 "VPN"
run_test 18 "C2_企业邮箱" "企业邮箱怎么配置？" "$KB_IT" 0 "邮箱"
run_test 19 "C3_打印机" "打印机怎么连接？" "$KB_IT" 0 "打印"
run_test 20 "C4_网络安全" "公司的网络安全管理制度有哪些要求？" "$KB_IT" 0 "安全"
run_test 21 "C5_安全事件" "发生安全事件应该怎么处理？" "$KB_IT" 0 "安全"
run_test 22 "C6_账号问题" "账号密码忘记了怎么办？" "$KB_IT" 0 "账号"
run_test 23 "C7_IT支持范围" "IT支持的范围包括哪些？" "$KB_IT" 0 "IT"
run_test 24 "C8_部署规范" "部署规范有哪些要求？" "$KB_IT" 0 "部署"

# ===========================================================================
# D. 财务知识库（6）
# ===========================================================================
sep "D. 财务知识库"
run_test 25 "D1_报销流程" "报销流程是什么？" "$KB_FINANCE" 0 "报销"
run_test 26 "D2_印章管理" "印章管理制度是什么？" "$KB_FINANCE" 0 "印章"
run_test 27 "D3_合同管理" "合同管理的流程是什么？" "$KB_FINANCE" 0 "合同"
run_test 28 "D4_预算管理" "预算怎么申请？审批流程是什么？" "$KB_FINANCE" 0 "预算"
run_test 29 "D5_报销标准" "差旅报销标准是多少？" "$KB_FINANCE" 0 "报销"
run_test 30 "D6_税务政策" "公司适用的税务政策有哪些？" "$KB_FINANCE" 0 "税务"

# ===========================================================================
# E. OA 知识库（6）
# ===========================================================================
sep "E. OA 知识库"
run_test 31 "E1_OA系统使用" "OA系统怎么登录和使用？" "$KB_OA" 0 "OA"
run_test 32 "E2_办公用品申领" "办公用品怎么申领？" "$KB_OA" 0 "办公"
run_test 33 "E3_OA数据安全" "OA系统的数据安全规范是什么？" "$KB_OA" 0 "安全"
run_test 34 "E4_客户服务" "客户服务流程是什么？" "$KB_OA" 0 "客户"
run_test 35 "E5_商务接待" "商务接待的流程和标准是什么？" "$KB_OA" 0 "接待"
run_test 36 "E6_销售管理" "销售管理系统的功能有哪些？" "$KB_OA" 0 "销售"

# ===========================================================================
# F. 开票信息（4）
# ===========================================================================
sep "F. 开票信息"
run_test 37 "F1_开票信息" "公司的开票信息是什么？" "$KB_INVOICE" 0 "开票"
run_test 38 "F2_税号查询" "公司的税号是多少？" "$KB_INVOICE" 0 "税号"
run_test 39 "F3_银行账户" "公司对公银行账户信息是什么？" "$KB_INVOICE" 0 "银行"
run_test 40 "F4_发票抬头" "发票抬头怎么写？" "$KB_INVOICE" 0 "发票"

# ===========================================================================
# G. 跨 KB 综合（6）
# ===========================================================================
sep "G. 跨知识库综合"
run_test 41 "G1_HR+财务" "年假期间的工资怎么算？" "$KB_HR,$KB_FINANCE" 0 "年假"
run_test 42 "G2_IT+OA" "IT部门怎么在OA系统提交工单？" "$KB_IT,$KB_OA" 0 "OA"
run_test 43 "G3_财务+OA" "OA系统的报销审批流程是什么？" "$KB_FINANCE,$KB_OA" 0 "报销"
run_test 44 "G4_三个库" "新员工入职需要哪些手续？" "$KB_HR,$KB_IT,$KB_OA" 0 "入职"
run_test 45 "G5_HR+OA" "请假在OA系统上怎么操作？" "$KB_HR,$KB_OA" 0 "请假"
run_test 46 "G6_IT+财务" "IT设备采购的预算审批流程是什么？" "$KB_IT,$KB_FINANCE" 0 "采购"

# ===========================================================================
# H. MCP 工具（8）
# ===========================================================================
sep "H. MCP 工具"
run_test 47 "H1_天气_深圳" "今天深圳的天气怎么样？" "" 0 "天气|温度|°"
run_test 48 "H2_天气_北京" "北京明天天气如何？" "" 0 "北京|天气"
run_test 49 "H3_天气_上海" "上海这周末天气怎么样？" "" 0 "上海|天气"
run_test 50 "H4_天气_广州" "广州今天多少度？会不会下雨？" "" 0 "广州|°"
run_test 51 "H5_图像_海滩" "帮我生成一张夕阳海滩的图片" "" 0 "图片|生成"
run_test 52 "H6_图像_城市" "生成一张未来城市夜景的图片" "" 0 "图片"
run_test 53 "H7_图像_山水" "画一幅水墨山水画" "" 0 "画"
run_test 54 "H8_天气_缺参数" "天气怎么样？" "" 0 "城市"

# ===========================================================================
# I. SKILL 工具（6）
# ===========================================================================
sep "I. SKILL 工具"
run_test 55 "I1_搜索_新闻" "帮我搜索一下最近AI相关的新闻" "" 0 "AI"
run_test 56 "I2_搜索_技术" "搜索一下Rust语言的最新发展" "" 0 "Rust"
run_test 57 "I3_搜索_行业" "帮我查一下最近云计算行业动态" "" 0 "云"
run_test 58 "I4_系统信息" "查询一下服务器的系统信息" "" 0 "CPU|内存|磁盘"
run_test 59 "I5_搜索+知识库" "搜索最新的员工培训方法，结合公司制度" "$KB_HR" 0 "培训"
run_test 60 "I6_系统信息+" "服务器的磁盘和内存使用情况怎么样？" "" 0 "磁盘|内存"

# ===========================================================================
# J. 多步推理（14）
# ===========================================================================
sep "J. 多步推理"
run_test 61 "J1_今天+天气+建议" "今天是什么日子？深圳天气怎么样？" "" 0 "天气"
run_test 62 "J2_节日+天气" "明天是什么节日？北京天气如何？" "" 0 "天气"
run_test 63 "J3_多城市天气" "深圳和北京今天哪个更热？" "" 0 "深圳|北京"
run_test 64 "J4_日期+星期" "这周五是几号？" "" 0 "周"
run_test 65 "J5_三天天气" "这三天深圳的天气趋势怎么样？" "" 0 "天气"
run_test 66 "J6_知识库+搜索" "查一下加班费规定，再搜一下劳动法最新规定" "$KB_HR" 0 "加班"
run_test 67 "J7_多步+比较" "北京和上海的天气哪个适合出行？" "" 0 "北京|上海"
run_test 68 "J8_时间推算" "45分钟后是什么时间？" "" 0 "时间"
run_test 69 "J9_日期+事件" "下周一有什么节日或者纪念日？" "" 0 "节"
run_test 70 "J10_知识库+天气" "公司请假规定是什么？顺便看看明天天气" "$KB_HR" 0 "请假|天气"
run_test 71 "J11_多段推理" "查一下今天日期→查天气→查节日→给出出行建议" "" 0 "天气|建议"
run_test 72 "J12_对比推理" "比较一下Rust和Go语言的优缺点，再查查最近的发展" "" 0 "Rust|Go"
run_test 73 "J13_财务+搜索" "报销标准是多少？顺便查查最新税务政策" "$KB_FINANCE" 0 "报销"
run_test 74 "J14_全流程" "今天星期几？北京天气怎么样？适合做什么？" "" 0 "北京|天气"

# ===========================================================================
# K. 深度思考（8）
# ===========================================================================
sep "K. 深度思考"
run_test 75 "K1_深度0_简单" "1+1等于几？" "" 0
run_test 76 "K2_深度0_知识库" "年假怎么计算？" "$KB_HR" 0 "年假"
run_test 77 "K3_深度30_推理" "如果今天是周三，那么100天后是星期几？" "" 30 "星期"
run_test 78 "K4_深度30_知识库" "公司的培训制度有哪些内容？" "$KB_HR" 30 "培训"
run_test 79 "K5_深度70_复杂" "一个水池，进水管5小时注满，排水管8小时排空，同时开多久满？" "" 70 "小时"
run_test 80 "K6_深度70_分析" "比较一下微服务架构和单体架构的优缺点" "" 70 "微服务"
run_test 81 "K7_深度100_代码" "用Python写一个快速排序算法" "" 100 "排序"
run_test 82 "K8_深度100_数学" "证明根号2是无理数" "" 100 "无理数"

# ===========================================================================
# L. 边界情况（8）
# ===========================================================================
sep "L. 边界情况"
run_test 83 "L1_空问题" "" "" 0
run_test 84 "L2_超短" "？" "" 0
run_test 85 "L3_超长" "$(python3 -c "print('a'*5000)")" "" 0
run_test 86 "L4_特殊字符" "@#$%^&*()_+{}[]|\\:;\"'<>,.?/~\`！@#¥%……&*" "" 0
run_test 87 "L5_无知识库" "公司附近有什么好吃的？" "" 0
run_test 88 "L6_纯数字" "42" "" 0
run_test 89 "L7_情绪化" "你怎么这么笨！！！回答我的问题！！！" "" 0
run_test 90 "L8_不相关问题" "今晚欧冠比赛谁赢了？" "" 0

# ===========================================================================
# M. 多轮对话（6）
# ===========================================================================
sep "M. 多轮对话"
run_test 91 "M1_第一轮" "公司年假怎么计算？" "$KB_HR" 0 "年假"
run_test 92 "M2_第二轮追问" "那病假呢？" "$KB_HR" 0 "病假"
run_test 93 "M3_第三轮补充" "如果我是入职2年，能休几天？" "$KB_HR" 0 "天"
run_test 94 "M4_新话题开始" "OA系统怎么申请办公用品？" "$KB_OA" 0 "办公"
run_test 95 "M5_跨话题" "IT部门的网络安全要求有哪些？" "$KB_IT" 0 "安全"
run_test 96 "M6_综合回顾" "我刚才问了哪些问题？" "$KB_HR,$KB_IT,$KB_OA" 0

# ===========================================================================
# N. 混合场景（4）
# ===========================================================================
sep "N. 混合场景"
run_test 97 "N1_知识库+SKILL" "公司的培训制度和行业最新培训方法对比" "$KB_HR" 0 "培训"
run_test 98 "N2_MCP+知识库" "今天天气适合做什么户外活动？顺便查查公司户外活动规定" "$KB_HR" 0 "天气"
run_test 99 "N3_多步+多工具" "今天北京天气→推荐适合穿的服装→查查公司着装规定" "$KB_HR" 0 "天气|北京"
run_test 100 "N4_三合一" "公司报销规定→搜索最新税务政策→今天天气适合去税务局吗" "$KB_FINANCE" 0 "报销|天气"

# ===========================================================================
# 报告
# ===========================================================================
echo -e "\n${CYN}════════════════════════════════════════════════${NC}"
echo -e "${CYN}  测 试 完 成${NC}"
echo -e "${CYN}  时间: $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo -e "${CYN}  Logs: $LOG_DIR${NC}"
echo -e "${CYN}════════════════════════════════════════════════${NC}"
echo -e "  总计: 100  |  通过: ${GRN}${PASSED}${NC}  |  失败: ${RED}${FAILED}${NC}  |  超时: ${YLW}${TIMEOUTS}${NC}"
echo -e "  验证: L1(存活)=${LEVELS[0]}  L2(关键词)=${LEVELS[1]}  L3(引用)=${LEVELS[2]}  L4(工具)=${LEVELS[3]}  L5(多步)=${LEVELS[4]}"
echo ""

# Flagged 列表
if [ ${#FLAGGED[@]} -gt 0 ]; then
  echo -e "${YLW}━━ 需要人工审查的用例 ━━${NC}"
  echo -e "  编号\t| 标签\t\t| 原因\t| 耗时\t| Log 文件"
  echo -e "  ${YLW}────────────────────────────────────────────────${NC}"
  for f in "${FLAGGED[@]}"; do
    IFS='|' read -r num label reason duration logfile <<< "$f"
    echo -e "  ${RED}[$num]${NC}\t| $label\t| $reason\t| ${duration}s\t| ${MGT}$logfile${NC}"
  done
  echo ""
fi

# 详细结果摘要
echo -e "${CYN}━━ 测试结果摘要（前 20 条回答）━━${NC}"
for result in "${RESULTS[@]:0:20}"; do
  IFS='|' read -r num label answer duration <<< "$result"
  preview=$(echo "$answer" | head -c 80)
  [ ${#answer} -gt 80 ] && preview+="…"
  echo -e "  [${BLU}$num${NC}] ${CYN}$label${NC}: $preview"
done

echo -e "\n${CYN}完整 SSE 日志目录:${NC} $LOG_DIR"
echo -e "${CYN}查看单个日志:${NC} cat $LOG_DIR/test1.sse.log"
echo -e "${CYN}查看所有请求:${NC} ls $LOG_DIR/*.question.json"
echo ""
