import { test, expect, type APIRequestContext } from '@playwright/test'
import path from 'path'

const P = '/api/v1'
const MOCK = 'http://127.0.0.1:18092'
const MCP_MOCK = 'http://127.0.0.1:18093'
const BACKEND = process.env.E2E_BACKEND_URL || 'http://localhost:8080'
const OPENAI_MOCK = 'http://127.0.0.1:18080'

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
    expect(discovered.data.resources.length).toBeGreaterThan(0)
    expect(discovered.data.resourceTemplates.length).toBeGreaterThan(0)
    expect(discovered.data.prompts.length).toBeGreaterThan(0)

    const resource = await request.get(`${P}/mcp-servers/${serverId}/resources/read`, {
      headers: auth(token!), params: { uri: 'mock://docs/readme' }
    })
    const resourceBody = await resource.json()
    expect(resourceBody.code, JSON.stringify(resourceBody)).toBe(0)
    expect(String(resourceBody.data.contents[0].text)).toContain('resource:mock://docs/readme')

    const prompt = await request.post(`${P}/mcp-servers/${serverId}/prompts/get`, {
      headers: auth(token!), data: { name: 'summarize', arguments: { text: 'hello' } }
    })
    const promptBody = await prompt.json()
    expect(promptBody.code, JSON.stringify(promptBody)).toBe(0)
    expect(JSON.stringify(promptBody.data.messages)).toContain('prompt:hello')

    const sync = await request.post(`${P}/mcp-servers/${serverId}/sync`, {
      headers: auth(token!), data: {}
    })
    const synced = await sync.json()
    expect(synced.code).toBe(0)
    expect(synced.data.total).toBeGreaterThan(0)

    const diff = await (await request.get(`${P}/mcp-servers/${serverId}/tools/diff`, {
      headers: auth(token!)
    })).json()
    expect(diff.code, JSON.stringify(diff)).toBe(0)
    expect(diff.data.counts.total).toBeGreaterThan(0)
    expect(diff.data.counts.added).toBe(0)
    expect(diff.data.counts.changed).toBe(0)
    expect(diff.data.counts.removed).toBe(0)

    const statsAfter = await (await request.get(`${MCP_MOCK}/stats`)).json()
    expect(statsAfter.initialize - statsBefore.initialize,
      'Mcp-Session-Id 应复用，不应每次 RPC 重新 initialize').toBe(1)
    expect(statsAfter.toolsList - statsBefore.toolsList).toBeGreaterThanOrEqual(3)

    // 切换到只暴露 echo2 的 Mock，验证 diff 的新增/移除分类
    const switched = await request.put(`${P}/mcp-servers/${serverId}`, {
      headers: auth(token!), data: { endpoint: `${MCP_MOCK}/mcp-diff` }
    })
    expect((await switched.json()).code).toBe(0)
    const diff2 = await (await request.get(`${P}/mcp-servers/${serverId}/tools/diff`, {
      headers: auth(token!)
    })).json()
    expect(diff2.data.counts.added).toBe(1)
    expect(diff2.data.counts.changed).toBe(0)
    expect(diff2.data.counts.removed).toBe(1)
    expect(diff2.data.removed[0].name).toBe('echo')
    expect(diff2.data.removed[0].referenced).toBe(false)

  } finally {
    if (serverId) await request.delete(`${P}/mcp-servers/${serverId}`, { headers: auth(token!) })
  }
})

