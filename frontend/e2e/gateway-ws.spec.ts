import { test, expect } from '@playwright/test'
import { spawn, type ChildProcess } from 'node:child_process'
import path from 'node:path'
import { createHmac, randomUUID } from 'node:crypto'

test.describe.configure({ mode: 'serial' })

const BACKEND = process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080'
const WS_BACKEND = BACKEND.replace(/^http/, 'ws')
const PORT_A = 18100
const PORT_B = 18101
let fallback: ChildProcess | undefined

function startEcho(port: number, prefix: string): ChildProcess {
  return spawn('python3', [path.resolve(process.cwd(), 'e2e', 'ws_echo_server.py'), String(port), prefix], {
    stdio: 'ignore'
  })
}

function stopEcho(proc: ChildProcess | undefined) {
  if (proc && !proc.killed) proc.kill('SIGKILL')
}

async function waitReady() {
  await new Promise(resolve => setTimeout(resolve, 400))
}

test.beforeAll(async () => {
  fallback = startEcho(PORT_B, 'B')
  await waitReady()
})

test.afterAll(() => {
  stopEcho(fallback)
})

async function login(request: any): Promise<string> {
  const res = await request.post(`${BACKEND}/api/v1/auth/login`, {
    data: { username: 'dev', password: 'Dev@123456' }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.accessToken
}

function connect(url: string): Promise<any> {
  return new Promise((resolve, reject) => {
    const ws = new (globalThis as any).WebSocket(url)
    ws.onopen = () => resolve(ws)
    ws.onerror = () => reject(new Error('websocket error'))
  })
}

function nextMessage(ws: any): Promise<string> {
  return new Promise(resolve => {
    ws.onmessage = (event: any) => resolve(String(event.data))
  })
}

function closed(ws: any): Promise<{ code: number; reason: string }> {
  return new Promise(resolve => {
    ws.onclose = (event: any) => resolve({ code: event.code, reason: event.reason })
  })
}

async function createRoute(request: any, token: string, data: Record<string, unknown>) {
  const created = await request.post(`${BACKEND}/api/v1/gateway/routes`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `E2E WS ${Date.now()}-${Math.random()}`, rateLimitPerMin: 0, ...data }
  })
  const body = await created.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data
}

async function deleteRoute(request: any, token: string, id: number) {
  await request.delete(`${BACKEND}/api/v1/gateway/routes/${id}`, {
    headers: { Authorization: `Bearer ${token}` }
  })
}

test('网关 WebSocket：鉴权、双向回显、全局连接配额', async ({ request }) => {
  const token = await login(request)
  const primary = startEcho(PORT_A, 'A')
  await waitReady()
  const route = await createRoute(request, token, {
    targetUrl: `http://127.0.0.1:${PORT_A}`,
    wsMaxConnections: 1,
    timeoutMs: 5000
  })
  const endpoint = `${WS_BACKEND}/api/v1/gateway/ws/${route.routeKey}?key=${encodeURIComponent(route.apiKey)}`
  let first: any
  try {
    first = await connect(endpoint)
    const reply = nextMessage(first)
    first.send('hello-websocket')
    expect(await reply).toBe('A:hello-websocket')

    const second = await connect(endpoint)
    const close = await closed(second)
    expect(close.code).toBe(1008)
    expect(close.reason).toContain('connection limit')
  } finally {
    first?.close()
    await deleteRoute(request, token, route.id)
    stopEcho(primary)
  }
})

test('网关 WebSocket：权重首跳', async ({ request }) => {
  const token = await login(request)
  const primary = startEcho(PORT_A, 'A')
  await waitReady()
  const route = await createRoute(request, token, {
    targetUrl: `http://127.0.0.1:${PORT_A}`,
    fallbackUrl: `http://127.0.0.1:${PORT_B}`,
    targetWeight: 0,
    fallbackWeight: 100,
    timeoutMs: 5000
  })
  const endpoint = `${WS_BACKEND}/api/v1/gateway/ws/${route.routeKey}?key=${encodeURIComponent(route.apiKey)}`
  let ws: any
  try {
    ws = await connect(endpoint)
    const reply = nextMessage(ws)
    ws.send('weighted')
    expect(await reply).toBe('B:weighted')
  } finally {
    ws?.close()
    await deleteRoute(request, token, route.id)
    stopEcho(primary)
  }
})

test('网关 WebSocket：握手签名与防重放', async ({ request }) => {
  const token = await login(request)
  const primary = startEcho(PORT_A, 'A')
  await waitReady()
  const route = await createRoute(request, token, {
    targetUrl: `http://127.0.0.1:${PORT_A}`,
    signingEnabled: true,
    timeoutMs: 5000
  })
  const path = `/api/v1/gateway/ws/${route.routeKey}`
  const timestamp = String(Date.now())
  const nonce = randomUUID()
  const query = new URLSearchParams({ key: route.apiKey, timestamp, nonce }).toString()
  const emptyHash = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
  const canonical = `GET\n${path}\n${query}\n${timestamp}\n${nonce}\n${emptyHash}`
  const signature = createHmac('sha256', route.signingSecret).update(canonical).digest('hex')
  const endpoint = `${WS_BACKEND}${path}?${query}&signature=${signature}`
  let ws: any
  try {
    ws = await connect(endpoint)
    const reply = nextMessage(ws)
    ws.send('signed')
    expect(await reply).toBe('A:signed')

    const bad = await connect(`${WS_BACKEND}${path}?${query}&signature=${'0'.repeat(64)}`)
    const badClose = await closed(bad)
    expect(badClose.code).toBe(1008)

    const replay = await connect(endpoint)
    const replayClose = await closed(replay)
    expect(replayClose.code).toBe(1008)
  } finally {
    ws?.close()
    await deleteRoute(request, token, route.id)
    stopEcho(primary)
  }
})

test('网关 WebSocket：上游掉线自动切换', async ({ request }) => {
  test.setTimeout(30_000)
  const token = await login(request)
  const primary = startEcho(PORT_A, 'A')
  await waitReady()
  const route = await createRoute(request, token, {
    targetUrl: `http://127.0.0.1:${PORT_A}`,
    fallbackUrl: `http://127.0.0.1:${PORT_B}`,
    timeoutMs: 5000
  })
  const endpoint = `${WS_BACKEND}/api/v1/gateway/ws/${route.routeKey}?key=${encodeURIComponent(route.apiKey)}`
  let ws: any
  try {
    ws = await connect(endpoint)
    const first = nextMessage(ws)
    ws.send('one')
    expect(await first).toBe('A:one')

    stopEcho(primary)
    await new Promise(resolve => setTimeout(resolve, 1000))

    const second = nextMessage(ws)
    ws.send('two')
    expect(await second).toBe('B:two')
  } finally {
    ws?.close()
    await deleteRoute(request, token, route.id)
    stopEcho(primary)
  }
})
