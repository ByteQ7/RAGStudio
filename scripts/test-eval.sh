#!/bin/bash
# RAGStudio 质量评估测试套件 — 召回率 + 命中率 + 正确率
# 用法: ./scripts/test-eval.sh [base_url]
set -uo pipefail

BASE_URL="${1:-http://localhost:9090/api/ragstudio}"
USER="${2:-admin}"
PASS="${3:-admin}"
TIMEOUT=90
LOG_DIR="logs/eval-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOG_DIR"

# ─── Agent 回答 QPS 配置（并发时批量运行用） ───
CONCURRENT=${CONCURRENT:-1}

# ─── RAG 检索评估（需要 rag-trace 接口开启） ───
EVAL_RETRIEVAL=${EVAL_RETRIEVAL:-0}

# ─── KB IDs ───
KB_HR="2079550081669562368"
KB_IT="2079555154697031680"
KB_FINANCE="2079556280884432896"
KB_INVOICE="2079555656021217280"
KB_OA="2079556733579857920"
KB_MKT="2082802790422646784"
KB_RD="2082811647886725120"
KB_STR="2082812310922301440"

# 所有请求统一携带全部 KB ID，确保每次检索都覆盖全部知识库
ALL_KB="$KB_HR,$KB_IT,$KB_FINANCE,$KB_INVOICE,$KB_OA,$KB_MKT,$KB_RD,$KB_STR"

# ─── 全局状态 ───
TOTAL=0; PASSED=0
TOTAL_TIME=0
TOTAL_KEYWORD_EXPECTED=0; TOTAL_KEYWORD_MATCHED=0
TOTAL_CITATION_CASES=0; TOTAL_TOOL_CASES=0
declare -A DEPT_TOTAL DEPT_PASSED DEPT_KW_EXP DEPT_KW_MATCH DEPT_TIME

GRN='\033[0;32m'; RED='\033[0;31m'; YLW='\033[1;33m'
BLU='\033[0;34m'; CYN='\033[0;36m'; BLD='\033[1m'; NC='\033[0m'

