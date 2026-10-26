import { test, expect, type Page } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'
const AGENT_ID = Number(process.env.E2E_GATE_AGENT_ID || 1)

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
    const datasetsRes = await request.get('/api/v1/eval/datasets', { headers })
    expect(datasetsRes.ok()).toBeTruthy()
    const datasets = (await datasetsRes.json()).data as any[]
    const dataset = datasets.find((d) => d.agentId === AGENT_ID)
      || datasets.find((d) => Array.isArray(d.items) && d.items.length > 0)
      || datasets[0]
    expect(dataset?.id).toBeTruthy()
    datasetId = dataset.id

    const added = await request.post(`/api/v1/eval/datasets/${datasetId}/items`, {
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

    const before = await request.get(`/api/v1/eval/datasets/${datasetId}/items`, { headers })
    const beforeRows = (await before.json()).data as any[]
    itemId = beforeRows.find((r) => r.stepType === 'RETRIEVAL'
      && r.expectedPayload?.includes('999999999'))?.id || null
    expect(itemId).toBeTruthy()

    const publish = await request.post(`/api/v1/agent/${AGENT_ID}/publish`, {
      headers,
      data: { gateDatasetId: datasetId }
    })
    expect(publish.status()).toBe(400)
    const publishBody = await publish.json()
    expect(publishBody.message).toContain('预发布测评未通过')

    const tasksRes = await request.get(`/api/v1/eval/tasks?agentId=${AGENT_ID}`, { headers })
    const tasks = (await tasksRes.json()).data.list as any[]
    const gateTask = tasks
      .filter((t) => t.evalType === 'PRE_RELEASE' && t.datasetId === datasetId)
      .sort((a, b) => b.id - a.id)[0]
    expect(gateTask?.id).toBeTruthy()

    const detailRes = await request.get(`/api/v1/eval/tasks/${gateTask.id}`, { headers })
    const detail = (await detailRes.json()).data
    expect(detail.stepSummary.RETRIEVAL.count).toBeGreaterThan(0)
    expect(detail.stepSummary.RETRIEVAL.passRate).toBe(0)
    expect(detail.results.some((r: any) => r.stepType === 'RETRIEVAL'
      && r.stepPassed === false)).toBeTruthy()
  } finally {
    if (itemId != null) {
      await request.delete(`/api/v1/eval/datasets/items/${itemId}`, { headers }).catch(() => {})
    }
  }
})
