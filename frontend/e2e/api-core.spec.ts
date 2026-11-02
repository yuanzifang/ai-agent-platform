import { test, expect, type APIRequestContext } from '@playwright/test'
import path from 'path'
import { createHmac, randomUUID } from 'node:crypto'
import {
  P, MOCK, MCP_MOCK, BACKEND, GATEWAY, OPENAI_MOCK,
  auth, login, createAgent, createAgentWithoutTools, publishAgent, deleteAgent
} from './api-helpers'

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
    const localIntent = (tpl.data || []).find((t: any) => t.id === 'local_intent_triage')
    expect(localIntent, '应存在本地意图分类模板').toBeTruthy()
    const localNodes = localIntent.definition?.nodes || []
    expect(localNodes).toHaveLength(17)
    expect(localNodes.filter((n: any) => n.type === 'LLM').every((n: any) => n.modelName === 'qwen2.5:7b')).toBe(true)
    expect(localNodes.filter((n: any) => n.type === 'CONDITION' && n.conditionSource === 'state')).toHaveLength(4)

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

test('工作流条件可读取共享状态', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E状态条件-${Date.now()}`)
  try {
    const workflow = {
      name: 'E2E 状态条件', status: 'ENABLED',
      definition: { nodes: [
        { id: 'start', type: 'START', name: 'start', next: 'save' },
        { id: 'save', type: 'STATE', name: '保存意图', stateKey: 'intent',
          valueTemplate: '售后退款', next: 'cond' },
        { id: 'cond', type: 'CONDITION', name: '读取共享状态', conditionSource: 'state',
          conditionStateKey: 'intent', conditionType: 'contains', conditionValue: '退款',
          trueNext: 'hit', falseNext: 'miss' },
        { id: 'hit', type: 'STATE', name: '命中', stateKey: 'route',
          valueTemplate: 'true', next: 'end' },
        { id: 'miss', type: 'STATE', name: '未命中', stateKey: 'route',
          valueTemplate: 'false', next: 'end' },
        { id: 'end', type: 'END', name: 'end' }
      ], edges: [] }
    }
    expect((await (await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token!), data: workflow
    })).json()).code).toBe(0)
    expect((await (await request.post(`${P}/agent/${agentId}/workflow/publish`, {
      headers: auth(token!), data: {}
    })).json()).code).toBe(0)
    const run = await (await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: auth(token!), data: { input: 'should not be used' }
    })).json()
    expect(run.code, JSON.stringify(run)).toBe(0)
    expect(run.data.status).toBe('SUCCESS')
    expect(run.data.state?.route).toBe('true')
    expect(run.data.state?.intent).toBe('售后退款')
    const names = (run.data.nodeRuns || []).map((x: any) => x.name)
    expect(names).toContain('命中')
    expect(names).not.toContain('未命中')
  } finally {
    await request.delete(`${P}/agent/${agentId}/workflow`, { headers: auth(token!) })
    await deleteAgent(request, token!, agentId)
  }
})

test('全局搜索支持名称和描述全文匹配', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const phrase = `海风退款${Date.now()}`
  const created = await request.post(`${P}/agent`, {
    headers: auth(token!),
    data: {
      name: `E2E搜索-${Date.now()}`,
      description: `${phrase}政策说明`,
      systemPrompt: '全局搜索测试。',
      modelConfig: { modelName: 'deepseek-flash' },
      visibility: 'PRIVATE'
    }
  })
  const agentBody = await created.json()
  expect(agentBody.code, JSON.stringify(agentBody)).toBe(0)
  const agentId = agentBody.data.id as number
  try {
    const search = await request.get(`${P}/search`, {
      headers: auth(token!), params: { q: phrase }
    })
    const body = await search.json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    const hit = (body.data.list || []).find((x: any) => x.type === 'AGENT' && x.id === agentId)
    expect(hit, '描述中的全文关键词应命中，而不是只搜名称').toBeTruthy()
  } finally {
    await deleteAgent(request, token!, agentId)
  }
})

test('全局搜索知识库和工具描述全文匹配', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const phrase = `星河退款${Date.now()}`
  let kbId: number | null = null
  let toolId: number | null = null
  try {
    const kb = await (await request.post(`${P}/knowledge-base`, {
      headers: auth(token!),
      data: { name: `E2E搜索KB-${Date.now()}`, description: `${phrase}知识库说明`, visibility: 'TENANT' }
    })).json()
    expect(kb.code, JSON.stringify(kb)).toBe(0)
    kbId = kb.data.id

    const tool = await (await request.post(`${P}/tools`, {
      headers: auth(token!),
      data: {
        name: `E2E搜索工具-${Date.now()}`, description: `${phrase}工具说明`,
        toolType: 'API', apiConfig: { url: 'http://127.0.0.1:18092/echo', method: 'POST' },
        timeoutMs: 5000, retryTimes: 0
      }
    })).json()
    expect(tool.code, JSON.stringify(tool)).toBe(0)
    toolId = tool.data.id

    const search = await (await request.get(`${P}/search`, {
      headers: auth(token!), params: { q: phrase }
    })).json()
    expect(search.code, JSON.stringify(search)).toBe(0)
    const list = search.data.list || []
    expect(list.some((x: any) => x.type === 'KB' && x.id === kbId),
      '知识库描述全文命中').toBeTruthy()
    expect(list.some((x: any) => x.type === 'TOOL' && x.id === toolId),
      '工具描述全文命中').toBeTruthy()
  } finally {
    if (toolId) await request.delete(`${P}/tools/${toolId}`, { headers: auth(token!) })
    if (kbId) await request.delete(`${P}/knowledge-base/${kbId}?force=true`, { headers: auth(token!) })
  }
})

test('全局搜索支持用例期望答案全文匹配', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E搜索用例-${Date.now()}`)
  let conversationId: number | null = null
  let caseId: number | null = null
  const phrase = `蓝鲸退款${Date.now()}`
  try {
    await publishAgent(request, token!, agentId)
    const conv = await (await request.post(`${P}/conversations`, {
      headers: auth(token!), data: { agentId, title: '搜索用例沉淀' }
    })).json()
    expect(conv.code, JSON.stringify(conv)).toBe(0)
    conversationId = conv.data.id
    const sent = await (await request.post(`${P}/conversations/${conversationId}/messages`, {
      headers: auth(token!), data: { message: '只回答OK', stream: false }, timeout: 60_000
    })).json()
    expect(sent.code, JSON.stringify(sent)).toBe(0)
    await request.post(`${P}/conversations/${conversationId}/messages/${sent.data.messageId}/feedback`, {
      headers: auth(token!), data: { type: 'DOWN', comment: '搜索用例' }
    })

    const cases = await (await request.get(`${P}/cases`, {
      headers: auth(token!), params: { source: 'AUTO_BAD' }
    })).json()
    const row = (cases.data.list || []).find((x: any) => x.assistantMessageId === sent.data.messageId)
    expect(row, '点踩应自动沉淀用例').toBeTruthy()
    caseId = row.id
    const upd = await (await request.put(`${P}/cases/${caseId}`, {
      headers: auth(token!), data: { expectedAnswer: `${phrase}说明`, status: 'APPROVED' }
    })).json()
    expect(upd.code, JSON.stringify(upd)).toBe(0)

    const search = await (await request.get(`${P}/search`, {
      headers: auth(token!), params: { q: phrase }
    })).json()
    expect(search.code, JSON.stringify(search)).toBe(0)
    expect((search.data.list || []).some((x: any) => x.type === 'CASE' && x.id === caseId),
      '用例期望答案全文命中').toBeTruthy()
  } finally {
    if (caseId) await request.delete(`${P}/cases/${caseId}`, { headers: auth(token!) })
    if (conversationId) await request.delete(`${P}/conversations/${conversationId}`, { headers: auth(token!) })
    await deleteAgent(request, token!, agentId)
  }
})

