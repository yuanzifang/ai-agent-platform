import { test, expect, type APIRequestContext } from '@playwright/test'
import path from 'path'

const P = '/api/v1'
const MOCK = 'http://127.0.0.1:18092'
const MCP_MOCK = 'http://127.0.0.1:18093'
const BACKEND = process.env.E2E_BACKEND_URL || 'http://localhost:8080'

function auth(token: string) {
  return { Authorization: `Bearer ${token}` }
}

async function login(request: APIRequestContext, username: string, password: string, tenantKey?: string) {
  const res = await request.post(`${P}/auth/login`, {
    data: { username, password, tenantKey }
  })
  const body = await res.json()
  return body?.code === 0 ? (body.data.accessToken as string) : null
}

async function createAgent(request: APIRequestContext, token: string, name: string): Promise<number> {
  const res = await request.post(`${P}/agent`, {
    headers: auth(token),
    data: {
      name,
      description: 'E2E API 集成测试',
      systemPrompt: '你是测试助手，简洁回答。',
      modelConfig: { modelName: 'deepseek-flash', temperature: 0.2 },
      agentCapabilities: { functionCall: true },
      tools: [{ toolId: 1, enabled: true }],
      visibility: 'PRIVATE'
    }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.id as number
}

async function publishAgent(request: APIRequestContext, token: string, agentId: number) {
  const res = await request.post(`${P}/agent/${agentId}/publish`, {
    headers: auth(token), data: { force: true }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
}

async function deleteAgent(request: APIRequestContext, token: string, agentId: number) {
  await request.delete(`${P}/agent/${agentId}`, { headers: auth(token) })
}

test('权限与租户基线', async ({ request }) => {
  const anon = await request.get(`${P}/agent`)
  expect(anon.status()).toBe(401)

  const dev = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(dev, 'dev 账号应可登录').toBeTruthy()

  const list = await request.get(`${P}/agent`, { headers: auth(dev!) })
  expect(list.status()).toBe(200)
  expect((await list.json()).code).toBe(0)

  const member = await login(request, 'zhangsan', 'Member@123', 'dev')
  if (member) {
    const denied = await request.get(`${P}/platform/tenants`, { headers: auth(member) })
    expect([403, 404]).toContain(denied.status())
  }

  const acme = await login(request, 'acme-owner', 'Owner@123456', 'acme')
  if (acme) {
    const cross = await request.get(`${P}/agent/1`, { headers: auth(acme) })
    expect([403, 404]).toContain(cross.status())
  }
})

test('Refresh Token 仅 HttpOnly Cookie', async ({ request }) => {
  const res = await request.post(`${P}/auth/login`, {
    data: { username: 'dev', password: 'Dev@123456', tenantKey: 'dev' }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  expect(body.data.accessToken).toBeTruthy()
  expect(body.data.refreshToken).toBeUndefined()
  const setCookie = String(res.headers()['set-cookie'] || '')
  expect(setCookie).toContain('refresh_token=')
  expect(setCookie.toLowerCase()).toContain('httponly')
  expect(setCookie.toLowerCase()).toContain('samesite=strict')

  const refresh = await request.post(`${P}/auth/refresh`, { data: {} })
  const refreshed = await refresh.json()
  expect(refreshed.code, JSON.stringify(refreshed)).toBe(0)
  expect(refreshed.data.accessToken).toBeTruthy()

  const logout = await request.post(`${P}/auth/logout`, { data: {} })
  expect((await logout.json()).code).toBe(0)
})

test('对话→工具→计费→Trace 全链路', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E链路-${Date.now()}`)
  try {
    await publishAgent(request, token!, agentId)
    const convRes = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: 'E2E 链路会话' }
    })
    const conv = await convRes.json()
    expect(conv.code).toBe(0)
    const conversationId = conv.data.id

    const msgRes = await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!),
      data: { message: '请用计算器计算 12*3', stream: false },
      timeout: 120_000
    })
    const msg = await msgRes.json()
    expect(msg.code, JSON.stringify(msg)).toBe(0)
    expect(String(msg.data.answer)).toContain('36')

    const tracesRes = await request.get(`${P}/observability/traces`, {
      headers: auth(token!), params: { agentId, pageSize: 5 }
    })
    const traces = await tracesRes.json()
    expect(traces.code).toBe(0)
    expect(traces.data.total).toBeGreaterThan(0)
    const traceId = traces.data.list[0].traceId
    const detailRes = await request.get(`${P}/observability/traces/${traceId}`, { headers: auth(token!) })
    const detail = await detailRes.json()
    const spans = detail.data.spans || detail.data.spanTree || []
    const flat = JSON.stringify(spans)
    expect(flat).toContain('"tool"')

    const usageRes = await request.get(`${P}/billing/usage`, {
      headers: auth(token!), params: { agentId, days: 1 }
    })
    const usage = await usageRes.json()
    expect(usage.code).toBe(0)
    const tokens = (usage.data.summary?.totalInputTokens ?? 0) + (usage.data.summary?.totalOutputTokens ?? 0)
    expect(tokens).toBeGreaterThan(0)
  } finally {
    await deleteAgent(request, token!, agentId)
  }
})

test('工作流模板与运行', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E工作流-${Date.now()}`)
  try {
    const templates = await request.get(`${P}/workflow/templates`, { headers: auth(token!) })
    const tpl = await templates.json()
    expect(tpl.code).toBe(0)
    expect(tpl.data.length).toBeGreaterThanOrEqual(5)

    const workflow = {
      name: 'E2E 简单问答',
      status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'llm1' },
          { id: 'llm1', type: 'LLM', name: '回答', prompt: '只回答OK', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    const save = await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: workflow
    })
    expect((await save.json()).code).toBe(0)

    const run = await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: '只回答OK' }, timeout: 120_000
    })
    const runBody = await run.json()
    expect(runBody.code, JSON.stringify(runBody)).toBe(0)
    expect(runBody.data.status).toBe('SUCCESS')
    expect(String(runBody.data.output)).toContain('OK')
  } finally {
    await request.delete(`${P}/agent/${agentId}/workflow`, { headers: auth(token!) })
    await deleteAgent(request, token!, agentId)
  }
})

