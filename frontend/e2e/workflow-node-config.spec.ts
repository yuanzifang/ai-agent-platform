import { test, expect, type APIRequestContext, type Page } from '@playwright/test'

const P = '/api/v1'
const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'

function auth(token: string) {
  return { Authorization: `Bearer ${token}` }
}

async function login(request: APIRequestContext) {
  const res = await request.post(`${P}/auth/login`, {
    data: { username: USER, password: PASSWORD, tenantKey: 'dev' }
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

test('工作流节点配置：LOOP 与失败重试策略 UI 回显保存', async ({ page, request }) => {
  const token = await login(request)
  const created = await request.post(`${P}/agent`, {
    headers: auth(token),
    data: {
      name: `E2E节点配置-${Date.now()}`,
      description: '工作流节点配置 UI E2E',
      systemPrompt: '节点配置测试。',
      modelConfig: { modelName: 'deepseek-flash' },
      agentCapabilities: { functionCall: false },
      tools: [],
      visibility: 'PRIVATE'
    }
  })
  const agentBody = await created.json()
  expect(agentBody.code, JSON.stringify(agentBody)).toBe(0)
  const agentId = agentBody.data.id as number
  try {
    const workflow = {
      name: 'E2E 节点配置', status: 'ENABLED',
      definition: {
        nodes: [
          { id: 'start', type: 'START', name: '开始', next: 'tool1' },
          { id: 'tool1', type: 'TOOL', name: '坏工具', toolId: 999999,
            retryTimes: 2, onError: 'CONTINUE', next: 'loop1' },
          { id: 'loop1', type: 'LOOP', name: '循环配置', items: ['A', 'B'],
            bodyNode: 'body1', loopEnd: 'join1', maxIterations: 2, next: 'end' },
          { id: 'body1', type: 'STATE', name: '循环体', stateKey: 'loop_body',
            valueTemplate: '{{last}}', next: 'join1' },
          { id: 'join1', type: 'JOIN', name: '汇合', next: 'end' },
          { id: 'end', type: 'END', name: '结束' }
        ], edges: []
      }
    }
    const saved = await request.put(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token), data: workflow
    })
    expect((await saved.json()).code).toBe(0)

    await pageLogin(page)
    await page.goto(`/agent/edit/${agentId}`)
    await page.waitForLoadState('networkidle')
    const wfItem = page.locator('.el-collapse-item').filter({ hasText: '工作流' }).first()
    await wfItem.locator('.el-collapse-item__header').click()
    await wfItem.getByRole('button', { name: '编辑工作流' }).click()
    const dialog = page.locator('.wf-dialog')
    await expect(dialog).toBeVisible()

    const toolRow = dialog.locator('.wf-node').filter({ hasText: '#tool1' }).first()
    await expect(toolRow).toBeVisible()
    await expect(toolRow.locator('.wf-node-head input').first()).toHaveValue('坏工具')
    await expect(toolRow.locator('.el-input-number input').first()).toHaveValue('2')
    await expect(toolRow.locator('.wf-node-body .el-select').last()).toContainText('继续下一节点')

    const loopRow = dialog.locator('.wf-node').filter({ hasText: '#loop1' }).first()
    await expect(loopRow).toBeVisible()
    await expect(loopRow.locator('textarea').first()).toHaveValue('["A","B"]')
    await expect(loopRow.locator('.el-input-number input').first()).toHaveValue('2')
    await expect(loopRow).toContainText('循环体')
    await expect(loopRow).toContainText('汇合')

    await dialog.getByRole('button', { name: '保存草稿' }).click()
    const fresh = await (await request.get(`${P}/agent/${agentId}/workflow`, {
      headers: auth(token)
    })).json()
    const nodes = new Map<string, any>((fresh.data.definition.nodes || []).map((n: any) => [n.id, n]))
    expect(nodes.get('tool1').retryTimes).toBe(2)
    expect(nodes.get('tool1').onError).toBe('CONTINUE')
    expect(nodes.get('loop1').items).toEqual(['A', 'B'])
    expect(nodes.get('loop1').bodyNode).toBe('body1')
    expect(nodes.get('loop1').loopEnd).toBe('join1')
    expect(nodes.get('loop1').maxIterations).toBe(2)
  } finally {
    await request.delete(`${P}/agent/${agentId}/workflow`, { headers: auth(token) })
    await request.delete(`${P}/agent/${agentId}`, { headers: auth(token) })
  }
})
