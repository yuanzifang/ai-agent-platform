import { test, expect, type APIRequestContext } from '@playwright/test'

const P = '/api/v1'
const MOCK = 'http://127.0.0.1:18092'

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
    await request.post(`${MOCK}/reset`).catch(() => {})
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
