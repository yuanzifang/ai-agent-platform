/** Agent 模型路由与知识库重排的纯序列化函数，便于前端单测。 */

export function buildModelRoutingConfig(form: any) {
  return {
    enabled: form.routingEnabled === true,
    classifierModel: form.routingClassifierModel || null,
    simpleModel: form.routingSimpleModel || null,
    complexModel: form.routingComplexModel || null,
    rules: (Array.isArray(form.routingRules) ? form.routingRules : [])
      .map((r: any) => ({
        match: String(r.keywords || '').split(/[,，]/).map((x: string) => x.trim()).filter(Boolean),
        model: String(r.model || '').trim(),
        intent: 'keyword'
      }))
      .filter((r: any) => r.match.length > 0 && r.model)
  }
}

export function buildKbRetrievalConfig(k: any) {
  return {
    topK: k.topK,
    scoreThreshold: k.scoreThreshold,
    mode: k.mode,
    rerank: k.rerank === true,
    rerankTopN: k.rerankTopN,
    rerankScoreThreshold: k.rerankScoreThreshold,
    documentType: k.documentType || undefined,
    sectionType: k.sectionType || undefined,
    regulatoryCategory: k.regulatoryCategory || undefined,
    chunkLevel: k.chunkLevel,
    minRelevance: k.minRelevance
  }
}
