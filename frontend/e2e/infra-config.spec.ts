import { test, expect, type Page } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'

async function loginAsPlatformAdmin(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(USER)
  await page.getByPlaceholder('密码').fill(PASSWORD)
  await page.getByRole('button', { name: '登 录' }).click()
  await page.waitForURL(/\/admin\//, { timeout: 20_000 })
  await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')), null,
    { timeout: 20_000 })
}

test('平台基础设施页与资源 Profile', async ({ page }) => {
  test.skip(process.env.E2E_PLATFORM_ADMIN !== '1',
    '需要显式设置 E2E_PLATFORM_ADMIN=1 和平台管理员账号')
  await loginAsPlatformAdmin(page)
  await page.goto('/admin/infrastructure')
  await expect(page.getByText('基础设施配置')).toBeVisible()
  await expect(page.getByText('MySQL 主数据库')).toBeVisible()
  await expect(page.getByText('Elasticsearch')).toBeVisible()
  await expect(page.getByText('资源 Profile', { exact: true })).toBeVisible()
  await expect(page.getByText('默认检索资源', { exact: true })).toBeVisible()
})
