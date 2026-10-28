<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.knowledge') }}</span>
      <div class="filters">
        <el-input v-model="keyword" :placeholder="t('knowledgeList.searchPh')" clearable class="kw"
                  @keydown.enter="search" @clear="search" />
        <el-select v-model="visibility" :placeholder="t('knowledgeList.visibilityPh')" clearable class="vis" @change="search">
          <el-option :label="t('knowledgeList.private')" value="PRIVATE" />
          <el-option :label="t('knowledgeList.tenant')" value="TENANT" />
          <el-option :label="t('knowledgeList.partial')" value="PARTIAL" />
        </el-select>
        <el-button @click="search">{{ t('knowledgeList.search') }}</el-button>
        <el-button v-if="canCreate" type="primary" @click="createVisible = true">{{ t('knowledgeList.create') }}</el-button>
      </div>
    </div>

    <el-empty v-if="!loading && kbs.length === 0" :description="t('knowledgeList.empty')" />

    <div v-else class="grid">
      <el-card v-for="kb in kbs" :key="kb.id" class="card" shadow="hover" @click="$router.push(`/knowledge/${kb.id}`)">
        <div class="card-head">
          <el-avatar :size="40" class="avatar">{{ kb.name.slice(0, 1) }}</el-avatar>
          <div class="head-main">
            <div class="name-row">
              <span class="name">{{ kb.name }}</span>
              <el-tag size="small" :type="visTag(kb.visibility)">{{ visText(kb.visibility) }}</el-tag>
              <el-tag v-if="kb.graphEnabled === false" size="small" type="info">{{ t('knowledgeList.graphOff') }}</el-tag>
            </div>
            <div class="meta">{{ t('knowledgeList.embedding') }} {{ kb.embeddingModel }} · {{ formatDate(kb.createdAt) }}</div>
            <div class="meta">{{ t('knowledgeList.searchBackend') }} {{ kb.searchBackend || 'MYSQL' }} · {{ t('knowledgeList.vectorBackend') }} {{ kb.vectorBackend || 'PGVECTOR' }}</div>
          </div>
        </div>
        <div class="desc">{{ kb.description || t('knowledgeList.noDesc') }}</div>
        <div class="foot">
          <span>{{ t('knowledgeList.docs') }} {{ kb.documentCount ?? 0 }} · {{ t('knowledgeList.chunks') }} {{ kb.chunkCount ?? 0 }}</span>
          <span>{{ t('knowledgeList.creator') }} {{ kb.creatorName || '-' }}</span>
        </div>
        <div class="actions" @click.stop>
          <el-button size="small" text type="primary" v-if="kb.graphEnabled !== false"
                     @click="$router.push(`/knowledge/${kb.id}/graph`)">{{ t('graphPage.title') }}</el-button>
          <template v-if="kb.canEdit">
            <el-button size="small" text @click="openEdit(kb)">{{ t('knowledgeList.edit') }}</el-button>
            <el-button size="small" text type="danger" @click="removeKb(kb)">{{ t('knowledgeList.delete') }}</el-button>
          </template>
        </div>
      </el-card>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination layout="prev, pager, next, total" :total="total"
                     :page-size="pageSize" :current-page="page" @current-change="onPage" />
    </div>

    <el-dialog v-model="editVisible" :title="t('knowledgeList.editTitle')" width="480px">
      <el-form label-width="70px">
        <el-form-item :label="t('knowledgeList.name')" required>
          <el-input v-model="editForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.desc')">
          <el-input v-model="editForm.description" type="textarea" :rows="2" maxlength="512" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.model')">
          <el-select v-model="editForm.embeddingModel" :disabled="editForm.documentCount > 0" style="width: 260px">
            <el-option v-for="m in embeddingModels" :key="m.name" :value="m.name"
                       :label="`${m.name} (${m.dimensions}${t('knowledgeList.dimSuffix')})`" />
          </el-select>
          <span v-if="editForm.documentCount > 0" class="hint">{{ t('knowledgeList.modelLocked') }}</span>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.searchBackend')">
          <el-select v-model="editForm.searchBackend" style="width: 260px">
            <el-option v-for="b in searchBackends" :key="b.value" :value="b.value"
                       :label="b.label + (b.available ? '' : t('knowledgeList.backendUnavailable'))"
                       :disabled="!b.available" />
          </el-select>
          <span class="hint">{{ t('knowledgeList.searchBackendHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.vectorBackend')">
          <el-select v-model="editForm.vectorBackend" :disabled="editForm.documentCount > 0" style="width: 260px">
            <el-option v-for="b in vectorBackends" :key="b.value" :value="b.value"
                       :label="b.label + (b.available ? '' : t('knowledgeList.backendUnavailable'))"
                       :disabled="!b.available" />
          </el-select>
          <span class="hint">{{ editForm.documentCount > 0 ? t('knowledgeList.vectorBackendLocked') : t('knowledgeList.vectorBackendHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.defaultTopK')">
          <el-input-number v-model="editForm.defaultTopK" :min="1" :max="50" />
          <span class="hint">{{ t('knowledgeList.maxTopK') }} </span>
          <el-input-number v-model="editForm.maxTopK" :min="1" :max="50" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.defaultRetrievalMode')">
          <el-select v-model="editForm.defaultRetrievalMode" style="width: 180px">
            <el-option :label="t('knowledgeList.modeHybrid')" value="hybrid" />
            <el-option :label="t('knowledgeList.modeVector')" value="vector" />
            <el-option :label="t('knowledgeList.modeKeyword')" value="keyword" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.defaultScoreThreshold')">
          <el-input-number v-model="editForm.defaultScoreThreshold" :min="0" :max="1" :step="0.05" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.defaultKeywordWeight')">
          <el-input-number v-model="editForm.defaultKeywordWeight" :min="0" :max="1" :step="0.05" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.graphEnabled')">
          <el-switch v-model="editForm.graphEnabled" />
          <span class="hint">{{ t('knowledgeList.graphHint') }}</span>
          <div class="hint">{{ t('knowledgeList.graphRebuildHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('knowledgeList.cancel') }}</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">{{ t('knowledgeList.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" :title="t('knowledgeList.createTitle')" width="480px">
      <el-form label-width="70px">
        <el-form-item :label="t('knowledgeList.name')" required>
          <el-input v-model="form.name" :placeholder="t('knowledgeList.namePh')" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.desc')">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.model')">
          <el-select v-model="form.embeddingModel" style="width: 260px">
            <el-option v-for="m in embeddingModels" :key="m.name" :value="m.name"
                       :label="`${m.name} (${m.dimensions}${t('knowledgeList.dimSuffix')})`" />
          </el-select>
          <span class="hint">{{ t('knowledgeList.modelHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.searchBackend')">
          <el-select v-model="form.searchBackend" style="width: 260px">
            <el-option v-for="b in searchBackends" :key="b.value" :value="b.value"
                       :label="b.label + (b.available ? '' : t('knowledgeList.backendUnavailable'))"
                       :disabled="!b.available" />
          </el-select>
          <span class="hint">{{ t('knowledgeList.searchBackendHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.vectorBackend')">
          <el-select v-model="form.vectorBackend" style="width: 260px">
            <el-option v-for="b in vectorBackends" :key="b.value" :value="b.value"
                       :label="b.label + (b.available ? '' : t('knowledgeList.backendUnavailable'))"
                       :disabled="!b.available" />
          </el-select>
          <span class="hint">{{ t('knowledgeList.vectorBackendHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.visibility')">
          <el-radio-group v-model="form.visibility">
            <el-radio value="TENANT">{{ t('knowledgeList.tenantRadio') }}</el-radio>
            <el-radio value="PRIVATE">{{ t('knowledgeList.privateRadio') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.defaultTopK')">
          <el-input-number v-model="form.defaultTopK" :min="1" :max="50" />
          <span class="hint">{{ t('knowledgeList.maxTopK') }} </span>
          <el-input-number v-model="form.maxTopK" :min="1" :max="50" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.defaultRetrievalMode')">
          <el-select v-model="form.defaultRetrievalMode" style="width: 180px">
            <el-option :label="t('knowledgeList.modeHybrid')" value="hybrid" />
            <el-option :label="t('knowledgeList.modeVector')" value="vector" />
            <el-option :label="t('knowledgeList.modeKeyword')" value="keyword" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.defaultScoreThreshold')">
          <el-input-number v-model="form.defaultScoreThreshold" :min="0" :max="1" :step="0.05" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.defaultKeywordWeight')">
          <el-input-number v-model="form.defaultKeywordWeight" :min="0" :max="1" :step="0.05" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.graphEnabled')">
          <el-switch v-model="form.graphEnabled" />
          <span class="hint">{{ t('knowledgeList.graphHint') }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('knowledgeList.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="create">{{ t('knowledgeList.createBtn') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const { t } = useI18n()

interface Kb {
  id: number; name: string; description: string; embeddingModel: string; createdAt: string
  visibility: string; documentCount: number; chunkCount: number; creatorName: string; canEdit: boolean
  graphEnabled?: boolean; parseConfig?: Record<string, any>
  searchBackend?: string; vectorBackend?: string; defaultTopK?: number; maxTopK?: number
  defaultRetrievalMode?: string; defaultScoreThreshold?: number; defaultKeywordWeight?: number
}

const userStore = useUserStore()
const canCreate = computed(() =>
  !!userStore.userInfo?.isPlatformAdmin || (userStore.userInfo?.permissions || []).includes('kb:create'))

const kbs = ref<Kb[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const keyword = ref('')
const visibility = ref('')
const loading = ref(false)
const createVisible = ref(false)
const creating = ref(false)
const embeddingModels = ref<any[]>([])
const searchBackends = ref<any[]>([{ value: 'MYSQL', label: 'MySQL', available: true }])
const vectorBackends = ref<any[]>([{ value: 'PGVECTOR', label: 'pgvector', available: true }])
const form = reactive({ name: '', description: '', visibility: 'TENANT', embeddingModel: '', graphEnabled: false,
  searchBackend: 'MYSQL', vectorBackend: 'PGVECTOR', defaultTopK: 5, maxTopK: 20,
  defaultRetrievalMode: 'hybrid', defaultScoreThreshold: 0.5, defaultKeywordWeight: 0.35 })
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = reactive({
  id: 0, name: '', description: '', embeddingModel: '', documentCount: 0, graphEnabled: false,
  searchBackend: 'MYSQL', vectorBackend: 'PGVECTOR', defaultTopK: 5, maxTopK: 20,
  defaultRetrievalMode: 'hybrid', defaultScoreThreshold: 0.5, defaultKeywordWeight: 0.35,
  parseConfig: {} as Record<string, any>
})

async function load() {
  loading.value = true
  try {
    const data = await http.get('/knowledge-base', {
      params: { keyword: keyword.value || undefined, visibility: visibility.value || undefined,
        page: page.value, pageSize }
    }) as any
    kbs.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}
function onPage(p: number) {
  page.value = p
  load()
}

function openEdit(kb: Kb) {
  editForm.id = kb.id
  editForm.name = kb.name
  editForm.description = kb.description || ''
  editForm.embeddingModel = kb.embeddingModel
  editForm.documentCount = kb.documentCount || 0
  editForm.graphEnabled = kb.graphEnabled !== false
  editForm.searchBackend = kb.searchBackend || 'MYSQL'
  editForm.vectorBackend = kb.vectorBackend || 'PGVECTOR'
  editForm.defaultTopK = kb.defaultTopK ?? 5
  editForm.maxTopK = kb.maxTopK ?? 20
  editForm.defaultRetrievalMode = kb.defaultRetrievalMode || 'hybrid'
  editForm.defaultScoreThreshold = kb.defaultScoreThreshold ?? 0.5
  editForm.defaultKeywordWeight = kb.defaultKeywordWeight ?? 0.35
  editForm.parseConfig = kb.parseConfig || {}
  editVisible.value = true
}

async function saveEdit() {
  if (!editForm.name.trim()) { ElMessage.warning(t('knowledgeList.nameRequired')); return }
  editSaving.value = true
  try {
    await http.put(`/knowledge-base/${editForm.id}`, {
      name: editForm.name.trim(), description: editForm.description,
      embeddingModel: editForm.embeddingModel, graphEnabled: editForm.graphEnabled,
      searchBackend: editForm.searchBackend, vectorBackend: editForm.vectorBackend,
      defaultTopK: editForm.defaultTopK, maxTopK: editForm.maxTopK,
      defaultRetrievalMode: editForm.defaultRetrievalMode,
      defaultScoreThreshold: editForm.defaultScoreThreshold,
      defaultKeywordWeight: editForm.defaultKeywordWeight,
      parseConfig: editForm.parseConfig
    })
    ElMessage.success(t('knowledgeList.savedMsg'))
    editVisible.value = false
    await load()
  } finally {
    editSaving.value = false
  }
}

/** 删除：先查 Agent 引用，二次确认；被引用时携带 force（需求 3.6.3(6)）。 */
async function removeKb(kb: Kb) {
  try {
    const refs = await http.get(`/knowledge-base/${kb.id}/references`) as any
    const agents: string[] = refs?.agents || []
    const msg = agents.length
      ? `${t('knowledgeList.deleteRefsPrefix')}${agents.length}${t('knowledgeList.deleteRefsMid')}${agents.join(', ')}${t('knowledgeList.deleteRefsSuffix')}`
      : `${t('knowledgeList.deletePrefix')}${kb.name}${t('knowledgeList.deleteSuffix')}`
    await ElMessageBox.confirm(msg, t('knowledgeList.deleteTitle'), {
      type: 'warning', confirmButtonText: t('knowledgeList.deleteBtn'), cancelButtonText: t('knowledgeList.cancel')
    })
    await http.delete(`/knowledge-base/${kb.id}`, { params: { force: agents.length > 0 } })
    ElMessage.success(t('knowledgeList.deletedMsg'))
    if (kbs.value.length === 1 && page.value > 1) page.value--
    await load()
  } catch {
    // 用户取消或请求失败（拦截器已提示）
  }
}

async function create() {
  if (!form.name.trim()) { ElMessage.warning(t('knowledgeList.nameRequired')); return }
  creating.value = true
  try {
    await http.post('/knowledge-base', {
      name: form.name, description: form.description, visibility: form.visibility,
      embeddingModel: form.embeddingModel, graphEnabled: form.graphEnabled,
      searchBackend: form.searchBackend, vectorBackend: form.vectorBackend,
      defaultTopK: form.defaultTopK, maxTopK: form.maxTopK,
      defaultRetrievalMode: form.defaultRetrievalMode,
      defaultScoreThreshold: form.defaultScoreThreshold,
      defaultKeywordWeight: form.defaultKeywordWeight
    })
    ElMessage.success(t('knowledgeList.createdMsg'))
    createVisible.value = false
    form.name = ''
    form.description = ''
    form.visibility = 'TENANT'
    form.embeddingModel = embeddingModels.value.find((m: any) => m.default)?.name || ''
    form.graphEnabled = false
    form.searchBackend = 'MYSQL'
    form.defaultRetrievalMode = 'hybrid'
    form.defaultScoreThreshold = 0.5
    form.defaultKeywordWeight = 0.35
    form.vectorBackend = 'PGVECTOR'
    await load()
  } finally {
    creating.value = false
  }
}

function visText(v?: string) {
  return v === 'PRIVATE' ? t('knowledgeList.visPrivate') : v === 'PARTIAL' ? t('knowledgeList.visPartial') : t('knowledgeList.visTenant')
}
function visTag(v?: string) {
  return v === 'PRIVATE' ? 'info' : v === 'PARTIAL' ? 'warning' : 'success'
}
function formatDate(t?: string) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : ''
}

onMounted(async () => {
  try {
    embeddingModels.value = await http.get('/knowledge-base/embedding-models') as any
    form.embeddingModel = embeddingModels.value.find((m: any) => m.default)?.name || ''
    const backends = await http.get('/knowledge-base/retrieval-backends', { _silent: true } as any) as any
    searchBackends.value = backends.search || []
    vectorBackends.value = backends.vector || []
  } catch {
    // 选项加载失败时后端仍会使用默认模型
  }
  await load()
})
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 16px; font-weight: 600; }
.filters { display: flex; align-items: center; gap: 8px; }
.kw { width: 200px; }
.vis { width: 140px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.card { cursor: pointer; }
.card-head { display: flex; gap: 12px; align-items: center; }
.head-main { min-width: 0; }
.name-row { display: flex; align-items: center; gap: 8px; }
.avatar { background: #67c23a; color: #fff; font-weight: 600; flex-shrink: 0; }
.name { font-weight: 600; font-size: 15px; }
.meta { font-size: 12px; color: #909399; margin-top: 2px; }
.desc { color: #909399; font-size: 13px; margin-top: 10px; min-height: 18px; }
.foot { display: flex; justify-content: space-between; color: #909399; font-size: 12px; margin-top: 10px;
        border-top: 1px dashed #ebeef5; padding-top: 8px; }
.actions { display: flex; justify-content: flex-end; gap: 4px; margin-top: 6px; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.pager { display: flex; justify-content: center; margin-top: 18px; }
</style>