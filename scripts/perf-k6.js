// k6 首 Token / SSE 压测（本机开发基线，非上线验收）。
// 需要后端已启动、模型可走 mock 或真实 Provider、租户限流足够高。
// 运行（mock/本地模型）：PERF_MODEL=perf-k6-mock k6 run scripts/perf-k6.js
// 真实模型必须显式 PERF_ALLOW_REAL=1，脚本默认拒绝，避免误用真实账号额度。
// 可用环境变量：PERF_BASE_URL / PERF_USERNAME / PERF_PASSWORD / PERF_TENANT /
//               PERF_RATE / PERF_DURATION / PERF_PRE_VUS / PERF_MAX_VUS /
//               PERF_VUS / PERF_THINK_SECONDS / PERF_MODEL / PERF_KB_ID /
//               PERF_MESSAGE / PERF_WARN_WORD / PERF_RECYCLE_EVERY /
//               PERF_TOKEN_REFRESH_SECONDS / PERF_ALLOW_REAL
// 注意：真实模型默认拒绝运行；必须显式设置 PERF_ALLOW_REAL=1，避免误用真实账号额度。
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend, Rate } from 'k6/metrics'

const BASE = __ENV.PERF_BASE_URL || 'http://localhost:8080'
const USERNAME = __ENV.PERF_USERNAME || 'dev'
const PASSWORD = __ENV.PERF_PASSWORD || 'Dev@123456'
const TENANT = __ENV.PERF_TENANT || 'dev'
const RATE = Number(__ENV.PERF_RATE || 3)
const DURATION = __ENV.PERF_DURATION || '60s'
const PRE_VUS = Number(__ENV.PERF_PRE_VUS || 10)
const MAX_VUS = Number(__ENV.PERF_MAX_VUS || 30)
const MODEL = __ENV.PERF_MODEL || 'deepseek-flash'
const KB_ID = __ENV.PERF_KB_ID || ''
const MESSAGE = __ENV.PERF_MESSAGE || '请简短回答：压测消息'
const WARN_WORD = __ENV.PERF_WARN_WORD || ''
const VUS = Number(__ENV.PERF_VUS || 0)
const THINK_SECONDS = Number(__ENV.PERF_THINK_SECONDS || 1)
const RECYCLE_EVERY = Number(__ENV.PERF_RECYCLE_EVERY || 0)
const TOKEN_REFRESH_MS = Math.max(60, Number(__ENV.PERF_TOKEN_REFRESH_SECONDS || 600)) * 1000
const ALLOW_REAL = __ENV.PERF_ALLOW_REAL === '1'
const REAL_MODEL = !String(MODEL).startsWith('/') && !/(mock|test|perf|local)/i.test(String(MODEL))
if (REAL_MODEL && !ALLOW_REAL) {
  throw new Error(`拒绝压测真实模型 ${MODEL}：如确认会消耗真实账号额度，请设置 PERF_ALLOW_REAL=1`)
}

const chatTtfb = new Trend('chat_ttfb_ms', true)
const chatTotal = new Trend('chat_total_ms', true)
const chatOk = new Rate('chat_ok')
const ragCitation = new Rate('rag_citation')
const moderationWarn = new Rate('moderation_warn')

const scenario = VUS > 0
  ? { executor: 'constant-vus', exec: 'chat', vus: VUS, duration: DURATION }
  : { executor: 'constant-arrival-rate', exec: 'chat', rate: RATE, timeUnit: '1s',
      duration: DURATION, preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS }

export const options = {
  discardResponseBodies: false,
  scenarios: { chat_stream: scenario },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'http_req_duration{endpoint:chat_stream}': ['p(95)<5000'],
    'chat_ttfb_ms': ['p(95)<1500'],
    'chat_ok': ['rate>0.99'],
    ...(KB_ID ? { rag_citation: ['rate>0.99'] } : {}),
    ...(WARN_WORD ? { moderation_warn: ['rate>0.99'] } : {}),
  },
}

function authHeaders(token) {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
    'Accept-Language': 'zh-CN',
  }
}

function loginToken(tag = 'login') {
  const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    username: USERNAME, password: PASSWORD, tenantKey: TENANT,
  }), { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: tag } })
  if (login.status !== 200) throw new Error(`login failed: ${login.status} ${login.body}`)
  const token = login.json('data.accessToken')
  if (!token) throw new Error(`login token missing: ${login.body}`)
  return token
}

export function setup() {
  const token = loginToken()

  const name = `PERF K6 ${Date.now()}`
  const created = http.post(`${BASE}/api/v1/agent`, JSON.stringify({
    name,
    description: 'k6 SSE 压测专用 Agent',
    systemPrompt: '你是压测助手，请用一句话简洁回答。',
    modelConfig: { modelName: MODEL, temperature: 0.2 },
    agentCapabilities: { functionCall: false },
    tools: [],
    knowledgeBases: KB_ID ? [{ knowledgeBaseId: Number(KB_ID), enabled: true }] : [],
    visibility: 'PRIVATE',
  }), { headers: authHeaders(token), tags: { endpoint: 'setup_agent' } })
  if (created.status !== 200 || created.json('code') !== 0) {
    throw new Error(`create agent failed: ${created.status} ${created.body}`)
  }
  const agentId = created.json('data.id')
  const published = http.post(`${BASE}/api/v1/agent/${agentId}/publish`, JSON.stringify({ force: true }),
    { headers: authHeaders(token), tags: { endpoint: 'setup_publish' } })
  if (published.status !== 200 || published.json('code') !== 0) {
    throw new Error(`publish agent failed: ${published.status} ${published.body}`)
  }
  return { token, agentId, name, lastLogin: Date.now() }
}