test('Prompt 模板 AI 优化', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const res = await request.post(`${P}/prompt-templates/optimize`, {
    headers: auth(token!),
    data: { prompt: '你是客服，回答用户问题，不知道就说不知道。', name: 'E2E 优化' },
    timeout: 120_000
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  expect(String(body.data.optimizedPrompt).length).toBeGreaterThan(5)
})

test('渠道入站与出站', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E渠道-${Date.now()}`)
  let channelId: number | null = null
  try {
    await publishAgent(request, token!, agentId)
    await request.get(`${MOCK}/reset`).catch(() => {})
    const chRes = await request.post(`${P}/agent/${agentId}/channels`, {
      headers: auth(token!),
      data: { name: 'E2E Webhook', type: 'WEBHOOK', outboundUrl: `${MOCK}/e2e-hook` }
    })
    const ch = await chRes.json()
    expect(ch.code, JSON.stringify(ch)).toBe(0)
    channelId = ch.data.id
    const tokenValue = ch.data.token

    const inbound = await request.post(`${P}/channels/${tokenValue}/message`, {
      data: { text: '你好', userId: 'e2e-user' }, timeout: 120_000
    })
    const inBody = await inbound.json()
    expect(inBody.code, JSON.stringify(inBody)).toBe(0)
    expect(String(inBody.data.answer).length).toBeGreaterThan(0)

    const capturedRes = await request.get(`${MOCK}/captured`)
    const captured = await capturedRes.json()
    const hit = captured.filter((c: any) => c.path.includes('e2e-hook'))
    expect(hit.length).toBeGreaterThan(0)
    expect(String(hit[hit.length - 1].body.answer).length).toBeGreaterThan(0)
  } finally {
    if (channelId) {
      await request.delete(`${P}/agent/${agentId}/channels/${channelId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
  }
})

test('配额阻断', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const quotaRes = await request.get(`${P}/billing/quota`, { headers: auth(token!) })
  const quota = (await quotaRes.json()).data.quota
  try {
    await request.put(`${P}/billing/quota`, {
      headers: auth(token!), data: { maxTokensPerDay: 1 }
    })
    const blocked = await request.post(`${P}/agent/debug/chat`, {
      headers: auth(token!),
      data: { config: { modelConfig: { modelName: 'deepseek-flash' }, systemPrompt: '只回答OK' },
        message: '只回答OK' }
    })
    const body = await blocked.json()
    expect(body.code === 429 || blocked.status() === 429,
      `应触发配额 429：${JSON.stringify(body).slice(0, 120)}`).toBeTruthy()
  } finally {
    await request.put(`${P}/billing/quota`, {
      headers: auth(token!), data: { maxTokensPerDay: quota.maxTokensPerDay }
    })
  }
})

