import { test, expect, type APIRequestContext } from '@playwright/test'
import path from 'path'
import { createHmac, randomUUID } from 'node:crypto'

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

async function createAgentWithoutTools(request: APIRequestContext, token: string, name: string): Promise<number> {
  const res = await request.post(`${P}/agent`, {
    headers: auth(token),
    data: {
      name,
      description: 'E2E 回传 Agent（无工具）',
      systemPrompt: '你负责复核交接结果，简洁回答。',
      modelConfig: { modelName: 'deepseek-flash', temperature: 0.2 },
      agentCapabilities: { functionCall: false },
      tools: [],
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

test('后端错误消息按 Accept-Language 返回英文', async ({ request }) => {
  const en = await request.post(`${P}/auth/login`, {
    headers: { 'Content-Type': 'application/json', 'Accept-Language': 'en-US,en;q=0.9' },
    data: { username: 'nobody', password: 'wrong-password' }
  })
  const enBody = await en.json()
  expect(enBody.code).toBe(401)
  expect(enBody.message).toContain('Invalid username or password')

  const zh = await request.post(`${P}/auth/login`, {
    headers: { 'Content-Type': 'application/json', 'Accept-Language': 'zh-CN' },
    data: { username: 'nobody', password: 'wrong-password' }
  })
  const zhBody = await zh.json()
  expect(zhBody.code).toBe(401)
  expect(zhBody.message).toContain('用户名或密码错误')
})

test('用户语言偏好持久化并回显', async ({ request }) => {
  const first = await (await request.post(`${P}/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { username: 'dev', password: 'Dev@123456', tenantKey: 'dev' }
  })).json()
  const token = first.data.accessToken
  await request.put(`${P}/auth/locale`, { headers: auth(token), data: { locale: 'en-US' } })
  const again = await (await request.post(`${P}/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { username: 'dev', password: 'Dev@123456', tenantKey: 'dev' }
  })).json()
  expect(again.data.user.locale).toBe('en-US')
  await request.put(`${P}/auth/locale`, { headers: auth(token), data: { locale: 'zh-CN' } })
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

test('工作流测试运行使用当前草稿，正式运行使用发布版', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E工作流草稿-${Date.now()}`)
  try {
    const published = {
      name: 'draft-run-test', status: 'ENABLED',
      definition: { nodes: [
        { id: 'start', type: 'START', name: 'start', next: 'state1' },
        { id: 'state1', type: 'STATE', name: 'published-state', stateKey: 'k', valueTemplate: 'v', next: 'end' },
        { id: 'end', type: 'END', name: 'published-end' }
      ], edges: [] }
    }
    expect((await (await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: published
    })).json()).code).toBe(0)
    expect((await (await request.post(`${P}/agent/${agentId}/workflow/publish`, {
      headers: auth(token!), data: {}
    })).json()).code).toBe(0)

    const draft = {
      name: 'draft-run-test', status: 'ENABLED',
      definition: { nodes: [
        { id: 'start', type: 'START', name: 'start', next: 'end' },
        { id: 'end', type: 'END', name: 'draft-end' }
      ], edges: [] }
    }
    expect((await (await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: draft
    })).json()).code).toBe(0)

    const draftRun = await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: 'x', draft: true }
    })
    const draftBody = await draftRun.json()
    expect(draftBody.code, JSON.stringify(draftBody)).toBe(0)
    const draftNames = (draftBody.data.nodeRuns || []).map((x: any) => x.name)
    expect(draftNames).toContain('start')
    expect(draftNames).not.toContain('published-state')

    const pinnedRun = await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: 'x' }
    })
    const pinnedBody = await pinnedRun.json()
    expect(pinnedBody.code, JSON.stringify(pinnedBody)).toBe(0)
    const pinnedNames = (pinnedBody.data.nodeRuns || []).map((x: any) => x.name)
    expect(pinnedNames).toContain('published-state')
    expect(pinnedNames).not.toContain('draft-end')
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
    data: { prompt: '你是客服，回答用户问题，不知道就说不知道。', name: 'E2E 优化',
      modelName: 'deepseek-flash' },
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

    // 媒体入站兜底 + 出站第一次 500 后重试成功
    const mediaCh = await (await request.post(`${P}/agent/${agentId}/channels`, {
      headers: auth(token!),
      data: { name: 'E2E Media Retry', type: 'WEBHOOK', outboundUrl: `${MOCK}/flaky` }
    })).json()
    expect(mediaCh.code, JSON.stringify(mediaCh)).toBe(0)
    const mediaInbound = await (await request.post(`${P}/channels/${mediaCh.data.token}/message`, {
      data: { image_url: 'https://example.com/media.png', userId: 'e2e-media' }, timeout: 120_000
    })).json()
    expect(mediaInbound.code, JSON.stringify(mediaInbound)).toBe(0)
    expect(mediaInbound.data.outboundSent).toBe(true)
    const afterRetry = (await (await request.get(`${MOCK}/captured`)).json())
      .filter((c: any) => c.path.includes('/flaky'))
    expect(afterRetry.length).toBeGreaterThanOrEqual(2)
    await request.delete(`${P}/agent/${agentId}/channels/${mediaCh.data.id}`, { headers: auth(token!) })
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
    // 不依赖模型最终措辞，直接校验 Trace 中工具被 DENIED
    const blockedTraces = await (await request.get(`${P}/observability/traces`, {
      headers: auth(token!), params: { agentId, pageSize: 20 }
    })).json()
    const blockedTrace = (blockedTraces.data.list || [])[0]
    expect(blockedTrace, '应生成 Trace').toBeTruthy()
    const blockedDetail = await (await request.get(
      `${P}/observability/traces/${blockedTrace.traceId}`, { headers: auth(token!) })).json()
    expect((blockedDetail.data.spans || []).some((sp: any) => sp.status === 'DENIED'
      && String(sp.name).includes('写工具'))).toBe(true)
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
    const confirmAudit = await (await request.get(`${P}/audit/operations`, {
      headers: auth(token!), params: { opType: 'tool.write.confirm', pageSize: 20 }
    })).json()
    expect((confirmAudit.data.list || []).some((a: any) => String(a.objectId) === String(conversationId))).toBe(true)

    // 细粒度策略：BLOCK 即使消息显式允许也阻断
    const blockedAgentRes = await request.post(`${P}/agent`, {
      headers: auth(token!),
      data: {
        name: `E2E 写策略阻断-${Date.now()}`,
        systemPrompt: '你是测试助手。',
        modelConfig: { modelName: 'deepseek-flash' },
        agentCapabilities: { functionCall: true, writeToolPolicy: 'BLOCK' },
        tools: [{ toolId, enabled: true }],
        visibility: 'PRIVATE'
      }
    })
    const blockedAgentId = (await blockedAgentRes.json()).data.id as number
    // ALLOWLIST 命中后可执行
    const allowAgentRes = await request.post(`${P}/agent`, {
      headers: auth(token!),
      data: {
        name: `E2E 写策略白名单-${Date.now()}`,
        systemPrompt: '你是测试助手。',
        modelConfig: { modelName: 'deepseek-flash' },
        agentCapabilities: { functionCall: true, writeToolPolicy: 'ALLOWLIST', allowedWriteToolIds: [toolId] },
        tools: [{ toolId, enabled: true }],
        visibility: 'PRIVATE'
      }
    })
    const allowAgentId = (await allowAgentRes.json()).data.id as number
    await request.post(`${P}/agent/${blockedAgentId}/publish`, { headers: auth(token!), data: { force: true } })
    await request.post(`${P}/agent/${allowAgentId}/publish`, { headers: auth(token!), data: { force: true } })
    const policyConv1 = (await (await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId: blockedAgentId, title: '写策略阻断' }
    })).json()).data.id
    const policyConv2 = (await (await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId: allowAgentId, title: '写策略白名单' }
    })).json()).data.id
    const beforePolicy = (await (await request.get(`${MOCK}/captured`)).json())
      .filter((c: any) => c.path.includes('write-tool')).length
    const blockedPolicy = await (await request.post(`${P}/conversations/${policyConv1}/messages`, {
      headers: auth(token!), data: { message: '请计算并调用写工具', stream: false, allowWriteTools: true },
      timeout: 60_000
    })).json()
    const policyTraces = await (await request.get(`${P}/observability/traces`, {
      headers: auth(token!), params: { agentId: blockedAgentId, pageSize: 20 }
    })).json()
    const policyTrace = (policyTraces.data.list || [])[0]
    expect(policyTrace, '应生成策略阻断 Trace').toBeTruthy()
    const policyDetail = await (await request.get(
      `${P}/observability/traces/${policyTrace.traceId}`, { headers: auth(token!) })).json()
    expect((policyDetail.data.spans || []).some((sp: any) => sp.status === 'DENIED')).toBe(true)
    const allowedPolicy = await (await request.post(`${P}/conversations/${policyConv2}/messages`, {
      headers: auth(token!), data: { message: '请计算并调用写工具', stream: false, allowWriteTools: true },
      timeout: 60_000
    })).json()
    expect(String(allowedPolicy.data.answer)).not.toContain('安全阻断')
    const afterPolicy = (await (await request.get(`${MOCK}/captured`)).json())
      .filter((c: any) => c.path.includes('write-tool')).length
    expect(afterPolicy).toBeGreaterThan(beforePolicy)
    await request.delete(`${P}/conversations/${policyConv1}`, { headers: auth(token!) })
    await request.delete(`${P}/conversations/${policyConv2}`, { headers: auth(token!) })
    await deleteAgent(request, token!, blockedAgentId)
    await deleteAgent(request, token!, allowAgentId)
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
  const returnId = await createAgentWithoutTools(request, token!, `E2E Handoff 回传-${Date.now()}`)
  try {
    await publishAgent(request, token!, targetId)
    await publishAgent(request, token!, sourceId)
    await publishAgent(request, token!, returnId)
    const workflow = {
      name: 'E2E Handoff', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'handoff1' },
          { id: 'handoff1', type: 'HANDOFF', name: '交给目标 Agent 并回传',
            agentId: targetId, inputTemplate: '{{last}}',
            returnAgentId: returnId, returnTemplate: '基于交接结果复核：{{last}}',
            returnRounds: 2, next: 'state1' },
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
    const before = await (await request.get(`${OPENAI_MOCK}/count`)).json()
    const run = await request.post(`${P}/agent/${sourceId}/workflow/run`, {
      headers: auth(token!), data: { input: '请目标 Agent 接手' }, timeout: 60_000
    })
    const body = await run.json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(body.data.status).toBe('SUCCESS')
    expect(String(body.data.output).length).toBeGreaterThan(0)
    const nodeTypes = (body.data.nodeRuns || []).map((n: any) => n.nodeType)
    expect(nodeTypes).toContain('HANDOFF')
    expect(body.data.state?.handoff_result).toContain('回传复核完成')
    const after = await (await request.get(`${OPENAI_MOCK}/count`)).json()
    expect(after.count - before.count).toBeGreaterThanOrEqual(4)
    const last = await (await request.get(`${OPENAI_MOCK}/last-chat`)).json()
    const systemText = (last.messages || []).filter((m: any) => m.role === 'system')
      .map((m: any) => String(m.content)).join('\n')
    expect(systemText).toContain('前序节点结果')
    expect(systemText).toContain('共享状态')
  } finally {
    await deleteAgent(request, token!, returnId)
    await deleteAgent(request, token!, sourceId)
    await deleteAgent(request, token!, targetId)
  }
})

test('工作流独立 Graph 边执行', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E Graph 边-${Date.now()}`)
  try {
    await publishAgent(request, token!, agentId)
    const workflow = {
      name: 'E2E Graph 边', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始' },
          { id: 'llm1', type: 'LLM', name: '生成', prompt: '只回答OK' },
          { id: 'end', type: 'END', name: '结束' }
        ],
        edges: [
          { from: 'start', to: 'llm1' },
          { from: 'llm1', to: 'end' }
        ]
      }
    }
    const save = await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: workflow
    })
    expect((await save.json()).code).toBe(0)
    const body = await (await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: 'Graph 边测试' }, timeout: 60_000
    })).json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(body.data.status).toBe('SUCCESS')
    expect((body.data.nodeRuns || []).map((n: any) => n.nodeType)).toContain('LLM')
  } finally {
    await deleteAgent(request, token!, agentId)
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

test('工作流并行分支内 LOOP', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E 分支Loop-${Date.now()}`)
  try {
    await publishAgent(request, token!, agentId)
    const workflow = {
      name: 'E2E 分支 Loop', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'parallel1' },
          { id: 'parallel1', type: 'PARALLEL', name: '并行',
            branches: ['loop1', 's3'], joinNode: 'join1', strategy: 'ALL_OR_NOTHING' },
          { id: 'loop1', type: 'LOOP', name: '分支循环', items: ['A', 'B'],
            bodyNode: 'body1', maxIterations: 5, next: 'join1' },
          { id: 'body1', type: 'STATE', name: '记录循环', stateKey: 'loop_last',
            valueTemplate: '{{last}}', next: 'join1' },
          { id: 's3', type: 'STATE', name: '外部分支', stateKey: 'branch_c',
            valueTemplate: 'c', next: 'join1' },
          { id: 'join1', type: 'JOIN', name: '汇合', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    expect((await (await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: workflow
    })).json()).code).toBe(0)
    const body = await (await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: '分支循环测试' }, timeout: 60_000
    })).json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    expect(body.data.status).toBe('SUCCESS')
    expect(body.data.state?.loop_last).toBeTruthy()
    expect(body.data.state?.branch_c).toBe('c')
  } finally {
    await deleteAgent(request, token!, agentId)
  }
})

test('工作流分支 LOOP 超限统一审批', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E 分支Loop超限-${Date.now()}`)
  try {
    await publishAgent(request, token!, agentId)
    const workflow = {
      name: 'E2E 分支 Loop 超限', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'parallel1' },
          { id: 'parallel1', type: 'PARALLEL', name: '并行',
            branches: ['loop1', 's3'], joinNode: 'join1', strategy: 'ALL_OR_NOTHING' },
          { id: 'loop1', type: 'LOOP', name: '分支超限循环', items: ['A', 'B', 'C'],
            bodyNode: 'body1', maxIterations: 2, next: 'join1' },
          { id: 'body1', type: 'STATE', name: '记录循环', stateKey: 'loop_last',
            valueTemplate: '{{last}}', next: 'join1' },
          { id: 's3', type: 'STATE', name: '外部分支', stateKey: 'branch_c',
            valueTemplate: 'c', next: 'join1' },
          { id: 'join1', type: 'JOIN', name: '汇合', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    expect((await (await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: workflow
    })).json()).code).toBe(0)
    const waiting = await (await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: '分支循环超限测试' }, timeout: 60_000
    })).json()
    expect(waiting.code, JSON.stringify(waiting)).toBe(0)
    expect(waiting.data.status).toBe('WAITING_APPROVAL')
    const approvals = await (await request.get(`${P}/workflow/approvals`, {
      headers: auth(token!), params: { status: 'PENDING', pageSize: 50 }
    })).json()
    const approval = (approvals.data.list || []).find((a: any) => a.runId === waiting.data.id)
    expect(approval, '分支 LOOP 超限应产生统一审批').toBeTruthy()
    const admin = await login(request, 'admin', process.env.PLATFORM_ADMIN_PASSWORD || 'Admin@123456')
    const decided = await (await request.post(`${P}/workflow/approvals/${approval.id}/decide`, {
      headers: { ...auth(admin!), 'X-Tenant-Id': '1' },
      data: { approve: true, comment: '分支循环放行' }
    })).json()
    expect(decided.code, JSON.stringify(decided)).toBe(0)
    const detail = await (await request.get(`${P}/workflow/runs/${waiting.data.id}`, {
      headers: auth(token!)
    })).json()
    expect(detail.data.status).toBe('SUCCESS')
    expect(detail.data.state?.branch_c).toBe('c')
    expect(detail.data.state?.loop_last).toBe('C')
  } finally {
    await deleteAgent(request, token!, agentId)
  }
})

