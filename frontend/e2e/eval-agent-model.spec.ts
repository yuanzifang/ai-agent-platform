import { test, expect, type Page } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'
const API = process.env.E2E_API_BASE || '/api/v1'
const AGENT_ID = Number(process.env.E2E_AGENT_MODEL_AGENT_ID || 1)
const DATASET_ID = Number(process.env.E2E_AGENT_MODEL_DATASET_ID || 1)

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

test('Agent 任务的 REWRITE 使用 Agent 模型，而不是样本里的平台模型', async ({ page, request }) => {
  test.setTimeout(180_000)
  test.skip(!process.env.E2E_GATE_ENABLED, '设置 E2E_GATE_ENABLED=1 后运行真实任务链路')
  await login(page)
  const token = await page.evaluate(() => localStorage.getItem('accessToken'))
  const headers = { Authorization: `Bearer ${token}` }
  const marker = `E2E_AGENT_MODEL_PRECEDENCE-${Date.now()}`
  let itemId: number | null = null

  try {
    const agentRes = await request.get(`${API}/agent/${AGENT_ID}`, { headers })
    expect(agentRes.ok()).toBeTruthy()
    const agentModel = (await agentRes.json()).data.modelConfig.modelName
    expect(agentModel).toBeTruthy()

    const added = await request.post(`${API}/eval/datasets/${DATASET_ID}/items`, {
      headers,
      data: {
        question: marker,
        expectedAnswer: '',
        scoreCriteria: '',
        stepType: 'REWRITE',
        expectedPayload: '{"must_keep":["退款"]}',
        stepConfig: '{"model":"qwen2.5:7b"}'
      }
    })
    expect(added.ok()).toBeTruthy()

    const items = await request.get(`${API}/eval/datasets/${DATASET_ID}/items`, { headers })
    itemId = (await items.json()).data.find((r: any) => r.question === marker)?.id || null
    expect(itemId).toBeTruthy()

    const created = await request.post(`${API}/eval/tasks`, {
      headers,
      data: {
        name: `E2E Agent模型优先级-${Date.now()}`,
        agentId: AGENT_ID,
        datasetId: DATASET_ID,
        judgeModel: 'deepseek-flash',
        passThreshold: 0.6
      }
    })
    expect(created.ok()).toBeTruthy()
    const taskId = (await created.json()).data.taskId
    expect(taskId).toBeTruthy()

    let detail: any = null
    for (let i = 0; i < 120; i++) {
      const res = await request.get(`${API}/eval/tasks/${taskId}`, { headers })
      detail = (await res.json()).data
      if (detail.status === 'DONE' || detail.status === 'FAILED') break
      await page.waitForTimeout(500)
    }
    expect(detail?.status).toBe('DONE')
    const row = (detail.results || []).find((r: any) => r.question === marker)
    expect(row).toBeTruthy()
    expect(row.stepType).toBe('REWRITE')
    expect(row.stepDetails).toContain(agentModel)
    expect(row.stepDetails).not.toContain('qwen2.5:7b')
  } finally {
    if (itemId != null) {
      await request.delete(`${API}/eval/datasets/items/${itemId}`, { headers }).catch(() => {})
    }
  }
})