test('MCP 工具分页', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const created = await request.post(`${P}/mcp-servers`, {
    headers: auth(token!),
    data: { name: `E2E MCP 分页-${Date.now()}`, transport: 'HTTP', endpoint: `${MCP_MOCK}/mcp-paged` }
  })
  const createdBody = await created.json()
  expect(createdBody.code, JSON.stringify(createdBody)).toBe(0)
  const serverId = createdBody.data.id
  try {
    const discover = await request.get(`${P}/mcp-servers/${serverId}/discover`, { headers: auth(token!) })
    const body = await discover.json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(body.data.tools.map((t: any) => t.name)).toEqual(['echo-page1', 'echo-page2'])
  } finally {
    await request.delete(`${P}/mcp-servers/${serverId}`, { headers: auth(token!) })
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
    expect(discovered.data.toolsChangedAt, 'stdio 主动通知应被检测').toBeTruthy()

    const sync = await request.post(`${P}/mcp-servers/${serverId}/sync`, {
      headers: auth(token!), data: {}
    })
    expect((await sync.json()).data.total).toBe(1)

    const detail = await request.get(`${P}/mcp-servers/${serverId}`, { headers: auth(token!) })
    const view = (await detail.json()).data
    expect(view.transport).toBe('STDIO')
    expect(view.command).toBe('python3')
    expect(view.envKeys).toContain('MCP_E2E_TOKEN')
    expect(view.toolsChangedAt, '同步成功后应清除待同步标记').toBeFalsy()

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


test('流式输出前置审核', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentRes = await request.post(`${P}/agent`, {
    headers: auth(token!),
    data: {
      name: `E2E 审核-${Date.now()}`,
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
    const conv = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: '审核' }
    })
    conversationId = (await conv.json()).data.id

    const blocked = await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!),
      data: { message: '触发审核', stream: false },
      timeout: 60_000
    })
    const blockedBody = await blocked.json()
    expect(blockedBody.code, JSON.stringify(blockedBody)).toBe(0)
    expect(String(blockedBody.data.answer)).toContain('拦截')

    const controller = new AbortController()
    const resp = await fetch(`${BACKEND}${P}/conversations/${conversationId}/messages`, {
      method: 'POST',
      headers: { ...auth(token!), 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: '流式审核', stream: true }),
      signal: controller.signal
    })
    expect(resp.status).toBe(200)
    const reader = resp.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let moderationSeen = false
    let doneStatus: string | null = null
    let leaked = false
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() || ''
      for (const block of blocks) {
        const event = (block.match(/event:(.*)/) || [])[1]?.trim()
        const data = (block.match(/data:(.*)/) || [])[1]?.trim()
        if (!data) continue
        const parsed = JSON.parse(data)
        if (event === 'moderation') moderationSeen = true
        if (event === 'delta' && String(parsed.content || '').includes('赌博')) leaked = true
        if (event === 'done') doneStatus = parsed.status
      }
    }
    expect(moderationSeen, '应收到 moderation 事件').toBe(true)
    expect(leaked, '命中内容不应下发').toBe(false)
    expect(doneStatus).toBe('BLOCKED')

    const detail = await request.get(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    const last = ((await detail.json()).data.messages || [])
      .filter((m: any) => m.role === 'ASSISTANT').pop()
    expect(last?.status).toBe('BLOCKED')
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
  }
})

test('工作流 Agent Handoff 与共享上下文', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const targetId = await createAgent(request, token!, `E2E Handoff 目标-${Date.now()}`)
  const sourceId = await createAgent(request, token!, `E2E Handoff 源-${Date.now()}`)
  try {
    await publishAgent(request, token!, targetId)
    await publishAgent(request, token!, sourceId)
    const workflow = {
      name: 'E2E Handoff', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'handoff1' },
          { id: 'handoff1', type: 'HANDOFF', name: '交给目标 Agent',
            agentId: targetId, inputTemplate: '{{input}}', next: 'state1' },
          { id: 'state1', type: 'STATE', name: '记录 handoff 结果',
            stateKey: 'handoff_result', valueTemplate: '{{last}}', next: 'llm1' },
          { id: 'llm1', type: 'LLM', name: '读取共享上下文', prompt: '总结前序结果', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    const save = await request.put(`${P}/agent/${sourceId}/workflow`, {
      headers: auth(token!), data: workflow
    })
    expect((await save.json()).code).toBe(0)
    const run = await request.post(`${P}/agent/${sourceId}/workflow/run`, {
      headers: auth(token!), data: { input: '请目标 Agent 接手' }, timeout: 60_000
    })
    const body = await run.json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(body.data.status).toBe('SUCCESS')
    expect(String(body.data.output).length).toBeGreaterThan(0)
    const nodeTypes = (body.data.nodeRuns || []).map((n: any) => n.nodeType)
    expect(nodeTypes).toContain('HANDOFF')
    expect(body.data.state?.handoff_result).toBeTruthy()
    const last = await (await request.get(`${OPENAI_MOCK}/last-chat`)).json()
    const systemText = (last.messages || []).filter((m: any) => m.role === 'system')
      .map((m: any) => String(m.content)).join('\n')
    expect(systemText).toContain('前序节点结果')
    expect(systemText).toContain('共享状态')
  } finally {
    await deleteAgent(request, token!, sourceId)
    await deleteAgent(request, token!, targetId)
  }
})

