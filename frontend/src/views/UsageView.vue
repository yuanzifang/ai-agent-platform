<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">用量看板</span>
      <el-select v-model="days" style="width: 140px" @change="load">
        <el-option :value="7" label="近 7 天" />
        <el-option :value="30" label="近 30 天" />
        <el-option :value="90" label="近 90 天" />
      </el-select>
    </div>

    <div class="cards">
      <el-card shadow="never"><div class="k">请求次数</div><div class="v">{{ summary.totalRequests }}</div></el-card>
      <el-card shadow="never"><div class="k">输入 Tokens</div><div class="v">{{ fmt(summary.totalInputTokens) }}</div></el-card>
      <el-card shadow="never"><div class="k">输出 Tokens</div><div class="v">{{ fmt(summary.totalOutputTokens) }}</div></el-card>
      <el-card shadow="never"><div class="k">成本（元）</div><div class="v">¥{{ money(summary.totalCost) }}</div></el-card>
    </div>

    <div class="cols">
      <el-card shadow="never" class="col">
        <template #header>按日趋势（输入/输出 Tokens）</template>
        <div v-for="d in summary.byDay" :key="d.date" class="bar-row">
          <span class="bar-date">{{ d.date.slice(5) }}</span>
          <div class="bar-track">
            <div class="bar in" :style="{ width: pct(d.inputTokens) }" :title="`输入 ${d.inputTokens}`" />
            <div class="bar out" :style="{ width: pct(d.outputTokens) }" :title="`输出 ${d.outputTokens}`" />
          </div>
          <span class="bar-num">{{ fmt(d.inputTokens + d.outputTokens) }} · ¥{{ money(d.cost) }}</span>
        </div>
        <el-empty v-if="summary.byDay.length === 0" description="暂无数据" :image-size="60" />
      </el-card>

      <el-card shadow="never" class="col">
        <template #header>按模型</template>
        <el-table :data="summary.byModel" size="small">
          <el-table-column prop="modelName" label="模型" />
          <el-table-column prop="count" label="次数" width="70" />
          <el-table-column label="输入" width="90">
            <template #default="{ row }">{{ fmt(row.inputTokens) }}</template>
          </el-table-column>
          <el-table-column label="输出" width="90">
            <template #default="{ row }">{{ fmt(row.outputTokens) }}</template>
          </el-table-column>
          <el-table-column label="成本" width="90">
            <template #default="{ row }">¥{{ money(row.cost) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-card shadow="never" class="mt">
      <template #header>调用明细</template>
      <el-table :data="rows" size="small">
        <el-table-column prop="createdAt" label="时间" width="170">
          <template #default="{ row }">{{ String(row.createdAt).replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ sourceText(row.source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="agentId" label="Agent" width="80">
          <template #default="{ row }">{{ row.agentId ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型" />
        <el-table-column prop="inputTokens" label="输入" width="90" />
        <el-table-column prop="outputTokens" label="输出" width="90" />
        <el-table-column label="成本" width="100">
          <template #default="{ row }">¥{{ money(row.cost) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize"
                     :total="total" layout="prev, pager, next" class="mt" @current-change="load" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import http from '../api/http'

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
  return s === 'chat' ? '对话' : s === 'debug' ? '调试' : s === 'embedding' ? '嵌入' : s
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
