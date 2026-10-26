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
  } finally {
    if (datasetId != null) {
      await request.delete(`/api/v1/eval/datasets/${datasetId}`, { headers }).catch(() => {})
    }
  }
})