test('工作流并行分支 State 合并', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E 并行State-${Date.now()}`)
  try {
    await publishAgent(request, token!, agentId)
    const workflow = {
      name: 'E2E 并行 State', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'parallel1' },
          { id: 'parallel1', type: 'PARALLEL', name: '并行',
            branches: ['s1', 's2'], joinNode: 'join1', strategy: 'ALL_OR_NOTHING' },
          { id: 's1', type: 'STATE', name: '分支A', stateKey: 'branch_a',
            valueTemplate: 'alpha', next: 'join1' },
          { id: 's2', type: 'STATE', name: '分支B', stateKey: 'branch_b',
            valueTemplate: 'beta', next: 'join1' },
          { id: 'join1', type: 'JOIN', name: '汇合', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    expect((await (await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: workflow
    })).json()).code).toBe(0)
    const body = await (await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: '并行测试' }, timeout: 60_000
    })).json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(body.data.status).toBe('SUCCESS')
    expect(body.data.state?.branch_a).toBe('alpha')
    expect(body.data.state?.branch_b).toBe('beta')
  } finally {
    await deleteAgent(request, token!, agentId)
  }
})

test('工作流嵌套并行 State 合并', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E 嵌套并行-${Date.now()}`)
  try {
    await publishAgent(request, token!, agentId)
    const workflow = {
      name: 'E2E 嵌套并行', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'outer' },
          { id: 'outer', type: 'PARALLEL', name: '外层并行',
            branches: ['inner', 's3'], joinNode: 'join1', strategy: 'ALL_OR_NOTHING' },
          { id: 'inner', type: 'PARALLEL', name: '内层并行',
            branches: ['n1', 'n2'], joinNode: 'join2', strategy: 'ALL_OR_NOTHING' },
          { id: 'n1', type: 'STATE', name: '内层A', stateKey: 'nested_a',
            valueTemplate: 'na', next: 'join2' },
          { id: 'n2', type: 'STATE', name: '内层B', stateKey: 'nested_b',
            valueTemplate: 'nb', next: 'join2' },
          { id: 'join2', type: 'JOIN', name: '内层汇合', next: 'join1' },
          { id: 's3', type: 'STATE', name: '外层C', stateKey: 'outer_c',
            valueTemplate: 'oc', next: 'join1' },
          { id: 'join1', type: 'JOIN', name: '外层汇合', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    expect((await (await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: workflow
    })).json()).code).toBe(0)
    const body = await (await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: '嵌套并行测试' }, timeout: 60_000
    })).json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(body.data.status).toBe('SUCCESS')
    expect(body.data.state?.nested_a).toBe('na')
    expect(body.data.state?.nested_b).toBe('nb')
    expect(body.data.state?.outer_c).toBe('oc')
  } finally {
    await deleteAgent(request, token!, agentId)
  }
})

