import { test, expect, type APIRequestContext } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

const P = '/api/v1'
const LOCAL_MODEL = 'qwen2.5:7b'
const ENABLED = process.env.E2E_LOCAL_WORKFLOW === '1'

function auth(token: string) {
  return { Authorization: `Bearer ${token}` }
}

async function login(request: APIRequestContext, username: string, password: string, tenantKey?: string) {
  const res = await request.post(`${P}/auth/login`, {
    data: { username, password, tenantKey }
  })
  const body = await res.json()
  return body?.code === 0 ? (body.data.accessToken as string) : null
}

async function createAgent(request: APIRequestContext, token: string, name: string): Promise<number> {
  const res = await request.post(`${P}/agent`, {
    headers: auth(token),
    data: {
      name,
      description: '本地模型工作流全分支 E2E',
      systemPrompt: '你是客服工作流测试助手。',
      modelConfig: { modelName: 'deepseek-flash', temperature: 0.1 },
      agentCapabilities: { functionCall: false },
      tools: [],
      visibility: 'PRIVATE'
    }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.id as number
}

async function runWorkflow(request: APIRequestContext, token: string, agentId: number, input: string) {
  const res = await request.post(`${P}/agent/${agentId}/workflow/run`, {
    headers: auth(token),
    data: { input, draft: true },
    timeout: 300_000
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data
}

async function findPendingApproval(request: APIRequestContext, token: string, runId: number) {
  const res = await request.get(`${P}/workflow/approvals`, {
    headers: auth(token),
    params: { status: 'PENDING', pageSize: 100 }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return (body.data.list || []).find((a: any) => a.runId === runId)
}

async function getRun(request: APIRequestContext, token: string, runId: number) {
  const res = await request.get(`${P}/workflow/runs/${runId}`, { headers: auth(token) })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data
}

function nodeNames(run: any): string[] {
  return (run.nodeRuns || []).map((x: any) => String(x.name || x.nodeId))
}

test.describe('本地模型意图工作流全分支', () => {
  test.skip(!ENABLED, '设置 E2E_LOCAL_WORKFLOW=1 并通过本机 Ollama 运行')
  test.setTimeout(600_000)

  test('五类意图、退款 PASS/FAIL、审批通过/拒绝均覆盖', async ({ request }) => {
    const token = await login(request, 'dev', 'Dev@123456', 'dev')
    expect(token).toBeTruthy()
    const agentId = await createAgent(request, token!, `E2E本地意图全分支-${Date.now()}`)
    try {
      const fixturePath = path.resolve(process.cwd(), 'e2e', 'fixtures', 'local-intent-workflow.json')
      const fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'))
      const llmNodes = (fixture.definition.nodes || []).filter((n: any) => n.type === 'LLM')
      expect(llmNodes.length).toBeGreaterThan(0)
      for (const node of llmNodes) {
        expect(node.modelName, `节点 ${node.id} 必须使用本地模型`).toBe(LOCAL_MODEL)
      }

      const save = await request.put(`${P}/agent/${agentId}/workflow`, {
        headers: auth(token!), data: fixture
      })
      const saveBody = await save.json()
      expect(saveBody.code, JSON.stringify(saveBody)).toBe(0)
      const publish = await request.post(`${P}/agent/${agentId}/workflow/publish`, {
        headers: auth(token!), data: {}
      })
      expect((await publish.json()).code).toBe(0)

      // 订单
      const order = await runWorkflow(request, token!, agentId, '我的订单什么时候发货？')
      expect(order.status).toBe('SUCCESS')
      expect(nodeNames(order)).toContain('订单回复')

      // 售后退款 PASS：沟通可以继续，最终回复
      const refundPass = await runWorkflow(request, token!, agentId, '请问退款一般多久到账？')
      expect(refundPass.status).toBe('SUCCESS')
      expect(nodeNames(refundPass)).toContain('退款安抚解释')
      expect(nodeNames(refundPass)).toContain('退款最终回复')
      expect(String(refundPass.output || '').trim().length).toBeGreaterThan(0)

      // 咨询
      const consult = await runWorkflow(request, token!, agentId, '你们这个产品怎么使用？')
      expect(consult.status).toBe('SUCCESS')
      expect(nodeNames(consult)).toContain('咨询回复')

      // 投诉
      const complaint = await runWorkflow(request, token!, agentId, '我要投诉你们的服务态度')
      expect(complaint.status).toBe('SUCCESS')
      expect(nodeNames(complaint)).toContain('投诉回复')

      // 其他
      const other = await runWorkflow(request, token!, agentId, '今天天气怎么样？')
      expect(other.status).toBe('SUCCESS')
      expect(nodeNames(other)).toContain('其他回复')

      // 退款 FAIL：进入审批，并由平台管理员通过后继续最终回复
      const refundFail = await runWorkflow(request, token!, agentId,
        '我要申请退款，退款太慢了，我要投诉你们')
      expect(refundFail.status).toBe('WAITING_APPROVAL')
      expect(nodeNames(refundFail)).toContain('退款人工审核')
      const approval = await findPendingApproval(request, token!, refundFail.id)
      expect(approval, '退款失败必须产生待审批记录').toBeTruthy()

      const admin = await login(request, 'admin', process.env.PLATFORM_ADMIN_PASSWORD || 'Admin@123456')
      expect(admin).toBeTruthy()
      const approved = await request.post(`${P}/workflow/approvals/${approval.id}/decide`, {
        headers: { ...auth(admin!), 'X-Tenant-Id': '1' },
        data: { approve: true, comment: '本地模型 E2E 审核通过' },
        timeout: 300_000
      })
      const approvedBody = await approved.json()
      expect(approvedBody.code, JSON.stringify(approvedBody)).toBe(0)
      const afterApprove = await getRun(request, token!, refundFail.id)
      expect(afterApprove.status).toBe('SUCCESS')
      expect(nodeNames(afterApprove)).toContain('退款最终回复')
      expect(String(afterApprove.output || '').trim().length).toBeGreaterThan(0)

      // 退款 FAIL 后审批拒绝：覆盖审批的另一个决策分支
      const refundRejected = await runWorkflow(request, token!, agentId,
        '我要申请退款，退款太慢了，我要投诉你们')
      expect(refundRejected.status).toBe('WAITING_APPROVAL')
      const rejectApproval = await findPendingApproval(request, token!, refundRejected.id)
      expect(rejectApproval, '拒绝分支必须产生待审批记录').toBeTruthy()
      const rejected = await request.post(`${P}/workflow/approvals/${rejectApproval.id}/decide`, {
        headers: { ...auth(admin!), 'X-Tenant-Id': '1' },
        data: { approve: false, comment: '本地模型 E2E 审核拒绝' }
      })
      const rejectedBody = await rejected.json()
      expect(rejectedBody.code, JSON.stringify(rejectedBody)).toBe(0)
      const afterReject = await getRun(request, token!, refundRejected.id)
      expect(afterReject.status).toBe('REJECTED')
      expect(String(afterReject.error || '')).toContain('审核拒绝')
    } finally {
      await request.delete(`${P}/agent/${agentId}/workflow`, { headers: auth(token!) })
      await request.delete(`${P}/agent/${agentId}`, { headers: auth(token!) })
    }
  })
})
