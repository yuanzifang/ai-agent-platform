import { test, expect } from '@playwright/test'
import { P, auth, login, deleteAgent } from './api-helpers'

const MODEL = process.env.E2E_REAL_MODEL_NAME || 'qwen2.5:7b-chat'
const ADMIN_PASSWORD = process.env.PLATFORM_ADMIN_PASSWORD || 'Admin@123456'

/**
 * 真实模型/真实裁判门禁的 CI 版本：不依赖固定 agent/dataset，自己创建再清理。
 * 需要 Ollama 中存在 E2E_REAL_MODEL_NAME，且该模型已注册为 CHAT。
 */
test('真实模型门禁（自包含 Agent/数据集）', async ({ request }) => {
  test.skip(!process.env.E2E_REAL_MODEL, '设置 E2E_REAL_MODEL=1 后运行真实模型链路')
  test.setTimeout(240_000)

  const dev = await login(request, 'dev', 'Dev@123456', 'dev')
  const admin = await login(request, 'admin', ADMIN_PASSWORD)
  expect(dev).toBeTruthy()
  expect(admin).toBeTruthy()

  let agentId: number | null = null
  let datasetId: number | null = null
  let oldJudge = ''

  const config = await (await request.get(`${P}/admin/config`, {
    headers: auth(admin!)
  })).json()
  expect(config.code, JSON.stringify(config)).toBe(0)
  oldJudge = (config.data || []).find((row: any) => row.configKey === 'eval.gate-judge-model')?.configValue || ''

  try {
    const updated = await (await request.put(`${P}/admin/config`, {
      headers: auth(admin!),
      data: { configKey: 'eval.gate-judge-model', configValue: MODEL }
    })).json()
    expect(updated.code, JSON.stringify(updated)).toBe(0)

    const name = `E2E真实门禁-${Date.now()}`
    const created = await (await request.post(`${P}/agent`, {
      headers: auth(dev!),
      data: {
        name,
        description: '真实模型门禁自包含测试',
        systemPrompt: '你是客服助手，依据知识库简洁回答。',
        modelConfig: { modelName: MODEL, temperature: 0.1 },
        agentCapabilities: { functionCall: false },
        visibility: 'PRIVATE'
      }
    })).json()
    expect(created.code, JSON.stringify(created)).toBe(0)
    agentId = created.data.id as number

    const ds = await (await request.post(`${P}/eval/datasets`, {
      headers: auth(dev!),
      data: { name: `${name}-数据集`, description: '真实门禁', agentId }
    })).json()
    expect(ds.code, JSON.stringify(ds)).toBe(0)
    datasetId = ds.data.id as number

    const answerMarker = `REAL_ANSWER_${Date.now()}`
    const retrievalMarker = `REAL_RETRIEVAL_${Date.now()}`
    for (const item of [
      {
        question: answerMarker,
        expectedAnswer: '应说明退款条件和时效',
        scoreCriteria: '回答需切题、诚实、简洁',
        stepType: 'ANSWER'
      },
      {
        question: retrievalMarker,
        expectedAnswer: '',
        scoreCriteria: '',
        stepType: 'RETRIEVAL',
        expectedPayload: '{"chunk_ids":[999999999]}',
        stepConfig: '{"knowledgeBaseId":1,"topK":5}'
      }
    ]) {
      const added = await (await request.post(`${P}/eval/datasets/${datasetId}/items`, {
        headers: auth(dev!), data: item
      })).json()
      expect(added.code, JSON.stringify(added)).toBe(0)
    }

    const publish = await request.post(`${P}/agent/${agentId}/publish`, {
      headers: auth(dev!), data: { gateDatasetId: datasetId }, timeout: 240_000
    })
    expect(publish.status()).toBe(400)
    const publishBody = await publish.json()
    expect(publishBody.message).toContain('预发布测评未通过')

    const tasks = await (await request.get(`${P}/eval/tasks`, {
      headers: auth(dev!), params: { agentId }
    })).json()
    expect(tasks.code, JSON.stringify(tasks)).toBe(0)
    const task = (tasks.data.list || [])
      .filter((row: any) => row.evalType === 'PRE_RELEASE' && row.datasetId === datasetId)
      .sort((a: any, b: any) => b.id - a.id)[0]
    expect(task?.id, '应生成真实门禁任务').toBeTruthy()

    const detail = await (await request.get(`${P}/eval/tasks/${task.id}`, {
      headers: auth(dev!)
    })).json()
    expect(detail.code, JSON.stringify(detail)).toBe(0)
    expect(detail.data.status).toBe('DONE')
    const answer = (detail.data.results || []).find((row: any) => row.question === answerMarker)
    expect(answer, '应包含真实 ANSWER 结果').toBeTruthy()
    expect(String(answer.answer || '').length).toBeGreaterThan(0)
    expect(String(answer.judgeReason || '').length).toBeGreaterThan(0)
    expect(String(answer.judgeReason)).not.toContain('mock')
    expect(detail.data.stepGate.failedSteps).toContain('RETRIEVAL')
  } finally {
    if (datasetId != null) {
      await request.delete(`${P}/eval/datasets/${datasetId}`, { headers: auth(dev!) }).catch(() => {})
    }
    if (agentId != null) await deleteAgent(request, dev!, agentId)
    await request.put(`${P}/admin/config`, {
      headers: auth(admin!),
      data: { configKey: 'eval.gate-judge-model', configValue: oldJudge }
    }).catch(() => {})
  }
})
