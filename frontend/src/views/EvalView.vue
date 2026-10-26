<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/agents')">
        <el-icon><ArrowLeft /></el-icon> {{ t('evalPage.back') }}
      </el-button>
      <span class="page-title">{{ t('page.eval') }}</span>
      <el-button size="small" @click="openStepRun">{{ t('evalPage.stepRun') }}</el-button>
      <el-radio-group v-model="tab">
        <el-radio-button value="once">{{ t('evalPage.once') }}</el-radio-button>
        <el-radio-button value="online">{{ t('evalPage.online') }}</el-radio-button>
        <el-radio-button value="pre">{{ t('evalPage.pre') }}</el-radio-button>
        <el-radio-button value="dataset">{{ t('evalPage.dataset') }}</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 一次性评测 -->
    <template v-if="tab === 'once'">
      <div class="sub-bar">
        <span></span>
        <div>
          <el-select v-model="agentId" :placeholder="t('evalPage.selectAgent')" size="small" style="width: 180px; margin-right: 8px"
                     @change="loadTasks">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
          <el-button type="primary" size="small" @click="taskDialog = true">{{ t('evalPage.startEval') }}</el-button>
        </div>
      </div>
      <el-card shadow="never">
        <el-table :data="tasks" size="small" @row-click="openTask">
          <el-table-column prop="name" :label="t('evalPage.taskName')" min-width="150" />
          <el-table-column prop="datasetName" :label="t('evalPage.datasetName')" width="130" />
          <el-table-column :label="t('evalPage.progress')" width="90">
            <template #default="{ row }">{{ row.completedCalls ?? 0 }}/{{ row.estimatedCalls ?? '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.status')" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'DONE' ? 'success' : row.status === 'RUNNING' ? 'warning' : row.status === 'FAILED' ? 'danger' : 'info'">
                {{ statusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('evalPage.avgScore')" width="90">
            <template #default="{ row }">{{ row.avgScore != null ? (row.avgScore * 100).toFixed(1) : '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.passRate')" width="90">
            <template #default="{ row }">{{ row.passRate != null ? (row.passRate * 100).toFixed(0) + '%' : '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.time')" width="140">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="tasks.length === 0" :description="t('evalPage.emptyTasks')" :image-size="60" />
      </el-card>
    </template>

    <!-- 在线监控 -->
    <template v-else-if="tab === 'online'">
      <div class="sub-bar">
        <span class="hint-inline">{{ t('evalPage.onlineHint') }}</span>
        <div>
          <el-select v-model="agentId" :placeholder="t('evalPage.selectAgent')" size="small" style="width: 160px; margin-right: 8px"
                     @change="loadTasks">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
          <el-button type="primary" size="small" :loading="onlineRunning" @click="runOnline">{{ t('evalPage.sampleNow') }}</el-button>
        </div>
      </div>
      <el-card shadow="never">
        <el-table :data="onlineTasks" size="small">
          <el-table-column prop="name" :label="t('evalPage.taskName')" min-width="150" />
          <el-table-column :label="t('evalPage.type')" width="110">
            <template #default="{ row }">
              <el-tag size="small">{{ row.evalType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('evalPage.sampleProgress')" width="90">
            <template #default="{ row }">{{ row.completedCalls ?? 0 }}/{{ row.estimatedCalls ?? '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.status')" width="90">
            <template #default="{ row }">{{ statusText(row.status) }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.avgScore')" width="90">
            <template #default="{ row }">{{ row.avgScore != null ? (row.avgScore * 100).toFixed(1) : '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.time')" width="140">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="onlineTasks.length === 0" :description="t('evalPage.emptyOnline')" :image-size="60" />
      </el-card>
    </template>

    <!-- 预发布评测 -->
    <template v-else-if="tab === 'pre'">
      <div class="sub-bar">
        <span class="hint-inline">{{ t('evalPage.preHint') }}</span>
        <div>
          <el-select v-model="agentId" :placeholder="t('evalPage.selectAgent')" size="small" style="width: 160px; margin-right: 8px"
                     @change="loadTasks">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
          <el-button type="primary" size="small" :loading="preRunning" @click="runPreRelease">{{ t('evalPage.runPre') }}</el-button>
        </div>
      </div>
      <el-card shadow="never">
        <el-table :data="preTasks" size="small">
          <el-table-column prop="name" :label="t('evalPage.taskName')" min-width="150" />
          <el-table-column prop="datasetName" :label="t('evalPage.datasetName')" width="140" />
          <el-table-column :label="t('evalPage.passRate')" width="100">
            <template #default="{ row }">{{ row.passRate != null ? (row.passRate * 100).toFixed(0) + '%' : '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.status')" width="90">
            <template #default="{ row }">{{ statusText(row.status) }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.time')" width="150">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="preTasks.length === 0" :description="t('evalPage.emptyPre')" :image-size="60" />
      </el-card>
    </template>

    <!-- 数据集管理 -->
    <template v-else>
      <div class="sub-bar">
        <span></span>
        <div>
          <el-button size="small" @click="openCoverage">{{ t('evalPage.coverageMatrix') }}</el-button>
          <el-button type="primary" size="small" @click="dsDialog = true">{{ t('evalPage.newDs') }}</el-button>
        </div>
      </div>
      <el-card shadow="never">
        <el-table :data="datasets" size="small">
          <el-table-column prop="name" :label="t('evalPage.name')" width="180" />
          <el-table-column prop="description" :label="t('evalPage.desc')" min-width="180" />
          <el-table-column prop="itemCount" :label="t('evalPage.itemCount')" width="80" />
          <el-table-column :label="t('evalPage.boundAgent')" width="110">
            <template #default="{ row }">{{ agentName(row.agentId) }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.actions')" width="260">
            <template #default="{ row }">
              <el-button size="small" @click="openItems(row)">{{ t('evalPage.sampleMgmt') }}</el-button>
              <el-button size="small" @click="openTrend(row)">{{ t('evalPage.trend') }}</el-button>
              <el-button size="small" type="danger" plain @click="removeDs(row)">{{ t('evalPage.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- 开始评测弹窗 -->
    <el-dialog v-model="taskDialog" :title="t('evalPage.startTitle')" width="480px">
      <el-form label-width="90px">
        <el-form-item :label="t('evalPage.taskName')">
          <el-input v-model="taskForm.name" :placeholder="t('evalPage.taskNamePh')" />
        </el-form-item>
        <el-form-item label="Agent" required>
          <el-select v-model="taskForm.agentId" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('evalPage.datasetName')" required>
          <el-select v-model="taskForm.datasetId" style="width: 100%">
            <el-option v-for="d in datasets" :key="d.id" :value="d.id" :label="`${d.name} (${d.itemCount}${t('evalPage.sampleSuffix')})`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('evalPage.judgeModel')">
          <el-select v-model="taskForm.judgeModel" style="width: 100%">
            <el-option v-for="m in models" :key="m.modelName" :value="m.modelName" :label="modelLabel(m)" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('evalPage.threshold')">
          <el-slider v-model="taskForm.passThreshold" :min="0.3" :max="0.95" :step="0.05" style="width: 90%" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">{{ t('evalPage.flowHint') }}</div>
      <template #footer>
        <el-button @click="taskDialog = false">{{ t('evalPage.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="createTask">{{ t('evalPage.submit') }}</el-button>
      </template>
    </el-dialog>

    <!-- 任务详情抽屉 -->
    <el-drawer v-model="taskVisible" :title="t('evalPage.resultTitle') + (current.name || '')" size="70%">
      <div class="sum-cards">
        <div class="sc"><div class="k">{{ t('evalPage.status') }}</div><div class="v">{{ statusText(current.status) }}</div></div>
        <div class="sc"><div class="k">{{ t('evalPage.avgScore') }}</div><div class="v">{{ current.avgScore != null ? (current.avgScore * 100).toFixed(1) : '-' }}</div></div>
        <div class="sc"><div class="k">{{ t('evalPage.passRateShort') }}</div><div class="v">{{ current.passRate != null ? (current.passRate * 100).toFixed(0) + '%' : '-' }}</div></div>
        <div class="sc"><div class="k">{{ t('evalPage.judgeModel') }}</div><div class="v small">{{ current.judgeModel }}</div></div>
      </div>
      <div v-if="current.stepSummary" class="step-summary">
        <div class="step-summary-title">{{ t('evalPage.stepSummary') }}</div>
        <div class="step-summary-row">
          <el-tag v-for="(v, k) in current.stepSummary" :key="k" size="small"
                  :type="v.count > 0 && v.passRate >= 0.8 ? 'success' : v.count > 0 ? 'warning' : 'info'">
            {{ t('evalPage.step' + String(k).charAt(0) + String(k).slice(1).toLowerCase()) }}：
            {{ v.count }} / {{ v.passRate != null ? (v.passRate * 100).toFixed(0) + '%' : '-' }}
          </el-tag>
        </div>
      </div>
      <div v-if="current.stepGate" class="gate-panel"
           :class="{ 'gate-panel-failed': current.stepGate.failedSteps && current.stepGate.failedSteps.length }">
        <div class="gate-head">
          <el-tag :type="current.stepGate.failedSteps && current.stepGate.failedSteps.length ? 'danger' : 'success'" size="small">
            {{ current.stepGate.failedSteps && current.stepGate.failedSteps.length
              ? t('evalPage.gateFailed') : t('evalPage.gatePassed') }}
          </el-tag>
          <span>{{ t('evalPage.gateThreshold') }}{{ pct(current.stepGate.threshold) }}</span>
          <span v-if="current.stepGate.failedSteps && current.stepGate.failedSteps.length">
            {{ t('evalPage.failedSteps') }}{{ current.stepGate.failedSteps.map(stepName).join(' / ') }}
          </span>
        </div>
        <div v-for="(failure, idx) in (current.stepGate.failures || [])" :key="idx" class="gate-failure">
          <b>{{ stepName(failure.stepType) }}</b>
          <span>score {{ failure.score }}</span>
          <span>{{ failure.reason }}</span>
          <span class="trace-link">{{ failure.traceId }}</span>
        </div>
      </div>
      <div v-if="current.id" class="compare-bar">
        <span class="compare-label">{{ t('evalPage.compare') }}</span>
        <el-select v-model="compareBaselineId" size="small" style="width: 240px"
                   :placeholder="t('evalPage.baselinePh')">
          <el-option v-for="t in baselineCandidates" :key="t.id" :value="t.id"
                     :label="`${t.name} · ${pct(t.passRate)}`" />
        </el-select>
        <el-button size="small" :disabled="!compareBaselineId" :loading="compareLoading"
                   @click="loadCompare">{{ t('evalPage.compareRun') }}</el-button>
        <span v-if="!baselineCandidates.length" class="hint-inline">{{ t('evalPage.noBaseline') }}</span>
      </div>
      <div v-if="compareResult" class="compare-result">
        <div class="compare-tags">
          <el-tag v-for="type in compareResult.comparison.improved" :key="'i' + type"
                  size="small" type="success">{{ stepName(type) }} {{ t('evalPage.improved') }}</el-tag>
          <el-tag v-for="type in compareResult.comparison.regressed" :key="'r' + type"
                  size="small" type="danger">{{ stepName(type) }} {{ t('evalPage.regressed') }}</el-tag>
          <span v-if="!compareResult.comparison.improved.length && !compareResult.comparison.regressed.length"
                class="hint-inline">{{ t('evalPage.noDelta') }}</span>
        </div>
        <el-table :data="compareRows" size="small">
          <el-table-column :label="t('evalPage.stepType')" width="110">
            <template #default="{ row }">{{ stepName(row.type) }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.currentPassRate')" width="90">
            <template #default="{ row }">{{ pct(row.current.passRate) }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.baselinePassRate')" width="90">
            <template #default="{ row }">{{ pct(row.baseline.passRate) }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.delta')" width="90">
            <template #default="{ row }">
              <span :class="deltaClass(row.passRateDelta)">{{ deltaText(row.passRateDelta) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-table :data="current.results || []" size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <div><b>{{ t('evalPage.expected') }}</b>{{ row.expectedAnswer || t('evalPage.none') }}</div>
              <div class="mt4"><b>{{ t('evalPage.actual') }}</b>{{ row.answer }}</div>
              <div class="mt4"><b>{{ t('evalPage.judgeReason') }}</b>{{ row.judgeReason }}</div>
              <div v-if="row.stepDetails" class="mt4"><b>{{ t('evalPage.stepDetails') }}</b><pre class="step-detail">{{ row.stepDetails }}</pre></div>
              <div v-if="row.reviewNote" class="mt4"><b>{{ t('evalPage.reviewNote') }}</b>{{ row.reviewNote }}</div>
              <div class="mt4 trace-link">Trace: {{ row.traceId }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('evalPage.question')" min-width="140">
          <template #default="{ row }">{{ clip(row.question, 24) }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.stepType')" width="105">
          <template #default="{ row }">
            <el-tag size="small">{{ row.stepType || 'ANSWER' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('evalPage.answer')" min-width="180">
          <template #default="{ row }">{{ clip(row.answer, 32) }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.score')" width="80">
          <template #default="{ row }">{{ row.score != null ? (row.score * 100).toFixed(0) : '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.pass')" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.passed ? 'success' : 'danger'">{{ row.passed ? t('evalPage.passed') : t('evalPage.failed') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('evalPage.latency')" width="80">
          <template #default="{ row }">{{ row.latencyMs }}ms</template>
        </el-table-column>
        <el-table-column prop="tokens" label="Token" width="80" />
        <el-table-column :label="t('evalPage.review')" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.reviewStatus === 'REVIEWED'" size="small" type="success">{{ t('evalPage.reviewed') }}</el-tag>
            <el-button v-else-if="row.id" size="small" text type="primary" @click.stop="openReview(row)">
              {{ t('evalPage.review') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="reviewVisible" :title="t('evalPage.reviewTitle')" width="520px">
      <el-input v-model="reviewNote" type="textarea" :rows="4"
                :placeholder="t('evalPage.reviewNotePh')" />
      <div class="hint mt8">{{ t('evalPage.reviewHint') }}</div>
      <template #footer>
        <el-button @click="reviewVisible = false">{{ t('evalPage.cancel') }}</el-button>
        <el-button type="primary" :loading="reviewSaving" @click="saveReview">
          {{ t('evalPage.markReviewed') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 样本管理抽屉 -->
    <el-drawer v-model="itemsVisible" :title="t('evalPage.sampleTitle') + (currentDs.name || '')" size="640px">
      <div v-if="stepCoverage" class="step-coverage">
        <span class="step-coverage-label">{{ t('evalPage.stepCoverage') }}</span>
        <el-tag v-for="(count, type) in stepCoverage.steps" :key="type" size="small"
                :type="count > 0 ? 'success' : 'info'">{{ type }} {{ count }}</el-tag>
        <span v-if="stepCoverage.missing && stepCoverage.missing.length" class="step-coverage-missing">
          {{ t('evalPage.missingSteps') }}{{ stepCoverage.missing.join(' / ') }}
        </span>
      </div>
      <div class="import-box">
        <el-input v-model="jsonl" type="textarea" :rows="3"
                  :placeholder="t('evalPage.importPh')" />
        <el-button size="small" class="mt8" @click="importItems">{{ t('evalPage.import') }}</el-button>
      </div>
      <el-divider />
      <div class="item-form">
        <el-input v-model="itemForm.question" :placeholder="t('evalPage.question')" size="small" />
        <el-select v-model="itemForm.stepType" size="small" style="width: 120px">
          <el-option v-for="s in stepTypes" :key="s.stepType" :label="s.stepType" :value="s.stepType" />
        </el-select>
        <el-input v-model="itemForm.expectedAnswer" :placeholder="t('evalPage.expectedAnswer')" size="small" />
        <el-input v-model="itemForm.scoreCriteria" :placeholder="t('evalPage.criteriaPh')" size="small" />
        <el-input v-model="itemForm.expectedPayload" :placeholder="t('evalPage.expectedPayloadPh')" size="small" />
        <el-input v-model="itemForm.stepConfig" :placeholder="t('evalPage.stepConfigPh')" size="small" />
        <el-button size="small" type="primary" @click="addItem">{{ t('evalPage.addSample') }}</el-button>
      </div>
      <el-table :data="items" size="small" class="mt8">
        <el-table-column :label="t('evalPage.question')" min-width="160">
          <template #default="{ row }">{{ clip(row.question, 30) }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.stepType')" width="100">
          <template #default="{ row }">{{ row.stepType || 'ANSWER' }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.expectedAnswer')" min-width="160">
          <template #default="{ row }">{{ clip(row.expectedAnswer, 30) }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.expectedPayload')" min-width="150">
          <template #default="{ row }">{{ clip(row.expectedPayload, 30) }}</template>
        </el-table-column>
        <el-table-column width="60">
          <template #default="{ row }">
            <el-button size="small" text type="danger" @click="removeItem(row)">{{ t('evalPage.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 数据集趋势 -->
    <el-dialog v-model="trendVisible" :title="t('evalPage.trendTitle') + (trendDataset.name || '')" width="900px">
      <el-table v-loading="trendLoading" :data="trendTasks" size="small">
        <el-table-column prop="name" :label="t('evalPage.task')" min-width="150" />
        <el-table-column :label="t('evalPage.time')" width="140">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.avgScore')" width="85">
          <template #default="{ row }">{{ pct(row.avgScore) }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.passRateShort')" width="85">
          <template #default="{ row }">{{ pct(row.passRate) }}</template>
        </el-table-column>
        <el-table-column v-for="type in stepKeys" :key="type" :label="stepName(type)" width="90">
          <template #default="{ row }">{{ pct(row.stepSummary && row.stepSummary[type] && row.stepSummary[type].passRate) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!trendLoading && !trendTasks.length" :description="t('evalPage.emptyTrend')" :image-size="60" />
    </el-dialog>

    <!-- 覆盖矩阵 -->
    <el-dialog v-model="coverageVisible" :title="t('evalPage.coverageTitle')" width="880px">
      <div v-loading="coverageLoading">
        <div v-if="coverage" class="coverage-totals">
          <span v-for="type in stepKeys" :key="type" class="coverage-total">
            {{ stepName(type) }}：{{ coverage.totalByType[type] || 0 }}
          </span>
          <span v-if="coverage.missingTypes && coverage.missingTypes.length" class="step-coverage-missing">
            {{ t('evalPage.missingSteps') }}{{ coverage.missingTypes.map(stepName).join(' / ') }}
          </span>
        </div>
        <el-table v-if="coverage" :data="coverage.datasets" size="small">
          <el-table-column prop="name" :label="t('evalPage.datasetName')" min-width="170" />
          <el-table-column v-for="type in stepKeys" :key="type" :label="stepName(type)" width="110">
            <template #default="{ row }">{{ row.steps[type] || 0 }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.missingSteps')" min-width="160">
            <template #default="{ row }">
              <el-tag v-for="type in row.missing" :key="type" size="small" type="warning" class="mr4">
                {{ stepName(type) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- 单步试跑 -->
    <el-dialog v-model="stepRunVisible" :title="t('evalPage.stepRunTitle')" width="640px">
      <el-form label-width="96px">
        <el-form-item :label="t('evalPage.stepType')">
          <el-select v-model="stepRunForm.stepType" style="width: 100%">
            <el-option v-for="s in standaloneStepTypes" :key="s.stepType" :value="s.stepType"
                       :label="`${s.stepType} · ${s.name}`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('evalPage.question')" required>
          <el-input v-model="stepRunForm.query" :placeholder="t('evalPage.stepRunQueryPh')" />
        </el-form-item>
        <el-form-item :label="t('evalPage.expectedPayload')">
          <el-input v-model="stepRunForm.expectedPayload" type="textarea" :rows="3"
                    :placeholder="t('evalPage.expectedPayloadPh')" />
        </el-form-item>
        <el-form-item :label="t('evalPage.stepConfig')">
          <el-input v-model="stepRunForm.stepConfig" type="textarea" :rows="3"
                    :placeholder="t('evalPage.stepConfigPh')" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">{{ t('evalPage.stepRunHint') }}</div>
      <div v-if="stepRunError" class="step-run-error">{{ stepRunError }}</div>
      <div v-if="stepRunResult" class="step-run-result">
        <div class="step-run-metrics">
          <el-tag :type="stepRunResult.passed ? 'success' : 'danger'">
            {{ stepRunResult.passed ? t('evalPage.passed') : t('evalPage.failed') }}
          </el-tag>
          <span>{{ t('evalPage.stepScore') }}：{{ (stepRunResult.score * 100).toFixed(1) }}</span>
        </div>
        <div class="step-run-block">
          <b>{{ t('evalPage.actualOutput') }}</b>
          <pre>{{ stepRunResult.actual }}</pre>
        </div>
        <div class="step-run-block">
          <b>{{ t('evalPage.stepDetails') }}</b>
          <pre>{{ JSON.stringify(stepRunResult.details, null, 2) }}</pre>
        </div>
      </div>
      <template #footer>
        <el-button @click="stepRunVisible = false">{{ t('evalPage.cancel') }}</el-button>
        <el-button type="primary" :loading="stepRunSaving" @click="runStep">{{ t('evalPage.runStep') }}</el-button>
      </template>
    </el-dialog>

    <!-- 新建数据集 -->
    <el-dialog v-model="dsDialog" :title="t('evalPage.dsNew')" width="460px">
      <el-form label-width="80px">
        <el-form-item :label="t('evalPage.name')" required><el-input v-model="dsForm.name" /></el-form-item>
        <el-form-item :label="t('evalPage.desc')"><el-input v-model="dsForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item :label="t('evalPage.bindAgent')">
          <el-select v-model="dsForm.agentId" clearable :placeholder="t('evalPage.dsAgentPh')" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dsDialog = false">{{ t('evalPage.cancel') }}</el-button>
        <el-button type="primary" @click="createDs">{{ t('evalPage.create') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'

const { t } = useI18n()

const route = useRoute()
const tab = ref('once')
const agentId = ref<number | null>(route.params.agentId ? Number(route.params.agentId) : null)
const agents = ref<any[]>([])
const models = ref<any[]>([])
const datasets = ref<any[]>([])
function modelLabel(m: any) {
  const name = m.displayName || m.modelName
  const c = Number(m.contextWindow || 0)
  return c > 0 ? `${name} (${c >= 1048576 ? (c / 1048576) + 'M' : (c / 1024) + 'K'}${t('evalPage.contextSuffix')})` : name
}
const tasks = ref<any[]>([])
const taskDialog = ref(false)
const creating = ref(false)
const taskForm = reactive({ name: '', agentId: null as number | null, datasetId: null as number | null, judgeModel: 'deepseek-flash', passThreshold: 0.6 })
const taskVisible = ref(false)
const current = reactive<any>({})
const itemsVisible = ref(false)
const currentDs = reactive<any>({})
const items = ref<any[]>([])
const stepCoverage = ref<any>(null)
const stepTypes = ref<any[]>([
  { stepType: 'ANSWER', name: '最终回答' },
  { stepType: 'REWRITE', name: '查询改写' },
  { stepType: 'RETRIEVAL', name: '知识检索' },
  { stepType: 'RERANK', name: '重排' }
])
const stepRunVisible = ref(false)
const stepRunSaving = ref(false)
const stepRunError = ref('')
const stepRunResult = ref<any>(null)
const stepRunForm = reactive({ stepType: 'REWRITE', query: '', expectedPayload: '', stepConfig: '' })
const stepKeys = ['ANSWER', 'REWRITE', 'RETRIEVAL', 'RERANK']
const trendVisible = ref(false)
const trendLoading = ref(false)
const trendDataset = reactive<any>({})
const trendTasks = ref<any[]>([])
const coverageVisible = ref(false)
const coverageLoading = ref(false)
const coverage = ref<any>(null)
const compareBaselineId = ref<number | null>(null)
const compareLoading = ref(false)
const compareResult = ref<any>(null)
const reviewVisible = ref(false)
const reviewSaving = ref(false)
const reviewRow = ref<any>(null)
const reviewNote = ref('')
const jsonl = ref('')
const itemForm = reactive({ question: '', expectedAnswer: '', scoreCriteria: '', stepType: 'ANSWER', expectedPayload: '', stepConfig: '' })
const dsDialog = ref(false)
const dsForm = reactive({ name: '', description: '', agentId: null as number | null })

onMounted(async () => {
  const [a, m, ds, st] = await Promise.all([
    http.get('/agent', { params: { pageSize: 100 } }) as any,
    http.get('/provider/models') as any,
    http.get('/eval/datasets') as any,
    http.get('/eval/step-types').catch(() => []) as any
  ])
  agents.value = a.list || []
  models.value = (m || []).filter((x: any) => !x.purpose || String(x.purpose).toUpperCase() === 'CHAT')
  datasets.value = ds || []
  if (Array.isArray(st) && st.length) stepTypes.value = st
  if (!taskForm.agentId && agents.value.length) taskForm.agentId = agents.value[0].id
  if (agentId.value) taskForm.agentId = agentId.value
  await loadTasks()
})

async function loadTasks() {
  const data = await http.get('/eval/tasks', { params: { agentId: agentId.value || undefined } }) as any
  const all = data.list || []
  tasks.value = all.filter((t: any) => t.evalType === 'ONCE')
  onlineTasks.value = all.filter((t: any) => t.evalType === 'ONLINE' || t.evalType === 'FEEDBACK')
  preTasks.value = all.filter((t: any) => t.evalType === 'PRE_RELEASE')
}

const onlineTasks = ref<any[]>([])
const preTasks = ref<any[]>([])
const onlineRunning = ref(false)
const preRunning = ref(false)

async function runOnline() {
  if (!agentId.value && agents.value.length) agentId.value = agents.value[0].id
  if (!agentId.value) { ElMessage.warning(t('evalPage.selectAgentWarn')); return }
  onlineRunning.value = true
  try {
    const res = await http.post('/eval/online', null, { params: { agentId: agentId.value, sampleSize: 10 } }) as any
    ElMessage.success(`${t('evalPage.sampledToast')}${res.sampled ?? 0}${t('evalPage.traces')}`)
    setTimeout(loadTasks, 2500)
  } finally {
    onlineRunning.value = false
  }
}

async function runPreRelease() {
  if (!agentId.value && agents.value.length) agentId.value = agents.value[0].id
  if (!agentId.value) { ElMessage.warning(t('evalPage.selectAgentWarn')); return }
  preRunning.value = true
  try {
    const res = await http.post('/eval/pre-release', null, { params: { agentId: agentId.value } }) as any
    ElMessage.success(`${t('evalPage.preSubmitted')}${res.taskId}${t('evalPage.preSubmittedSuffix')}`)
    setTimeout(loadTasks, 2500)
  } finally {
    preRunning.value = false
  }
}

async function createTask() {
  if (!taskForm.agentId || !taskForm.datasetId) { ElMessage.warning(t('evalPage.selectAgentDs')); return }
  creating.value = true
  try {
    await http.post('/eval/tasks', { ...taskForm })
    ElMessage.success(t('evalPage.taskSubmitted'))
    taskDialog.value = false
    setTimeout(loadTasks, 1500)
  } finally {
    creating.value = false
  }
}

async function openTask(row: any) {
  compareBaselineId.value = null
  compareResult.value = null
  Object.assign(current, await http.get(`/eval/tasks/${row.id}`) as any)
  taskVisible.value = true
}

async function loadCompare() {
  if (!compareBaselineId.value) return
  compareLoading.value = true
  try {
    compareResult.value = await http.get(
      `/eval/tasks/${current.id}/compare/${compareBaselineId.value}`) as any
  } finally {
    compareLoading.value = false
  }
}

async function openTrend(row: any) {
  Object.assign(trendDataset, row)
  trendTasks.value = []
  trendVisible.value = true
  trendLoading.value = true
  try {
    const data = await http.get(`/eval/datasets/${row.id}/trend`, {
      params: { agentId: row.agentId || undefined, limit: 10 }
    }) as any
    trendTasks.value = data.tasks || []
  } finally {
    trendLoading.value = false
  }
}

async function openCoverage() {
  coverage.value = null
  coverageVisible.value = true
  coverageLoading.value = true
  try {
    coverage.value = await http.get('/eval/coverage', {
      params: { agentId: agentId.value || undefined }
    }) as any
  } finally {
    coverageLoading.value = false
  }
}

function stepName(type: string) {
  return t('evalPage.step' + type.charAt(0) + type.slice(1).toLowerCase())
}

function pct(value: any) {
  return value == null ? '-' : (Number(value) * 100).toFixed(0) + '%'
}

function deltaText(value: any) {
  if (value == null) return '-'
  const n = Number(value) * 100
  return (n > 0 ? '+' : '') + n.toFixed(0)
}

function deltaClass(value: any) {
  if (value == null || Number(value) === 0) return ''
  return Number(value) > 0 ? 'delta-up' : 'delta-down'
}

async function createDs() {
  if (!dsForm.name.trim()) { ElMessage.warning(t('evalPage.nameRequired')); return }
  await http.post('/eval/datasets', { ...dsForm })
  ElMessage.success(t('evalPage.created'))
  dsDialog.value = false
  datasets.value = await http.get('/eval/datasets') as any
}

async function removeDs(row: any) {
  await ElMessageBox.confirm(`${t('evalPage.deleteDsPrefix')}${row.name}${t('evalPage.deleteDsMid')}`, t('evalPage.confirm'), { type: 'warning' })
  await http.delete(`/eval/datasets/${row.id}`)
  datasets.value = await http.get('/eval/datasets') as any
}

async function openItems(row: any) {
  Object.assign(currentDs, row)
  itemsVisible.value = true
  stepCoverage.value = null
  const [list, coverage] = await Promise.all([
    http.get(`/eval/datasets/${row.id}/items`) as any,
    http.get(`/eval/datasets/${row.id}/step-summary`).catch(() => null) as any
  ])
  items.value = list
  stepCoverage.value = coverage
}

async function addItem() {
  if (!itemForm.question.trim()) { ElMessage.warning(t('evalPage.questionRequired')); return }
  await http.post(`/eval/datasets/${currentDs.id}/items`, { ...itemForm })
  items.value = await http.get(`/eval/datasets/${currentDs.id}/items`) as any
  Object.assign(itemForm, { question: '', expectedAnswer: '', scoreCriteria: '', stepType: 'ANSWER', expectedPayload: '', stepConfig: '' })
}

const standaloneStepTypes = computed(() =>
  stepTypes.value.filter((s: any) => s.stepType !== 'ANSWER'))

const baselineCandidates = computed(() =>
  tasks.value.filter((t: any) => t.id !== current.id && t.datasetId === current.datasetId
    && t.status === 'DONE'))

const compareRows = computed(() => {
  const steps = compareResult.value?.comparison?.steps || {}
  return stepKeys.map((type) => ({
    type,
    current: steps[type]?.current || {},
    baseline: steps[type]?.baseline || {},
    passRateDelta: steps[type]?.passRateDelta,
  }))
})

function openStepRun() {
  stepRunVisible.value = true
  stepRunError.value = ''
  stepRunResult.value = null
}

async function runStep() {
  if (!stepRunForm.query.trim()) {
    ElMessage.warning(t('evalPage.stepRunQueryRequired'))
    return
  }
  stepRunSaving.value = true
  stepRunError.value = ''
  stepRunResult.value = null
  try {
    stepRunResult.value = await http.post('/eval/steps/run', { ...stepRunForm }) as any
  } catch (e: any) {
    stepRunError.value = e?.response?.data?.message || e?.message || t('evalPage.stepRunFailed')
  } finally {
    stepRunSaving.value = false
  }
}

function openReview(row: any) {
  reviewRow.value = row
  reviewNote.value = row.reviewNote || ''
  reviewVisible.value = true
}

async function saveReview() {
  if (!reviewRow.value) return
  reviewSaving.value = true
  try {
    const data = await http.put(`/eval/results/${reviewRow.value.id}/review`, {
      reviewed: true, note: reviewNote.value
    }) as any
    reviewRow.value.reviewStatus = data.reviewStatus
    reviewRow.value.reviewNote = data.reviewNote
    reviewVisible.value = false
    ElMessage.success(t('evalPage.reviewSaved'))
  } finally {
    reviewSaving.value = false
  }
}

async function importItems() {
  if (!jsonl.value.trim()) return
  const res = await http.post(`/eval/datasets/${currentDs.id}/items/import`, { jsonl: jsonl.value }) as any
  ElMessage.success(`${t('evalPage.imported')}${res.imported}${t('evalPage.importedSuffix')}`)
  jsonl.value = ''
  items.value = await http.get(`/eval/datasets/${currentDs.id}/items`) as any
}

async function removeItem(row: any) {
  await http.delete(`/eval/datasets/items/${row.id}`)
  items.value = await http.get(`/eval/datasets/${currentDs.id}/items`) as any
}

function agentName(id: number | null) {
  return agents.value.find(a => a.id === id)?.name || '-'
}
function statusText(s: string) {
  return s === 'DONE' ? t('evalPage.done') : s === 'RUNNING' ? t('evalPage.running') : s === 'FAILED' ? t('evalPage.failedStatus') : t('evalPage.pending')
}
function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(5, 16) : '-' }
function clip(t: string, n: number) { t = t || ''; return t.length > n ? t.slice(0, n) + '…' : t }
</script>

<style scoped>
.page { max-width: 1150px; margin: 0 auto; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; flex: 1; }
.hint-inline { font-size: 12px; color: #909399; max-width: 560px; }
.mb12 { margin-bottom: 12px; }
.sub-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.dialog-hint { font-size: 12px; color: #909399; margin-top: 6px; }
.sum-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 14px; }
.sc { background: #f5f7fa; border-radius: 6px; padding: 10px; }
.k { font-size: 12px; color: #909399; }
.v { font-size: 20px; font-weight: 700; margin-top: 4px; }
.v.small { font-size: 14px; }
.expand { padding: 6px 12px; font-size: 13px; line-height: 1.7; }
.mt4 { margin-top: 4px; }
.mt8 { margin-top: 8px; }
.trace-link { color: #409eff; font-family: monospace; font-size: 12px; }
.import-box textarea { width: 100%; }
.item-form { display: flex; flex-direction: column; gap: 8px; }

@media (max-width: 768px) {
  .sum-cards { grid-template-columns: repeat(2, 1fr); }
  .toolbar { flex-wrap: wrap; row-gap: 8px; }
}
.step-summary { margin: 10px 0 14px; }
.step-summary-title { font-size: 13px; font-weight: 600; margin-bottom: 6px; }
.step-summary-row { display: flex; gap: 8px; flex-wrap: wrap; }
.step-detail { white-space: pre-wrap; word-break: break-all; font-size: 12px; margin: 4px 0; }
.step-coverage { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.step-coverage-label { font-size: 13px; font-weight: 600; margin-right: 2px; }
.step-coverage-missing { font-size: 12px; color: #e6a23c; }
.step-run-error { margin-top: 8px; color: #f56c6c; font-size: 13px; white-space: pre-wrap; }
.step-run-result { margin-top: 12px; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px; }
.step-run-metrics { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.step-run-block { margin-top: 8px; }
.step-run-block pre { margin: 4px 0 0; white-space: pre-wrap; word-break: break-all; font-size: 12px; background: #f5f7fa; padding: 8px; border-radius: 4px; max-height: 240px; overflow: auto; }
.compare-bar { display: flex; align-items: center; gap: 8px; margin: 8px 0 12px; }
.compare-label { font-size: 13px; font-weight: 600; }
.compare-result { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px; margin-bottom: 14px; }
.compare-tags { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.delta-up { color: #67c23a; }
.delta-down { color: #f56c6c; }
.coverage-totals { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-bottom: 10px; font-size: 13px; }
.coverage-total { font-weight: 600; }
.mr4 { margin-right: 4px; }
.gate-panel { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px; margin-bottom: 12px; background: #f0f9eb; }
.gate-panel-failed { background: #fef0f0; border-color: #fde2e2; }
.gate-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
.gate-failure { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 8px; font-size: 12px; color: #606266; }
</style>