test('工作流错误按 Accept-Language 返回英文', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const agentId = await createAgent(request, token!, `E2E工作流i18n-${Date.now()}`)
  try {
    const invalidSource = await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: { ...auth(token!), 'Accept-Language': 'en-US,en;q=0.9' },
      data: {
        name: 'E2E 工作流 i18n', status: 'ENABLED',
        definition: { nodes: [
          { id: 'start', type: 'START', name: 'start', next: 'cond' },
          { id: 'cond', type: 'CONDITION', name: 'cond', conditionSource: 'bogus',
            conditionType: 'contains', conditionValue: 'x', trueNext: 'end', falseNext: 'end' },
          { id: 'end', type: 'END', name: 'end' }
        ], edges: [] }
      }
    })
    const invalidBody = await invalidSource.json()
    expect(invalidBody.code).toBe(400)
    expect(String(invalidBody.message)).toContain('Condition source')

    const invalidState = await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: { ...auth(token!), 'Accept-Language': 'en-US,en;q=0.9' },
      data: {
        name: 'E2E 工作流 i18n', status: 'ENABLED',
        definition: { nodes: [
          { id: 'start', type: 'START', name: 'start', next: 'state' },
          { id: 'state', type: 'STATE', name: 'state', valueTemplate: 'x', next: 'end' },
          { id: 'end', type: 'END', name: 'end' }
        ], edges: [] }
      }
    })
    const stateBody = await invalidState.json()
    expect(stateBody.code).toBe(400)
    expect(String(stateBody.message)).toContain('STATE node requires stateKey')
  } finally {
    await request.delete(`${P}/agent/${agentId}/workflow`, { headers: auth(token!) })
    await deleteAgent(request, token!, agentId)
  }
})

