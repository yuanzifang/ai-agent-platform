import { test, expect, type Page } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'
const AGENT_ID = 1
const GATE_UI_QUARANTINED = process.env.E2E_GATE_QUARANTINE === '1'

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

test('发布弹窗：门禁失败直接展示失败步骤与样本明细', async ({ page }) => {
  test.skip(GATE_UI_QUARANTINED, '网关门禁 UI 用例已隔离，由独立任务修复')
  await login(page)
  await page.route(`**/api/v1/agent/${AGENT_ID}**`, async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (method === 'PUT' && path === `/api/v1/agent/${AGENT_ID}`) {
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ code: 0, data: { updatedAt: new Date().toISOString() } })
      })
    }
    if (method === 'POST' && path === `/api/v1/agent/${AGENT_ID}/publish`) {
      return route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 400,
          message: '预发布测评未通过，无法发布（通过率 0.5 < 阈值 0.8；OWNER/ADMIN 可强制发布）'
        })
      })
    }
    if (method === 'POST' && path === `/api/v1/agent/${AGENT_ID}/gate-preview`) {
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          data: {
            skipped: false, passed: false, passRate: 0.5, threshold: 0.8,
            stepGate: {
              threshold: 0.8,
              failedSteps: ['RETRIEVAL', 'ANSWER'],
              failures: [
                { stepType: 'RETRIEVAL', score: 0, reason: '未命中期望 chunk', traceId: 'trace-retrieval-123456' },
                { stepType: 'ANSWER', score: 0.2, reason: '回答与期望不一致', traceId: 'trace-answer-abcdef' }
              ]
            }
          }
        })
      })
    }
    return route.continue()
  })

  await page.goto(`/agent/edit/${AGENT_ID}`)
  await expect(page.locator('.topbar')).toContainText('Agent')

  // 发布前配置检查可能弹出；有则确认继续。
  await page.locator('.topbar .actions').getByRole('button', { name: '发布', exact: true }).click()
  const checklist = page.getByRole('dialog', { name: '发布前配置检查' })
  if (await checklist.isVisible().catch(() => false)) {
    await checklist.getByRole('button', { name: '仍要发布' }).click()
  }

  const gateDialog = page.getByRole('dialog', { name: '预发布门禁未通过' })
  await expect(gateDialog).toBeVisible()
  await expect(gateDialog).toContainText('失败步骤：')
  await expect(gateDialog).toContainText('RETRIEVAL / ANSWER')
  await expect(gateDialog).toContainText('未命中期望 chunk')
  await expect(gateDialog).toContainText('回答与期望不一致')
  await gateDialog.getByRole('button', { name: '取消' }).click()
})

test('写工具策略变更：普通发布阻断后可强制发布', async ({ page }) => {
  test.skip(GATE_UI_QUARANTINED, '网关门禁 UI 用例已隔离，由独立任务修复')
  await login(page)
  let forceRequested = false
  await page.route(`**/api/v1/agent/${AGENT_ID}**`, async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (method === 'PUT' && path === `/api/v1/agent/${AGENT_ID}`) {
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ code: 0, data: { updatedAt: new Date().toISOString() } })
      })
    }
    if (method === 'GET' && path === `/api/v1/agent/${AGENT_ID}/deployment`) {
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ code: 0, data: { deployment: null } })
      })
    }
    if (method === 'POST' && path === `/api/v1/agent/${AGENT_ID}/publish`) {
      const body = route.request().postDataJSON() as { force?: boolean }
      if (body?.force) {
        forceRequested = true
        return route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({ code: 0, data: { version: 'V2' } })
        })
      }
      return route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 400,
          message: 'writePolicyGate: 写工具策略比上一发布版本更宽松，需 OWNER/ADMIN 确认后强制发布'
        })
      })
    }
    return route.continue()
  })

  await page.goto(`/agent/edit/${AGENT_ID}`)
  await page.locator('.topbar .actions').getByRole('button', { name: '发布', exact: true }).click()
  const checklist = page.getByRole('dialog', { name: '发布前配置检查' })
  if (await checklist.isVisible().catch(() => false)) {
    await checklist.getByRole('button', { name: '仍要发布' }).click()
  }

  const dialog = page.getByRole('dialog', { name: '写工具策略变更需确认' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('写工具策略比上一发布版本更宽松')
  await dialog.getByRole('button', { name: '强制发布' }).click()
  await expect.poll(() => forceRequested).toBe(true)
  await expect(page.getByText('已发布 V2')).toBeVisible()
})
