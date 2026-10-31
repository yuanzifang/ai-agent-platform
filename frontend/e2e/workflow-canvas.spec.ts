import { test, expect, type APIRequestContext, type Locator, type Page } from '@playwright/test'

const P = '/api/v1'
const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'

function auth(token: string) {
  return { Authorization: `Bearer ${token}` }
}

async function apiLogin(request: APIRequestContext) {
  const res = await request.post(`${P}/auth/login`, {
    data: { username: USER, password: PASSWORD, tenantKey: 'dev' }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.accessToken as string
}

async function createAgent(request: APIRequestContext, token: string): Promise<number> {
  const res = await request.post(`${P}/agent`, {
    headers: auth(token),
    data: {
      name: `E2E画布交互-${Date.now()}`,
      description: '工作流画布 UI E2E',
      systemPrompt: '你是测试助手。',
      modelConfig: { modelName: 'deepseek-flash' },
      agentCapabilities: { functionCall: false },
      tools: [],
      visibility: 'PRIVATE'
    }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.id as number
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

async function getWorkflow(request: APIRequestContext, token: string, agentId: number) {
  const res = await request.get(`${P}/agent/${agentId}/workflow`, { headers: auth(token) })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data
}

async function dragPort(page: Page, source: Locator, target: Locator) {
  await source.scrollIntoViewIfNeeded()
  await target.scrollIntoViewIfNeeded()
  const s = await source.boundingBox()
  const t = await target.boundingBox()
  expect(s, '源端口必须可见').toBeTruthy()
  expect(t, '目标节点必须可见').toBeTruthy()
  await page.mouse.move(s!.x + s!.width / 2, s!.y + s!.height / 2)
  await page.mouse.down()
  await page.mouse.move(t!.x + t!.width / 2, t!.y + t!.height / 2, { steps: 8 })
  await page.mouse.up()
  await page.waitForTimeout(120)
}

async function saveDraft(page: Page, dialog: Locator, agentId: number) {
  const [res] = await Promise.all([
    page.waitForResponse(r => r.url().includes(`/agent/${agentId}/workflow`)
      && r.request().method() === 'PUT'),
    dialog.getByRole('button', { name: '保存草稿' }).click()
  ])
  expect(res.ok(), await res.text()).toBeTruthy()
}

test('工作流画布：拖入、单击配置、T/F 连线、删除边、草稿测试', async ({ page, request }) => {
  test.setTimeout(120_000)
  const token = await apiLogin(request)
  const agentId = await createAgent(request, token)
  try {
    await pageLogin(page)
    await page.goto(`/agent/edit/${agentId}`)
    await page.waitForLoadState('networkidle')

    const wfItem = page.locator('.el-collapse-item').filter({ hasText: '工作流' }).first()
    await wfItem.locator('.el-collapse-item__header').click()
    await wfItem.getByRole('button', { name: '编辑工作流' }).click()
    const dialog = page.locator('.wf-dialog')
    await expect(dialog).toBeVisible()
    await dialog.getByText('画布布局', { exact: true }).click()
    await expect(dialog.locator('.wf-canvas-node')).toHaveCount(3)

    // 1. 从节点面板拖入 LLM，并单击节点修改名称
    const paletteLlm = dialog.locator('.wf-palette-item').filter({ hasText: 'LLM' }).first()
    await paletteLlm.dragTo(dialog.locator('[data-testid="wf-canvas"] .wf-canvas'), {
      targetPosition: { x: 420, y: 260 }
    })
    await expect(dialog.locator('.wf-canvas-node')).toHaveCount(4)
    const panel = dialog.locator('[data-testid="wf-node-panel"]')
    await expect(panel).toBeVisible()
    await panel.locator('.wf-name-line input').first().fill('拖入节点')
    await expect(dialog.locator('.wf-canvas-node').filter({ hasText: '拖入节点' })).toBeVisible()

    // 2. 添加条件节点与两个状态节点
    await dialog.locator('.wf-palette-item').filter({ hasText: '条件' }).first().click()
    await panel.locator('.wf-name-line input').first().fill('路由条件')
    await panel.getByPlaceholder('条件值').fill('YES')
    await dialog.locator('.wf-palette-item').filter({ hasText: '状态' }).first().click()
    await panel.locator('.wf-name-line input').first().fill('通过状态')
    await panel.getByPlaceholder('状态键，如 handoff_result').fill('pass_state')
    await dialog.locator('.wf-palette-item').filter({ hasText: '状态' }).first().click()
    await panel.locator('.wf-name-line input').first().fill('拒绝状态')
    await panel.getByPlaceholder('状态键，如 handoff_result').fill('fail_state')
    await expect(dialog.locator('.wf-canvas-node')).toHaveCount(7)

    // 关闭右侧节点面板，避免面板遮住画布右侧的连线目标节点
    await panel.locator('.wf-node-panel-head button').last().click()
    await expect(panel).toBeHidden()
    await dialog.getByRole('button', { name: '自动布局' }).click()
    await dialog.getByRole('button', { name: '适应画布' }).click()
    const startNode = dialog.locator('.wf-canvas-node[data-node-id="start"]')
    const endNode = dialog.locator('.wf-canvas-node[data-node-id="end"]')
    const conditionNode = dialog.locator('.wf-canvas-node').filter({ hasText: '路由条件' }).first()
    const passNode = dialog.locator('.wf-canvas-node').filter({ hasText: '通过状态' }).first()
    const failNode = dialog.locator('.wf-canvas-node').filter({ hasText: '拒绝状态' }).first()

    // 3. 普通节点连线 + 条件节点 T/F 双端口连线
    await dragPort(page, startNode.locator('.wf-port').first(), conditionNode)
    await dragPort(page, conditionNode.locator('.wf-port-true'), passNode)
    await dragPort(page, conditionNode.locator('.wf-port-false'), failNode)
    await dragPort(page, passNode.locator('.wf-port').first(), endNode)
    await dragPort(page, failNode.locator('.wf-port').first(), endNode)

    await saveDraft(page, dialog, agentId)
    const saved = await getWorkflow(request, token, agentId)
    const savedNodes = new Map<string, any>((saved.definition.nodes || []).map((n: any) => [n.id, n]))
    const condId = await conditionNode.getAttribute('data-node-id')
    const passId = await passNode.getAttribute('data-node-id')
    const failId = await failNode.getAttribute('data-node-id')
    const savedCond = savedNodes.get(condId || '')
    expect(savedCond, '条件节点应已保存').toBeTruthy()
    expect(savedCond.trueNext).toBe(passId)
    expect(savedCond.falseNext).toBe(failId)

    // 4. 运行当前草稿：T 分支和 F 分支都覆盖
    const runInput = dialog.getByPlaceholder('输入测试内容')
    const runButton = dialog.getByRole('button', { name: '运行', exact: true })
    await runInput.fill('YES')
    await runButton.click()
    await expect(dialog.locator('[data-testid="wf-run-result"]')).toContainText('SUCCESS')
    await expect(dialog.locator('[data-testid="wf-run-result"]')).toContainText('通过状态')

    await runInput.fill('NO')
    await runButton.click()
    await expect(dialog.locator('[data-testid="wf-run-result"]')).toContainText('SUCCESS')
    await expect(dialog.locator('[data-testid="wf-run-result"]')).toContainText('拒绝状态')

    // 5. 添加显式 Graph 边，再在画布选中并删除
    const edgeHeader = dialog.locator('.el-collapse-item__header').filter({ hasText: 'Graph 有向边' })
    await edgeHeader.click()
    await dialog.getByRole('button', { name: '添加边' }).click()
    await saveDraft(page, dialog, agentId)
    const withEdge = await getWorkflow(request, token, agentId)
    expect((withEdge.definition.edges || []).length).toBeGreaterThan(0)

    await dialog.getByText('画布布局', { exact: true }).click()
    const explicitPath = dialog.locator('.wf-edges path[marker-end]').first()
    await expect(explicitPath).toBeVisible()
    await explicitPath.dispatchEvent('click')
    const deleteEdge = dialog.getByRole('button', { name: '删除连线' })
    await expect(deleteEdge).toBeVisible()
    await deleteEdge.click()
    await saveDraft(page, dialog, agentId)
    const withoutEdge = await getWorkflow(request, token, agentId)
    expect((withoutEdge.definition.edges || []).length).toBe(0)
  } finally {
    await request.delete(`${P}/agent/${agentId}/workflow`, { headers: auth(token) })
    await request.delete(`${P}/agent/${agentId}`, { headers: auth(token) })
  }
})