test('模型与 Provider 安全删除', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const provider = await (await request.post(`${P}/provider`, {
    headers: auth(token!),
    data: {
      name: `E2E删除Provider-${Date.now()}`,
      providerType: 'OPENAI_COMPATIBLE',
      baseUrl: 'http://127.0.0.1:18092'
    }
  })).json()
  expect(provider.code, JSON.stringify(provider)).toBe(0)
  const providerId = provider.data.id as number
  let modelId: number | null = null
  let agentId: number | null = null
  const modelName = `E2E-delete-model-${Date.now()}`
  try {
    const model = await (await request.post(`${P}/provider/${providerId}/models`, {
      headers: auth(token!),
      data: { modelName, purpose: 'CHAT', contextWindow: 8192 }
    })).json()
    expect(model.code, JSON.stringify(model)).toBe(0)
    modelId = model.data.id

    const listed = await (await request.get(`${P}/provider/models`, { headers: auth(token!) })).json()
    const customModel = (listed.data || []).find((m: any) => m.modelId === modelId)
    expect(customModel?.providerPreset, '租户模型应允许删除').toBe(false)
    const platformModel = (listed.data || []).find((m: any) => m.providerPreset)
    if (platformModel) {
      const platformDelete = await request.delete(`${P}/models/${platformModel.modelId}`, {
        headers: auth(token!)
      })
      expect((await platformDelete.json()).code, '平台预置模型不可删除').toBe(403)
    }

    const referencedAgent = await (await request.post(`${P}/agent`, {
      headers: auth(token!),
      data: {
        name: `E2E删除引用-${Date.now()}`,
        description: '模型删除引用保护',
        systemPrompt: '只回答 OK',
        modelConfig: { modelName },
        visibility: 'PRIVATE'
      }
    })).json()
    expect(referencedAgent.code, JSON.stringify(referencedAgent)).toBe(0)
    agentId = referencedAgent.data.id
    expect((await (await request.delete(`${P}/models/${modelId}`, {
      headers: auth(token!)
    })).json()).code, '被 Agent 引用的模型不可删除').toBe(409)
    await deleteAgent(request, token!, agentId)
    agentId = null

    const blocked = await request.delete(`${P}/provider/${providerId}`, { headers: auth(token!) })
    const blockedBody = await blocked.json()
    expect(blockedBody.code, '仍有模型时 Provider 不能删除').toBe(409)

    expect((await (await request.delete(`${P}/models/${modelId}`, {
      headers: auth(token!)
    })).json()).code).toBe(0)
    modelId = null
    expect((await (await request.delete(`${P}/provider/${providerId}`, {
      headers: auth(token!)
    })).json()).code).toBe(0)

    const providers = await (await request.get(`${P}/provider`, { headers: auth(token!) })).json()
    expect((providers.data || []).some((p: any) => p.id === providerId)).toBe(false)
  } finally {
    if (agentId) await deleteAgent(request, token!, agentId)
    if (modelId) await request.delete(`${P}/models/${modelId}`, { headers: auth(token!) })
    await request.delete(`${P}/provider/${providerId}`, { headers: auth(token!) })
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

test('Agent 调试：未保存配置生效、debug 计费、不写正式 Trace', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()

  const tracesBefore = await (await request.get(`${P}/observability/traces`, {
    headers: auth(token!), params: { pageSize: 1 }
  })).json()
  const usageBefore = await (await request.get(`${P}/billing/usage`, {
    headers: auth(token!), params: { days: 1, pageSize: 100 }
  })).json()
  const debugBefore = (usageBefore.data.list || []).filter((row: any) => row.source === 'debug').length

  const debugRes = await request.post(`${P}/agent/debug/chat`, {
    headers: auth(token!),
    data: {
      config: {
        systemPrompt: 'E2E-DEBUG-UNSAVED-PROMPT，只回答 OK',
        modelConfig: { modelName: 'deepseek-flash', temperature: 0.2 }
      },
      message: '请回答 OK'
    }
  })
  const debug = await debugRes.json()
  expect(debug.code, JSON.stringify(debug)).toBe(0)
  expect(Number(debug.data.usage.inputTokens) + Number(debug.data.usage.outputTokens)).toBeGreaterThan(0)

  // 未保存的调试 systemPrompt 确实被送到模型，而不是读取已保存的 Agent 配置。
  const last = await (await request.get(`${OPENAI_MOCK}/last`)).json()
  expect(JSON.stringify(last.messages || [])).toContain('E2E-DEBUG-UNSAVED-PROMPT')

  const usageAfter = await (await request.get(`${P}/billing/usage`, {
    headers: auth(token!), params: { days: 1, pageSize: 100 }
  })).json()
  const debugAfter = (usageAfter.data.list || []).filter((row: any) => row.source === 'debug').length
  expect(debugAfter, '调试 Token 应写入 source=debug').toBeGreaterThan(debugBefore)

  const tracesAfter = await (await request.get(`${P}/observability/traces`, {
    headers: auth(token!), params: { pageSize: 1 }
  })).json()
  expect(tracesAfter.data.total, '调试不应写正式 Trace').toBe(tracesBefore.data.total)
})


test('Agent 必填校验与配置重新读取', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()

  const blank = await (await request.post(`${P}/agent`, {
    headers: auth(token!),
    data: {
      name: '   ',
      systemPrompt: '有效提示词',
      modelConfig: { modelName: 'deepseek-flash' }
    }
  })).json()
  expect(blank.code, JSON.stringify(blank)).toBe(400)
  expect(blank.message).toContain('名称不能为空')

  const agentId = await createAgent(request, token!, `E2E配置回显-${Date.now()}`)
  try {
    const first = await (await request.get(`${P}/agent/${agentId}`, {
      headers: auth(token!)
    })).json()
    expect(first.code, JSON.stringify(first)).toBe(0)
    expect(first.data.description).toBe('E2E API 集成测试')
    expect(first.data.systemPrompt).toBe('你是测试助手，简洁回答。')
    expect(first.data.modelConfig.modelName).toBe('deepseek-flash')
    expect(first.data.modelConfig.temperature).toBe(0.2)
    expect(first.data.agentCapabilities.functionCall).toBe(true)
    expect(first.data.tools.length).toBeGreaterThan(0)

    const updatedName = `E2E配置回显更新-${Date.now()}`
    const update = await (await request.put(`${P}/agent/${agentId}`, {
      headers: auth(token!),
      data: {
        name: updatedName,
        description: '更新后的描述',
        systemPrompt: '更新后的系统提示词',
        modelConfig: { modelName: 'deepseek-flash', temperature: 0.5 }
      }
    })).json()
    expect(update.code, JSON.stringify(update)).toBe(0)

    const second = await (await request.get(`${P}/agent/${agentId}`, {
      headers: auth(token!)
    })).json()
    expect(second.code, JSON.stringify(second)).toBe(0)
    expect(second.data.name).toBe(updatedName)
    expect(second.data.description).toBe('更新后的描述')
    expect(second.data.systemPrompt).toBe('更新后的系统提示词')
    expect(second.data.modelConfig.modelName).toBe('deepseek-flash')
    expect(second.data.modelConfig.temperature).toBe(0.5)
    expect(second.data.agentCapabilities.functionCall).toBe(true)
    expect(second.data.tools.length).toBeGreaterThan(0)
  } finally {
    await deleteAgent(request, token!, agentId)
  }
})

