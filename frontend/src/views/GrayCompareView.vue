<template>
  <div class="page">
    <h2>{{ t('grayPage.title') }}</h2>
    <p class="desc">{{ t('grayPage.desc') }}</p>

    <div class="toolbar">
      <el-input v-model="tag" :placeholder="t('grayPage.tagPh')" style="width: 220px" clearable @keyup.enter="load" />
      <el-select v-model="days" style="width: 140px" @change="loadAll">
        <el-option :label="t('grayPage.last1d')" :value="1" />
        <el-option :label="t('grayPage.last7d')" :value="7" />
        <el-option :label="t('grayPage.last30d')" :value="30" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="loadAll">{{ t('app.refresh') }}</el-button>
    </div>

    <template v-if="result">
      <div class="groups">
        <div v-for="group in ['baseline', 'gray']" :key="group" class="group-card">
          <h3>{{ group === 'gray' ? t('grayPage.grayGroup') : t('grayPage.baselineGroup') }}</h3>
          <div class="stat"><span>{{ t('grayPage.requests') }}</span><b>{{ stat(group, 'requests') }}</b></div>
          <div class="stat"><span>{{ t('grayPage.errorRate') }}</span><b>{{ pct(stat(group, 'errorRate')) }}</b></div>
          <div class="stat"><span>{{ t('grayPage.avgLatency') }}</span><b>{{ stat(group, 'avgLatencyMs') }} ms</b></div>
          <div class="stat"><span>P95</span><b>{{ stat(group, 'p95LatencyMs') }} ms</b></div>
          <div class="stat"><span>{{ t('grayPage.avgTokens') }}</span><b>{{ stat(group, 'avgTotalTokens') }}</b></div>
          <div class="stat"><span>{{ t('grayPage.totalCost') }}</span><b>{{ stat(group, 'totalCost') }}</b></div>
        </div>
        <div class="group-card delta">
          <h3>{{ t('grayPage.delta') }}</h3>
          <div v-for="(v, k) in result.delta" :key="k" class="stat">
            <span>{{ k }}</span><b :class="Number(v) > 0 ? 'up' : 'down'">{{ v }}</b>
          </div>
        </div>
      </div>

      <h3>{{ t('grayPage.byModel') }}</h3>
      <div class="models">
        <div v-for="group in ['baseline', 'gray']" :key="group" class="model-block">
          <h4>{{ group }}</h4>
          <el-table :data="result[group].byModel" size="small" border>
            <el-table-column prop="model_name" label="Model" min-width="160" />
            <el-table-column prop="requests" :label="t('grayPage.requests')" width="90" />
            <el-table-column prop="avg_latency" :label="t('grayPage.avgLatency')" width="120" />
            <el-table-column prop="errors" :label="t('grayPage.errors')" width="80" />
          </el-table>
        </div>
      </div>

      <h3>{{ t('grayPage.evalCompare') }}</h3>
      <div class="groups">
        <div v-for="group in ['baseline', 'gray']" :key="group" class="group-card">
          <h3>{{ group }}</h3>
          <div class="stat"><span>{{ t('grayPage.evals') }}</span><b>{{ result.eval[group].evals }}</b></div>
          <div class="stat"><span>{{ t('grayPage.avgScore') }}</span><b>{{ result.eval[group].avgScore }}</b></div>
          <div class="stat"><span>{{ t('grayPage.passRate') }}</span><b>{{ pct(result.eval[group].passRate) }}</b></div>
        </div>
      </div>
    </template>
    <el-empty v-else-if="!loading" :description="t('grayPage.emptyHint')" />

    <!-- 黄金数据集 A/B 逐数据集对比 -->
    <template v-if="evalData && evalData.length">
      <h2 style="margin-top: 32px">{{ t('grayPage.evalDetail') }}</h2>
      <div v-for="ds in evalData" :key="ds.datasetId" class="ds-block">
        <h3>{{ ds.datasetName || ds.datasetId }}</h3>
        <div class="groups">
          <div v-for="grp in ['baseline', 'gray']" :key="grp" class="group-card">
            <h4>{{ grp === 'gray' ? t('grayPage.grayGroup') : t('grayPage.baselineGroup') }}</h4>
            <div class="stat"><span>{{ t('grayPage.items') }}</span><b>{{ ds[grp]?.items ?? '-' }}</b></div>
            <div class="stat"><span>{{ t('grayPage.avgScore') }}</span><b>{{ ds[grp]?.avgScore ?? '-' }}</b></div>
            <div class="stat"><span>{{ t('grayPage.passRate') }}</span><b>{{ pct(ds[grp]?.passRate) }}</b></div>
            <template v-if="ds[grp]?.byStepType">
              <div v-for="(st, stName) in ds[grp].byStepType" :key="stName" class="stat sub">
                <span>{{ stName }}</span>
                <b>{{ st.avgScore }} / {{ pct(st.passRate) }}</b>
              </div>
            </template>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()
const tag = ref('')
const days = ref(7)
const loading = ref(false)
const result = ref<any>(null)
const evalData = ref<any[]>([])

function stat(group: string, key: string) {
  const v = result.value?.[group]?.[key]
  return v === undefined || v === null ? '-' : v
}
function pct(v: unknown) {
  const n = Number(v)
  return Number.isNaN(n) ? '-' : (n * 100).toFixed(2) + '%'
}

async function loadEvalCompare() {
  try {
    evalData.value = await http.get('/observability/gray/eval-compare', {
      params: { days: days.value }
    })
  } catch { /* 无数据时静默 */ }
}

async function loadCompare() {
  if (!tag.value.trim()) return
  result.value = await http.get('/observability/gray/compare', {
    params: { tag: tag.value.trim(), days: days.value }
  })
}

async function load() {
  loading.value = true
  try {
    await Promise.allSettled([loadCompare(), loadEvalCompare()])
  } finally {
    loading.value = false
  }
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.allSettled([loadCompare(), loadEvalCompare()])
  } finally {
    loading.value = false
  }
}

onMounted(() => loadEvalCompare())
</script>

<style scoped>
.page { padding: 20px; }
.desc { color: var(--el-text-color-secondary); font-size: 13px; margin: 0 0 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.groups { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
.group-card { flex: 1; min-width: 220px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 12px 16px; }
.group-card h3 { margin: 0 0 8px; font-size: 14px; }
.group-card.delta { background: var(--el-fill-color-light); }
.stat { display: flex; justify-content: space-between; padding: 3px 0; font-size: 13px; }
.stat.sub { padding-left: 12px; color: var(--el-text-color-secondary); }
.stat b.up { color: var(--el-color-danger); }
.stat b.down { color: var(--el-color-success); }
.models { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
.model-block { flex: 1; min-width: 320px; }
.model-block h4 { margin: 4px 0 6px; font-size: 13px; }
.ds-block { margin-bottom: 20px; }
.ds-block h3 { font-size: 14px; margin: 8px 0 8px; }
h3 { font-size: 15px; margin: 16px 0 8px; }
</style>
