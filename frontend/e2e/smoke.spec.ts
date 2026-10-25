import { test, expect, type Page, type APIRequestContext } from '@playwright/test'

const USER = process.env.E2E_USER || 'dev'
const PASSWORD = process.env.E2E_PASSWORD || 'Dev@123456'

async function login(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(USER)
  await page.getByPlaceholder('密码').fill(PASSWORD)
  await page.getByRole('button', { name: '登 录' }).click()
  await page.waitForURL(/\/agents/, { timeout: 20_000 })
  // 登录后路由可能还会重定向一次；等网络和本地会话稳定后再操作，避免
  // page.evaluate 撞上 Execution context was destroyed。
  await page.waitForLoadState('networkidle')
  await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')), null,
    { timeout: 20_000 })
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

  // 清除 access token 后刷新：应通过 HttpOnly Refresh Cookie 自动恢复会话
  await page.evaluate(() => localStorage.removeItem('accessToken'))
  await page.reload()
  await page.waitForURL(/\/agents/, { timeout: 20_000 })
  await page.waitForLoadState('networkidle')
  await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')), null,
    { timeout: 20_000 })

  await page.getByRole('button', { name: '切换主题' }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)

  await page.getByRole('button', { name: '切换主题' }).click()
  await expect(page.locator('html')).not.toHaveClass(/dark/)

  await page.goto('/tools')
  await expect(page.getByRole('button', { name: '新建 API 工具' }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '添加 MCP Server' }).first()).toBeVisible()

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

  await page.goto('/observability')
  await page.getByRole('tab', { name: 'Logs' }).click()
  await expect(page.getByText('应用日志异步采集')).toBeVisible()

  await page.goto('/provider')
  await page.getByRole('button', { name: '接入新 Provider' }).click()
  const providerDialog = page.locator('.el-dialog:visible').filter({
    has: page.locator('.el-dialog__title', { hasText: '接入新 Provider' })
  })
  await expect(providerDialog.getByText('API Key')).toBeVisible()
  await providerDialog.getByRole('button', { name: '取消' }).click()

  await page.goto('/approvals')
  await expect(page.getByText('人工审批箱')).toBeVisible()
})