test('MCP HTTP：会话复用与 2025-03-26 能力', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const statsBefore = await (await request.get(`${MCP_MOCK}/stats`)).json()
  let serverId: number | null = null
  try {
    const create = await request.post(`${P}/mcp-servers`, {
      headers: auth(token!),
      data: { name: `E2E MCP HTTP-${Date.now()}`, transport: 'HTTP', endpoint: `${MCP_MOCK}/mcp` }
    })
    const created = await create.json()
    expect(created.code, JSON.stringify(created)).toBe(0)
    serverId = created.data.id

    const testRes = await request.post(`${P}/mcp-servers/${serverId}/test`, { headers: auth(token!) })
    const tested = await testRes.json()
    expect(tested.code).toBe(0)
    expect(tested.data.success, JSON.stringify(tested.data)).toBe(true)

    const discover = await request.get(`${P}/mcp-servers/${serverId}/discover`, { headers: auth(token!) })
    const discovered = await discover.json()
    expect(discovered.code).toBe(0)
    expect(discovered.data.tools.length).toBeGreaterThan(0)
    expect(JSON.stringify(discovered.data.tools[0].annotations)).toContain('readOnlyHint')

    const sync = await request.post(`${P}/mcp-servers/${serverId}/sync`, {
      headers: auth(token!), data: {}
    })
    const synced = await sync.json()
    expect(synced.code).toBe(0)
    expect(synced.data.total).toBeGreaterThan(0)

    const statsAfter = await (await request.get(`${MCP_MOCK}/stats`)).json()
    expect(statsAfter.initialize - statsBefore.initialize,
      'Mcp-Session-Id 应复用，不应每次 RPC 重新 initialize').toBe(1)
    expect(statsAfter.toolsList - statsBefore.toolsList).toBeGreaterThanOrEqual(3)
  } finally {
    if (serverId) await request.delete(`${P}/mcp-servers/${serverId}`, { headers: auth(token!) })
  }
})

test('MCP OAuth 2.1：PKCE 授权与自动刷新', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const statsBefore = await (await request.get(`${MCP_MOCK}/stats`)).json()
  let serverId: number | null = null
  try {
    const create = await request.post(`${P}/mcp-servers`, {
      headers: auth(token!),
      data: {
        name: `E2E MCP OAuth-${Date.now()}`, transport: 'HTTP',
        endpoint: `${MCP_MOCK}/mcp-oauth`, authType: 'OAUTH'
      }
    })
    const created = await create.json()
    expect(created.code, JSON.stringify(created)).toBe(0)
    serverId = created.data.id

    const authorize = await request.post(`${P}/mcp-servers/${serverId}/oauth/authorize`, {
      headers: auth(token!)
    })
    const authBody = await authorize.json()
    expect(authBody.code, JSON.stringify(authBody)).toBe(0)
    expect(String(authBody.data.authorizeUrl)).toContain('code_challenge_method=S256')

    // 模拟浏览器授权：授权端点 302 回后端 callback；callback 再 302 回前端
    const authorizeRes = await request.get(authBody.data.authorizeUrl, { maxRedirects: 0 })
    expect(authorizeRes.status()).toBe(302)
    const callbackUrl = authorizeRes.headers()['location']
    expect(callbackUrl).toContain('/api/v1/mcp-servers/oauth/callback')
    const callbackRes = await request.get(callbackUrl, { maxRedirects: 0 })
    expect(callbackRes.status()).toBe(302)
    expect(callbackRes.headers()['location']).toContain('oauth=success')

    // state 一次性：重放同一回调应失败
    const replay = await request.get(callbackUrl, { maxRedirects: 0 })
    expect(replay.status()).toBe(302)
    expect(replay.headers()['location']).toContain('oauth=error')

    const statusRes = await request.get(`${P}/mcp-servers/${serverId}/oauth/status`, { headers: auth(token!) })
    const status = (await statusRes.json()).data
    expect(status.connected).toBe(true)
    expect(status.refreshable).toBe(true)

    // 授权码 token 故意 3 秒过期（后端提前 60s 刷新），首次调用自动 refresh 后成功
    const testRes = await request.post(`${P}/mcp-servers/${serverId}/test`, { headers: auth(token!) })
    const tested = await testRes.json()
    expect(tested.code).toBe(0)
    expect(tested.data.success, JSON.stringify(tested.data)).toBe(true)

    const statsAfter = await (await request.get(`${MCP_MOCK}/stats`)).json()
    expect(statsAfter.register - statsBefore.register).toBe(1)
    expect(statsAfter.authorize - statsBefore.authorize).toBe(1)
    expect(statsAfter.refresh - statsBefore.refresh).toBeGreaterThanOrEqual(1)

    const disconnect = await request.post(`${P}/mcp-servers/${serverId}/oauth/disconnect`, {
      headers: auth(token!)
    })
    expect((await disconnect.json()).code).toBe(0)
    const status2 = (await (await request.get(`${P}/mcp-servers/${serverId}/oauth/status`, {
      headers: auth(token!)
    })).json()).data
    expect(status2.connected).toBe(false)
  } finally {
    if (serverId) await request.delete(`${P}/mcp-servers/${serverId}`, { headers: auth(token!) })
  }
})

