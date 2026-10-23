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
            </div>
            <div class="meta">嵌入模型 {{ kb.embeddingModel }} · {{ formatDate(kb.createdAt) }}</div>
          </div>
        </div>
        <div class="desc">{{ kb.description || t('knowledgeList.noDesc') }}</div>
        <div class="foot">
          <span>文档 {{ kb.documentCount ?? 0 }} · 分段 {{ kb.chunkCount ?? 0 }}</span>
          <span>创建人 {{ kb.creatorName || '-' }}</span>
        </div>
        <div v-if="kb.canEdit" class="actions" @click.stop>
          <el-button size="small" text @click="openEdit(kb)">{{ t('knowledgeList.edit') }}</el-button>
          <el-button size="small" text type="danger" @click="removeKb(kb)">{{ t('knowledgeList.delete') }}</el-button>
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
                       :label="`${m.name}（${m.dimensions} 维）`" />
          </el-select>
          <span v-if="editForm.documentCount > 0" class="hint">{{ t('knowledgeList.modelLocked') }}</span>
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
                       :label="`${m.name}（${m.dimensions} 维）`" />
          </el-select>
          <span class="hint">{{ t('knowledgeList.modelHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('knowledgeList.visibility')">
          <el-radio-group v-model="form.visibility">
            <el-radio value="TENANT">所有团队成员</el-radio>
            <el-radio value="PRIVATE">只有我</el-radio>
          </el-radio-group>
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
const form = reactive({ name: '', description: '', visibility: 'TENANT', embeddingModel: '' })
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = reactive({ id: 0, name: '', description: '', embeddingModel: '', documentCount: 0 })

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
  editVisible.value = true
}

async function saveEdit() {
  if (!editForm.name.trim()) { ElMessage.warning('名称必填'); return }
  editSaving.value = true
  try {
    await http.put(`/knowledge-base/${editForm.id}`, {
      name: editForm.name.trim(), description: editForm.description,
      embeddingModel: editForm.embeddingModel
    })
    ElMessage.success('已保存')
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
      ? `该知识库被 ${agents.length} 个 Agent 引用（${agents.join('、')}），删除后这些 Agent 将无法再检索它。确认删除？`
      : `确认删除知识库「${kb.name}」？文档、分段与向量数据将一并删除，不可恢复。`
    await ElMessageBox.confirm(msg, '删除知识库', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
    await http.delete(`/knowledge-base/${kb.id}`, { params: { force: agents.length > 0 } })
    ElMessage.success('已删除')
    if (kbs.value.length === 1 && page.value > 1) page.value--
    await load()
  } catch {
    // 用户取消或请求失败（拦截器已提示）
  }
}

async function create() {
  if (!form.name.trim()) { ElMessage.warning('名称必填'); return }
  creating.value = true
  try {
    await http.post('/knowledge-base', {
      name: form.name, description: form.description, visibility: form.visibility,
      embeddingModel: form.embeddingModel
    })
    ElMessage.success('已创建')
    createVisible.value = false
    form.name = ''
    form.description = ''
    form.visibility = 'TENANT'
    form.embeddingModel = embeddingModels.value.find((m: any) => m.default)?.name || ''
    await load()
  } finally {
    creating.value = false
  }
}

function visText(v?: string) {
  return v === 'PRIVATE' ? '只有我' : v === 'PARTIAL' ? '部分成员' : '所有团队成员'
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
.pager { display: flex; justify-content: center; margin-top: 18px; }
</style>