import http from 'node:http'
import crypto from 'node:crypto'
import { Readable } from 'node:stream'

/**
 * Agent 运行时网关（零依赖）：
 * - 对外暴露 OpenAI 兼容接口（/v1/models、/v1/chat/completions，支持 SSE 透传）
 * - Bearer 鉴权：默认 RUNTIME_TOKEN；可选 CALLER_KEYS 多调用方 Key（每个可带日配额）
 * - 按 Key 的分钟级限流 + 日配额 + 内存用量统计（/stats）
 * - CORS、健康检查、请求日志（不含密钥）
 */

const PORT = Number(process.env.PORT || 8080)
const PLATFORM = (process.env.PLATFORM_BASE || 'http://host.docker.internal:8080').replace(/\/+$/, '')
const API_KEY = process.env.API_KEY || ''
const AGENT_SLUG = process.env.AGENT_SLUG || 'agent'
const RUNTIME_TOKEN = process.env.RUNTIME_TOKEN || ''
const STATS_TOKEN = process.env.STATS_TOKEN || RUNTIME_TOKEN
const CORS_ORIGIN = process.env.CORS_ORIGIN || '*'
const RATE_LIMIT_PER_MIN = Math.max(0, Number(process.env.RATE_LIMIT_PER_MIN || 60))
const RATE_BURST = Math.max(0, Number(process.env.RATE_BURST || 20))
const CALLER_QUOTA_DAILY = Math.max(0, Number(process.env.CALLER_QUOTA_DAILY || 0))
const MAX_BODY = 2 * 1024 * 1024

const hash = (value) => crypto.createHash('sha256').update(value).digest('hex')
const safeEqual = (a, b) => {
  const left = Buffer.from(String(a))
  const right = Buffer.from(String(b))
  return left.length === right.length && crypto.timingSafeEqual(left, right)
}

/** CALLER_KEYS_JSON：[{hash, quota, name}]（平台注入，容器内不保存明文） */
function parseCallerKeysJson(raw) {
  const map = new Map()
  if (!raw) return map
  try {
    for (const row of JSON.parse(raw)) {
      const keyHash = String(row?.hash || '').toLowerCase()
      if (!keyHash) continue
      map.set(keyHash, {
        id: keyHash.slice(0, 12),
        name: String(row.name || `key-${keyHash.slice(0, 6)}`),
        quota: Number(row.quota || 0) || 0
      })
    }
  } catch (error) {
    console.error('invalid CALLER_KEYS_JSON:', error.message)
  }
  return map
}

/** 兼容旧格式 CALLER_KEYS：key1:日配额,key2:0（0=不限） */
function parseLegacyCallerKeys(raw) {
  const map = new Map()
  if (!raw) return map
  for (const part of raw.split(',')) {
    const [key, quota] = part.trim().split(':')
    if (!key) continue
    const keyHash = hash(key)
    map.set(keyHash, {
      id: keyHash.slice(0, 12),
      name: `key-${keyHash.slice(0, 6)}`,
      quota: Number(quota || CALLER_QUOTA_DAILY) || 0
    })
  }
  return map
}

const CALLER_KEYS = parseCallerKeysJson(process.env.CALLER_KEYS_JSON)
for (const [keyHash, entry] of parseLegacyCallerKeys(process.env.CALLER_KEYS)) {
  if (!CALLER_KEYS.has(keyHash)) CALLER_KEYS.set(keyHash, entry)
}

const usage = new Map()   // callerId -> { day, count, streams }
const buckets = new Map() // callerId -> { tokens, last }

const day = () => new Date().toISOString().slice(0, 10)

function bearer(req) {
  const auth = String(req.headers['authorization'] || '')
  return auth.toLowerCase().startsWith('bearer ') ? auth.slice(7).trim() : ''
}

function resolveCaller(req) {
  const token = bearer(req)
  if (!token) return null
  if (RUNTIME_TOKEN && safeEqual(token, RUNTIME_TOKEN)) {
    return { id: 'default', label: 'deployment token', quota: CALLER_QUOTA_DAILY }
  }
  const entry = CALLER_KEYS.get(hash(token))
  return entry ? { ...entry, label: entry.name } : null
}

function usageOf(caller) {
  let row = usage.get(caller.id)
  if (!row || row.day !== day()) {
    row = { day: day(), count: 0, streams: 0 }
    usage.set(caller.id, row)
  }
  row.name = caller.label
  return row
}

/** 令牌桶：容量 burst+1，按 RATE_LIMIT_PER_MIN 匀速回填。 */
function allowRequest(callerId) {
  if (RATE_LIMIT_PER_MIN <= 0) return true
  const now = Date.now()
  const capacity = RATE_BURST + 1
  const refillPerMs = RATE_LIMIT_PER_MIN / 60000
  let bucket = buckets.get(callerId)
  if (!bucket) {
    bucket = { tokens: capacity, last: now }
    buckets.set(callerId, bucket)
  }
  bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.last) * refillPerMs)
  bucket.last = now
  if (bucket.tokens < 1) return false
  bucket.tokens -= 1
  return true
}

