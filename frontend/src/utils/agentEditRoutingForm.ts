/** Agent 编辑页模型路由配置回显，避免主组件继续膨胀。 */
export function hydrateRoutingForm(form: any, modelConfig: any) {
  const routing = modelConfig?.routing && typeof modelConfig.routing === 'object'
    ? modelConfig.routing : {}
  form.routingEnabled = routing.enabled === true
  form.routingClassifierModel = routing.classifierModel || ''
  form.routingSimpleModel = routing.simpleModel || ''
  form.routingComplexModel = routing.complexModel || ''
  form.routingRules = Array.isArray(routing.rules) ? routing.rules.map((r: any) => ({
    keywords: Array.isArray(r.match) ? r.match.join(',') : String(r.match || ''),
    model: r.model || ''
  })) : []
}