test('平台作为 MCP Server：Agent 工具与鉴权', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  // 不绑定工具，确保 Mock 模型直接回答 OK
  const agentRes = await request.post(`${P}/agent`, {
    headers: auth(token!),
    data: {
      name: `E2E MCP平台-${Date.now()}`,
      description: 'E2E 平台 MCP Server',
      systemPrompt: '你是测试助手，简洁回答。',
      modelConfig: { modelName: 'deepseek-flash', temperature: 0.2 },
      visibility: 'PRIVATE'
    }
  })
  const agentBody = await agentRes.json()
  expect(agentBody.code, JSON.stringify(agentBody)).toBe(0)
  const agentId = agentBody.data.id as number
  let keyId: number | null = null
  try {
    await publishAgent(request, token!, agentId)
    const keyRes = await request.post(`${P}/agent/${agentId}/api-keys`, {
      headers: auth(token!),
      data: { name: 'E2E MCP', channel: 'API', dailyLimit: 0, minuteLimit: 0 }
    })
    const keyBody = await keyRes.json()
    expect(keyBody.code, JSON.stringify(keyBody)).toBe(0)
    const apiKey = keyBody.data.apiKey as string
    keyId = keyBody.data.id

    const init = await request.post(`${BACKEND}/mcp`, {
      headers: { 'X-Api-Key': apiKey },
      data: {
        jsonrpc: '2.0', id: 1, method: 'initialize',
        params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'e2e', version: '1.0' } }
      }
    })
    expect(init.status()).toBe(200)
    expect(init.headers()['mcp-session-id']).toBeTruthy()
    const initBody = await init.json()
    expect(initBody.result.serverInfo.name).toBe('ai-agent-platform')

    const list = await request.post(`${BACKEND}/mcp`, {
      headers: { 'X-Api-Key': apiKey },
      data: { jsonrpc: '2.0', id: 2, method: 'tools/list' }
    })
    const tools = (await list.json()).result.tools
    const names = tools.map((t: any) => t.name)
    expect(names).toContain('ask_agent')
    expect(JSON.stringify(tools.find((t: any) => t.name === 'ask_agent').annotations))
      .toContain('openWorldHint')

    const call = await request.post(`${BACKEND}/mcp`, {
      headers: { 'X-Api-Key': apiKey },
      data: {
        jsonrpc: '2.0', id: 3, method: 'tools/call',
        params: { name: 'ask_agent', arguments: { message: '只回答OK' } }
      },
      timeout: 120_000
    })
    const callBody = await call.json()
    expect(callBody.result.isError, JSON.stringify(callBody)).toBe(false)
    expect(String(callBody.result.content[0].text)).toContain('OK')
    expect(Number(callBody.result.structuredContent.conversationId)).toBeGreaterThan(0)

    const bad = await request.post(`${BACKEND}/mcp`, {
      headers: { 'X-Api-Key': 'sk-invalid-key' },
      data: { jsonrpc: '2.0', id: 4, method: 'tools/list' }
    })
    expect(bad.status()).toBe(401)
    expect(bad.headers()['www-authenticate']).toContain('Bearer')
  } finally {
    if (keyId) await request.delete(`${P}/agent/${agentId}/api-keys/${keyId}`, { headers: auth(token!) })
    await deleteAgent(request, token!, agentId)
  }
})

