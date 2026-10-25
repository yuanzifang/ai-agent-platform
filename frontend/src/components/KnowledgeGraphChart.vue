<template>
  <div ref="el" class="kg-chart" :style="{ height: height || '560px' }" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([GraphChart, LegendComponent, TooltipComponent, CanvasRenderer])

export interface GraphCategory {
  name: string
  itemStyle?: { color?: string }
}

export interface GraphNode {
  id: string
  name: string
  category: number
  symbolSize: number
  value: any
}

export interface GraphLink {
  source: string
  target: string
  value: string
  valueObj: any
}

const props = defineProps<{
  nodes: GraphNode[]
  links: GraphLink[]
  categories: GraphCategory[]
  height?: string
}>()

const emit = defineEmits<{
  (e: 'node-click', value: any): void
  (e: 'edge-click', value: any): void
}>()

const { t, locale } = useI18n()
const el = ref<HTMLElement>()
let chart: echarts.ECharts | null = null

function render() {
  if (!el.value) return
  if (!chart) {
    chart = echarts.init(el.value)
    chart.on('click', (params: any) => {
      if (params?.dataType === 'node') emit('node-click', params.data?.value)
      else if (params?.dataType === 'edge') emit('edge-click', params.data?.valueObj)
    })
  }
  const manyLinks = props.links.length > 45
  chart.setOption({
    backgroundColor: 'transparent',
    color: ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#9b59b6', '#16a085', '#d35400'],
    tooltip: {
      confine: true,
      formatter: (params: any) => {
        if (params.dataType === 'edge') {
          const r = params.data?.valueObj || {}
          return `<b>${escapeHtml(params.data?.value || r.relation || '')}</b><br/>`
            + `${escapeHtml(r.sourceName || '')} → ${escapeHtml(r.targetName || '')}<br/>`
            + `${t('graphPage.weight')} ${r.weight ?? '-'} · ${t('graphPage.confidence')} ${r.confidence ?? '-'}`
            + (r.evidence ? `<br/><span style="color:#909399">${escapeHtml(String(r.evidence).slice(0, 120))}</span>` : '')
        }
        const entity = params.data?.value || {}
        return `<b>${escapeHtml(entity.name || '')}</b>`
          + (entity.entityType ? ` <span style="color:#409eff">${escapeHtml(entity.entityType)}</span>` : '')
          + (entity.description ? `<br/><span style="color:#606266">${escapeHtml(String(entity.description).slice(0, 160))}</span>` : '')
          + `<br/><span style="color:#909399">${entity.source === 'MANUAL' ? t('graphPage.sourceManual') : t('graphPage.sourceAuto')} · ${t('graphPage.confidence')} ${entity.confidence ?? '-'}</span>`
      }
    },
    legend: [{
      type: 'scroll',
      bottom: 0,
      data: props.categories.map(c => c.name),
      textStyle: { color: '#606266', fontSize: 11 },
      itemWidth: 10,
      itemHeight: 10
    }],
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      cursor: 'pointer',
      data: props.nodes,
      links: props.links,
      categories: props.categories,
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 7,
      force: {
        repulsion: 260,
        edgeLength: [80, 170],
        gravity: 0.08,
        friction: 0.55,
        layoutAnimation: true
      },
      label: {
        show: true,
        position: 'right',
        formatter: '{b}',
        fontSize: 11,
        color: '#303133'
      },
      labelLayout: { hideOverlap: true },
      lineStyle: {
        color: 'source',
        curveness: 0.12,
        opacity: 0.55,
        width: 1.2
      },
      edgeLabel: {
        show: !manyLinks,
        formatter: (params: any) => params.data?.value || '',
        fontSize: 9,
        color: '#909399'
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 3, opacity: 0.9 },
        label: { fontWeight: 'bold' }
      },
      itemStyle: {
        borderColor: '#fff',
        borderWidth: 2,
        shadowBlur: 6,
        shadowColor: 'rgba(0,0,0,.12)'
      }
    }]
  }, true)
}

function escapeHtml(value: string) {
  return String(value || '').replace(/[&<>"']/g, ch => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  } as Record<string, string>)[ch] || ch)
}

function resize() {
  chart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
  if (typeof ResizeObserver !== 'undefined' && el.value) {
    const observer = new ResizeObserver(() => resize())
    observer.observe(el.value)
    ;(el.value as any).__kgObserver = observer
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  const observer = (el.value as any)?.__kgObserver
  observer?.disconnect()
  chart?.dispose()
  chart = null
})

watch(() => [props.nodes, props.links, props.categories], render, { deep: true })
watch(locale, render)
</script>

<style scoped>
.kg-chart {
  width: 100%;
  min-height: 360px;
  background: radial-gradient(circle at 50% 45%, #ffffff 0%, #f7f9fc 100%);
  border-radius: 6px;
}
</style>