test('工作流并行 HANDOFF fan-out/fan-in', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const targetA = await createAgent(request, token!, `E2E Fanout A-${Date.now()}`)
  const targetB = await createAgent(request, token!, `E2E Fanout B-${Date.now()}`)
  const source = await createAgent(request, token!, `E2E Fanout 源-${Date.now()}`)
  try {
    for (const id of [targetA, targetB, source]) await publishAgent(request, token!, id)
    const workflow = {
      name: 'E2E 并行 Handoff', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'parallel1' },
          { id: 'parallel1', type: 'PARALLEL', name: '并行',
            branches: ['h1', 'h2'], joinNode: 'join1', strategy: 'ALL_OR_NOTHING' },
          { id: 'h1', type: 'HANDOFF', name: '交给 A', agentId: targetA,
            inputTemplate: '{{input}}', next: 's1' },
          { id: 'h2', type: 'HANDOFF', name: '交给 B', agentId: targetB,
            inputTemplate: '{{input}}', next: 's2' },
          { id: 's1', type: 'STATE', name: '记录 A', stateKey: 'agent_a',
            valueTemplate: '{{last}}', next: 'join1' },
          { id: 's2', type: 'STATE', name: '记录 B', stateKey: 'agent_b',
            valueTemplate: '{{last}}', next: 'join1' },
          { id: 'join1', type: 'JOIN', name: '汇合', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    expect((await (await request.put(`${P}/agent/${source}/workflow`, {
      headers: auth(token!), data: workflow
    })).json()).code).toBe(0)
    const body = await (await request.post(`${P}/agent/${source}/workflow/run`, {
      headers: auth(token!), data: { input: '并行交接测试' }, timeout: 120_000
    })).json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(body.data.status).toBe('SUCCESS')
    expect(body.data.state?.agent_a).toBeTruthy()
    expect(body.data.state?.agent_b).toBeTruthy()
  } finally {
    await deleteAgent(request, token!, source)
    await deleteAgent(request, token!, targetA)
    await deleteAgent(request, token!, targetB)
  }
})

