import { test, expect, type Page, type Route } from '@playwright/test'

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

const task = {
  id: 901,
  name: 'E2E步骤回归任务',
  agentId: 1,
  datasetId: 77,
  datasetName: 'E2E步骤集',
  evalType: 'ONCE',
  status: 'DONE',
  estimatedCalls: 2,
  completedCalls: 2,
  avgScore: 0.75,
  passRate: 0.5,
  createdAt: '2026-10-26T10:00:00'
}

const baseline = { ...task, id: 900, name: 'E2E基线任务', passRate: 1 }

const detail = {
  ...task,
  judgeModel: 'deepseek-flash',
  stepSummary: {
    ANSWER: { count: 1, avgScore: 0.9, passRate: 1 },
    REWRITE: { count: 1, avgScore: 0.6, passRate: 0.5 },
    RETRIEVAL: { count: 0, avgScore: null, passRate: null },
    RERANK: { count: 0, avgScore: null, passRate: null }
  },
  results: [
    {
      id: 501,
      question: '退款太慢，帮我改写查询',
      expectedAnswer: '',
      answer: '退款申请进度查询',
      score: 0.6,
      passed: true,
      judgeReason: '实体保留',
      stepType: 'REWRITE',
      stepPassed: true,
      stepDetails: '{"keptEntities":true}',
      reviewStatus: 'PENDING',
      reviewNote: null,
      traceId: 'trace-e2e-task',
      latencyMs: 12,
      tokens: 20
    }
  ]
}

const comparison = {
  taskId: 901,
  baselineTaskId: 900,
  comparison: {
    improved: [],
    regressed: ['REWRITE'],
    steps: {
      REWRITE: {
        current: { count: 1, avgScore: 0.6, passRate: 0.5 },
        baseline: { count: 1, avgScore: 0.8, passRate: 1 },
        passRateDelta: -0.5,
        avgScoreDelta: -0.2
      }
    }
  }
}

const trend = {
  datasetId: 77,
  tasks: [
    { ...task, stepSummary: detail.stepSummary }
  ]
}

const coverage = {
  datasets: [{
    datasetId: 77,
    name: 'E2E步骤集',
    total: 2,
    steps: { ANSWER: 1, REWRITE: 1, RETRIEVAL: 0, RERANK: 0 },
    missing: ['RETRIEVAL', 'RERANK']
  }],
  totalByType: { ANSWER: 1, REWRITE: 1, RETRIEVAL: 0, RERANK: 0 },
  missingTypes: ['RETRIEVAL', 'RERANK']
}

function reply(route: Route, data: any) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 0, data })
  })
}

test('任务详情：分步汇总、回归对比、趋势/覆盖矩阵与人工复核', async ({ page }) => {
  await login(page)
  await page.route('**/api/v1/eval/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (method === 'GET' && path === '/api/v1/eval/datasets') {
      return reply(route, [{
        id: 77, name: 'E2E步骤集', description: '步骤回归', itemCount: 2,
        agentId: 1, agentName: '客服助手'
      }])
    }
    if (method === 'GET' && path === '/api/v1/eval/tasks') {
      return reply(route, { list: [task, baseline] })
    }
    if (method === 'GET' && path === '/api/v1/eval/tasks/901') {
      return reply(route, detail)
    }
    if (method === 'GET' && path === '/api/v1/eval/tasks/901/compare/900') {
      return reply(route, comparison)
    }
    if (method === 'GET' && path === '/api/v1/eval/datasets/77/trend') {
      return reply(route, trend)
    }
    if (method === 'GET' && path === '/api/v1/eval/coverage') {
      return reply(route, coverage)
    }
    if (method === 'PUT' && path === '/api/v1/eval/results/501/review') {
      return reply(route, {
        id: 501, reviewStatus: 'REVIEWED', reviewNote: '已核对改写实体', reviewedBy: 1
      })
    }
    return route.continue()
  })

  await page.goto('/eval')
  const taskRow = page.locator('.el-table__row').filter({ hasText: 'E2E步骤回归任务' })
  await expect(taskRow).toBeVisible()
  await taskRow.click()

  const drawer = page.locator('.el-drawer:visible')
  await expect(drawer).toContainText('分步骤结果')
  await expect(drawer).toContainText('改写')
  await expect(drawer.locator('.el-table__row').filter({ hasText: 'REWRITE' })).toBeVisible()

  // 跨任务回归对比：基线通过率 100%，当前 50%，应显示回归和 -50。
  await drawer.locator('.compare-bar .el-select').click()
  await page.getByRole('option', { name: /E2E基线任务/ }).click()
  await drawer.getByRole('button', { name: '对比' }).click()
  await expect(drawer.locator('.compare-result')).toContainText('改写 回归')
  await expect(drawer.locator('.compare-result')).toContainText('-50')

  // 人工复核。
  await drawer.getByRole('button', { name: '复核' }).click()
  const review = page.getByRole('dialog', { name: '人工复核' })
  await review.getByPlaceholder('记录核对依据、缺口原因或是否接受').fill('已核对改写实体')
  await review.getByRole('button', { name: '确认已复核' }).click()
  await expect(drawer).toContainText('已复核')
  await drawer.getByRole('button', { name: '关闭此对话框' }).click()

  // 趋势与覆盖矩阵。
  await page.getByText('数据集管理', { exact: true }).click()
  const dsRow = page.locator('.el-table__row').filter({ hasText: 'E2E步骤集' })
  await dsRow.getByRole('button', { name: '趋势' }).click()
  const trendDialog = page.getByRole('dialog', { name: /数据集趋势/ })
  await expect(trendDialog).toContainText('E2E步骤回归任务')
  await trendDialog.getByRole('button', { name: '关闭此对话框' }).click()

  await page.getByRole('button', { name: '覆盖矩阵' }).click()
  const coverageDialog = page.getByRole('dialog', { name: '步骤覆盖矩阵' })
  await expect(coverageDialog).toContainText('E2E步骤集')
  await expect(coverageDialog).toContainText('检索')
  await coverageDialog.getByRole('button', { name: '关闭此对话框' }).click()
})
