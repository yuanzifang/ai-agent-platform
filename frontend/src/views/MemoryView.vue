<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.memories') }}</span>
      <span class="sub">用户可见、可编辑的事实；对话时自动注入（最多 10 条 / 2000 字）</span>
      <div class="spacer" />
      <el-input v-model="keyword" clearable placeholder="搜索记忆" style="width: 200px"
                @keyup.enter="load" @clear="load" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新增记忆</el-button>
    </div>

    <div v-if="profile" class="profile-card">
      <div class="profile-title">用户画像</div>
      <div class="profile-summary">{{ profile.summary || '暂无画像，聊天后会自动生成，也可手动添加' }}</div>
      <div class="profile-stats">
        共 {{ profile.total }} 条 · 自动生成 {{ profile.autoCount }} 条
      </div>
    </div>

    <el-empty v-if="!loading && rows.length === 0" description="还没有长期记忆。可记录称呼、偏好、长期目标等事实。" />
    <div v-else v-loading="loading" class="memory-list">
      <div v-for="m in rows" :key="m.id" class="memory-card">
        <div class="memory-main">
          <div class="memory-content">{{ m.content }}</div>
          <div class="memory-meta">
            <el-tag size="small" effect="plain">{{ categoryText(m.category) }}</el-tag>
            <el-tag v-if="m.source === 'AUTO'" size="small" type="success" effect="plain">自动</el-tag>
            <span>{{ m.agentId ? `仅 Agent #${m.agentId}` : '全部 Agent' }}</span>
            <span>更新 {{ fmt(m.updatedAt) }}</span>
          </div>
        </div>
        <div class="memory-ops">
          <el-button size="small" text @click="openEdit(m)">编辑</el-button>
          <el-button size="small" text type="danger" @click="remove(m)">删除</el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑记忆' : '新增记忆'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="4" maxlength="2000" show-word-limit
                    placeholder="如：用户希望被称为「老王」；常住上海；偏好简洁回答" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" style="width: 180px">
            <el-option label="事实" value="FACT" />
            <el-option label="偏好" value="PREFERENCE" />
            <el-option label="画像" value="PROFILE" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用范围">
          <el-select v-model="form.agentId" clearable placeholder="全部 Agent" style="width: 250px">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const rows = ref<any[]>([])
const profile = ref<any>(null)
const agents = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const dialogVisible = ref(false)
const form = reactive({ id: 0, content: '', category: 'FACT', agentId: null as number | null })

onMounted(async () => {
  const data = await http.get('/agent', { params: { pageSize: 100 } }) as any
  agents.value = (data.list || []).filter((a: any) => a.status !== 'DELETED')
  await load()
})

async function load() {
  loading.value = true
  try {
    const [data, profileData] = await Promise.all([
      http.get('/memories', { params: { keyword: keyword.value || undefined } }),
      http.get('/memories/profile')
    ]) as any[]
    rows.value = data.list || []
    profile.value = profileData
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { id: 0, content: '', category: 'FACT', agentId: null })
  dialogVisible.value = true
}

function openEdit(row: any) {
  Object.assign(form, {
    id: row.id, content: row.content, category: row.category || 'FACT', agentId: row.agentId || null
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.content.trim()) { ElMessage.warning('记忆内容不能为空'); return }
  saving.value = true
  try {
    const payload = { content: form.content.trim(), category: form.category, agentId: form.agentId }
    if (form.id) await http.put(`/memories/${form.id}`, payload)
    else await http.post('/memories', payload)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  try {
    await ElMessageBox.confirm('删除后该记忆不再注入对话，确认？', '删除记忆',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  await http.delete(`/memories/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

function categoryText(category: string) {
  return ({ FACT: '事实', PREFERENCE: '偏好', PROFILE: '画像', OTHER: '其他' } as any)[category] || category
}

function fmt(value?: string) {
  return value ? String(value).replace('T', ' ').slice(5, 16) : '—'
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.profile-card {
  border: 1px solid #e4e7ed; border-radius: 6px; padding: 10px 12px;
  margin-bottom: 12px; background: #fafbfc;
}
.profile-title { font-weight: 600; font-size: 13px; margin-bottom: 4px; }
.profile-summary { font-size: 14px; line-height: 1.6; }
.profile-stats { font-size: 12px; color: #909399; margin-top: 4px; }
.memory-list { display: flex; flex-direction: column; gap: 8px; }
.memory-card {
  display: flex; align-items: flex-start; gap: 12px;
  border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 12px;
}
.memory-main { flex: 1; min-width: 0; }
.memory-content { font-size: 14px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.memory-meta { display: flex; align-items: center; gap: 10px; margin-top: 5px; font-size: 12px; color: #909399; }
.memory-ops { flex-shrink: 0; }
</style>