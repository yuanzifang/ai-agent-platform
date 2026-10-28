import { test, expect, type Page, type APIRequestContext } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'
const BACKEND = process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080'

async function login(page: Page, platformAdmin = false) {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(USER)
  await page.getByPlaceholder('密码').fill(PASSWORD)
  await page.getByRole('button', { name: '登 录' }).click()
  await page.waitForURL(platformAdmin ? /\/admin\// : /\/agents/, { timeout: 20_000 })
  await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')), null,
    { timeout: 20_000 })
}

async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${BACKEND}/api/v1/auth/login`, {
    data: { username: USER, password: PASSWORD }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.accessToken as string
}

test('平台基础设施页与资源 Profile', async ({ page }) => {
  test.skip(process.env.E2E_PLATFORM_ADMIN !== '1',
    '需要显式设置 E2E_PLATFORM_ADMIN=1 和平台管理员账号')
  await login(page, true)
  await page.goto('/admin/infrastructure')
  await expect(page.getByText('基础设施配置')).toBeVisible()
  await expect(page.getByText('MySQL 主数据库')).toBeVisible()
  await expect(page.getByText('Elasticsearch')).toBeVisible()
  await expect(page.getByText('Alias', { exact: true })).toBeVisible()
  await expect(page.getByText('资源 Profile', { exact: true })).toBeVisible()
  await expect(page.getByText('默认检索资源', { exact: true })).toBeVisible()
})

test('Agent 编辑页资源 Profile 与知识库绑定区', async ({ page, request }) => {
  test.skip(process.env.E2E_AGENT_PROFILE !== '1',
    '需要显式设置 E2E_AGENT_PROFILE=1')
  const token = await apiToken(request)
  const name = `E2E profile check ${Date.now()}`
  let agentId: number | undefined
  try {
    const created = await request.post(`${BACKEND}/api/v1/agent`, {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        name,
        description: 'E2E 资源 Profile 页面检查',
        systemPrompt: '你是测试助手。',
        modelConfig: { modelName: 'deepseek-flash', temperature: 0.2 },
        agentCapabilities: { functionCall: false },
        tools: [],
        resourceProfileId: 1,
        visibility: 'PRIVATE'
      }
    })
    const body = await created.json()
    expect(body.code, JSON.stringify(body)).toBe(0)
    agentId = body.data.id as number

    await login(page)
    await page.goto(`/agent/edit/${agentId}`)
    const knowledgeHeader = page.locator('.el-collapse-item__header').filter({ hasText: '知识库' })
    await knowledgeHeader.click()
    await expect(page.getByText('资源 Profile', { exact: true })).toBeVisible()
    await expect(page.getByText('默认检索资源', { exact: false }).first()).toBeVisible()
  } finally {
    if (agentId) {
      await request.delete(`${BACKEND}/api/v1/agent/${agentId}`, {
        headers: { Authorization: `Bearer ${token}` }
      })
    }
  }
})
