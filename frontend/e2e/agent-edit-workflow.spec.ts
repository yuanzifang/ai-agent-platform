import { test, expect, type Page } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'
const AGENT_ID = 1

async function login(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(USER)
  await page.getByPlaceholder('密码').fill(PASSWORD)
  await page.getByRole('button', { name: '登 录' }).click()
  await page.waitForURL(/\/agents/, { timeout: 20_000 })
  await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')), null,
    { timeout: 20_000 })
}

/** 只 mock Agent 自身接口（详情/工作流），目录类接口走真实后端；徽标断言不依赖库里是否有 Agent。 */
function mockAgentApi(page: Page, workflowData: Record<string, unknown>) {
  return page.route(`**/api/v1/agent/${AGENT_ID}**`, async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (method === 'GET' && path === `/api/v1/agent/${AGENT_ID}/workflow`) {
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ code: 0, data: workflowData })
      })
    }
    if (method === 'GET' && path === `/api/v1/agent/${AGENT_ID}`) {
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          data: {
            id: AGENT_ID, name: 'E2E工作流徽标', status: 'DRAFT',
            modelConfig: {}, agentCapabilities: {}, memoryConfig: {},
            knowledgeBases: [], skills: [], tools: [], subAgents: [], variables: []
          }
        })
      })
    }
    return route.continue()
  })
}

function workflowPanel(page: Page) {
  return page.locator('.el-collapse-item').filter({
    has: page.locator('.p-title', { hasText: '工作流 / 人工审批' })
  })
}

test('已配置工作流的 Agent 进编辑页即显示已配置（此前要打开弹窗才刷新）', async ({ page }) => {
  await login(page)
  await mockAgentApi(page, {
    configured: true, agentId: AGENT_ID, name: '回归工作流', status: 'ENABLED',
    definition: {
      nodes: [
        { id: 'start', type: 'START', name: '开始' },
        { id: 'llm-1', type: 'LLM', name: '总结' },
        { id: 'end', type: 'END', name: '结束' }
      ],
      edges: []
    }
  })

  await page.goto(`/agent/edit/${AGENT_ID}`)
  const badge = workflowPanel(page).locator('.el-tag')
  await expect(badge).toContainText('已配置')
  await expect(badge).toContainText('3 个节点')
})

test('未配置工作流的 Agent 进编辑页显示未配置', async ({ page }) => {
  await login(page)
  await mockAgentApi(page, {
    configured: false, agentId: AGENT_ID, definition: { nodes: [], edges: [] }
  })

  await page.goto(`/agent/edit/${AGENT_ID}`)
  await expect(workflowPanel(page).locator('.el-tag')).toContainText('未配置')
})
