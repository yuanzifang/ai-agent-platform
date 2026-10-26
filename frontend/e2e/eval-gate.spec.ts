import { test, expect, type Page } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'
const AGENT_ID = Number(process.env.E2E_GATE_AGENT_ID || 1)
const API = process.env.E2E_API_BASE || '/api/v1'

async function login(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(USER)
  await page.getByPlaceholder('密码').fill(PASSWORD)
  await page.getByRole('button', { name: '登 录' }).click()
  await page.waitForURL(/\/agents/, { timeout: 20_000 })
  await page.waitForLoadState('networkidle')
  await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')), null,
    { timeout: 20_000 })
}

test('真实发布门禁：检索步骤失败阻断发布并保留分步明细', async ({ page, request }) => {
  test.skip(!process.env.E2E_GATE_ENABLED, '设置 E2E_GATE_ENABLED=1 后运行真实门禁链路')
  await login(page)
  const token = await page.evaluate(() => localStorage.getItem('accessToken'))
  const headers = { Authorization: `Bearer ${token}` }
  let datasetId: number | null = null
  let itemId: number | null = null

  try {
    const datasetsRes = await request.get(`${API}/eval/datasets`, { headers })
    expect(datasetsRes.ok()).toBeTruthy()
    const datasets = (await datasetsRes.json()).data as any[]
    const dataset = datasets.find((d) => d.agentId === AGENT_ID)
      || datasets.find((d) => Array.isArray(d.items) && d.items.length > 0)
      || datasets[0]
    expect(dataset?.id).toBeTruthy()
    datasetId = dataset.id

    const added = await request.post(`${API}/eval/datasets/${datasetId}/items`, {
      headers,
      data: {
        question: `E2E门禁不可命中检索-${Date.now()}`,
        expectedAnswer: '',
        scoreCriteria: '',
        stepType: 'RETRIEVAL',
        expectedPayload: '{"chunk_ids":[999999999]}',
        stepConfig: '{"knowledgeBaseId":1,"topK":5}'
      }
    })
    expect(added.ok()).toBeTruthy()

    const before = await request.get(`${API}/eval/datasets/${datasetId}/items`, { headers })
    const beforeRows = (await before.json()).data as any[]
    itemId = beforeRows.find((r) => r.stepType === 'RETRIEVAL'
      && r.expectedPayload?.includes('999999999'))?.id || null
    expect(itemId).toBeTruthy()

    const publish = await request.post(`${API}/agent/${AGENT_ID}/publish`, {
      headers,
      data: { gateDatasetId: datasetId }
    })
    expect(publish.status()).toBe(400)
    const publishBody = await publish.json()
    expect(publishBody.message).toContain('预发布测评未通过')
    const preview = await request.post(`${API}/agent/${AGENT_ID}/gate-preview`, { headers, data: {} })
    expect(preview.ok()).toBeTruthy()
    const previewGate = (await preview.json()).data
    expect(previewGate.stepGate.failedSteps).toContain('RETRIEVAL')
    expect(previewGate.stepGate.failures.some((f: any) => f.stepType === 'RETRIEVAL')).toBeTruthy()

    const tasksRes = await request.get(`${API}/eval/tasks?agentId=${AGENT_ID}`, { headers })
    const tasks = (await tasksRes.json()).data.list as any[]
    const gateTask = tasks
      .filter((t) => t.evalType === 'PRE_RELEASE' && t.datasetId === datasetId)
      .sort((a, b) => b.id - a.id)[0]
    expect(gateTask?.id).toBeTruthy()

    const detailRes = await request.get(`${API}/eval/tasks/${gateTask.id}`, { headers })
    const detail = (await detailRes.json()).data
    expect(detail.stepSummary.RETRIEVAL.count).toBeGreaterThan(0)
    expect(detail.stepSummary.RETRIEVAL.passRate).toBe(0)
    expect(detail.stepGate.failedSteps).toContain('RETRIEVAL')
    expect(detail.stepGate.failures.some((f: any) => f.stepType === 'RETRIEVAL')).toBeTruthy()
    expect(detail.results.some((r: any) => r.stepType === 'RETRIEVAL'
      && r.stepPassed === false)).toBeTruthy()
  } finally {
    if (itemId != null) {
      await request.delete(`${API}/eval/datasets/items/${itemId}`, { headers }).catch(() => {})
    }
  }
})

test('真实 ANSWER 裁判门禁：低分回答阻断发布并标记 ANSWER 失败', async ({ page, request }) => {
  test.skip(!process.env.E2E_GATE_ENABLED, '设置 E2E_GATE_ENABLED=1 后运行真实门禁链路')
  await login(page)
  const token = await page.evaluate(() => localStorage.getItem('accessToken'))
  const headers = { Authorization: `Bearer ${token}` }
  let itemId: number | null = null

  try {
    const datasetsRes = await request.get(`${API}/eval/datasets`, { headers })
    const datasets = (await datasetsRes.json()).data as any[]
    const dataset = datasets.find((d) => d.agentId === AGENT_ID) || datasets[0]
    expect(dataset?.id).toBeTruthy()

    const added = await request.post(`${API}/eval/datasets/${dataset.id}/items`, {
      headers,
      data: {
        question: `E2E_LOW_SCORE 请介绍退款政策-${Date.now()}`,
        expectedAnswer: '应说明退款条件和时效',
        scoreCriteria: '回答需与期望答案一致',
        stepType: 'ANSWER'
      }
    })
    expect(added.ok()).toBeTruthy()

    const before = await request.get(`${API}/eval/datasets/${dataset.id}/items`, { headers })
    const beforeRows = (await before.json()).data as any[]
    itemId = beforeRows.find((r) => r.question?.startsWith('E2E_LOW_SCORE'))?.id || null
    expect(itemId).toBeTruthy()

    const publish = await request.post(`${API}/agent/${AGENT_ID}/publish`, {
      headers,
      data: { gateDatasetId: dataset.id }
    })
    expect(publish.status()).toBe(400)
    expect((await publish.json()).message).toContain('预发布测评未通过')
    const preview = await request.post(`${API}/agent/${AGENT_ID}/gate-preview`, { headers, data: {} })
    expect(preview.ok()).toBeTruthy()
    expect((await preview.json()).data.stepGate.failedSteps).toContain('ANSWER')

    const tasksRes = await request.get(`${API}/eval/tasks?agentId=${AGENT_ID}`, { headers })
    const tasks = (await tasksRes.json()).data.list as any[]
    const gateTask = tasks
      .filter((t) => t.evalType === 'PRE_RELEASE' && t.datasetId === dataset.id)
      .sort((a, b) => b.id - a.id)[0]
    const detailRes = await request.get(`${API}/eval/tasks/${gateTask.id}`, { headers })
    const detail = (await detailRes.json()).data
    expect(detail.stepSummary.ANSWER.count).toBeGreaterThan(0)
    expect(detail.stepSummary.ANSWER.passRate).toBeLessThan(0.8)
    expect(detail.stepGate.failedSteps).toContain('ANSWER')
    expect(detail.results.some((r: any) => r.stepType === 'ANSWER'
      && r.stepPassed === false && r.judgeReason?.includes('强制低分'))).toBeTruthy()
  } finally {
    if (itemId != null) {
      await request.delete(`${API}/eval/datasets/items/${itemId}`, { headers }).catch(() => {})
    }
  }
})
