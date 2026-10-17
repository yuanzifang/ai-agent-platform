<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/agents')">
        <el-icon><ArrowLeft /></el-icon> 返回
      </el-button>
      <span class="page-title">可观测 {{ agentName ? '· ' + agentName : '' }}</span>
      <el-select v-model="days" style="width: 130px" @change="loadAll">
        <el-option :value="7" label="近 7 天" />
        <el-option :value="30" label="近 30 天" />
      </el-select>
    </div>

    <div class="cards">
      <el-card shadow="never"><div class="k">Trace 数</div><div class="v">{{ overview.traceCount }}</div></el-card>
      <el-card shadow="never"><div class="k">成功率</div><div class="v">{{ overview.successRate }}%</div></el-card>
      <el-card shadow="never"><div class="k">平均延迟</div><div class="v">{{ overview.avgLatencyMs }} ms</div></el-card>
      <el-card shadow="never"><div class="k">Tokens</div><div class="v">{{ Number(overview.totalTokens || 0).toLocaleString() }}</div></el-card>
    </div>

    <el-card shadow="never" class="mb">
      <template #header>模型分布（Token 占比）</template>
      <div v-for="m in overview.byModel" :key="m.modelName" class="m-row">
        <span class="m-name">{{ m.modelName }}</span>
        <div class="m-track">
          <div class="m-bar" :style="{ width: pct(m.tokens) }" />
        </div>
        <span class="m-num">{{ m.count }} 次 · {{ Number(m.tokens).toLocaleString() }}</span>
      </div>
      <el-empty v-if="(overview.byModel || []).length === 0" description="暂无数据，先去对话几轮" :image-size="60" />
    </el-card>

    <el-card shadow="never">
      <template #header>Trace 列表（点击查看 Span 树）</template>
      <el-table :data="traces" size="small" @row-click="openTrace">
        <el-table-column prop="traceId" label="Trace ID" min-width="240">
          <template #default="{ row }">
            <span class="tid">{{ row.traceId.slice(0, 16) }}…</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : row.status === 'STOPPED' ? 'warning' : 'danger'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型" width="140" />
        <el-table-column label="Tokens" width="120">
          <template #default="{ row }">{{ row.inputTokens }} / {{ row.outputTokens }}</template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="延迟" width="90">
          <template #default="{ row }">{{ row.latencyMs }}ms</template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="traceVisible" :title="'Span 树 · ' + (current.traceId || '')" size="560px">
      <el-descriptions :column="2" border size="small" class="mb">
        <el-descriptions-item label="状态">{{ current.status }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ current.modelName }}</el-descriptions-item>
        <el-descriptions-item label="Tokens">{{ current.inputTokens }} / {{ current.outputTokens }}</el-descriptions-item>
        <el-descriptions-item label="总延迟">{{ current.latencyMs }}ms</el-descriptions-item>
      </el-descriptions>
      <div v-for="(s, i) in current.spans || []" :key="i" class="span-row" :class="s.spanType">
        <span class="dot" :class="s.spanType" />
        <span class="s-type">{{ s.spanType }}</span>
        <span class="s-name">{{ s.name }}</span>
        <span class="s-meta">
          <template v-if="s.spanType === 'llm'">{{ s.inputTokens }}/{{ s.outputTokens }} tok · </template>
          <template v-if="s.spanType === 'retrieval'">{{ attrHits(s) }} 命中 · </template>
          {{ s.latencyMs }}ms · {{ s.status }}
        </span>
      </div>
      <div class="legend">
        <span><i class="dot agent" /> agent</span>
        <span><i class="dot chain" /> chain</span>
        <span><i class="dot llm" /> llm</span>
        <span><i class="dot retrieval" /> retrieval</span>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/http'
import { ArrowLeft } from '@element-plus/icons-vue'

const route = useRoute()
const agentId = route.params.agentId ? Number(route.params.agentId) : undefined
const agentName = ref('')
const days = ref(30)
const overview = reactive<any>({ traceCount: 0, successRate: 0, avgLatencyMs: 0, totalTokens: 0, byModel: [] })
const traces = ref<any[]>([])
const traceVisible = ref(false)
const current = reactive<any>({})
const maxTokens = computed(() => Math.max(1, ...(overview.byModel || []).map((m: any) => m.tokens)))

onMounted(async () => {
  if (agentId) {
    const agents = await http.get('/agent', { params: { pageSize: 100 } }) as any
    agentName.value = (agents.list || []).find((a: any) => a.id === agentId)?.name || ''
  }
  await loadAll()
})

async function loadAll() {
  Object.assign(overview, await http.get('/observability/overview', { params: { agentId, days: days.value } }) as any)
  const t = await http.get('/observability/traces', { params: { agentId, pageSize: 20 } }) as any
  traces.value = t.list || []
}

async function openTrace(row: any) {
  Object.assign(current, await http.get(`/observability/traces/${row.traceId}`) as any)
  traceVisible.value = true
}

function attrHits(s: any) { return s.inputTokens || 0 }
function pct(v: number) { return Math.max(3, Math.round(v / maxTokens.value * 100)) + '%' }
function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(5, 16) : '-' }
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; flex: 1; }
.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 14px; }
.k { font-size: 13px; color: #909399; }
.v { font-size: 24px; font-weight: 700; margin-top: 6px; }
.mb { margin-bottom: 14px; }
.m-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; font-size: 13px; }
.m-name { width: 150px; flex-shrink: 0; }
.m-track { flex: 1; height: 14px; background: #f5f7fa; border-radius: 3px; overflow: hidden; }
.m-bar { height: 100%; background: #409eff; border-radius: 3px; }
.m-num { width: 200px; text-align: right; color: #909399; flex-shrink: 0; }
.tid { color: #409eff; cursor: pointer; }
.span-row { display: flex; align-items: center; gap: 8px; padding: 10px 8px; border-bottom: 1px dashed #ebeef5; font-size: 13px; margin-left: 8px; }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.dot.agent { background: #3B82F6; }
.dot.chain { background: #10B981; }
.dot.llm { background: #F59E0B; }
.dot.retrieval, .dot.tool { background: #8B5CF6; }
.s-type { width: 76px; color: #909399; }
.s-name { font-weight: 600; min-width: 110px; }
.s-meta { color: #909399; }
.legend { display: flex; gap: 16px; margin-top: 14px; font-size: 12px; color: #909399; }
</style>