test('工作流 Loop 与节点错误策略', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E Loop-${Date.now()}`)
  try {
    const loopWorkflow = {
      name: 'E2E Loop', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'loop1' },
          { id: 'loop1', type: 'LOOP', name: '循环', items: ['A', 'B'],
            bodyNode: 'llm1', loopEnd: 'join1', maxIterations: 5, next: 'end' },
          { id: 'llm1', type: 'LLM', name: '回复', prompt: '只回复OK', next: 'join1' },
          { id: 'join1', type: 'JOIN', name: '汇合', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    const save = await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: loopWorkflow
    })
    expect((await save.json()).code).toBe(0)
    const run = await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: '测试' }, timeout: 60_000
    })
    const runBody = await run.json()
    expect(runBody.code, JSON.stringify(runBody)).toBe(0)
    expect(runBody.data.status).toBe('SUCCESS')
    expect(String(runBody.data.output)).toContain('[1]')
    expect(String(runBody.data.output)).toContain('[2]')

    const limitWorkflow = {
      name: 'E2E Loop超限', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'loop1' },
          { id: 'loop1', type: 'LOOP', name: '循环超限', items: ['A', 'B', 'C'],
            bodyNode: 'llm1', maxIterations: 2, next: 'end' },
          { id: 'llm1', type: 'LLM', name: '回复', prompt: '只回复OK', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    await request.put(`${P}/agent/${agentId}/workflow`, { headers: auth(token!), data: limitWorkflow })
    const limited = await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: '测试' }, timeout: 60_000
    })
    const limitedBody = await limited.json()
    expect(limitedBody.code, JSON.stringify(limitedBody)).toBe(0)
    expect(limitedBody.data.status).toBe('WAITING_APPROVAL')
    const approvals = await (await request.get(`${P}/workflow/approvals`, {
      headers: auth(token!), params: { status: 'PENDING', pageSize: 50 }
    })).json()
    const pending = (approvals.data.list || []).find((a: any) => a.runId === limitedBody.data.id)
    expect(pending, '超过循环上限应创建人工审批').toBeTruthy()
    expect(String(pending.title)).toContain('循环')

    const errorWorkflow = {
      name: 'E2E 节点错误策略', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'tool1' },
          { id: 'tool1', type: 'TOOL', name: '坏工具', toolId: 999999,
            retryTimes: 1, onError: 'CONTINUE', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    await request.put(`${P}/agent/${agentId}/workflow`, { headers: auth(token!), data: errorWorkflow })
    const run2 = await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: '测试' }, timeout: 60_000
    })
    const run2Body = await run2.json()
    expect(run2Body.code, JSON.stringify(run2Body)).toBe(0)
    expect(run2Body.data.status).toBe('SUCCESS')
    expect(String(run2Body.data.output)).toContain('节点失败')
  } finally {
    await request.delete(`${P}/agent/${agentId}/workflow`, { headers: auth(token!) })
    await deleteAgent(request, token!, agentId)
  }
})

test('OTel Trace 导出', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const before = await (await request.get('http://127.0.0.1:18097/stats')).json()
  const agentId = await createAgent(request, token!, `E2E OTel-${Date.now()}`)
  let conversationId: number | null = null
  try {
    await publishAgent(request, token!, agentId)
    const conv = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: 'OTel 导出' }
    })
    conversationId = (await conv.json()).data.id
    await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!),
      data: { message: '只回答OK', stream: false },
      timeout: 60_000
    })
    let after: any = before
    for (let i = 0; i < 20; i++) {
      await new Promise(resolve => setTimeout(resolve, 500))
      after = await (await request.get('http://127.0.0.1:18097/stats')).json()
      if (after.requests > before.requests) break
    }
    expect(after.requests, 'OTLP collector 应收到 trace').toBeGreaterThan(before.requests)
    expect(after.bytes).toBeGreaterThan(0)
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
  }
})

test('长期记忆注入与编辑', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const memoryRes = await request.post(`${P}/memories`, {
    headers: auth(token!),
    data: { content: '我的名字是王小明，偏好简洁回答。', category: 'PROFILE' }
  })
  const memoryBody = await memoryRes.json()
  expect(memoryBody.code, JSON.stringify(memoryBody)).toBe(0)
  const memoryId = memoryBody.data.id as number
  const agentId = await createAgent(request, token!, `E2E 记忆-${Date.now()}`)
  let conversationId: number | null = null
  try {
    await publishAgent(request, token!, agentId)
    const conv = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: '记忆注入' }
    })
    conversationId = (await conv.json()).data.id
    await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!), data: { message: '只回答OK', stream: false }, timeout: 60_000
    })
    const last = await (await request.get(`${OPENAI_MOCK}/last-chat`)).json()
    const systemText = (last.messages || [])
      .filter((m: any) => m.role === 'system')
      .map((m: any) => typeof m.content === 'string' ? m.content : JSON.stringify(m.content))
      .join('\n')
    expect(systemText).toContain('王小明')
    expect(systemText).toContain('长期记忆')

    const updated = await request.put(`${P}/memories/${memoryId}`, {
      headers: auth(token!), data: { content: '我的名字是王小明，偏好简洁回答，常住上海。' }
    })
    expect((await updated.json()).data.content).toContain('上海')
    const list = await (await request.get(`${P}/memories`, { headers: auth(token!) })).json()
    expect((list.data.list || []).some((m: any) => m.id === memoryId)).toBe(true)
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await request.delete(`${P}/memories/${memoryId}`, { headers: auth(token!) })
    await deleteAgent(request, token!, agentId)
  }
})

test('知识库按库选择 embedding 模型', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const modelsRes = await request.get(`${P}/knowledge-base/embedding-models`, { headers: auth(token!) })
  const modelsBody = await modelsRes.json()
  expect(modelsBody.code).toBe(0)
  const names = (modelsBody.data || []).map((m: any) => m.name)
  expect(names).toContain('nomic-embed-text')
  expect(names).toContain('bge-m3')

  const create = await request.post(`${P}/knowledge-base`, {
    headers: auth(token!),
    data: { name: `E2E 多模型-${Date.now()}`, description: 'embedding 选择', visibility: 'PRIVATE',
      embeddingModel: 'bge-m3' }
  })
  const created = await create.json()
  expect(created.code, JSON.stringify(created)).toBe(0)
  const kbId = created.data.id as number
  try {
    const detail = await (await request.get(`${P}/knowledge-base/${kbId}`, { headers: auth(token!) })).json()
    expect(detail.data.embeddingModel).toBe('bge-m3')

    const update = await request.put(`${P}/knowledge-base/${kbId}`, {
      headers: auth(token!),
      data: { name: `E2E 多模型-${kbId}`, description: '切换 nomic', embeddingModel: 'nomic-embed-text' }
    })
    expect((await update.json()).code, '无文档时应允许切换').toBe(0)
    const detail2 = await (await request.get(`${P}/knowledge-base/${kbId}`, { headers: auth(token!) })).json()
    expect(detail2.data.embeddingModel).toBe('nomic-embed-text')
  } finally {
    await request.delete(`${P}/knowledge-base/${kbId}?force=true`, { headers: auth(token!) })
  }
})

test('自我纠正机制', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentRes = await request.post(`${P}/agent`, {
    headers: auth(token!),
    data: {
      name: `E2E 自纠正-${Date.now()}`,
      systemPrompt: '你是测试助手。',
      modelConfig: { modelName: 'deepseek-flash' },
      agentCapabilities: { selfCorrection: true, selfCorrectionRounds: 2 },
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
      headers: auth(token!), data: { agentId, title: '自我纠正' }
    })
    conversationId = (await conv.json()).data.id
    const sent = await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!),
      data: { message: '请介绍你自己', stream: false },
      timeout: 60_000
    })
    const body = await sent.json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(String(body.data.answer)).toContain('纠正后的答案')
    const cases = await (await request.get(`${P}/cases`, {
      headers: auth(token!), params: { source: 'AUTO_SELF_CORRECTION' }
    })).json()
    const captured = (cases.data.list || []).find((c: any) => c.assistantMessageId === body.data.messageId)
    expect(captured, '自我纠正应自动沉淀坏例').toBeTruthy()
    expect(String(captured.expectedAnswer)).toContain('纠正后的答案')
    await request.delete(`${P}/cases/${captured.id}`, { headers: auth(token!) })
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
  }
})

test('长期记忆自动生成与用户画像', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E 自动记忆-${Date.now()}`)
  let conversationId: number | null = null
  let memoryId: number | null = null
  try {
    await publishAgent(request, token!, agentId)
    const conv = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: '自动记忆' }
    })
    conversationId = (await conv.json()).data.id
    await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!), data: { message: '我是测试工程师，请记住', stream: false },
      timeout: 60_000
    })
    for (let i = 0; i < 20; i++) {
      await new Promise(resolve => setTimeout(resolve, 300))
      const list = await (await request.get(`${P}/memories`, { headers: auth(token!) })).json()
      const auto = (list.data.list || []).find((m: any) =>
        m.source === 'AUTO' && String(m.content).includes('测试工程师'))
      if (auto) { memoryId = auto.id; break }
    }
    expect(memoryId, '应自动生成长期记忆').toBeTruthy()
    const profile = await (await request.get(`${P}/memories/profile`, { headers: auth(token!) })).json()
    expect(profile.data.autoCount).toBeGreaterThan(0)
    expect(String(profile.data.summary)).toContain('测试工程师')
  } finally {
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    if (memoryId) await request.delete(`${P}/memories/${memoryId}`, { headers: auth(token!) })
    await deleteAgent(request, token!, agentId)
  }
})


