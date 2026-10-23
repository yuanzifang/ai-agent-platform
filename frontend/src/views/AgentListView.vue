<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="keyword" :placeholder="t('agentList.search')" clearable style="width: 240px"
                @keyup.enter="load" @clear="load" />
      <el-button @click="$router.push('/templates')">{{ t('agentList.fromTemplate') }}</el-button>
      <el-button type="primary" @click="openCreate">{{ t('agentList.create') }}</el-button>
    </div>

    <div v-if="loading" class="loading"><el-skeleton :rows="3" animated /></div>

    <el-empty v-else-if="agents.length === 0" :description="t('agentList.empty')" />

    <div v-else class="grid">
      <el-card v-for="a in agents" :key="a.id" class="card" shadow="hover" @click="enter(a)">
        <div class="card-head">
          <el-avatar :size="40" class="avatar">{{ initials(a.name) }}</el-avatar>
          <div class="title-wrap">
            <div class="name">{{ a.name || t('agentList.unnamed') }}</div>
            <el-tag size="small" :type="a.status === 'PUBLISHED' ? 'success' : 'info'">
              {{ a.status === 'PUBLISHED' ? t('agentList.published') : t('agentList.draft') }}
            </el-tag>
            <el-tag v-if="a.status === 'PUBLISHED' && a.capability?.incomplete" size="small" type="warning">
              {{ t('agentList.incomplete') }}
            </el-tag>
          </div>
        </div>
        <div class="desc">{{ a.description || t('agentList.noDesc') }}</div>
        <div class="card-foot" @click.stop>
          <el-button size="small" @click="edit(a)">{{ t('agentList.edit') }}</el-button>
          <el-button size="small" @click="observe(a)">{{ t('agentList.observe') }}</el-button>
          <el-button size="small" @click="metrics(a)">{{ t('agentList.metrics') }}</el-button>
          <el-button size="small" :disabled="a.status !== 'PUBLISHED'" @click="enter(a)">{{ t('agentList.chat') }}</el-button>
          <el-button v-if="a.status !== 'PUBLISHED'" size="small" type="success" plain @click="publish(a)">{{ t('agentList.publish') }}</el-button>
          <el-button size="small" type="danger" plain @click="remove(a)">{{ t('agentList.delete') }}</el-button>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="createVisible" :title="t('agentList.createTitle')" width="560px">
      <el-form label-width="90px">
        <el-form-item :label="t('agentList.name')" required>
          <el-input v-model="form.name" :placeholder="t('agentList.namePh')" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('agentList.desc')">
          <el-input v-model="form.description" type="textarea" :rows="2" :placeholder="t('agentList.descPh')" />
        </el-form-item>
        <el-form-item :label="t('agentList.model')" required>
          <el-select v-model="form.modelName" :placeholder="t('agentList.selectModel')" style="width: 100%">
            <el-option v-for="m in models" :key="m.modelName" :value="m.modelName"
                       :label="modelLabel(m)" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('agentList.prompt')" required>
          <el-input v-model="form.systemPrompt" type="textarea" :rows="5"
                    :placeholder="t('agentList.promptPh')" />
        </el-form-item>
        <el-form-item :label="t('agentList.temperature')">
          <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 90%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('agentList.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="submit">{{ t('agentList.createPublish') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

interface AgentItem { id: number; agentKey: string; name: string; description: string; status: string
  capability?: { incomplete: boolean; hasKb: boolean; hasTool: boolean; hasSkill: boolean } }
interface ModelItem { modelName: string; displayName: string }

const router = useRouter()
const agents = ref<AgentItem[]>([])
const models = ref<ModelItem[]>([])
const loading = ref(false)
const keyword = ref('')
const createVisible = ref(false)
const creating = ref(false)
const form = reactive({ name: '', description: '', modelName: '', systemPrompt: '', temperature: 0.7 })

async function load() {
  loading.value = true
  try {
    const data = await http.get('/agent', { params: { keyword: keyword.value || undefined, pageSize: 50 } }) as any
    agents.value = data.list || []
  } finally {
    loading.value = false
  }
}

async function loadModels() {
  const data = await http.get('/provider/models') as any
  models.value = (data || []).filter((m: any) => !m.purpose || String(m.purpose).toUpperCase() === 'CHAT')
}

function modelLabel(m: any) {
  const name = m.displayName || m.modelName
  const c = Number(m.contextWindow || 0)
  return c > 0 ? `${name} (${c >= 1048576 ? (c / 1048576) + 'M' : (c / 1024) + 'K'}${t('agentList.contextSuffix')})` : name
}

function openCreate() {
  Object.assign(form, { name: '', description: '', modelName: '', systemPrompt: '', temperature: 0.7 })
  createVisible.value = true
}

async function submit() {
  if (!form.name.trim() || !form.modelName || !form.systemPrompt.trim()) {
    ElMessage.warning(t('agentList.required'))
    return
  }
  creating.value = true
  try {
    const created = await http.post('/agent', {
      name: form.name,
      description: form.description,
      systemPrompt: form.systemPrompt,
      modelConfig: { modelName: form.modelName, temperature: form.temperature }
    }) as any
    // 仅创建草稿并引导去编辑页：发布统一收口到编辑页（走配置检查+预发布门禁），
    // 避免"裸 Agent 绕过门禁直接上线"且断掉配置引导
    createVisible.value = false
    ElMessage.success(t('agentList.createdDraft'))
    router.push(`/agent/edit/${created.id}`)
  } finally {
    creating.value = false
  }
}

async function publish(a: AgentItem) {
  await http.post(`/agent/${a.id}/publish`)
  ElMessage.success(t('agentList.publishedMsg'))
  await load()
}

async function remove(a: AgentItem) {
  await ElMessageBox.confirm(`${t('agentList.deleteQuestion')}${a.name || t('agentList.unnamed')}${t('agentList.deleteQuestionSuffix')}`, t('agentList.deleteTitle'), { type: 'warning' })
  await http.delete(`/agent/${a.id}`)
  ElMessage.success(t('agentList.deleted'))
  await load()
}

function observe(a: AgentItem) {
  router.push(`/observability/${a.id}`)
}

function metrics(a: AgentItem) {
  router.push(`/metrics/${a.id}`)
}

function edit(a: AgentItem) {
  router.push(`/agent/edit/${a.id}`)
}

function enter(a: AgentItem) {
  if (a.status !== 'PUBLISHED') {
    ElMessage.info(t('agentList.draftFirst'))
    return
  }
  router.push(`/chat/${a.id}`)
}

function initials(name: string) {
  return (name || 'A').slice(0, 1)
}

onMounted(() => { load(); loadModels() })
</script>

<style scoped>
.page {
  max-width: 1200px;
  margin: 0 auto;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  margin-bottom: 16px;
}
.loading {
  background: #fff;
  padding: 24px;
  border-radius: 6px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
.card {
  cursor: pointer;
}
.card-head {
  display: flex;
  gap: 12px;
  align-items: center;
}
.avatar {
  background: #409eff;
  color: #fff;
  font-weight: 600;
  flex-shrink: 0;
}
.title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.name {
  font-weight: 600;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.desc {
  color: #909399;
  font-size: 13px;
  margin: 10px 0;
  height: 36px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
}
.card-foot .el-button {
  margin-left: 0;
}
</style>