const conversations = {}
const conversationUse = {}

function ensureToken(data) {
  if (!data.lastLogin || Date.now() - data.lastLogin >= TOKEN_REFRESH_MS) {
    data.token = loginToken('login_refresh')
    data.lastLogin = Date.now()
  }
}

function conversationId(data) {
  const used = conversationUse[__VU] || 0
  if (!conversations[__VU] || (RECYCLE_EVERY > 0 && used >= RECYCLE_EVERY)) {
    const res = http.post(`${BASE}/api/v1/conversations`, JSON.stringify({
      agentId: data.agentId,
      title: `k6-${data.name}-${__VU}-${Date.now()}`,
    }), { headers: authHeaders(data.token), tags: { endpoint: 'conversation_create' } })
    if (res.status !== 200 || res.json('code') !== 0) {
      throw new Error(`create conversation failed: ${res.status} ${res.body}`)
    }
    conversations[__VU] = res.json('data.id')
    conversationUse[__VU] = 0
  }
  conversationUse[__VU] = (conversationUse[__VU] || 0) + 1
  return conversations[__VU]
}

export function chat(data) {
  ensureToken(data)
  const convId = conversationId(data)
  let res = http.post(`${BASE}/api/v1/conversations/${convId}/messages`, JSON.stringify({
    message: MESSAGE,
    stream: true,
  }), {
    headers: { ...authHeaders(data.token), Accept: 'text/event-stream' },
    timeout: '60s',
    tags: { endpoint: 'chat_stream' },
  })
  if (res.status === 401) {
    data.token = loginToken('login_retry')
    data.lastLogin = Date.now()
    res = http.post(`${BASE}/api/v1/conversations/${convId}/messages`, JSON.stringify({
      message: MESSAGE,
      stream: true,
    }), {
      headers: { ...authHeaders(data.token), Accept: 'text/event-stream' },
      timeout: '60s',
      tags: { endpoint: 'chat_stream' },
    })
  }

  const body = String(res.body || '')
  const done = /event:\s*done/.test(body)
  const citation = /event:\s*citation/.test(body)
  const moderation = /event:\s*moderation/.test(body)
  if (KB_ID) ragCitation.add(citation)
  if (WARN_WORD) moderationWarn.add(moderation)
  const ok = res.status === 200 && done
  chatOk.add(ok)
  chatTtfb.add(res.timings.waiting)
  chatTotal.add(res.timings.duration)
  const resultChecks = {
    'chat status 200': (r) => r.status === 200,
    'chat done event': () => done,
    'chat content-type sse': (r) => String(r.headers['Content-Type'] || '').includes('text/event-stream'),
  }
  if (KB_ID) resultChecks['rag citation event'] = () => citation
  if (WARN_WORD) resultChecks['moderation event'] = () => moderation && body.includes(WARN_WORD)
  check(res, resultChecks)
  if (VUS > 0) sleep(Math.max(0, THINK_SECONDS))
}

export function teardown(data) {
  if (!data || !data.agentId) return
  http.del(`${BASE}/api/v1/agent/${data.agentId}`, null, {
    headers: authHeaders(data.token),
    tags: { endpoint: 'teardown_agent' },
  })
}

export function handleSummary(data) {
  const metric = (name, key) => {
    const values = data.metrics[name] && data.metrics[name].values
    return values && values[key] !== undefined ? values[key] : 'n/a'
  }
  const summary = [
    VUS > 0 ? `k6 SSE concurrency: vus=${VUS} think=${THINK_SECONDS}s duration=${DURATION}` : `k6 SSE baseline: rate=${RATE}/s duration=${DURATION}`,
    `chat_ttfb p95=${metric('chat_ttfb_ms', 'p(95)')}ms avg=${metric('chat_ttfb_ms', 'avg')}ms`,
    `chat_total p95=${metric('chat_total_ms', 'p(95)')}ms avg=${metric('chat_total_ms', 'avg')}ms`,
    `chat_ok rate=${metric('chat_ok', 'rate')}`,
    `http_req_failed rate=${metric('http_req_failed', 'rate')}`,
    `http_reqs count=${metric('http_reqs', 'count')}`,
    ...(KB_ID ? [`rag_citation rate=${metric('rag_citation', 'rate')}`] : []),
    ...(WARN_WORD ? [`moderation_warn rate=${metric('moderation_warn', 'rate')}`] : []),
  ].join('\n')
  return {
    stdout: summary + '\n',
    '/tmp/k6-summary.json': JSON.stringify(data, null, 2),
  }
}