test('私有 Agent 授权与撤销实时生效', async ({ request }) => {
  const owner = await login(request, 'dev', 'Dev@123456', 'dev')
  const memberLogin = await (await request.post(`${P}/auth/login`, {
    data: { username: 'zhangsan', password: 'Member@123', tenantKey: 'dev' }
  })).json()
  expect(memberLogin.code, JSON.stringify(memberLogin)).toBe(0)
  const member = memberLogin.data.accessToken as string
  const memberId = Number(memberLogin.data.user.id)
  const name = `E2E权限实时-${Date.now()}`
  const agentId = await createAgent(request, owner!, name)
  const visibleToMember = async () => {
    const list = await (await request.get(`${P}/agent`, {
      headers: auth(member), params: { keyword: name, pageSize: 50 }
    })).json()
    expect(list.code, JSON.stringify(list)).toBe(0)
    return (list.data.list || []).some((row: any) => Number(row.id) === agentId)
  }
  try {
    expect(await visibleToMember(), '未授权时私有 Agent 不应出现').toBe(false)

    const grant = await (await request.put(`${P}/agent/${agentId}/grants`, {
      headers: auth(owner!), data: { userIds: [memberId] }
    })).json()
    expect(grant.code, JSON.stringify(grant)).toBe(0)
    expect(await visibleToMember(), '授权后应立即可见').toBe(true)

    const revoke = await (await request.put(`${P}/agent/${agentId}/grants`, {
      headers: auth(owner!), data: { userIds: [] }
    })).json()
    expect(revoke.code, JSON.stringify(revoke)).toBe(0)
    expect(await visibleToMember(), '撤销后应立即不可见').toBe(false)
  } finally {
    await deleteAgent(request, owner!, agentId)
  }
})

