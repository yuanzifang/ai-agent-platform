<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push(`/knowledge/${kbId}`)">
        <el-icon><ArrowLeft /></el-icon> {{ t('graphPage.back') }}
      </el-button>
      <span class="title">{{ t('graphPage.title') }}</span>
      <el-tag size="small">{{ t('graphPage.entityCount') }} {{ graph.entityCount || 0 }}</el-tag>
      <el-tag size="small" type="success">{{ t('graphPage.relationCount') }} {{ graph.relationCount || 0 }}</el-tag>
      <div class="spacer" />
      <template v-if="canEdit">
        <el-button type="primary" :loading="building" :disabled="activeJob" @click="buildGraph">
          {{ activeJob ? t('graphPage.building') : t('graphPage.rebuild') }}
        </el-button>
        <el-button @click="openExtract">{{ t('graphPage.aiExtract') }}</el-button>
        <el-button @click="openCreateEntity">{{ t('graphPage.addEntity') }}</el-button>
        <el-button @click="openCreateRelation">{{ t('graphPage.addRelation') }}</el-button>
      </template>
      <el-button @click="loadAll">{{ t('graphPage.refresh') }}</el-button>
    </div>

    <el-alert v-if="activeJob" type="info" :closable="false" class="job-alert">
      <div class="job-row">
        <span class="job-text">{{ job.message || t('graphPage.building') }}</span>
        <el-progress :percentage="jobPercent" :stroke-width="10" style="flex:1; min-width:180px" />
      </div>
      <div class="job-meta">
        {{ t('graphPage.jobDocs') }} {{ job.processedDocuments || 0 }}/{{ job.totalDocuments || 0 }}
        · {{ t('graphPage.jobChunks') }} {{ job.processedChunks || 0 }}/{{ job.totalChunks || 0 }}
        · {{ t('graphPage.jobEntities') }} {{ job.entityCount || 0 }}
        · {{ t('graphPage.jobRelations') }} {{ job.relationCount || 0 }}
      </div>
    </el-alert>

    <el-tabs v-model="tab" class="graph-tabs">
      <!-- 图谱视图 -->
      <el-tab-pane :label="t('graphPage.graph')" name="graph">
        <div class="filters">
          <el-input v-model="graphSearch" clearable :placeholder="t('graphPage.searchNode')" style="width: 240px" />
          <el-select v-model="graphType" clearable :placeholder="t('graphPage.allTypes')" style="width: 160px">
            <el-option v-for="type in types" :key="type" :label="type" :value="type" />
          </el-select>
          <el-button @click="chartKey++">{{ t('graphPage.resetLayout') }}</el-button>
          <span class="hint">{{ t('graphPage.zoomHint') }}</span>
          <span class="hint auto-hint">{{ t('graphPage.autoHint') }}</span>
        </div>
        <div class="graph-layout">
          <div class="graph-main">
            <el-empty v-if="!chartNodes.length" :description="t('graphPage.empty')" :image-size="70" />
            <KnowledgeGraphChart v-else :key="chartKey" :nodes="chartNodes" :links="chartLinks"
                                 :categories="categoryList" height="560px"
                                 @node-click="onNodeClick" @edge-click="onEdgeClick" />
          </div>
          <el-card shadow="never" class="detail-panel">
            <template #header>
              <div class="detail-head">
                <span>{{ t('graphPage.detail') }}</span>
                <el-button v-if="selectedEntity || selectedRelation" text size="small" @click="closeDetail">
                  {{ t('graphPage.close') }}
                </el-button>
              </div>
            </template>

            <template v-if="selectedEntity">
              <div class="entity-title">
                <span>{{ selectedEntity.name }}</span>
                <el-tag size="small">{{ selectedEntity.entityType || t('graphPage.otherType') }}</el-tag>
              </div>
              <div class="tags">
                <el-tag size="small" :type="selectedEntity.source === 'MANUAL' ? 'success' : 'info'">
                  {{ selectedEntity.source === 'MANUAL' ? t('graphPage.sourceManual') : t('graphPage.sourceAuto') }}
                </el-tag>
                <el-tag size="small" type="warning">
                  {{ t('graphPage.confidence') }} {{ Number(selectedEntity.confidence ?? 1).toFixed(2) }}
                </el-tag>
                <el-tag size="small" type="info">
                  {{ t('graphPage.mentionCount') }} {{ entityDetail?.mentionCount ?? mentionCount(selectedEntity.id) }}
                </el-tag>
              </div>
              <p class="description">{{ selectedEntity.description || t('graphPage.noDescription') }}</p>
              <el-divider content-position="left">{{ t('graphPage.evidence') }}</el-divider>
              <el-skeleton v-if="detailLoading" :rows="3" animated />
              <template v-else>
                <el-empty v-if="!entityDetail?.mentions?.length" :description="t('graphPage.noEvidence')" :image-size="44" />
                <div v-for="m in entityDetail?.mentions || []" :key="m.id" class="mention">
                  <div class="mention-meta">
                    <span>{{ m.documentName || ('#' + (m.documentId || '-')) }}</span>
                    <span v-if="m.chunkIndex != null">#{{ m.chunkIndex }}</span>
                  </div>
                  <div class="quote">{{ m.quote || t('graphPage.noQuote') }}</div>
                </div>
                <el-divider content-position="left">{{ t('graphPage.relations') }}</el-divider>
                <div v-for="r in entityDetail?.relations || []" :key="r.id" class="relation-line">
                  <span>{{ r.fromName }} → {{ r.toName }}</span>
                  <el-tag size="small" type="info">{{ r.relation }}</el-tag>
                </div>
              </template>
            </template>

            <template v-else-if="selectedRelation">
              <div class="entity-title">
                <span>{{ selectedRelation.sourceName }} → {{ selectedRelation.targetName }}</span>
              </div>
              <div class="tags">
                <el-tag size="small">{{ selectedRelation.relation }}</el-tag>
                <el-tag size="small" :type="selectedRelation.source === 'MANUAL' ? 'success' : 'info'">
                  {{ selectedRelation.source === 'MANUAL' ? t('graphPage.sourceManual') : t('graphPage.sourceAuto') }}
                </el-tag>
              </div>
              <p class="description">
                {{ t('graphPage.weight') }} {{ selectedRelation.weight ?? '-' }} ·
                {{ t('graphPage.confidence') }} {{ Number(selectedRelation.confidence ?? 1).toFixed(2) }}
              </p>
              <p class="description">{{ selectedRelation.evidence || t('graphPage.noEvidence') }}</p>
            </template>

            <el-empty v-else :description="t('graphPage.selectNodeHint')" :image-size="54" />
          </el-card>
        </div>
      </el-tab-pane>

      <!-- 实体维护 -->
      <el-tab-pane :label="t('graphPage.entityManage')" name="entities">
        <div class="filters">
          <el-input v-model="entitySearch" clearable :placeholder="t('graphPage.searchEntity')" style="width: 240px" />
          <el-select v-model="entityTypeFilter" clearable :placeholder="t('graphPage.allTypes')" style="width: 160px">
            <el-option v-for="type in types" :key="type" :label="type" :value="type" />
          </el-select>
          <el-select v-model="entitySourceFilter" clearable :placeholder="t('graphPage.allSources')" style="width: 140px">
            <el-option :label="t('graphPage.sourceAuto')" value="AUTO" />
            <el-option :label="t('graphPage.sourceManual')" value="MANUAL" />
          </el-select>
          <span class="hint">{{ t('graphPage.manageHint') }}</span>
        </div>
        <el-table :data="filteredEntityRows" size="small" stripe max-height="620">
          <el-table-column prop="name" :label="t('graphPage.entityName')" min-width="160">
            <template #default="{ row }">
              <el-link type="primary" @click="openEntityDetail(row)">{{ row.name }}</el-link>
            </template>
          </el-table-column>
          <el-table-column :label="t('graphPage.entityType')" width="120">
            <template #default="{ row }">
              <el-tag size="small">{{ row.entityType || t('graphPage.otherType') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('graphPage.description')" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('graphPage.source')" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.source === 'MANUAL' ? 'success' : 'info'">
                {{ row.source === 'MANUAL' ? t('graphPage.sourceManual') : t('graphPage.sourceAuto') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('graphPage.confidence')" width="90">
            <template #default="{ row }">{{ Number(row.confidence ?? 1).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column :label="t('graphPage.mentionCount')" width="90">
            <template #default="{ row }">{{ mentionCount(row.id) }}</template>
          </el-table-column>
          <el-table-column v-if="canEdit" :label="t('graphPage.actions')" width="150" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEditEntity(row)">{{ t('graphPage.edit') }}</el-button>
              <el-button text type="danger" size="small" @click="removeEntity(row)">{{ t('graphPage.remove') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 关系维护 -->
      <el-tab-pane :label="t('graphPage.relationManage')" name="relations">
        <div class="filters">
          <el-input v-model="relationSearch" clearable :placeholder="t('graphPage.searchRelation')" style="width: 260px" />
          <el-button v-if="canEdit" type="primary" plain @click="openCreateRelation">{{ t('graphPage.addRelation') }}</el-button>
          <span class="hint">{{ canEdit ? t('graphPage.manageHint') : '' }}</span>
        </div>
        <el-table :data="filteredRelationRows" size="small" stripe max-height="620">
          <el-table-column :label="t('graphPage.relationFrom')" min-width="130">
            <template #default="{ row }">{{ nameOf(row.fromId) }}</template>
          </el-table-column>
          <el-table-column :label="t('graphPage.relationName')" width="150">
            <template #default="{ row }"><el-tag size="small" type="info">{{ row.relation }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('graphPage.relationTo')" min-width="130">
            <template #default="{ row }">{{ nameOf(row.toId) }}</template>
          </el-table-column>
          <el-table-column :label="t('graphPage.weight')" width="80">
            <template #default="{ row }">{{ row.weight ?? '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('graphPage.source')" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.source === 'MANUAL' ? 'success' : 'info'">
                {{ row.source === 'MANUAL' ? t('graphPage.sourceManual') : t('graphPage.sourceAuto') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('graphPage.evidence')" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.evidence || '-' }}</template>
          </el-table-column>
          <el-table-column v-if="canEdit" :label="t('graphPage.actions')" width="150" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEditRelation(row)">{{ t('graphPage.edit') }}</el-button>
              <el-button text type="danger" size="small" @click="removeRelation(row)">{{ t('graphPage.remove') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- AI 抽取：先预览，确认后提交，避免重复调用模型 -->
    <el-dialog v-model="extractVisible" :title="t('graphPage.extractTitle')" width="880px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item :label="t('graphPage.extractDocument')">
          <el-select v-model="extractDocId" clearable filterable :placeholder="t('graphPage.extractDocumentPh')" style="width: 100%">
            <el-option v-for="doc in doneDocuments" :key="doc.id" :value="doc.id"
                       :label="`${doc.fileName} (${doc.chunkCount || 0} chunks)`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('graphPage.extractText')">
          <el-input v-model="extractText" type="textarea" :rows="4"
                    :placeholder="t('graphPage.extractTextPh')" />
        </el-form-item>
      </el-form>
      <div class="hint">{{ t('graphPage.extractHint') }}</div>

      <div v-if="extractResult" class="extract-preview">
        <el-alert type="success" :closable="false"
                  :title="`${t('graphPage.extracted')} ${extractResult.entityCount || 0} ${t('graphPage.extractedMid')} ${extractResult.relationCount || 0} ${t('graphPage.extractedSuffix')}`" />
        <el-table :data="extractResult.entities || []" size="small" max-height="220" class="mt12">
          <el-table-column prop="name" :label="t('graphPage.entityName')" min-width="130" />
          <el-table-column prop="entityType" :label="t('graphPage.entityType')" width="100" />
          <el-table-column prop="description" :label="t('graphPage.description')" min-width="180" show-overflow-tooltip />
          <el-table-column prop="chunkIndex" :label="t('graphPage.chunk')" width="80" />
          <el-table-column prop="quote" :label="t('graphPage.evidence')" min-width="180" show-overflow-tooltip />
        </el-table>
        <el-table :data="extractResult.relations || []" size="small" max-height="180" class="mt12">
          <el-table-column :label="t('graphPage.relations')" min-width="220">
            <template #default="{ row }">{{ row.from }} → {{ row.to }}</template>
          </el-table-column>
          <el-table-column prop="relation" :label="t('graphPage.relationName')" width="120" />
          <el-table-column prop="chunkIndex" :label="t('graphPage.chunk')" width="80" />
          <el-table-column prop="quote" :label="t('graphPage.evidence')" min-width="200" show-overflow-tooltip />
        </el-table>
      </div>

      <template #footer>
        <el-button @click="extractVisible = false">{{ t('graphPage.close') }}</el-button>
        <el-button :loading="extracting" @click="runExtractPreview">{{ t('graphPage.preview') }}</el-button>
        <el-button type="primary" :loading="committing"
                   :disabled="!extractResult || (!extractResult.entityCount && !extractResult.relationCount)"
                   @click="commitExtract">
          {{ t('graphPage.commitPreview') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 手工实体 -->
    <el-dialog v-model="entityDialog.visible"
               :title="entityDialog.id ? t('graphPage.editEntity') : t('graphPage.addEntity')" width="520px">
      <el-form label-width="92px">
        <el-form-item :label="t('graphPage.entityName')" required>
          <el-input v-model="entityDialog.name" :placeholder="t('graphPage.entityNamePh')" />
        </el-form-item>
        <el-form-item :label="t('graphPage.entityType')">
          <el-select v-model="entityDialog.entityType" filterable allow-create default-first-option
                     :placeholder="t('graphPage.entityTypePh')" style="width: 100%">
            <el-option v-for="type in typeOptions" :key="type" :label="type" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('graphPage.description')">
          <el-input v-model="entityDialog.description" type="textarea" :rows="3"
                    :placeholder="t('graphPage.descriptionPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="entityDialog.visible = false">{{ t('graphPage.cancel') }}</el-button>
        <el-button type="primary" :loading="savingEntity" @click="saveEntity">{{ t('graphPage.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 手工关系 -->
    <el-dialog v-model="relationDialog.visible"
               :title="relationDialog.id ? t('graphPage.editRelation') : t('graphPage.addRelation')" width="560px">
      <el-form label-width="92px">
        <el-form-item :label="t('graphPage.relationFrom')" required>
          <el-select v-model="relationDialog.fromId" filterable :disabled="!!relationDialog.id" style="width: 100%">
            <el-option v-for="e in entities" :key="e.id" :value="e.id" :label="e.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('graphPage.relationTo')" required>
          <el-select v-model="relationDialog.toId" filterable :disabled="!!relationDialog.id" style="width: 100%">
            <el-option v-for="e in entities" :key="e.id" :value="e.id" :label="e.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('graphPage.relationName')" required>
          <el-input v-model="relationDialog.relation" :placeholder="t('graphPage.relationNamePh')" />
        </el-form-item>
        <el-form-item :label="t('graphPage.weight')">
          <el-input-number v-model="relationDialog.weight" :min="0.01" :max="99.99" :step="0.1" />
        </el-form-item>
        <el-form-item :label="t('graphPage.evidence')">
          <el-input v-model="relationDialog.evidence" type="textarea" :rows="2"
                    :placeholder="t('graphPage.evidencePh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="relationDialog.visible = false">{{ t('graphPage.cancel') }}</el-button>
        <el-button type="primary" :loading="savingRelation" @click="saveRelation">{{ t('graphPage.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'
import KnowledgeGraphChart from '../components/KnowledgeGraphChart.vue'

const route = useRoute()
const { t } = useI18n()
const kbId = Number(route.params.id)

const graph = reactive<any>({ entities: [], relations: [], mentionCounts: {}, entityCount: 0, relationCount: 0, canEdit: false })
const canEdit = computed(() => !!graph.canEdit)
const entities = computed<any[]>(() => graph.entities || [])
const relations = computed<any[]>(() => graph.relations || [])
const entityMap = computed(() => new Map<number, any>(entities.value.map(e => [Number(e.id), e])))
const types = computed<string[]>(() => {
  const set = new Set<string>()
  for (const e of entities.value) set.add(e.entityType || t('graphPage.otherType'))
  return Array.from(set).sort()
})

const typeOptions = computed(() => {
  const base = ['人物', '组织', '产品', '政策', '流程', '概念', '指标', '技术', '其他']
  return Array.from(new Set([...base, ...types.value]))
})

const tab = ref('graph')
const graphSearch = ref('')
const graphType = ref('')
const entitySearch = ref('')
const entityTypeFilter = ref('')
const entitySourceFilter = ref('')
const relationSearch = ref('')
const chartKey = ref(0)

const palette = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#9b59b6', '#16a085', '#d35400']
const categoryList = computed(() => types.value.map((name, i) => ({
  name,
  itemStyle: { color: palette[i % palette.length] }
})))

const filteredEntities = computed(() => {
  const keyword = graphSearch.value.trim().toLowerCase()
  return entities.value.filter(e => {
    if (graphType.value && (e.entityType || t('graphPage.otherType')) !== graphType.value) return false
    if (!keyword) return true
    return String(e.name || '').toLowerCase().includes(keyword)
      || String(e.description || '').toLowerCase().includes(keyword)
  })
})

const filteredRelationRows = computed(() => {
  const keyword = relationSearch.value.trim().toLowerCase()
  if (!keyword) return relations.value
  return relations.value.filter(r => {
    const text = `${nameOf(r.fromId)} ${nameOf(r.toId)} ${r.relation || ''} ${r.evidence || ''}`.toLowerCase()
    return text.includes(keyword)
  })
})

const filteredEntityRows = computed(() => {
  const keyword = entitySearch.value.trim().toLowerCase()
  return entities.value.filter(e => {
    if (entityTypeFilter.value && (e.entityType || t('graphPage.otherType')) !== entityTypeFilter.value) return false
    if (entitySourceFilter.value && (e.source || 'AUTO') !== entitySourceFilter.value) return false
    if (!keyword) return true
    return `${e.name || ''} ${e.description || ''}`.toLowerCase().includes(keyword)
  })
})

const degree = computed<Record<number, number>>(() => {
  const d: Record<number, number> = {}
  for (const r of relations.value) {
    d[r.fromId] = (d[r.fromId] || 0) + 1
    d[r.toId] = (d[r.toId] || 0) + 1
  }
  return d
})

function categoryIndex(type: string) {
  const idx = types.value.indexOf(type || t('graphPage.otherType'))
  return idx < 0 ? 0 : idx
}

const chartNodes = computed(() => filteredEntities.value.map(e => ({
  id: String(e.id),
  name: e.name,
  category: categoryIndex(e.entityType || t('graphPage.otherType')),
  symbolSize: Math.max(18, Math.min(52, 18 + (degree.value[e.id] || 0) * 4)),
  value: e
})))

const chartLinks = computed(() => {
  const ids = new Set(filteredEntities.value.map(e => Number(e.id)))
  return relations.value
    .filter(r => ids.has(Number(r.fromId)) && ids.has(Number(r.toId)))
    .map(r => ({
      source: String(r.fromId),
      target: String(r.toId),
      value: r.relation,
      valueObj: { ...r, sourceName: nameOf(r.fromId), targetName: nameOf(r.toId) }
    }))
})

function nameOf(id: number) {
  return entityMap.value.get(Number(id))?.name || `#${id}`
}

function mentionCount(id: number) {
  return graph.mentionCounts?.[id] ?? graph.mentionCounts?.[String(id)] ?? 0
}

async function load() {
  try {
    const data = await http.get(`/knowledge-base/${kbId}/graph`) as any
    Object.assign(graph, data || {})
    if (selectedEntity.value) {
      const fresh = entities.value.find(e => Number(e.id) === Number(selectedEntity.value.id))
      if (fresh) selectedEntity.value = fresh
      else closeDetail()
    } else if (selectedRelation.value) {
      const fresh = relations.value.find(r => Number(r.id) === Number(selectedRelation.value.id))
      if (fresh) selectedRelation.value = fresh
      else closeDetail()
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.loadFail'))
  }
}

// ---------------- 全库构建 ----------------
const job = ref<any>({})
const building = ref(false)
let jobTimer: number | null = null
const activeJob = computed(() => job.value?.status === 'PENDING' || job.value?.status === 'RUNNING')
const jobPercent = computed(() => {
  if (job.value?.status === 'DONE') return 100
  const total = Number(job.value?.totalDocuments || 0)
  if (!total) return job.value?.status === 'RUNNING' ? 30 : 0
  return Math.min(99, Math.round((Number(job.value?.processedDocuments || 0) / total) * 100))
})

async function loadJob() {
  try {
    const data = await http.get(`/knowledge-base/${kbId}/graph/jobs/latest`) as any
    job.value = data || {}
    if (activeJob.value) startPolling()
  } catch (e) {
    // 新库尚无任务时接口返回空对象；忽略
  }
}

function startPolling() {
  if (jobTimer != null) return
  jobTimer = window.setInterval(pollJob, 1500)
}

function stopPolling() {
  if (jobTimer != null) {
    window.clearInterval(jobTimer)
    jobTimer = null
  }
}

async function pollJob() {
  try {
    const data = await http.get(`/knowledge-base/${kbId}/graph/jobs/latest`) as any
    job.value = data || {}
    if (!activeJob.value) {
      stopPolling()
      if (job.value.status === 'DONE') {
        ElMessage.success(t('graphPage.buildDone'))
        await load()
      } else if (job.value.status === 'FAILED') {
        ElMessage.error(job.value.error || t('graphPage.buildFailed'))
      }
    }
  } catch (e) {
    stopPolling()
  }
}

async function buildGraph() {
  try {
    await ElMessageBox.confirm(t('graphPage.buildConfirm'), t('graphPage.rebuild'), { type: 'warning' })
  } catch {
    return
  }
  building.value = true
  try {
    const data = await http.post(`/knowledge-base/${kbId}/graph/build`) as any
    job.value = data || {}
    ElMessage.success(t('graphPage.buildStarted'))
    startPolling()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.buildFailed'))
  } finally {
    building.value = false
  }
}

// ---------------- 实体详情 ----------------
const selectedEntity = ref<any>(null)
const selectedRelation = ref<any>(null)
const entityDetail = ref<any>(null)
const detailLoading = ref(false)

async function onNodeClick(entity: any) {
  if (!entity?.id) return
  selectedRelation.value = null
  selectedEntity.value = entity
  entityDetail.value = null
  detailLoading.value = true
  try {
    entityDetail.value = await http.get(`/knowledge-base/${kbId}/graph/entities/${entity.id}`) as any
  } catch (e) {
    entityDetail.value = null
  } finally {
    detailLoading.value = false
  }
}

function openEntityDetail(entity: any) {
  tab.value = 'graph'
  onNodeClick(entity)
}

function onEdgeClick(relation: any) {
  if (!relation) return
  selectedEntity.value = null
  entityDetail.value = null
  selectedRelation.value = relation
}

function closeDetail() {
  selectedEntity.value = null
  selectedRelation.value = null
  entityDetail.value = null
}

// ---------------- AI 抽取 ----------------
const extractVisible = ref(false)
const extracting = ref(false)
const committing = ref(false)
const extractDocId = ref<number | null>(null)
const extractText = ref('')
const extractResult = ref<any>(null)
const documents = ref<any[]>([])
const doneDocuments = computed(() => documents.value.filter(d => d.status === 'DONE'))

async function loadDocuments() {
  try {
    documents.value = await http.get(`/knowledge-base/${kbId}/documents`) as any || []
  } catch (e) {
    documents.value = []
  }
}

function openExtract() {
  extractVisible.value = true
  extractResult.value = null
  extractText.value = ''
  extractDocId.value = doneDocuments.value[0]?.id ?? null
}

// 来源变化后旧预览不再对应当前输入，清空避免误提交
watch([extractDocId, extractText], () => {
  extractResult.value = null
})

async function runExtractPreview() {
  extracting.value = true
  extractResult.value = null
  try {
    extractResult.value = await http.post(`/knowledge-base/${kbId}/graph/extract`, {
      documentId: extractDocId.value,
      text: extractText.value.trim() || null,
      commit: false
    }, { timeout: 180000 }) as any
    if (!extractResult.value?.entityCount && !extractResult.value?.relationCount) {
      ElMessage.warning(t('graphPage.noExtract'))
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.extractFail'))
  } finally {
    extracting.value = false
  }
}

async function commitExtract() {
  if (!extractResult.value) return
  committing.value = true
  try {
    await http.post(`/knowledge-base/${kbId}/graph/commit`, {
      documentId: extractDocId.value,
      entities: extractResult.value.entities || [],
      relations: extractResult.value.relations || []
    })
    ElMessage.success(t('graphPage.extractDone'))
    extractVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.saveFail'))
  } finally {
    committing.value = false
  }
}

// ---------------- 手工实体 ----------------
const entityDialog = reactive<any>({ visible: false, id: null, name: '', entityType: '', description: '' })
const savingEntity = ref(false)

function openCreateEntity() {
  Object.assign(entityDialog, { visible: true, id: null, name: '', entityType: '', description: '' })
}

function openEditEntity(row: any) {
  Object.assign(entityDialog, {
    visible: true, id: row.id, name: row.name,
    entityType: row.entityType || '', description: row.description || ''
  })
}

async function saveEntity() {
  if (!entityDialog.name?.trim()) {
    ElMessage.warning(t('graphPage.nameRequired'))
    return
  }
  savingEntity.value = true
  try {
    const body = {
      name: entityDialog.name.trim(),
      entityType: entityDialog.entityType,
      description: entityDialog.description
    }
    if (entityDialog.id) {
      await http.put(`/knowledge-base/${kbId}/graph/entities/${entityDialog.id}`, body)
    } else {
      await http.post(`/knowledge-base/${kbId}/graph/entities`, body)
    }
    ElMessage.success(t('graphPage.saved'))
    entityDialog.visible = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.saveFail'))
  } finally {
    savingEntity.value = false
  }
}

async function removeEntity(row: any) {
  try {
    await ElMessageBox.confirm(`${t('graphPage.remove')} ${row.name}?`, t('graphPage.remove'), { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/knowledge-base/${kbId}/graph/entities/${row.id}`)
    ElMessage.success(t('graphPage.deleted'))
    if (selectedEntity.value?.id === row.id) closeDetail()
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.saveFail'))
  }
}

// ---------------- 手工关系 ----------------
const relationDialog = reactive<any>({
  visible: false, id: null, fromId: null, toId: null, relation: '', weight: 1, evidence: ''
})
const savingRelation = ref(false)

function openCreateRelation() {
  Object.assign(relationDialog, {
    visible: true, id: null,
    fromId: entities.value[0]?.id ?? null,
    toId: entities.value[1]?.id ?? null,
    relation: '', weight: 1, evidence: ''
  })
}

function openEditRelation(row: any) {
  Object.assign(relationDialog, {
    visible: true, id: row.id, fromId: row.fromId, toId: row.toId,
    relation: row.relation, weight: Number(row.weight ?? 1), evidence: row.evidence || ''
  })
}

async function saveRelation() {
  if (!relationDialog.fromId || !relationDialog.toId || !relationDialog.relation?.trim()) {
    ElMessage.warning(t('graphPage.relationRequired'))
    return
  }
  savingRelation.value = true
  try {
    if (relationDialog.id) {
      await http.put(`/knowledge-base/${kbId}/graph/relations/${relationDialog.id}`, {
        relation: relationDialog.relation.trim(),
        weight: relationDialog.weight,
        evidence: relationDialog.evidence
      })
    } else {
      await http.post(`/knowledge-base/${kbId}/graph/relations`, {
        fromId: relationDialog.fromId,
        toId: relationDialog.toId,
        relation: relationDialog.relation.trim(),
        weight: relationDialog.weight,
        evidence: relationDialog.evidence
      })
    }
    ElMessage.success(t('graphPage.saved'))
    relationDialog.visible = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.saveFail'))
  } finally {
    savingRelation.value = false
  }
}

async function removeRelation(row: any) {
  try {
    await ElMessageBox.confirm(`${t('graphPage.remove')} ${row.relation}?`, t('graphPage.remove'), { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/knowledge-base/${kbId}/graph/relations/${row.id}`)
    ElMessage.success(t('graphPage.deleted'))
    if (selectedRelation.value?.id === row.id) closeDetail()
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.saveFail'))
  }
}

async function loadAll() {
  await Promise.all([load(), loadDocuments(), loadJob()])
  // 文档被删除后避免抽取对话框仍指向不存在的文档
  if (extractDocId.value != null
      && !documents.value.some(d => Number(d.id) === Number(extractDocId.value))) {
    extractDocId.value = doneDocuments.value[0]?.id ?? null
  }
}

onMounted(loadAll)

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.title { font-weight: 600; font-size: 15px; }
.spacer { flex: 1; }
.job-alert { margin-bottom: 10px; }
.job-row { display: flex; align-items: center; gap: 12px; }
.job-text { font-size: 13px; color: #303133; white-space: nowrap; }
.job-meta { margin-top: 6px; font-size: 12px; color: #909399; }
.graph-tabs { margin-top: 4px; }
.filters { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }
.auto-hint { margin-left: auto; }
.graph-layout { display: flex; gap: 12px; align-items: stretch; }
.graph-main { flex: 1; min-width: 0; }
.detail-panel { width: 310px; flex: 0 0 310px; }
.detail-head { display: flex; align-items: center; justify-content: space-between; }
.entity-title { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 15px; word-break: break-all; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0; }
.description { color: #606266; font-size: 13px; line-height: 1.6; word-break: break-word; }
.mention { padding: 8px 0; border-bottom: 1px dashed #ebeef5; }
.mention:last-child { border-bottom: none; }
.mention-meta { display: flex; justify-content: space-between; color: #909399; font-size: 11px; gap: 8px; }
.quote { margin-top: 4px; color: #303133; font-size: 12px; line-height: 1.5; }
.relation-line { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 4px 0; font-size: 12px; }
.extract-preview { margin-top: 12px; }
.mt12 { margin-top: 12px; }
@media (max-width: 980px) {
  .graph-layout { flex-direction: column; }
  .detail-panel { width: auto; flex: none; }
}
</style>
