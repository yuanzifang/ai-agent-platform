#!/bin/bash
# M6 安全合规验收：越权访问矩阵 + 限流 + 审计（需求 3.22）
BASE=http://localhost:8080/api/v1
# 清理上一轮限流计数，保证可重复执行
redis-cli --scan --pattern 'ratelimit:*' 2>/dev/null | xargs -r redis-cli DEL >/dev/null 2>&1
PASS=0; FAIL=0
check() { # name expected_status actual_status
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "PASS | $1 => $3"
  else FAIL=$((FAIL+1)); echo "FAIL | $1 => 期望 $2 实际 $3"; fi
}
code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }

# 三方身份：dev(OWNER@dev)、zhangsan(MEMBER@dev)、acme-owner(OWNER@acme)
DEV=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' -d '{"username":"dev","password":"Dev@123456","tenantKey":"dev"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
ZS=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' -d '{"username":"zhangsan","password":"Member@123","tenantKey":"dev"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
ACME=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' -d '{"username":"acme-owner","password":"Owner@123456","tenantKey":"acme"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

echo "== 越权矩阵 =="
check "匿名访问 Agent 列表（401）" 401 "$(code $BASE/agent)"
check "zhangsan 读他人会话（403 归属校验）" 403 "$(code -H "Authorization: Bearer $ZS" $BASE/conversations/1)"
check "zhangsan 读同租户 Agent（200 允许）" 200 "$(code -H "Authorization: Bearer $ZS" $BASE/agent/1)"
check "acme 跨租户读 dev Agent（404 插件过滤）" 404 "$(code -H "Authorization: Bearer $ACME" $BASE/agent/1)"
check "acme 跨租户改 dev Agent（404）" 404 "$(code -X PUT -H "Authorization: Bearer $ACME" -H 'Content-Type: application/json' -H 'If-Match: 2030-01-01T00:00:00' -d '{"name":"hack"}' $BASE/agent/1)"
check "acme 跨租户读知识库（404）" 404 "$(code -H "Authorization: Bearer $ACME" $BASE/knowledge-base/1/documents)"
C=$(code -H "Authorization: Bearer $ACME" $BASE/conversations/1)
if [ "$C" = "403" ] || [ "$C" = "404" ]; then PASS=$((PASS+1)); echo "PASS | acme 跨租户读会话（403/404，插件过滤不泄露存在性） => $C"
else FAIL=$((FAIL+1)); echo "FAIL | acme 跨租户读会话 => 期望 403/404 实际 $C"; fi
check "zhangsan 调平台管理接口（403）" 403 "$(code -H "Authorization: Bearer $ZS" $BASE/platform/tenants)"
check "dev 伪造 X-Tenant-Id=2（403）" 403 "$(code -H "Authorization: Bearer $DEV" -H "X-Tenant-Id: 2" $BASE/agent)"
check "伪造 token（401）" 401 "$(code -H "Authorization: Bearer fake.token.here" $BASE/agent)"

echo "== 限流（RATE_LIMIT=60/min，快速打满） =="
LIMITED=0
for i in $(seq 1 70); do
  C=$(code -H "Authorization: Bearer $ZS" $BASE/conversations)
  [ "$C" = "429" ] && LIMITED=1 && break
done
check "租户限流触发 429" 1 "$LIMITED"

echo
echo "结果：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ]
