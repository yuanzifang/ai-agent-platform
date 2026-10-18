<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索 Agent 名称" clearable style="width: 240px"
                @keyup.enter="load" @clear="load" />
      <el-button @click="$router.push('/templates')">从模板创建</el-button>
      <el-button type="primary" @click="openCreate">新建 Agent</el-button>
    </div>

    <div v-if="loading" class="loading"><el-skeleton :rows="3" animated /></div>

    <el-empty v-else-if="agents.length === 0" description="还没有 Agent，点击右上角「新建 Agent」创建一个" />

    <div v-else class="grid">
      <el-card v-for="a in agents" :key="a.id" class="card" shadow="hover" @click="enter(a)">
        <div class="card-head">
          <el-avatar :size="40" class="avatar">{{ initials(a.name) }}</el-avatar>
          <div class="title-wrap">
            <div class="name">{{ a.name || '未命名' }}</div>
            <el-tag size="small" :type="a.status === 'PUBLISHED' ? 'success' : 'info'">
              {{ a.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </el-tag>
          </div>
        </div>
        <div class="desc">{{ a.description || '暂无描述' }}</div>
        <div class="card-foot" @click.stop>
          <el-button size="small" @click="edit(a)">编辑</el-button>
          <el-button size="small" @click="observe(a)">观测</el-button>
          <el-button size="small" @click="metrics(a)">监控</el-button>
          <el-button size="small" :disabled="a.status !== 'PUBLISHED'" @click="enter(a)">对话</el-button>
          <el-button v-if="a.status !== 'PUBLISHED'" size="small" type="success" plain @click="publish(a)">发布</el-button>
          <el-button size="small" type="danger" plain @click="remove(a)">删除</el-button>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="createVisible" title="新建 Agent" width="560px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：售后客服助手" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话说明这个 Agent 做什么" />
        </el-form-item>
        <el-form-item label="模型" required>
          <el-select v-model="form.modelName" placeholder="选择模型" style="width: 100%">
            <el-option v-for="m in models" :key="m.modelName" :value="m.modelName"
                       :label="m.displayName || m.modelName" />
          </el-select>
        </el-form-item>
        <el-form-item label="提示词" required>
          <el-input v-model="form.systemPrompt" type="textarea" :rows="5"
                    placeholder="系统提示词（System Prompt），定义 Agent 的角色与规则" />
        </el-form-item>
        <el-form-item label="温度">
          <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 90%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submit">创建并发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

interface AgentItem { id: number; agentKey: string; name: string; description: string; status: string }
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

function openCreate() {
  Object.assign(form, { name: '', description: '', modelName: '', systemPrompt: '', temperature: 0.7 })
  createVisible.value = true
}

async function submit() {
  if (!form.name.trim() || !form.modelName || !form.systemPrompt.trim()) {
    ElMessage.warning('名称、模型、提示词必填')
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
    await http.post(`/agent/${created.id}/publish`)
    ElMessage.success('已创建并发布')
    createVisible.value = false
    await load()
  } finally {
    creating.value = false
  }
}

async function publish(a: AgentItem) {
  await http.post(`/agent/${a.id}/publish`)
  ElMessage.success('已发布')
  await load()
}

async function remove(a: AgentItem) {
  await ElMessageBox.confirm(`确定删除「${a.name || '未命名'}」？`, '删除确认', { type: 'warning' })
  await http.delete(`/agent/${a.id}`)
  ElMessage.success('已删除')
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
    ElMessage.info('草稿 Agent 请先发布再对话')
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
  justify-content: flex-end;
  gap: 4px;
}
</style>
