import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import http from 'node:http'

/**
 * agent-gateway 黑盒单测：以受控环境变量子进程启动 server.mjs，走真实 HTTP 断言。
 * 覆盖：健康检查、鉴权（运行时令牌/旧版调用方 Key）、限流 429、日配额 429、
 * /stats 鉴权与内存统计、CORS 预检、/v1/chat/completions 对上游的转发（JSON 与 SSE）。
 * 约定：不走 Redis（未设 REDIS_URL 时网关自动退回内存路径），纯本机回环。
 */

const NODE = process.execPath

async function waitHealthy(port, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/healthz`)
      if (res.ok) return
    } catch { /* 未监听，继续等 */ }
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`gateway on :${port} 未就绪`)
}

async function startGateway(extraEnv = {}) {
  const port = 20000 + Math.floor(Math.random() * 20000)
  const child = spawn(NODE, ['server.mjs'], {
    cwd: new URL('.', import.meta.url).pathname,
    env: { ...process.env, ...extraEnv, PORT: String(port) },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  const logs = []
  child.stdout.on('data', chunk => logs.push(chunk))
  child.stderr.on('data', chunk => logs.push(chunk))
  child.logs = () => logs.join('')
  try {
    await waitHealthy(port)
  } catch (error) {
    child.kill('SIGKILL')
    console.error(child.logs())
    throw error
  }
  return {
    port,
    stop: () => new Promise(resolve => {
      child.on('exit', resolve)
      child.kill('SIGTERM')
      setTimeout(() => { try { child.kill('SIGKILL') } catch { /* 已退出 */ } resolve() }, 3000)
    })
  }
}

/** 平台上游桩：记录收到的请求，按场景回 JSON 或 SSE。 */
async function startStub() {
  const seen = []
  const server = http.createServer((req, res) => {
    const chunks = []
    req.on('data', chunk => chunks.push(chunk))
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString()
      seen.push({
        path: req.url,
        apiKey: req.headers['x-api-key'],
        contentType: req.headers['content-type'],
        body
      })
      // 与真实平台契约一致：流式与否由请求体 stream 字段决定（网关不转发 Accept 头）
      let wantsStream = false
      try { wantsStream = JSON.parse(body).stream === true } catch { /* 非 JSON 按非流式 */ }
      if (wantsStream) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
        res.write('data: {"delta":"你"}\n\n')
        res.write('data: {"delta":"好"}\n\n')
        res.write('data: [DONE]\n\n')
        res.end()
      } else {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ id: 'chatcmpl-stub', choices: [{ message: { role: 'assistant', content: 'ok' } }] }))
      }
    })
  })
  const port = await new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server.address().port)))
  return { port, seen, stop: () => new Promise(resolve => server.close(resolve)) }
}

test('健康检查与 CORS 预检', async () => {
  const gw = await startGateway({ RUNTIME_TOKEN: 'tok-health' })
  try {
    const health = await fetch(`http://127.0.0.1:${gw.port}/healthz`)
    assert.equal(health.status, 200)
    assert.equal(await health.text(), 'ok')
    const options = await fetch(`http://127.0.0.1:${gw.port}/v1/models`, { method: 'OPTIONS' })
    assert.equal(options.status, 204)
    assert.ok(options.headers.get('access-control-allow-origin'))
    assert.ok((options.headers.get('access-control-allow-headers') || '').includes('Authorization'))
  } finally {
    await gw.stop()
  }
})

test('鉴权：缺令牌/错令牌 401，运行时令牌与旧版调用方 Key 均可通过', async () => {
  const gw = await startGateway({ RUNTIME_TOKEN: 'tok-main', CALLER_KEYS: 'caller-a:0' })
  try {
    const base = `http://127.0.0.1:${gw.port}/v1/models`
    assert.equal((await fetch(base)).status, 401)
    assert.equal((await fetch(base, { headers: { Authorization: 'Bearer wrong' } })).status, 401)
    const viaRuntime = await fetch(base, { headers: { Authorization: 'Bearer tok-main' } })
    assert.equal(viaRuntime.status, 200)
    assert.equal((await viaRuntime.json()).data[0].id, 'agent')
    const viaCaller = await fetch(base, { headers: { Authorization: 'Bearer caller-a' } })
    assert.equal(viaCaller.status, 200)
  } finally {
    await gw.stop()
  }
})

