import { test, expect, type APIRequestContext } from '@playwright/test'
import path from 'path'
import { createHmac, randomUUID } from 'node:crypto'
import {
  P, MOCK, MCP_MOCK, BACKEND, GATEWAY, OPENAI_MOCK,
  auth, login, createAgent, createAgentWithoutTools, publishAgent, deleteAgent
} from './api-helpers'

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
    const ok = await request.get(`${GATEWAY}/${route.routeKey}/health`, {
      headers: { 'X-Gateway-Key': route.apiKey }
    })
    expect(ok.status()).toBe(200)
    expect(await ok.text()).toContain('UP')

    const denied = await request.get(`${GATEWAY}/${route.routeKey}/health`, {
      headers: { 'X-Gateway-Key': 'gw_invalid' }
    })
    expect(denied.status()).toBe(401)

    const wafBlocked = await request.get(
      `${GATEWAY}/${route.routeKey}/health?q=1%20union%20select%20password%20from%20users`, {
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
      expect((await request.get(`${GATEWAY}/${signedRoute.routeKey}/always-ok`, { headers })).status()).toBe(200)
      expect((await request.get(`${GATEWAY}/${signedRoute.routeKey}/always-ok`, {
        headers: { ...headers, 'X-Gateway-Signature': '0'.repeat(64) }
      })).status()).toBe(401)
      expect((await request.get(`${GATEWAY}/${signedRoute.routeKey}/always-ok`, { headers })).status()).toBe(409)
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
      const scopeOk = await request.get(`${GATEWAY}/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': scopedKey.data.apiKey }
      })
      expect(scopeOk.status()).toBe(200)
      const scopeMethod = await request.post(`${GATEWAY}/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': scopedKey.data.apiKey }, data: {}
      })
      expect(scopeMethod.status()).toBe(403)
      const scopePath = await request.get(`${GATEWAY}/${scopeRoute.data.routeKey}/other`, {
        headers: { 'X-Gateway-Key': scopedKey.data.apiKey }
      })
      expect(scopePath.status()).toBe(403)

      const dailyKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!),
        data: { name: 'daily', dailyQuota: 1 }
      })).json()
      const dailyFirst = await request.get(`${GATEWAY}/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': dailyKey.data.apiKey }
      })
      expect(dailyFirst.status()).toBe(200)
      const dailySecond = await request.get(`${GATEWAY}/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': dailyKey.data.apiKey }
      })
      expect(dailySecond.status()).toBe(429)

      const ipDeniedKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!), data: { name: 'ip-denied', allowedIps: '10.0.0.0/8' }
      })).json()
      const ipDenied = await request.get(`${GATEWAY}/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': ipDeniedKey.data.apiKey }
      })
      expect(ipDenied.status()).toBe(403)

      const ipOkKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!), data: { name: 'ip-ok', allowedIps: '127.0.0.1' }
      })).json()
      const ipOk = await request.get(`${GATEWAY}/${scopeRoute.data.routeKey}/always-ok`, {
        headers: { 'X-Gateway-Key': ipOkKey.data.apiKey }
      })
      expect(ipOk.status()).toBe(200)

      const bodyKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!), data: { name: 'body-limit', maxBodyBytes: 10 }
      })).json()
      const bodyTooLarge = await request.post(`${GATEWAY}/${scopeRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': bodyKey.data.apiKey }, data: { name: 'ok' }
      })
      expect(bodyTooLarge.status()).toBe(413)
      const bodySmall = await request.post(`${GATEWAY}/${scopeRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': bodyKey.data.apiKey }, data: {}
      })
      expect(bodySmall.status()).toBe(200)

      const clearKey = await (await request.post(`${P}/gateway/routes/${scopeRoute.data.id}/keys`, {
        headers: auth(token!), data: { name: 'clear-scope', allowedMethods: 'GET' }
      })).json()
      const blockedPost = await request.post(`${GATEWAY}/${scopeRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': clearKey.data.apiKey }, data: {}
      })
      expect(blockedPost.status()).toBe(403)
      await request.put(`${P}/gateway/routes/${scopeRoute.data.id}/keys/${clearKey.data.id}`, {
        headers: auth(token!), data: { allowedMethods: '' }
      })
      const allowedPost = await request.post(`${GATEWAY}/${scopeRoute.data.routeKey}/probe`, {
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
      const invalid = await request.post(`${GATEWAY}/${schemaRoute.data.routeKey}/probe`, {
        headers: { 'X-Gateway-Key': schemaRoute.data.apiKey },
        data: { name: 123 }
      })
      expect(invalid.status()).toBe(400)
      expect(await invalid.text()).toContain('Schema')
      const valid = await request.post(`${GATEWAY}/${schemaRoute.data.routeKey}/probe`, {
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
    const oldKey = await request.get(`${GATEWAY}/${route.routeKey}/health`, {
      headers: { 'X-Gateway-Key': route.apiKey }
    })
    expect(oldKey.status()).toBe(401)
    const newKey = await request.get(`${GATEWAY}/${route.routeKey}/health`, {
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
      const sse = await request.get(`${GATEWAY}/${sseRoute.data.routeKey}/gateway-sse`, {
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
        const cb = await request.get(`${GATEWAY}/${cbRoute.data.routeKey}/probe`, {
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
      const viaFallback = await request.get(`${GATEWAY}/${fallbackRoute.data.routeKey}/health`, {
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
      const first = await request.get(`${GATEWAY}/${limitedRoute.data.routeKey}/health`, {
        headers: { 'X-Gateway-Key': limitedRoute.data.apiKey }
      })
      expect(first.status()).toBe(200)
      const second = await request.get(`${GATEWAY}/${limitedRoute.data.routeKey}/health`, {
        headers: { 'X-Gateway-Key': limitedRoute.data.apiKey }
      })
      expect(second.status()).toBe(429)
    } finally {
      await request.delete(`${P}/gateway/routes/${limitedRoute.data.id}`, { headers: auth(token!) })
    }

    await request.put(`${P}/gateway/routes/${route.id}`, { headers: auth(token!), data: { enabled: false } })
    const disabled = await request.get(`${GATEWAY}/${route.routeKey}/health`, {
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
  const admin = await login(request, 'admin', process.env.PLATFORM_ADMIN_PASSWORD || 'Admin@123456')
  expect(admin).toBeTruthy()
  const config = await (await request.get(`${P}/admin/config`, { headers: auth(admin!) })).json()
  const oldGraph = (config.data || []).find((row: any) => row.configKey === 'knowledge.graph.retrieval-enabled')?.configValue || ''
  await request.put(`${P}/admin/config`, {
    headers: auth(admin!),
    data: { configKey: 'knowledge.graph.retrieval-enabled', configValue: 'true' }
  })
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
    await request.put(`${P}/admin/config`, {
      headers: auth(admin!),
      data: { configKey: 'knowledge.graph.retrieval-enabled', configValue: oldGraph }
    }).catch(() => {})
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

test('Agent 发布版本快照与回滚', async ({ request }) => {
  const token = await login(request, 'dev', 'Dev@123456', 'dev')
  expect(token).toBeTruthy()
  const originalName = `E2E版本回滚-${Date.now()}`
  const agentId = await createAgent(request, token!, originalName)
  try {
    await publishAgent(request, token!, agentId)
    const first = await (await request.get(`${P}/agent/${agentId}/versions`, {
      headers: auth(token!)
    })).json()
    expect(first.code, JSON.stringify(first)).toBe(0)
    expect(first.data.map((v: any) => v.version)).toContain('V1')

    const updatedName = `${originalName}-v2`
    const update = await (await request.put(`${P}/agent/${agentId}`, {
      headers: auth(token!),
      data: { name: updatedName, systemPrompt: '第二版提示词' }
    })).json()
    expect(update.code, JSON.stringify(update)).toBe(0)
    await publishAgent(request, token!, agentId)

    const second = await (await request.get(`${P}/agent/${agentId}/versions`, {
      headers: auth(token!)
    })).json()
    expect(second.data.map((v: any) => v.version)).toEqual(expect.arrayContaining(['V1', 'V2']))
    const secondDetail = await (await request.get(`${P}/agent/${agentId}`, {
      headers: auth(token!)
    })).json()
    expect(secondDetail.data.name).toBe(updatedName)
    expect(secondDetail.data.systemPrompt).toBe('第二版提示词')

    const rollback = await (await request.post(`${P}/agent/${agentId}/versions/V1/rollback`, {
      headers: auth(token!), data: {}
    })).json()
    expect(rollback.code, JSON.stringify(rollback)).toBe(0)
    expect(rollback.data.rolledBackFrom).toBe('V1')
    expect(rollback.data.version).toBe('V3')

    const restored = await (await request.get(`${P}/agent/${agentId}`, {
      headers: auth(token!)
    })).json()
    expect(restored.data.name).toBe(originalName)
    expect(restored.data.systemPrompt).toBe('你是测试助手，简洁回答。')

    const versions = await (await request.get(`${P}/agent/${agentId}/versions`, {
      headers: auth(token!)
    })).json()
    expect(versions.data.map((v: any) => v.version)).toEqual(expect.arrayContaining(['V1', 'V2', 'V3']))
  } finally {
    await deleteAgent(request, token!, agentId)
  }
})
