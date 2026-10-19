<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/agents')">
        <el-icon><ArrowLeft /></el-icon> 返回
      </el-button>
      <span class="page-title">可观测 {{ agentName ? '· ' + agentName : '' }}</span>
      <el-button v-if="agentId" size="small" @click="$router.push(`/eval/${agentId}`)">Agent 评测</el-button>
      <el-button size="small" :loading="refreshing" @click="refreshAll">刷新</el-button>
      <el-select v-model="hours" style="width: 130px" @change="refreshAll">
        <el-option :value="24" label="近 24 小时" />
        <el-option :value="168" label="近 7 天" />
        <el-option :value="720" label="近 30 天" />
      </el-select>
    </div>

    <div class="cards">
      <el-tooltip v-for="c in cards" :key="c.key" :content="c.tip" placement="top">
        <el-card shadow="never" class="metric-card" @click="drill(c.key)">
          <div class="k">{{ c.label }}</div>
          <div class="v">{{ c.value }}</div>
        </el-card>
      </el-tooltip>
    </div>

    <div class="charts">
      <el-card shadow="never">
        <template #header>Span 结构图（agent / chain / llm / tool）</template>
        <el-empty v-if="emptySeries" description="暂无 Span 数据" :image-size="50" />
        <EChart v-else :option="spanChartOption" height="240px" />
      </el-card>
      <el-card shadow="never">
        <template #header>Top Tools</template>
        <el-empty v-if="(overview.topTools || []).length === 0" description="暂无工具调用" :image-size="50" />
        <div v-else class="tool-scroll">
          <EChart :option="toolChartOption" :height="toolChartHeight" />
        </div>
      </el-card>
      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <span>模型占比（LLM Span）</span>
            <el-radio-group v-model="modelSort" size="small">
              <el-radio-button value="calls">调用量</el-radio-button>
              <el-radio-button value="tokens">Token</el-radio-button>
            </el-radio-group>
          </div>
        </template>
        <el-empty v-if="(overview.byModel || []).length === 0" description="暂无模型调用" :image-size="50" />
        <EChart v-else :option="modelChartOption" height="240px" />
      </el-card>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="tab">
        <!-- Traces -->
        <el-tab-pane label="Traces" name="traces">
          <div class="filter-bar">
            <el-input v-model="keyword" placeholder="搜索 Trace ID / 输入 / 输出" clearable style="width: 260px"
                      @keyup.enter="loadTraces(true)" @clear="loadTraces(true)" />
            <el-checkbox v-model="onlyError" @change="loadTraces(true)">仅错误</el-checkbox>
            <el-checkbox v-model="hideEval" @change="loadTraces(true)">隐藏评测</el-checkbox>
            <el-popover placement="bottom-start" :width="380" trigger="click">
              <template #reference>
                <el-button size="small">高级筛选</el-button>
              </template>
              <div class="adv-form">
                <div class="row">
                  <span>状态</span>
                  <el-select v-model="adv.status" clearable placeholder="全部" size="small" style="width: 130px">
                    <el-option value="SUCCESS" label="SUCCESS" />
                    <el-option value="ERROR" label="ERROR" />
                    <el-option value="STOPPED" label="STOPPED" />
                  </el-select>
                </div>
                <div class="row">
                  <span>版本</span>
                  <el-input v-model="adv.version" placeholder="如 V11" size="small" style="width: 130px" />
                </div>
                <div class="row">
                  <span>时间范围</span>
                  <el-date-picker v-model="adv.timeRange" type="datetimerange" size="small"
                                  value-format="YYYY-MM-DD HH:mm:ss" start-placeholder="开始" end-placeholder="结束"
                                  style="width: 240px" />
                </div>
                <div class="row">
                  <span>Token 范围</span>
                  <el-input-number v-model="adv.tokenMin" :min="0" size="small" style="width: 110px" placeholder="最小" />
                  <el-input-number v-model="adv.tokenMax" :min="0" size="small" style="width: 110px" placeholder="最大" />
                </div>
                <div class="row">
                  <span>延迟范围</span>
                  <el-input-number v-model="adv.latencyMin" :min="0" size="small" style="width: 110px" placeholder="最小 ms" />
                  <el-input-number v-model="adv.latencyMax" :min="0" size="small" style="width: 110px" placeholder="最大 ms" />
                </div>
                <div class="row">
                  <span>用户 ID</span>
                  <el-input v-model="adv.userIds" placeholder="多个用英文逗号分隔" size="small" style="width: 230px" />
                </div>
                <div class="row">
                  <span>Session ID</span>
                  <el-input v-model="adv.sessionIds" placeholder="多个用英文逗号分隔" size="small" style="width: 230px" />
                </div>
                <div class="row">
                  <span>Tags</span>
                  <el-select v-model="adv.tags" multiple filterable allow-create default-first-option
                             placeholder="多选" size="small" style="width: 230px">
                    <el-option v-for="t in tagOptions" :key="t" :value="t" :label="t" />
                  </el-select>
                </div>
                <div class="adv-actions">
                  <el-button size="small" text @click="resetAdv">重置</el-button>
                  <el-button size="small" type="primary" @click="loadTraces(true)">应用</el-button>
                </div>
              </div>
            </el-popover>
            <el-popover placement="bottom-end" :width="240" trigger="click">
              <template #reference>
                <el-button size="small">Columns</el-button>
              </template>
              <div class="col-config">
                <div v-for="(key, i) in colOrder" :key="key" class="col-item" draggable="true"
                     @dragstart="onDragStart(i)" @dragover.prevent="onDragOver(i)" @drop="onDrop(i)">
                  <span class="drag">⠿</span>
                  <el-checkbox :model-value="colVisible[key]" @change="(v: any) => toggleCol(key, !!v)">
                    {{ colLabel(key) }}
                  </el-checkbox>
                </div>
                <el-button size="small" text style="margin-top: 6px" @click="resetColumns">恢复默认</el-button>
              </div>
            </el-popover>
            <span class="spacer" />
            <span class="hint">共 {{ traceTotal }} 条</span>
          </div>

          <el-table :data="traces" size="small" @row-click="openTrace" :show-overflow-tooltip="false">
            <el-table-column v-for="col in visibleColumns" :key="col.key" :label="col.label"
                             :width="col.width" :min-width="col.minWidth">
              <template #default="{ row }">
                <el-tag v-if="col.key === 'status'" size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
                <el-tag v-else-if="col.key === 'kind'" size="small" :type="kindType(row.kind)">{{ row.kind || '-' }}</el-tag>
                <span v-else-if="col.key === 'name'" class="tid">{{ row.name }}</span>
                <span v-else-if="col.key === 'input'" class="cell-ellipsis">{{ row.input || '-' }}</span>
                <span v-else-if="col.key === 'output'" class="cell-ellipsis">{{ row.output || '-' }}</span>
                <span v-else-if="col.key === 'version'">{{ row.version || '-' }}</span>
                <span v-else-if="col.key === 'startTime'">{{ fmtTime(row.startTime) }}</span>
                <span v-else-if="col.key === 'latencyMs'">{{ row.latencyMs }} ms</span>
                <span v-else-if="col.key === 'totalTokens'">{{ intFmt(row.totalTokens) }}</span>
                <span v-else-if="col.key === 'score'">
                  <el-tag v-if="row.score != null" size="small" :type="row.score >= 0.6 ? 'success' : 'danger'">
                    {{ (row.score * 100).toFixed(0) }}
                  </el-tag>
                  <span v-else>—</span>
                </span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="traces.length === 0" :description="keyword ? '无匹配结果' : '暂无 Trace'" :image-size="60" />
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" background
                         :total="traceTotal" v-model:current-page="tracePage" v-model:page-size="tracePageSize"
                         :page-sizes="[20, 50, 100]" @current-change="loadTraces(false)"
                         @size-change="loadTraces(true)" />
        </el-tab-pane>

        <!-- Sessions -->
        <el-tab-pane label="Sessions" name="sessions">
          <div class="filter-bar">
            <el-input v-model="sessionKw" placeholder="搜索 Session ID" clearable style="width: 240px"
                      @keyup.enter="loadSessions(true)" @clear="loadSessions(true)" />
            <span class="spacer" />
            <span class="hint">共 {{ sessionTotal }} 条</span>
          </div>
          <el-table :data="sessions" size="small">
            <el-table-column prop="sessionId" label="Session ID" min-width="220" show-overflow-tooltip />
            <el-table-column label="Trace 数" width="100">
              <template #default="{ row }">{{ intFmt(row.traceCount) }}</template>
            </el-table-column>
            <el-table-column label="Token" width="120">
              <template #default="{ row }">{{ intFmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column label="成本" width="110">
              <template #default="{ row }">{{ costFmt(row.cost) }}</template>
            </el-table-column>
            <el-table-column label="首次时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.firstTime) }}</template>
            </el-table-column>
            <el-table-column label="最后时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.lastTime) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="sessions.length === 0" description="暂无会话" :image-size="60" />
          <el-pagination class="pager" layout="total, prev, pager, next" background
                         :total="sessionTotal" v-model:current-page="sessionPage" :page-size="20"
                         @current-change="loadSessions(false)" />
        </el-tab-pane>

        <!-- Users -->
        <el-tab-pane label="Users" name="users">
          <div class="filter-bar">
            <el-input v-model="userKw" placeholder="搜索用户名 / 用户 ID" clearable style="width: 240px"
                      @keyup.enter="loadUsers(true)" @clear="loadUsers(true)" />
            <span class="spacer" />
            <span class="hint">共 {{ userTotal }} 条</span>
          </div>
          <el-table :data="users" size="small">
            <el-table-column prop="userId" label="用户 ID" width="120" />
            <el-table-column prop="username" label="用户名" min-width="140">
              <template #default="{ row }">{{ row.username || '—' }}</template>
            </el-table-column>
            <el-table-column label="调用数" width="100">
              <template #default="{ row }">{{ intFmt(row.calls) }}</template>
            </el-table-column>
            <el-table-column label="Token" width="120">
              <template #default="{ row }">{{ intFmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column label="成本" width="110">
              <template #default="{ row }">{{ costFmt(row.cost) }}</template>
            </el-table-column>
            <el-table-column label="首次时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.firstTime) }}</template>
            </el-table-column>
            <el-table-column label="最后时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.lastTime) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="users.length === 0" description="暂无用户调用" :image-size="60" />
          <el-pagination class="pager" layout="total, prev, pager, next" background
                         :total="userTotal" v-model:current-page="userPage" :page-size="20"
                         @current-change="loadUsers(false)" />
        </el-tab-pane>

        <!-- Versions -->
        <el-tab-pane label="Versions" name="versions">
          <div class="filter-bar">
            <el-input v-model="versionKw" placeholder="搜索版本号" clearable style="width: 200px"
                      @keyup.enter="loadVersions(true)" @clear="loadVersions(true)" />
            <span class="spacer" />
            <span class="hint">共 {{ versionTotal }} 条</span>
          </div>
          <el-table :data="versions" size="small">
            <el-table-column prop="version" label="版本" width="110" />
            <el-table-column label="调用数" width="100">
              <template #default="{ row }">{{ intFmt(row.calls) }}</template>
            </el-table-column>
            <el-table-column label="成功率" width="110">
              <template #default="{ row }">{{ pct(row.successRate) }}</template>
            </el-table-column>
            <el-table-column label="平均分" width="100">
              <template #default="{ row }">
                {{ row.avgScore != null ? (Number(row.avgScore) * 100).toFixed(1) : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="Token" width="120">
              <template #default="{ row }">{{ intFmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column label="成本" width="110">
              <template #default="{ row }">{{ costFmt(row.cost) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="versions.length === 0" description="暂无版本数据（仅统计带版本号的 Trace）" :image-size="60" />
          <el-pagination class="pager" layout="total, prev, pager, next" background
                         :total="versionTotal" v-model:current-page="versionPage" :page-size="20"
                         @current-change="loadVersions(false)" />
        </el-tab-pane>

        <!-- Logs -->
        <el-tab-pane label="Logs" name="logs">
          <el-empty description="应用日志经 OpenTelemetry 采集后在此展示（当前环境未接入 OTel，暂无日志）" :image-size="60" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-drawer v-model="traceVisible" :title="'Span 树 · ' + (current.traceId || '')" size="620px">
      <el-descriptions :column="2" border size="small" class="mb">
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="statusType(current.status)">{{ current.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="类型">{{ current.kind || '-' }}</el-descriptions-item>
        <el-descriptions-item label="名称" :span="2">{{ current.name }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ current.version || '-' }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ current.modelName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Token">{{ current.inputTokens }} / {{ current.outputTokens }}（共 {{ intFmt(current.totalTokens) }}）</el-descriptions-item>
        <el-descriptions-item label="成本">{{ costFmt(current.cost) }}</el-descriptions-item>
        <el-descriptions-item label="延迟">{{ current.latencyMs }} ms</el-descriptions-item>
        <el-descriptions-item label="评分">{{ current.score != null ? (current.score * 100).toFixed(0) : '—' }}</el-descriptions-item>
        <el-descriptions-item label="开始时间" :span="2">{{ fmtTime(current.startTime) }}</el-descriptions-item>
        <el-descriptions-item label="Tags" :span="2">
          <template v-if="(current.tags || []).length">
            <el-tag v-for="t in current.tags" :key="t" size="small" class="tag-item">{{ t }}</el-tag>
          </template>
          <span v-else>—</span>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="current.errorMessage" class="err-box">错误信息：{{ current.errorMessage }}</div>

      <div class="io-block">
        <div class="io-title">INPUT</div>
        <pre class="io-text">{{ current.input || '—' }}</pre>
      </div>
      <div class="io-block">
        <div class="io-title">OUTPUT</div>
        <pre class="io-text">{{ current.output || '—' }}</pre>
      </div>

      <div class="io-title" style="margin-top: 14px">SPANS</div>
      <div v-for="(s, i) in spanRows" :key="i" class="span-row" :class="s.spanType"
           :style="{ marginLeft: (8 + s.depth * 22) + 'px' }">
        <span class="dot" :class="s.spanType" />
        <span class="s-type">{{ s.spanType }}</span>
        <span class="s-name">{{ s.name }}</span>
        <span class="s-meta">
          <template v-if="s.modelName">模型 {{ s.modelName }} · </template>
          <template v-if="s.toolName">工具 {{ s.toolName }} · </template>
          <template v-if="s.spanType === 'llm'">{{ s.inputTokens }}/{{ s.outputTokens }} tok · </template>
          <template v-if="s.spanType === 'retrieval'">{{ (s.attributes || {}).hits || 0 }} 命中 · </template>
          {{ s.durationMs }}ms · {{ s.status }}
          <span class="span-time">{{ fmtTime(s.startTime) }}</span>
        </span>
      </div>
      <div class="legend">
        <span><i class="dot agent" /> agent</span>
        <span><i class="dot chain" /> chain</span>
        <span><i class="dot llm" /> llm</span>
        <span><i class="dot tool" /> tool</span>
        <span><i class="dot retrieval" /> retrieval</span>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/http'
import { ArrowLeft } from '@element-plus/icons-vue'
import EChart from '../components/EChart.vue'
import { useUserStore } from '../stores/user'

const route = useRoute()
const userStore = useUserStore()
const agentId = route.params.agentId ? Number(route.params.agentId) : undefined
const agentName = ref('')
const hours = ref(24)
const refreshing = ref(false)
const tab = ref('traces')

const overview = reactive<any>({
  cost: 0, traceCount: 0, totalTokens: 0, spanCount: 0, latencyP50Ms: 0, latencyP99Ms: 0,
  errorCount: 0, byModel: [], topTools: [], spanSeries: []
})
const modelSort = ref<'calls' | 'tokens'>('calls')

// ---- Traces ----
const traces = ref<any[]>([])
const traceTotal = ref(0)
const tracePage = ref(1)
const tracePageSize = ref(20)
const keyword = ref('')
const onlyError = ref(false)
const hideEval = ref(false)
const adv = reactive<any>({
  status: '', version: '', timeRange: null,
  tokenMin: undefined, tokenMax: undefined, latencyMin: undefined, latencyMax: undefined,
  userIds: '', sessionIds: '', tags: [] as string[]
})
const tagOptions = ['once', 'pre_release', 'online', 'feedback']

// ---- Columns 配置（localStorage: columns:{userId}:{agentId}，需求 3.4 / 架构 4.6）----
interface ColMeta { key: string; label: string; width?: number; minWidth?: number }
const ALL_COLUMNS: ColMeta[] = [
  { key: 'status', label: 'STATUS', width: 100 },
  { key: 'kind', label: 'KIND', width: 90 },
  { key: 'name', label: 'NAME', minWidth: 190 },
  { key: 'input', label: 'INPUT', minWidth: 180 },
  { key: 'output', label: 'OUTPUT', minWidth: 180 },
  { key: 'version', label: 'VERSION', width: 90 },
  { key: 'startTime', label: 'START TIME', width: 165 },
  { key: 'latencyMs', label: 'LATENCY', width: 100 },
  { key: 'totalTokens', label: 'TOTAL TOKENS', width: 125 },
  { key: 'score', label: 'SCORE', width: 85 }
]
const colOrder = ref<string[]>(ALL_COLUMNS.map(c => c.key))
const colVisible = reactive<Record<string, boolean>>(
  Object.fromEntries(ALL_COLUMNS.map(c => [c.key, true])))
const columnsKey = computed(() => `columns:${userStore.userInfo?.id || 'anon'}:${agentId ?? 'all'}`)
const visibleColumns = computed(() =>
  colOrder.value.filter(k => colVisible[k]).map(k => ALL_COLUMNS.find(c => c.key === k)!))

// ---- Tabs ----
const sessions = ref<any[]>([]); const sessionTotal = ref(0); const sessionPage = ref(1); const sessionKw = ref('')
const users = ref<any[]>([]); const userTotal = ref(0); const userPage = ref(1); const userKw = ref('')
const versions = ref<any[]>([]); const versionTotal = ref(0); const versionPage = ref(1); const versionKw = ref('')

// ---- Detail ----
const traceVisible = ref(false)
const current = reactive<any>({})

const emptySeries = computed(() =>
  (overview.spanSeries || []).every((b: any) => !b.agent && !b.chain && !b.llm && !b.tool))

const cards = computed(() => [
  { key: 'cost', label: '成本', value: costFmt(overview.cost), tip: 'SUM(cost)：模型单价（元/千 Token）× Token 汇总' },
  { key: 'traceCount', label: '追踪总数', value: intFmt(overview.traceCount), tip: 'COUNT(DISTINCT trace_id)' },
  { key: 'totalTokens', label: '总 Token', value: intFmt(overview.totalTokens), tip: 'SUM(input_tokens + output_tokens)' },
  { key: 'spanCount', label: 'Span 总数', value: intFmt(overview.spanCount), tip: 'COUNT(*) FROM agent_span' },
  { key: 'latencyP50Ms', label: '延迟 P50', value: num(overview.latencyP50Ms) + ' ms', tip: '根 Span 耗时 P50（应用层分位数）' },
  { key: 'latencyP99Ms', label: '延迟 P99', value: num(overview.latencyP99Ms) + ' ms', tip: '根 Span 耗时 P99（应用层分位数）' },
  { key: 'errorCount', label: '错误数', value: intFmt(overview.errorCount), tip: "COUNT WHERE status='ERROR'；点击下钻错误 Trace" }
])

const spanChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['agent', 'chain', 'llm', 'tool'] },
  grid: { left: 40, right: 12, top: 30, bottom: 26 },
  xAxis: { type: 'category', data: (overview.spanSeries || []).map((b: any) => b.bucket) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    { name: 'agent', type: 'bar', stack: 'spans', itemStyle: { color: '#3B82F6' }, data: (overview.spanSeries || []).map((b: any) => b.agent) },
    { name: 'chain', type: 'bar', stack: 'spans', itemStyle: { color: '#10B981' }, data: (overview.spanSeries || []).map((b: any) => b.chain) },
    { name: 'llm', type: 'bar', stack: 'spans', itemStyle: { color: '#F59E0B' }, data: (overview.spanSeries || []).map((b: any) => b.llm) },
    { name: 'tool', type: 'bar', stack: 'spans', itemStyle: { color: '#6B7280' }, data: (overview.spanSeries || []).map((b: any) => b.tool) }
  ]
}))

