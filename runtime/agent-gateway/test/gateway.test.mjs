import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import crypto from 'node:crypto'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const dirname = path.dirname(fileURLToPath(import.meta.url))
const serverPath = path.resolve(dirname, '../server.mjs')
const sha256 = (value) => crypto.createHash('sha256').update(value).digest('hex')

async function freePort() {
  return new Promise((resolve, reject) => {
    const socket = net.createServer()
    socket.once('error', reject)
    socket.listen(0, '127.0.0.1', () => {
      const port = socket.address().port
      socket.close(() => resolve(port))
    })
  })
}

async function waitForGateway(port, child, logs) {
  for (let i = 0; i < 80; i++) {
    if (child.exitCode != null) throw new Error(`gateway exited early\n${logs()}`)
    try {
      const res = await fetch(`http://127.0.0.1:${port}/healthz`, { signal: AbortSignal.timeout(500) })
      if (res.ok) return
    } catch { /* retry */ }
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`gateway did not become ready\n${logs()}`)
}

async function startGateway(t, extraEnv = {}) {
  const port = await freePort()
  let output = ''
  const child = spawn(process.execPath, [serverPath], {
    env: {
      ...process.env,
      PORT: String(port),
      PLATFORM_BASE: 'http://127.0.0.1:9',
      RUNTIME_TOKEN: 'test-token',
      RATE_LIMIT_PER_MIN: '0',
      ...extraEnv
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  child.stdout.on('data', chunk => { output += chunk })
  child.stderr.on('data', chunk => { output += chunk })
  t.after(() => child.kill('SIGTERM'))
  await waitForGateway(port, child, () => output)
  return { port, output: () => output }
}

async function request(port, urlPath, options = {}) {
  const res = await fetch(`http://127.0.0.1:${port}${urlPath}`, options)
  return { status: res.status, headers: res.headers, text: await res.text() }
}

test('健康检查、CORS、鉴权与模型列表', async (t) => {
  const gateway = await startGateway(t, { AGENT_SLUG: 'unit-agent', STATS_TOKEN: 'stats-token' })

  const health = await request(gateway.port, '/healthz')
  assert.equal(health.status, 200)
  assert.equal(health.text, 'ok')

  const preflight = await request(gateway.port, '/v1/models', { method: 'OPTIONS' })
  assert.equal(preflight.status, 204)
  assert.equal(preflight.headers.get('access-control-allow-origin'), '*')

  const unauthorized = await request(gateway.port, '/v1/models')
  assert.equal(unauthorized.status, 401)
  assert.match(unauthorized.text, /invalid or missing bearer token/)

  const models = await request(gateway.port, '/v1/models', {
    headers: { Authorization: 'Bearer test-token' }
  })
  assert.equal(models.status, 200)
  assert.equal(JSON.parse(models.text).data[0].id, 'unit-agent')

  const statsDenied = await request(gateway.port, '/stats')
  assert.equal(statsDenied.status, 401)
  const stats = await request(gateway.port, '/stats', {
    headers: { Authorization: 'Bearer stats-token' }
  })
  assert.equal(stats.status, 200)
  assert.equal(JSON.parse(stats.text).store, 'memory')
  assert.equal(JSON.parse(stats.text).callers[0].name, 'deployment token')
})

test('调用方 Key 鉴权、日配额与统计', async (t) => {
  const callerKey = 'caller-key-a'
  const gateway = await startGateway(t, {
    RUNTIME_TOKEN: '',
    STATS_TOKEN: 'stats-token',
    CALLER_KEYS_JSON: JSON.stringify([{ hash: sha256(callerKey), quota: 1, name: 'Key A' }])
  })

  const wrong = await request(gateway.port, '/v1/models', {
    headers: { Authorization: 'Bearer wrong-key' }
  })
  assert.equal(wrong.status, 401)

  const first = await request(gateway.port, '/v1/models', {
    headers: { Authorization: `Bearer ${callerKey}` }
  })
  assert.equal(first.status, 200)

  const second = await request(gateway.port, '/v1/models', {
    headers: { Authorization: `Bearer ${callerKey}` }
  })
  assert.equal(second.status, 429)
  assert.match(second.text, /daily quota exceeded/)

  const stats = await request(gateway.port, '/stats', {
    headers: { Authorization: 'Bearer stats-token' }
  })
  const body = JSON.parse(stats.text)
  assert.equal(body.callers[0].name, 'Key A')
  assert.equal(body.callers[0].requests, 1)
})

test('分钟级限流返回 429', async (t) => {
  const gateway = await startGateway(t, {
    RUNTIME_TOKEN: 'limited-token',
    RATE_LIMIT_PER_MIN: '1',
    RATE_BURST: '0'
  })

  const first = await request(gateway.port, '/v1/models', {
    headers: { Authorization: 'Bearer limited-token' }
  })
  const second = await request(gateway.port, '/v1/models', {
    headers: { Authorization: 'Bearer limited-token' }
  })
  assert.equal(first.status, 200)
  assert.equal(second.status, 429)
  assert.equal(second.headers.get('retry-after'), '1')
})

test('聊天代理透传 JSON 与 SSE，并携带上游鉴权', async (t) => {
  const received = []
  let upstreamPort
  const upstream = http.createServer((req, res) => {
    let body = ''
    req.on('data', chunk => { body += chunk })
    req.on('end', () => {
      received.push({ headers: req.headers, body })
      if (body.includes('"stream":true')) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream' })
        res.write('data: {"choices":[{"delta":{"content":"hi"}}]}\n\n')
        res.end('data: [DONE]\n\n')
      } else {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end('{"id":"upstream-json"}')
      }
    })
  })
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
  upstreamPort = upstream.address().port
  t.after(() => upstream.close())

  const gateway = await startGateway(t, {
    PLATFORM_BASE: `http://127.0.0.1:${upstreamPort}`,
    API_KEY: 'upstream-key',
    RUNTIME_TOKEN: 'proxy-token'
  })

  const json = await request(gateway.port, '/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: 'Bearer proxy-token',
      'Content-Type': 'application/json',
      'X-Request-Id': 'req-json'
    },
    body: JSON.stringify({ model: 'unit-agent', messages: [{ role: 'user', content: 'hi' }] })
  })
  assert.equal(json.status, 200)
  assert.equal(json.text, '{"id":"upstream-json"}')
  assert.equal(received[0].headers['x-api-key'], 'upstream-key')
  assert.equal(received[0].headers['x-request-id'], 'req-json')

  const sse = await request(gateway.port, '/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: 'Bearer proxy-token',
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    },
    body: JSON.stringify({ stream: true, messages: [] })
  })
  assert.equal(sse.status, 200)
  assert.equal(sse.headers.get('content-type'), 'text/event-stream')
  assert.match(sse.text, /data: .*"content":"hi"/)
  assert.match(sse.text, /\[DONE\]/)
})

