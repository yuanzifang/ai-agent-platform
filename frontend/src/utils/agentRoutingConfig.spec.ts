import { describe, expect, it } from 'vitest'
import { buildKbRetrievalConfig, buildModelRoutingConfig } from './agentRoutingConfig'

describe('agentRoutingConfig', () => {
  it('serializes routing rules and trims empty values', () => {
    const result = buildModelRoutingConfig({
      routingEnabled: true,
      routingClassifierModel: 'fast-model',
      routingSimpleModel: 'small-model',
      routingComplexModel: 'large-model',
      routingRules: [
        { keywords: '代码， 编程', model: 'large-model' },
        { keywords: '  ', model: 'large-model' },
        { keywords: '售后', model: '' }
      ]
    })

    expect(result.enabled).toBe(true)
    expect(result.rules).toEqual([
      { match: ['代码', '编程'], model: 'large-model', intent: 'keyword' }
    ])
  })

  it('keeps routing disabled when the switch is off', () => {
    const result = buildModelRoutingConfig({
      routingEnabled: false,
      routingRules: [{ keywords: '代码', model: 'large-model' }]
    })

    expect(result.enabled).toBe(false)
    expect(result.rules).toHaveLength(1)
  })

  it('serializes rerank and keeps an explicit false', () => {
    const base = {
      topK: 3, scoreThreshold: 0.3, mode: 'hybrid',
      documentType: 'general', sectionType: 'body',
      regulatoryCategory: '', chunkLevel: 1, minRelevance: 0.2
    }

    expect(buildKbRetrievalConfig({ ...base, rerank: false }).rerank).toBe(false)
    expect(buildKbRetrievalConfig({ ...base }).rerank).toBe(false)
    expect(buildKbRetrievalConfig({ ...base, rerank: true }).rerank).toBe(true)
    expect(buildKbRetrievalConfig({ ...base, rerankTopN: 2, rerankScoreThreshold: 0.15 }).rerankTopN).toBe(2)
    expect(buildKbRetrievalConfig({ ...base, rerankTopN: 2, rerankScoreThreshold: 0.15 }).rerankScoreThreshold).toBe(0.15)
    expect(buildKbRetrievalConfig({ ...base }).topK).toBe(3)
  })
})