test('MCP stdio：本地进程接入与命令防护', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const script = path.resolve(process.cwd(), 'e2e', 'stdio_mcp_server.py')
  let serverId: number | null = null
  try {
    const create = await request.post(`${P}/mcp-servers`, {
      headers: auth(token!),
      data: {
        name: `E2E MCP stdio-${Date.now()}`, transport: 'STDIO', command: 'python3',
        args: [script], env: { MCP_E2E_TOKEN: 'hello' }
      }
    })
    const created = await create.json()
    expect(created.code, JSON.stringify(created)).toBe(0)
    serverId = created.data.id

    const testRes = await request.post(`${P}/mcp-servers/${serverId}/test`, { headers: auth(token!) })
    const tested = await testRes.json()
    expect(tested.code).toBe(0)
    expect(tested.data.success, JSON.stringify(tested.data)).toBe(true)
    expect(tested.data.toolCount).toBe(1)

    const discover = await request.get(`${P}/mcp-servers/${serverId}/discover`, { headers: auth(token!) })
    const discovered = await discover.json()
    expect(discovered.code).toBe(0)
    expect(JSON.stringify(discovered.data.tools[0].annotations)).toContain('destructiveHint')

    const sync = await request.post(`${P}/mcp-servers/${serverId}/sync`, {
      headers: auth(token!), data: {}
    })
    expect((await sync.json()).data.total).toBe(1)

    const detail = await request.get(`${P}/mcp-servers/${serverId}`, { headers: auth(token!) })
    const view = (await detail.json()).data
    expect(view.transport).toBe('STDIO')
    expect(view.command).toBe('python3')
    expect(view.envKeys).toContain('MCP_E2E_TOKEN')

    const bad = await request.post(`${P}/mcp-servers`, {
      headers: auth(token!),
      data: { name: '非法命令', transport: 'STDIO', command: 'bash', args: [] }
    })
    expect((await bad.json()).code, 'shell 解释器应被拒绝').toBe(400)
  } finally {
    if (serverId) await request.delete(`${P}/mcp-servers/${serverId}`, { headers: auth(token!) })
  }
})

