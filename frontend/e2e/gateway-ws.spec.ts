import { test, expect } from '@playwright/test'
import { spawn, type ChildProcess } from 'node:child_process'
import path from 'node:path'

const BACKEND = process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080'
const WS_BACKEND = BACKEND.replace(/^http/, 'ws')
const WS_PORT = 18100
let echo: ChildProcess | undefined

test.beforeAll(async () => {
  echo = spawn('python3', [path.resolve(process.cwd(), 'e2e', 'ws_echo_server.py')], {
    stdio: 'ignore'
  })
  await new Promise(resolve => setTimeout(resolve, 500))
})

test.afterAll(() => {
  echo?.kill()
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

test('网关 WebSocket：鉴权、双向回显、全局连接配额', async ({ request }) => {
  const token = await login(request)
  const created = await request.post(`${BACKEND}/api/v1/gateway/routes`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: `E2E WS ${Date.now()}`,
      targetUrl: `http://127.0.0.1:${WS_PORT}`,
      wsMaxConnections: 1,
      timeoutMs: 5000,
      rateLimitPerMin: 0
    }
  })
  const body = await created.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  const route = body.data
  const endpoint = `${WS_BACKEND}/api/v1/gateway/ws/${route.routeKey}?key=${encodeURIComponent(route.apiKey)}`
  let first: any
  try {
    first = await connect(endpoint)
    const reply = nextMessage(first)
    first.send('hello-websocket')
    expect(await reply).toBe('hello-websocket')

    const second = await connect(endpoint)
    const close = await closed(second)
    expect(close.code).toBe(1008)
    expect(close.reason).toContain('connection limit')
  } finally {
    first?.close()
    await request.delete(`${BACKEND}/api/v1/gateway/routes/${route.id}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
  }
})