const toolChartHeight = computed(() =>
  Math.max(120, (overview.topTools || []).length * 30 + 50) + 'px')

const toolChartOption = computed(() => {
  const list = overview.topTools || []
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        const idx = params[0]?.dataIndex ?? 0
        const t = list[idx] || {}
        return `${t.toolName}<br/>调用 ${t.calls} 次 · P95 延迟 ${t.p95LatencyMs}ms`
      }
    },
    grid: { left: 110, right: 40, top: 10, bottom: 26 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', inverse: true, data: list.map((t: any) => t.toolName) },
    series: [{
      type: 'bar', data: list.map((t: any) => t.calls), itemStyle: { color: '#6B7280' },
      label: { show: true, position: 'right', formatter: (p: any) => `${p.value} 次 / P95 ${list[p.dataIndex]?.p95LatencyMs}ms` }
    }]
  }
})

const modelChartOption = computed(() => ({
  tooltip: {
    trigger: 'item',
    formatter: (p: any) => {
      const m = (overview.byModel || [])[p.dataIndex] || {}
      return `${p.name}<br/>调用 ${m.calls ?? 0} 次 · Token ${intFmt(m.tokens)}`
    }
  },
  legend: { bottom: 0, type: 'scroll' },
  series: [{
    type: 'pie', radius: ['42%', '68%'],
    data: (overview.byModel || []).map((m: any) => ({
      name: m.modelName, value: modelSort.value === 'calls' ? m.calls : num(m.tokens)
    }))
  }]
}))

