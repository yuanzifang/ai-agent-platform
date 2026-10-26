import { describe, expect, it } from 'vitest'
import { escapeHtml, renderGateFailures } from './gateDetails'

const labels = { failedSteps: '失败步骤：', score: '得分' }

describe('gateDetails', () => {
  it('renders failed steps and failures with escaped content', () => {
    const html = renderGateFailures({
      stepGate: {
        failedSteps: ['RETRIEVAL', '<ANSWER>'],
        failures: [{
          stepType: 'RETRIEVAL', score: 0, reason: '<script>alert(1)</script>',
          traceId: 'trace-1234567890abcdef'
        }]
      }
    }, labels)
    expect(html).toContain('失败步骤：')
    expect(html).toContain('RETRIEVAL')
    expect(html).toContain('&lt;ANSWER&gt;')
    expect(html).toContain('得分=0.00')
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;')
    expect(html).not.toContain('<script>')
  })

  it('returns empty for skipped or detail-less gates, and escapes special chars', () => {
    expect(renderGateFailures({ skipped: true }, labels)).toBe('')
    expect(renderGateFailures({ stepGate: { failedSteps: [], failures: [] } }, labels)).toBe('')
    expect(escapeHtml(`<a href="x">'&`)).toBe('&lt;a href=&quot;x&quot;&gt;&#39;&amp;')
  })

  it('caps failure rows at eight', () => {
    const failures = Array.from({ length: 12 }, (_, i) => ({ stepType: `S${i}`, score: 0 }))
    const html = renderGateFailures({ stepGate: { failedSteps: ['RETRIEVAL'], failures } }, labels)
    expect(html).toContain('S0')
    expect(html).toContain('S7')
    expect(html).not.toContain('S8')
  })
})