const server = http.createServer(async (req, res) => {
  const startedAt = Date.now()
  const context = { caller: '-', stream: false }
  const cors = {
    'Access-Control-Allow-Origin': CORS_ORIGIN,
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Requested-With',
    'Access-Control-Max-Age': '600'
  }
  const sendJson = (status, payload, extra = {}) => {
    res.writeHead(status, { ...cors, 'Content-Type': 'application/json', ...extra })
    res.end(JSON.stringify(payload))
  }
  res.on('finish', () => {
    console.log(JSON.stringify({
      ts: new Date().toISOString(),
      caller: context.caller,
      method: req.method,
      path: (req.url || '').split('?')[0],
      status: res.statusCode,
      ms: Date.now() - startedAt,
      stream: context.stream
    }))
  })

  const url = new URL(req.url || '/', 'http://localhost')

  if (req.method === 'OPTIONS') {
    res.writeHead(204, cors)
    res.end()
    return
  }
  if (url.pathname === '/healthz') {
    res.writeHead(200, { ...cors, 'Content-Type': 'text/plain' })
    res.end('ok')
    return
  }
  if (url.pathname === '/stats') {
    const token = bearer(req)
    if (!STATS_TOKEN || !token || !safeEqual(token, STATS_TOKEN)) {
      sendJson(401, { error: { message: 'invalid or missing bearer token', type: 'invalid_request_error' } })
      return
    }
    const callers = [...usage.entries()]
      .filter(([, row]) => row.day === day())
      .map(([id, row]) => ({
        caller: id,
        name: id === 'default' ? 'deployment token' : (row.name || id),
        requests: row.count,
        streams: row.streams
      }))
    sendJson(200, {
      day: day(),
      rateLimitPerMin: RATE_LIMIT_PER_MIN,
      burst: RATE_BURST,
      callers
    })
    return
  }
  if (!url.pathname.startsWith('/v1/')) {
    sendJson(404, { error: { message: 'not found', type: 'invalid_request_error' } })
    return
  }

  const caller = resolveCaller(req)
  if (!caller) {
    sendJson(401, { error: { message: 'invalid or missing bearer token', type: 'invalid_request_error' } },
      { 'WWW-Authenticate': 'Bearer' })
    return
  }
  context.caller = caller.label

  if (!allowRequest(caller.id)) {
    sendJson(429, { error: { message: 'rate limit exceeded', type: 'rate_limit_error' } }, { 'Retry-After': '1' })
    return
  }
  const row = usageOf(caller)
  if (caller.quota > 0 && row.count >= caller.quota) {
    sendJson(429, { error: { message: `daily quota exceeded (${caller.quota})`, type: 'quota_exceeded' } })
    return
  }
  row.count += 1
  if ((req.headers.accept || '').includes('text/event-stream')) row.streams += 1

  if (url.pathname === '/v1/models' && req.method === 'GET') {
    sendJson(200, {
      object: 'list',
      data: [{ id: AGENT_SLUG, object: 'model', created: Math.floor(Date.now() / 1000), owned_by: 'ai-agent-platform' }]
    })
    return
  }
  if (url.pathname === '/v1/chat/completions' && req.method === 'POST') {
    await proxyChat(req, res, cors, context, sendJson)
    return
  }
  sendJson(404, { error: { message: 'not found', type: 'invalid_request_error' } })
})

async function proxyChat(req, res, cors, context, sendJson) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > MAX_BODY) {
      sendJson(413, { error: { message: 'request body too large', type: 'invalid_request_error' } })
      return
    }
    chunks.push(chunk)
  }
  const requestId = String(req.headers['x-request-id'] || crypto.randomUUID())
  let upstream
  try {
    upstream = await fetch(`${PLATFORM}/api/v1/open/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': req.headers['content-type'] || 'application/json',
        'X-Api-Key': API_KEY,
        'X-Request-Id': requestId
      },
      body: Buffer.concat(chunks)
    })
  } catch (error) {
    sendJson(502, { error: { message: 'upstream unavailable', type: 'upstream_error' } })
    return
  }
  const contentType = upstream.headers.get('content-type') || 'application/json'
  context.stream = contentType.includes('text/event-stream')
  res.writeHead(upstream.status, {
    ...cors,
    'Content-Type': contentType,
    'X-Request-Id': requestId,
    'Cache-Control': 'no-cache'
  })
  if (!upstream.body) {
    res.end()
    return
  }
  const body = Readable.fromWeb(upstream.body)
  body.on('error', () => res.destroy())
  body.pipe(res)
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(JSON.stringify({
    ts: new Date().toISOString(),
    msg: 'agent-gateway started',
    port: PORT,
    platform: PLATFORM,
    agent: AGENT_SLUG,
    rateLimitPerMin: RATE_LIMIT_PER_MIN,
    burst: RATE_BURST,
    callerKeys: CALLER_KEYS.size
  }))
})

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    server.close(() => process.exit(0))
    setTimeout(() => process.exit(1), 5000)
  })
}