onMounted(async () => {
  loadColumns()
  if (agentId) {
    const agents = await http.get('/agent', { params: { pageSize: 100 } }) as any
    agentName.value = (agents.list || []).find((a: any) => a.id === agentId)?.name || ''
  }
  await refreshAll()
})

watch(tab, (t) => {
  if (t === 'sessions' && sessions.value.length === 0) loadSessions(true)
  if (t === 'users' && users.value.length === 0) loadUsers(true)
  if (t === 'versions' && versions.value.length === 0) loadVersions(true)
})

async function refreshAll() {
  refreshing.value = true
  try {
    await Promise.all([
      http.get('/observability/overview', { params: { agentId, days: hours.value / 24 } })
        .then((d: any) => Object.assign(overview, d)),
      loadTraces(true),
      loadSessions(true),
      loadUsers(true),
      loadVersions(true)
    ])
  } finally {
    refreshing.value = false
  }
}

function rangeParams() {
  const end = new Date()
  const start = new Date(end.getTime() - hours.value * 3600_000)
  return { startTime: fmtFull(start), endTime: fmtFull(end) }
}

async function loadTraces(reset: boolean) {
  if (reset) tracePage.value = 1
  const params: any = {
    agentId, page: tracePage.value, pageSize: tracePageSize.value,
    hideEval: hideEval.value || undefined,
    keyword: keyword.value || undefined
  }
  if (onlyError.value) params.status = 'ERROR'
  if (adv.status) params.status = adv.status
  if (adv.version) params.version = adv.version
  if (adv.userIds) params.userIds = adv.userIds
  if (adv.sessionIds) params.sessionIds = adv.sessionIds
  if (adv.tokenMin !== undefined && adv.tokenMin !== null) params.tokenMin = adv.tokenMin
  if (adv.tokenMax !== undefined && adv.tokenMax !== null) params.tokenMax = adv.tokenMax
  if (adv.latencyMin !== undefined && adv.latencyMin !== null) params.latencyMin = adv.latencyMin
  if (adv.latencyMax !== undefined && adv.latencyMax !== null) params.latencyMax = adv.latencyMax
  if (adv.tags?.length) params.tags = adv.tags.join(',')
  if (adv.timeRange?.length === 2) {
    params.startTime = adv.timeRange[0]
    params.endTime = adv.timeRange[1]
  }
  const data = await http.get('/observability/traces', { params }) as any
  traces.value = data.list || []
  traceTotal.value = data.total || 0
}