test('请求体超过 2MB 返回 413', async (t) => {
  const gateway = await startGateway(t, { RUNTIME_TOKEN: 'body-token' })
  const oversized = `{"x":"${'a'.repeat(2 * 1024 * 1024)}"}`
  const res = await request(gateway.port, '/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: 'Bearer body-token',
      'Content-Type': 'application/json'
    },
    body: oversized
  })
  assert.equal(res.status, 413)
  assert.match(res.text, /request body too large/)
})

test('灰度标记 X-Gray-Tag 透传到平台；未标记时不伪造头', async (t) => {
  const received = []
  const upstream = http.createServer((req, res) => {
    let body = ''
    req.on('data', chunk => { body += chunk })
    req.on('end', () => {
      received.push({ headers: req.headers })
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end('{"id":"upstream"}')
    })
  })
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
  const upstreamPort = upstream.address().port
  t.after(() => upstream.close())

  const gateway = await startGateway(t, {
    PLATFORM_BASE: `http://127.0.0.1:${upstreamPort}`,
    API_KEY: 'upstream-key',
    RUNTIME_TOKEN: 'proxy-token'
  })

  const base = {
    method: 'POST',
    headers: { Authorization: 'Bearer proxy-token', 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'unit-agent', messages: [{ role: 'user', content: 'hi' }] })
  }

  const withTag = await request(gateway.port, '/v1/chat/completions', {
    ...base, headers: { ...base.headers, 'X-Gray-Tag': 'canary-v2' }
  })
  assert.equal(withTag.status, 200)
  assert.equal(received.at(-1).headers['x-gray-tag'], 'canary-v2')

  await request(gateway.port, '/v1/chat/completions', base)
  assert.equal(received.at(-1).headers['x-gray-tag'], undefined, '未标记时不得伪造头')
})
