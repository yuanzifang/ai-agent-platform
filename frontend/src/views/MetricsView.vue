<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/agents')">
        <el-icon><ArrowLeft /></el-icon> {{ t('metricsPage.back') }}
      </el-button>
      <span class="page-title">{{ t('page.metrics') }} {{ agentName ? '· ' + agentName : '' }}</span>
      <el-select v-model="period" style="width: 150px" @change="onPeriodChange">
        <el-option value="24h" :label="t('metricsPage.last24')" />
        <el-option value="7d" :label="t('metricsPage.last7')" />
        <el-option value="30d" :label="t('metricsPage.last30')" />
        <el-option value="custom" :label="t('metricsPage.custom')" />
      </el-select>
      <el-date-picker v-if="period === 'custom'" v-model="customRange" type="datetimerange"
                      value-format="YYYY-MM-DD HH:mm:ss" :start-placeholder="t('metricsPage.start')" :end-placeholder="t('metricsPage.end')"
                      style="width: 360px" @change="load" />
      <el-button size="small" :loading="loading" @click="load">{{ t('metricsPage.refresh') }}</el-button>
    </div>

    <div class="cards">
      <el-tooltip v-for="c in cardDefs" :key="c.key" :content="c.tip" placement="top">
        <el-card shadow="never" class="metric-card" @click="drill(c.key)">
          <div class="k">{{ c.label }}</div>
          <div class="v">{{ c.fmt(metrics[c.key]?.value) }}</div>
          <div class="ratio" :class="ratioClass(metrics[c.key]?.changeRatio)">
            <template v-if="metrics[c.key]">{{ t('metricsPage.changePrefix') }}{{ ratioText(metrics[c.key].changeRatio) }}</template>
            <template v-else>—</template>
          </div>
        </el-card>
      </el-tooltip>
    </div>

    <div class="charts">
      <el-card shadow="never">
        <template #header>{{ t('metricsPage.activeTrend') }} ({{ trends.bucket === 'hour' ? t('metricsPage.byHour') : t('metricsPage.byDay') }} {{ t('metricsPage.dedupUser') }})</template>
        <el-empty v-if="emptyTrend(trends.activeUsers)" :description="t('metricsPage.noData')" :image-size="50" />
        <EChart v-else :option="activeUsersOption" height="240px" />
      </el-card>
      <el-card shadow="never">
        <template #header>{{ t('metricsPage.callsTrend') }} ({{ t('metricsPage.tracesPer') }}{{ trends.bucket === 'hour' ? t('metricsPage.byHour') : t('metricsPage.byDay') }})</template>
        <el-empty v-if="emptyTrend(trends.calls)" :description="t('metricsPage.noData')" :image-size="50" />
        <EChart v-else :option="callsErrorsOption" height="240px" />
      </el-card>
    </div>

    <div class="hint-line">
      {{ t('metricsPage.source') }}
      {{ t('metricsPage.drill') }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api/http'
import { ArrowLeft } from '@element-plus/icons-vue'
import EChart from '../components/EChart.vue'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const agentId = route.params.agentId ? Number(route.params.agentId) : undefined
const agentName = ref('')
const period = ref('24h')
const customRange = ref<[string, string] | null>(null)
const loading = ref(false)

const metrics = reactive<Record<string, any>>({})
const trends = reactive<any>({ bucket: 'hour', buckets: [], activeUsers: [], calls: [], errors: [] })

const cardDefs = [
  { key: 'cost', label: t('metricsPage.cost'), tip: t('metricsPage.costTip'), fmt: (v: any) => costFmt(v) },
  { key: 'sessions', label: t('metricsPage.sessions'), tip: t('metricsPage.sessionsTip'), fmt: (v: any) => intFmt(v) },
  { key: 'dau', label: t('metricsPage.dau'), tip: t('metricsPage.dauTip'), fmt: (v: any) => num(v).toFixed(1) },
  { key: 'sessionsPerUser', label: t('metricsPage.sessionsPerUser'), tip: t('metricsPage.spuTip'), fmt: (v: any) => num(v).toFixed(2) },
  { key: 'calls', label: t('metricsPage.calls'), tip: t('metricsPage.callsTip'), fmt: (v: any) => intFmt(v) },
  { key: 'successRate', label: t('metricsPage.successRate'), tip: t('metricsPage.successTip'), fmt: (v: any) => pct(v) },
  { key: 'p99LatencyMs', label: t('metricsPage.p99'), tip: t('metricsPage.p99Tip'), fmt: (v: any) => num(v) + ' ms' },
  { key: 'tokens', label: 'Token', tip: 'SUM(input_tokens + output_tokens)', fmt: (v: any) => intFmt(v) },
  { key: 'errorCount', label: t('metricsPage.errorCount'), tip: t('metricsPage.errorTip'), fmt: (v: any) => intFmt(v) }
]

const xLabels = computed(() =>
  (trends.buckets || []).map((b: string) => (trends.bucket === 'hour' ? b.slice(11) : b.slice(5))))

const activeUsersOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 40, right: 16, top: 20, bottom: 26 },
  xAxis: { type: 'category', data: xLabels.value },
  yAxis: { type: 'value', minInterval: 1 },
  series: [{
    name: t('metricsPage.activeUsers'), type: 'line', smooth: true, data: trends.activeUsers || [],
    itemStyle: { color: '#409EFF' }, areaStyle: { opacity: 0.12 }
  }]
}))

const callsErrorsOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: [t('metricsPage.callsSeries'), t('metricsPage.errorsSeries')] },
  grid: { left: 40, right: 40, top: 30, bottom: 26 },
  xAxis: { type: 'category', data: xLabels.value },
  yAxis: [
    { type: 'value', name: t('metricsPage.callsSeries'), minInterval: 1 },
    { type: 'value', name: t('metricsPage.errorsSeries'), minInterval: 1 }
  ],
  series: [
    { name: t('metricsPage.callsSeries'), type: 'bar', data: trends.calls || [], itemStyle: { color: '#409EFF' }, yAxisIndex: 0 },
    { name: t('metricsPage.errorsSeries'), type: 'line', data: trends.errors || [], itemStyle: { color: '#F56C6C' }, yAxisIndex: 1 }
  ]
}))

onMounted(async () => {
  if (!agentId) {
    const agents = await http.get('/agent', { params: { pageSize: 100 } }) as any
    const first = (agents.list || [])[0]
    router.replace(first ? `/metrics/${first.id}` : '/agents')
    return
  }
  const agents = await http.get('/agent', { params: { pageSize: 100 } }) as any
  agentName.value = (agents.list || []).find((a: any) => a.id === agentId)?.name || ''
  await load()
})

function onPeriodChange() {
  if (period.value !== 'custom') load()
}

async function load() {
  loading.value = true
  try {
    const params: any = { period: period.value }
    if (period.value === 'custom') {
      if (!customRange.value?.length) return
      params.startTime = customRange.value[0]
      params.endTime = customRange.value[1]
    }
    const data = await http.get(`/metrics/agent/${agentId}`, { params }) as any
    Object.assign(metrics, data.metrics || {})
    Object.assign(trends, data.trends || {})
  } finally {
    loading.value = false
  }
}

function drill(_key: string) {
  if (agentId) router.push(`/observability/${agentId}`)
}

function emptyTrend(list: number[] | undefined) {
  return !list || list.every(v => !v)
}

function num(v: any) { return Number(v || 0) }
function intFmt(v: any) { return num(v).toLocaleString() }
function costFmt(v: any) {
  const n = num(v)
  if (n === 0) return '¥0'
  return '¥' + (n >= 0.01 ? n.toFixed(4) : n.toFixed(6))
}
function pct(v: any, digits = 1) { return num(v).toFixed(digits) + '%' }
function ratioText(r: any) {
  const n = num(r)
  if (n > 0) return `↑${n.toFixed(1)}%`
  if (n < 0) return `↓${Math.abs(n).toFixed(1)}%`
  return '0%'
}
function ratioClass(r: any) {
  const n = num(r)
  return n > 0 ? 'up' : n < 0 ? 'down' : 'flat'
}
</script>

<style scoped>
.page { max-width: 1240px; margin: 0 auto; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; flex: 1; }
.cards { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin-bottom: 14px; }
.metric-card { cursor: pointer; }
.metric-card :deep(.el-card__body) { padding: 12px; }
.k { font-size: 12px; color: #909399; white-space: nowrap; }
.v { font-size: 19px; font-weight: 700; margin-top: 6px; white-space: nowrap; }
.ratio { font-size: 12px; margin-top: 4px; color: #909399; }
.ratio.up { color: #f56c6c; }
.ratio.down { color: #67c23a; }
.ratio.flat { color: #909399; }
.charts { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.charts :deep(.el-card__body) { padding: 8px 12px 4px; }
.hint-line { color: #909399; font-size: 12px; margin-top: 12px; }

@media (max-width: 768px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  .charts { grid-template-columns: 1fr; }
  .toolbar { flex-wrap: wrap; row-gap: 8px; }
}
</style>