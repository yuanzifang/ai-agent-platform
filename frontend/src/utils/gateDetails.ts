export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (ch) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[ch] as string)
}

export interface GateDetailLabels {
  failedSteps: string
  score: string
}

/** 把发布门禁的 failedSteps/failures 渲染为可放进 ElMessageBox 的安全 HTML。 */
export function renderGateFailures(gate: any, labels: GateDetailLabels): string {
  const stepGate = gate?.stepGate
  if (!stepGate) return ''
  const failed = Array.isArray(stepGate.failedSteps) ? stepGate.failedSteps : []
  const failures = Array.isArray(stepGate.failures) ? stepGate.failures : []
  if (!failed.length && !failures.length) return ''
  const parts: string[] = []
  if (failed.length) {
    parts.push(`<div><b>${escapeHtml(labels.failedSteps)}</b>${failed.map((v: any) => escapeHtml(String(v))).join(' / ')}</div>`)
  }
  if (failures.length) {
    const rows = failures.slice(0, 8).map((f: any) => {
      const score = f.score == null ? '-' : Number(f.score).toFixed(2)
      const reason = f.reason ? ` · ${escapeHtml(String(f.reason))}` : ''
      const trace = f.traceId ? ` · ${escapeHtml(String(f.traceId).slice(0, 16))}` : ''
      return `<div>• ${escapeHtml(String(f.stepType || ''))} ${escapeHtml(labels.score)}=${score}${reason}${trace}</div>`
    })
    parts.push(`<div style="max-height:180px;overflow:auto;margin-top:6px">${rows.join('')}</div>`)
  }
  return parts.join('')
}