async function loadSessions(reset: boolean) {
  if (reset) sessionPage.value = 1
  const data = await http.get('/observability/sessions', {
    params: { agentId, ...rangeParams(), keyword: sessionKw.value || undefined, page: sessionPage.value, pageSize: 20 }
  }) as any
  sessions.value = data.list || []
  sessionTotal.value = data.total || 0
}

async function loadUsers(reset: boolean) {
  if (reset) userPage.value = 1
  const data = await http.get('/observability/users', {
    params: { agentId, ...rangeParams(), keyword: userKw.value || undefined, page: userPage.value, pageSize: 20 }
  }) as any
  users.value = data.list || []
  userTotal.value = data.total || 0
}

async function loadVersions(reset: boolean) {
  if (reset) versionPage.value = 1
  const data = await http.get('/observability/versions', {
    params: { agentId, ...rangeParams(), keyword: versionKw.value || undefined, page: versionPage.value, pageSize: 20 }
  }) as any
  versions.value = data.list || []
  versionTotal.value = data.total || 0
}

async function openTrace(row: any) {
  Object.assign(current, await http.get(`/observability/traces/${row.traceId}`) as any)
  traceVisible.value = true
}

function drill(key: string) {
  if (key === 'errorCount') {
    onlyError.value = true
  }
  tab.value = 'traces'
  loadTraces(true)
}