test('工作流并行分支审批', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E 并行审批-${Date.now()}`)
  try {
    await publishAgent(request, token!, agentId)
    const workflow = {
      name: 'E2E 并行审批', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'parallel1' },
          { id: 'parallel1', type: 'PARALLEL', name: '并行',
            branches: ['a1', 'a2'], joinNode: 'join1', strategy: 'ALL_OR_NOTHING' },
          { id: 'a1', type: 'APPROVAL', name: '分支A审批', next: 's1' },
          { id: 'a2', type: 'APPROVAL', name: '分支B审批', next: 's2' },
          { id: 's1', type: 'STATE', name: 'A状态', stateKey: 'pa',
            valueTemplate: 'pa', next: 'join1' },
          { id: 's2', type: 'STATE', name: 'B状态', stateKey: 'pb',
            valueTemplate: 'pb', next: 'join1' },
          { id: 'join1', type: 'JOIN', name: '汇合', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ]
      }
    }
    expect((await (await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: workflow
    })).json()).code).toBe(0)
    const waiting = await (await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: '并行审批测试' }, timeout: 60_000
    })).json()
    expect(waiting.code, JSON.stringify(waiting)).toBe(0)
    expect(waiting.data.status).toBe('WAITING_APPROVAL')
    const approvals = await (await request.get(`${P}/workflow/approvals`, {
      headers: auth(token!), params: { status: 'PENDING', pageSize: 50 }
    })).json()
    const approval = (approvals.data.list || []).find((a: any) => a.runId === waiting.data.id)
    expect(approval, '并行分支应产生统一审批').toBeTruthy()
    const admin = await login(request, 'admin', process.env.PLATFORM_ADMIN_PASSWORD || 'Admin@123456')
    expect(admin).toBeTruthy()
    const decided = await (await request.post(`${P}/workflow/approvals/${approval.id}/decide`, {
      headers: { ...auth(admin!), 'X-Tenant-Id': '1' },
      data: { approve: true, comment: '并行审批通过' }
    })).json()
    expect(decided.code, JSON.stringify(decided)).toBe(0)
    const detail = await (await request.get(`${P}/workflow/runs/${waiting.data.id}`, {
      headers: auth(token!)
    })).json()
    expect(detail.data.status).toBe('SUCCESS')
    expect(detail.data.state?.pa).toBe('pa')
    expect(detail.data.state?.pb).toBe('pb')
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
  test.skip(process.env.E2E_OTEL !== '1',
    '设置 E2E_OTEL=1，并让后端以 OTEL_ENABLED=true 指向 18097 collector 后运行')
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


test('知识库向量索引状态与手动维护', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const suffix = Date.now()
  let kbId: number | null = null
  try {
    const created = await (await request.post(`${P}/knowledge-base`, {
      headers: auth(token!),
      data: { name: `E2E vector-index-${suffix}`, visibility: 'PRIVATE', embeddingModel: 'nomic-embed-text' }
    })).json()
    expect(created.code, JSON.stringify(created)).toBe(0)
    kbId = created.data.id

    const status = await (await request.get(`${P}/knowledge-base/${kbId}/vector-indexes`, {
      headers: auth(token!)
    })).json()
    expect(status.code, JSON.stringify(status)).toBe(0)
    expect(status.data.globalIndexName).toBe('idx_embeddings_hnsw')
    expect(Array.isArray(status.data.indexes)).toBe(true)

    const reconciled = await (await request.post(`${P}/knowledge-base/${kbId}/vector-indexes/reconcile`, {
      headers: auth(token!)
    })).json()
    expect(reconciled.code, JSON.stringify(reconciled)).toBe(0)
    expect(reconciled.data).toHaveProperty('created')
    expect(reconciled.data).toHaveProperty('desired')
  } finally {
    if (kbId) await request.delete(`${P}/knowledge-base/${kbId}?force=true`, { headers: auth(token!) })
  }
})

test('知识库上传→分段ACL→检索权限', async ({ request }) => {
  let ollamaReady = false
  try {
    const tags = await (await request.get('http://127.0.0.1:11434/api/tags', { timeout: 3000 })).json()
    ollamaReady = (tags.models || []).some((m: any) => String(m.name).startsWith('nomic-embed-text'))
  } catch { /* CI 无 Ollama */ }
  test.skip(!ollamaReady, '需要本机 Ollama + nomic-embed-text')

  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const suffix = Date.now()
  const userA = `e2e-acl-a-${suffix}`
  const userB = `e2e-acl-b-${suffix}`
  const password = 'Acl@123456'
  let kbId: number | null = null
  let userAId: number | null = null
  let userBId: number | null = null

  async function createMember(username: string): Promise<number> {
    const inv = await (await request.post(`${P}/auth/invitations`, {
      headers: auth(token!), data: { email: `${username}@example.com`, role: 'MEMBER' }
    })).json()
    expect(inv.code, JSON.stringify(inv)).toBe(0)
    const accepted = await (await request.post(`${P}/auth/invitations/accept`, {
      data: { token: inv.data.token, username, password }
    })).json()
    expect(accepted.code, JSON.stringify(accepted)).toBe(0)
    const users = await (await request.get(`${P}/users`, { headers: auth(token!) })).json()
    const row = (users.data || []).find((u: any) => u.username === username)
    expect(row, '新成员应出现在成员列表').toBeTruthy()
    return row.id
  }

  try {
    userAId = await createMember(userA)
    userBId = await createMember(userB)

    const kb = await (await request.post(`${P}/knowledge-base`, {
      headers: auth(token!),
      data: { name: `E2E ACL-${suffix}`, visibility: 'TENANT', embeddingModel: 'nomic-embed-text' }
    })).json()
    expect(kb.code, JSON.stringify(kb)).toBe(0)
    kbId = kb.data.id
    const content = Buffer.from('退款政策：支持 7 天无理由退款，退款将在 3 个工作日内原路退回。', 'utf8')
    const doc = await (await request.post(`${P}/knowledge-base/${kbId}/documents`, {
      headers: auth(token!),
      data: { fileName: `acl-${suffix}.txt`, sizeBytes: content.length, contentType: 'text/plain' }
    })).json()
    expect(doc.code, JSON.stringify(doc)).toBe(0)
    const put = await request.put(doc.data.uploadUrl, {
      data: content, headers: { 'Content-Type': 'text/plain' }
    })
    expect(put.status(), 'MinIO 直传').toBeLessThan(300)
    const callback = await (await request.post(`${P}/knowledge-base/${kbId}/documents/callback`, {
      headers: auth(token!), data: { objectName: doc.data.objectName }
    })).json()
    expect(callback.code, JSON.stringify(callback)).toBe(0)
    const docId = callback.data.documentId

    let status = 'PENDING'
    for (let i = 0; i < 60; i++) {
      await new Promise(r => setTimeout(r, 1000))
      const docs = await (await request.get(`${P}/knowledge-base/${kbId}/documents`, {
        headers: auth(token!)
      })).json()
      const row = (docs.data || []).find((d: any) => d.id === docId)
      status = row?.status
      if (status === 'DONE' || status === 'FAILED') break
    }
    expect(status, '文档应解析入库').toBe('DONE')

    const chunksRes = await (await request.get(
      `${P}/knowledge-base/${kbId}/documents/${docId}/chunks?pageSize=20`,
      { headers: auth(token!) })).json()
    expect((chunksRes.data.list || []).length).toBeGreaterThan(0)
    const chunkId = chunksRes.data.list[0].id

    const tokenA = await login(request, userA, password, 'dev')
    const tokenB = await login(request, userB, password, 'dev')
    expect(tokenA).toBeTruthy()
    expect(tokenB).toBeTruthy()

    const search = async (tk: string | null) => (await (await request.post(
      `${P}/knowledge-base/${kbId}/search`, {
        headers: auth(tk!), data: { query: '退款政策是什么', topK: 5, scoreThreshold: 0.1, mode: 'hybrid' }
      })).json()).data as any[]
    expect((await search(tokenA)).length, 'ACL 前 A 可检索').toBeGreaterThan(0)
    expect((await search(tokenB)).length, 'ACL 前 B 可检索').toBeGreaterThan(0)

    const acl = await (await request.put(`${P}/knowledge-base/${kbId}/chunks/${chunkId}/acl`, {
      headers: auth(token!), data: { allowedUsers: [userAId], tenantVisible: false }
    })).json()
    expect(acl.code, JSON.stringify(acl)).toBe(0)

    expect((await search(tokenA)).length, 'ACL 后 A 仍可检索').toBeGreaterThan(0)
    expect((await search(tokenB)).length, 'ACL 后 B 不可检索').toBe(0)
  } finally {
    if (kbId) await request.delete(`${P}/knowledge-base/${kbId}?force=true`, { headers: auth(token!) })
    for (const id of [userAId, userBId]) {
      if (id) await request.delete(`${P}/users/${id}`, { headers: auth(token!) })
    }
  }
})



test('统一 API 网关：转发/鉴权/轮换/限流日志', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const created = await (await request.post(`${P}/gateway/routes`, {
    headers: auth(token!),
    data: { name: `E2E Gateway-${Date.now()}`, targetUrl: 'http://localhost:8080/actuator',
      timeoutMs: 5000, retries: 1, rateLimitPerMin: 100 }
  })).json()
  expect(created.code, JSON.stringify(created)).toBe(0)
  const route = created.data
  expect(route.apiKey).toMatch(/^gw_/)
  try {
    const ok = await request.get(`${P}/gateway/call/${route.routeKey}/health`, {
      headers: { 'X-Gateway-Key': route.apiKey }
    })
    expect(ok.status()).toBe(200)
    expect(await ok.text()).toContain('UP')

    const denied = await request.get(`${P}/gateway/call/${route.routeKey}/health`, {
      headers: { 'X-Gateway-Key': 'gw_invalid' }
    })
    expect(denied.status()).toBe(401)

    const wafBlocked = await request.get(
      `${P}/gateway/call/${route.routeKey}/health?q=1%20union%20select%20password%20from%20users`, {
        headers: { 'X-Gateway-Key': route.apiKey }
      })
    expect(wafBlocked.status()).toBe(400)
    expect(await wafBlocked.text()).toContain('WAF')

    const signed = await (await request.post(`${P}/gateway/routes`, {
      headers: auth(token!),
      data: { name: `E2E Gateway Signed-${Date.now()}`, targetUrl: `${MOCK}/always-ok`,
        timeoutMs: 5000, rateLimitPerMin: 100, signingEnabled: true }
    })).json()
    expect(signed.code, JSON.stringify(signed)).toBe(0)
    const signedRoute = signed.data
    try {
      const path = `${P}/gateway/call/${signedRoute.routeKey}/always-ok`
      const timestamp = String(Date.now())
      const nonce = randomUUID()
      // 服务器 canonical 使用空 body 的 SHA-256
      const emptyHash = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
      const signedCanonical = `GET\n${path}\n\n${timestamp}\n${nonce}\n${emptyHash}`
      const signature = createHmac('sha256', signed.data.signingSecret).update(signedCanonical).digest('hex')
      const headers = {
        'X-Gateway-Key': signedRoute.apiKey,
        'X-Gateway-Timestamp': timestamp,
        'X-Gateway-Nonce': nonce,
        'X-Gateway-Signature': signature
      }
      expect((await request.get(`${P}/gateway/call/${signedRoute.routeKey}/always-ok`, { headers })).status()).toBe(200)
      expect((await request.get(`${P}/gateway/call/${signedRoute.routeKey}/always-ok`, {
        headers: { ...headers, 'X-Gateway-Signature': '0'.repeat(64) }
      })).status()).toBe(401)
      expect((await request.get(`${P}/gateway/call/${signedRoute.routeKey}/always-ok`, { headers })).status()).toBe(409)
    } finally {
      await request.delete(`${P}/gateway/routes/${signedRoute.id}`, { headers: auth(token!) })
    }

    const scopeRoute = await (await request.post(`${P}/gateway/routes`, {
      headers: auth(token!),
      data: { name: `E2E Gateway Scope-${Date.now()}`, targetUrl: MOCK,
        timeoutMs: 5000, rateLimitPerMin: 0 }
    })).json()
    expect(scopeRoute.code, JSON.stringify(scopeRoute)).toBe(0)
    const scopedKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
      headers: auth(token!),
      data: { name: 'limited', allowedMethods: 'GET', allowedPaths: '/always-ok' }
    })).json()
    expect(scopedKey.code, JSON.stringify(scopedKey)).toBe(0)
    try {
      const scopeOk = await request.get(`${P}/gateway/call/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': scopedKey.data.apiKey }
      })
      expect(scopeOk.status()).toBe(200)
      const scopeMethod = await request.post(`${P}/gateway/call/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': scopedKey.data.apiKey }, data: {}
      })
      expect(scopeMethod.status()).toBe(403)
      const scopePath = await request.get(`${P}/gateway/call/${scopeRoute.data.routeKey}/other`, {
        headers: { 'X-Gateway-Key': scopedKey.data.apiKey }
      })
      expect(scopePath.status()).toBe(403)

      const dailyKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!),
        data: { name: 'daily', dailyQuota: 1 }
      })).json()
      const dailyFirst = await request.get(`${P}/gateway/call/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': dailyKey.data.apiKey }
      })
      expect(dailyFirst.status()).toBe(200)
      const dailySecond = await request.get(`${P}/gateway/call/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': dailyKey.data.apiKey }
      })
      expect(dailySecond.status()).toBe(429)

      const ipDeniedKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!), data: { name: 'ip-denied', allowedIps: '10.0.0.0/8' }
      })).json()
      const ipDenied = await request.get(`${P}/gateway/call/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': ipDeniedKey.data.apiKey }
      })
      expect(ipDenied.status()).toBe(403)

      const ipOkKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!), data: { name: 'ip-ok', allowedIps: '127.0.0.1' }
      })).json()
      const ipOk = await request.get(`${P}/gateway/call/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': ipOkKey.data.apiKey }
      })
      expect(ipOk.status()).toBe(200)

      const bodyKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!), data: { name: 'body-limit', maxBodyBytes: 10 }
      })).json()
      const bodyTooLarge = await request.post(`${P}/gateway/call/${scopeRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': bodyKey.data.apiKey }, data: { name: 'ok' }
      })
      expect(bodyTooLarge.status()).toBe(413)
      const bodySmall = await request.post(`${P}/gateway/call/${scopeRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': bodyKey.data.apiKey }, data: {}
      })
      expect(bodySmall.status()).toBe(200)

      const clearKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!), data: { name: 'clear-scope', allowedMethods: 'GET' }
      })).json()
      const blockedPost = await request.post(`${P}/gateway/call/${scopeRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': clearKey.data.apiKey }, data: {}
      })
      expect(blockedPost.status()).toBe(403)
      await request.put(`${P}/gateway/routes/${scopeRoute.data.id}/keys/${clearKey.data.id}`, {
        headers: auth(token!), data: { allowedMethods: '' }
      })
      const allowedPost = await request.post(`${P}/gateway/call/${scopeRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': clearKey.data.apiKey }, data: {}
      })
      expect(allowedPost.status()).toBe(200)
    } finally {
      await request.delete(`${P}/gateway/routes/${scopeRoute.data.id}`, { headers: auth(token!) })
    }

    const schemaRoute = await (await request.post(`${P}/gateway/routes`, {
      headers: auth(token!),
      data: { name: `E2E Gateway Schema-${Date.now()}`, targetUrl: `${MOCK}/probe`,
        timeoutMs: 5000, rateLimitPerMin: 100,
        requestSchema: '{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}' }
    })).json()
    expect(schemaRoute.code, JSON.stringify(schemaRoute)).toBe(0)
    try {
      const invalid = await request.post(`${P}/gateway/call/${schemaRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': schemaRoute.data.apiKey },
        data: { name: 123 }
      })
      expect(invalid.status()).toBe(400)
      expect(await invalid.text()).toContain('Schema')
      const valid = await request.post(`${P}/gateway/call/${schemaRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': schemaRoute.data.apiKey },
        data: { name: 'ok' }
      })
      expect(valid.status()).toBe(200)
    } finally {
      await request.delete(`${P}/gateway/routes/${schemaRoute.data.id}`, { headers: auth(token!) })
    }

    const logs = await (await request.get(`${P}/gateway/routes/${route.id}/logs`, {
      headers: auth(token!)
    })).json()
    expect((logs.data || []).length).toBeGreaterThanOrEqual(2)
    expect(logs.data[0].path).toContain('/health')

    const rotated = await (await request.post(`${P}/gateway/routes/${route.id}/rotate-key`, {
      headers: auth(token!)
    })).json()
    expect(rotated.code, JSON.stringify(rotated)).toBe(0)
    expect(rotated.data.apiKey).not.toBe(route.apiKey)
    const oldKey = await request.get(`${P}/gateway/call/${route.routeKey}/health`, {
      headers: { 'X-Gateway-Key': route.apiKey }
    })
    expect(oldKey.status()).toBe(401)
    const newKey = await request.get(`${P}/gateway/call/${route.routeKey}/health`, {
      headers: { 'X-Gateway-Key': rotated.data.apiKey }
    })
    expect(newKey.status()).toBe(200)

    const sseRoute = await (await request.post(`${P}/gateway/routes`, {
      headers: auth(token!),
      data: { name: `E2E Gateway SSE-${Date.now()}`, targetUrl: MOCK,
        timeoutMs: 5000, retries: 0, rateLimitPerMin: 100 }
    })).json()
    expect(sseRoute.code, JSON.stringify(sseRoute)).toBe(0)
    try {
      const sse = await request.get(`${P}/gateway/call/${sseRoute.data.routeKey}/gateway-sse`, {
        headers: { 'X-Gateway-Key': sseRoute.data.apiKey, Accept: 'text/event-stream' }
      })
      expect(sse.status()).toBe(200)
      expect(String(sse.headers()['content-type'])).toContain('text/event-stream')
      expect(String(sse.headers()['transfer-encoding'] || '')).toContain('chunked')
      const sseText = await sse.text()
      expect(sseText).toContain('data: chunk-0')
      expect(sseText).toContain('data: chunk-2')
      expect(sseText).toContain('data: [DONE]')
    } finally {
      await request.delete(`${P}/gateway/routes/${sseRoute.data.id}`, { headers: auth(token!) })
    }

    await request.get(`${MOCK}/reset`).catch(() => {})
    const cbRoute = await (await request.post(`${P}/gateway/routes`, {
      headers: auth(token!),
      data: { name: `E2E Gateway Circuit-${Date.now()}`,
        targetUrl: `${MOCK}/always-fail`, fallbackUrl: `${MOCK}/always-ok`,
        timeoutMs: 2000, retries: 0, rateLimitPerMin: 100 }
    })).json()
    expect(cbRoute.code, JSON.stringify(cbRoute)).toBe(0)
    try {
      for (let i = 0; i < 4; i++) {
        const cb = await request.get(`${P}/gateway/call/${cbRoute.data.routeKey}/probe`, {
          headers: { 'X-Gateway-Key': cbRoute.data.apiKey }
        })
        expect(cb.status()).toBe(200)
        expect(await cb.json()).toEqual({ ok: true })
      }
      const stats = await (await request.get(`${MOCK}/stats`)).json()
      expect(stats.failCalls).toBe(3)
      expect(stats.okCalls).toBe(4)
    } finally {
      await request.delete(`${P}/gateway/routes/${cbRoute.data.id}`, { headers: auth(token!) })
    }

    const fallbackRoute = await (await request.post(`${P}/gateway/routes`, {
      headers: auth(token!),
      data: { name: `E2E Gateway Fallback-${Date.now()}`,
        targetUrl: 'http://127.0.0.1:9/actuator',
        fallbackUrl: 'http://localhost:8080/actuator',
        timeoutMs: 2000, retries: 0, rateLimitPerMin: 100 }
    })).json()
    try {
      const viaFallback = await request.get(`${P}/gateway/call/${fallbackRoute.data.routeKey}/health`, {
        headers: { 'X-Gateway-Key': fallbackRoute.data.apiKey }
      })
      expect(viaFallback.status()).toBe(200)
      expect(await viaFallback.text()).toContain('UP')
    } finally {
      await request.delete(`${P}/gateway/routes/${fallbackRoute.data.id}`, { headers: auth(token!) })
    }

    const limitedRoute = await (await request.post(`${P}/gateway/routes`, {
      headers: auth(token!),
      data: { name: `E2E Gateway Limit-${Date.now()}`, targetUrl: 'http://localhost:8080/actuator',
        timeoutMs: 5000, retries: 0, rateLimitPerMin: 1 }
    })).json()
    try {
      const first = await request.get(`${P}/gateway/call/${limitedRoute.data.routeKey}/health`, {
        headers: { 'X-Gateway-Key': limitedRoute.data.apiKey }
      })
      expect(first.status()).toBe(200)
      const second = await request.get(`${P}/gateway/call/${limitedRoute.data.routeKey}/health`, {
        headers: { 'X-Gateway-Key': limitedRoute.data.apiKey }
      })
      expect(second.status()).toBe(429)
    } finally {
      await request.delete(`${P}/gateway/routes/${limitedRoute.data.id}`, { headers: auth(token!) })
    }

    await request.put(`${P}/gateway/routes/${route.id}`, { headers: auth(token!), data: { enabled: false } })
    const disabled = await request.get(`${P}/gateway/call/${route.routeKey}/health`, {
      headers: { 'X-Gateway-Key': rotated.data.apiKey }
    })
    expect(disabled.status()).toBe(503)
  } finally {
    await request.delete(`${P}/gateway/routes/${route.id}`, { headers: auth(token!) })
  }
})


test('文档重新上传覆盖原文件', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const suffix = Date.now()
  const kb = await (await request.post(`${P}/knowledge-base`, {
    headers: auth(token!), data: { name: `E2E 覆盖上传-${suffix}`, visibility: 'PRIVATE',
      embeddingModel: 'nomic-embed-text' }
  })).json()
  expect(kb.code, JSON.stringify(kb)).toBe(0)
  const kbId = kb.data.id
  const upload = async (text: string, documentId?: number) => {
    const content = Buffer.from(text, 'utf8')
    const presign = await (await request.post(`${P}/knowledge-base/${kbId}/documents`, {
      headers: auth(token!), data: { fileName: `overwrite-${suffix}.txt`, sizeBytes: content.length,
        contentType: 'text/plain', documentId }
    })).json()
    expect(presign.code, JSON.stringify(presign)).toBe(0)
    const put = await request.put(presign.data.uploadUrl, {
      data: content, headers: { 'Content-Type': 'text/plain' }
    })
    expect(put.status()).toBeLessThan(300)
    const cb = await (await request.post(`${P}/knowledge-base/${kbId}/documents/callback`, {
      headers: auth(token!), data: { objectName: presign.data.objectName, documentId }
    })).json()
    expect(cb.code, JSON.stringify(cb)).toBe(0)
    return cb.data.documentId as number
  }
  const waitDone = async (docId: number) => {
    for (let i = 0; i < 60; i++) {
      await new Promise(r => setTimeout(r, 1000))
      const docs = await (await request.get(`${P}/knowledge-base/${kbId}/documents`, {
        headers: auth(token!)
      })).json()
      const row = (docs.data || []).find((d: any) => d.id === docId)
      if (row?.status === 'DONE') return row
      if (row?.status === 'FAILED') throw new Error(row.errorMessage || 'parse failed')
    }
    throw new Error('parse timeout')
  }
  try {
    const docId = await upload('旧内容：量子色动力学。')
    const first = await waitDone(docId)
    const newDocId = await upload('新内容：石墨烯晶格结构。', docId)
    expect(newDocId).toBe(docId)
    const second = await waitDone(docId)
    expect(second.objectName).not.toBe(first.objectName)
    const docs = await (await request.get(`${P}/knowledge-base/${kbId}/documents`, {
      headers: auth(token!)
    })).json()
    expect((docs.data || []).filter((d: any) => d.fileName === first.fileName).length).toBe(1)
    const chunks = await (await request.get(
      `${P}/knowledge-base/${kbId}/documents/${docId}/chunks?pageSize=20`,
      { headers: auth(token!) })).json()
    const text = (chunks.data.list || []).map((c: any) => c.content).join('\n')
    expect(text).toContain('石墨烯')
    expect(text).not.toContain('量子色动力学')
  } finally {
    await request.delete(`${P}/knowledge-base/${kbId}?force=true`, { headers: auth(token!) })
  }
})

test('知识图谱 AI 抽取并提交', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const kb = await (await request.post(`${P}/knowledge-base`, {
    headers: auth(token!), data: { name: `E2E 图谱抽取-${Date.now()}`, visibility: 'PRIVATE',
      embeddingModel: 'nomic-embed-text', graphEnabled: true }
  })).json()
  expect(kb.code, JSON.stringify(kb)).toBe(0)
  const kbId = kb.data.id
  try {
    const preview = await (await request.post(`${P}/knowledge-base/${kbId}/graph/extract`, {
      headers: auth(token!),
      data: { text: '智云科技生产净水器，净水器提供 7 天无理由退款。', commit: false,
        modelName: 'deepseek-flash' }
    })).json()
    expect(preview.code, JSON.stringify(preview)).toBe(0)
    expect(preview.data.entityCount).toBe(2)
    expect(preview.data.relationCount).toBe(1)
    expect(preview.data.committed).toBe(false)
    let graph = await (await request.get(`${P}/knowledge-base/${kbId}/graph`, {
      headers: auth(token!)
    })).json()
    expect(graph.data.entityCount).toBe(0)

    const committed = await (await request.post(`${P}/knowledge-base/${kbId}/graph/extract`, {
      headers: auth(token!),
      data: { text: '智云科技生产净水器，净水器提供 7 天无理由退款。', commit: true,
        modelName: 'deepseek-flash' }
    })).json()
    expect(committed.code, JSON.stringify(committed)).toBe(0)
    expect(committed.data.committed).toBe(true)
    graph = await (await request.get(`${P}/knowledge-base/${kbId}/graph`, {
      headers: auth(token!)
    })).json()
    expect(graph.data.entityCount).toBe(2)
    expect(graph.data.relationCount).toBe(1)
    expect(graph.data.relations[0].relation).toBe('生产')
  } finally {
    await request.delete(`${P}/knowledge-base/${kbId}?force=true`, { headers: auth(token!) })
  }
})

test('知识图谱实体/关系 CRUD', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const kb = await (await request.post(`${P}/knowledge-base`, {
    headers: auth(token!), data: { name: `E2E 图谱-${Date.now()}`, visibility: 'PRIVATE',
      embeddingModel: 'nomic-embed-text', graphEnabled: true }
  })).json()
  expect(kb.code, JSON.stringify(kb)).toBe(0)
  const kbId = kb.data.id
  try {
    const e1 = await (await request.post(`${P}/knowledge-base/${kbId}/graph/entities`, {
      headers: auth(token!), data: { name: '退款政策', entityType: '政策', description: '7 天无理由' }
    })).json()
    expect(e1.code, JSON.stringify(e1)).toBe(0)
    const e2 = await (await request.post(`${P}/knowledge-base/${kbId}/graph/entities`, {
      headers: auth(token!), data: { name: '净水器', entityType: '产品' }
    })).json()
    expect(e2.code, JSON.stringify(e2)).toBe(0)
    const duplicate = await (await request.post(`${P}/knowledge-base/${kbId}/graph/entities`, {
      headers: auth(token!), data: { name: '退款政策', entityType: '政策' }
    })).json()
    expect(duplicate.code).toBe(409)
    const noType = await (await request.post(`${P}/knowledge-base/${kbId}/graph/entities`, {
      headers: auth(token!), data: { name: '无类型实体' }
    })).json()
    expect(noType.code, JSON.stringify(noType)).toBe(0)
    const noTypeDup = await (await request.post(`${P}/knowledge-base/${kbId}/graph/entities`, {
      headers: auth(token!), data: { name: '无类型实体' }
    })).json()
    expect(noTypeDup.code).toBe(409)

    const relation = await (await request.post(`${P}/knowledge-base/${kbId}/graph/relations`, {
      headers: auth(token!), data: { fromId: e1.data.id, toId: e2.data.id, relation: '适用于', weight: 2 }
    })).json()
    expect(relation.code, JSON.stringify(relation)).toBe(0)
    const relationDup = await (await request.post(`${P}/knowledge-base/${kbId}/graph/relations`, {
      headers: auth(token!), data: { fromId: e1.data.id, toId: e2.data.id, relation: '适用于' }
    })).json()
    expect(relationDup.code).toBe(409)
    const graph = await (await request.get(`${P}/knowledge-base/${kbId}/graph`, {
      headers: auth(token!)
    })).json()
    expect(graph.data.entityCount).toBe(3)
    expect(graph.data.relationCount).toBe(1)
    expect(graph.data.relations[0].relation).toBe('适用于')

    const updated = await (await request.put(
      `${P}/knowledge-base/${kbId}/graph/entities/${e1.data.id}`,
      { headers: auth(token!), data: { description: '更新后的说明' } }
    )).json()
    expect(updated.data.description).toBe('更新后的说明')

    await request.delete(`${P}/knowledge-base/${kbId}/graph/relations/${relation.data.id}`,
      { headers: auth(token!) })
    await request.delete(`${P}/knowledge-base/${kbId}/graph/entities/${e1.data.id}`,
      { headers: auth(token!) })
    const after = await (await request.get(`${P}/knowledge-base/${kbId}/graph`, {
      headers: auth(token!)
    })).json()
    expect(after.data.entityCount).toBe(2)
    expect(after.data.relationCount).toBe(0)
  } finally {
    await request.delete(`${P}/knowledge-base/${kbId}?force=true`, { headers: auth(token!) })
  }
})


test('GraphRAG：高阈值下图谱关联 chunk 召回', async ({ request }) => {
  let ollamaReady = false
  try {
    const tags = await (await request.get('http://127.0.0.1:11434/api/tags', { timeout: 3000 })).json()
    ollamaReady = (tags.models || []).some((m: any) => String(m.name).startsWith('nomic-embed-text'))
  } catch { /* CI 无 Ollama */ }
  test.skip(!ollamaReady, '需要本机 Ollama + nomic-embed-text')

  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const suffix = Date.now()
  const kb = await (await request.post(`${P}/knowledge-base`, {
    headers: auth(token!), data: { name: `E2E GraphRAG-${suffix}`, visibility: 'PRIVATE',
      embeddingModel: 'nomic-embed-text', graphEnabled: true }
  })).json()
  expect(kb.code, JSON.stringify(kb)).toBe(0)
  const kbId = kb.data.id
  try {
    const content = Buffer.from('量子色动力学中的胶子禁闭现象与渐近自由，属于高能物理归档材料。', 'utf8')
    const doc = await (await request.post(`${P}/knowledge-base/${kbId}/documents`, {
      headers: auth(token!),
      data: { fileName: `graphrag-${suffix}.txt`, sizeBytes: content.length, contentType: 'text/plain' }
    })).json()
    expect(doc.code, JSON.stringify(doc)).toBe(0)
    await request.put(doc.data.uploadUrl, { data: content, headers: { 'Content-Type': 'text/plain' } })
    const callback = await (await request.post(`${P}/knowledge-base/${kbId}/documents/callback`, {
      headers: auth(token!), data: { objectName: doc.data.objectName }
    })).json()
    const docId = callback.data.documentId
    let done = false
    for (let i = 0; i < 60; i++) {
      await new Promise(r => setTimeout(r, 1000))
      const docs = await (await request.get(`${P}/knowledge-base/${kbId}/documents`, {
        headers: auth(token!)
      })).json()
      const row = (docs.data || []).find((d: any) => d.id === docId)
      if (row?.status === 'DONE') { done = true; break }
      if (row?.status === 'FAILED') break
    }
    expect(done, '文档应解析入库').toBe(true)
    const chunks = await (await request.get(
      `${P}/knowledge-base/${kbId}/documents/${docId}/chunks?pageSize=20`,
      { headers: auth(token!) })).json()
    const chunkId = (chunks.data.list || [])[0]?.id
    expect(chunkId).toBeTruthy()
    const entity = await (await request.post(`${P}/knowledge-base/${kbId}/graph/entities`, {
      headers: auth(token!), data: { name: '退款政策', entityType: '政策', chunkId }
    })).json()
    expect(entity.code, JSON.stringify(entity)).toBe(0)

    // 第二跳：上传第二段无关文本，实体 C 关联它；A→B→C 才可达
    const content2 = Buffer.from('石墨烯的晶格结构与电子迁移率研究，属于材料科学归档。', 'utf8')
    const doc2 = await (await request.post(`${P}/knowledge-base/${kbId}/documents`, {
      headers: auth(token!),
      data: { fileName: `graphrag2-${suffix}.txt`, sizeBytes: content2.length, contentType: 'text/plain' }
    })).json()
    await request.put(doc2.data.uploadUrl, { data: content2, headers: { 'Content-Type': 'text/plain' } })
    const cb2 = await (await request.post(`${P}/knowledge-base/${kbId}/documents/callback`, {
      headers: auth(token!), data: { objectName: doc2.data.objectName }
    })).json()
    const docId2 = cb2.data.documentId
    for (let i = 0; i < 60; i++) {
      await new Promise(r => setTimeout(r, 1000))
      const docs = await (await request.get(`${P}/knowledge-base/${kbId}/documents`, {
        headers: auth(token!)
      })).json()
      const row = (docs.data || []).find((d: any) => d.id === docId2)
      if (row?.status === 'DONE') break
      if (row?.status === 'FAILED') throw new Error('second document failed')
    }
    const chunks2 = await (await request.get(
      `${P}/knowledge-base/${kbId}/documents/${docId2}/chunks?pageSize=20`,
      { headers: auth(token!) })).json()
    const chunkId2 = (chunks2.data.list || [])[0]?.id
    const b = await (await request.post(`${P}/knowledge-base/${kbId}/graph/entities`, {
      headers: auth(token!), data: { name: '售后政策', entityType: '政策' }
    })).json()
    const c = await (await request.post(`${P}/knowledge-base/${kbId}/graph/entities`, {
      headers: auth(token!), data: { name: '石墨烯产品', entityType: '产品', chunkId: chunkId2 }
    })).json()
    expect(b.code, JSON.stringify(b)).toBe(0)
    expect(c.code, JSON.stringify(c)).toBe(0)
    await request.post(`${P}/knowledge-base/${kbId}/graph/relations`, {
      headers: auth(token!), data: { fromId: entity.data.id, toId: b.data.id, relation: '关联' }
    })
    await request.post(`${P}/knowledge-base/${kbId}/graph/relations`, {
      headers: auth(token!), data: { fromId: b.data.id, toId: c.data.id, relation: '包含' }
    })

    const search = await (await request.post(`${P}/knowledge-base/${kbId}/search`, {
      headers: auth(token!),
      data: { query: '退款政策', topK: 5, scoreThreshold: 0.99, mode: 'hybrid' }
    })).json()
    expect(search.code, JSON.stringify(search)).toBe(0)
    const graphHit = (search.data || []).find((h: any) => String(h.content).includes('量子色动力学'))
    expect(graphHit, '应通过 1 跳图谱关联召回 chunk').toBeTruthy()
    const meta = typeof graphHit.metadata === 'string' ? JSON.parse(graphHit.metadata) : graphHit.metadata
    expect(meta.graph).toBe(true)
    expect(meta.entity_name).toBe('退款政策')
    const secondHop = (search.data || []).find((h: any) => String(h.content).includes('石墨烯'))
    expect(secondHop, '应通过 2 跳图谱关联召回 chunk').toBeTruthy()
    const meta2 = typeof secondHop.metadata === 'string' ? JSON.parse(secondHop.metadata) : secondHop.metadata
    expect(String(meta2.reason)).toContain('related2')
  } finally {
    await request.delete(`${P}/knowledge-base/${kbId}?force=true`, { headers: auth(token!) })
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
    const blind = await (await request.get(`${P}/cases/calibrations`, {
      headers: auth(token!), params: { status: 'PENDING', blind: true }
    })).json()
    expect(blind.data.list[0].judgeScore).toBeNull()
    expect(blind.data.list[0].referenceAnswer).toBeNull()
    const sample = await (await request.post(`${P}/cases/calibrations/sample`, {
      headers: auth(token!), data: { percent: 100 }
    })).json()
    expect(sample.code, JSON.stringify(sample)).toBe(0)
    expect(sample.data.sampled).toBeGreaterThanOrEqual(2)
    const item = cal.data.list[0]
    const assigned = await (await request.put(`${P}/cases/calibrations/${item.id}/assign`, {
      headers: auth(token!), data: {}
    })).json()
    expect(assigned.code, JSON.stringify(assigned)).toBe(0)
    expect(assigned.data.assigneeId).toBeTruthy()
    const mine = await (await request.get(`${P}/cases/calibrations`, {
      headers: auth(token!), params: { status: 'PENDING', mine: true, sampledOnly: true }
    })).json()
    expect(mine.data.list.some((x: any) => x.id === item.id)).toBe(true)
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
    expect(Number(calStats.data.interAnnotatorAgreement)).toBeGreaterThanOrEqual(0)
    expect(calStats.data.sampled).toBeGreaterThanOrEqual(2)
    expect(calStats.data.assigned).toBeGreaterThanOrEqual(1)
    const done = await (await request.get(`${P}/cases/calibrations`, {
      headers: auth(token!), params: { status: 'DONE' }
    })).json()
    expect(done.data.list.some((x: any) => x.id === item.id)).toBe(true)

    // 复核仲裁：第二标注人改为 FAIL，形成冲突；平台管理员给出最终标签
    const conflictLabel = await (await request.put(`${P}/cases/calibrations/${item.id}`, {
      headers: { ...auth(admin!), 'X-Tenant-Id': '1' },
      data: { humanScore: 0.2, humanLabel: 'FAIL', note: '冲突标注' }
    })).json()
    expect(conflictLabel.code, JSON.stringify(conflictLabel)).toBe(0)
    const conflictList = await (await request.get(`${P}/cases/calibrations`, {
      headers: auth(token!), params: { status: 'DONE' }
    })).json()
    const conflictItem = (conflictList.data.list || []).find((x: any) => x.id === item.id)
    expect(conflictItem.arbitrationRequired).toBe(true)
    const arbitrated = await (await request.put(`${P}/cases/calibrations/${item.id}/arbitrate`, {
      headers: { ...auth(admin!), 'X-Tenant-Id': '1' },
      data: { humanLabel: 'PASS', humanScore: 0.9, note: '复核结论' }
    })).json()
    expect(arbitrated.code, JSON.stringify(arbitrated)).toBe(0)
    expect(arbitrated.data.arbitratedLabel).toBe('PASS')
    expect(arbitrated.data.arbitrationRequired).toBe(false)
    const afterArbitration = await (await request.get(`${P}/cases/calibrations/stats`, {
      headers: auth(token!)
    })).json()
    expect(afterArbitration.data.arbitrated).toBeGreaterThanOrEqual(1)
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