test('对话用例库：反馈沉淀与模型回归', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E 用例库-${Date.now()}`)
  let conversationId: number | null = null
  const caseIds: number[] = []
  let runId: number | null = null
  try {
    await publishAgent(request, token!, agentId)
    const conv = await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: '用例库' }
    })
    conversationId = (await conv.json()).data.id

    const send = async (message: string) => {
      const res = await request.post(`${P}/conversations/${conversationId}/messages`, {
        headers: auth(token!), data: { message, stream: false }, timeout: 60_000
      })
      const body = await res.json()
      expect(body.code, JSON.stringify(body)).toBe(0)
      return body.data.messageId as number
    }
    const first = await send('只回答OK')
    await request.post(`${P}/conversations/${conversationId}/messages/${first}/feedback`, {
      headers: auth(token!), data: { type: 'DOWN', comment: '答非所问' }
    })
    const second = await send('只回答OK')
    await request.post(`${P}/conversations/${conversationId}/messages/${second}/feedback`, {
      headers: auth(token!), data: { type: 'UP' }
    })

    const list = await (await request.get(`${P}/cases`, { headers: auth(token!) })).json()
    const bad = (list.data.list || []).find((c: any) => c.assistantMessageId === first)
    const classic = (list.data.list || []).find((c: any) => c.assistantMessageId === second)
    expect(bad?.kind).toBe('BAD')
    expect(bad?.source).toBe('AUTO_BAD')
    expect(classic?.kind).toBe('CLASSIC')
    caseIds.push(bad.id, classic.id)

    const noExpected = await request.put(`${P}/cases/${bad.id}`, {
      headers: auth(token!), data: { status: 'APPROVED' }
    })
    expect((await noExpected.json()).code, '坏例缺少期望答案应拒绝审核').toBe(400)
    for (const id of caseIds) {
      const upd = await request.put(`${P}/cases/${id}`, {
        headers: auth(token!), data: { status: 'APPROVED', expectedAnswer: 'OK' }
      })
      expect((await upd.json()).code).toBe(0)
    }

    const run = await request.post(`${P}/cases/run`, {
      headers: auth(token!),
      data: { modelName: 'deepseek-flash', judgeModel: 'deepseek-flash', caseIds },
      timeout: 120_000
    })
    const runBody = await run.json()
    expect(runBody.code, JSON.stringify(runBody)).toBe(0)
    runId = runBody.data.id
    expect(runBody.data.total).toBe(2)
    expect(runBody.data.passed).toBe(2)
    expect(runBody.data.items.length).toBe(2)

    const promote = await request.put(`${P}/cases/${bad.id}`, {
      headers: auth(token!),
      data: { status: 'APPROVED', datasetTier: 'GOLDEN', riskLevel: 'HIGH',
        coverageTags: ['安全', '坏例'], expiresAt: '2027-12-31T23:59:59' }
    })
    expect((await promote.json()).code).toBe(0)
    const golden = await (await request.get(`${P}/cases`, {
      headers: auth(token!), params: { tier: 'GOLDEN' }
    })).json()
    expect(golden.data.total).toBe(1)
    const goldenRun = await (await request.post(`${P}/cases/run`, {
      headers: auth(token!),
      data: { modelName: 'deepseek-flash', judgeModel: 'deepseek-flash', tier: 'GOLDEN', caseIds: [bad.id] },
      timeout: 120_000
    })).json()
    expect(goldenRun.code, JSON.stringify(goldenRun)).toBe(0)
    expect(goldenRun.data.total).toBe(1)

    const cal = await (await request.get(`${P}/cases/calibrations`, {
      headers: auth(token!), params: { status: 'PENDING' }
    })).json()
    expect(cal.code, JSON.stringify(cal)).toBe(0)
    expect(cal.data.total).toBeGreaterThanOrEqual(2)
    const item = cal.data.list[0]
    const savedCal = await (await request.put(`${P}/cases/calibrations/${item.id}`, {
      headers: auth(token!), data: { humanScore: 1, humanLabel: 'PASS', note: 'E2E 人工校准' }
    })).json()
    expect(savedCal.code, JSON.stringify(savedCal)).toBe(0)
    expect(savedCal.data.humanLabel).toBe('PASS')
    expect(savedCal.data.labelCount).toBe(1)
    const admin = await login(request, 'admin', process.env.PLATFORM_ADMIN_PASSWORD || 'Admin@123456')
    expect(admin, '需要平台管理员作为第二标注人').toBeTruthy()
    const secondCal = await (await request.put(`${P}/cases/calibrations/${item.id}`, {
      headers: { ...auth(admin!), 'X-Tenant-Id': '1' },
      data: { humanScore: 0.8, humanLabel: 'PASS', note: '第二标注人' }
    })).json()
    expect(secondCal.code, JSON.stringify(secondCal)).toBe(0)
    expect(secondCal.data.labelCount).toBe(2)
    expect((secondCal.data.labels || []).length).toBe(2)
    const calStats = await (await request.get(`${P}/cases/calibrations/stats`, {
      headers: auth(token!)
    })).json()
    expect(calStats.data.labeled).toBeGreaterThanOrEqual(1)
    const done = await (await request.get(`${P}/cases/calibrations`, {
      headers: auth(token!), params: { status: 'DONE' }
    })).json()
    expect(done.data.list.some((x: any) => x.id === item.id)).toBe(true)
  } finally {
    if (runId) {
      // 回归记录保留用于审计；用例本身删除
    }
    for (const id of caseIds) {
      await request.delete(`${P}/cases/${id}`, { headers: auth(token!) })
    }
    if (conversationId) {
      await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    }
    await deleteAgent(request, token!, agentId)
  }
})
