// k6 首 Token / SSE 压测（本机开发基线，非上线验收）。
// 需要后端已启动、模型可走 mock 或真实 Provider、租户限流足够高。
// 运行：k6 run scripts/perf-k6.js
// 可用环境变量：PERF_BASE_URL / PERF_USERNAME / PERF_PASSWORD / PERF_TENANT /
//               PERF_RATE / PERF_DURATION / PERF_PRE_VUS / PERF_MAX_VUS
import http from 'k6/http'
import { check } from 'k6'
import { Trend, Rate } from 'k6/metrics'

const BASE = __ENV.PERF_BASE_URL || 'http://localhost:8080'
const USERNAME = __ENV.PERF_USERNAME || 'dev'
const PASSWORD = __ENV.PERF_PASSWORD || 'Dev@123456'
const TENANT = __ENV.PERF_TENANT || 'dev'
const RATE = Number(__ENV.PERF_RATE || 3)
const DURATION = __ENV.PERF_DURATION || '60s'
const PRE_VUS = Number(__ENV.PERF_PRE_VUS || 10)
const MAX_VUS = Number(__ENV.PERF_MAX_VUS || 30)

const chatTtfb = new Trend('chat_ttfb_ms', true)
const chatTotal = new Trend('chat_total_ms', true)
const chatOk = new Rate('chat_ok')

export const options = {
  discardResponseBodies: false,
  scenarios: {
    chat_stream: {
      executor: 'constant-arrival-rate',
      exec: 'chat',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'http_req_duration{endpoint:chat_stream}': ['p(95)<5000'],
    'chat_ttfb_ms': ['p(95)<1500'],
    'chat_ok': ['rate>0.99'],
  },
}

function authHeaders(token) {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
    'Accept-Language': 'zh-CN',
  }
}

export function setup() {
  const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    username: USERNAME, password: PASSWORD, tenantKey: TENANT,
  }), { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'login' } })
  if (login.status !== 200) throw new Error(`login failed: ${login.status} ${login.body}`)
  const token = login.json('data.accessToken')
  if (!token) throw new Error(`login token missing: ${login.body}`)

  const name = `PERF K6 ${Date.now()}`
  const created = http.post(`${BASE}/api/v1/agent`, JSON.stringify({
    name,
    description: 'k6 SSE 压测专用 Agent',
    systemPrompt: '你是压测助手，请用一句话简洁回答。',
    modelConfig: { modelName: 'deepseek-flash', temperature: 0.2 },
    agentCapabilities: { functionCall: false },
    tools: [],
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
  return { token, agentId, name }
}

const conversations = {}

function conversationId(data) {
  if (conversations[__VU]) return conversations[__VU]
  const res = http.post(`${BASE}/api/v1/conversations`, JSON.stringify({
    agentId: data.agentId,
    title: `k6-${data.name}-${__VU}`,
  }), { headers: authHeaders(data.token), tags: { endpoint: 'conversation_create' } })
  if (res.status !== 200 || res.json('code') !== 0) {
    throw new Error(`create conversation failed: ${res.status} ${res.body}`)
  }
  conversations[__VU] = res.json('data.id')
  return conversations[__VU]
}

export function chat(data) {
  const convId = conversationId(data)
  const res = http.post(`${BASE}/api/v1/conversations/${convId}/messages`, JSON.stringify({
    message: '请简短回答：压测消息',
    stream: true,
  }), {
    headers: { ...authHeaders(data.token), Accept: 'text/event-stream' },
    timeout: '60s',
    tags: { endpoint: 'chat_stream' },
  })

  const body = String(res.body || '')
  const done = /event:\s*done/.test(body)
  const ok = res.status === 200 && done
  chatOk.add(ok)
  chatTtfb.add(res.timings.waiting)
  chatTotal.add(res.timings.duration)
  check(res, {
    'chat status 200': (r) => r.status === 200,
    'chat done event': () => done,
    'chat content-type sse': (r) => String(r.headers['Content-Type'] || '').includes('text/event-stream'),
  })
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
    `k6 SSE baseline: rate=${RATE}/s duration=${DURATION}`,
    `chat_ttfb p95=${metric('chat_ttfb_ms', 'p(95)')}ms avg=${metric('chat_ttfb_ms', 'avg')}ms`,
    `chat_total p95=${metric('chat_total_ms', 'p(95)')}ms avg=${metric('chat_total_ms', 'avg')}ms`,
    `chat_ok rate=${metric('chat_ok', 'rate')}`,
    `http_req_failed rate=${metric('http_req_failed', 'rate')}`,
    `http_reqs count=${metric('http_reqs', 'count')}`,
  ].join('\n')
  return {
    stdout: summary + '\n',
    '/tmp/k6-summary.json': JSON.stringify(data, null, 2),
  }
}