test('临时用户密码修改后生效且可清理', async ({ request }) => {
  const admin = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(admin).toBeTruthy()
  const username = `e2e-pw-${Date.now()}`
  const invited = await (await request.post(`${P}/auth/invitations`, {
    headers: auth(admin!),
    data: { email: `${username}@example.com`, role: 'MEMBER' }
  })).json()
  expect(invited.code, JSON.stringify(invited)).toBe(0)
  const token = invited.data.token as string
  const accepted = await (await request.post(`${P}/auth/invitations/accept`, {
    data: { token, username, password: 'TempPass123' }
  })).json()
  expect(accepted.code, JSON.stringify(accepted)).toBe(0)
  const userId = Number(accepted.data.userId)

  try {
    const before = await login(request, username, 'TempPass123', 'dev')
    expect(before, '临时用户旧密码应可登录').toBeTruthy()
    const changed = await (await request.post(`${P}/auth/change-password`, {
      headers: auth(before!),
      data: { oldPassword: 'TempPass123', newPassword: 'TempPass456' }
    })).json()
    expect(changed.code, JSON.stringify(changed)).toBe(0)

    expect(await login(request, username, 'TempPass123', 'dev'), '旧密码应失效').toBeNull()
    const after = await login(request, username, 'TempPass456', 'dev')
    expect(after, '新密码应可登录').toBeTruthy()
  } finally {
    const removed = await (await request.delete(`${P}/users/${userId}`, {
      headers: auth(admin!)
    })).json()
    expect(removed.code, JSON.stringify(removed)).toBe(0)
  }
})
