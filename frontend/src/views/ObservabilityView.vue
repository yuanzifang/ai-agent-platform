<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/agents')">
        <el-icon><ArrowLeft /></el-icon> {{ t('obsPage.back') }}
      </el-button>
      <span class="page-title">{{ t('page.observability') }} {{ agentName ? '· ' + agentName : '' }}</span>
      <el-button v-if="agentId" size="small" @click="$router.push(`/eval/${agentId}`)">{{ t('obsPage.eval') }}</el-button>
      <el-button size="small" :loading="refreshing" @click="refreshAll">{{ t('obsPage.refresh') }}</el-button>
      <el-select v-model="hours" style="width: 130px" @change="refreshAll">
        <el-option :value="24" :label="t('obsPage.last24')" />
        <el-option :value="168" :label="t('obsPage.last7')" />
        <el-option :value="720" :label="t('obsPage.last30')" />
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
        <template #header>{{ t('obsPage.spanTree') }}</template>
        <el-empty v-if="emptySeries" :description="t('obsPage.noSpans')" :image-size="50" />
        <EChart v-else :option="spanChartOption" height="240px" />
      </el-card>
      <el-card shadow="never">
        <template #header>Top Tools</template>
        <el-empty v-if="(overview.topTools || []).length === 0" :description="t('obsPage.noTools')" :image-size="50" />
        <div v-else class="tool-scroll">
          <EChart :option="toolChartOption" :height="toolChartHeight" />
        </div>
      </el-card>
      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <span>{{ t('obsPage.modelShare') }}</span>
            <el-radio-group v-model="modelSort" size="small">
              <el-radio-button value="calls">{{ t('obsPage.calls') }}</el-radio-button>
              <el-radio-button value="tokens">Token</el-radio-button>
            </el-radio-group>
          </div>
        </template>
        <el-empty v-if="(overview.byModel || []).length === 0" :description="t('obsPage.noModelCalls')" :image-size="50" />
        <EChart v-else :option="modelChartOption" height="240px" />
      </el-card>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="tab">
        <!-- Traces -->
        <el-tab-pane label="Traces" name="traces">
          <div class="filter-bar">
            <el-input v-model="keyword" :placeholder="t('obsPage.searchPh')" clearable style="width: 260px"
                      @keyup.enter="loadTraces(true)" @clear="loadTraces(true)" />
            <el-checkbox v-model="onlyError" @change="loadTraces(true)">{{ t('obsPage.onlyError') }}</el-checkbox>
            <el-checkbox v-model="hideEval" @change="loadTraces(true)">{{ t('obsPage.hideEval') }}</el-checkbox>
            <el-popover placement="bottom-start" :width="380" trigger="click">
              <template #reference>
                <el-button size="small">{{ t('obsPage.advanced') }}</el-button>
              </template>
              <div class="adv-form">
                <div class="row">
                  <span>{{ t('obsPage.status') }}</span>
                  <el-select v-model="adv.status" clearable :placeholder="t('obsPage.all')" size="small" style="width: 130px">
                    <el-option value="SUCCESS" label="SUCCESS" />
                    <el-option value="ERROR" label="ERROR" />
                    <el-option value="STOPPED" label="STOPPED" />
                  </el-select>
                </div>
                <div class="row">
                  <span>{{ t('obsPage.version') }}</span>
                  <el-input v-model="adv.version" :placeholder="t('obsPage.versionPh')" size="small" style="width: 130px" />
                </div>
                <div class="row">
                  <span>{{ t('obsPage.timeRange') }}</span>
                  <el-date-picker v-model="adv.timeRange" type="datetimerange" size="small"
                                  value-format="YYYY-MM-DD HH:mm:ss" :start-placeholder="t('obsPage.start')" :end-placeholder="t('obsPage.end')"
                                  style="width: 240px" />
                </div>
                <div class="row">
                  <span>{{ t('obsPage.tokenRange') }}</span>
                  <el-input-number v-model="adv.tokenMin" :min="0" size="small" style="width: 110px" :placeholder="t('obsPage.min')" />
                  <el-input-number v-model="adv.tokenMax" :min="0" size="small" style="width: 110px" :placeholder="t('obsPage.max')" />
                </div>
                <div class="row">
                  <span>{{ t('obsPage.latencyRange') }}</span>
                  <el-input-number v-model="adv.latencyMin" :min="0" size="small" style="width: 110px" :placeholder="t('obsPage.minMs')" />
                  <el-input-number v-model="adv.latencyMax" :min="0" size="small" style="width: 110px" :placeholder="t('obsPage.maxMs')" />
                </div>
                <div class="row">
                  <span>{{ t('obsPage.userId') }}</span>
                  <el-input v-model="adv.userIds" :placeholder="t('obsPage.userIdsPh')" size="small" style="width: 230px" />
                </div>
                <div class="row">
                  <span>Session ID</span>
                  <el-input v-model="adv.sessionIds" :placeholder="t('obsPage.userIdsPh')" size="small" style="width: 230px" />
                </div>
                <div class="row">
                  <span>Tags</span>
                  <el-select v-model="adv.tags" multiple filterable allow-create default-first-option
                             :placeholder="t('obsPage.multiSelect')" size="small" style="width: 230px">
                    <el-option v-for="t in tagOptions" :key="t" :value="t" :label="t" />
                  </el-select>
                </div>
                <div class="adv-actions">
                  <el-button size="small" text @click="resetAdv">{{ t('obsPage.reset') }}</el-button>
                  <el-button size="small" type="primary" @click="loadTraces(true)">{{ t('obsPage.apply') }}</el-button>
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
                <el-button size="small" text style="margin-top: 6px" @click="resetColumns">{{ t('obsPage.restoreDefaults') }}</el-button>
              </div>
            </el-popover>
            <span class="spacer" />
            <span class="hint">{{ t('obsPage.total') }}{{ traceTotal }}{{ t('obsPage.tracesSuffix') }}</span>
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
          <el-empty v-if="traces.length === 0" :description="keyword ? t('obsPage.noMatch') : t('obsPage.noTraces')" :image-size="60" />
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" background
                         :total="traceTotal" v-model:current-page="tracePage" v-model:page-size="tracePageSize"
                         :page-sizes="[20, 50, 100]" @current-change="loadTraces(false)"
                         @size-change="loadTraces(true)" />
        </el-tab-pane>

        <!-- Sessions -->
        <el-tab-pane label="Sessions" name="sessions">
          <div class="filter-bar">
            <el-input v-model="sessionKw" :placeholder="t('obsPage.sessionSearch')" clearable style="width: 240px"
                      @keyup.enter="loadSessions(true)" @clear="loadSessions(true)" />
            <span class="spacer" />
            <span class="hint">{{ t('obsPage.total') }}{{ sessionTotal }}{{ t('obsPage.tracesSuffix') }}</span>
          </div>
          <el-table :data="sessions" size="small">
            <el-table-column prop="sessionId" label="Session ID" min-width="220" show-overflow-tooltip />
            <el-table-column :label="t('obsPage.traceCount')" width="100">
              <template #default="{ row }">{{ intFmt(row.traceCount) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.token')" width="120">
              <template #default="{ row }">{{ intFmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.cost')" width="110">
              <template #default="{ row }">{{ costFmt(row.cost) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.firstTime')" width="160">
              <template #default="{ row }">{{ fmtTime(row.firstTime) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.lastTime')" width="160">
              <template #default="{ row }">{{ fmtTime(row.lastTime) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="sessions.length === 0" :description="t('obsPage.noSessions')" :image-size="60" />
          <el-pagination class="pager" layout="total, prev, pager, next" background
                         :total="sessionTotal" v-model:current-page="sessionPage" :page-size="20"
                         @current-change="loadSessions(false)" />
        </el-tab-pane>

        <!-- Users -->
        <el-tab-pane label="Users" name="users">
          <div class="filter-bar">
            <el-input v-model="userKw" :placeholder="t('obsPage.userSearch')" clearable style="width: 240px"
                      @keyup.enter="loadUsers(true)" @clear="loadUsers(true)" />
            <span class="spacer" />
            <span class="hint">{{ t('obsPage.total') }}{{ userTotal }}{{ t('obsPage.rowsSuffix') }}</span>
          </div>
          <el-table :data="users" size="small">
            <el-table-column prop="userId" :label="t('obsPage.userId')" width="120" />
            <el-table-column prop="username" :label="t('obsPage.username')" min-width="140">
              <template #default="{ row }">{{ row.username || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.callCount')" width="100">
              <template #default="{ row }">{{ intFmt(row.calls) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.token')" width="120">
              <template #default="{ row }">{{ intFmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.cost')" width="110">
              <template #default="{ row }">{{ costFmt(row.cost) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.firstTime')" width="160">
              <template #default="{ row }">{{ fmtTime(row.firstTime) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.lastTime')" width="160">
              <template #default="{ row }">{{ fmtTime(row.lastTime) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="users.length === 0" :description="t('obsPage.noUsers')" :image-size="60" />
          <el-pagination class="pager" layout="total, prev, pager, next" background
                         :total="userTotal" v-model:current-page="userPage" :page-size="20"
                         @current-change="loadUsers(false)" />
        </el-tab-pane>

        <!-- Versions -->
        <el-tab-pane label="Versions" name="versions">
          <div class="filter-bar">
            <el-input v-model="versionKw" :placeholder="t('obsPage.versionSearch')" clearable style="width: 200px"
                      @keyup.enter="loadVersions(true)" @clear="loadVersions(true)" />
            <span class="spacer" />
            <span class="hint">{{ t('obsPage.total') }}{{ versionTotal }}{{ t('obsPage.rowsSuffix') }}</span>
          </div>
          <el-table :data="versions" size="small">
            <el-table-column prop="version" :label="t('obsPage.versionCol')" width="110" />
            <el-table-column :label="t('obsPage.callCount')" width="100">
              <template #default="{ row }">{{ intFmt(row.calls) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.avgScore')" width="110">
              <template #default="{ row }">{{ pct(row.successRate) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.avgScore')" width="100">
              <template #default="{ row }">
                {{ row.avgScore != null ? (Number(row.avgScore) * 100).toFixed(1) : '—' }}
              </template>
            </el-table-column>
            <el-table-column :label="t('obsPage.token')" width="120">
              <template #default="{ row }">{{ intFmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.cost')" width="110">
              <template #default="{ row }">{{ costFmt(row.cost) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="versions.length === 0" :description="t('obsPage.noVersions')" :image-size="60" />
          <el-pagination class="pager" layout="total, prev, pager, next" background
                         :total="versionTotal" v-model:current-page="versionPage" :page-size="20"
                         @current-change="loadVersions(false)" />
        </el-tab-pane>

        <!-- Gray A/B -->
        <el-tab-pane :label="t('obsPage.grayTab')" name="gray">
          <div class="filter-bar">
            <el-input v-model="grayTag" :placeholder="t('obsPage.grayTagPh')" clearable style="width: 220px"
                      @keyup.enter="loadGray" />
            <span class="hint">{{ t('obsPage.grayDays') }}</span>
            <el-input-number v-model="grayDays" :min="1" :max="90" size="small" />
            <el-button size="small" :loading="grayLoading" @click="loadGray">{{ t('obsPage.loadGray') }}</el-button>
            <span class="hint">{{ t('obsPage.grayHint') }}</span>
          </div>
          <el-empty v-if="!grayData" :description="t('obsPage.grayNoData')" :image-size="60" />
          <template v-else>
            <el-descriptions :column="4" border size="small" class="mb">
              <el-descriptions-item :label="t('obsPage.grayRequests')">{{ intFmt(grayData.baseline?.requests) }} / {{ intFmt(grayData.gray?.requests) }}</el-descriptions-item>
              <el-descriptions-item :label="t('obsPage.grayErrorRate')">{{ pct((grayData.baseline?.errorRate || 0) * 100) }} / {{ pct((grayData.gray?.errorRate || 0) * 100) }}</el-descriptions-item>
              <el-descriptions-item :label="t('obsPage.grayAvgLatency')">{{ num(grayData.baseline?.avgLatencyMs) }} / {{ num(grayData.gray?.avgLatencyMs) }} ms</el-descriptions-item>
              <el-descriptions-item :label="t('obsPage.grayP95')">{{ num(grayData.baseline?.p95LatencyMs) }} / {{ num(grayData.gray?.p95LatencyMs) }} ms</el-descriptions-item>
              <el-descriptions-item :label="t('obsPage.grayTokens')">{{ intFmt(grayData.baseline?.avgTotalTokens) }} / {{ intFmt(grayData.gray?.avgTotalTokens) }}</el-descriptions-item>
              <el-descriptions-item :label="t('obsPage.grayCost')">{{ costFmt(grayData.baseline?.totalCost) }} / {{ costFmt(grayData.gray?.totalCost) }}</el-descriptions-item>
              <el-descriptions-item :label="t('obsPage.grayToolCalls')">{{ num(grayData.baseline?.avgToolCallsPerTrace) }} / {{ num(grayData.gray?.avgToolCallsPerTrace) }}</el-descriptions-item>
              <el-descriptions-item :label="t('obsPage.grayDelta')">{{ t('obsPage.grayBaseline') }} → {{ t('obsPage.grayGroup') }}</el-descriptions-item>
            </el-descriptions>
            <div class="gray-grid">
              <el-card shadow="never">
                <template #header>{{ t('obsPage.grayByModel') }} · {{ t('obsPage.grayBaseline') }}</template>
                <el-table :data="grayData.baseline?.byModel || []" size="small">
                  <el-table-column prop="model_name" label="Model" />
                  <el-table-column prop="requests" :label="t('obsPage.grayRequests')" width="80" />
                  <el-table-column prop="avg_latency" :label="t('obsPage.grayAvgLatency')" width="110" />
                </el-table>
              </el-card>
              <el-card shadow="never">
                <template #header>{{ t('obsPage.grayByModel') }} · {{ t('obsPage.grayGroup') }}</template>
                <el-table :data="grayData.gray?.byModel || []" size="small">
                  <el-table-column prop="model_name" label="Model" />
                  <el-table-column prop="requests" :label="t('obsPage.grayRequests')" width="80" />
                  <el-table-column prop="avg_latency" :label="t('obsPage.grayAvgLatency')" width="110" />
                </el-table>
              </el-card>
            </div>
            <el-descriptions :column="3" border size="small" class="mt8">
              <el-descriptions-item :label="t('obsPage.grayEvalBaseline')">
                {{ grayData.eval?.baseline?.evals || 0 }} {{ t('obsPage.grayEvalCount') }} · {{ pct((grayData.eval?.baseline?.passRate || 0) * 100) }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('obsPage.grayEvalGray')">
                {{ grayData.eval?.gray?.evals || 0 }} {{ t('obsPage.grayEvalCount') }} · {{ pct((grayData.eval?.gray?.passRate || 0) * 100) }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('obsPage.grayEvalScore')">
                {{ num(grayData.eval?.baseline?.avgScore) }} / {{ num(grayData.eval?.gray?.avgScore) }}
              </el-descriptions-item>
            </el-descriptions>
          </template>
        </el-tab-pane>

        <!-- Logs -->
        <el-tab-pane label="Logs" name="logs">
          <div class="filter-bar">
            <el-select v-model="logLevel" clearable :placeholder="t('obsPage.level')" style="width: 120px"
                       @change="loadLogs(true)">
              <el-option label="INFO" value="INFO" />
              <el-option label="WARN" value="WARN" />
              <el-option label="ERROR" value="ERROR" />
            </el-select>
            <el-input v-model="logKeyword" :placeholder="t('obsPage.logSearch')" clearable style="width: 260px"
                      @keyup.enter="loadLogs(true)" @clear="loadLogs(true)" />
            <el-button @click="loadLogs(true)">{{ t('obsPage.search') }}</el-button>
            <span class="hint">{{ t('obsPage.logHint') }}</span>
          </div>
          <el-table :data="logs" size="small" v-loading="logLoading" :empty-text="t('obsPage.noLogs')">
            <el-table-column type="expand">
              <template #default="{ row }">
                <pre v-if="row.exception" class="log-exception">{{ row.exception }}</pre>
                <span v-else class="hint">{{ t('obsPage.noStack') }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('obsPage.time')" width="170">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column :label="t('obsPage.levelCol')" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="levelType(row.level)">{{ row.level }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="logger" label="Logger" min-width="200" show-overflow-tooltip />
            <el-table-column :label="t('obsPage.message')" min-width="320" show-overflow-tooltip />
            <el-table-column prop="traceId" label="Trace ID" width="190" show-overflow-tooltip />
            <el-table-column :label="t('obsPage.thread')" width="140" show-overflow-tooltip />
          </el-table>
          <el-pagination class="pager" layout="total, prev, pager, next" background
                         :total="logTotal" v-model:current-page="logPage" :page-size="50"
                         @current-change="loadLogs(false)" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-drawer v-model="traceVisible" :title="t('obsPage.spanTreeTitle') + (current.traceId || '')" size="620px">
      <el-descriptions :column="2" border size="small" class="mb">
        <el-descriptions-item :label="t('obsPage.spanStatus')">
          <el-tag size="small" :type="statusType(current.status)">{{ current.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('obsPage.kind')">{{ current.kind || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('obsPage.name')" :span="2">{{ current.name }}</el-descriptions-item>
        <el-descriptions-item :label="t('obsPage.versionCol')">{{ current.version || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('obsPage.model')">{{ current.modelName || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('obsPage.token')">{{ current.inputTokens }} / {{ current.outputTokens }}{{ t('obsPage.totalTokens') }}{{ intFmt(current.totalTokens) }}）</el-descriptions-item>
        <el-descriptions-item :label="t('obsPage.cost')">{{ costFmt(current.cost) }}</el-descriptions-item>
        <el-descriptions-item :label="t('obsPage.latency')">{{ current.latencyMs }} ms</el-descriptions-item>
        <el-descriptions-item :label="t('obsPage.score')">{{ current.score != null ? (current.score * 100).toFixed(0) : '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('obsPage.startTime')" :span="2">{{ fmtTime(current.startTime) }}</el-descriptions-item>
        <el-descriptions-item label="Tags" :span="2">
          <template v-if="(current.tags || []).length">
            <el-tag v-for="t in current.tags" :key="t" size="small" class="tag-item">{{ t }}</el-tag>
          </template>
          <span v-else>—</span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="io-title" style="margin-top: 14px">{{ t('obsPage.modelTrace') }}</div>
      <div v-if="modelTraceRows.length" class="model-trace">
        <div v-for="(s, i) in modelTraceRows" :key="i" class="model-trace-row">
          <el-tag size="small" :type="s.status === 'SUCCESS' ? 'success' : 'warning'">{{ s.status }}</el-tag>
          <b>{{ s.name }}</b>
          <span>{{ s.modelName || current.modelName || '—' }}</span>
          <span v-if="s.attributes?.from || s.attributes?.to">{{ s.attributes?.from }} → {{ s.attributes?.to }}</span>
          <span class="hint">{{ s.durationMs }}ms · {{ fmtTime(s.startTime) }}</span>
        </div>
      </div>
      <div v-else class="hint">{{ t('obsPage.modelTraceEmpty') }}</div>

      <div v-if="current.errorMessage" class="err-box">{{ t('obsPage.errorInfo') }}{{ current.errorMessage }}</div>

      <div class="io-block">
        <div class="io-title">INPUT</div>
        <pre class="io-text">{{ current.input || '—' }}</pre>
      </div>
      <div class="io-block">
        <div class="io-title">OUTPUT</div>
        <pre class="io-text">{{ current.output || '—' }}</pre>
      </div>

      <div class="span-head">
        <div class="io-title">{{ t('obsPage.spans') }}</div>
        <el-radio-group v-model="traceView" size="small">
          <el-radio-button value="waterfall">{{ t('obsPage.waterfall') }}</el-radio-button>
          <el-radio-button value="list">{{ t('obsPage.list') }}</el-radio-button>
        </el-radio-group>
        <span class="hint">{{ t('obsPage.totalDuration') }} {{ current.latencyMs }}ms</span>
      </div>
      <div v-if="traceView === 'waterfall'" class="waterfall">
        <div class="waterfall-axis">
          <span>0%</span><span>25%</span><span>50%</span><span>75%</span><span>100%</span>
        </div>
        <div v-for="(s, i) in spanRows" :key="i" class="waterfall-row">
          <div class="waterfall-label" :style="{ paddingLeft: (8 + s.depth * 14) + 'px' }">
            <span class="dot" :class="s.spanType" />
            <span class="s-type">{{ s.spanType }}</span>
            <span class="s-name">{{ s.name }}</span>
          </div>
          <div class="waterfall-track">
            <div class="waterfall-bar" :class="s.spanType"
                 :style="{ left: s.leftPct + '%', width: s.widthPct + '%' }"
                 :title="`${s.durationMs}ms · ${s.status}`">
              <span v-if="s.widthPct >= 12">{{ s.durationMs }}ms</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else>
        <div v-for="(s, i) in spanRows" :key="i" class="span-row" :class="s.spanType"
             :style="{ marginLeft: (8 + s.depth * 22) + 'px' }">
          <span class="dot" :class="s.spanType" />
          <span class="s-type">{{ s.spanType }}</span>
          <span class="s-name">{{ s.name }}</span>
          <span class="s-meta">
            <template v-if="s.modelName">{{ t('obsPage.modelPrefix') }}{{ s.modelName }} · </template>
            <template v-if="s.toolName">{{ t('obsPage.toolPrefix') }}{{ s.toolName }} · </template>
            <template v-if="s.spanType === 'llm'">{{ s.inputTokens }}/{{ s.outputTokens }} tok · </template>
            <template v-if="s.spanType === 'retrieval'">{{ (s.attributes || {}).hits || 0 }}{{ t('obsPage.hits') }} · </template>
            {{ s.durationMs }}ms · {{ s.status }}
            <span class="span-time">{{ fmtTime(s.startTime) }}</span>
          </span>
        </div>
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
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/http'
import { ArrowLeft } from '@element-plus/icons-vue'
import EChart from '../components/EChart.vue'
import { useUserStore } from '../stores/user'

const { t } = useI18n()

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
const logs = ref<any[]>([]); const logTotal = ref(0); const logPage = ref(1)
const logLevel = ref(''); const logKeyword = ref(''); const logLoading = ref(false)

// ---- Detail ----
const traceVisible = ref(false)
const current = reactive<any>({})
const traceView = ref<'waterfall' | 'list'>('waterfall')
const grayTag = ref('')
const grayDays = ref(7)
const grayLoading = ref(false)
const grayData = ref<any>(null)

const emptySeries = computed(() =>
  (overview.spanSeries || []).every((b: any) => !b.agent && !b.chain && !b.llm && !b.tool))

const cards = computed(() => [
  { key: 'cost', label: t('obsPage.costCard'), value: costFmt(overview.cost), tip: t('obsPage.costTip') },
  { key: 'traceCount', label: t('obsPage.traceCountCard'), value: intFmt(overview.traceCount), tip: t('obsPage.traceTip') },
  { key: 'totalTokens', label: t('obsPage.totalTokenCard'), value: intFmt(overview.totalTokens), tip: t('obsPage.tokenTip') },
  { key: 'spanCount', label: t('obsPage.spanCount'), value: intFmt(overview.spanCount), tip: t('obsPage.spanTip') },
  { key: 'latencyP50Ms', label: t('obsPage.latencyP50'), value: num(overview.latencyP50Ms) + ' ms', tip: t('obsPage.p50Tip') },
  { key: 'latencyP99Ms', label: t('obsPage.latencyP99'), value: num(overview.latencyP99Ms) + ' ms', tip: t('obsPage.p99Tip') },
  { key: 'errorCount', label: t('obsPage.errorCount'), value: intFmt(overview.errorCount), tip: t('obsPage.errorTip') }
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
        return `${t.toolName}<br/>${t('obsPage.calls')} ${t.calls}${t('obsPage.callsUnit')}${t('obsPage.p95')}${t.p95LatencyMs}ms`
      }
    },
    grid: { left: 110, right: 40, top: 10, bottom: 26 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', inverse: true, data: list.map((t: any) => t.toolName) },
    series: [{
      type: 'bar', data: list.map((t: any) => t.calls), itemStyle: { color: '#6B7280' },
      label: { show: true, position: 'right', formatter: (p: any) => `${p.value}${t('obsPage.callsUnit')} / P95 ${list[p.dataIndex]?.p95LatencyMs}ms` }
    }]
  }
})

const modelChartOption = computed(() => ({
  tooltip: {
    trigger: 'item',
    formatter: (p: any) => {
      const m = (overview.byModel || [])[p.dataIndex] || {}
      return `${p.name}<br/>${t('obsPage.calls')} ${m.calls ?? 0}${t('obsPage.callsUnit')}${t('obsPage.tokenPrefix')}${intFmt(m.tokens)}`
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
  if (t === 'logs' && logs.value.length === 0) loadLogs(true)
  if (t === 'gray' && !grayData.value && grayTag.value.trim()) loadGray()
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
      loadVersions(true),
      loadLogs(true)
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

async function loadLogs(reset: boolean) {
  if (reset) logPage.value = 1
  logLoading.value = true
  try {
    const data = await http.get('/observability/logs', {
      params: {
        level: logLevel.value || undefined,
        keyword: logKeyword.value || undefined,
        page: logPage.value, pageSize: 50
      }
    }) as any
    logs.value = data.list || []
    logTotal.value = data.total || 0
  } finally {
    logLoading.value = false
  }
}

function levelType(level: string) {
  if (level === 'ERROR') return 'danger'
  if (level === 'WARN') return 'warning'
  return 'info'
}

async function loadGray() {
  if (!grayTag.value.trim()) return
  grayLoading.value = true
  try {
    grayData.value = await http.get('/observability/gray/compare', {
      params: { tag: grayTag.value.trim(), days: grayDays.value, agentId }
    })
  } finally {
    grayLoading.value = false
  }
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
  const parse = (v: any) => {
    const n = Date.parse(String(v || '').replace(' ', 'T'))
    return Number.isFinite(n) ? n : 0
  }
  const starts = spans.map((s: any) => parse(s.startTime)).filter(Boolean)
  const origin = starts.length ? Math.min(...starts) : parse(current.startTime)
  const end = Math.max(...spans.map((s: any) => parse(s.startTime) + num(s.durationMs)), origin + num(current.latencyMs))
  const total = Math.max(end - origin, 1)
  return spans.map((s: any) => {
    const start = parse(s.startTime) || origin
    const width = Math.max(0.4, Math.min(100, num(s.durationMs) * 100 / total))
    return {
      ...s, depth: depth(s),
      leftPct: Math.max(0, Math.min(99.6, (start - origin) * 100 / total)),
      widthPct: Math.min(100, width)
    }
  })
})

const modelTraceRows = computed(() => (current.spans || [])
  .filter((s: any) => s.spanType === 'llm' || /model|fallback|circuit/i.test(String(s.name || '')))
  .sort((a: any, b: any) => String(a.startTime || '').localeCompare(String(b.startTime || ''))))

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
.log-exception {
  margin: 0;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow: auto;
}
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
.span-head { display: flex; align-items: center; gap: 10px; margin-top: 14px; }
.span-head .io-title { margin: 0; flex: 1; }
.waterfall { margin-top: 8px; border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px 10px; }
.waterfall-axis { display: flex; justify-content: space-between; color: #c0c4cc; font-size: 11px; margin-left: 250px; padding-bottom: 4px; }
.waterfall-row { display: grid; grid-template-columns: 250px 1fr; align-items: center; min-height: 28px; border-top: 1px dashed #f2f3f5; }
.waterfall-label { display: flex; align-items: center; gap: 6px; overflow: hidden; white-space: nowrap; font-size: 12px; }
.waterfall-label .s-type { width: 52px; }
.waterfall-label .s-name { overflow: hidden; text-overflow: ellipsis; }
.waterfall-track { position: relative; height: 16px; background: #f5f7fa; border-radius: 3px; }
.waterfall-bar { position: absolute; top: 2px; height: 12px; min-width: 2px; border-radius: 3px; opacity: .85; display: flex; align-items: center; overflow: hidden; }
.waterfall-bar span { font-size: 10px; color: #fff; padding-left: 4px; white-space: nowrap; }
.waterfall-bar.agent { background: #3B82F6; }
.waterfall-bar.chain { background: #10B981; }
.waterfall-bar.llm { background: #F59E0B; }
.waterfall-bar.tool { background: #6B7280; }
.waterfall-bar.retrieval { background: #8B5CF6; }
.model-trace { border: 1px solid #ebeef5; border-radius: 6px; padding: 6px 10px; }
.model-trace-row { display: flex; align-items: center; gap: 10px; padding: 5px 0; border-bottom: 1px dashed #f2f3f5; font-size: 12px; }
.model-trace-row:last-child { border-bottom: 0; }
.gray-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 6px; }
.gray-grid :deep(.el-card__body) { padding: 8px; }
@media (max-width: 768px) {
  .waterfall-axis { margin-left: 140px; }
  .waterfall-row { grid-template-columns: 140px 1fr; }
  .waterfall-label .s-name { max-width: 70px; }
  .gray-grid { grid-template-columns: 1fr; }
}


@media (max-width: 768px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  .charts { grid-template-columns: 1fr; }
  .toolbar { flex-wrap: wrap; row-gap: 8px; }
  .adv-form .row { flex-wrap: wrap; }
}
</style>