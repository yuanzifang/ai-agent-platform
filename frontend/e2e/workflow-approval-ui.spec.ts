import { test, expect, type APIRequestContext, type Page } from '@playwright/test'

const P = '/api/v1'
const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'

function auth(token: string) {
  return { Authorization: `Bearer ${token}` }
}

async function login(request: APIRequestContext, username: string, password: string, tenantKey?: string) {
  const res = await request.post(`${P}/auth/login`, {
    data: { username, password, tenantKey }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.accessToken as string
}

async function pageLogin(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(USER)
  await page.getByPlaceholder('密码').fill(PASSWORD)
  await page.getByRole('button', { name: '登 录' }).click()
  await page.waitForURL(/\/agents/, { timeout: 20_000 })
  await page.waitForLoadState('networkidle')
  await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')), null,
    { timeout: 20_000 })
}

test('审批箱可通过工作流审批并继续运行', async ({ page, request }) => {
  const token = await login(request, USER, PASSWORD, 'dev')
  const admin = await login(request, 'admin', process.env.PLATFORM_ADMIN_PASSWORD || 'Admin@123456')
  const title = `UI审批-${Date.now()}`
  const created = await request.post(`${P}/agent`, {
    headers: auth(token),
    data: {
      name: `E2E审批箱-${Date.now()}`,
      description: '审批箱 UI E2E',
      systemPrompt: '审批工作流测试。',
      modelConfig: { modelName: 'deepseek-flash' },
      agentCapabilities: { functionCall: false },
      tools: [],
      visibility: 'TENANT'
    }
  })
  const agentBody = await created.json()
  expect(agentBody.code, JSON.stringify(agentBody)).toBe(0)
  const agentId = agentBody.data.id as number
  let runId: number | null = null
  try {
    expect((await (await request.post(`${P}/agent/${agentId}/publish`, {
      headers: auth(token), data: { force: true }
    })).json()).code).toBe(0)
    expect((await (await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token),
      data: {
        name: 'E2E 审批 UI', status: 'ENABLED',
        definition: {
          nodes: [
            { id: 'start', type: 'START', name: '开始', next: 'approval' },
            { id: 'approval', type: 'APPROVAL', name: '审批节点', title,
              requiredRole: 'OWNER', timeoutMinutes: 60, next: 'done' },
            { id: 'done', type: 'STATE', name: '审批后状态', stateKey: 'approved',
              valueTemplate: 'yes', next: 'end' },
            { id: 'end', type: 'END', name: '结束' }
          ], edges: []
        }
      }
    })).json()).code).toBe(0)
    expect((await (await request.post(`${P}/agent/${agentId}/workflow/publish`, {
      headers: auth(token), data: {}
    })).json()).code).toBe(0)

    // 用管理员发起运行，确保待审批人不是发起人；开发者 OWNER 可在审批箱处理。
    const waiting = await (await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: { ...auth(admin), 'X-Tenant-Id': '1' },
      data: { input: '需要审批' }, timeout: 60_000
    })).json()
    expect(waiting.code, JSON.stringify(waiting)).toBe(0)
    expect(waiting.data.status).toBe('WAITING_APPROVAL')
    runId = waiting.data.id

    await pageLogin(page)
    await page.goto('/approvals')
    await page.waitForLoadState('networkidle')
    const row = page.locator('.el-table__row').filter({ hasText: title }).first()
    await expect(row).toBeVisible({ timeout: 20_000 })
    await row.getByRole('button', { name: '通过', exact: true }).click()
    const dialog = page.locator('.el-dialog:visible').filter({
      has: page.locator('.el-dialog__title', { hasText: '通过审批' })
    })
    await expect(dialog).toBeVisible()
    await dialog.locator('textarea').fill('UI 审批通过')
    await dialog.getByRole('button', { name: '确认通过' }).click()
    await expect(page.getByText(/已通过，运行状态：/)).toBeVisible({ timeout: 20_000 })

    const detail = await (await request.get(`${P}/workflow/runs/${runId}`, {
      headers: auth(token)
    })).json()
    expect(detail.code, JSON.stringify(detail)).toBe(0)
    expect(detail.data.status).toBe('SUCCESS')
    expect(detail.data.state?.approved).toBe('yes')
    expect((detail.data.nodeRuns || []).some((x: any) => x.name === '审批节点')).toBe(true)

    // 再发起一次运行，验证审批箱拒绝路径与拒绝原因必填
    const rejectWaiting = await (await request.post(`${P}/agent/${agentId}/workflow/run`, {
      headers: { ...auth(admin), 'X-Tenant-Id': '1' },
      data: { input: '需要拒绝审批' }, timeout: 60_000
    })).json()
    expect(rejectWaiting.code, JSON.stringify(rejectWaiting)).toBe(0)
    expect(rejectWaiting.data.status).toBe('WAITING_APPROVAL')
    runId = rejectWaiting.data.id

    await page.getByRole('button', { name: '刷新', exact: true }).click()
    const rejectRow = page.locator('.el-table__row').filter({ hasText: title }).first()
    await expect(rejectRow).toBeVisible({ timeout: 20_000 })
    await rejectRow.getByRole('button', { name: '拒绝', exact: true }).click()
    const rejectDialog = page.locator('.el-dialog:visible').filter({
      has: page.locator('.el-dialog__title', { hasText: '拒绝审批' })
    })
    await expect(rejectDialog).toBeVisible()
    await rejectDialog.getByRole('button', { name: '确认拒绝' }).click()
    await expect(page.getByText('拒绝时请填写原因')).toBeVisible()
    await rejectDialog.locator('textarea').fill('UI 审批拒绝')
    await rejectDialog.getByRole('button', { name: '确认拒绝' }).click()
    await expect(page.locator('.el-message__content').filter({ hasText: '已拒绝' })).toBeVisible({ timeout: 20_000 })

    const rejectedDetail = await (await request.get(`${P}/workflow/runs/${runId}`, {
      headers: auth(token)
    })).json()
    expect(rejectedDetail.code, JSON.stringify(rejectedDetail)).toBe(0)
    expect(rejectedDetail.data.status).toBe('REJECTED')
    expect(String(rejectedDetail.data.error || '')).toContain('UI 审批拒绝')
  } finally {
    // 若审批未完成，确保不留下阻塞测试数据。
    if (runId) {
      const approvals = await (await request.get(`${P}/workflow/approvals`, {
        headers: auth(token), params: { status: 'PENDING', pageSize: 100 }
      })).json().catch(() => null)
      const pending = (approvals?.data?.list || []).find((a: any) => a.runId === runId)
      if (pending) {
        await request.post(`${P}/workflow/approvals/${pending.id}/decide`, {
          headers: { ...auth(admin), 'X-Tenant-Id': '1' },
          data: { approve: false, comment: 'E2E 清理' }
        }).catch(() => {})
      }
    }
    await request.delete(`${P}/agent/${agentId}/workflow`, { headers: auth(token) })
    await request.delete(`${P}/agent/${agentId}`, { headers: auth(token) })
  }
})