test('限流：RATE_LIMIT_PER_MIN=1 且零突发时第二请求 429', async () => {
  const gw = await startGateway({ RUNTIME_TOKEN: 'tok-rate', RATE_LIMIT_PER_MIN: '1', RATE_BURST: '0' })
  try {
    const base = `http://127.0.0.1:${gw.port}/v1/models`
    const headers = { Authorization: 'Bearer tok-rate' }
    assert.equal((await fetch(base, { headers })).status, 200)
    const second = await fetch(base, { headers })
    assert.equal(second.status, 429)
    assert.equal((await second.json()).error.type, 'rate_limit_error')
    assert.ok(second.headers.get('retry-after'))
  } finally {
    await gw.stop()
  }
})

test('日配额：配额 1 的调用方第二次请求 429 quota_exceeded', async () => {
  const gw = await startGateway({ RUNTIME_TOKEN: 'tok-quota', CALLER_KEYS: 'quota-key:1', RATE_LIMIT_PER_MIN: '600' })
  try {
    const base = `http://127.0.0.1:${gw.port}/v1/models`
    const headers = { Authorization: 'Bearer quota-key' }
    assert.equal((await fetch(base, { headers })).status, 200)
    const second = await fetch(base, { headers })
    assert.equal(second.status, 429)
    assert.equal((await second.json()).error.type, 'quota_exceeded')
  } finally {
    await gw.stop()
  }
})

test('/stats：无令牌 401，带令牌返回内存统计', async () => {
  const gw = await startGateway({ RUNTIME_TOKEN: 'tok-stats', STATS_TOKEN: 'tok-stats-admin' })
  try {
    assert.equal((await fetch(`http://127.0.0.1:${gw.port}/stats`)).status, 401)
    const wrong = await fetch(`http://127.0.0.1:${gw.port}/stats`, { headers: { Authorization: 'Bearer tok-stats' } })
    assert.equal(wrong.status, 401)
    const ok = await fetch(`http://127.0.0.1:${gw.port}/stats`, { headers: { Authorization: 'Bearer tok-stats-admin' } })
    assert.equal(ok.status, 200)
    const payload = await ok.json()
    assert.equal(payload.store, 'memory')
    assert.ok(Array.isArray(payload.callers))
  } finally {
    await gw.stop()
  }
})

test('chat/completions：转发到平台并携带 X-Api-Key；SSE 按块透传', async () => {
  const stub = await startStub()
  const gw = await startGateway({
    RUNTIME_TOKEN: 'tok-proxy',
    PLATFORM_BASE: `http://127.0.0.1:${stub.port}`,
    API_KEY: 'plat-key-123',
    AGENT_SLUG: 'my-agent'
  })
  try {
    const base = `http://127.0.0.1:${gw.port}/v1/chat/completions`
    const headers = { Authorization: 'Bearer tok-proxy', 'Content-Type': 'application/json' }
    const jsonRes = await fetch(base, {
      method: 'POST', headers,
      body: JSON.stringify({ model: 'my-agent', messages: [{ role: 'user', content: 'hi' }] })
    })
    assert.equal(jsonRes.status, 200)
    assert.equal((await jsonRes.json()).choices[0].message.content, 'ok')

    const sseRes = await fetch(base, {
      method: 'POST', headers: { ...headers, Accept: 'text/event-stream' },
      body: JSON.stringify({ model: 'my-agent', stream: true, messages: [] })
    })
    assert.equal(sseRes.status, 200)
    assert.ok((sseRes.headers.get('content-type') || '').includes('text/event-stream'))
    const sseText = await sseRes.text()
    assert.ok(sseText.includes('data: {"delta":"你"}'))
    assert.ok(sseText.includes('[DONE]'))

    assert.equal(stub.seen.length, 2)
    assert.equal(stub.seen[0].path, '/api/v1/open/chat/completions')
    assert.equal(stub.seen[0].apiKey, 'plat-key-123')
  } finally {
    await gw.stop()
    await stub.stop()
  }
})

test('上游不可达时返回 502', async () => {
  const gw = await startGateway({ RUNTIME_TOKEN: 'tok-502', PLATFORM_BASE: 'http://127.0.0.1:9' })
  try {
    const res = await fetch(`http://127.0.0.1:${gw.port}/v1/chat/completions`, {
      method: 'POST',
      headers: { Authorization: 'Bearer tok-502', 'Content-Type': 'application/json' },
      body: '{}'
    })
    assert.equal(res.status, 502)
    assert.equal((await res.json()).error.type, 'upstream_error')
  } finally {
    await gw.stop()
  }
})
