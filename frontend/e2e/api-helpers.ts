import { test, expect, type APIRequestContext } from '@playwright/test'
import path from 'path'
import { createHmac, randomUUID } from 'node:crypto'

export const P = '/api/v1'
export const MOCK = 'http://127.0.0.1:18092'
export const MCP_MOCK = 'http://127.0.0.1:18093'
export const BACKEND = process.env.E2E_BACKEND_URL || 'http://localhost:8080'
export const GATEWAY = `${BACKEND}${P}/gateway/call`
export const OPENAI_MOCK = 'http://127.0.0.1:18080'

export function auth(token: string) {
  return { Authorization: `Bearer ${token}` }
}

export async function login(request: APIRequestContext, username: string, password: string, tenantKey?: string) {
  const res = await request.post(`${P}/auth/login`, {
    data: { username, password, tenantKey }
  })
  const body = await res.json()
  return body?.code === 0 ? (body.data.accessToken as string) : null
}

export async function createAgent(request: APIRequestContext, token: string, name: string): Promise<number> {
  const res = await request.post(`${P}/agent`, {
    headers: auth(token),
    data: {
      name,
      description: 'E2E API 集成测试',
      systemPrompt: '你是测试助手，简洁回答。',
      modelConfig: { modelName: 'deepseek-flash', temperature: 0.2 },
      agentCapabilities: { functionCall: true },
      tools: [{ toolId: 1, enabled: true }],
      visibility: 'PRIVATE'
    }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.id as number
}

export async function createAgentWithoutTools(request: APIRequestContext, token: string, name: string): Promise<number> {
  const res = await request.post(`${P}/agent`, {
    headers: auth(token),
    data: {
      name,
      description: 'E2E 回传 Agent（无工具）',
      systemPrompt: '你负责复核交接结果，简洁回答。',
      modelConfig: { modelName: 'deepseek-flash', temperature: 0.2 },
      agentCapabilities: { functionCall: false },
      tools: [],
      visibility: 'PRIVATE'
    }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.id as number
}

export async function publishAgent(request: APIRequestContext, token: string, agentId: number) {
  const res = await request.post(`${P}/agent/${agentId}/publish`, {
    headers: auth(token), data: { force: true }
  })
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
}

export async function deleteAgent(request: APIRequestContext, token: string, agentId: number) {
  await request.delete(`${P}/agent/${agentId}`, { headers: auth(token) })
}