function resetAdv() {
  Object.assign(adv, {
    status: '', version: '', timeRange: null, tokenMin: undefined, tokenMax: undefined,
    latencyMin: undefined, latencyMax: undefined, userIds: '', sessionIds: '', tags: []
  })
  loadTraces(true)
}

// ---- Columns 配置 ----
function loadColumns() {
  try {
    const raw = localStorage.getItem(columnsKey.value)
    if (!raw) return
    const saved = JSON.parse(raw)
    if (Array.isArray(saved.order)) {
      const known = saved.order.filter((k: string) => ALL_COLUMNS.some(c => c.key === k))
      const missing = ALL_COLUMNS.map(c => c.key).filter(k => !known.includes(k))
      colOrder.value = [...known, ...missing]
    }
    if (saved.visible) {
      for (const c of ALL_COLUMNS) {
        if (typeof saved.visible[c.key] === 'boolean') colVisible[c.key] = saved.visible[c.key]
      }
    }
  } catch { /* 忽略坏配置 */ }
}

function saveColumns() {
  localStorage.setItem(columnsKey.value, JSON.stringify({ order: colOrder.value, visible: { ...colVisible } }))
}

function toggleCol(key: string, value: boolean) {
  colVisible[key] = value
  saveColumns()
}

function resetColumns() {
  colOrder.value = ALL_COLUMNS.map(c => c.key)
  ALL_COLUMNS.forEach(c => { colVisible[c.key] = true })
  saveColumns()
}