# ─── 登录 ───
login() {
  local resp
  resp=$(curl -s --max-time 10 -X POST "$BASE_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
  TOKEN=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
  [ -n "$TOKEN" ] || { echo "❌ 登录失败"; exit 1; }
}

# ─── SSE 解析 (Python 内联) ───
parse_sse() {
  local tag="$1"
  python3 -c "
import json, sys, os

sse_path = '$LOG_DIR/$tag.sse'
if not os.path.exists(sse_path):
    print('__TIMEOUT__')
    sys.exit(0)

with open(sse_path) as f:
    raw = f.read()

answer = ''
msgs = []
citations = 0
tool_names = []
in_msg = False

for line in raw.split('\n'):
    line = line.strip()
    if line.startswith('event:'):
        e = line[6:].strip()
        in_msg = (e == 'message')
        if e == 'citation':
            citations += 1
    elif line.startswith('data:'):
        data = line[5:]
        try:
            p = json.loads(data)
            if isinstance(p, dict):
                if p.get('action') == 'FINISH' and p.get('finalAnswer'):
                    answer = p['finalAnswer']
                if in_msg and p.get('type') == 'response' and p.get('delta'):
                    msgs.append(p['delta'])
                if p.get('action') not in (None, 'finish', 'FINISH', '') and p.get('action'):
                    tool_names.append(p['action'])
        except:
            pass

if not answer:
    answer = ''.join(msgs)

# Output: answer | citations | tools_comma_sep
print(json.dumps({
    'answer': answer[:3000] if answer else '(empty)',
    'citations': citations,
    'tools': ','.join(tool_names)
}, ensure_ascii=False))
" 2>/dev/null
}

# ─── 单次对话 ───
do_chat() {
  local question="$1" kb="$2" thinking="$3" tag="$4"
  local data
  # 用环境变量传参避免 Python 字符串注入
  export _PY_Q="$question" _PY_KB="$kb" _PY_THINK="$thinking"
  if [ -n "$kb" ]; then
    data=$(python3 -c "
import os, json
q = os.environ.get('_PY_Q','')
kb = os.environ.get('_PY_KB','')
ids = [x for x in kb.split(',') if x]
d = {'question': q, 'deepThinkingLevel': int(os.environ.get('_PY_THINK','0'))}
if ids: d['knowledgeBaseIds'] = ids
print(json.dumps(d, ensure_ascii=False))
")
  else
    data=$(python3 -c "
import os, json
d = {'question': os.environ.get('_PY_Q',''), 'deepThinkingLevel': int(os.environ.get('_PY_THINK','0'))}
print(json.dumps(d, ensure_ascii=False))
")
  fi
  unset _PY_Q _PY_KB _PY_THINK
  echo "$data" > "$LOG_DIR/$tag.req.json"

  curl -s --max-time "$TIMEOUT" -X POST "$BASE_URL/rag/v3/chat" \
    -H "Content-Type: application/json" \
    -H "Authorization: $TOKEN" \
    -d "$data" > "$LOG_DIR/$tag.sse" 2>/dev/null || { echo '__TIMEOUT__'; return; }

  parse_sse "$tag"
}

# ─── 评估单条用例 ───
evaluate() {
  local num="$1" dept="$2" label="$3" question="$4" kb="${5:-}" thinking="${6:-0}" keywords="${7:-}"

  TOTAL=$((TOTAL + 1))
  DEPT_TOTAL["$dept"]=$((${DEPT_TOTAL["$dept"]:-0} + 1))
  local tag
  tag=$(printf "%03d" $num)-$(echo "$label" | tr -cd 'a-zA-Z0-9_-' | head -c30)

  local t0
  t0=$(date +%s%N)

  # ─── 输出用例信息 ───
  local label_pad
  printf -v label_pad "%-16s" "$label"
  printf "${BLU}[%03d]${NC} ${CYN}%-16s${NC} " "$num" "$label_pad"

  local result
  result=$(do_chat "$question" "$kb" "$thinking" "$tag")
  local dur_ms=$(( ($(date +%s%N) - t0) / 1000000 ))

  # 解析 JSON 结果
  local answer citations tools
  answer=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('answer',''))" 2>/dev/null)
  citations=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('citations',0))" 2>/dev/null)
  tools=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tools',''))" 2>/dev/null)

  # ─── L0: 存活检查 ───
  local alive=false
  [ "$answer" != "__TIMEOUT__" ] && [ -n "$answer" ] && [ "$answer" != "(empty)" ] && alive=true

  # ─── L1: 关键词命中率 ───
  # 归一化匹配：去除逗号/空格/全角标点后再匹配一次（如答案 "5,200万元" 可命中关键词 "5200万"）
  local kw_exp=0 kw_hit=0 kw_ratio=0
  if [ -n "$keywords" ]; then
    kw_exp=$(echo "$keywords" | tr ',' '\n' | grep -c .)
    kw_hit=$(KWS="$keywords" ANS="$answer" python3 -c "
import os, re
keywords = [k for k in os.environ.get('KWS', '').split(',') if k]
answer = os.environ.get('ANS', '')
def norm(s):
    return re.sub(r'[，、。；;：:\s,·]+', '', s.lower())
na = norm(answer)
hits = 0
for kw in keywords:
    if kw.lower() in answer or norm(kw) in na:
        hits += 1
print(hits)
" 2>/dev/null)
    [ "$kw_exp" -gt 0 ] && kw_ratio=$(( kw_hit * 100 / kw_exp ))
  fi

  TOTAL_KEYWORD_EXPECTED=$((TOTAL_KEYWORD_EXPECTED + kw_exp))
  TOTAL_KEYWORD_MATCHED=$((TOTAL_KEYWORD_MATCHED + kw_hit))
  DEPT_KW_EXP["$dept"]=$((${DEPT_KW_EXP["$dept"]:-0} + kw_exp))
  DEPT_KW_MATCH["$dept"]=$((${DEPT_KW_MATCH["$dept"]:-0} + kw_hit))

  # ─── L2: 引用覆盖 ───
  local has_citation=false
  [ "${citations:-0}" -gt 0 ] && has_citation=true && TOTAL_CITATION_CASES=$((TOTAL_CITATION_CASES + 1))

  # ─── L3: 工具调用 ───
  local has_tool=false
  [ -n "$tools" ] && [ "$tools" != "__TIMEOUT__" ] && has_tool=true && TOTAL_TOOL_CASES=$((TOTAL_TOOL_CASES + 1))

  # ─── 判定 ───
  local passed=false
  if $alive; then
    if [ $kw_exp -eq 0 ] || [ $kw_hit -gt 0 ]; then
      passed=true
      PASSED=$((PASSED + 1))
      DEPT_PASSED["$dept"]=$((${DEPT_PASSED["$dept"]:-0} + 1))
    fi
  fi

  TOTAL_TIME=$((TOTAL_TIME + dur_ms))
  DEPT_TIME["$dept"]=$((${DEPT_TIME["$dept"]:-0} + dur_ms))

  # ─── 单行摘要 ───
  local status_icon="" kw_bar=""
  if $passed; then
    status_icon="${GRN}✓ PASS${NC}"
  else
    status_icon="${RED}✗ FAIL${NC}"
  fi

  # 关键词进度条
  if [ $kw_exp -gt 0 ]; then
    local bar_len=8 filled=0
    filled=$(( kw_ratio * bar_len / 100 ))
    local bar=""
    for i in $(seq 1 $bar_len); do
      if [ $i -le $filled ]; then bar+="█"; else bar+="░"; fi
    done
    kw_bar=" KW:${bar} ${kw_hit}/${kw_exp}"
  fi

  local citemark="" toolmark=""
  $has_citation && citemark=" cite" || citemark=""
  $has_tool && toolmark=" tool" || toolmark=""

  printf "${status_icon} %4dms%s%s%s\n" "$dur_ms" "$kw_bar" "$citemark" "$toolmark"
}

# ════════════════════════════════════════════════════════════
#  报告生成
# ════════════════════════════════════════════════════════════
report() {
  echo ""
  echo -e "${CYN}╔══════════════════════════════════════════════════════════╗${NC}"
  echo -e "${CYN}║              RAGStudio 质量评估报告                     ║${NC}"
  echo -e "${CYN}╚══════════════════════════════════════════════════════════╝${NC}"
  echo ""

  # ─── 总体指标 ───
  local overall_pass_rate=0 avg_kw_rate=0 avg_ms=0
  [ $TOTAL -gt 0 ] && overall_pass_rate=$(( PASSED * 100 / TOTAL ))
  [ $TOTAL_KEYWORD_EXPECTED -gt 0 ] && avg_kw_rate=$(( TOTAL_KEYWORD_MATCHED * 100 / TOTAL_KEYWORD_EXPECTED ))
  [ $TOTAL -gt 0 ] && avg_ms=$(( TOTAL_TIME / TOTAL ))

  local ci_rate=0 tool_rate=0
  [ $TOTAL -gt 0 ] && ci_rate=$(( TOTAL_CITATION_CASES * 100 / TOTAL ))
  [ $TOTAL -gt 0 ] && tool_rate=$(( TOTAL_TOOL_CASES * 100 / TOTAL ))

  echo -e "${BLD}═══ 综合指标 ═══${NC}"
  printf "  %-22s ${GRN}%3d%%${NC}   (%d/%d)\n" "整体通过率" "$overall_pass_rate" "$PASSED" "$TOTAL"
  printf "  %-22s ${YLW}%3d%%${NC}   (命中 %d/%d 个关键词)\n" "平均关键词命中率" "$avg_kw_rate" "$TOTAL_KEYWORD_MATCHED" "$TOTAL_KEYWORD_EXPECTED"
  printf "  %-22s ${CYN}%3d%%${NC}   (%d/%d 用例)\n" "引用覆盖率" "$ci_rate" "$TOTAL_CITATION_CASES" "$TOTAL"
  printf "  %-22s ${CYN}%3d%%${NC}   (%d/%d 用例)\n" "工具调用率" "$tool_rate" "$TOTAL_TOOL_CASES" "$TOTAL"
  printf "  %-22s %4dms\n" "平均响应时间" "$avg_ms"

  # ─── 分部门明细 ───
  echo ""
  echo -e "${BLD}═══ 分部门指标 ═══${NC}"
  printf "  %-14s %6s %8s %10s %8s\n" "部门" "用例数" "通过率" "关键词命中率" "平均耗时"
  printf "  %-14s %6s %8s %10s %8s\n" "──────" "────" "──────" "──────────" "──────"

  local all_depts=("HR" "IT" "Finance" "Invoice" "OA" "MKT" "RD" "STR" "EDGE" "CHART" "X")
  for dept in "${all_depts[@]}"; do
    local dt=${DEPT_TOTAL["$dept"]:-0}
    if [ "$dt" -eq 0 ]; then continue; fi
    local dp=${DEPT_PASSED["$dept"]:-0}
    local dkr=0 dke=${DEPT_KW_EXP["$dept"]:-0} dkm=${DEPT_KW_MATCH["$dept"]:-0}
    [ "$dke" -gt 0 ] && dkr=$(( dkm * 100 / dke ))
    local dpr=0 dtm=${DEPT_TIME["$dept"]:-0}
    [ "$dt" -gt 0 ] && dpr=$(( dp * 100 / dt ))
    local davg=0
    [ "$dt" -gt 0 ] && davg=$(( dtm / dt ))
    printf "  ${BLD}%-14s${NC} %6d %7d%% %9d%% %7dms\n" "$dept" "$dt" "$dpr" "$dkr" "$davg"
  done

  # ─── 评级 ───
  echo ""
  local grade=""
  if [ $overall_pass_rate -ge 90 ] && [ $avg_kw_rate -ge 80 ]; then
    grade="${GRN}🏆 A — 优秀${NC}"
  elif [ $overall_pass_rate -ge 75 ] && [ $avg_kw_rate -ge 60 ]; then
    grade="${YLW}👍 B — 良好${NC}"
  elif [ $overall_pass_rate -ge 50 ]; then
    grade="${YLW}⚠️  C — 有待改进${NC}"
  else
    grade="${RED}❌ D — 需要排查${NC}"
  fi
  echo -e "  综合评级: $grade"
  echo -e "\n  ${CYN}SSE 日志: $LOG_DIR/${NC}"
}

# ════════════════════════════════════════════════════════════
#  Main
# ════════════════════════════════════════════════════════════
echo -e "${CYN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYN}║   RAGStudio 质量评估套件                     ║${NC}"
echo -e "${CYN}║   指标: 通过率 | 关键词命中率 | 引用/工具率    ║${NC}"
echo -e "${CYN}╚══════════════════════════════════════════════╝${NC}"
echo ""
echo "▶ 登录..."
login
echo -e "  ${GRN}✓${NC} TOKEN 获取成功"
echo ""

# 即使 Ctrl+C 也打印报告
trap 'report; exit 130' INT

# ════════════════════════════════════════════════════════════
#  测试用例
#  格式: evaluate <编号> <部门> <标签> "问题" "KB_ID" <thinking> "关键词1,关键词2,..."
# ════════════════════════════════════════════════════════════

# ── HR ──
evaluate 1  HR   "年假天数"    "公司年假有多少天？"           "$ALL_KB"  0  "年假,年,天,入职满"
evaluate 2  HR   "绩效考核"    "绩效考核的周期是多久？"        "$ALL_KB"  0  "季度,年度,考核周期,S级"
evaluate 3  HR   "招聘流程"    "招聘流程有几轮面试？"          "$ALL_KB"  0  "面试,轮,简历,录用"
evaluate 4  HR   "保密协议"    "保密协议的违约责任是什么？"     "$ALL_KB"  0  "保密,泄密,连续旷工,解除合同"
evaluate 5  HR   "缺勤处罚"    "旷工几天会被开除？"            "$ALL_KB"  0  "旷工,连续,3天,解除合同"
evaluate 6  HR   "培训计划"    "新员工入职培训包含哪些内容？"    "$ALL_KB"  0  "入职,培训,企业文化,试用期"
evaluate 7  HR   "薪资结构"    "公司的薪资结构由哪些部分组成？"   "$ALL_KB"  0  "基本工资,绩效奖金,津贴,年终奖"
evaluate 8  HR   "资产领用"    "员工领用公司资产需要什么流程？"   "$ALL_KB"  0  "资产,领用,领用单,部门负责人"

# ── IT ──
evaluate 9  IT   "数据库规范"  "公司数据库的命名规范是什么？"    "$ALL_KB"  0  "命名规范,小写,下划线,必须字段"
evaluate 10 IT   "网络安全"    "公司网络安全策略包含哪些方面？"  "$ALL_KB"  0  "数据加密,密码,敏感,绝密"
evaluate 11 IT   "权限管理"    "访问控制的权限分级是怎样的？"    "$ALL_KB"  0  "最小权限,职责分离,敏感权限,密码策略"
evaluate 12 IT   "部署流程"    "新服务的部署流程有几个阶段？"    "$ALL_KB"  0  "Staging,生产,审批,CI/CD"
evaluate 13 IT   "安全事件"    "安全事件响应流程的第一步是什么？"  "$ALL_KB"  0  "发现,报告,立即,应急响应"
evaluate 14 IT   "技术支持"    "IT支持的响应时间承诺是多少？"     "$ALL_KB"  0  "响应,IT服务台,8888,工单"

# ── Finance ──
evaluate 15 Finance "费用报销"  "差旅费用报销的流程是什么？"      "$ALL_KB" 0 "差旅,报销,发票,审批"
evaluate 16 Finance "预算管理"  "年度预算编制的起始时间是什么？"   "$ALL_KB" 0 "预算,编制,9月,部门"
evaluate 17 Finance "合同审批"  "合同审批需要几个层级？"           "$ALL_KB" 0 "合同,审批,层级,权限"
evaluate 18 Finance "印章管理"  "公司印章有哪些种类？"             "$ALL_KB" 0 "印章,公章,合同章,财务章"
evaluate 19 Finance "税务申报"  "增值税的申报周期是多久？"         "$ALL_KB" 0 "增值税,申报,月度,15日"

# ── OA ──
evaluate 20 OA     "系统使用"  "OA系统的主要功能模块有哪些？"     "$ALL_KB"   0 "审批,公文,会议,考勤"
evaluate 21 OA     "数据安全"  "OA系统的数据安全等级要求是什么？"  "$ALL_KB"   0 "加密,传输,存储,访问控制"

# ── Invoice ──
evaluate 22 Invoice "开票信息"  "公司开票信息包含哪些内容？"       "$ALL_KB" 0 "公司名称,税号,开户行,账号"

# ── Market ──
evaluate 23 MKT    "营收目标"   "2026年的年营收目标是多少？"         "$ALL_KB" 0 "5200万,营收目标,85.7"
evaluate 24 MKT    "预算分配"   "2026年营销总预算中人力成本占多少？"  "$ALL_KB" 0 "32.5,人力成本,2000万"
evaluate 25 MKT    "竞品分析"   "竞品A的产品优势有哪些？"             "$ALL_KB" 0 "竞品A,品牌认知,私有化部署,最早进入市场"
evaluate 26 MKT    "品牌传播"   "2026年品牌传播的关键节点有哪些？"    "$ALL_KB" 0 "AI EXPO,多模态RAG,开源Agent,客户大会"
evaluate 27 MKT    "数字营销"   "SEO优化中标题标签的规范字数？"       "$ALL_KB" 0 "标题标签,50,60,字符"
evaluate 28 MKT    "客户画像"   "核心客户的行业分布中金融占比？"      "$ALL_KB" 0 "金融,30%,25%,制造"
evaluate 29 MKT    "价格策略"   "专业版产品月费是多少？"               "$ALL_KB" 0 "25,000,专业版,月费"
evaluate 30 MKT    "危机公关"   "红色级别危机响应时间？"               "$ALL_KB" 0 "红色,1小时,危机,CEO"

# ── RD ──
evaluate 31 RD     "代码规范"   "后端开发中类名的命名规范是什么？"    "$ALL_KB"  0 "UpperCamelCase,驼峰,类名"
evaluate 32 RD     "异常处理"   "BusinessException和SystemException的区别？" "$ALL_KB" 0 "BusinessException,业务逻辑,SystemException,系统级"
evaluate 33 RD     "系统架构"   "RAG Core Engine有哪些核心组件？"     "$ALL_KB"  0 "AgentLoop,RetrievalEngine,Memory,Skill"
evaluate 34 RD     "部署架构"   "生产环境的节点配置是什么？"           "$ALL_KB"  0 "生产,5节点,16C64G"
evaluate 35 RD     "安全规范"   "API Key在日志中应该如何处理？"        "$ALL_KB"  0 "API Key,日志,屏蔽,敏感"
evaluate 36 RD     "Git流程"    "feature分支的合并规则是什么？"        "$ALL_KB"  0 "feature,develop,Code Review,CI"
evaluate 37 RD     "性能目标"   "首Token延迟的P50目标是多少？"        "$ALL_KB"  0 "首Token,2s,P50,延迟"
evaluate 38 RD     "API认证"    "API认证使用什么框架？"                "$ALL_KB"  0 "Sa-Token,Bearer Token"

# ── Strategy (PDF+Word 含图表) ──
evaluate 39 STR    "营收趋势"   "2026年上半年营收同比增长了多少？"     "$ALL_KB" 0 "同比增长,55%,74%,85%"
evaluate 40 STR    "市场份额"   "我方在AI知识管理市场中的份额？"       "$ALL_KB" 0 "12%,份额"
evaluate 41 STR    "战略阶段"   "2026年战略路线图包含哪些阶段？"       "$ALL_KB" 0 "市场验证,产品打磨,规模扩张,生态建设,国际化"
evaluate 42 STR    "投资分配"   "AI模型研发的预算金额是多少？"         "$ALL_KB" 0 "4500,AI模型,22.5"
evaluate 43 STR    "SaaS占比"  "SaaS订阅收入占营收结构比例？"          "$ALL_KB" 0 "42%,SaaS,订阅"
evaluate 44 STR    "PEST分析"  "政策环境提到的规划文件名称？"           "$ALL_KB" 0 "新一代人工智能,规划,2026-2030"
evaluate 45 STR    "竞争格局"   "竞品B的核心优势是什么？"               "$ALL_KB" 0 "开源,社区,MCP,竞品B"
evaluate 46 STR    "战略投资"   "2026年度战略投资总规模是多少？"        "$ALL_KB" 0 "2亿,20,000,投资"
evaluate 47 STR    "盈亏平衡"   "预计哪一年实现盈亏平衡？"              "$ALL_KB" 0 "2027,盈亏平衡"
evaluate 48 STR    "客户数量"   "2026年H1新增付费客户数？"              "$ALL_KB" 0 "85家,35家,50家"
evaluate 49 STR    "人才流失"   "核心AI人才年流失率目标？"              "$ALL_KB" 0 "10%,流失"
evaluate 50 STR    "市场验证"   "市场验证阶段的起止时间是什么？"        "$ALL_KB" 0 "市场验证,2026-01,2026-03"

# ── Cross-department ──
evaluate 51 X      "IT+HR联合"  "公司IT和HR的资产管理制度有什么关联？" "$ALL_KB"    0 ""
evaluate 52 X      "财务+OA"    "财务报销在OA系统中如何操作？"           "$ALL_KB" 0 ""
evaluate 53 X      "研发+战略"  "研发预算和战略投资的关系？"             "$ALL_KB"  0 ""
evaluate 54 X      "市场+战略"  "Q1营收和市场部预算是否对应？"           "$ALL_KB" 0 ""

# ── 图表专项 (验证 PDF/Word 图表提取效果) ──
evaluate 55 CHART  "趋势图"     "2026年Q4的营收预测是多少？"          "$ALL_KB" 0 "1700,Q4,预测"
evaluate 56 CHART  "饼图"       "私有化部署收入占营收百分比？"         "$ALL_KB" 0 "28%,私有化"
evaluate 57 CHART  "柱状图"     "竞品A的市场份额变化？"               "$ALL_KB" 0 "竞品A,26,下降"
evaluate 58 CHART  "甘特图"     "规模扩张阶段的起止时间？"             "$ALL_KB" 0 "2026-06,2026-09,规模扩张"
evaluate 59 CHART  "仪表盘"     "Q3客户满意度NPS是多少？"              "$ALL_KB" 0 "88,NPS,满意度"
evaluate 60 CHART  "折线图"     "2026年4月营收相较2025年4月增长？"      "$ALL_KB" 0 ""

# ── 边界测试 ──
evaluate 61 EDGE   "空KB"       "你好，今天天气怎么样？"               ""         0 "天气,公网"
evaluate 62 EDGE   "数字精确"   "客户续约率的目标是多少？"             "$ALL_KB"  0 "85%,续约"
evaluate 63 EDGE   "表格解析"   "竞品C的威胁等级是什么？"             "$ALL_KB"  0 "中,竞品C"
evaluate 64 EDGE   "长文档"     "A/B测试的样本量要求是多少？"          "$ALL_KB"  0 "1000,样本"
evaluate 65 EDGE   "多部门"     "AI模型研发和市场营销总预算？"          "$ALL_KB" 0 ""

# ════════════════════════════════════════════════════════════
report
