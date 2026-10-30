<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.memories') }}</span>
      <span class="sub">{{ t('memory.sub') }}</span>
      <div class="spacer" />
      <el-input v-model="keyword" clearable :placeholder="t('memory.searchPh')" style="width: 200px"
                @keyup.enter="load" @clear="load" />
      <el-button @click="load">{{ t('memory.refresh') }}</el-button>
      <el-button type="primary" @click="openCreate">{{ t('memory.create') }}</el-button>
    </div>

    <div v-if="profile" class="profile-card">
      <div class="profile-title">{{ t('memory.profileTitle') }}</div>
      <div class="profile-summary">{{ profile.summary || t('memory.emptyProfile') }}</div>
      <div class="profile-stats">
        {{ t('memory.total') }}{{ profile.total }}{{ t('memory.autoCount') }}{{ profile.autoCount }}
        <el-button text size="small" @click="openProfileEdit">{{ t('memory.editProfile') }}</el-button>
        <el-button text size="small" @click="copyProfile">{{ t('memory.copyProfile') }}</el-button>
      </div>
      <div class="profile-json-title">{{ t('memory.profileJson') }}</div>
      <pre class="profile-json">{{ profileJson }}</pre>
    </div>

    <el-empty v-if="!loading && rows.length === 0" :description="t('memory.empty')" />
    <div v-else v-loading="loading" class="memory-list">
      <div v-for="m in rows" :key="m.id" class="memory-card">
        <div class="memory-main">
          <div class="memory-content"><MarkdownContent :content="m.content" /></div>
          <div class="memory-meta">
            <el-tag size="small" effect="plain">{{ categoryText(m.category) }}</el-tag>
            <el-tag v-if="m.source === 'AUTO'" size="small" type="success" effect="plain">{{ t('memory.auto') }}</el-tag>
            <span>{{ m.agentId ? `${t('memory.onlyAgent')}${m.agentId}` : t('memory.allAgents') }}</span>
            <span>{{ t('memory.updated') }} {{ fmt(m.updatedAt) }}</span>
          </div>
        </div>
        <div class="memory-ops">
          <el-button size="small" text @click="openEdit(m)">{{ t('memory.edit') }}</el-button>
          <el-button size="small" text type="danger" @click="remove(m)">{{ t('memory.delete') }}</el-button>
        </div>
      </div>
    </div>

    <el-drawer v-model="profileEditVisible" :title="t('memory.editProfileTitle')" size="760px">
      <el-alert type="info" :closable="false" :title="t('memory.editProfileHint')" class="mb12" />
      <el-tabs v-model="profileEditTab">
        <el-tab-pane v-for="cat in profileTabs" :key="cat" :label="categoryText(cat)" :name="cat">
          <div v-for="row in editRowsByCategory(cat)" :key="row.key" class="edit-memory-item">
            <el-input v-model="row.content" type="textarea" :rows="3" maxlength="2000" show-word-limit
                      :placeholder="t('memory.contentPh')" />
            <div class="edit-memory-meta">
              <el-select v-model="row.category" size="small" style="width: 130px">
                <el-option v-for="c in profileTabs" :key="c" :label="categoryText(c)" :value="c" />
              </el-select>
              <el-select v-model="row.agentId" size="small" clearable :placeholder="t('memory.allAgents')" style="width: 220px">
                <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
              </el-select>
              <span class="hint">{{ t('memory.markdownHint') }}</span>
              <div class="spacer" />
              <el-button text size="small" type="danger" @click="removeEditRow(row)">{{ t('memory.delete') }}</el-button>
            </div>
            <div v-if="row.content.trim()" class="edit-preview">
              <MarkdownContent :content="row.content" />
            </div>
          </div>
          <el-button text type="primary" @click="addEditRow(cat)">{{ t('memory.addProfileItem') }}</el-button>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="profileEditVisible = false">{{ t('memory.cancel') }}</el-button>
        <el-button type="primary" :loading="profileEditSaving" @click="saveProfileEdit">{{ t('memory.save') }}</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="dialogVisible" :title="form.id ? t('memory.editTitle') : t('memory.newTitle')" width="520px">
      <el-form label-width="90px">
        <el-form-item :label="t('memory.content')" required>
          <el-input v-model="form.content" type="textarea" :rows="4" maxlength="2000" show-word-limit
                    :placeholder="t('memory.contentPh')" />
        </el-form-item>
        <el-form-item :label="t('memory.category')">
          <el-select v-model="form.category" style="width: 180px">
            <el-option :label="t('memory.fact')" value="FACT" />
            <el-option :label="t('memory.preference')" value="PREFERENCE" />
            <el-option :label="t('memory.profile')" value="PROFILE" />
            <el-option :label="t('memory.other')" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('memory.scope')">
          <el-select v-model="form.agentId" clearable :placeholder="t('memory.allAgents')" style="width: 250px">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('memory.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('memory.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import MarkdownContent from '../components/MarkdownContent.vue'

const { t } = useI18n()

const rows = ref<any[]>([])
const profile = ref<any>(null)
const agents = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const dialogVisible = ref(false)
const form = reactive({ id: 0, content: '', category: 'FACT', agentId: null as number | null })
const route = useRoute()
const profileJson = computed(() => JSON.stringify(profile.value || {}, null, 2))
const filterAgentId = ref<number | null>(route.query.agentId ? Number(route.query.agentId) : null)
const profileTabs = ['PROFILE', 'PREFERENCE', 'FACT', 'OTHER']
const profileEditVisible = ref(false)
const profileEditSaving = ref(false)
const profileEditTab = ref('PROFILE')
const editRows = ref<any[]>([])
const removedIds = ref<number[]>([])

onMounted(async () => {
  const data = await http.get('/agent', { params: { pageSize: 100 } }) as any
  agents.value = (data.list || []).filter((a: any) => a.status !== 'DELETED')
  await load()
})

async function load() {
  loading.value = true
  try {
    const [data, profileData] = await Promise.all([
      http.get('/memories', { params: { keyword: keyword.value || undefined, agentId: filterAgentId.value || undefined } }),
      http.get('/memories/profile')
    ]) as any[]
    rows.value = data.list || []
    profile.value = profileData
  } finally {
    loading.value = false
  }
}

async function openProfileEdit() {
  const data = await http.get('/memories') as any
  editRows.value = (data.list || []).map((m: any) => ({
    key: m.id ? `m-${m.id}` : `n-${Date.now()}-${Math.random()}`,
    id: m.id || 0,
    content: m.content || '',
    category: m.category || 'FACT',
    agentId: m.agentId || null
  }))
  removedIds.value = []
  profileEditTab.value = editRows.value.some(r => r.category === 'PROFILE') ? 'PROFILE' : 'FACT'
  profileEditVisible.value = true
}

function editRowsByCategory(category: string) {
  return editRows.value.filter(r => r.category === category)
}

function addEditRow(category: string) {
  editRows.value.push({
    key: `n-${Date.now()}-${Math.random()}`,
    id: 0, content: '', category, agentId: null
  })
}

function removeEditRow(row: any) {
  if (row.id) removedIds.value.push(row.id)
  editRows.value = editRows.value.filter(r => r.key !== row.key)
}

async function saveProfileEdit() {
  const blank = editRows.value.find(r => !r.content.trim())
  if (blank) {
    ElMessage.warning(t('memory.contentRequired'))
    return
  }
  profileEditSaving.value = true
  try {
    for (const id of removedIds.value) await http.delete(`/memories/${id}`)
    for (const row of editRows.value) {
      const payload = { content: row.content.trim(), category: row.category, agentId: row.agentId || null }
      if (row.id) await http.put(`/memories/${row.id}`, payload)
      else await http.post('/memories', payload)
    }
    ElMessage.success(t('memory.saved'))
    profileEditVisible.value = false
    await load()
  } finally { profileEditSaving.value = false }
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
  if (!form.content.trim()) { ElMessage.warning(t('memory.contentRequired')); return }
  saving.value = true
  try {
    const payload = { content: form.content.trim(), category: form.category, agentId: form.agentId }
    if (form.id) await http.put(`/memories/${form.id}`, payload)
    else await http.post('/memories', payload)
    ElMessage.success(t('memory.saved'))
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  try {
    await ElMessageBox.confirm(t('memory.deleteConfirm'), t('memory.deleteTitle'),
      { type: 'warning', confirmButtonText: t('memory.delete'), cancelButtonText: t('memory.cancel') })
  } catch { return }
  await http.delete(`/memories/${row.id}`)
  ElMessage.success(t('memory.deleted'))
  await load()
}

async function copyProfile() {
  try {
    await navigator.clipboard.writeText(profileJson.value)
    ElMessage.success(t('memory.copied'))
  } catch { ElMessage.error(t('memory.copyFail')) }
}

function categoryText(category: string) {
  return ({ FACT: t('memory.fact'), PREFERENCE: t('memory.preference'), PROFILE: t('memory.profile'), OTHER: t('memory.other') } as any)[category] || category
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
.profile-stats { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #909399; margin-top: 4px; }
.profile-json-title { font-size: 12px; color: #909399; margin-top: 10px; }
.profile-json {
  margin: 4px 0 0; padding: 10px; max-height: 260px; overflow: auto;
  background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 4px;
  font-size: 12px; line-height: 1.55; color: #303133; white-space: pre-wrap;
}
.memory-list { display: flex; flex-direction: column; gap: 8px; }
.memory-card {
  display: flex; align-items: flex-start; gap: 12px;
  border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 12px;
}
.memory-main { flex: 1; min-width: 0; }
.memory-content { font-size: 14px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.memory-meta { display: flex; align-items: center; gap: 10px; margin-top: 5px; font-size: 12px; color: #909399; }
.memory-ops { flex-shrink: 0; }
.mb12 { margin-bottom: 12px; }
.edit-memory-item { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px; margin-bottom: 10px; }
.edit-memory-meta { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.edit-preview { margin-top: 8px; padding: 8px 10px; background: #fafbfc; border-radius: 4px; }
</style>