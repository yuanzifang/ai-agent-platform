import { test, expect, type APIRequestContext } from '@playwright/test'
import path from 'path'
import { createHmac, randomUUID } from 'node:crypto'
import {
  P, MOCK, MCP_MOCK, BACKEND, GATEWAY, OPENAI_MOCK,
  auth, login, createAgent, createAgentWithoutTools, publishAgent, deleteAgent
} from './api-helpers'

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

