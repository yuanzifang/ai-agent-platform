import { test, expect, type Page, type APIRequestContext } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'

async function login(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(USER)
  await page.getByPlaceholder('密码').fill(PASSWORD)
  await page.getByRole('button', { name: '登 录' }).click()
  await page.waitForURL(/\/agents/, { timeout: 20_000 })
}

async function tokenOf(page: Page): Promise<string | null> {
  return page.evaluate(() => localStorage.getItem('accessToken'))
}

async function cleanupSkill(request: APIRequestContext, token: string | null, name: string) {
  if (!token) return
  try {
    const res = await request.get('/api/v1/skills', {
      headers: { Authorization: `Bearer ${token}` }
    })
    const body = await res.json()
    const rows = body?.data?.list || []
    const target = rows.find((r: any) => r.name === name)
    if (target?.id) {
      await request.delete(`/api/v1/skills/${target.id}`, {
        headers: { Authorization: `Bearer ${token}` }
      })
    }
  } catch {
    // 清理失败不阻断 CI；数据库为一次性环境
  }
}

test('登录与能力入口可见', async ({ page }) => {
  await login(page)

  await page.goto('/tools')
  await expect(page.getByRole('button', { name: '新建 API 工具' })).toBeVisible()
  await expect(page.getByRole('button', { name: '添加 MCP Server' })).toBeVisible()

  await page.goto('/mcp-servers')
  await expect(page.getByRole('button', { name: '添加 MCP Server' })).toBeVisible()

  await page.goto('/prompt-templates')
  await page.getByRole('button', { name: '新建模板' }).click()
  const templateDialog = page.locator('.el-dialog:visible').filter({
    has: page.locator('.el-dialog__title', { hasText: '新建模板' })
  })
  await expect(templateDialog).toBeVisible()
  await templateDialog.locator('.el-form-item', { hasText: '内容' }).locator('textarea')
    .fill('你是客服，回答用户问题，变量 {{product}}，不知道就说不知道。')
  await expect(templateDialog.getByRole('button', { name: 'AI 优化' })).toBeVisible()
  await templateDialog.getByRole('button', { name: '取消' }).click()
})

test('技能脚本可直跑并保存', async ({ page, request }) => {
  await login(page)
  const name = `E2E脚本技能-${Date.now()}`
  let token: string | null = null

  try {
    await page.goto('/skills')
    await page.getByRole('button', { name: '新增技能' }).click()
    const dialog = page.locator('.el-dialog:visible').filter({
      has: page.locator('.el-dialog__title', { hasText: '新建技能' })
    })
    await expect(dialog).toBeVisible()

    await dialog.locator('.el-form-item', { hasText: '名称' }).locator('input').first().fill(name)
    await dialog.locator('.el-form-item', { hasText: '描述' }).locator('input').first()
      .fill('Playwright smoke test skill')
    await dialog.locator('.el-form-item', { hasText: '提示词' }).locator('textarea')
      .fill('你是加法助手，必须调用脚本工具，然后只回答结果。')
    await dialog.locator('.el-form-item', { hasText: '参数定义' }).locator('textarea')
      .fill('[{"key":"a","type":"number","required":true},{"key":"b","type":"number","required":true}]')

    const scriptLanguage = dialog.locator('.el-form-item').filter({ hasText: '脚本' }).first()
    await scriptLanguage.locator('.el-select').click()
    await page.locator('.el-select-dropdown__item:visible', { hasText: 'Python 3' }).click()

    await dialog.locator('.el-form-item', { hasText: '脚本代码' }).locator('textarea')
      .fill('print(params.get("a",0) + params.get("b",0))')
    const scriptTest = dialog.locator('.el-form-item', { hasText: '脚本测试' })
    await scriptTest.locator('textarea').fill('{"a":20,"b":22}')
    await scriptTest.getByRole('button', { name: '运行脚本' }).click()
    await expect(dialog.locator('.script-output')).toContainText('42', { timeout: 20_000 })

    await dialog.getByRole('button', { name: '保存' }).click()
    await expect(page.locator('.el-table__body tr', { hasText: name })).toBeVisible()
    token = await tokenOf(page)
  } finally {
    await cleanupSkill(request, token || await tokenOf(page).catch(() => null), name)
  }
})
