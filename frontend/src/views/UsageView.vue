<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.usage') }}</span>
      <el-select v-model="days" style="width: 140px" @change="load">
        <el-option :value="7" :label="t('usage.last7')" />
        <el-option :value="30" :label="t('usage.last30')" />
        <el-option :value="90" :label="t('usage.last90')" />
      </el-select>
    </div>

    <div class="cards">
      <el-card shadow="never"><div class="k">{{ t('usage.requests') }}</div><div class="v">{{ summary.totalRequests }}</div></el-card>
      <el-card shadow="never"><div class="k">{{ t('usage.inputTokens') }}</div><div class="v">{{ fmt(summary.totalInputTokens) }}</div></el-card>
      <el-card shadow="never"><div class="k">{{ t('usage.outputTokens') }}</div><div class="v">{{ fmt(summary.totalOutputTokens) }}</div></el-card>
      <el-card shadow="never"><div class="k">{{ t('usage.cost') }}</div><div class="v">¥{{ money(summary.totalCost) }}</div></el-card>
    </div>

    <div class="cols">
      <el-card shadow="never" class="col">
        <template #header>{{ t('usage.dailyTrend') }}</template>
        <div v-for="d in summary.byDay" :key="d.date" class="bar-row">
          <span class="bar-date">{{ d.date.slice(5) }}</span>
          <div class="bar-track">
            <div class="bar in" :style="{ width: pct(d.inputTokens) }" :title="`${t('usage.input')} ${d.inputTokens}`" />
            <div class="bar out" :style="{ width: pct(d.outputTokens) }" :title="`${t('usage.output')} ${d.outputTokens}`" />
          </div>
          <span class="bar-num">{{ fmt(d.inputTokens + d.outputTokens) }} · ¥{{ money(d.cost) }}</span>
        </div>
        <el-empty v-if="summary.byDay.length === 0" :description="t('usage.noData')" :image-size="60" />
      </el-card>

      <el-card shadow="never" class="col">
        <template #header>{{ t('usage.byModel') }}</template>
        <el-table :data="summary.byModel" size="small">
          <el-table-column prop="modelName" :label="t('usage.model')" />
          <el-table-column prop="count" :label="t('usage.count')" width="70" />
          <el-table-column :label="t('usage.input')" width="90">
            <template #default="{ row }">{{ fmt(row.inputTokens) }}</template>
          </el-table-column>
          <el-table-column :label="t('usage.output')" width="90">
            <template #default="{ row }">{{ fmt(row.outputTokens) }}</template>
          </el-table-column>
          <el-table-column :label="t('usage.costShort')" width="90">
            <template #default="{ row }">¥{{ money(row.cost) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-card shadow="never" class="mt">
      <template #header>{{ t('usage.details') }}</template>
      <el-table :data="rows" size="small">
        <el-table-column prop="createdAt" :label="t('usage.time')" width="170">
          <template #default="{ row }">{{ String(row.createdAt).replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column prop="source" :label="t('usage.source')" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ sourceText(row.source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="agentId" label="Agent" width="80">
          <template #default="{ row }">{{ row.agentId ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="modelName" :label="t('usage.model')" />
        <el-table-column prop="inputTokens" :label="t('usage.input')" width="90" />
        <el-table-column prop="outputTokens" :label="t('usage.output')" width="90" />
        <el-table-column :label="t('usage.costShort')" width="100">
          <template #default="{ row }">¥{{ money(row.cost) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize"
                     :total="total" layout="prev, pager, next" class="mt" @current-change="load" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const { t } = useI18n()

const days = ref(30)
const page = ref(1)
const pageSize = 20
const total = ref(0)
const rows = ref<any[]>([])
const summary = reactive<any>({ totalRequests: 0, totalInputTokens: 0, totalOutputTokens: 0,
  totalCost: 0, byModel: [], byDay: [] })
const maxDay = ref(1)

async function load() {
  const data = await http.get('/billing/usage', {
    params: { days: days.value, page: page.value, pageSize }
  }) as any
  Object.assign(summary, data.summary)
  rows.value = data.list || []
  total.value = data.total || 0
  maxDay.value = Math.max(1, ...(summary.byDay || []).map((d: any) => d.inputTokens + d.outputTokens))
}

function money(v?: number | string) {
  const n = Number(v || 0)
  return n.toFixed(n !== 0 && Math.abs(n) < 0.01 ? 6 : 4)
}

function pct(v: number) {
  return Math.max(2, Math.round(v / maxDay.value * 100)) + '%'
}
function fmt(v: number) {
  return (v ?? 0).toLocaleString()
}
function sourceText(s: string) {
  return s === 'chat' ? t('usage.chat') : s === 'debug' ? t('usage.debug') : s === 'embedding' ? t('usage.embedding') : s
}

onMounted(load)
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 14px; }
.k { font-size: 13px; color: #909399; }
.v { font-size: 26px; font-weight: 700; margin-top: 6px; }
.cols { display: grid; grid-template-columns: 1.2fr 1fr; gap: 14px; }
.mt { margin-top: 14px; }
.bar-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 12px; }
.bar-date { width: 42px; color: #909399; flex-shrink: 0; }
.bar-track { flex: 1; display: flex; gap: 2px; background: #f5f7fa; border-radius: 3px; overflow: hidden; height: 14px; }
.bar { height: 100%; border-radius: 2px; }
.bar.in { background: #409eff; }
.bar.out { background: #67c23a; }
.bar-num { width: 70px; text-align: right; color: #606266; flex-shrink: 0; }
</style>