const dragIndex = ref<number | null>(null)
function onDragStart(i: number) { dragIndex.value = i }
function onDragOver(_i: number) { /* 仅阻止默认以允许 drop */ }
function onDrop(i: number) {
  if (dragIndex.value === null || dragIndex.value === i) return
  const arr = [...colOrder.value]
  const [moved] = arr.splice(dragIndex.value, 1)
  arr.splice(i, 0, moved)
  colOrder.value = arr
  dragIndex.value = null
  saveColumns()
}

function colLabel(key: string) { return ALL_COLUMNS.find(c => c.key === key)?.label || key }

// ---- Span 树缩进 ----
const spanRows = computed(() => {
  const spans = current.spans || []
  const byId = new Map<string, any>()
  spans.forEach((s: any) => byId.set(s.spanId, s))
  function depth(s: any): number {
    let d = 0
    let p = s.parentSpanId
    while (p && byId.has(p) && d < 10) {
      d++
      p = byId.get(p).parentSpanId
    }
    return d
  }
  return spans.map((s: any) => ({ ...s, depth: depth(s) }))
})

// ---- 格式化 ----
function num(v: any) { return Number(v || 0) }
function intFmt(v: any) { return num(v).toLocaleString() }
function costFmt(v: any) {
  const n = num(v)
  if (n === 0) return '¥0'
  return '¥' + (n >= 0.01 ? n.toFixed(4) : n.toFixed(6))
}
function pct(v: any, digits = 1) { return num(v).toFixed(digits) + '%' }
function fmtTime(t?: string) { return t ? String(t).replace('T', ' ').slice(0, 19) : '—' }
function fmtFull(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
function statusType(s: string) {
  return s === 'SUCCESS' ? 'success' : s === 'STOPPED' ? 'warning' : 'danger'
}
function kindType(k: string) {
  return k === 'internal' ? 'primary' : k === 'external' ? 'warning' : k === 'eval' ? 'info' : 'info'
}
</script>

<style scoped>
.page { max-width: 1240px; margin: 0 auto; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; flex: 1; }
.cards { display: grid; grid-template-columns: repeat(7, 1fr); gap: 10px; margin-bottom: 14px; }
.metric-card { cursor: pointer; }
.metric-card :deep(.el-card__body) { padding: 12px; }
.k { font-size: 12px; color: #909399; white-space: nowrap; }
.v { font-size: 18px; font-weight: 700; margin-top: 6px; white-space: nowrap; }
.charts { display: grid; grid-template-columns: 1.2fr 1fr 1fr; gap: 12px; margin-bottom: 14px; }
.charts :deep(.el-card__body) { padding: 8px 12px 4px; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.tool-scroll { max-height: 300px; overflow-y: auto; }
.filter-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.spacer { flex: 1; }
.hint { color: #909399; font-size: 13px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.tid { color: #409eff; cursor: pointer; }
.cell-ellipsis { display: inline-block; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: bottom; }
.adv-form .row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.adv-form .row > span:first-child { width: 74px; color: #606266; font-size: 13px; flex-shrink: 0; }
.adv-actions { display: flex; justify-content: flex-end; gap: 6px; margin-top: 4px; }
.col-config .col-item { display: flex; align-items: center; gap: 6px; padding: 3px 0; cursor: grab; }
.col-config .drag { color: #c0c4cc; cursor: grab; }
.span-row { display: flex; align-items: center; gap: 8px; padding: 8px; border-bottom: 1px dashed #ebeef5; font-size: 13px; }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; flex-shrink: 0; }
.span-time { color: #c0c4cc; font-size: 11px; margin-left: 8px; }
.dot.agent { background: #3B82F6; }
.dot.chain { background: #10B981; }
.dot.llm { background: #F59E0B; }
.dot.tool { background: #6B7280; }
.dot.retrieval { background: #8B5CF6; }
.s-type { width: 66px; color: #909399; flex-shrink: 0; }
.s-name { font-weight: 600; min-width: 100px; }
.s-meta { color: #909399; }
.legend { display: flex; gap: 16px; margin-top: 14px; font-size: 12px; color: #909399; }
.io-block { margin-top: 10px; }
.io-title { font-size: 12px; color: #909399; margin-bottom: 4px; font-weight: 600; }
.io-text { background: #f5f7fa; border-radius: 4px; padding: 8px 10px; font-size: 13px; white-space: pre-wrap; word-break: break-word; margin: 0; max-height: 220px; overflow-y: auto; font-family: inherit; }
.err-box { background: #fef0f0; color: #f56c6c; border-radius: 4px; padding: 8px 10px; font-size: 13px; margin-top: 8px; word-break: break-word; }
.tag-item { margin-right: 4px; }
.mb { margin-bottom: 12px; }
</style>
