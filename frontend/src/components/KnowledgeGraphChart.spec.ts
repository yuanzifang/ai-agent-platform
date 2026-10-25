import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

const echartsMock = vi.hoisted(() => {
  const chart = {
    setOption: vi.fn(),
    on: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn()
  }
  return { chart, init: vi.fn(() => chart), use: vi.fn() }
})

vi.mock('echarts/core', () => ({
  init: echartsMock.init,
  use: echartsMock.use
}))
vi.mock('echarts/charts', () => ({ GraphChart: {} }))
vi.mock('echarts/components', () => ({ LegendComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import KnowledgeGraphChart from './KnowledgeGraphChart.vue'

function mountChart() {
  const i18n = createI18n({
    legacy: false,
    locale: 'zh-CN',
    messages: {
      'zh-CN': {
        graphPage: {
          weight: '权重',
          confidence: '置信度',
          sourceManual: '手工',
          sourceAuto: '自动'
        }
      }
    }
  })
  return mount(KnowledgeGraphChart, {
    props: {
      nodes: [{
        id: '1',
        name: '实体',
        category: 0,
        symbolSize: 20,
        value: { name: '实体' }
      }],
      links: [],
      categories: [{ name: '概念' }]
    },
    global: { plugins: [i18n] }
  })
}

describe('KnowledgeGraphChart', () => {
  it('escapes tooltip HTML from entity and relation values', () => {
    const wrapper = mountChart()
    expect(echartsMock.init).toHaveBeenCalledTimes(1)
    const calls = echartsMock.chart.setOption.mock.calls
    const option = calls[calls.length - 1]?.[0] as any

    const nodeHtml = option.tooltip.formatter({
      dataType: 'node',
      data: {
        value: {
          name: '<img src=x onerror="window.__xss=1">',
          description: '<script>window.__xss=1</script>',
          entityType: '概念',
          source: 'AUTO',
          confidence: 0.9
        }
      }
    })
    expect(nodeHtml).not.toContain('<img')
    expect(nodeHtml).not.toContain('<script')
    expect(nodeHtml).toContain('&lt;img')

    const edgeHtml = option.tooltip.formatter({
      dataType: 'edge',
      data: {
        value: '<b>关系</b>',
        valueObj: {
          sourceName: '<svg onload=1>',
          targetName: '目标',
          weight: 1,
          confidence: 0.8,
          evidence: '<iframe>'
        }
      }
    })
    expect(edgeHtml).not.toContain('<b>关系</b>')
    expect(edgeHtml).not.toContain('<svg')
    expect(edgeHtml).not.toContain('<iframe')
    wrapper.unmount()
  })

  it('emits node and edge click payloads', () => {
    const wrapper = mountChart()
    const onClick = echartsMock.chart.on.mock.calls.find(call => call[0] === 'click')?.[1] as Function
    expect(onClick).toBeTypeOf('function')

    onClick({ dataType: 'node', data: { value: { id: '1', name: '实体' } } })
    onClick({ dataType: 'edge', data: { valueObj: { id: 'r1', relation: '关联' } } })

    expect(wrapper.emitted('node-click')?.[0]?.[0]).toEqual({ id: '1', name: '实体' })
    expect(wrapper.emitted('edge-click')?.[0]?.[0]).toEqual({ id: 'r1', relation: '关联' })
    wrapper.unmount()
  })
})
