import { test, expect, type Page } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'

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

test('步骤级评测样本：步骤类型/期望 JSON 可见并可编辑', async ({ page, request }) => {
  await login(page)
  const token = await page.evaluate(() => localStorage.getItem('accessToken'))
  const headers = { Authorization: `Bearer ${token}` }
  const name = `E2E步骤数据集-${Date.now()}`
  let datasetId: number | null = null

  try {
    const created = await request.post('/api/v1/eval/datasets', {
      headers,
      data: { name, description: '步骤级 E2E' }
    })
    expect(created.ok()).toBeTruthy()
    datasetId = (await created.json()).data.id

    const added = await request.post(`/api/v1/eval/datasets/${datasetId}/items`, {
      headers,
      data: {
        question: '用户说退款太慢，帮我改写查询',
        expectedAnswer: '',
        scoreCriteria: '',
        stepType: 'REWRITE',
        expectedPayload: '{"must_keep":["退款"]}',
        stepConfig: '{"maxChars":120}'
      }
    })
    expect(added.ok()).toBeTruthy()

    await page.goto('/eval')
    await page.getByText('数据集管理', { exact: true }).click()
    const row = page.locator('.el-table__row').filter({ hasText: name })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '样本管理' }).click()

    const drawer = page.locator('.el-drawer:visible')
    await expect(drawer).toContainText(name)
    await expect(drawer.locator('.el-table__row').filter({ hasText: 'REWRITE' })).toBeVisible()
    const rewriteRow = drawer.locator('.el-table__row').filter({ hasText: 'REWRITE' })
    await expect(rewriteRow).toContainText('must_keep')
    await expect(rewriteRow).toContainText('退款')
    await expect(drawer.locator('.step-coverage')).toContainText('步骤覆盖')
    await expect(drawer.locator('.step-coverage')).toContainText('REWRITE 1')
    await expect(drawer.locator('.step-coverage')).toContainText('RETRIEVAL 0')

    // 通过 UI 新增一个检索步骤，确认步骤类型与期望 JSON 会随表单提交。
    await drawer.getByPlaceholder('问题').fill('UI 新增检索样本')
    await drawer.locator('.item-form .el-select').click()
    await page.getByRole('option', { name: 'RETRIEVAL' }).click()
    await drawer.getByPlaceholder('expectedPayload JSON，如 {"chunk_ids":[1,2]}')
      .fill('{"chunk_ids":[11,12]}')
    await drawer.getByPlaceholder('stepConfig JSON，如 {"knowledgeBaseId":1,"topK":5}')
      .fill('{"knowledgeBaseId":1}')
    await drawer.getByRole('button', { name: '添加样本' }).click()
    await expect(drawer.locator('.el-table__row').filter({ hasText: 'UI 新增检索样本' })).toBeVisible()
    await expect(drawer.locator('.el-table__row').filter({ hasText: 'RETRIEVAL' })).toBeVisible()

    const items = await request.get(`/api/v1/eval/datasets/${datasetId}/items`, { headers })
    const rows = (await items.json()).data
    expect(rows.some((row: any) => row.stepType === 'REWRITE'
      && row.expectedPayload?.includes('must_keep'))).toBeTruthy()
    expect(rows.some((row: any) => row.stepType === 'RETRIEVAL'
      && row.expectedPayload?.includes('chunk_ids'))).toBeTruthy()

    await drawer.getByRole('button', { name: '关闭此对话框' }).click()
    await page.getByRole('button', { name: '单步试跑' }).click()
    const stepDialog = page.getByRole('dialog', { name: '单步评测' })
    await expect(stepDialog).toBeVisible()
    await expect(stepDialog.locator('.el-select')).toContainText('REWRITE')
    await expect(stepDialog.getByText('仅本地规则改写')).toBeVisible()
    await stepDialog.locator('.el-switch').click()
    await expect(stepDialog.locator('.el-switch')).toHaveClass(/is-checked/)
    await stepDialog.locator('.el-select').click()
    await expect(page.getByRole('option', { name: /RETRIEVAL/ })).toBeVisible()
    await expect(page.getByRole('option', { name: /ANSWER/ })).toBeVisible()
    await page.getByRole('option', { name: /ANSWER/ }).click()
    await expect(stepDialog).toContainText('选择 Agent')
    await stepDialog.getByRole('button', { name: '取消' }).click()
  } finally {
    if (datasetId != null) {
      await request.delete(`/api/v1/eval/datasets/${datasetId}`, { headers }).catch(() => {})
    }
  }
})

test('本地模型 REWRITE：真实 Ollama 返回可用改写', async ({ page, request }) => {
  test.setTimeout(180_000)
  test.skip(!process.env.E2E_LOCAL_REWRITE, '设置 E2E_LOCAL_REWRITE=1 后运行本地模型验收')
  await login(page)
  const model = process.env.E2E_LOCAL_REWRITE_MODEL || 'qwen3.6:27b'
  const token = await page.evaluate(() => localStorage.getItem('accessToken'))
  const models = await request.get('/api/v1/provider/models?purpose=CHAT', {
    headers: { Authorization: `Bearer ${token}` }
  })
  const chatModels = (await models.json()).data as any[]
  expect(chatModels.some((m) => m.modelName === model)).toBe(false)
  const res = await request.post('/api/v1/eval/steps/run', {
    headers: { Authorization: `Bearer ${token}` },
    timeout: 180_000,
    data: {
      stepType: 'REWRITE',
      query: '用户说退款太慢，帮我改写查询',
      expectedPayload: '{"must_keep":["退款","慢"]}',
      stepConfig: JSON.stringify({ model, instructions: '保留退款、慢等实体，只做必要消歧' })
    }
  })
  if (!res.ok()) console.log('LOCAL-REWRITE-ERROR', res.status(), await res.text())
  expect(res.ok()).toBeTruthy()
  const body = (await res.json()).data
  expect(body.actual).toBeTruthy()
  expect(body.actual).toContain('退款')
  expect(body.score).toBeGreaterThanOrEqual(0.8)
  expect(body.passed).toBe(true)
  expect(body.details.localOnly).toBe(false)
  console.log(`LOCAL-REWRITE model=${model} score=${body.score} actual=${body.actual}`)
})

test('ANSWER 单步：选择 Agent 后执行回答与裁判', async ({ page, request }) => {
  test.skip(!process.env.E2E_ANSWER_STEP, '设置 E2E_ANSWER_STEP=1 后运行 ANSWER 单步链路')
  await login(page)
  const token = await page.evaluate(() => localStorage.getItem('accessToken'))
  const res = await request.post('/api/v1/eval/steps/run', {
    headers: { Authorization: `Bearer ${token}` },
    timeout: 180_000,
    data: {
      stepType: 'ANSWER',
      agentId: 1,
      query: '只回答OK',
      expectedPayload: '{"expectedAnswer":"OK"}',
      stepConfig: '{"judgeModel":"qwen2.5:7b"}'
    }
  })
  expect(res.ok()).toBeTruthy()
  const body = (await res.json()).data
  expect(body.stepType).toBe('ANSWER')
  expect(body.actual).toBeTruthy()
  expect(body.details.model).toBeTruthy()
  expect(body.details.judgeModel).toBe('qwen2.5:7b')
  expect(body.score).toBeGreaterThanOrEqual(0)
  console.log(`ANSWER-STEP model=${body.details.model} judge=${body.details.judgeModel} score=${body.score} reason=${body.details.reason} actual=${body.actual}`)
})
