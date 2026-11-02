import { test, expect, type APIRequestContext } from '@playwright/test'
import path from 'path'
import { createHmac, randomUUID } from 'node:crypto'
import {
  P, MOCK, MCP_MOCK, BACKEND, GATEWAY, OPENAI_MOCK,
  auth, login, createAgent, createAgentWithoutTools, publishAgent, deleteAgent
} from './api-helpers'

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