test('技能脚本可直跑并保存', async ({ page, request }) => {
  await login(page)
  const name = `E2E脚本技能-${Date.now()}`
  let token: string | null = null

  try {
    await page.goto('/skills')
    await page.getByRole('button', { name: '新增技能' }).first().click()
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

test('Markdown 渲染与工具步骤卡片', async ({ page, request }) => {
  test.setTimeout(180_000) // CI 冷启动 Vite + 两段对话，60s 全局预算不够
  await login(page)
  const token = await tokenOf(page)

  // 回答内容由模型决定，E2E 只验证 Markdown 组件接入与回复落位；
  // 加粗/表格/代码块/复制按钮和 XSS 过滤由 MarkdownContent.spec.ts 单测覆盖。
  // 自建纯对话 Agent：不依赖库内已有 agent id（CI 全新库没有 id=1，曾致 404 后超时）。
  const plainName = `E2E UI对话-${Date.now()}`
  const plainRes = await request.post('/api/v1/agent', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: plainName,
      systemPrompt: '你是测试助手。',
      modelConfig: { modelName: 'deepseek-flash' },
      agentCapabilities: { functionCall: false },
      visibility: 'PRIVATE'
    }
  })
  const plainBody = await plainRes.json()
  expect(plainBody.code, JSON.stringify(plainBody)).toBe(0)
  const plainId = plainBody.data.id as number
  try {
    await page.goto(`/chat/${plainId}`)
    // 不用 waitForLoadState('networkidle')：SSE/轮询型页面网络永不静止，该等待无默认超时会挂死
    await page.getByPlaceholder(/输入消息/).waitFor({ state: 'visible', timeout: 30_000 })
    const assistantCount = await page.locator('.messages .msg.assistant').count()
    const input = page.getByPlaceholder(/输入消息/)
    await input.fill('markdown测试')
    await page.getByRole('button', { name: '发送' }).click()
    await expect(page.locator('.messages .msg.assistant')).toHaveCount(assistantCount + 1,
      { timeout: 30_000 })
    const reply = page.locator('.messages .msg.assistant .markdown-body').last()
    await expect(reply).toBeVisible({ timeout: 30_000 })
    await expect(reply).not.toBeEmpty()
  } finally {
    await request.delete(`/api/v1/agent/${plainId}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
  }

  // 工具步骤卡片：创建临时 Agent（绑定计算器）→ 发布 → 对话
  const name = `E2E UI工具-${Date.now()}`
  const created = await request.post('/api/v1/agent', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name,
      systemPrompt: '你是测试助手，必须调用计算器工具。',
      modelConfig: { modelName: 'deepseek-flash' },
      agentCapabilities: { functionCall: true },
      tools: [{ toolId: 1, enabled: true }],
      visibility: 'PRIVATE'
    }
  })
  const createdBody = await created.json()
  expect(createdBody.code, JSON.stringify(createdBody)).toBe(0)
  const agentId = createdBody.data.id as number
  try {
    await request.post(`/api/v1/agent/${agentId}/publish`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { force: true }
    })
    await page.goto(`/chat/${agentId}`)
    await page.getByPlaceholder(/输入消息/).fill('请用计算器计算 12*3')
    await page.getByRole('button', { name: '发送' }).click()
    const step = page.locator('.tool-step').filter({ hasText: '计算器' }).last()
    await expect(step).toBeVisible({ timeout: 30_000 })
    await expect(step.locator('.tool-meta')).toContainText('完成', { timeout: 30_000 })
    // 最终措辞由模型决定，工具是否真正执行已由步骤卡片“完成”及 API 用例覆盖。
    await expect(page.locator('.messages .msg.assistant .markdown-body').last())
      .not.toBeEmpty({ timeout: 30_000 })
  } finally {
    await request.delete(`/api/v1/agent/${agentId}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
  }
})

test('变量弹窗与开场推荐问题', async ({ page, request }) => {
  await login(page)
  const token = await tokenOf(page)
  const name = `E2E 变量-${Date.now()}`
  const created = await request.post('/api/v1/agent', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name,
      systemPrompt: '你是订单助手。',
      openingStatement: '你好！我可以帮你查询订单状态。你想查哪个订单？',
      variables: [{ key: 'orderNo', label: '订单号', required: true, defaultValue: '' }],
      modelConfig: { modelName: 'deepseek-flash' },
      visibility: 'PRIVATE'
    }
  })
  const createdBody = await created.json()
  expect(createdBody.code, JSON.stringify(createdBody)).toBe(0)
  const agentId = createdBody.data.id as number
  try {
    await request.post(`/api/v1/agent/${agentId}/publish`, {
      headers: { Authorization: `Bearer ${token}` }, data: { force: true }
    })
    await page.goto(`/chat/${agentId}`)
    const suggestion = page.locator('.suggestions button').first()
    await expect(suggestion).toBeVisible({ timeout: 20_000 })
    await suggestion.click()

    const dialog = page.locator('.el-dialog:visible').filter({
      has: page.locator('.el-dialog__title', { hasText: '填写对话变量' })
    })
    await expect(dialog).toBeVisible()
    await dialog.locator('.el-form-item', { hasText: '订单号' }).locator('input').fill('A100')
    await dialog.getByRole('button', { name: '开始对话' }).click()
    await expect(dialog).toBeHidden({ timeout: 10_000 })
    await expect(page.locator('.messages .msg.assistant .markdown-body').last())
      .not.toBeEmpty({ timeout: 30_000 })
  } finally {
    await request.delete(`/api/v1/agent/${agentId}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
  }
})


test('英文界面切换与核心文案', async ({ page }) => {
  await page.goto('/login')
  await page.evaluate(() => localStorage.setItem('locale', 'en-US'))
  await page.reload()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  await expect(page.getByPlaceholder('Username')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Enterprise SSO' })).toBeVisible()

  await page.getByPlaceholder('Username').fill(USER)
  await page.getByPlaceholder('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.waitForURL(/\/agents/, { timeout: 20_000 })
  await expect(page.getByText('Agents', { exact: true }).first()).toBeVisible()

  await page.evaluate(() => localStorage.setItem('locale', 'zh-CN'))
  await page.reload()
  await expect(page.getByText('Agent 列表', { exact: true }).first()).toBeVisible()
})

test('顶栏全局搜索 Agent/知识库/用例/工具', async ({ page, request }) => {
  await login(page)
  const token = await tokenOf(page)
  const name = `E2E客服搜索-${Date.now()}`
  const created = await request.post('/api/v1/agent', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name,
      description: '全局搜索自包含数据',
      systemPrompt: '你是搜索测试助手。',
      modelConfig: { modelName: 'deepseek-flash' },
      visibility: 'PRIVATE'
    }
  })
  const createdBody = await created.json()
  expect(createdBody.code, JSON.stringify(createdBody)).toBe(0)
  const agentId = createdBody.data.id as number
  try {
    const input = page.getByPlaceholder('搜索 Agent / 知识库 / 用例 / 工具')
    await input.fill(name)
    const first = page.locator('.gs-item').first()
    await expect(first).toBeVisible({ timeout: 10_000 })
    await expect(first).toContainText(name)
    await first.click()
    await page.waitForURL(new RegExp(`/agent/edit/${agentId}`), { timeout: 10_000 })
  } finally {
    await request.delete(`/api/v1/agent/${agentId}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
  }
})

test('知识库批量上传与进度展示', async ({ page, request }) => {
  await login(page)
  const token = await tokenOf(page)
  const kb = await (await request.post('/api/v1/knowledge-base', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `UI 批量上传-${Date.now()}`, visibility: 'PRIVATE', embeddingModel: 'nomic-embed-text',
      graphEnabled: false }
  })).json()
  expect(kb.code, JSON.stringify(kb)).toBe(0)
  const kbId = kb.data.id
  try {
    await page.goto(`/knowledge/${kbId}`)
    await expect(page.locator('.kb-title')).toBeVisible()
    const files = [
      { name: 'batch-a.txt', mimeType: 'text/plain', buffer: Buffer.from('批量上传文件 A') },
      { name: 'batch-b.txt', mimeType: 'text/plain', buffer: Buffer.from('批量上传文件 B') }
    ]
    await page.locator('.toolbar input[type=file]').first().setInputFiles(files)
    await expect(page.locator('.upload-task')).toHaveCount(2, { timeout: 20_000 })
    await expect(page.locator('.upload-task-status.done')).toHaveCount(2, { timeout: 30_000 })
    await expect(page.locator('.docs').getByText('batch-a.txt')).toBeVisible()
    await expect(page.locator('.docs').getByText('batch-b.txt')).toBeVisible()
  } finally {
    await request.delete(`/api/v1/knowledge-base/${kbId}?force=true`, {
      headers: { Authorization: `Bearer ${token}` }
    })
  }
})