test('会话重命名与置顶', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E会话整理-${Date.now()}`)
  let conversationId: number | null = null
  try {
    await publishAgent(request, token!, agentId)
    const convRes = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: '原会话名' }
    })
    const convBody = await convRes.json()
    expect(convBody.code, JSON.stringify(convBody)).toBe(0)
    conversationId = convBody.data.id

    const update = await request.put(`${P}/conversations/${conversationId}`, {
      headers: auth(token!), data: { title: '置顶会话', pinned: true }
    })
    expect((await update.json()).code).toBe(0)

    const list = await request.get(`${P}/conversations`, {
      headers: auth(token!), params: { pageSize: 50 }
    })
    const row = (await list.json()).data.list.find((c: any) => c.id === conversationId)
    expect(row.title).toBe('置顶会话')
    expect(row.pinned).toBe(true)
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
  }
})

test('SSE 断线续传', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  // 不绑定工具，避免 Mock 走 tool_calls；"长回答" 触发慢速流
  const agentRes = await request.post(`${P}/agent`, {
    headers: auth(token!),
    data: {
      name: `E2E 续传-${Date.now()}`,
      systemPrompt: '你是测试助手。',
      modelConfig: { modelName: 'deepseek-flash' },
      visibility: 'PRIVATE'
    }
  })
  const agentBody = await agentRes.json()
  expect(agentBody.code, JSON.stringify(agentBody)).toBe(0)
  const agentId = agentBody.data.id as number
  let conversationId: number | null = null
  try {
    await request.post(`${P}/agent/${agentId}/publish`, {
      headers: auth(token!), data: { force: true }
    })
    const convRes = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: 'SSE 续传' }
    })
    conversationId = (await convRes.json()).data.id

    const controller = new AbortController()
    const resp = await fetch(`${BACKEND}${P}/conversations/${conversationId}/messages`, {
      method: 'POST',
      headers: { ...auth(token!), 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: '长回答测试', stream: true }),
      signal: controller.signal
    })
    expect(resp.status).toBe(200)
    const reader = resp.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let received = ''
    while (received.length < 40) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() || ''
      for (const block of blocks) {
        const event = (block.match(/event:(.*)/) || [])[1]?.trim()
        const data = (block.match(/data:(.*)/) || [])[1]?.trim()
        if (event === 'delta' && data) received += JSON.parse(data).content
      }
    }
    controller.abort()
    expect(received.length).toBeGreaterThan(0)

    await new Promise(resolve => setTimeout(resolve, 500))
    const resume = await fetch(
      `${BACKEND}${P}/conversations/${conversationId}/resume?offset=${received.length}`,
      { headers: auth(token!) })
    expect(resume.status).toBe(200)
    const rreader = resume.body!.getReader()
    let rbuffer = ''
    let rest = ''
    let doneStatus: string | null = null
    while (true) {
      const { done, value } = await rreader.read()
      if (done) break
      rbuffer += decoder.decode(value, { stream: true })
      const blocks = rbuffer.split('\n\n')
      rbuffer = blocks.pop() || ''
      for (const block of blocks) {
        const event = (block.match(/event:(.*)/) || [])[1]?.trim()
        const data = (block.match(/data:(.*)/) || [])[1]?.trim()
        if (event === 'delta' && data) rest += JSON.parse(data).content
        if (event === 'done' && data) doneStatus = JSON.parse(data).status
      }
    }
    expect(rest.length, '断线后应继续收到增量').toBeGreaterThan(0)
    expect(doneStatus).toBe('SUCCESS')

    const detail = await request.get(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    const messages = (await detail.json()).data.messages || []
    const assistant = messages.filter((m: any) => m.role === 'ASSISTANT').pop()
    expect(assistant?.status).toBe('SUCCESS')
    expect(String(assistant?.content).length).toBe(received.length + rest.length)
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
  }
})

test('写工具默认阻断与显式允许', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const name = `E2E写工具-${Date.now()}`
  const toolRes = await request.post(`${P}/tools`, {
    headers: auth(token!),
    data: {
      name, toolType: 'API', description: 'E2E 写操作工具',
      apiConfig: { url: `${MOCK}/write-tool`, method: 'POST' },
      timeoutMs: 5000, retryTimes: 0
    }
  })
  const toolBody = await toolRes.json()
  expect(toolBody.code, JSON.stringify(toolBody)).toBe(0)
  const toolId = toolBody.data.id as number

  const agentRes = await request.post(`${P}/agent`, {
    headers: auth(token!),
    data: {
      name: `E2E 写工具Agent-${Date.now()}`,
      systemPrompt: '你是测试助手。',
      modelConfig: { modelName: 'deepseek-flash' },
      agentCapabilities: { functionCall: true },
      tools: [{ toolId, enabled: true }],
      visibility: 'PRIVATE'
    }
  })
  const agentBody = await agentRes.json()
  expect(agentBody.code, JSON.stringify(agentBody)).toBe(0)
  const agentId = agentBody.data.id as number
  let conversationId: number | null = null
  try {
    await request.post(`${P}/agent/${agentId}/publish`, {
      headers: auth(token!), data: { force: true }
    })
    const convRes = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: '写工具门禁' }
    })
    conversationId = (await convRes.json()).data.id
    await request.get(`${MOCK}/reset`).catch(() => {})

    const blocked = await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!),
      data: { message: '请计算并调用写工具', stream: false },
      timeout: 60_000
    })
    const blockedBody = await blocked.json()
    expect(blockedBody.code, JSON.stringify(blockedBody)).toBe(0)
    expect(String(blockedBody.data.answer)).toContain('安全阻断')
    let captured = await (await request.get(`${MOCK}/captured`)).json()
    expect(captured.filter((c: any) => c.path.includes('write-tool')).length).toBe(0)

    const allowed = await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!),
      data: { message: '请计算并调用写工具', stream: false, allowWriteTools: true },
      timeout: 60_000
    })
    const allowedBody = await allowed.json()
    expect(allowedBody.code, JSON.stringify(allowedBody)).toBe(0)
    expect(String(allowedBody.data.answer)).not.toContain('安全阻断')
    captured = await (await request.get(`${MOCK}/captured`)).json()
    expect(captured.filter((c: any) => c.path.includes('write-tool')).length).toBeGreaterThan(0)
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
    await request.delete(`${P}/tools/${toolId}`, { headers: auth(token!) })
  }
})

test('模型失败自动切换备用', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const providers = await (await request.get(`${P}/provider`, { headers: auth(token!) })).json()
  const deepseek = (providers.data || []).find((p: any) => p.providerType === 'DEEPSEEK')
  expect(deepseek, '需要内置 DeepSeek Provider').toBeTruthy()

  const brokenModel = `broken-model-${Date.now()}`
  const modelRes = await request.post(`${P}/provider/${deepseek.id}/models`, {
    headers: auth(token!),
    data: { modelName: brokenModel, displayName: 'E2E失败模型', purpose: 'CHAT', contextWindow: 8192 }
  })
  const modelBody = await modelRes.json()
  expect(modelBody.code, JSON.stringify(modelBody)).toBe(0)
  const modelId = modelBody.data.id as number

  const agentRes = await request.post(`${P}/agent`, {
    headers: auth(token!),
    data: {
      name: `E2E 降级-${Date.now()}`,
      systemPrompt: '你是测试助手。',
      modelConfig: { modelName: brokenModel, fallbackModelName: 'deepseek-flash', temperature: 0.2 },
      visibility: 'PRIVATE'
    }
  })
  const agentBody = await agentRes.json()
  expect(agentBody.code, JSON.stringify(agentBody)).toBe(0)
  const agentId = agentBody.data.id as number
  let conversationId: number | null = null
  try {
    await request.post(`${P}/agent/${agentId}/publish`, {
      headers: auth(token!), data: { force: true }
    })
    const conv = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: '模型降级' }
    })
    conversationId = (await conv.json()).data.id
    const sent = await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!),
      data: { message: '只回答OK', stream: false },
      timeout: 120_000
    })
    const body = await sent.json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(String(body.data.answer)).toContain('OK')
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
    if (modelId) {
      await request.put(`${P}/models/${modelId}`, {
        headers: auth(token!), data: { status: 'DISABLED' }
      })
    }
  }
})

test('Anthropic 原生协议', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const providerName = `E2E Anthropic-${Date.now()}`
  const created = await request.post(`${P}/provider`, {
    headers: auth(token!),
    data: {
      name: providerName, providerType: 'ANTHROPIC',
      baseUrl: 'http://127.0.0.1:18095', apiKey: 'sk-ant-mock', timeoutMs: 15000
    }
  })
  const createdBody = await created.json()
  expect(createdBody.code, JSON.stringify(createdBody)).toBe(0)
  const providerId = createdBody.data.id as number

  const tested = await (await request.post(`${P}/provider/${providerId}/test`, {
    headers: auth(token!)
  })).json()
  expect(tested.data.success, JSON.stringify(tested.data)).toBe(true)

  const synced = await (await request.post(`${P}/provider/${providerId}/models/sync`, {
    headers: auth(token!)
  })).json()
  expect(synced.code, JSON.stringify(synced)).toBe(0)

  const models = await (await request.get(`${P}/provider/models`, { headers: auth(token!) })).json()
  const model = (models.data || []).find((m: any) =>
    m.providerId === providerId && m.modelName === 'claude-mock')
  expect(model, '应同步出 claude-mock').toBeTruthy()
  await request.put(`${P}/models/${model.modelId}`, {
    headers: auth(token!), data: { status: 'ENABLED' }
  })

  const agentRes = await request.post(`${P}/agent`, {
    headers: auth(token!),
    data: {
      name: `E2E Anthropic Agent-${Date.now()}`,
      systemPrompt: '你是测试助手。',
      modelConfig: { modelName: 'claude-mock', temperature: 0.2 },
      visibility: 'PRIVATE'
    }
  })
  const agentBody = await agentRes.json()
  expect(agentBody.code, JSON.stringify(agentBody)).toBe(0)
  const agentId = agentBody.data.id as number
  let conversationId: number | null = null
  try {
    await request.post(`${P}/agent/${agentId}/publish`, {
      headers: auth(token!), data: { force: true }
    })
    const conv = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: 'Anthropic 原生' }
    })
    conversationId = (await conv.json()).data.id
    const sent = await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!),
      data: { message: '只回答OK', stream: false },
      timeout: 60_000
    })
    const body = await sent.json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(String(body.data.answer)).toContain('Anthropic OK')
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
    await request.put(`${P}/models/${model.modelId}`, {
      headers: auth(token!), data: { status: 'DISABLED' }
    })
  }
})
