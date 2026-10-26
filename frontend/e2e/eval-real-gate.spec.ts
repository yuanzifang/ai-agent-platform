import { test, expect, type Page } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'
const API = process.env.E2E_API_BASE || '/api/v1'
const AGENT_ID = Number(process.env.E2E_REAL_AGENT_ID || 1)
const DATASET_ID = Number(process.env.E2E_REAL_DATASET_ID || 1)

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

test('真实模型/真实裁判门禁：ANSWER 走 Agent 模型，门禁裁判走本地 qwen', async ({ page, request }) => {
  test.setTimeout(300_000)
  test.skip(!process.env.E2E_REAL_MODEL, '设置 E2E_REAL_MODEL=1 后运行真实模型链路')
  await login(page)
  const token = await page.evaluate(() => localStorage.getItem('accessToken'))
  const headers = { Authorization: `Bearer ${token}` }
  const answerMarker = `E2E_REAL_ANSWER-${Date.now()}`
  const retrievalMarker = `E2E_REAL_RETRIEVAL-${Date.now()}`
  let answerItemId: number | null = null
  let retrievalItemId: number | null = null

  try {
    const agentRes = await request.get(`${API}/agent/${AGENT_ID}`, { headers })
    expect(agentRes.ok()).toBeTruthy()
    const agentModel = (await agentRes.json()).data.modelConfig.modelName
    expect(agentModel).toBeTruthy()

    for (const payload of [
      {
        question: answerMarker,
        expectedAnswer: '请说明退款条件和时效',
        scoreCriteria: '回答需切题、诚实、简洁',
        stepType: 'ANSWER'
      },
      {
        question: retrievalMarker,
        expectedAnswer: '',
        scoreCriteria: '',
        stepType: 'RETRIEVAL',
        expectedPayload: '{"chunk_ids":[999999999]}',
        stepConfig: '{"knowledgeBaseId":1,"topK":5}'
      }
    ]) {
      const added = await request.post(`${API}/eval/datasets/${DATASET_ID}/items`, {
        headers, data: payload
      })
      expect(added.ok()).toBeTruthy()
    }

    const items = await request.get(`${API}/eval/datasets/${DATASET_ID}/items`, { headers })
    const rows = (await items.json()).data as any[]
    answerItemId = rows.find((r) => r.question === answerMarker)?.id || null
    retrievalItemId = rows.find((r) => r.question === retrievalMarker)?.id || null
    expect(answerItemId).toBeTruthy()
    expect(retrievalItemId).toBeTruthy()

    const publish = await request.post(`${API}/agent/${AGENT_ID}/publish`, {
      headers, data: { gateDatasetId: DATASET_ID }, timeout: 300_000
    })
    expect(publish.status()).toBe(400)
    expect((await publish.json()).message).toContain('预发布测评未通过')

    const tasksRes = await request.get(`${API}/eval/tasks?agentId=${AGENT_ID}`, { headers })
    const tasks = (await tasksRes.json()).data.list as any[]
    const gateTask = tasks
      .filter((t) => t.evalType === 'PRE_RELEASE' && t.datasetId === DATASET_ID)
      .sort((a, b) => b.id - a.id)[0]
    expect(gateTask?.id).toBeTruthy()

    const detailRes = await request.get(`${API}/eval/tasks/${gateTask.id}`, { headers })
    const detail = (await detailRes.json()).data
    expect(detail.status).toBe('DONE')
    const answer = (detail.results || []).find((r: any) => r.question === answerMarker)
    expect(answer).toBeTruthy()
    expect(answer.answer).toBeTruthy()
    expect(answer.judgeReason).toBeTruthy()
    expect(String(answer.judgeReason)).not.toContain('mock')
    expect(detail.stepGate.failedSteps).toContain('RETRIEVAL')
    console.log(`REAL-GATE agentModel=${agentModel} answer=${answer.answer} reason=${answer.judgeReason}`)
  } finally {
    for (const id of [answerItemId, retrievalItemId]) {
      if (id != null) {
        await request.delete(`${API}/eval/datasets/items/${id}`, { headers }).catch(() => {})
      }
    }
  }
})
